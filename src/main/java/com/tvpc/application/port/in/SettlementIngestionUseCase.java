package com.tvpc.application.port.in;

import com.tvpc.domain.model.Settlement;
import io.vertx.core.Future;

/**
 * Input port for settlement ingestion use case
 * Defines the contract for processing incoming settlements
 */
public interface SettlementIngestionUseCase {

    /**
     * Process a settlement through the 5-step ingestion flow
     * @param command The settlement ingestion command
     * @return Future with the generated sequence ID (REF_ID)
     */
    Future<Long> processSettlement(SettlementIngestionCommand command);

    /**
     * Command object for settlement ingestion
     */
    record SettlementIngestionCommand(
            String settlementId,
            Long settlementVersion,
            String pts,
            String processingEntity,
            String counterpartyId,
            String valueDate,
            String currency,
            java.math.BigDecimal amount,
            String businessStatus,
            String direction,
            String settlementType
    ) {}
}
