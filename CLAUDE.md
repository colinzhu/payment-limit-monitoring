# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

This repository contains the specification and design for a **Payment Limit Monitoring System** - a financial risk management application that tracks settlement flows from trading systems, calculates aggregated exposure by counterparty and value date, and flags transactions exceeding predefined limits.

**Status**: This is a design/specification repository. No code implementation exists yet.

## Key Design Documents

- `.kiro/specs/payment-limit-monitoring/requirements.md` - Complete functional requirements with acceptance criteria
- `.kiro/specs/payment-limit-monitoring/tech-design.md` - Technical architecture and implementation details

## Technology Stack

- **Framework**: Vert.x
- **Language**: Java
- **Database**: Oracle Database
- **Messaging**: Event-driven via Vert.x event bus (no external MQ)

## Core Architecture Concepts

### The Sequence ID (SEQ_ID) - Critical Concept

Every settlement gets an **auto-incrementing sequence ID** (`SEQ_ID`) that serves as the foundation for consistency:
- Monotonically increasing primary key from `SETTLEMENT` table
- **NOT a version number** - it only increases
- Defines scope: "calculate running totals using all settlements with SEQ_ID ≤ x"
- Used as `REF_ID` in `RUNNING_TOTAL` table
- Oracle sequence must be created with `ORDER` to guarantee sequential generation

### Data Model

**SETTLEMENT** (latest versions only)
- `SEQ_ID` - Auto-incrementing primary key (Oracle sequence with ORDER)
- `SETTLEMENT_ID` - Business settlement identifier
- `SETTLEMENT_VERSION` - Timestamp in long format
- `PTS`, `PROCESSING_ENTITY`, `COUNTERPARTY_ID`, `VALUE_DATE` - Group fields
- `CURRENCY`, `AMOUNT` - Transaction details
- `BUSINESS_STATUS` - PENDING, INVALID, VERIFIED, CANCELLED
- `DIRECTION` - PAY or RECEIVE
- `GROSS_NET` - GROSS or NET
- `IS_OLD` - Flag for old versions (true if not latest version)
- `CREATE_TIME`, `UPDATE_TIME` - Audit timestamps

**SETTLEMENT_HIST** - Old versions archived daily by a batch job

**EXCHANGE_RATE** - Latest rates per currency for USD conversion
- `CURRENCY`, `RATE_TO_USD`, `UPDATE_TIME`
- Only one rate per currency (most recent)

**RUNNING_TOTAL** - Aggregated exposure per group
- `ID` - Auto-incrementing primary key
- `PTS`, `PROCESSING_ENTITY`, `COUNTERPARTY_ID`, `VALUE_DATE` - Group fields
- `RUNNING_TOTAL` - Calculated sum in USD
- `REF_ID` - The settlement sequence ID used for this calculation
- `CREATE_TIME`, `UPDATE_TIME` - Audit timestamps

**ACTIVITIES** - Audit trail for approval workflow
- `PTS`, `PROCESSING_ENTITY`, `SETTLEMENT_ID`, `SETTLEMENT_VERSION`
- `USER_ID`, `USER_NAME`, `ACTION_TYPE`, `ACTION_COMMENT`
- `CREATE_TIME`

**NOTIFICATION_QUEUE** - Uses existing DB queue component for notification messages

### Settlement Ingestion Flow (HTTP Request)

All steps must complete within one HTTP request/response. Any error rejects with 500.

**Step 0: Validate Settlement**
- Required fields: PTS, Processing_Entity, Counterparty_ID, Value_Date, Currency, Amount, Settlement_ID, Settlement_Version, Settlement_Direction, Settlement_Type, Business_Status
- Currency: Valid ISO 4217
- Amount: Numeric
- Direction: PAY or RECEIVE
- Type: GROSS or NET
- Business Status: PENDING, INVALID, VERIFIED, or CANCELLED

**Step 1: Save Settlement**
- Insert to `SETTLEMENT` table
- Get auto-generated `SEQ_ID`
- This ID becomes the event trigger

**Step 2: Mark Old Versions**
```sql
UPDATE SETTLEMENT SET IS_OLD = true, UPDATE_TIME = CURRENT_TIMESTAMP
WHERE SETTLEMENT_ID = ? AND PTS = ? AND PROCESSING_ENTITY = ?
  AND SETTLEMENT_VERSION < (SELECT MAX(SETTLEMENT_VERSION) FROM SETTLEMENT WHERE SETTLEMENT_ID = ? AND PTS = ? AND PROCESSING_ENTITY = ?)
  AND IS_OLD IS NULL
```

**Step 3: Detect Counterparty Changes**
```sql
-- Check if counterparty changed from previous version
SELECT COUNTERPARTY_ID FROM SETTLEMENT
WHERE ID = (SELECT MAX(ID) FROM SETTLEMENT WHERE SETTLEMENT_ID = ? AND PTS = ? AND PROCESSING_ENTITY = ? AND ID < ?)
  AND COUNTERPARTY_ID != ?
```

**Step 4: Generate Events**
- Default: 1 event (current group)
- If counterparty changed: 2 events (old group + new group)
- Event format: `PTS`, `PROCESSING_ENTITY`, `COUNTERPARTY_ID`, `VALUE_DATE`, `SEQ_ID`
- Sent to Vert.x event bus

**Step 5: Calculate Running Total**
- Single SQL to select settlements and calculate total
- **Filters**: Group fields + Business Status (from rules) + Direction (from rules) + Latest version only + `ID` ≤ `SEQ_ID`
- **Calculation**: JOIN with `EXCHANGE_RATE`, SUM amounts in USD
- **Update**:
```sql
UPDATE RUNNING_TOTAL SET RUNNING_TOTAL = ?, UPDATE_TIME = CURRENT_TIMESTAMP, REF_ID = ?
WHERE PTS = ? AND PROCESSING_ENTITY = ? AND COUNTERPARTY_ID = ? AND VALUE_DATE = ?
AND REF_ID <= ? -- ensures idempotency and prevents concurrent updates
```

### Exchange Rate Behavior

**Daily Fetch and Storage**
- Fetched daily from external system
- Stored in `EXCHANGE_RATE` table with `UPDATE_TIME`
- Only latest rate per currency kept

**Application Rules**
- Used **only at processing time** when settlement is processed
- Each settlement's USD equivalent is **fixed** at that moment
- Rate changes only affect **future** settlements, not historical calculations

**MVP vs Advanced**
- **MVP**: Fixed 500M USD limit for all counterparties
- **Advanced**: Fetch counterparty-specific limits daily
- When limits updated: re-evaluate all affected settlement groups

### Filtering Rules

**Rule Fetching and Caching**
- Fetched every 5 minutes from external rule system
- Cached in memory for settlements received between fetches
- Determines which `BUSINESS_STATUS` values are included

**Inclusion Criteria** (PAY settlements included if):
- `DIRECTION` = PAY
- `BUSINESS_STATUS` IN (PENDING, INVALID, VERIFIED)

**Exclusion Criteria**:
- `DIRECTION` = RECEIVE
- `BUSINESS_STATUS` = CANCELLED

**Rule Update Handling**
- New rules only apply to new calculations
- Existing running totals NOT automatically updated
- Manual recalculation required for historical data

### Manual Trigger Recalculation

**User Request**
- Provides criteria: `PTS`, `PROCESSING_ENTITY`, `VALUE_DATE` range

**Event Generation**
```sql
-- Find groups matching criteria
SELECT DISTINCT PTS, PROCESSING_ENTITY, COUNTERPARTY_ID, VALUE_DATE
FROM RUNNING_TOTAL
WHERE ... (user criteria)
```
For each group: Generate event with `SEQ_ID` = current max ID in `SETTLEMENT`

**Requirements**
- Admin/supervisor privileges required
- Logged with user ID, timestamp, scope, reason

### Status Calculation (On-Demand, Not Stored)

**Algorithm** (computed at query time):
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

### Approval Workflow (Two-Step with Segregation)

**Two-Step Process**
1. **User A**: REQUEST RELEASE → PENDING_AUTHORISE
2. **User B**: AUTHORISE → AUTHORISED (must be different user)

**Eligibility Rules** (must meet ALL):
- `BUSINESS_STATUS` = VERIFIED
- `DIRECTION` = PAY
- Current status = BLOCKED (subtotal > limit)

**Not eligible**:
- RECEIVE settlements (always CREATED)
- CANCELLED settlements (always CREATED)
- PENDING/INVALID settlements (must be VERIFIED first)
- CREATED settlements (not blocked)

**Security Enforcement**
- **User segregation**: Same user ID cannot perform both REQUEST and AUTHORISE
- **Identity tracking**: User ID (not session) stored in audit trail
- **Audit verification**: System checks audit trail before allowing AUTHORISE
- **Bulk actions**: Only for VERIFIED settlements in same group

**Status Transitions**
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

**Reset Conditions**
- When settlement receives new version
- All previous approval actions invalidated (ignored)
- Status recalculated based on new data

### Notification System

**When Status Becomes AUTHORISED**
1. Send notification to external system
2. If unavailable: **exponential backoff**
3. Retry sequence: 1min, 2min, 4min, 8min, 16min, 32min, 64min...
4. Maximum 24 hours of retry attempts
5. After 24h: mark as failed, log for manual intervention

### Search & Export

**Search Interface** - Multi-criteria filtering:
- `PTS`, `PROCESSING_ENTITY`, `VALUE_DATE` range, `COUNTERPARTY_ID`
- `SETTLEMENT_DIRECTION`, `SETTLEMENT_TYPE`, `BUSINESS_STATUS`
- Settlement status filters (exceeding limit, not exceeding, all)

**Search Results Display**
- **Upper section**: Settlement Groups (group ID, running total, limit, % used, count)
- **Lower section**: Individual Settlements (click to expand)

**Export to Excel**
- Filtered search results
- All settlement details + calculated status + group running total
- Status calculated at export time

### External API Requirements

**Query by Settlement ID**
```
GET /settlement/{settlementId}
Response: Settlement details + calculated status
```

**Manual Recalculation Trigger**
```
POST /recalculate
Body: { "pts", "processingEntity", "counterpartyId", "valueDateFrom", "valueDateTo", "reason" }
Response: { "status": "COMPLETED" }
```

**Notification on AUTHORISED**
```
POST /external/notification
Body: { "settlementId", "status": "AUTHORISED", "timestamp", "details" }
```

### Distributed Processing & Consistency

**Single-Threaded Processor**
- Eliminates race conditions
- One batch processor runs at a time
- Database transactions ensure atomicity

**Fault Tolerance**
- Survive restarts without data loss
- Resume processing after failures
- Maintain data integrity across instances

**Idempotency**
- Settlement processing handles duplicates
- Version ordering by `SETTLEMENT_VERSION`
- Locking for group recalculations

### Key Design Principles

| Principle | Why |
|-----------|-----|
| **Complete Recalculation** | Ensures data consistency over incremental updates |
| **On-Demand Status** | Avoids mass updates, computed when needed |
| **Immutable Settlements** | All versions preserved for audit trail |
| **Event-Driven** | Handles high volume with consistency |
| **Atomic Operations** | All critical operations in transactions |
| **Single-Threaded** | Eliminates race conditions |
| **Sequence ID Ordering** | Monotonic ordering for version control |

### Performance Targets

| Metric | Target |
|--------|--------|
| Settlement ingestion | 200K / 30 minutes (~111/sec) |
| Status availability | < 30 seconds from ingestion |
| Subtotal recalculation | < 10 seconds |
| API response (p99) | < 3 seconds |

## Common Pitfalls (Critical)

❌ **Don't store status fields** - Compute on-demand
❌ **Don't use incremental updates** - Always complete recalculation
❌ **Don't skip version history** - Audit requirement
❌ **Don't allow same user to request and authorize** - Security violation
❌ **Don't recalculate historical data on rate changes** - Rates fixed at processing time
❌ **Don't process events concurrently** - Use single-threaded processor
❌ **Don't ignore counterparty changes** - Must trigger dual events

## External Dependencies

- **Exchange Rate System** - Daily fetch of currency rates
- **Rule System** - Fetches filtering rules every 5 minutes
- **External Notification** - POST to external system on AUTHORISED (with retry)

## Implementation Checklist

When code implementation begins:

1. Define complete database schema with all tables and indexes
2. Implement ingestion service with version management
3. Create batch processor with adaptive scheduling and deduplication
4. Build status calculation logic
5. Implement approval workflow with audit enforcement
6. Add search, filtering, and export functionality
7. Create external API endpoints
8. Implement configuration services (rates, limits, rules)
9. Add notification retry mechanism (exponential backoff)
10. Build audit and compliance features
11. Add distributed processing safeguards
12. Write comprehensive tests (unit, integration, performance)

## Architecture
- **Critical**: Use Hexagonal Architecture