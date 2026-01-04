package com.tvpc.domain.event;

import lombok.Value;

import java.time.LocalDate;

/**
 * Domain event for settlement processing
 * Triggered when a settlement needs running total recalculation
 */
@Value
public class SettlementEvent {
    String pts;
    String processingEntity;
    String counterpartyId;
    LocalDate valueDate;
    Long refId;  // The settlement's sequence ID

    /**
     * Get the group identifier for this event
     */
    public String getGroupKey() {
        return String.format("%s|%s|%s|%s", pts, processingEntity, counterpartyId, valueDate);
    }
}
