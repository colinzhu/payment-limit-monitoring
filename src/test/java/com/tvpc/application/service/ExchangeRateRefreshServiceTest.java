package com.tvpc.application.service;

import java.math.BigDecimal;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import org.mockito.Mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import org.mockito.MockitoAnnotations;

import com.tvpc.application.port.out.ExchangeRateProvider;
import com.tvpc.application.port.out.ExchangeRateRepository;

import io.vertx.core.Future;
import io.vertx.core.Vertx;

/**
 * Unit test for ExchangeRateRefreshService
 * Tests the use case implementation in isolation using mocks
 */
class ExchangeRateRefreshServiceTest {

    @Mock
    private ExchangeRateProvider rateProvider;

    @Mock
    private ExchangeRateRepository repository;

    private Vertx vertx;
    private ExchangeRateRefreshService service;
    private AutoCloseable mocks;

    @BeforeEach
    void setUp() {
        mocks = MockitoAnnotations.openMocks(this);
        vertx = Vertx.vertx();
        service = new ExchangeRateRefreshService(vertx, rateProvider, repository);
    }

    @AfterEach
    void tearDown() throws Exception {
        service.stopPeriodicRefresh();
        if (vertx != null) {
            CountDownLatch latch = new CountDownLatch(1);
            vertx.close().onComplete(ar -> latch.countDown());
            latch.await(5, TimeUnit.SECONDS);
        }
        if (mocks != null) {
            mocks.close();
        }
    }

    @Test
    void refreshRates_shouldFetchAndSaveRates() throws InterruptedException {
        // Given
        Map<String, BigDecimal> mockRates = Map.of(
                "USD", new BigDecimal("1.0"),
                "EUR", new BigDecimal("1.085")
        );
        
        when(rateProvider.fetchCurrentRates()).thenReturn(Future.succeededFuture(mockRates));
        when(repository.saveRate(any(), any())).thenReturn(Future.succeededFuture());

        CountDownLatch latch = new CountDownLatch(1);

        // When
        service.refreshRates()
                .onComplete(ar -> {
                    // Then
                    assertTrue(ar.succeeded());
                    verify(rateProvider, times(1)).fetchCurrentRates();
                    verify(repository, times(1)).saveRate(eq("USD"), eq(new BigDecimal("1.0")));
                    verify(repository, times(1)).saveRate(eq("EUR"), eq(new BigDecimal("1.085")));
                    latch.countDown();
                });

        assertTrue(latch.await(5, TimeUnit.SECONDS));
    }

    @Test
    void refreshRates_shouldHandleProviderFailure() throws InterruptedException {
        // Given
        when(rateProvider.fetchCurrentRates()).thenReturn(Future.failedFuture("Network error"));

        CountDownLatch latch = new CountDownLatch(1);

        // When
        service.refreshRates()
                .onComplete(ar -> {
                    // Then
                    assertTrue(ar.failed());
                    assertEquals("Network error", ar.cause().getMessage());
                    verify(rateProvider, times(1)).fetchCurrentRates();
                    verify(repository, never()).saveRate(any(), any());
                    latch.countDown();
                });

        assertTrue(latch.await(5, TimeUnit.SECONDS));
    }

    @Test
    void refreshRates_shouldHandleRepositoryFailure() throws InterruptedException {
        // Given
        Map<String, BigDecimal> mockRates = Map.of("USD", new BigDecimal("1.0"));
        
        when(rateProvider.fetchCurrentRates()).thenReturn(Future.succeededFuture(mockRates));
        when(repository.saveRate(any(), any())).thenReturn(Future.failedFuture("Database error"));

        CountDownLatch latch = new CountDownLatch(1);

        // When
        service.refreshRates()
                .onComplete(ar -> {
                    // Then
                    assertTrue(ar.failed());
                    verify(rateProvider, times(1)).fetchCurrentRates();
                    verify(repository, times(1)).saveRate(eq("USD"), eq(new BigDecimal("1.0")));
                    latch.countDown();
                });

        assertTrue(latch.await(5, TimeUnit.SECONDS));
    }

    @Test
    void startPeriodicRefresh_shouldPerformInitialRefresh() throws InterruptedException {
        // Given
        Map<String, BigDecimal> mockRates = Map.of("USD", new BigDecimal("1.0"));
        
        when(rateProvider.fetchCurrentRates()).thenReturn(Future.succeededFuture(mockRates));
        when(repository.saveRate(any(), any())).thenReturn(Future.succeededFuture());

        CountDownLatch latch = new CountDownLatch(1);

        // When
        service.startPeriodicRefresh()
                .onComplete(ar -> {
                    // Then - initial refresh should have been called
                    assertTrue(ar.succeeded());
                    verify(rateProvider, times(1)).fetchCurrentRates();
                    verify(repository, times(1)).saveRate(eq("USD"), eq(new BigDecimal("1.0")));
                    latch.countDown();
                });

        assertTrue(latch.await(5, TimeUnit.SECONDS));
    }

    @Test
    void stopPeriodicRefresh_shouldCancelTimer() throws InterruptedException {
        // Given
        Map<String, BigDecimal> mockRates = Map.of("USD", new BigDecimal("1.0"));
        
        when(rateProvider.fetchCurrentRates()).thenReturn(Future.succeededFuture(mockRates));
        when(repository.saveRate(any(), any())).thenReturn(Future.succeededFuture());

        CountDownLatch latch = new CountDownLatch(1);

        // When
        service.startPeriodicRefresh()
                .onComplete(ar -> {
                    assertTrue(ar.succeeded());
                    
                    // Stop the service
                    service.stopPeriodicRefresh();
                    
                    // Then - should be able to call stop multiple times safely
                    service.stopPeriodicRefresh();
                    latch.countDown();
                });

        assertTrue(latch.await(5, TimeUnit.SECONDS));
    }
}
