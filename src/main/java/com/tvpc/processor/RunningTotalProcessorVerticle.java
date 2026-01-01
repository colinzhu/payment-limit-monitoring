package com.tvpc.processor;

import com.tvpc.event.SettlementEvent;
import com.tvpc.repository.RunningTotalRepository;
import io.vertx.core.AbstractVerticle;
import io.vertx.core.Future;
import io.vertx.core.Promise;
import io.vertx.sqlclient.SqlClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Single-threaded event processor for running total calculations
 * Eliminates race conditions by processing events sequentially
 */
public class RunningTotalProcessorVerticle extends AbstractVerticle {
    private static final Logger log = LoggerFactory.getLogger(RunningTotalProcessorVerticle.class);

    private final SqlClient sqlClient;
    private final RunningTotalRepository runningTotalRepository;

    public RunningTotalProcessorVerticle(
            SqlClient sqlClient,
            RunningTotalRepository runningTotalRepository
    ) {
        this.sqlClient = sqlClient;
        this.runningTotalRepository = runningTotalRepository;
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
     * OPTIMIZED: Uses single SQL to calculate and save running total
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
                    // OPTIMIZED: Single database operation combines query, calculation, and update
                    return runningTotalRepository.calculateAndSaveRunningTotal(
                            event.getPts(),
                            event.getProcessingEntity(),
                            event.getCounterpartyId(),
                            event.getValueDate(),
                            event.getSeqId(),
                            connection
                    ).onSuccess(v -> {
                        connection.close();
                        log.debug("Successfully processed event: {}", event);
                    }).onFailure(error -> {
                        connection.close();
                        log.error("Failed to process event: {}", event, error);
                    });
                });
    }
}
