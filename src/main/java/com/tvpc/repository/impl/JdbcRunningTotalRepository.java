package com.tvpc.repository.impl;

import com.tvpc.repository.RunningTotalRepository;
import io.vertx.core.Future;
import io.vertx.core.Promise;
import io.vertx.sqlclient.SqlClient;
import io.vertx.sqlclient.SqlConnection;
import io.vertx.sqlclient.Tuple;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * JDBC implementation of RunningTotalRepository
 */
public class JdbcRunningTotalRepository implements RunningTotalRepository {

    private final SqlClient sqlClient;

    public JdbcRunningTotalRepository(SqlClient sqlClient) {
        this.sqlClient = sqlClient;
    }

    @Override
    public Future<Void> updateRunningTotal(
            String pts,
            String processingEntity,
            String counterpartyId,
            LocalDate valueDate,
            BigDecimal runningTotal,
            Long refId,
            SqlConnection connection
    ) {
        Promise<Void> promise = Promise.promise();

        LocalDateTime now = LocalDateTime.now();

        // Log the parameters for debugging
        System.out.println("DEBUG updateRunningTotal: pts=" + pts +
            ", pe=" + processingEntity +
            ", cp=" + counterpartyId +
            ", vd=" + valueDate +
            ", total=" + runningTotal +
            ", refId=" + refId);

        // Use MERGE for Oracle (UPSERT pattern)
        // Parameters:
        // 1-6: For USING SELECT (PTS, PE, CP, VD, RUNNING_TOTAL, REF_ID)
        // 7: For WHEN MATCHED UPDATE (UPDATE_TIME) - only if REF_ID >= existing
        // 8-9: For WHEN NOT MATCHED INSERT (CREATE_TIME, UPDATE_TIME)
        // Note: REF_ID check prevents stale updates from out-of-order events
        // Oracle requires CASE in SET clause for conditional updates based on src values
        String sql = "MERGE INTO RUNNING_TOTAL rt " +
                "USING (SELECT ? as PTS, ? as PROCESSING_ENTITY, ? as COUNTERPARTY_ID, ? as VALUE_DATE, ? as RUNNING_TOTAL, ? as REF_ID FROM DUAL) src " +
                "ON (rt.PTS = src.PTS AND rt.PROCESSING_ENTITY = src.PROCESSING_ENTITY AND rt.COUNTERPARTY_ID = src.COUNTERPARTY_ID AND rt.VALUE_DATE = src.VALUE_DATE) " +
                "WHEN MATCHED THEN UPDATE SET " +
                "rt.RUNNING_TOTAL = CASE WHEN src.REF_ID >= rt.REF_ID THEN src.RUNNING_TOTAL ELSE rt.RUNNING_TOTAL END, " +
                "rt.REF_ID = CASE WHEN src.REF_ID >= rt.REF_ID THEN src.REF_ID ELSE rt.REF_ID END, " +
                "rt.UPDATE_TIME = CASE WHEN src.REF_ID >= rt.REF_ID THEN ? ELSE rt.UPDATE_TIME END " +
                "WHEN NOT MATCHED THEN INSERT (PTS, PROCESSING_ENTITY, COUNTERPARTY_ID, VALUE_DATE, RUNNING_TOTAL, REF_ID, CREATE_TIME, UPDATE_TIME) " +
                "VALUES (src.PTS, src.PROCESSING_ENTITY, src.COUNTERPARTY_ID, src.VALUE_DATE, src.RUNNING_TOTAL, src.REF_ID, ?, ?)";

        Tuple params = Tuple.of(
                pts,                    // 1
                processingEntity,       // 2
                counterpartyId,         // 3
                valueDate,              // 4
                runningTotal,           // 5
                refId,                  // 6
                now,                    // 7: UPDATE_TIME for MATCHED
                now,                    // 8: CREATE_TIME for NOT MATCHED
                now                     // 9: UPDATE_TIME for NOT MATCHED
        );

        System.out.println("DEBUG SQL: " + sql);
        System.out.println("DEBUG params: " + params);

        connection.preparedQuery(sql)
                .execute(params)
                .onSuccess(result -> {
                    System.out.println("DEBUG: updateRunningTotal succeeded");
                    promise.complete();
                })
                .onFailure(error -> {
                    System.err.println("DEBUG: updateRunningTotal failed: " + error.getMessage());
                    promise.fail(error);
                });

        return promise.future();
    }

    @Override
    public Future<java.util.Optional<RunningTotalRepository.RunningTotal>> getRunningTotal(
            String pts,
            String processingEntity,
            String counterpartyId,
            LocalDate valueDate
    ) {
        Promise<java.util.Optional<RunningTotalRepository.RunningTotal>> promise = Promise.promise();

        String sql = "SELECT RUNNING_TOTAL, REF_ID FROM RUNNING_TOTAL " +
                "WHERE PTS = ? AND PROCESSING_ENTITY = ? AND COUNTERPARTY_ID = ? AND VALUE_DATE = ?";

        Tuple params = Tuple.of(pts, processingEntity, counterpartyId, valueDate);

        sqlClient.preparedQuery(sql)
                .execute(params)
                .onSuccess(result -> {
                    if (result.size() > 0) {
                        var row = result.iterator().next();
                        RunningTotalRepository.RunningTotal runningTotal = new RunningTotalRepository.RunningTotal(
                                row.getBigDecimal("RUNNING_TOTAL"), row.getLong("REF_ID")
                        );
                        promise.complete(java.util.Optional.of(runningTotal));
                    } else {
                        promise.complete(java.util.Optional.empty());
                    }
                })
                .onFailure(promise::fail);

        return promise.future();
    }

    @Override
    public Future<Void> calculateAndSaveRunningTotal(
            String pts,
            String processingEntity,
            String counterpartyId,
            LocalDate valueDate,
            Long maxSeqId,
            SqlConnection connection
    ) {
        Promise<Void> promise = Promise.promise();

        // Log the parameters for debugging
        System.out.println("DEBUG calculateAndSaveRunningTotal: pts=" + pts +
            ", pe=" + processingEntity +
            ", cp=" + counterpartyId +
            ", vd=" + valueDate +
            ", maxSeqId=" + maxSeqId);

        // Combined SQL: Calculates running total from settlements and updates RUNNING_TOTAL in one operation
        // Uses MERGE for Oracle (UPSERT pattern)
        // Key optimization: Single round-trip, database-side calculation, atomic operation
        String sql = "MERGE INTO RUNNING_TOTAL rt " +
                "USING ( " +
                "  SELECT " +
                "    ? as PTS, " +
                "    ? as PROCESSING_ENTITY, " +
                "    ? as COUNTERPARTY_ID, " +
                "    ? as VALUE_DATE, " +
                "    COALESCE(SUM(s.AMOUNT * COALESCE(r.RATE_TO_USD, 1.0)), 0) as RUNNING_TOTAL, " +
                "    ? as REF_ID " +
                "  FROM SETTLEMENT s " +
                "  LEFT JOIN EXCHANGE_RATE r ON s.CURRENCY = r.CURRENCY " +
                "  WHERE s.PTS = ? " +
                "    AND s.PROCESSING_ENTITY = ? " +
                "    AND s.COUNTERPARTY_ID = ? " +
                "    AND s.VALUE_DATE = ? " +
                "    AND s.ID <= ? " +
                "    AND s.DIRECTION = 'PAY' " +
                "    AND s.BUSINESS_STATUS != 'CANCELLED' " +
                "    AND s.SETTLEMENT_VERSION = ( " +
                "      SELECT MAX(SETTLEMENT_VERSION) " +
                "      FROM SETTLEMENT " +
                "      WHERE SETTLEMENT_ID = s.SETTLEMENT_ID " +
                "        AND PTS = s.PTS " +
                "        AND PROCESSING_ENTITY = s.PROCESSING_ENTITY " +
                "    ) " +
                ") src " +
                "ON (rt.PTS = src.PTS " +
                "    AND rt.PROCESSING_ENTITY = src.PROCESSING_ENTITY " +
                "    AND rt.COUNTERPARTY_ID = src.COUNTERPARTY_ID " +
                "    AND rt.VALUE_DATE = src.VALUE_DATE) " +
                "WHEN MATCHED THEN UPDATE SET " +
                "  rt.RUNNING_TOTAL = CASE WHEN src.REF_ID >= rt.REF_ID THEN src.RUNNING_TOTAL ELSE rt.RUNNING_TOTAL END, " +
                "  rt.REF_ID = CASE WHEN src.REF_ID >= rt.REF_ID THEN src.REF_ID ELSE rt.REF_ID END, " +
                "  rt.UPDATE_TIME = CASE WHEN src.REF_ID >= rt.REF_ID THEN CURRENT_TIMESTAMP ELSE rt.UPDATE_TIME END " +
                "WHEN NOT MATCHED THEN INSERT " +
                "  (PTS, PROCESSING_ENTITY, COUNTERPARTY_ID, VALUE_DATE, RUNNING_TOTAL, REF_ID, CREATE_TIME, UPDATE_TIME) " +
                "VALUES (src.PTS, src.PROCESSING_ENTITY, src.COUNTERPARTY_ID, src.VALUE_DATE, src.RUNNING_TOTAL, src.REF_ID, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)";

        Tuple params = Tuple.of(
                pts,                    // 1: For USING SELECT (PTS)
                processingEntity,       // 2: For USING SELECT (PROCESSING_ENTITY)
                counterpartyId,         // 3: For USING SELECT (COUNTERPARTY_ID)
                valueDate,              // 4: For USING SELECT (VALUE_DATE)
                maxSeqId,               // 5: For USING SELECT (REF_ID)
                pts,                    // 6: For WHERE clause (PTS)
                processingEntity,       // 7: For WHERE clause (PROCESSING_ENTITY)
                counterpartyId,         // 8: For WHERE clause (COUNTERPARTY_ID)
                valueDate,              // 9: For WHERE clause (VALUE_DATE)
                maxSeqId                // 10: For WHERE clause (ID <= maxSeqId)
        );

        System.out.println("DEBUG SQL: " + sql);
        System.out.println("DEBUG params: " + params);

        connection.preparedQuery(sql)
                .execute(params)
                .onSuccess(result -> {
                    System.out.println("DEBUG: calculateAndSaveRunningTotal succeeded");
                    promise.complete();
                })
                .onFailure(error -> {
                    System.err.println("DEBUG: calculateAndSaveRunningTotal failed: " + error.getMessage());
                    promise.fail(error);
                });

        return promise.future();
    }
}
