package com.tvpc.adapter.out.http;

import io.vertx.core.Future;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Test for ExchangeRateHttpAdapter
 */
class ExchangeRateHttpAdapterTest {

    private ExchangeRateHttpAdapter adapter;

    @BeforeEach
    void setUp() {
        adapter = new ExchangeRateHttpAdapter();
    }

    @Test
    void fetchCurrentRates_shouldReturnDummyData() {
        // When
        Future<Map<String, BigDecimal>> future = adapter.fetchCurrentRates();

        // Then
        assertTrue(future.succeeded());
        Map<String, BigDecimal> rates = future.result();
        
        assertNotNull(rates);
        assertFalse(rates.isEmpty());
        
        // Verify USD rate is 1.0
        assertTrue(rates.containsKey("USD"));
        assertEquals(new BigDecimal("1.000000"), rates.get("USD"));
        
        // Verify other common currencies exist
        assertTrue(rates.containsKey("EUR"));
        assertTrue(rates.containsKey("GBP"));
        assertTrue(rates.containsKey("JPY"));
        
        // Verify rates are positive
        rates.values().forEach(rate -> 
            assertTrue(rate.compareTo(BigDecimal.ZERO) > 0)
        );
    }
}
