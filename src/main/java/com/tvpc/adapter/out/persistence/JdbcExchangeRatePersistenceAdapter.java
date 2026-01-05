package com.tvpc.adapter.out.persistence;

import com.tvpc.application.port.out.ExchangeRateRepository;
import io.vertx.core.Future;
import io.vertx.sqlclient.SqlClient;
import io.vertx.sqlclient.Tuple;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/**
 * JDBC implementation of ExchangeRateRepository
 */
@Slf4j
@RequiredArgsConstructor
public class JdbcExchangeRatePersistenceAdapter implements ExchangeRateRepository {

    private final SqlClient sqlClient;

    @Override
    public Future<Optional<BigDecimal>> getRate(String currency) {
        String sql = "SELECT RATE_TO_USD FROM EXCHANGE_RATE WHERE CURRENCY = ?";
        
        return sqlClient.preparedQuery(sql)
                .execute(Tuple.of(currency))
                .<Optional<BigDecimal>>map(result -> {
                    if (result.size() > 0) {
                        BigDecimal rate = result.iterator().next().getBigDecimal("RATE_TO_USD");
                        log.debug("Found rate for {}: {}", currency, rate);
                        return Optional.of(rate);
                    }
                    log.debug("No rate found for {}", currency);
                    return Optional.empty();
                })
                .onFailure(error -> log.error("Failed to get rate for {}: {}", currency, error.getMessage()));
    }

    @Override
    public Future<Map<String, BigDecimal>> getAllRates() {
        String sql = "SELECT CURRENCY, RATE_TO_USD FROM EXCHANGE_RATE";
        
        return sqlClient.query(sql)
                .execute()
                .map(result -> {
                    Map<String, BigDecimal> rates = new HashMap<>();
                    result.forEach(row -> {
                        rates.put(row.getString("CURRENCY"), row.getBigDecimal("RATE_TO_USD"));
                    });
                    log.info("Retrieved {} exchange rates from database", rates.size());
                    return rates;
                })
                .onFailure(error -> log.error("Failed to get all rates: {}", error.getMessage()));
    }

    @Override
    public Future<Void> saveRate(String currency, BigDecimal rateToUSD) {
        LocalDateTime now = LocalDateTime.now();
        
        String sql = "MERGE INTO EXCHANGE_RATE er " +
                "USING (SELECT ? as CURRENCY, ? as RATE_TO_USD, ? as UPDATE_TIME FROM DUAL) src " +
                "ON (er.CURRENCY = src.CURRENCY) " +
                "WHEN MATCHED THEN UPDATE SET " +
                "  er.RATE_TO_USD = src.RATE_TO_USD, " +
                "  er.UPDATE_TIME = src.UPDATE_TIME " +
                "WHEN NOT MATCHED THEN INSERT (CURRENCY, RATE_TO_USD, UPDATE_TIME) " +
                "VALUES (src.CURRENCY, src.RATE_TO_USD, src.UPDATE_TIME)";

        Tuple params = Tuple.of(currency, rateToUSD, now);

        return sqlClient.preparedQuery(sql)
                .execute(params)
                .onSuccess(result -> log.debug("Saved rate for {}: {}", currency, rateToUSD))
                .onFailure(error -> log.error("Failed to save rate for {}: {}", currency, error.getMessage()))
                .mapEmpty();
    }

    @Override
    public Future<Boolean> areRatesStale() {
        String sql = "SELECT COUNT(*) as CNT FROM EXCHANGE_RATE " +
                "WHERE UPDATE_TIME < (CURRENT_TIMESTAMP - INTERVAL '12' HOUR)";
        
        return sqlClient.query(sql)
                .execute()
                .map(result -> {
                    int staleCount = result.iterator().next().getInteger("CNT");
                    boolean stale = staleCount > 0;
                    log.debug("Stale rates check: {} stale rates found", staleCount);
                    return stale;
                })
                .onFailure(error -> log.error("Failed to check if rates are stale: {}", error.getMessage()));
    }
}
