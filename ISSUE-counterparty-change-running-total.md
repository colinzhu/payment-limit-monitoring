# ISSUE: Counterparty Change Running Total Not Zeroed for Old Group

## Problem Description

When a settlement's counterparty changes (e.g., from CP-ABC to CP-ABCx), the old group's running total was not being recalculated to 0, even though all settlements in that group were marked as IS_OLD=1.

### Expected Behavior

After counterparty change:
- **Old group (CP-ABC)**: Running total should be 0 (no active settlements)
- **New group (CP-ABCx)**: Running total should reflect the new settlement amount

### Actual Behavior

After counterparty change:
- **Old group (CP-ABC)**: Running total remained at previous value (e.g., 2,170,000 USD)
- **New group (CP-ABCx)**: Running total was correct (e.g., 3,255,000 USD)

## Test Scenario

### Sequence of Events

1. **First settlement**: SETT-12345, CP-ABC, 1,000,000 EUR
   - Settlement ID: 37
   - Running total: 1,085,000 USD (1M × 1.085)

2. **New version**: SETT-12345, CP-ABC, 2,000,000 EUR
   - Settlement ID: 38
   - IS_OLD: ID 37 = 1, ID 38 = 0
   - Running total: 2,170,000 USD (2M × 1.085)

3. **Counterparty change**: SETT-12345, CP-ABCx, 3,000,000 EUR
   - Settlement ID: 39
   - IS_OLD: ID 37 = 1, ID 38 = 1, ID 39 = 0
   - **Expected**: CP-ABC = 0, CP-ABCx = 3,255,000
   - **Actual (before fix)**: CP-ABC = 2,170,000, CP-ABCx = 3,255,000

## Root Cause Analysis

### Issue 1: Missing IS_OLD Filter in Repository Query

**File**: `src/main/java/com/tvpc/repository/impl/JdbcSettlementRepository.java`

**Method**: `findByGroupWithFilters()`

**Problem**: The SQL query found the latest version by `MAX(SETTLEMENT_VERSION)`, but didn't filter out IS_OLD=1 records.

```sql
-- BEFORE (incorrect)
SELECT s.* FROM SETTLEMENT s
INNER JOIN (
  SELECT SETTLEMENT_ID, MAX(SETTLEMENT_VERSION) as max_version
  FROM SETTLEMENT
  WHERE PTS = ? AND PROCESSING_ENTITY = ? AND COUNTERPARTY_ID = ? AND VALUE_DATE = ?
    AND ID <= ?
  GROUP BY SETTLEMENT_ID
) latest ON s.SETTLEMENT_ID = latest.SETTLEMENT_ID AND s.SETTLEMENT_VERSION = latest.max_version
WHERE s.DIRECTION = 'PAY' AND s.BUSINESS_STATUS != 'CANCELLED'
```

**Why it failed**: When counterparty changed, the query for CP-ABC group:
1. Inner subquery: Finds SETT-12345 with MAX version = 3,000,000 (from ID 32, which has CP-ABC)
2. But ID 32 has IS_OLD=1!
3. Outer join matches ID 32
4. Returns IS_OLD=1 record, which should be excluded

### Issue 2: Only One Group Processed Synchronously

**File**: `src/main/java/com/tvpc/service/SettlementIngestionService.java`

**Method**: `executeIngestionSteps()`

**Problem**: When counterparty changed, the service:
1. Generated 2 events (for both old and new groups)
2. Published events to event bus
3. Called `calculateRunningTotal()` for current group only
4. Old group was never processed synchronously

```java
// BEFORE (incorrect)
// Step 4: Generate Events
generateEvents(settlement, result.seqId, result.oldCounterparty);

// Step 5: Calculate Running Total (only for current group)
return calculateRunningTotal(settlement, result.seqId, connection)
        .map(result.seqId);
```

**Why it failed**: The event bus pattern was designed for async processing, but there was no event consumer. The old group's running total was never recalculated.

## Solution

### Fix 1: Add IS_OLD Filter to Repository Query

```java
// AFTER (correct)
String sql = "SELECT s.* FROM SETTLEMENT s " +
        "INNER JOIN (" +
        "  SELECT SETTLEMENT_ID, MAX(SETTLEMENT_VERSION) as max_version " +
        "  FROM SETTLEMENT " +
        "  WHERE PTS = ? AND PROCESSING_ENTITY = ? AND COUNTERPARTY_ID = ? AND VALUE_DATE = ? " +
        "  AND ID <= ? " +
        "  AND (IS_OLD IS NULL OR IS_OLD = 0) " +  // <-- ADDED
        "  GROUP BY SETTLEMENT_ID" +
        ") latest ON s.SETTLEMENT_ID = latest.SETTLEMENT_ID AND s.SETTLEMENT_VERSION = latest.max_version " +
        "WHERE s.DIRECTION = 'PAY' AND s.BUSINESS_STATUS != 'CANCELLED' " +
        "AND (s.IS_OLD IS NULL OR s.IS_OLD = 0) " +  // <-- ADDED
        "ORDER BY s.SETTLEMENT_ID";
```

### Fix 2: Process Both Groups When Counterparty Changes

```java
// AFTER (correct)
// Step 4: Generate Events
generateEvents(settlement, result.seqId, result.oldCounterparty);

// Step 5: Calculate Running Total for current group
Future<Void> runningTotalFuture = calculateRunningTotal(settlement, result.seqId, connection);

// If counterparty changed, also calculate for old group
if (result.oldCounterparty.isPresent() && !result.oldCounterparty.get().equals(settlement.getCounterpartyId())) {
    runningTotalFuture = runningTotalFuture.compose(v ->
        calculateRunningTotalForGroup(
                settlement.getPts(),
                settlement.getProcessingEntity(),
                result.oldCounterparty.get(),
                settlement.getValueDate(),
                result.seqId,
                connection
        )
    );
}

return runningTotalFuture.map(result.seqId);
```

### Additional Changes

1. **Added new method** `calculateRunningTotalForGroup()` to calculate running total for any group
2. **Refactored** `processSettlementsRecursively()` to accept group parameters instead of Settlement object

## Verification

### Test Results

```
SETTLEMENT Table:
ID  SETTLEMENT_ID  COUNTERPARTY_ID  AMOUNT  IS_OLD
37  SETT-12345     CP-ABC           1M      1
38  SETT-12345     CP-ABC           2M      1
39  SETT-12345     CP-ABCx          3M      0

RUNNING_TOTAL Table:
PTS  PROCESSING_ENTITY  COUNTERPARTY_ID  RUNNING_TOTAL  REF_ID
PTS-A  PE-001          CP-ABC           0              39  ✓ CORRECT
PTS-A  PE-001          CP-ABCx          3,255,000      39  ✓ CORRECT
```

### Application Logs (Counterparty Change)

```
Step 3: Detected counterparty change: Optional[CP-ABC]
Step 4: Generating events
Counterparty changed from CP-ABC to CP-ABCx, triggering recalculation for both groups
Publishing 2 settlement events

Step 5a: For CP-ABCx group
Found 1 settlements for group calculation (pts=PTS-A, pe=PE-001, cp=CP-ABCx, vd=2025-12-31)
updateRunningTotal: total=3255000.00, refId=39

Step 5b: For CP-ABC group (old)
Found 0 settlements for group calculation (pts=PTS-A, pe=PE-001, cp=CP-ABC, vd=2025-12-31)
updateRunningTotal: total=0, refId=39
```

## Files Modified

1. `src/main/java/com/tvpc/repository/impl/JdbcSettlementRepository.java`
   - Method: `findByGroupWithFilters()`
   - Added IS_OLD filter to inner and outer queries

2. `src/main/java/com/tvpc/service/SettlementIngestionService.java`
   - Method: `executeIngestionSteps()` - Added dual group processing
   - Method: `calculateRunningTotalForGroup()` - New helper method
   - Method: `processSettlementsRecursively()` - Refactored parameters

## Impact

- **Correctness**: Running totals now correctly reflect only active (IS_OLD=0) settlements
- **Consistency**: Counterparty changes properly update both old and new groups
- **Data Integrity**: Prevents stale running totals from being displayed

## Related Requirements

From `.kiro/specs/payment-limit-monitoring/tech-design.md`:

> **Step 5: Calculate Running Total**
> - **Filters**: Group fields + Business Status (from rules) + Direction (from rules) + **Latest version only** + `ID` ≤ `SEQ_ID`

> **Step 4: Generate Events**
> - Default: 1 event (current group)
> - If counterparty changed: 2 events (old group + new group)

> **Step 2: Mark Old Versions**
> - All previous versions marked with IS_OLD = 1
> - Only latest version should be used for calculations
