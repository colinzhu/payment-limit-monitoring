package com.tvpc.processor;

import com.tvpc.event.SettlementEvent;
import com.tvpc.repository.ExchangeRateRepository;
import com.tvpc.repository.RunningTotalRepository;
import com.tvpc.repository.SettlementRepository;
import io.vertx.core.AbstractVerticle;
import io.vertx.core.Future;
import io.vertx.core.Promise;
import io.vertx.sqlclient.SqlClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;

/**
 * Single-threaded event processor for running total calculations
 * Eliminates race conditions by processing events sequentially
 */
public class RunningTotalProcessorVerticle extends AbstractVerticle {
    private static final Logger log = LoggerFactory.getLogger(RunningTotalProcessorVerticle.class);

    private final SqlClient sqlClient;
    private final SettlementRepository settlementRepository;
    private final RunningTotalRepository runningTotalRepository;
    private final ExchangeRateRepository exchangeRateRepository;

    public RunningTotalProcessorVerticle(
            SqlClient sqlClient,
            SettlementRepository settlementRepository,
            RunningTotalRepository runningTotalRepository,
            ExchangeRateRepository exchangeRateRepository
    ) {
        this.sqlClient = sqlClient;
        this.settlementRepository = settlementRepository;
        this.runningTotalRepository = runningTotalRepository;
        this.exchangeRateRepository = exchangeRateRepository;
    }

    @Override
    public void start(Promise<Void> startPromise) {
        log.info("Starting Running Total Processor Verticle...");

        // Register event bus consumer
        vertx.eventBus().consumer("settlement.events", message -> {
            SettlementEvent event = (SettlementEvent) message.body();

            log.debug("Received event for processing: {}", event);

            // Process the event
            processEvent(event)
                    .onSuccess(v -> {
                        log.debug("Successfully processed event: {}", event);
                        message.reply("OK");
                    })
                    .onFailure(error -> {
                        log.error("Failed to process event: {}", event, error);
                        message.fail(500, error.getMessage());
                    });
        });

        log.info("Running Total Processor Verticle started successfully");
        startPromise.complete();
    }

    /**
     * Process a settlement event - calculate running total for the group
     */
    private Future<Void> processEvent(SettlementEvent event) {
        log.info("Processing running total for group: {}", event.getGroupKey());

        // Get database connection
        if (!(sqlClient instanceof io.vertx.jdbcclient.JDBCPool)) {
            return Future.failedFuture("SqlClient must be JDBCPool instance");
        }

        io.vertx.jdbcclient.JDBCPool pool = (io.vertx.jdbcclient.JDBCPool) sqlClient;

        return pool.getConnection()
                .compose(connection -> {
                    Promise<Void> promise = Promise.promise();

                    // Get all settlements for the group (with filtering)
                    settlementRepository.findByGroupWithFilters(
                            event.getPts(),
                            event.getProcessingEntity(),
                            event.getCounterpartyId(),
                            event.getValueDate().toString(),
                            event.getSeqId(),
                            connection
                    ).compose(settlements -> {
                        log.debug("Found {} settlements for calculation", settlements.size());

                        if (settlements.isEmpty()) {
                            // No settlements to calculate - set total to 0
                            return runningTotalRepository.updateRunningTotal(
                                    event.getPts(),
                                    event.getProcessingEntity(),
                                    event.getCounterpartyId(),
                                    event.getValueDate(),
                                    BigDecimal.ZERO,
                                    event.getSeqId(),
                                    connection
                            );
                        }

                        // Calculate total in USD
                        BigDecimal totalUSD = BigDecimal.ZERO;

                        // Process each settlement
                        return processSettlementsRecursively(
                                settlements,
                                0,
                                totalUSD,
                                event,
                                connection
                        );

                    }).onSuccess(v -> {
                        connection.close();
                        promise.complete();
                    }).onFailure(error -> {
                        connection.close();
                        promise.fail(error);
                    });

                    return promise.future();
                });
    }

    /**
     * Recursive processing of settlements to handle async exchange rate lookups
     */
    private Future<Void> processSettlementsRecursively(
            List<com.tvpc.domain.Settlement> settlements,
            int index,
            BigDecimal runningTotal,
            SettlementEvent event,
            io.vertx.sqlclient.SqlConnection connection
    ) {
        if (index >= settlements.size()) {
            // All settlements processed, update running total
            return runningTotalRepository.updateRunningTotal(
                    event.getPts(),
                    event.getProcessingEntity(),
                    event.getCounterpartyId(),
                    event.getValueDate(),
                    runningTotal,
                    event.getSeqId(),
                    connection
            );
        }

        com.tvpc.domain.Settlement settlement = settlements.get(index);

        // Get exchange rate
        return exchangeRateRepository.getRate(settlement.getCurrency())
                .compose(rateOpt -> {
                    if (rateOpt.isEmpty()) {
                        return Future.failedFuture("No exchange rate found for currency: " + settlement.getCurrency());
                    }

                    BigDecimal rate = rateOpt.get();
                    BigDecimal usdAmount = settlement.getAmount().multiply(rate).setScale(2, RoundingMode.HALF_UP);

                    log.debug("Settlement {}: {} {} = {} USD (rate: {})",
                            settlement.getSettlementId(),
                            settlement.getAmount(),
                            settlement.getCurrency(),
                            usdAmount,
                            rate);

                    // Continue with next settlement
                    return processSettlementsRecursively(
                            settlements,
                            index + 1,
                            runningTotal.add(usdAmount),
                            event,
                            connection
                    );
                });
    }
}
