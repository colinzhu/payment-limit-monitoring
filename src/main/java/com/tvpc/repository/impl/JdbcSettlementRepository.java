package com.tvpc.repository.impl;

import com.tvpc.domain.BusinessStatus;
import com.tvpc.domain.Settlement;
import com.tvpc.domain.SettlementDirection;
import com.tvpc.domain.SettlementType;
import com.tvpc.repository.SettlementRepository;
import io.vertx.core.Future;
import io.vertx.core.Promise;
import io.vertx.sqlclient.SqlClient;
import io.vertx.sqlclient.SqlConnection;
import io.vertx.sqlclient.Tuple;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * JDBC implementation of SettlementRepository
 */
public class JdbcSettlementRepository implements SettlementRepository {

    private final SqlClient sqlClient;

    public JdbcSettlementRepository(SqlClient sqlClient) {
        this.sqlClient = sqlClient;
    }

    @Override
    public Future<Long> save(Settlement settlement, SqlConnection connection) {
        Promise<Long> promise = Promise.promise();

        // First, get the next sequence value
        // This ensures we have a valid ID before the insert
        connection.query("SELECT SETTLEMENT_SEQ.NEXTVAL AS ID FROM DUAL").execute()
                .compose(result -> {
                    System.out.println("DEBUG save: NEXTVAL result size = " + result.size());

                    if (result.size() == 0) {
                        System.err.println("DEBUG save: No rows returned from NEXTVAL query");
                        promise.fail("No sequence value returned");
                        return Future.failedFuture("No sequence value");
                    }

                    var row = result.iterator().next();
                    System.out.println("DEBUG save: Row = " + row);

                    // Try different ways to get the value
                    Object rawValue = row.getValue(0);
                    System.out.println("DEBUG save: Raw value (index 0) = " + rawValue + " (type: " + (rawValue != null ? rawValue.getClass().getName() : "null") + ")");

                    Object rawValueNamed = row.getValue("ID");
                    System.out.println("DEBUG save: Raw value (named 'ID') = " + rawValueNamed + " (type: " + (rawValueNamed != null ? rawValueNamed.getClass().getName() : "null") + ")");

                    Long id = row.getLong(0);
                    System.out.println("DEBUG save: getLong(0) = " + id);

                    Long idNamed = row.getLong("ID");
                    System.out.println("DEBUG save: getLong('ID') = " + idNamed);

                    // Now insert with the explicit ID
                    String sql = "INSERT INTO SETTLEMENT (" +
                            "ID, SETTLEMENT_ID, SETTLEMENT_VERSION, PTS, PROCESSING_ENTITY, " +
                            "COUNTERPARTY_ID, VALUE_DATE, CURRENCY, AMOUNT, " +
                            "BUSINESS_STATUS, DIRECTION, GROSS_NET, IS_OLD, " +
                            "CREATE_TIME, UPDATE_TIME" +
                            ") VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";

                    Tuple params = Tuple.of(
                            id,                                    // ID from sequence
                            settlement.getSettlementId(),
                            settlement.getSettlementVersion(),
                            settlement.getPts(),
                            settlement.getProcessingEntity(),
                            settlement.getCounterpartyId(),
                            settlement.getValueDate(),
                            settlement.getCurrency(),
                            settlement.getAmount(),
                            settlement.getBusinessStatus().getValue(),
                            settlement.getDirection().getValue(),
                            settlement.getSettlementType().getValue(),
                            settlement.getIsOld() ? 1 : 0,
                            LocalDateTime.now(),
                            LocalDateTime.now()
                    );

                    System.out.println("DEBUG save: Inserting with ID = " + id);
                    return connection.preparedQuery(sql).execute(params).map(id);
                })
                .onSuccess(id -> {
                    System.out.println("DEBUG save: Insert succeeded, ID = " + id);
                    settlement.setId(id);
                    promise.complete(id);
                })
                .onFailure(error -> {
                    System.err.println("DEBUG save: Failed - " + error.getMessage());
                    promise.fail(error);
                });

        return promise.future();
    }

    @Override
    public Future<Void> markOldVersions(String settlementId, String pts, String processingEntity, SqlConnection connection) {
        Promise<Void> promise = Promise.promise();

        // Oracle-compatible: Use 1 for TRUE
        String sql = "UPDATE SETTLEMENT SET IS_OLD = 1, UPDATE_TIME = ? " +
                "WHERE SETTLEMENT_ID = ? AND PTS = ? AND PROCESSING_ENTITY = ? " +
                "AND SETTLEMENT_VERSION < (SELECT MAX(SETTLEMENT_VERSION) " +
                "FROM SETTLEMENT WHERE SETTLEMENT_ID = ? AND PTS = ? AND PROCESSING_ENTITY = ?) " +
                "AND (IS_OLD IS NULL OR IS_OLD = 0)";

        Tuple params = Tuple.of(
                LocalDateTime.now(),
                settlementId,
                pts,
                processingEntity,
                settlementId,
                pts,
                processingEntity
        );

        connection.preparedQuery(sql)
                .execute(params)
                .onSuccess(result -> promise.complete())
                .onFailure(promise::fail);

        return promise.future();
    }

    @Override
    public Future<Optional<String>> findPreviousCounterparty(String settlementId, String pts, String processingEntity, Long currentId, SqlConnection connection) {
        Promise<Optional<String>> promise = Promise.promise();

        String sql = "SELECT COUNTERPARTY_ID FROM SETTLEMENT " +
                "WHERE ID = (SELECT MAX(ID) FROM SETTLEMENT " +
                "WHERE SETTLEMENT_ID = ? AND PTS = ? AND PROCESSING_ENTITY = ? AND ID < ?) " +
                "AND COUNTERPARTY_ID IS NOT NULL";

        Tuple params = Tuple.of(settlementId, pts, processingEntity, currentId);

        connection.preparedQuery(sql)
                .execute(params)
                .onSuccess(result -> {
                    if (result.size() > 0) {
                        String counterpartyId = result.iterator().next().getString("COUNTERPARTY_ID");
                        promise.complete(Optional.of(counterpartyId));
                    } else {
                        promise.complete(Optional.empty());
                    }
                })
                .onFailure(promise::fail);

        return promise.future();
    }

    @Override
    public Future<Optional<Settlement>> findLatestVersion(String settlementId, String pts, String processingEntity) {
        Promise<Optional<Settlement>> promise = Promise.promise();

        // Oracle-compatible: Use FETCH FIRST instead of LIMIT
        String sql = "SELECT * FROM SETTLEMENT " +
                "WHERE SETTLEMENT_ID = ? AND PTS = ? AND PROCESSING_ENTITY = ? " +
                "ORDER BY SETTLEMENT_VERSION DESC " +
                "FETCH FIRST 1 ROW ONLY";

        Tuple params = Tuple.of(settlementId, pts, processingEntity);

        sqlClient.preparedQuery(sql)
                .execute(params)
                .onSuccess(result -> {
                    if (result.size() > 0) {
                        promise.complete(Optional.of(mapToSettlement(result.iterator().next())));
                    } else {
                        promise.complete(Optional.empty());
                    }
                })
                .onFailure(promise::fail);

        return promise.future();
    }

    @Override
    public Future<List<Settlement>> findByGroup(String pts, String processingEntity, String counterpartyId, String valueDate, Long maxSeqId, SqlConnection connection) {
        Promise<List<Settlement>> promise = Promise.promise();

        String sql = "SELECT * FROM SETTLEMENT " +
                "WHERE PTS = ? AND PROCESSING_ENTITY = ? AND COUNTERPARTY_ID = ? AND VALUE_DATE = ? " +
                "AND ID <= ? " +
                "ORDER BY SETTLEMENT_ID, SETTLEMENT_VERSION";

        Tuple params = Tuple.of(pts, processingEntity, counterpartyId, LocalDate.parse(valueDate), maxSeqId);

        connection.preparedQuery(sql)
                .execute(params)
                .onSuccess(result -> {
                    List<Settlement> settlements = new ArrayList<>();
                    for (var row : result) {
                        settlements.add(mapToSettlement(row));
                    }
                    promise.complete(settlements);
                })
                .onFailure(promise::fail);

        return promise.future();
    }

    @Override
    public Future<List<Settlement>> findByGroupWithFilters(String pts, String processingEntity, String counterpartyId, String valueDate, Long maxSeqId, SqlConnection connection) {
        Promise<List<Settlement>> promise = Promise.promise();

        // Use window function to find latest version per settlement ID, without relying on IS_OLD flag
        // This correctly handles counterparty changes where old versions may have IS_OLD=1
        String sql = "SELECT * FROM (" +
                "  SELECT s.*, " +
                "         MAX(s.SETTLEMENT_VERSION) OVER (PARTITION BY s.SETTLEMENT_ID, s.PTS, s.PROCESSING_ENTITY) as max_version " +
                "  FROM SETTLEMENT s " +
                "  WHERE s.PTS = ? AND s.PROCESSING_ENTITY = ? AND s.COUNTERPARTY_ID = ? AND s.VALUE_DATE = ? " +
                "    AND s.ID <= ? " +
                "    AND s.DIRECTION = 'PAY' AND s.BUSINESS_STATUS != 'CANCELLED' " +
                ") ranked " +
                "WHERE SETTLEMENT_VERSION = max_version " +
                "ORDER BY SETTLEMENT_ID";

        Tuple params = Tuple.of(pts, processingEntity, counterpartyId, LocalDate.parse(valueDate), maxSeqId);

        connection.preparedQuery(sql)
                .execute(params)
                .onSuccess(result -> {
                    List<Settlement> settlements = new ArrayList<>();
                    for (var row : result) {
                        settlements.add(mapToSettlement(row));
                    }
                    promise.complete(settlements);
                })
                .onFailure(promise::fail);

        return promise.future();
    }

    private Settlement mapToSettlement(io.vertx.sqlclient.Row row) {
        Settlement settlement = new Settlement();
        settlement.setId(row.getLong("ID"));
        settlement.setSettlementId(row.getString("SETTLEMENT_ID"));
        settlement.setSettlementVersion(row.getLong("SETTLEMENT_VERSION"));
        settlement.setPts(row.getString("PTS"));
        settlement.setProcessingEntity(row.getString("PROCESSING_ENTITY"));
        settlement.setCounterpartyId(row.getString("COUNTERPARTY_ID"));
        settlement.setValueDate(row.getLocalDate("VALUE_DATE"));
        settlement.setCurrency(row.getString("CURRENCY"));
        settlement.setAmount(row.getBigDecimal("AMOUNT"));
        settlement.setBusinessStatus(BusinessStatus.fromValue(row.getString("BUSINESS_STATUS")));
        settlement.setDirection(SettlementDirection.fromValue(row.getString("DIRECTION")));
        settlement.setSettlementType(SettlementType.fromValue(row.getString("GROSS_NET")));

        // Handle both BOOLEAN (H2) and INTEGER (Oracle) for IS_OLD
        Object isOldValue = row.getValue("IS_OLD");
        boolean isOld;
        if (isOldValue instanceof Boolean) {
            isOld = (Boolean) isOldValue;
        } else if (isOldValue instanceof Number) {
            isOld = ((Number) isOldValue).intValue() == 1;
        } else {
            isOld = false;
        }
        settlement.setIsOld(isOld);

        settlement.setCreateTime(row.getLocalDateTime("CREATE_TIME"));
        settlement.setUpdateTime(row.getLocalDateTime("UPDATE_TIME"));
        return settlement;
    }
}
