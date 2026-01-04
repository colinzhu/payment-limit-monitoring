package com.tvpc.adapter.in.web.ingestion;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.math.BigDecimal;

/**
 * DTO for incoming settlement data from external trading systems
 */
public record SettlementIngestionRequest(
        String settlementId,
        Long settlementVersion,
        String pts,
        String processingEntity,
        String counterpartyId,
        String valueDate,
        String currency,
        BigDecimal amount,
        String businessStatus,
        String direction,
        String settlementType
) {
    @JsonCreator
    public SettlementIngestionRequest(
            @JsonProperty("settlementId") String settlementId,
            @JsonProperty("settlementVersion") Long settlementVersion,
            @JsonProperty("pts") String pts,
            @JsonProperty("processingEntity") String processingEntity,
            @JsonProperty("counterpartyId") String counterpartyId,
            @JsonProperty("valueDate") String valueDate,
            @JsonProperty("currency") String currency,
            @JsonProperty("amount") BigDecimal amount,
            @JsonProperty("businessStatus") String businessStatus,
            @JsonProperty("direction") String direction,
            @JsonProperty("settlementType") String settlementType
    ) {
        this.settlementId = settlementId;
        this.settlementVersion = settlementVersion;
        this.pts = pts;
        this.processingEntity = processingEntity;
        this.counterpartyId = counterpartyId;
        this.valueDate = valueDate;
        this.currency = currency;
        this.amount = amount;
        this.businessStatus = businessStatus;
        this.direction = direction;
        this.settlementType = settlementType;
    }
}
