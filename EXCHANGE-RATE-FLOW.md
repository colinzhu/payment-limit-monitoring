# Exchange Rate System Flow

## Component Architecture

```
┌─────────────────────────────────────────────────────────────────┐
│                     Application Startup                          │
└─────────────────────────────────────────────────────────────────┘
                              │
                              ▼
┌─────────────────────────────────────────────────────────────────┐
│              HttpServerVerticle.initializeServices()             │
│  • Creates ExchangeRateHttpAdapter                               │
│  • Creates JdbcExchangeRatePersistenceAdapter                    │
│  • Creates ExchangeRateRefreshService                            │
└─────────────────────────────────────────────────────────────────┘
                              │
                              ▼
┌─────────────────────────────────────────────────────────────────┐
│          ExchangeRateRefreshService.start()                      │
│  • Triggers immediate refresh                                    │
│  • Sets up 12-hour periodic timer                                │
└─────────────────────────────────────────────────────────────────┘
                              │
                              ▼
┌─────────────────────────────────────────────────────────────────┐
│                    Initial Refresh Flow                          │
└─────────────────────────────────────────────────────────────────┘
```

## Refresh Flow (Initial & Periodic)

```
┌──────────────────────────────────────────────────────────────────┐
│  ExchangeRateRefreshService.refreshRates()                       │
└──────────────────────────────────────────────────────────────────┘
                              │
                              ▼
┌──────────────────────────────────────────────────────────────────┐
│  ExchangeRateHttpAdapter.fetchRates()                            │
│  ┌────────────────────────────────────────────────────────────┐  │
│  │ TODO: HTTP GET https://api.example.com/rates?base=USD     │  │
│  │ Currently: Returns dummy data                              │  │
│  │   USD -> 1.000000                                          │  │
│  │   EUR -> 1.085000                                          │  │
│  │   GBP -> 1.270000                                          │  │
│  │   JPY -> 0.006800                                          │  │
│  │   CHF -> 1.130000                                          │  │
│  │   CNY -> 0.138000                                          │  │
│  │   HKD -> 0.128000                                          │  │
│  │   SGD -> 0.745000                                          │  │
│  └────────────────────────────────────────────────────────────┘  │
└──────────────────────────────────────────────────────────────────┘
                              │
                              ▼
                    Map<String, BigDecimal>
                              │
                              ▼
┌──────────────────────────────────────────────────────────────────┐
│  ExchangeRateRefreshService.saveAllRates()                       │
│  • Iterates through each currency                                │
│  • Calls repository.saveRate() for each                          │
└──────────────────────────────────────────────────────────────────┘
                              │
                              ▼
┌──────────────────────────────────────────────────────────────────┐
│  JdbcExchangeRatePersistenceAdapter.saveRate()                   │
│  ┌────────────────────────────────────────────────────────────┐  │
│  │ MERGE INTO EXCHANGE_RATE                                   │  │
│  │ USING (SELECT ? as CURRENCY, ? as RATE, ? as TIME)         │  │
│  │ ON (CURRENCY = ?)                                          │  │
│  │ WHEN MATCHED THEN UPDATE SET                               │  │
│  │   RATE_TO_USD = ?, UPDATE_TIME = ?                         │  │
│  │ WHEN NOT MATCHED THEN INSERT                               │  │
│  │   (CURRENCY, RATE_TO_USD, CREATE_TIME, UPDATE_TIME)        │  │
│  └────────────────────────────────────────────────────────────┘  │
└──────────────────────────────────────────────────────────────────┘
                              │
                              ▼
┌──────────────────────────────────────────────────────────────────┐
│                    EXCHANGE_RATE Table                           │
│  ┌────────────┬──────────────┬─────────────┬─────────────┐      │
│  │ CURRENCY   │ RATE_TO_USD  │ CREATE_TIME │ UPDATE_TIME │      │
│  ├────────────┼──────────────┼─────────────┼─────────────┤      │
│  │ USD        │ 1.000000     │ 2026-01-05  │ 2026-01-05  │      │
│  │ EUR        │ 1.085000     │ 2026-01-05  │ 2026-01-05  │      │
│  │ GBP        │ 1.270000     │ 2026-01-05  │ 2026-01-05  │      │
│  │ ...        │ ...          │ ...         │ ...         │      │
│  └────────────┴──────────────┴─────────────┴─────────────┘      │
└──────────────────────────────────────────────────────────────────┘
```

## Periodic Refresh Timer

```
Application Running
        │
        │ Every 12 hours
        ▼
┌─────────────────────────────────────────┐
│  Vert.x Timer Fires                     │
│  vertx.setPeriodic(12h, handler)        │
└─────────────────────────────────────────┘
        │
        ▼
┌─────────────────────────────────────────┐
│  refreshRates() called automatically    │
└─────────────────────────────────────────┘
        │
        ▼
    [Same flow as above]
```

## Usage in Settlement Calculation

```
Settlement Ingestion
        │
        ▼
┌──────────────────────────────────────────────────────────────────┐
│  Calculate Running Total                                         │
│  ┌────────────────────────────────────────────────────────────┐  │
│  │ SELECT COALESCE(                                           │  │
│  │   SUM(s.AMOUNT * COALESCE(r.RATE_TO_USD, 1.0)),           │  │
│  │   0                                                        │  │
│  │ ) as RUNNING_TOTAL                                         │  │
│  │ FROM SETTLEMENT s                                          │  │
│  │ LEFT JOIN EXCHANGE_RATE r ON s.CURRENCY = r.CURRENCY      │  │
│  │ WHERE ...                                                  │  │
│  └────────────────────────────────────────────────────────────┘  │
└──────────────────────────────────────────────────────────────────┘
        │
        ▼
┌──────────────────────────────────────────────────────────────────┐
│  Example Calculation:                                            │
│  • Settlement: 1000 EUR                                          │
│  • Exchange Rate: 1.085 (EUR to USD)                             │
│  • USD Amount: 1000 * 1.085 = 1085.00 USD                        │
│  • Added to running total                                        │
└──────────────────────────────────────────────────────────────────┘
```

## Error Handling Flow

```
┌──────────────────────────────────────────────────────────────────┐
│  Refresh Triggered                                               │
└──────────────────────────────────────────────────────────────────┘
        │
        ▼
┌──────────────────────────────────────────────────────────────────┐
│  HTTP Fetch                                                      │
└──────────────────────────────────────────────────────────────────┘
        │
        ├─── Success ──────────────────────────────────────────────┐
        │                                                           │
        │                                                           ▼
        │                                              ┌────────────────────┐
        │                                              │ Save to Database   │
        │                                              └────────────────────┘
        │                                                           │
        │                                                           ▼
        │                                              ┌────────────────────┐
        │                                              │ Log Success        │
        │                                              │ Continue Operation │
        │                                              └────────────────────┘
        │
        └─── Failure ──────────────────────────────────────────────┐
                                                                    │
                                                                    ▼
                                                       ┌────────────────────┐
                                                       │ Log Error          │
                                                       └────────────────────┘
                                                                    │
                                                                    ▼
                                                       ┌────────────────────┐
                                                       │ If Startup:        │
                                                       │   App Fails        │
                                                       │ If Periodic:       │
                                                       │   Continue Running │
                                                       │   Use Old Rates    │
                                                       └────────────────────┘
```

## Shutdown Flow

```
┌──────────────────────────────────────────────────────────────────┐
│  Application Shutdown Signal                                     │
└──────────────────────────────────────────────────────────────────┘
        │
        ▼
┌──────────────────────────────────────────────────────────────────┐
│  HttpServerVerticle.stop()                                       │
└──────────────────────────────────────────────────────────────────┘
        │
        ▼
┌──────────────────────────────────────────────────────────────────┐
│  ExchangeRateRefreshService.stop()                               │
│  • Cancels periodic timer                                        │
│  • Prevents further refreshes                                    │
└──────────────────────────────────────────────────────────────────┘
        │
        ▼
┌──────────────────────────────────────────────────────────────────┐
│  Database Connection Pool Closed                                 │
└──────────────────────────────────────────────────────────────────┘
```

## Data Flow Summary

```
External API (Future)
        │
        ▼
Dummy Data (Current) ──────► ExchangeRateHttpAdapter
        │
        ▼
Map<Currency, Rate> ────────► ExchangeRateRefreshService
        │
        ▼
Individual Rates ───────────► JdbcExchangeRatePersistenceAdapter
        │
        ▼
EXCHANGE_RATE Table ────────► Used by Settlement Calculations
        │
        ▼
Running Total in USD
```

## Timeline Example

```
Time: 00:00 - Application Starts
        │
        ├─ 00:00:01 - Initial fetch (8 rates)
        ├─ 00:00:02 - Save to database
        ├─ 00:00:03 - Timer set for 12h
        │
Time: 12:00 - First Periodic Refresh
        │
        ├─ 12:00:01 - Fetch rates
        ├─ 12:00:02 - Update database
        │
Time: 24:00 - Second Periodic Refresh
        │
        ├─ 24:00:01 - Fetch rates
        ├─ 24:00:02 - Update database
        │
        ... continues every 12 hours ...
```

## Key Points

1. **Non-blocking**: All operations use Vert.x async/Future pattern
2. **Atomic Updates**: MERGE statement ensures no race conditions
3. **Graceful Degradation**: Missing rates default to 1.0 in calculations
4. **Automatic**: No manual intervention required
5. **Extensible**: Easy to replace dummy data with real API
