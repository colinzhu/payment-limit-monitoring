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
        // 7: For WHEN MATCHED UPDATE (UPDATE_TIME)
        // 8-9: For WHEN NOT MATCHED INSERT (CREATE_TIME, UPDATE_TIME)
        String sql = "MERGE INTO RUNNING_TOTAL rt " +
                "USING (SELECT ? as PTS, ? as PROCESSING_ENTITY, ? as COUNTERPARTY_ID, ? as VALUE_DATE, ? as RUNNING_TOTAL, ? as REF_ID FROM DUAL) src " +
                "ON (rt.PTS = src.PTS AND rt.PROCESSING_ENTITY = src.PROCESSING_ENTITY AND rt.COUNTERPARTY_ID = src.COUNTERPARTY_ID AND rt.VALUE_DATE = src.VALUE_DATE) " +
                "WHEN MATCHED THEN UPDATE SET rt.RUNNING_TOTAL = src.RUNNING_TOTAL, rt.REF_ID = src.REF_ID, rt.UPDATE_TIME = ? " +
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
}
