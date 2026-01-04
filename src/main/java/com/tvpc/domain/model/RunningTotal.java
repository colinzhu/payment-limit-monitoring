package com.tvpc.domain.model;

import lombok.Value;

import java.math.BigDecimal;

/**
 * Running Total - Aggregated exposure for a settlement group
 * Entity - Has identity
 */
@Value
public class RunningTotal {
    Long id;  // Primary key
    String pts;
    String processingEntity;
    String counterpartyId;
    String valueDate;  // Stored as string for simplicity
    BigDecimal total;
    Long refId;  // Sequence ID used for calculation
}
