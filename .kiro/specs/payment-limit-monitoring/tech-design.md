# Payment Limit Monitoring System - Architecture Design

## Technology Stack
- **Framework**: Vert.x
- **Language**: Java
- **Database**: Oracle Database
- **Messaging**: No MQ (event-driven via Vert.x event bus)

## Architecture
- **Modularized architecture**: 
---

## Core Concept: Sequence ID as REF_ID

The system uses an **auto-incrementing sequence ID** as the foundation for consistency:
- Every settlement saved gets a monotonically increasing sequence ID - `SEQ_ID`
- When creating the `SETTLEMENT` table, it should be defined as `ORDER` for the sequence ID field
- This ID will be used in the `RUNNING_TOTAL` table
- **Critical**: Sequence ID is NOT a version number - it only increases
- `SEQ_ID` defines the scope: "calculate running totals using all settlements with SEQ_ID ≤ x"

---

## Data Model

### SETTLEMENT Table
Stores the **latest version** of each settlement:
- `SEQ_ID` - Auto-incrementing primary key
- `SETTLEMENT_ID` - Business settlement identifier
- `SETTLEMENT_VERSION` - Version number (timestamp in long format)
- `PTS` - Primary Trading System source
- `PROCESSING_ENTITY` - Business unit within trading system
- `COUNTERPARTY_ID` - External party identifier
- `VALUE_DATE` - Settlement settle date
- `CURRENCY` - ISO 4217 currency code
- `AMOUNT` - Transaction amount
- `BUSINESS_STATUS` - PENDING, INVALID, VERIFIED, or CANCELLED
- `DIRECTION` - PAY (outgoing) or RECEIVE (incoming)
- `GROSS_NET` - GROSS (individual) or NET (netted settlement)
- `IS_OLD` - True if this is an old version
- `CREATE_TIME` - Audit timestamp
- `UPDATE_TIME` - Audit timestamp

### SETTLEMENT_HIST Table
Stores **old versions** of settlements (non-latest):
- Same fields as SETTLEMENT table
- Populated by daily archival job that moves non-latest versions
- Indexed by (SETTLEMENT_ID, SETTLEMENT_VERSION) for efficient querying

### EXCHANGE_RATE Table
Stores **latest** exchange rates for currency conversion:
- `CURRENCY` - ISO 4217 currency code
- `RATE_TO_USD` - Conversion rate to USD
- `UPDATE_TIME` - When rate was last updated
- Only one rate per currency kept (most recent)

### EVENT format (no need to store in DB)
Triggers for running total recalculation:
- `PTS`, `PROCESSING_ENTITY`, `COUNTERPARTY_ID`, `VALUE_DATE` - Group identifier
- `SEQ_ID` - `SEQ_ID` of the settlement, which triggered this event

### RUNNING_TOTAL Table
Stores aggregated exposure data:
- `ID` - Auto-incrementing primary key
- `PTS`, `PROCESSING_ENTITY`, `COUNTERPARTY_ID`, `VALUE_DATE` - Group fields
- `RUNNING_TOTAL` - Calculated sum in USD
- `REF_ID` - The settlement sequence ID used for this calculation
- `CREATE_TIME` - When this total was first calculated
- `UPDATE_TIME` - When this total was last updated

### ACTIVITIES Table (Audit table)
Records approval workflow and system actions:
- `ID` - Auto-incrementing primary key
- `PTS` - Trading system
- `PROCESSING_ENTITY` - Business unit within trading system
- `SETTLEMENT_ID` - Which settlement was affected
- `SETTLEMENT_VERSION` - Version number`
- `USER_ID` - User who performed the action
- `USER_NAME` - User who performed the action
- `ACTION_TYPE` - REQUEST_RELEASE, AUTHORISE, or other actions
- `ACTION_COMMENT` - Additional context about the action
- `CREATE_TIME` - When action occurred

### NOTIFICATION_QUEUE Table
Will use existing DB queue component to store the notification messages.

---

## Settlement Ingestion Flow
When a settlement arrives from an HTTP request (Vert.x HTTP server), it goes through the following steps. If there are any errors, reject the settlement with 500 status code.

### Step 0: Validate Settlement
Before saving, validate all required fields:
- **Required fields**: PTS, Processing_Entity, Counterparty_ID, Value_Date, Currency, Amount, Settlement_ID, Settlement_Version, Settlement_Direction, Settlement_Type, Business_Status
- **Currency**: Must be valid ISO 4217 code
- **Amount**: Must be numeric value
- **Date**: Must be valid format
- **Direction**: Must be PAY or RECEIVE
- **Type**: Must be GROSS or NET
- **Business Status**: Must be PENDING, INVALID, VERIFIED, or CANCELLED

If validation fails: Reject settlement and log error for investigation.

### Step 1: Save Settlement
When a settlement arrives:
1. Insert to `SETTLEMENT` table
2. Get the auto-generated sequence ID
3. This ID becomes the `SEQ_ID` for event generation

### Step 2: Mark `IS_OLD` for old versions
**Mark Old**:
```sql
UPDATE SETTLEMENT SET IS_OLD = true, UPDATE_TIME = CURRENT_TIMESTAMP
WHERE SETTLEMENT_ID = ? AND PTS = ? AND PROCESSING_ENTITY = ? 
  AND SETTLEMENT_VERSION < (SELECT MAX(SETTLEMENT_VERSION) FROM SETTLEMENT WHERE SETTLEMENT_ID = ? AND PTS = ? AND PROCESSING_ENTITY = ?)
  AND IS_OLD IS NULL 
```
- Immediate after the new settlement is saved.
- Old records will be moved to `SETTLEMENT_HIST` daily non-busy hours
- Question: how about move old directly to `SETTLEMENT_HIST` without marking old? - Answer: No, because detect `COUNTERPARTY_ID` change needs to check the old record

### Step 3: Detect Counterparty Changes
**Check for counterparty change**:
```sql
-- Find previous one for the same settlement with different COUNTERPARTY_ID. Only check against the last one record.
SELECT COUNTERPARTY_ID FROM SETTLEMENT
WHERE ID = (SELECT MAX(ID) FROM SETTLEMENT WHERE SETTLEMENT_ID = ? AND PTS = ? AND PROCESSING_ENTITY = ? AND ID < ?)
  AND COUNTERPARTY_ID != ?
```
- Immediate after the Mark Old is completed.

### Step 4: Generate Events
- **Event format**: `PTS`, `PROCESSING_ENTITY`, `COUNTERPARTY_ID`, `VALUE_DATE` and `SEQ_ID` - Sequence ID of current settlement
- **Default**: 1 event
- **If counterparty changed**: 2 events
  - One for old counterparty group
  - One for new counterparty group
- Send to Vert.x event bus

### Step 5: Calculate Running Total
Calculate the running total for the group in the event
- Within one SQL to select the settlements and calculate the total and update the `RUNNING_TOTAL`.
- **Filter settlements**: 
  - `PTS`, `PROCESSING_ENTITY`, `COUNTERPARTY_ID`, `VALUE_DATE` = values from event
  - `BUSINESS_STATUS` values from the rules from external system, e.g. in PENDING, VERIFIED, or CANCELLED
  - `DIRECTION` values from the rules from external system, e.g. = PAY
  - `SETTLEMENT_VERSION` = MAX(SETTLEMENT_VERSION) of current `SETTLEMENT_ID` -- to filter out `COUNTERPARTY_ID` changed record
  - `ID` <= sequence ID of current settlement
- **Calculate running total**:
  - JOIN with `EXCHANGE_RATE` to convert to USD
  - SUM
- **Save to Running Total**:
```sql
UPDATE RUNNING_TOTAL SET RUNNING_TOTAL = ?, UPDATE_TIME = CURRENT_TIMESTAMP, SEQ_ID = ?
WHERE PTS = ? AND PROCESSING_ENTITY = ? AND COUNTERPARTY_ID = ? AND VALUE_DATE = ?
AND SEQ_ID <= ? -- make sure it's "<=" so that can use same ID to trigger recalculation; also used to avoid concurrent updates by another thread/instance
``` 


## Exchange Rate Behavior

### Daily Fetch and Storage
- Fetched daily from external system
- Stored in `EXCHANGE_RATE` table with `UPDATE_TIME`
- Only latest rate per currency kept
- History maintained for audit purposes

### Application Rules
- Used **only at processing time** (when settlement is processed)
- Each settlement's USD equivalent is **fixed** at that moment
- Rate changes only affect **future** settlements, not historical calculations

### MVP vs Advanced Mode
- **MVP**: Fixed 500M USD limit for all counterparties
- **Advanced**: Fetch counterparty-specific limits daily from external system
- When limits updated: re-evaluate all affected settlement groups

---

## Filtering Rules

### Rule Fetching and Caching
- Fetched every 5 minutes from external rule system
- Cached in memory for settlements received between fetches
- Determines which `BUSINESS_STATUS` values are included in calculations

### Inclusion Criteria
Settlements are **included** in running total calculations if:
- `DIRECTION` = PAY
- `BUSINESS_STATUS` IN (PENDING, INVALID, VERIFIED)

Settlements are **excluded** if:
- `DIRECTION` = RECEIVE
- `BUSINESS_STATUS` = CANCELLED

### Rule Update Handling
When new rules are fetched:
1. Will only be applied to in new running total calculations
2. Existing running total calculations will be NOT be automatically updated. Users can manually trigger a recalculation with provide settlement criteria e.g. `PTS`, `PROCESSING_ENTITY`, `VALUE_DATE`
3. Users should avoid trigger a recalculation during the busy hours.

---

## Manual Trigger a Recalculation

### User Request
User provides criteria:
- `PTS`, `PROCESSING_ENTITY`
- `VALUE_DATE` range

### Event Generation
```sql
-- Find groups matching criteria
SELECT DISTINCT PTS, PROCESSING_ENTITY, COUNTERPARTY_ID, VALUE_DATE
FROM RUNNING_TOTAL
WHERE ... (user criteria)
```

For each group:
- Generate event with `SEQ_ID` = current max ID in `SETTLEMENT` table
- Send the event to event bus for same processing logic to re-calculate

### Requirements
- Admin/supervisor privileges required
- Logged with user ID, timestamp, scope, reason

---

## Status Calculation (On-Demand, Not Stored)

Status is **computed at query time**, never stored in database.

### Algorithm
For a given settlement:
1. Get group running total from `RUNNING_TOTAL` 
2. Get exposure limit (500M default or counterparty-specific)
3. Apply logic:
   ```
   if (direction == RECEIVE || business_status == CANCELLED):
       return CREATED
   if (running total > limit):
       return BLOCKED
   if (audit shows PENDING_AUTHORISE):
       return PENDING_AUTHORISE
   if (audit shows AUTHORISE after PENDING_AUTHORISE):
       return AUTHORISED
   return CREATED
   ```

---

## Approval Workflow (Two-Step with Segregation)

**Critical**: user actions should only be linked to the settlement with specific version

### Two-Step Process
1. **User A**: REQUEST RELEASE → Settlement becomes PENDING_AUTHORISE
   - System records user ID, timestamp, settlement details
   - Audit log entry created in `ACTIVITIES` table
2. **User B**: AUTHORISE → Settlement becomes AUTHORISED
   - System verifies user B ≠ user A
   - Records authorization with timestamp
   - Triggers notification to external system

### Eligibility Rules
**Settlement must meet ALL criteria:**
- `BUSINESS_STATUS` = VERIFIED
- `DIRECTION` = PAY
- Current status = BLOCKED (subtotal > limit)

**Not eligible:**
- RECEIVE settlements (always CREATED)
- CANCELLED settlements (always CREATED)
- PENDING/INVALID settlements (must be VERIFIED first)
- CREATED settlements (not blocked)

### Security Enforcement
- **User segregation**: Same user ID cannot perform both REQUEST and AUTHORISE
- **Identity tracking**: User ID (not session) stored in audit trail
- **Audit verification**: System checks audit trail before allowing AUTHORISE
- **Bulk actions**: Only allowed for VERIFIED settlements in same group
  - Group = same PTS, Processing_Entity, Counterparty_ID, Value_Date
  - Each settlement gets individual audit entry

### Status Transitions
```
CREATED (subtotal ≤ limit)
  ↓ [subtotal exceeds limit]
BLOCKED (PAY + VERIFIED)
  ↓ [User A: REQUEST RELEASE]
PENDING_AUTHORISE
  ↓ [User B: AUTHORISE]
AUTHORISED
  ↓ [new version arrives]
RESET to CREATED/BLOCKED based on new data
```

### Reset Conditions
**Critical**: the database doesn't store the STATUS, so "reset" is only to ignore previous approval actions
Whenever settlement receives new version.
- **Result**: All previous approval actions invalidated (ignored), status recalculated

---

## Search & Export

### Search Interface
**Multi-criteria filtering:**
- `PTS` - Primary Trading System
- `PROCESSING_ENTITY` - Business unit
- `VALUE_DATE` - Date range
- `COUNTERPARTY_ID` - Specific counterparty
- `SETTLEMENT_DIRECTION` - PAY or RECEIVE
- `SETTLEMENT_TYPE` - GROSS or NET
- `BUSINESS_STATUS` - PENDING, INVALID, VERIFIED, CANCELLED

**Settlement status filters:**
- Only PAY settlements with status not CANCELLED in groups exceeding limit
- Only PAY settlements that do not exceed limit
- All settlements regardless of direction and business status

### Search Results Display
**Upper section - Settlement Groups:**
- Group identifier (PTS, Processing_Entity, Counterparty_ID, Value_Date)
- Running total in USD (calculated from PAY + not CANCELLED)
- Exposure limit
- Percentage of limit used
- Number of settlements in group

**Lower section - Individual Settlements:**
When user clicks a group, display all settlements:
- Settlement_ID, Settlement_Version
- Amount, Currency, USD equivalent
- Direction (PAY/RECEIVE) - color coded
- Type (GROSS/NET) - with indicator for NET that can change direction
- Business Status (PENDING-yellow, INVALID-orange, VERIFIED-green, CANCELLED-red)
- Current calculated status (CREATED/BLOCKED/PENDING_AUTHORISE/AUTHORISED)
- Approval workflow info (if applicable)

### Export to Excel
**Functionality:**
- Export filtered search results
- All settlement details included
- Status information calculated at export time
- Settlements of all directions and business statuses
- Columns: All fields + calculated status + group running total

### User Interface Requirements
- Paginated results for large datasets
- Visual indicators for settlement types
- Color coding for statuses
- Group/subgroup hierarchy
- Click-to-expand group details
- Real-time status calculation display

---

## External API Requirements

### Query by Settlement ID
```
GET /settlement/{settlementId}
Response: Settlement details + calculated status
```

**Response includes:**
- Settlement data (all fields)
- Current calculated status (CREATED, BLOCKED, PENDING_AUTHORISE, AUTHORISED)
- If BLOCKED: group running total, exposure limit, counterparty details
- If PENDING_AUTHORISE or AUTHORISED: approval workflow info (timestamps, users)

**Error handling:**
- 404 if Settlement_ID not found with clear message

### Manual Recalculation Trigger
```
POST /recalculate
Body: {
  "pts": "string",
  "processingEntity": "string",
  "counterpartyId": "string",
  "valueDateFrom": "date",
  "valueDateTo": "date",
  "reason": "string"
}
Response: { "jobId": "12345", "status": "PENDING" }
```

**Requirements:**
- Admin/supervisor privileges required
- Logs: user ID, timestamp, scope, reason
- Returns job ID for tracking progress
- Same batch processor handles recalculation

### Notification on AUTHORISED
```
POST /external/notification
Body: {
  "settlementId": "string",
  "status": "AUTHORISED",
  "timestamp": "ISO8601",
  "details": { ... }
}
```

**Retry policy:**
- Exponential backoff: 1min, 2min, 4min, 8min, 16min, 32min, 64min...
- Maximum 24 hours
- After 24h: mark as FAILED, log for manual intervention
- Failed notifications visible in admin dashboard

---

## Notification System

### When Status Becomes AUTHORISED
1. Send notification to external system
2. If unavailable: **exponential backoff**
3. Retry sequence: 1min, 2min, 4min, 8min, 16min, 32min, 64min...
4. Maximum 24 hours of retry attempts
5. After 24h: mark as failed, log for manual intervention

---

## Performance Targets

| Metric | Target |
|--------|--------|
| Settlement ingestion | 200K / 30 minutes (~111/sec) |
| Status availability | < 30 seconds from ingestion |
| Subtotal recalculation | < 10 seconds |
| API response (p99) | < 3 seconds |

---

## Distributed Processing & Consistency

### Single-Threaded Processor
- **Eliminates race conditions**
- One batch processor runs at a time
- Database transactions ensure atomicity

### Fault Tolerance
- Survive restarts without data loss
- Resume processing after failures
- Maintain data integrity across instances

### Idempotency
- Settlement processing handles duplicates
- Version ordering by `SETTLEMENT_VERSION`
- Locking for group recalculations

---

## Key Design Principles

| Principle | Why |
|-----------|-----|
| **Complete Recalculation** | Ensures data consistency over incremental updates |
| **On-Demand Status** | Avoids mass updates, computed when needed |
| **Immutable Settlements** | All versions preserved for audit trail |
| **Event-Driven** | Handles high volume with consistency |
| **Atomic Operations** | All critical operations in transactions |
| **Single-Threaded** | Eliminates race conditions |
| **Sequence ID Ordering** | Monotonic ordering for version control |

---

## Common Pitfalls to Avoid

❌ **Don't store status fields** - Compute on-demand
❌ **Don't use incremental updates** - Always complete recalculation
❌ **Don't skip version history** - Audit requirement
❌ **Don't allow same user to request and authorize** - Security violation
❌ **Don't recalculate historical data on rate changes** - Rates fixed at processing time
❌ **Don't process events concurrently** - Use single-threaded processor
❌ **Don't ignore counterparty changes** - Must trigger dual events

---

## Implementation Checklist

When development begins:

1. ✅ Define complete database schema with all tables and indexes
2. ✅ Implement ingestion service with version management
3. ✅ Create batch processor with adaptive scheduling and deduplication
4. ✅ Build status calculation logic
5. ✅ Implement approval workflow with audit enforcement
6. ✅ Add search, filtering, and export functionality
7. ✅ Create external API endpoints
8. ✅ Implement configuration services (rates, limits, rules)
9. ✅ Add notification retry mechanism
10. ✅ Build audit and compliance features
11. ✅ Add distributed processing safeguards
12. ✅ Write comprehensive tests (unit, integration, performance)
