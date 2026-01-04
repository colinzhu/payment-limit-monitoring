package com.tvpc.application.port.in;

import io.vertx.core.Future;

import java.time.LocalDate;

/**
 * Inbound port - Use case for manual recalculation
 * Primary port (driven by the presentation layer)
 */
public interface RecalculateUseCase {
    /**
     * Manually trigger recalculation for a scope of settlements
     * @param pts Primary Trading System (optional, can be null for all)
     * @param processingEntity Business unit (optional, can be null for all)
     * @param counterpartyId Counterparty (optional, can be null for all)
     * @param valueDateFrom Start of value date range
     * @param valueDateTo End of value date range
     * @param userId User requesting the recalculation
     * @param reason Reason for recalculation
     * @return Future indicating completion
     */
    Future<Void> recalculate(
            String pts,
            String processingEntity,
            String counterpartyId,
            LocalDate valueDateFrom,
            LocalDate valueDateTo,
            String userId,
            String reason
    );
}
