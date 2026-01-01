package com.tvpc.repository;

import io.vertx.core.Future;

import java.time.LocalDateTime;

/**
 * Repository for Activity/Audit trail operations
 */
public interface ActivityRepository {

    /**
     * Record an activity
     * @param pts Primary Trading System
     * @param processingEntity Business unit
     * @param settlementId Settlement identifier
     * @param settlementVersion Settlement version
     * @param userId User identifier
     * @param userName User name
     * @param actionType Action type (REQUEST_RELEASE, AUTHORISE, etc.)
     * @param actionComment Optional comment
     * @return Future indicating completion
     */
    Future<Void> recordActivity(
            String pts,
            String processingEntity,
            String settlementId,
            Long settlementVersion,
            String userId,
            String userName,
            String actionType,
            String actionComment
    );

    /**
     * Check if a user has already requested release for a settlement
     * @param settlementId Settlement identifier
     * @param settlementVersion Settlement version
     * @param userId User identifier
     * @return Future with true if user already requested
     */
    Future<Boolean> hasUserRequested(String settlementId, Long settlementVersion, String userId);

    /**
     * Check if a settlement has been authorized
     * @param settlementId Settlement identifier
     * @param settlementVersion Settlement version
     * @return Future with true if authorized
     */
    Future<Boolean> isAuthorized(String settlementId, Long settlementVersion);

    /**
     * Get approval workflow info for a settlement
     * @param settlementId Settlement identifier
     * @param settlementVersion Settlement version
     * @return Future with workflow info (requester, authorizer, timestamps)
     */
    Future<WorkflowInfo> getWorkflowInfo(String settlementId, Long settlementVersion);

    /**
     * Inner class for workflow information
     */
    class WorkflowInfo {
        private final String requesterId;
        private final String requesterName;
        private final LocalDateTime requestTime;
        private final String authorizerId;
        private final String authorizerName;
        private final LocalDateTime authorizeTime;

        public WorkflowInfo(String requesterId, String requesterName, LocalDateTime requestTime,
                           String authorizerId, String authorizerName, LocalDateTime authorizeTime) {
            this.requesterId = requesterId;
            this.requesterName = requesterName;
            this.requestTime = requestTime;
            this.authorizerId = authorizerId;
            this.authorizerName = authorizerName;
            this.authorizeTime = authorizeTime;
        }

        public String getRequesterId() { return requesterId; }
        public String getRequesterName() { return requesterName; }
        public LocalDateTime getRequestTime() { return requestTime; }
        public String getAuthorizerId() { return authorizerId; }
        public String getAuthorizerName() { return authorizerName; }
        public LocalDateTime getAuthorizeTime() { return authorizeTime; }
    }
}
