# Exchange Rate Quick Start Guide

## Overview
Exchange rates are automatically fetched and refreshed every 12 hours. The system stores the latest rate for each currency in the EXCHANGE_RATE table.

## How It Works

1. **On Startup**: Rates are fetched immediately from external system (currently dummy data)
2. **Storage**: Rates saved to EXCHANGE_RATE table (one per currency)
3. **Refresh**: Automatic refresh every 12 hours
4. **Usage**: Automatically used in settlement calculations to convert to USD

## Quick Verification

### 1. Check Application Logs
```
Starting Exchange Rate Refresh Service (interval: 12 hours)
Fetched 8 exchange rates
Exchange rates refreshed successfully
```

### 2. Query Database
```sql
-- View all rates
SELECT CURRENCY, RATE_TO_USD, UPDATE_TIME 
FROM EXCHANGE_RATE 
ORDER BY CURRENCY;

-- Check when rates were last updated
SELECT MAX(UPDATE_TIME) as LAST_UPDATE 
FROM EXCHANGE_RATE;
```

### 3. Test in Calculation
```sql
-- See how rates are applied
SELECT 
    s.CURRENCY,
    s.AMOUNT,
    r.RATE_TO_USD,
    s.AMOUNT * COALESCE(r.RATE_TO_USD, 1.0) as USD_AMOUNT
FROM SETTLEMENT s
LEFT JOIN EXCHANGE_RATE r ON s.CURRENCY = r.CURRENCY
WHERE ROWNUM <= 10;
```

## Current Dummy Data

| Currency | Rate    | Description        |
|----------|---------|-------------------|
| USD      | 1.00    | US Dollar (base)  |
| EUR      | 1.085   | Euro              |
| GBP      | 1.27    | British Pound     |
| JPY      | 0.0068  | Japanese Yen      |
| CHF      | 1.13    | Swiss Franc       |
| CNY      | 0.138   | Chinese Yuan      |
| HKD      | 0.128   | Hong Kong Dollar  |
| SGD      | 0.745   | Singapore Dollar  |

## Replacing with Real API

Edit `src/main/java/com/tvpc/adapter/out/http/ExchangeRateHttpAdapter.java`:

```java
public Future<Map<String, BigDecimal>> fetchRates() {
    // TODO: Replace this with actual HTTP call
    // Example: Call https://api.exchangerate.com/v1/latest?base=USD
    
    // Current: Returns dummy data
    Map<String, BigDecimal> rates = Map.of(...);
    return Future.succeededFuture(rates);
}
```

## Troubleshooting

### Rates Not Updating
- Check application logs for errors
- Verify database connection
- Check EXCHANGE_RATE table exists

### Missing Currency Rate
- Add currency to `ExchangeRateHttpAdapter.fetchRates()`
- Or configure external API to include currency
- System defaults to 1.0 if rate not found

### Stale Rates
```sql
-- Check for rates older than 12 hours
SELECT CURRENCY, UPDATE_TIME,
       ROUND((SYSDATE - UPDATE_TIME) * 24, 2) as HOURS_OLD
FROM EXCHANGE_RATE
WHERE UPDATE_TIME < (SYSDATE - INTERVAL '12' HOUR);
```

## Manual Refresh

To manually trigger a refresh (future enhancement):
```java
// Add endpoint to HttpServerVerticle
router.post("/admin/refresh-rates").handler(ctx -> {
    exchangeRateRefreshService.refreshRates()
        .onSuccess(v -> ctx.response().end("Rates refreshed"))
        .onFailure(err -> ctx.response().setStatusCode(500).end(err.getMessage()));
});
```

## Configuration

Current settings (hardcoded):
- **Refresh Interval**: 12 hours
- **Base Currency**: USD
- **Currencies**: 8 major currencies

To change refresh interval, edit `ExchangeRateRefreshService.java`:
```java
private static final long REFRESH_INTERVAL_MS = 12 * 60 * 60 * 1000; // 12 hours
```

## Files Modified/Created

### New Files
- `ExchangeRateHttpAdapter.java` - Fetches rates
- `JdbcExchangeRatePersistenceAdapter.java` - Stores rates
- `ExchangeRateRefreshService.java` - Manages refresh
- `ExchangeRateHttpAdapterTest.java` - Unit test

### Modified Files
- `HttpServerVerticle.java` - Wires components
- `schema.sql` - Added CREATE_TIME column

### Existing (Unchanged)
- `ExchangeRate.java` - Domain model
- `ExchangeRateRepository.java` - Port interface

## Architecture

```
External System (HTTP)
        ↓
ExchangeRateHttpAdapter (dummy data)
        ↓
ExchangeRateRefreshService (12h timer)
        ↓
JdbcExchangeRatePersistenceAdapter
        ↓
EXCHANGE_RATE Table
        ↓
Used in Running Total Calculation
```

## Key Features

✅ Automatic refresh every 12 hours  
✅ Initial fetch on startup  
✅ Graceful error handling  
✅ Non-blocking async operations  
✅ Database persistence with MERGE  
✅ Integration with settlement calculations  
✅ Ready for real HTTP API integration  

## Next Steps

1. ✅ Implementation complete with dummy data
2. ⏳ Test with real settlements
3. ⏳ Replace dummy data with real API
4. ⏳ Add monitoring and alerts
5. ⏳ Add manual refresh endpoint
