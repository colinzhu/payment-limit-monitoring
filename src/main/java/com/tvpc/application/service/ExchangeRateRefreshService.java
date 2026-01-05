package com.tvpc.application.service;

import com.tvpc.application.port.in.ExchangeRateRefreshUseCase;
import com.tvpc.application.port.out.ExchangeRateProvider;
import com.tvpc.application.port.out.ExchangeRateRepository;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import lombok.extern.slf4j.Slf4j;

import java.math.BigDecimal;
import java.util.Map;

/**
 * Use case implementation for exchange rate refresh operations
 * Part of hexagonal architecture - application layer
 * Depends only on ports (interfaces), not concrete adapters
 */
@Slf4j
public class ExchangeRateRefreshService implements ExchangeRateRefreshUseCase {

    private static final long REFRESH_INTERVAL_MS = 12 * 60 * 60 * 1000; // 12 hours
    
    private final Vertx vertx;
    private final ExchangeRateProvider rateProvider;
    private final ExchangeRateRepository repository;
    private Long timerId;

    public ExchangeRateRefreshService(
            Vertx vertx,
            ExchangeRateProvider rateProvider,
            ExchangeRateRepository repository
    ) {
        this.vertx = vertx;
        this.rateProvider = rateProvider;
        this.repository = repository;
    }

    @Override
    public Future<Void> startPeriodicRefresh() {
        log.info("Starting Exchange Rate Refresh Service (interval: 12 hours)");
        
        // Perform initial refresh
        return refreshRates()
                .onSuccess(v -> {
                    // Schedule periodic refresh every 12 hours
                    timerId = vertx.setPeriodic(REFRESH_INTERVAL_MS, id -> {
                        log.info("Periodic exchange rate refresh triggered");
                        refreshRates()
                                .onFailure(error -> log.error("Periodic refresh failed", error));
                    });
                    log.info("Exchange Rate Refresh Service started successfully");
                })
                .onFailure(error -> log.error("Failed to start Exchange Rate Refresh Service", error));
    }

    @Override
    public void stopPeriodicRefresh() {
        if (timerId != null) {
            vertx.cancelTimer(timerId);
            timerId = null;
            log.info("Exchange Rate Refresh Service stopped");
        }
    }

    @Override
    public Future<Void> refreshRates() {
        log.info("Refreshing exchange rates...");
        
        return rateProvider.fetchCurrentRates()
                .compose(rates -> saveAllRates(rates))
                .onSuccess(v -> log.info("Exchange rates refreshed successfully"))
                .onFailure(error -> log.error("Failed to refresh exchange rates", error));
    }

    /**
     * Save all fetched rates to database
     */
    private Future<Void> saveAllRates(Map<String, BigDecimal> rates) {
        log.info("Saving {} exchange rates to database", rates.size());
        
        // Save all rates sequentially
        Future<Void> future = Future.succeededFuture();
        
        for (Map.Entry<String, BigDecimal> entry : rates.entrySet()) {
            future = future.compose(v -> 
                repository.saveRate(entry.getKey(), entry.getValue())
            );
        }
        
        return future.onSuccess(v -> 
            log.info("All exchange rates saved successfully")
        );
    }
}
