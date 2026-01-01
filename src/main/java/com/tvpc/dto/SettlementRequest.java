package com.tvpc.dto;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.math.BigDecimal;

/**
 * DTO for incoming settlement data from external trading systems
 */
public class SettlementRequest {
    private final String settlementId;
    private final Long settlementVersion;
    private final String pts;
    private final String processingEntity;
    private final String counterpartyId;
    private final String valueDate;  // ISO date format: YYYY-MM-DD
    private final String currency;
    private final BigDecimal amount;
    private final String businessStatus;
    private final String direction;
    private final String settlementType;

    @JsonCreator
    public SettlementRequest(
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

    // Getters
    public String getSettlementId() {
        return settlementId;
    }

    public Long getSettlementVersion() {
        return settlementVersion;
    }

    public String getPts() {
        return pts;
    }

    public String getProcessingEntity() {
        return processingEntity;
    }

    public String getCounterpartyId() {
        return counterpartyId;
    }

    public String getValueDate() {
        return valueDate;
    }

    public String getCurrency() {
        return currency;
    }

    public BigDecimal getAmount() {
        return amount;
    }

    public String getBusinessStatus() {
        return businessStatus;
    }

    public String getDirection() {
        return direction;
    }

    public String getSettlementType() {
        return settlementType;
    }

    @Override
    public String toString() {
        return "SettlementRequest{" +
                "settlementId='" + settlementId + '\'' +
                ", settlementVersion=" + settlementVersion +
                ", pts='" + pts + '\'' +
                ", processingEntity='" + processingEntity + '\'' +
                ", counterpartyId='" + counterpartyId + '\'' +
                ", valueDate='" + valueDate + '\'' +
                ", currency='" + currency + '\'' +
                ", amount=" + amount +
                ", businessStatus='" + businessStatus + '\'' +
                ", direction='" + direction + '\'' +
                ", settlementType='" + settlementType + '\'' +
                '}';
    }
}
