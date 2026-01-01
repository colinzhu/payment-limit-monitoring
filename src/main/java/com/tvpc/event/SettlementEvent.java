package com.tvpc.event;

import java.time.LocalDate;
import java.util.Objects;

/**
 * Event data structure for settlement processing
 * Triggered when a settlement needs running total recalculation
 */
public class SettlementEvent {
    private final String pts;
    private final String processingEntity;
    private final String counterpartyId;
    private final LocalDate valueDate;
    private final Long refId;  // The settlement's sequence ID

    public SettlementEvent(String pts, String processingEntity, String counterpartyId, LocalDate valueDate, Long refId) {
        this.pts = pts;
        this.processingEntity = processingEntity;
        this.counterpartyId = counterpartyId;
        this.valueDate = valueDate;
        this.refId = refId;
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

    public LocalDate getValueDate() {
        return valueDate;
    }

    public Long getRefId() {
        return refId;
    }

    /**
     * Get the group identifier for this event
     */
    public String getGroupKey() {
        return String.format("%s|%s|%s|%s", pts, processingEntity, counterpartyId, valueDate);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        SettlementEvent that = (SettlementEvent) o;
        return Objects.equals(pts, that.pts) &&
               Objects.equals(processingEntity, that.processingEntity) &&
               Objects.equals(counterpartyId, that.counterpartyId) &&
               Objects.equals(valueDate, that.valueDate) &&
               Objects.equals(refId, that.refId);
    }

    @Override
    public int hashCode() {
        return Objects.hash(pts, processingEntity, counterpartyId, valueDate, refId);
    }

    @Override
    public String toString() {
        return "SettlementEvent{" +
                "pts='" + pts + '\'' +
                ", processingEntity='" + processingEntity + '\'' +
                ", counterpartyId='" + counterpartyId + '\'' +
                ", valueDate=" + valueDate +
                ", seqId=" + refId +
                '}';
    }
}
