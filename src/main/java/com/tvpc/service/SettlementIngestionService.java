package com.tvpc.service;

import com.tvpc.domain.BusinessStatus;
import com.tvpc.domain.Settlement;
import com.tvpc.domain.SettlementDirection;
import com.tvpc.domain.SettlementType;
import com.tvpc.dto.SettlementRequest;
import com.tvpc.dto.ValidationResult;
import com.tvpc.event.SettlementEvent;
import com.tvpc.repository.SettlementRepository;
import com.tvpc.repository.RunningTotalRepository;
import com.tvpc.repository.ActivityRepository;
import com.tvpc.validation.SettlementValidator;
import io.vertx.core.Future;
import io.vertx.sqlclient.SqlClient;
import io.vertx.sqlclient.SqlConnection;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Main service for settlement ingestion - implements the 5-step flow
 */
public class SettlementIngestionService {
    private static final Logger log = LoggerFactory.getLogger(SettlementIngestionService.class);

    private final SettlementValidator validator;
    private final SettlementRepository settlementRepository;
    private final RunningTotalRepository runningTotalRepository;
    private final ActivityRepository activityRepository;
    private final SqlClient sqlClient;
    private final ConfigurationService configurationService;

    public SettlementIngestionService(
            SettlementValidator validator,
            SettlementRepository settlementRepository,
            RunningTotalRepository runningTotalRepository,
            ActivityRepository activityRepository,
            SqlClient sqlClient,
            ConfigurationService configurationService
    ) {
        this.validator = validator;
        this.settlementRepository = settlementRepository;
        this.runningTotalRepository = runningTotalRepository;
        this.activityRepository = activityRepository;
        this.sqlClient = sqlClient;
        this.configurationService = configurationService;
    }

    /**
     * Main entry point - processes a settlement request through the 5-step flow
     * All steps are executed within a single database transaction
     */
    public Future<Long> processSettlement(SettlementRequest request) {
        log.info("Processing settlement: {}", request.getSettlementId());

        // Step 0: Validate
        ValidationResult validation = validator.validate(request);
        if (!validation.isValid()) {
            log.warn("Validation failed for settlement {}: {}", request.getSettlementId(), validation.getErrors());
            return Future.failedFuture(new IllegalArgumentException("Validation failed: " + validation.getErrors()));
        }

        // Convert to domain object
        Settlement settlement = convertToDomain(request);

        // Get database connection and execute all steps in a transaction
        // Note: sqlClient needs to be JDBCPool for getConnection() to work
        if (!(sqlClient instanceof io.vertx.jdbcclient.JDBCPool)) {
            return Future.failedFuture("SqlClient must be JDBCPool instance for transaction support");
        }

        io.vertx.jdbcclient.JDBCPool pool = (io.vertx.jdbcclient.JDBCPool) sqlClient;

        return pool.getConnection()
                .compose(connection -> {
                    // Begin transaction
                    return connection.begin()
                            .compose(tx -> {
                                // Execute all 5 steps in sequence
                                return executeIngestionSteps(connection, settlement)
                                        .compose(seqId -> {
                                            // Commit transaction
                                            return tx.commit().map(seqId);
                                        })
                                        .onFailure(throwable -> {
                                            // Rollback on any failure
                                            log.error("Transaction failed, rolling back: {}", throwable.getMessage());
                                            tx.rollback();
                                        });
                            });
                });
    }

    /**
     * Execute the 5-step ingestion flow
     */
    private Future<Long> executeIngestionSteps(SqlConnection connection, Settlement settlement) {
        // Step 1: Save Settlement
        return saveSettlement(settlement, connection)
                .compose(seqId -> {
                    log.debug("Step 1: Saved settlement with seqId: {}", seqId);

                    // Step 2: Mark Old Versions
                    return markOldVersions(settlement, connection)
                            .map(seqId);
                })
                .compose(seqId -> {
                    log.debug("Step 2: Marked old versions");

                    // Step 3: Detect Counterparty Changes
                    return detectCounterpartyChange(settlement, seqId, connection)
                            .map(oldCounterparty -> {
                                log.debug("Step 3: Detected counterparty change: {}", oldCounterparty);
                                return new CounterpartyChangeResult(seqId, oldCounterparty);
                            });
                })
                .compose(result -> {
                    log.debug("Step 4: Generate events and calculate running totals");

                    // Step 4 & 5: Generate events and directly calculate running totals
                    // Build event list (1 or 2 events depending on counterparty change)
                    List<SettlementEvent> events = generateEvents(settlement, result.seqId, result.oldCounterparty);

                    // Step 5: Loop through events and calculate running totals synchronously
                    // This replaces event dispatch - all calculation happens within the transaction
                    Future<Void> runningTotalFuture = Future.succeededFuture();

                    for (SettlementEvent event : events) {
                        runningTotalFuture = runningTotalFuture.compose(v ->
                            calculateRunningTotalForGroup(
                                    event.getPts(),
                                    event.getProcessingEntity(),
                                    event.getCounterpartyId(),
                                    event.getValueDate(),
                                    event.getRefId(),
                                    connection
                            )
                        );
                    }

                    return runningTotalFuture.map(result.seqId);
                })
                .onSuccess(seqId -> log.info("Successfully processed settlement {} with seqId: {}", settlement.getSettlementId(), seqId))
                .onFailure(error -> log.error("Failed to process settlement {}: {}", settlement.getSettlementId(), error.getMessage()));
    }

    // Step 1: Save Settlement
    private Future<Long> saveSettlement(Settlement settlement, SqlConnection connection) {
        return settlementRepository.save(settlement, connection)
                .onSuccess(seqId -> log.debug("saveSettlement returned seqId: {}", seqId))
                .onFailure(error -> log.error("saveSettlement failed: {}", error.getMessage()));
    }

    // Step 2: Mark Old Versions
    private Future<Void> markOldVersions(Settlement settlement, SqlConnection connection) {
        return settlementRepository.markOldVersions(
                settlement.getSettlementId(),
                settlement.getPts(),
                settlement.getProcessingEntity(),
                connection
        );
    }

    // Step 3: Detect Counterparty Changes
    private Future<Optional<String>> detectCounterpartyChange(Settlement settlement, Long seqId, SqlConnection connection) {
        return settlementRepository.findPreviousCounterparty(
                settlement.getSettlementId(),
                settlement.getPts(),
                settlement.getProcessingEntity(),
                seqId,
                connection
        );
    }

    // Step 4: Generate Events (returns list for synchronous processing)
    private List<SettlementEvent> generateEvents(Settlement settlement, Long seqId, Optional<String> oldCounterparty) {
        List<SettlementEvent> events = new ArrayList<>();

        // Default: 1 event for current group
        SettlementEvent currentEvent = new SettlementEvent(
                settlement.getPts(),
                settlement.getProcessingEntity(),
                settlement.getCounterpartyId(),
                settlement.getValueDate(),
                seqId
        );
        events.add(currentEvent);

        // If counterparty changed, also trigger recalculation for old group
        if (oldCounterparty.isPresent() && !oldCounterparty.get().equals(settlement.getCounterpartyId())) {
            SettlementEvent oldEvent = new SettlementEvent(
                    settlement.getPts(),
                    settlement.getProcessingEntity(),
                    oldCounterparty.get(),
                    settlement.getValueDate(),
                    seqId
            );
            events.add(oldEvent);
            log.info("Counterparty changed from {} to {}, triggering recalculation for both groups",
                    oldCounterparty.get(), settlement.getCounterpartyId());
        }

        return events;
    }

    // Step 5: Calculate running total for a specific group
    // OPTIMIZED: Uses single SQL to calculate and save running total
    private Future<Void> calculateRunningTotalForGroup(
            String pts,
            String processingEntity,
            String counterpartyId,
            LocalDate valueDate,
            Long seqId,
            SqlConnection connection
    ) {
        log.debug("Calculating running total for group (pts={}, pe={}, cp={}, vd={}, seqId={})",
                pts, processingEntity, counterpartyId, valueDate, seqId);

        // OPTIMIZED: Single database operation combines query, calculation, and update
        return runningTotalRepository.calculateAndSaveRunningTotal(
                pts,
                processingEntity,
                counterpartyId,
                valueDate,
                seqId,
                connection
        );
    }

    /**
     * Convert DTO to domain object
     */
    private Settlement convertToDomain(SettlementRequest request) {
        return new Settlement(
                request.getSettlementId(),
                request.getSettlementVersion(),
                request.getPts(),
                request.getProcessingEntity(),
                request.getCounterpartyId(),
                LocalDate.parse(request.getValueDate()),
                request.getCurrency(),
                request.getAmount(),
                BusinessStatus.fromValue(request.getBusinessStatus()),
                SettlementDirection.fromValue(request.getDirection()),
                SettlementType.fromValue(request.getSettlementType())
        );
    }

    // Helper class for counterparty change result
    private static class CounterpartyChangeResult {
        final Long seqId;
        final Optional<String> oldCounterparty;

        CounterpartyChangeResult(Long seqId, Optional<String> oldCounterparty) {
            this.seqId = seqId;
            this.oldCounterparty = oldCounterparty;
        }
    }
}
