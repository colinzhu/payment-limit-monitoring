# Exchange Rate Feature

## Overview
The exchange rate feature automatically fetches currency conversion rates from an external system and stores them in the database. Rates are refreshed every 12 hours to ensure accurate currency conversions for settlement calculations.

## Architecture

### Components

1. **Domain Model**
   - `ExchangeRate` - Value object representing a currency rate to USD

2. **Port Interface**
   - `ExchangeRateRepository` - Output port for exchange rate persistence operations

3. **Adapters**
   - `ExchangeRateHttpAdapter` - Fetches rates from external system (currently returns dummy data)
   - `JdbcExchangeRatePersistenceAdapter` - Persists rates to Oracle database

4. **Service**
   - `ExchangeRateRefreshService` - Manages periodic refresh of exchange rates

### Database Schema

```sql
CREATE TABLE EXCHANGE_RATE (
    CURRENCY VARCHAR2(3) PRIMARY KEY,
    RATE_TO_USD NUMBER(12,6) NOT NULL,
    CREATE_TIME TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    UPDATE_TIME TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);
```

**Key Points:**
- Only one rate per currency (most recent)
- All rates are relative to USD
- `CREATE_TIME` tracks when the rate was first inserted
- `UPDATE_TIME` tracks the last refresh

## Refresh Mechanism

### Timing
- **Initial Refresh**: Performed immediately on application startup
- **Periodic Refresh**: Every 12 hours (43,200,000 milliseconds)

### Process Flow
1. `ExchangeRateRefreshService.start()` is called during application initialization
2. Service immediately fetches rates via `ExchangeRateHttpAdapter`
3. Fetched rates are saved to database via `JdbcExchangeRatePersistenceAdapter`
4. Timer is set to repeat the process every 12 hours
5. On application shutdown, the timer is cancelled

### Error Handling
- If initial refresh fails, application startup fails (critical dependency)
- If periodic refresh fails, error is logged but application continues running
- Previous rates remain available until next successful refresh

## Dummy Data

Currently, the `ExchangeRateHttpAdapter` returns dummy data for testing:

```java
"USD" -> 1.000000  (base currency)
"EUR" -> 1.085000
"GBP" -> 1.270000
"JPY" -> 0.006800
"CHF" -> 1.130000
"CNY" -> 0.138000
"HKD" -> 0.128000
"SGD" -> 0.745000
```

## Integration with Settlement Calculations

Exchange rates are used in the running total calculation:

```sql
SELECT COALESCE(SUM(s.AMOUNT * COALESCE(r.RATE_TO_USD, 1.0)), 0) as RUNNING_TOTAL
FROM SETTLEMENT s
LEFT JOIN EXCHANGE_RATE r ON s.CURRENCY = r.CURRENCY
```

- If a rate exists for the settlement currency, it's used for conversion
- If no rate exists, defaults to 1.0 (assumes USD)
- All running totals are calculated in USD

## Future Enhancements

### Replace Dummy Data with Real HTTP Call

Update `ExchangeRateHttpAdapter.fetchRates()`:

```java
public Future<Map<String, BigDecimal>> fetchRates() {
    WebClient client = WebClient.create(vertx);
    
    return client
        .get(443, "api.exchangerate.com", "/v1/latest")
        .ssl(true)
        .addQueryParam("base", "USD")
        .send()
        .compose(response -> {
            if (response.statusCode() == 200) {
                JsonObject body = response.bodyAsJsonObject();
                Map<String, BigDecimal> rates = parseRates(body);
                return Future.succeededFuture(rates);
            } else {
                return Future.failedFuture("HTTP " + response.statusCode());
            }
        });
}
```

### Configuration Options

Add to `application.yml`:

```yaml
exchange-rate:
  refresh-interval-hours: 12
  http-endpoint: "https://api.exchangerate.com/v1/latest"
  timeout-seconds: 30
  retry-attempts: 3
```

### Monitoring

Add metrics:
- Last successful refresh timestamp
- Number of rates fetched
- Refresh failure count
- Stale rate alerts (rates older than 24 hours)

## Testing

### Unit Test
```bash
mvn test -Dtest=ExchangeRateHttpAdapterTest
```

### Manual Testing

1. Start the application
2. Check logs for initial refresh:
   ```
   Starting Exchange Rate Refresh Service (interval: 12 hours)
   Refreshing exchange rates...
   Fetched 8 exchange rates
   Saving 8 exchange rates to database
   All exchange rates saved successfully
   Exchange rates refreshed successfully
   ```

3. Query database:
   ```sql
   SELECT * FROM EXCHANGE_RATE ORDER BY CURRENCY;
   ```

4. Verify rates are used in calculations:
   ```sql
   SELECT s.CURRENCY, s.AMOUNT, r.RATE_TO_USD, 
          s.AMOUNT * COALESCE(r.RATE_TO_USD, 1.0) as USD_AMOUNT
   FROM SETTLEMENT s
   LEFT JOIN EXCHANGE_RATE r ON s.CURRENCY = r.CURRENCY;
   ```

## Migration

For existing databases, run the migration script:

```bash
sqlplus tvpc/tvpc123@//localhost:1521/FREEPDB1 @database/migration_add_exchange_rate_create_time.sql
```

This adds the `CREATE_TIME` column to existing `EXCHANGE_RATE` tables.
