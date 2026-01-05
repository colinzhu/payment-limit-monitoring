package com.tvpc.application.port.out;

import io.vertx.core.Future;

import java.math.BigDecimal;
import java.util.Map;

/**
 * Output port for fetching exchange rates from external sources
 * Part of hexagonal architecture - defines what the application needs
 */
public interface ExchangeRateProvider {

    /**
     * Fetch current exchange rates from external system
     * @return Map of currency code to rate (relative to USD)
     */
    Future<Map<String, BigDecimal>> fetchCurrentRates();
}
