package com.tvpc.domain.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * Settlement entity - represents a financial transaction record
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Settlement {
    private Long id;                    // Auto-generated sequence ID (REF_ID)
    private String settlementId;        // Business identifier
    private Long settlementVersion;     // Timestamp in long format from external system
    private String pts;                 // Primary Trading System
    private String processingEntity;    // Business unit within trading system
    private String counterpartyId;      // External party identifier
    private LocalDate valueDate;        // Settlement date
    private String currency;            // ISO 4217 currency code
    private BigDecimal amount;          // Transaction amount
    private BusinessStatus businessStatus;  // PENDING, INVALID, VERIFIED, CANCELLED
    private SettlementDirection direction;  // PAY or RECEIVE
    private SettlementType settlementType;  // GROSS or NET
    private Boolean isOld;              // Flag for old versions
    private LocalDateTime createTime;   // Audit timestamp
    private LocalDateTime updateTime;   // Audit timestamp

    // Helper methods
    public boolean isPay() {
        return SettlementDirection.PAY.equals(direction);
    }

    public boolean isReceive() {
        return SettlementDirection.RECEIVE.equals(direction);
    }

    public boolean isCancelled() {
        return BusinessStatus.CANCELLED.equals(businessStatus);
    }

    public boolean isVerified() {
        return BusinessStatus.VERIFIED.equals(businessStatus);
    }

    public boolean isIncludedInRunningTotal() {
        return isPay() && !isCancelled();
    }

    // Group identifier for running total calculation
    public String getGroupKey() {
        return String.format("%s|%s|%s|%s", pts, processingEntity, counterpartyId, valueDate);
    }
}
