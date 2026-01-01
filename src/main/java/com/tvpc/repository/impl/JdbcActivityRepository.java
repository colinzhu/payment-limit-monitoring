package com.tvpc.repository.impl;

import com.tvpc.repository.ActivityRepository;
import io.vertx.core.Future;
import io.vertx.core.Promise;
import io.vertx.sqlclient.SqlClient;
import io.vertx.sqlclient.Tuple;

import java.time.LocalDateTime;

/**
 * JDBC implementation of ActivityRepository
 */
public class JdbcActivityRepository implements ActivityRepository {

    private final SqlClient sqlClient;

    public JdbcActivityRepository(SqlClient sqlClient) {
        this.sqlClient = sqlClient;
    }

    @Override
    public Future<Void> recordActivity(
            String pts,
            String processingEntity,
            String settlementId,
            Long settlementVersion,
            String userId,
            String userName,
            String actionType,
            String actionComment
    ) {
        Promise<Void> promise = Promise.promise();

        String sql = "INSERT INTO ACTIVITIES " +
                "(PTS, PROCESSING_ENTITY, SETTLEMENT_ID, SETTLEMENT_VERSION, USER_ID, USER_NAME, ACTION_TYPE, ACTION_COMMENT, CREATE_TIME) " +
                "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)";

        Tuple params = Tuple.of(
                pts,
                processingEntity,
                settlementId,
                settlementVersion,
                userId,
                userName,
                actionType,
                actionComment,
                LocalDateTime.now()
        );

        sqlClient.preparedQuery(sql)
                .execute(params)
                .onSuccess(result -> promise.complete())
                .onFailure(promise::fail);

        return promise.future();
    }

    @Override
    public Future<Boolean> hasUserRequested(String settlementId, Long settlementVersion, String userId) {
        Promise<Boolean> promise = Promise.promise();

        String sql = "SELECT COUNT(*) as count FROM ACTIVITIES " +
                "WHERE SETTLEMENT_ID = ? AND SETTLEMENT_VERSION = ? AND USER_ID = ? AND ACTION_TYPE = 'REQUEST_RELEASE'";

        Tuple params = Tuple.of(settlementId, settlementVersion, userId);

        sqlClient.preparedQuery(sql)
                .execute(params)
                .onSuccess(result -> {
                    long count = result.iterator().next().getLong("count");
                    promise.complete(count > 0);
                })
                .onFailure(promise::fail);

        return promise.future();
    }

    @Override
    public Future<Boolean> isAuthorized(String settlementId, Long settlementVersion) {
        Promise<Boolean> promise = Promise.promise();

        String sql = "SELECT COUNT(*) as count FROM ACTIVITIES " +
                "WHERE SETTLEMENT_ID = ? AND SETTLEMENT_VERSION = ? AND ACTION_TYPE = 'AUTHORISE'";

        Tuple params = Tuple.of(settlementId, settlementVersion);

        sqlClient.preparedQuery(sql)
                .execute(params)
                .onSuccess(result -> {
                    long count = result.iterator().next().getLong("count");
                    promise.complete(count > 0);
                })
                .onFailure(promise::fail);

        return promise.future();
    }

    @Override
    public Future<WorkflowInfo> getWorkflowInfo(String settlementId, Long settlementVersion) {
        Promise<WorkflowInfo> promise = Promise.promise();

        String sql = "SELECT " +
                "MAX(CASE WHEN ACTION_TYPE = 'REQUEST_RELEASE' THEN USER_ID END) as requester_id, " +
                "MAX(CASE WHEN ACTION_TYPE = 'REQUEST_RELEASE' THEN USER_NAME END) as requester_name, " +
                "MAX(CASE WHEN ACTION_TYPE = 'REQUEST_RELEASE' THEN CREATE_TIME END) as request_time, " +
                "MAX(CASE WHEN ACTION_TYPE = 'AUTHORISE' THEN USER_ID END) as authorizer_id, " +
                "MAX(CASE WHEN ACTION_TYPE = 'AUTHORISE' THEN USER_NAME END) as authorizer_name, " +
                "MAX(CASE WHEN ACTION_TYPE = 'AUTHORISE' THEN CREATE_TIME END) as authorize_time " +
                "FROM ACTIVITIES " +
                "WHERE SETTLEMENT_ID = ? AND SETTLEMENT_VERSION = ? " +
                "AND ACTION_TYPE IN ('REQUEST_RELEASE', 'AUTHORISE')";

        Tuple params = Tuple.of(settlementId, settlementVersion);

        sqlClient.preparedQuery(sql)
                .execute(params)
                .onSuccess(result -> {
                    if (result.size() > 0) {
                        var row = result.iterator().next();
                        WorkflowInfo info = new WorkflowInfo(
                                row.getString("requester_id"),
                                row.getString("requester_name"),
                                row.getLocalDateTime("request_time"),
                                row.getString("authorizer_id"),
                                row.getString("authorizer_name"),
                                row.getLocalDateTime("authorize_time")
                        );
                        promise.complete(info);
                    } else {
                        promise.complete(null);
                    }
                })
                .onFailure(promise::fail);

        return promise.future();
    }
}
