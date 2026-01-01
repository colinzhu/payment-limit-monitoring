package com.tvpc.repository;

import io.vertx.core.Future;
import io.vertx.sqlclient.SqlConnection;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * Repository for Running Total operations
 */
public interface RunningTotalRepository {

    /**
     * Update or insert running total for a group
     * @param pts Primary Trading System
     * @param processingEntity Business unit
     * @param counterpartyId Counterparty identifier
     * @param valueDate Settlement value date
     * @param runningTotal Calculated total in USD
     * @param refId Sequence ID used for calculation
     * @param connection Database connection
     * @return Future indicating completion
     */
    Future<Void> updateRunningTotal(
            String pts,
            String processingEntity,
            String counterpartyId,
            LocalDate valueDate,
            BigDecimal runningTotal,
            Long refId,
            SqlConnection connection
    );

    /**
     * Get current running total for a group
     * @param pts Primary Trading System
     * @param processingEntity Business unit
     * @param counterpartyId Counterparty identifier
     * @param valueDate Settlement value date
     * @return Future with optional running total
     */
    Future<java.util.Optional<RunningTotal>> getRunningTotal(
            String pts,
            String processingEntity,
            String counterpartyId,
            LocalDate valueDate
    );

    /**
     * Calculate and save running total in a single SQL operation
     * Combines query, calculation, and update into one database call
     * @param pts Primary Trading System
     * @param processingEntity Business unit
     * @param counterpartyId Counterparty identifier
     * @param valueDate Settlement value date
     * @param maxSeqId Maximum sequence ID to include
     * @param connection Database connection
     * @return Future indicating completion
     */
    Future<Void> calculateAndSaveRunningTotal(
            String pts,
            String processingEntity,
            String counterpartyId,
            LocalDate valueDate,
            Long maxSeqId,
            SqlConnection connection
    );

    /**
     * Inner class for running total data
     */
    class RunningTotal {
        private final BigDecimal total;
        private final Long refId;

        public RunningTotal(BigDecimal total, Long refId) {
            this.total = total;
            this.refId = refId;
        }

        public BigDecimal getTotal() {
            return total;
        }

        public Long getRefId() {
            return refId;
        }
    }
}
