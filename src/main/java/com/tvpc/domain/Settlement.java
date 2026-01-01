package com.tvpc.domain;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Objects;

/**
 * Settlement entity - represents a financial transaction record
 */
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

    // Constructors
    public Settlement() {}

    public Settlement(
            String settlementId,
            Long settlementVersion,
            String pts,
            String processingEntity,
            String counterpartyId,
            LocalDate valueDate,
            String currency,
            BigDecimal amount,
            BusinessStatus businessStatus,
            SettlementDirection direction,
            SettlementType settlementType
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
        this.isOld = false;
    }

    // Getters and Setters
    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getSettlementId() {
        return settlementId;
    }

    public void setSettlementId(String settlementId) {
        this.settlementId = settlementId;
    }

    public Long getSettlementVersion() {
        return settlementVersion;
    }

    public void setSettlementVersion(Long settlementVersion) {
        this.settlementVersion = settlementVersion;
    }

    public String getPts() {
        return pts;
    }

    public void setPts(String pts) {
        this.pts = pts;
    }

    public String getProcessingEntity() {
        return processingEntity;
    }

    public void setProcessingEntity(String processingEntity) {
        this.processingEntity = processingEntity;
    }

    public String getCounterpartyId() {
        return counterpartyId;
    }

    public void setCounterpartyId(String counterpartyId) {
        this.counterpartyId = counterpartyId;
    }

    public LocalDate getValueDate() {
        return valueDate;
    }

    public void setValueDate(LocalDate valueDate) {
        this.valueDate = valueDate;
    }

    public String getCurrency() {
        return currency;
    }

    public void setCurrency(String currency) {
        this.currency = currency;
    }

    public BigDecimal getAmount() {
        return amount;
    }

    public void setAmount(BigDecimal amount) {
        this.amount = amount;
    }

    public BusinessStatus getBusinessStatus() {
        return businessStatus;
    }

    public void setBusinessStatus(BusinessStatus businessStatus) {
        this.businessStatus = businessStatus;
    }

    public SettlementDirection getDirection() {
        return direction;
    }

    public void setDirection(SettlementDirection direction) {
        this.direction = direction;
    }

    public SettlementType getSettlementType() {
        return settlementType;
    }

    public void setSettlementType(SettlementType settlementType) {
        this.settlementType = settlementType;
    }

    public Boolean getIsOld() {
        return isOld;
    }

    public void setIsOld(Boolean isOld) {
        this.isOld = isOld;
    }

    public LocalDateTime getCreateTime() {
        return createTime;
    }

    public void setCreateTime(LocalDateTime createTime) {
        this.createTime = createTime;
    }

    public LocalDateTime getUpdateTime() {
        return updateTime;
    }

    public void setUpdateTime(LocalDateTime updateTime) {
        this.updateTime = updateTime;
    }

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

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        Settlement that = (Settlement) o;
        return Objects.equals(id, that.id) &&
               Objects.equals(settlementId, that.settlementId) &&
               Objects.equals(settlementVersion, that.settlementVersion);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id, settlementId, settlementVersion);
    }

    @Override
    public String toString() {
        return "Settlement{" +
                "id=" + id +
                ", settlementId='" + settlementId + '\'' +
                ", settlementVersion=" + settlementVersion +
                ", pts='" + pts + '\'' +
                ", processingEntity='" + processingEntity + '\'' +
                ", counterpartyId='" + counterpartyId + '\'' +
                ", valueDate=" + valueDate +
                ", currency='" + currency + '\'' +
                ", amount=" + amount +
                ", businessStatus=" + businessStatus +
                ", direction=" + direction +
                ", settlementType=" + settlementType +
                ", isOld=" + isOld +
                '}';
    }
}
