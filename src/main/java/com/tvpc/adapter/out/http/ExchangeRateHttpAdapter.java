package com.tvpc.adapter.out.http;

import com.tvpc.application.port.out.ExchangeRateProvider;
import io.vertx.core.Future;
import lombok.extern.slf4j.Slf4j;

import java.math.BigDecimal;
import java.util.Map;

/**
 * HTTP adapter to fetch exchange rates from external system
 * Implements ExchangeRateProvider output port
 * Part of hexagonal architecture - adapter layer
 * Currently returns dummy test data
 */
@Slf4j
public class ExchangeRateHttpAdapter implements ExchangeRateProvider {

    @Override
    public Future<Map<String, BigDecimal>> fetchCurrentRates() {
        log.info("Fetching exchange rates from external system (dummy data)");

        // Dummy test data - common currencies with rates to USD
        // TODO: Replace with actual HTTP call when external system is ready
        Map<String, BigDecimal> rates = Map.of(
                "USD", new BigDecimal("1.000000"),
                "EUR", new BigDecimal("1.085000"),
                "GBP", new BigDecimal("1.270000"),
                "JPY", new BigDecimal("0.006800"),
                "CHF", new BigDecimal("1.130000"),
                "CNY", new BigDecimal("0.138000"),
                "HKD", new BigDecimal("0.128000"),
                "SGD", new BigDecimal("0.745000")
        );

        log.info("Fetched {} exchange rates", rates.size());
        return Future.succeededFuture(rates);
    }
}
