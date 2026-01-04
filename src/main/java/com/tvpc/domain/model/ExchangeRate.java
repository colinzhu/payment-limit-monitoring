package com.tvpc.domain.model;

import lombok.Value;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * Exchange Rate - Currency conversion rate to USD
 * Entity - Has identity
 */
@Value
public class ExchangeRate {
    String currency;  // ISO 4217 code
    BigDecimal rateToUsd;
    LocalDateTime updateTime;
}
