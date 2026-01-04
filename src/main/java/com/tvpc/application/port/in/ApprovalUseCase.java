package com.tvpc.application.port.in;

import io.vertx.core.Future;

/**
 * Inbound port - Use case for approval workflow
 * Primary port (driven by the presentation layer)
 */
public interface ApprovalUseCase {
    /**
     * Request release for a blocked settlement
     * @param settlementId Business settlement ID
     * @param settlementVersion Version number
     * @param userId User requesting release
     * @param userName User name
     * @param comment Optional comment
     * @return Future indicating success
     */
    Future<Void> requestRelease(
            String settlementId,
            Long settlementVersion,
            String userId,
            String userName,
            String comment
    );

    /**
     * Authorize a settlement that has been requested for release
     * @param settlementId Business settlement ID
     * @param settlementVersion Version number
     * @param userId User authorizing (must be different from requester)
     * @param userName User name
     * @param comment Optional comment
     * @return Future indicating success
     */
    Future<Void> authorize(
            String settlementId,
            Long settlementVersion,
            String userId,
            String userName,
            String comment
    );
}
