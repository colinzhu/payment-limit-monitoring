package com.tvpc.application.port.in;

import io.vertx.core.Future;

/**
 * Input port for exchange rate refresh operations
 * Part of hexagonal architecture - defines what the application can do
 */
public interface ExchangeRateRefreshUseCase {

    /**
     * Start the periodic refresh service
     * Performs initial refresh immediately, then schedules periodic refreshes
     */
    Future<Void> startPeriodicRefresh();

    /**
     * Stop the periodic refresh service
     */
    void stopPeriodicRefresh();

    /**
     * Manually trigger a refresh of exchange rates
     */
    Future<Void> refreshRates();
}
