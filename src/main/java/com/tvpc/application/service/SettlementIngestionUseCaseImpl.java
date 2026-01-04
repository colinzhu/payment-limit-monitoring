package com.tvpc.application.service;

import com.tvpc.application.port.in.SettlementIngestionUseCase;
import com.tvpc.application.port.out.ConfigurationRepository;
import com.tvpc.application.port.out.RunningTotalRepository;
import com.tvpc.application.port.out.SettlementRepository;
import com.tvpc.domain.event.SettlementEvent;
import com.tvpc.domain.model.BusinessStatus;
import com.tvpc.domain.model.Settlement;
import com.tvpc.domain.model.SettlementDirection;
import com.tvpc.domain.model.SettlementType;
import io.vertx.core.Future;
import io.vertx.jdbcclient.JDBCPool;
import io.vertx.sqlclient.SqlConnection;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Application service implementing the settlement ingestion use case
 * Implements the 5-step ingestion flow
 */
@Slf4j
@RequiredArgsConstructor
public class SettlementIngestionUseCaseImpl implements SettlementIngestionUseCase {

    private final SettlementValidator validator;
    private final SettlementRepository settlementRepository;
    private final RunningTotalRepository runningTotalRepository;
    private final JDBCPool jdbcPool;
    private final ConfigurationRepository configurationRepository;

    @Override
    public Future<Long> processSettlement(SettlementIngestionCommand command) {
        log.info("Processing settlement: {}", command.settlementId());

        // Step 0: Validate
        ValidationResult validation = validator.validate(command);
        if (!validation.isValid()) {
            log.warn("Validation failed for settlement {}: {}", command.settlementId(), validation.errors());
            return Future.failedFuture(new IllegalArgumentException("Validation failed: " + validation.errors()));
        }

        // Convert to domain object
        Settlement settlement = convertToDomain(command);

        return jdbcPool.withTransaction(connection -> executeIngestionSteps(connection, settlement));
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
                            .map(oldCounterparty -> new CounterpartyChangeResult(seqId, oldCounterparty));
                })
                .compose(result -> {
                    log.debug("Step 4: Generate events and calculate running totals");

                    // Step 4 & 5: Generate events and directly calculate running totals
                    List<SettlementEvent> events = generateEvents(settlement, result.seqId(), result.oldCounterparty());

                    // Step 5: Loop through events and calculate running totals synchronously
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

                    return runningTotalFuture.map(result.seqId());
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

    // Step 4: Generate Events
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
     * Convert command to domain object
     */
    private Settlement convertToDomain(SettlementIngestionCommand command) {
        return Settlement.builder()
                .settlementId(command.settlementId())
                .settlementVersion(command.settlementVersion())
                .pts(command.pts())
                .processingEntity(command.processingEntity())
                .counterpartyId(command.counterpartyId())
                .valueDate(LocalDate.parse(command.valueDate()))
                .currency(command.currency())
                .amount(command.amount())
                .businessStatus(BusinessStatus.fromValue(command.businessStatus()))
                .direction(SettlementDirection.fromValue(command.direction()))
                .settlementType(SettlementType.fromValue(command.settlementType()))
                .isOld(false)
                .build();
    }

    // Helper record for counterparty change result
    private record CounterpartyChangeResult(Long seqId, Optional<String> oldCounterparty) {}
}
