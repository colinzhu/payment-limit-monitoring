# Payment Limit Monitoring System - Design2 Document

## Overview

This design document presents an alternative, practical implementation approach for the Payment Limit Monitoring System. While the first design document explores architectural patterns and correctness properties, this document focuses on concrete implementation details, database schemas, API contracts, and practical deployment considerations.

The system processes settlements from trading systems, enforces exposure limits, manages approval workflows, and provides search and reporting capabilities for operational teams.

## System Architecture

### High-Level Architecture

```
┌─────────────────────────────────────────────────────────────┐
│                   External Systems                           │
│  ┌──────────┐  ┌──────────┐  ┌──────────┐  ┌──────────┐   │
│  │   PTS    │  │   Rules  │  │ Exchange │  │  Limits  │   │
│  │ Systems  │  │  System  │  │  Rates   │  │  System  │   │
│  └────┬─────┘  └────┬─────┘  └────┬─────┘  └────┬─────┘   │
│       │             │             │             │          │
└───────┼─────────────┼─────────────┼─────────────┼──────────┘
        │             │             │             │
┌───────▼─────────────▼─────────────▼─────────────▼──────────┐
│              API Gateway / Ingestion Layer                  │
│  ┌──────────────────────────────────────────────────────┐  │
│  │  Settlement Ingestion Service (REST + Kafka)         │  │
│  │  - Receive settlements from PTS                      │  │
│  │  - Validate data structure                           │  │
│  │  - Apply filtering rules immediately                 │  │
│  │  - Append to event store                             │  │
│  └──────────────────────────────────────────────────────┘  │
└─────────────────────────────────────────────────────────────┘
        │
        ▼
┌─────────────────────────────────────────────────────────────┐
│                    Core Services                            │
│  ┌──────────────────┐      ┌──────────────────────────┐   │
│  │ Background       │      │   Approval Workflow      │   │
│  │ Processor        │─────▶│   & Status Service       │   │
│  │ (Single Thread)  │      │                          │   │
│  └──────────────────┘      └──────────────────────────┘   │
│         │                          │                        │
│         ▼                          ▼                        │
│  ┌──────────────────┐      ┌──────────────────────────┐   │
│  │   Materialized   │      │      Audit Service       │   │
│  │   Views / DB     │      │   (Immutable Logs)       │   │
│  └──────────────────┘      └──────────────────────────┘   │
└─────────────────────────────────────────────────────────────┘
        │
        ▼
┌─────────────────────────────────────────────────────────────┐
│                    Query & Reporting                        │
│  ┌──────────────────┐      ┌──────────────────────────┐   │
│  │  UI Service      │      │    API Service           │   │
│  │  (Web App)       │      │  (External Queries)      │   │
│  └──────────────────┘      └──────────────────────────┘   │
└─────────────────────────────────────────────────────────────┘
```

## Database Schema Design

### Core Tables

#### 1. Settlements Table (Write-Optimized, Immutable)

```sql
CREATE TABLE settlements (
    settlement_id VARCHAR(100) NOT NULL,
    settlement_version INTEGER NOT NULL,
    pts VARCHAR(50) NOT NULL,
    processing_entity VARCHAR(100) NOT NULL,
    counterparty_id VARCHAR(100) NOT NULL,
    value_date DATE NOT NULL,
    currency VARCHAR(3) NOT NULL,
    amount DECIMAL(20, 2) NOT NULL,
    is_eligible BOOLEAN NOT NULL,
    received_at TIMESTAMP NOT NULL,

    PRIMARY KEY (settlement_id, settlement_version),
    INDEX idx_counterparty (counterparty_id),
    INDEX idx_value_date (value_date),
    INDEX idx_pts_entity (pts, processing_entity),
    INDEX idx_received_at (received_at)
);
```

**Design Decisions:**
- **Composite Primary Key**: (`settlement_id`, `settlement_version`) supports versioning
- **Immutable Records**: Once inserted, records are never updated (only new versions added)
- **is_eligible**: Determined at ingestion time based on current filtering rules
- **Indexes**: Optimized for search by counterparty, date, and PTS entity

#### 2. Settlement Groups Table (Materialized Aggregates)

```sql
CREATE TABLE settlement_groups (
    group_id VARCHAR(255) NOT NULL PRIMARY KEY,
    pts VARCHAR(50) NOT NULL,
    processing_entity VARCHAR(100) NOT NULL,
    counterparty_id VARCHAR(100) NOT NULL,
    value_date DATE NOT NULL,
    subtotal_usd DECIMAL(20, 2) NOT NULL DEFAULT 0,
    settlement_count INTEGER NOT NULL DEFAULT 0,
    exposure_limit DECIMAL(20, 2) NOT NULL,
    exceeds_limit BOOLEAN NOT NULL DEFAULT FALSE,
    last_updated TIMESTAMP NOT NULL,

    INDEX idx_exceeds_limit (exceeds_limit),
    INDEX idx_counterparty (counterparty_id),
    INDEX idx_value_date (value_date)
);
```

**Design Decisions:**
- **Group ID**: Composite key format: `{pts}::{entity}::{counterparty}::{value_date}`
- **Subtotal USD**: Maintained incrementally using atomic operations
- **Exposure Limit**: Current limit applied (500M for MVP, or counterparty-specific)
- **Exceeds Limit**: Denormalized boolean for fast filtering
- **Dynamic Status**: CRETED/BLOCKED computed from subtotal vs limit

#### 3. Settlement Status Table (Approval Tracking)

```sql
CREATE TABLE settlement_approval (
    settlement_id VARCHAR(100) NOT NULL PRIMARY KEY,
    settlement_version INTEGER NOT NULL,
    requested_by VARCHAR(100),
    requested_at TIMESTAMP,
    authorized_by VARCHAR(100),
    authorized_at TIMESTAMP,

    FOREIGN KEY (settlement_id, settlement_version)
        REFERENCES settlements(settlement_id, settlement_version)
);

CREATE INDEX idx_approval_workflow ON settlement_approval(requested_by, authorized_by);
```

**Design Decisions:**
- **Separate Table**: Approval status isolated from settlement data
- **Two-Phase Approval**: Fields for both request and authorize actions
- **User Tracking**: Records who performed each action
- **Version Link**: Tied to specific settlement version for audit

#### 4. Audit Table (Immutable History)

```sql
CREATE TABLE audit_log (
    audit_id BIGSERIAL PRIMARY KEY,
    settlement_id VARCHAR(100) NOT NULL,
    settlement_version INTEGER NOT NULL,
    user_id VARCHAR(100) NOT NULL,
    action VARCHAR(50) NOT NULL,
    action_timestamp TIMESTAMP NOT NULL,
    group_context JSONB NOT NULL,

    INDEX idx_settlement_audit (settlement_id, settlement_version),
    INDEX idx_user_action (user_id, action_timestamp)
);
```

**Design Decisions:**
- **Auto-Increment ID**: Ensures global ordering
- **JSONB Context**: Flexible storage of group state at action time
- **Immutable**: Logs never modified or deleted
- **Indexed**: Fast lookup by settlement or user/action patterns

#### 5. Configuration Tables

```sql
CREATE TABLE exchange_rates (
    from_currency VARCHAR(3) NOT NULL,
    to_currency VARCHAR(3) NOT NULL,
    rate DECIMAL(20, 10) NOT NULL,
    effective_date DATE NOT NULL,
    fetched_at TIMESTAMP NOT NULL,

    PRIMARY KEY (from_currency, to_currency, effective_date)
);

CREATE TABLE exposure_limits (
    counterparty_id VARCHAR(100) PRIMARY KEY,
    limit_amount DECIMAL(20, 2) NOT NULL,
    limit_type VARCHAR(20) NOT NULL,
    effective_date DATE NOT NULL,
    fetched_at TIMESTAMP NOT NULL
);

CREATE TABLE filtering_rules (
    rule_id VARCHAR(100) PRIMARY KEY,
    criteria JSONB NOT NULL,
    is_active BOOLEAN NOT NULL,
    last_updated TIMESTAMP NOT NULL
);
```

## Service Layer Design

### 1. Settlement Ingestion Service

**Endpoint:** `POST /api/v1/settlements/ingest`

**Request:**
```json
{
  "settlementId": "SETL-20250115-001",
  "settlementVersion": 1,
  "pts": "PTS-A",
  "processingEntity": "ENTITY-001",
  "counterpartyId": "CP-12345",
  "valueDate": "2025-02-01",
  "currency": "EUR",
  "amount": 1000000.00
}
```

**Processing Flow:**
```
1. Validate input structure and data types
2. Fetch current filtering rules from cache (Redis) or system
3. Apply rules: isEligible = matchesFilterCriteria(settlement, rules)
4. Insert settlement record with is_eligible flag
5. If eligible: Publish event to message queue for processing
6. Return 202 Accepted with settlement reference
```

**Response:**
```json
{
  "status": "accepted",
  "settlementId": "SETL-20250115-001",
  "processingOrder": 123456,
  "isEligible": true,
  "estimatedProcessingTime": "5-10 seconds"
}
```

### 2. Background Calculation Service (Single-Threaded)

**Pseudocode:**
```typescript
class BackgroundProcessor {
  private lastProcessedId: number = 0;

  async processBatches() {
    while (true) {
      // 1. Fetch new events
      const events = await db.query(`
        SELECT * FROM settlements
        WHERE processing_order > ?
        AND is_eligible = true
        AND processed = false
        ORDER BY processing_order ASC
        LIMIT 500
      `, [this.lastProcessedId]);

      if (events.length === 0) {
        await sleep(5000); // Wait 5 seconds
        continue;
      }

      // 2. Process each event sequentially (no race conditions)
      for (const event of events) {
        await this.processEvent(event);
        this.lastProcessedId = event.processing_order;
      }

      // 3. Mark as processed
      await db.query(
        'UPDATE settlements SET processed = true WHERE processing_order IN (?)',
        [events.map(e => e.processing_order)]
      );
    }
  }

  async processEvent(event: SettlementEvent) {
    // Calculate USD amount
    const rate = await exchangeRateService.getRate(event.currency, 'USD');
    const usdAmount = event.amount * rate;

    // Get group identifier
    const groupId = `${event.pts}::${event.processingEntity}::${event.counterpartyId}::${event.valueDate}`;

    // Atomic update of group subtotal
    await db.query(`
      UPDATE settlement_groups
      SET subtotal_usd = subtotal_usd + ?,
          settlement_count = settlement_count + 1,
          last_updated = NOW()
      WHERE group_id = ?
    `, [usdAmount, groupId]);

    // If group doesn't exist, create it
    const exposureLimit = await limitService.getCurrentLimit(event.counterpartyId);
    await db.query(`
      INSERT INTO settlement_groups
      (group_id, pts, processing_entity, counterparty_id, value_date,
       subtotal_usd, settlement_count, exposure_limit, exceeds_limit, last_updated)
      VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, NOW())
      ON CONFLICT (group_id) DO NOTHING
    `, [groupId, event.pts, event.processingEntity, event.counterpartyId,
        event.valueDate, usdAmount, 1, exposureLimit, usdAmount > exposureLimit]);
  }
}
```

### 3. Approval Workflow Service

**Endpoint:** `POST /api/v1/settlements/{id}/request-release`

**Preconditions:**
- Settlement must be BLOCKED (subtotal > limit)
- No existing approval for this settlement/version
- User must have appropriate permissions

**Processing:**
```typescript
async requestRelease(settlementId: string, userId: string) {
  // 1. Verify settlement status
  const status = await settlementService.getStatus(settlementId);
  if (status !== 'BLOCKED') {
    throw new Error('Settlement is not BLOCKED');
  }

  // 2. Check for existing approval
  const existing = await db.query(
    'SELECT * FROM settlement_approval WHERE settlement_id = ?',
    [settlementId]
  );

  if (existing.length > 0) {
    throw new Error('Approval already in progress');
  }

  // 3. Get current settlement version
  const settlement = await db.query(`
    SELECT settlement_id, settlement_version
    FROM settlements
    WHERE settlement_id = ?
    ORDER BY settlement_version DESC
    LIMIT 1
  `, [settlementId]);

  // 4. Create approval record
  await db.query(`
    INSERT INTO settlement_approval
    (settlement_id, settlement_version, requested_by, requested_at)
    VALUES (?, ?, ?, NOW())
  `, [settlementId, settlement.settlement_version, userId]);

  // 5. Create audit record
  await auditService.logAction({
    settlementId,
    settlementVersion: settlement.settlement_version,
    userId,
    action: 'REQUEST_RELEASE',
    groupContext: await getGroupContext(settlementId)
  });

  return { status: 'PENDING_AUTHORISE' };
}
```

**Endpoint:** `POST /api/v1/settlements/{id}/authorise`

**Additional Precondition:** Different user must perform this action (segregation of duties)

```typescript
async authorise(settlementId: string, userId: string) {
  // 1. Verify approval is pending
  const approval = await db.query(
    'SELECT * FROM settlement_approval WHERE settlement_id = ? AND requested_by IS NOT NULL AND authorized_by IS NULL',
    [settlementId]
  );

  if (approval.length === 0) {
    throw new Error('No pending approval found');
  }

  // 2. Verify different user (seg of duties)
  if (approval[0].requested_by === userId) {
    throw new Error('Same user cannot both request and authorise');
  }

  // 3. Update approval record
  await db.query(`
    UPDATE settlement_approval
    SET authorized_by = ?, authorized_at = NOW()
    WHERE settlement_id = ?
  `, [userId, settlementId]);

  // 4. Create audit record
  await auditService.logAction({
    settlementId,
    settlementVersion: approval[0].settlement_version,
    userId,
    action: 'AUTHORISE',
    groupContext: await getGroupContext(settlementId)
  });

  // 5. Send notification
  await notificationService.sendAuthorizationNotification(settlementId);

  return { status: 'AUTHORISED' };
}
```

### 4. Settlement Status Computation

**Dynamic Status Calculation (No stored status field):**

```typescript
async getStatus(settlementId: string): Promise<SettlementStatus> {
  // 1. Get latest settlement version
  const settlement = await db.query(`
    SELECT * FROM settlements
    WHERE settlement_id = ?
    ORDER BY settlement_version DESC
    LIMIT 1
  `, [settlementId]);

  if (settlement.length === 0) {
    throw new Error('Settlement not found');
  }

  // 2. Check approval status
  const approval = await db.query(
    'SELECT * FROM settlement_approval WHERE settlement_id = ?',
    [settlementId]
  );

  // 3. Get group subtotal
  const groupId = buildGroupId(settlement[0]);
  const group = await db.query(
    'SELECT subtotal_usd, exposure_limit FROM settlement_groups WHERE group_id = ?',
    [groupId]
  );

  if (group.length === 0) {
    return 'CREATED'; // No group = no exceedance
  }

  const { subtotal_usd, exposure_limit } = group[0];
  const exceedsLimit = subtotal_usd > exposure_limit;

  // 4. Compute status
  if (approval.length > 0) {
    const app = approval[0];
    if (app.authorized_by) {
      return 'AUTHORISED';
    } else if (app.requested_by) {
      return 'PENDING_AUTHORISE';
    }
  }

  return exceedsLimit ? 'BLOCKED' : 'CREATED';
}
```

## API Contracts

### Settlement Ingestion

**Request:**
```
POST /api/v1/settlements/ingest
Content-Type: application/json

{
  "settlementId": "string",
  "settlementVersion": "number",
  "pts": "string",
  "processingEntity": "string",
  "counterpartyId": "string",
  "valueDate": "string (YYYY-MM-DD)",
  "currency": "string (ISO 4217)",
  "amount": "number (> 0)"
}
```

**Response (202 Accepted):**
```json
{
  "status": "accepted",
  "settlementId": "SETL-123",
  "processingOrder": 456789,
  "isEligible": true,
  "estimatedProcessingTime": "5-10 seconds"
}
```

**Error Response (400 Bad Request):**
```json
{
  "status": "rejected",
  "reason": "Missing required fields: currency",
  "details": ["currency is required"]
}
```

### Status Query (External System API)

**Request:**
```
GET /api/v1/settlements/{settlementId}/status
```

**Response (200 OK):**
```json
{
  "settlementId": "SETL-123",
  "currentVersion": 3,
  "status": "BLOCKED",
  "groupDetails": {
    "pts": "PTS-A",
    "processingEntity": "ENTITY-1",
    "counterpartyId": "CP-5678",
    "valueDate": "2025-02-01",
    "subtotalUsd": 550000000.00,
    "exposureLimit": 500000000.00,
    "exceedsBy": 50000000.00
  },
  "approvalDetails": null,
  "reason": "Group subtotal 550M USD exceeds limit 500M USD"
}
```

**Statuses:**
- `CREATED`: Within limit, no approval
- `BLOCKED`: Exceeds limit, no approval
- `PENDING_AUTHORISE`: Requested release, awaiting authorization
- `AUTHORISED`: Approved by second user

### Settlement Search

**Request:**
```
GET /api/v1/settlements/search?
  pts=PTS-A&
  processingEntity=ENTITY-1&
  counterpartyId=CP-5678&
  valueDate=2025-02-01&
  status=BLOCKED&
  page=1&
  pageSize=20
```

**Response (200 OK):**
```json
{
  "total": 45,
  "page": 1,
  "pageSize": 20,
  "groups": [
    {
      "groupId": "PTS-A::ENTITY-1::CP-5678::2025-02-01",
      "settlementCount": 45,
      "subtotalUsd": 550000000.00,
      "exposureLimit": 500000000.00,
      "exceedsLimit": true,
      "settlements": [
        {
          "settlementId": "SETL-123",
          "version": 3,
          "currency": "EUR",
          "amount": 1500000.00,
          "usdAmount": 1650000.00,
          "status": "BLOCKED"
        }
        // ... more settlements
      ]
    }
  ]
}
```

### Bulk Approval Actions

**Request:**
```
POST /api/v1/settlements/bulk/request-release
Content-Type: application/json

{
  "settlementIds": ["SETL-123", "SETL-124", "SETL-125"],
  "userId": "OPERATOR-123"
}
```

**Preconditions:**
- All settlements must belong to same group
- All must be in BLOCKED state

**Response (200 OK):**
```json
{
  "status": "success",
  "processed": 3,
  "failed": 0,
  "results": [
    {
      "settlementId": "SETL-123",
      "newStatus": "PENDING_AUTHORISE"
    }
    // ... for each
  ]
}
```

### Manual Recalculation (Admin API)

**Request:**
```
POST /api/v1/admin/recalculate
Content-Type: application/json

{
  "scope": {
    "pts": "PTS-A",
    "processingEntity": "ENTITY-1",
    "fromValueDate": "2025-01-01"
  }
}
```

**Processing:**
```typescript
// 1. Lock all affected groups (prevent concurrent updates)
const groups = await getAffectedGroups(scope);
await acquireGroupLocks(groups);

// 2. For each settlement in scope, re-evaluate eligibility
const settlements = await getSettlementsInScope(scope);

for (const settlement of settlements) {
  // Re-evaluate against current rules
  const isEligible = await ruleEngine.evaluate(settlement);

  if (isEligible !== settlement.is_eligible) {
    // Need to update
    if (isEligible) {
      // Add to group subtotal
      await addContribution(settlement);
    } else {
      // Remove from group subtotal
      await removeContribution(settlement);
    }

    // Update settlement eligibility flag
    await db.query(
      'UPDATE settlements SET is_eligible = ? WHERE settlement_id = ? AND settlement_version = ?',
      [isEligible, settlement.id, settlement.version]
    );
  }
}

// 3. Re-evaluate all affected group limits
await recalculateAllGroupLimits(groups);

// 4. Release locks
await releaseGroupLocks(groups);
```

## Data Flow Patterns

### Settlement Ingestion Flow

```
1. HTTP Request Received
   ↓
2. Validation Layer
   - Schema validation
   - Business rule checks
   ↓
3. Rule Engine Evaluation
   - Fetch current filtering rules
   - Determine eligibility
   ↓
4. Event Store (PostgreSQL)
   - Insert settlement record (immutable)
   - Flag is_eligible
   - Return processing order
   ↓
5. Event Bus (Kafka/Redis Stream)
   - Publish "new settlement" event
   ↓
6. Background Processor
   - Sequential event processing
   - Calculate USD amounts
   - Update group subtotals (atomic)
   ↓
7. Materialized Views Updated
   - Group totals stored
   - Exceeds limit flag updated
   ↓
8. Queries Read from Views
   - Fast lookups
   - No real-time calculation needed
```

### Approval Workflow Flow

```
1. User Views BLOCKED Settlement
   ↓
2. Status Calculation (Dynamic)
   - Read group subtotal
   - Compare with limit
   - Check approval table
   ↓
3. User Clicks "Request Release"
   ↓
4. Approval Service
   - Verify status is BLOCKED
   - Create approval record
   - Log audit entry
   ↓
5. Status Changes to PENDING_AUTHORISE
   ↓
6. Different User Views Settlement
   ↓
7. User Clicks "Authorise"
   ↓
8. Approval Service
   - Verify different user
   - Update approval record
   - Send notification
   - Log audit
   ↓
9. Status Changes to AUTHORISED
   ↓
10. External System Notification
    - Callback to requester
    - Approval details provided
```

### Version Update Flow

```
1. New Version Received (Version 3)
   ↓
2. Validation
   - Check higher version number
   - Validate new data
   ↓
3. Insert New Version
   - Settlement record with version 3
   - is_eligible flag recalculated
   ↓
4. Background Processor Detects Update
   ↓
5. Calculate Net Change
   - Old version contribution: 80M USD
   - New version contribution: 90M USD
   - Delta: +10M USD
   ↓
6. Atomic Group Update
   UPDATE group SET subtotal = subtotal + 10M USD
   ↓
7. Reset Approval
   - Delete existing approval records
   - Status reverts to CREATED/BLOCKED
   ↓
8. Audit Log
   - Record version change
   - Track old and new values
```

## Performance Considerations

### High-Volume Processing (200K settlements / 30 min)

**Ingestion Rate:**
- Target: ~111 settlements per second sustained
- Per-settlement processing: < 10ms for ingestion
- Bulk insert operations where possible

**Background Processing:**
- Processing window: 5-10 seconds for status to reflect
- Batch size: 500-1000 settlements per cycle
- Group-level updates only (no mass settlement record updates)

**Optimization Strategies:**

1. **Write Optimization**
   ```sql
   -- Batch insert settlements
   INSERT INTO settlements (settlement_id, settlement_version, ...)
   VALUES (...), (...), (...);
   ```

2. **Index Strategy**
   - Primary key lookups (fast)
   - Composite indexes for search patterns
   - Partial indexes for active-only queries

3. **Caching Layer (Redis)**
   - Exchange rates (24hr TTL)
   - Filtering rules (5min TTL)
   - Group subtotals (30sec TTL)
   - Settlement status (30sec TTL)

4. **Connection Pooling**
   - Pool size: 20-50 connections
   - Separate pools for reads vs writes
   - Prepared statement cache

### Database Load Distribution

**Read Pattern:**
- 90%: Status queries by settlement_id
- 5%: Search queries by group criteria
- 5%: Report queries (aggregate)

**Write Pattern:**
- 60%: Settlement insertion (append-only)
- 30%: Group subtotal updates (incremental)
- 10%: Approval/audit records

**Scaling Strategy:**
- Read replicas for query load
- Partition by value_date for historical data
- Vertical scaling for primary DB (write capacity)

## Monitoring and Observability

### Key Metrics to Track

**Ingestion Metrics:**
- Settlements received per second
- Validation failure rate
- Average ingestion latency
- Queue depth (if using message queue)

**Processing Metrics:**
- Events processed per cycle
- Processing lag (time from ingestion to status update)
- Batch processing duration
- Error rate

**System Metrics:**
- Database connection pool usage
- Cache hit rates
- API response times (p50, p95, p99)
- Memory and CPU utilization

**Business Metrics:**
- Settlements by status distribution
- Settlements exceeding limits
- Average approval time
- Bulk operation frequency

### Alerting Thresholds

**Critical:**
- Processing lag > 30 seconds
- Settlement ingestion failure rate > 5%
- Database connection exhaustion

**Warning:**
- Queue depth > 10,000
- Processing batch takes > 15 seconds
- Cache hit rate < 80%

**Info:**
- Hourly settlement volume
- Peak processing times
- Most active counterparties

## Deployment Strategy

### Infrastructure Requirements

**Minimum Production Setup:**
- PostgreSQL: 8 vCPU, 32GB RAM, 500GB SSD
- Redis: 4 vCPU, 16GB RAM
- Background processor: 4 vCPU, 8GB RAM
- API servers: 2 instances, 4 vCPU, 8GB RAM each

**Recommended Setup:**
- PostgreSQL: Primary + 2 read replicas
- Redis: Cluster mode (3 nodes)
- Background processors: 2 instances (active-passive)
- API servers: 4+ instances behind load balancer
- Message queue: Kafka or managed equivalent

### CI/CD Pipeline

```
1. Code changes
   ↓
2. Automated tests (unit + property-based)
   ↓
3. Build container image
   ↓
4. Run integration tests with test DB
   ↓
5. Deploy to staging
   ↓
6. Health check verification
   ↓
7. Canary deployment to production (10%)
   ↓
8. Gradual rollout to 100%
   ↓
9. Monitor for 30 minutes
```

### Database Migration Strategy

**Zero-Downtime Migrations:**
1. Add new columns as nullable
2. Deploy code that writes to both old and new
3. Backfill existing data
4. Add NOT NULL constraint
5. Deploy code using only new
6. Remove old columns (later)

**Example: Adding Index**
```sql
-- Concurrent index creation (no locking)
CREATE INDEX CONCURRENTLY idx_new ON table(column);
```

## Security Considerations

### Authentication & Authorization

**API Endpoints:**
- Ingestion: API key or mTLS
- User actions: OAuth2/JWT with role-based access
- Admin actions: Separate admin role

**Roles:**
- `ingestion_service`: Can ingest settlements
- `operator`: Can view and request release
- `authorizer`: Can approve releases
- `admin`: Manual recalculations, view audit

### Data Protection

**At Rest:**
- Column-level encryption for: counterparty_id, amounts
- Database encryption enabled
- Backup encryption

**In Transit:**
- TLS 1.3 for all API calls
- mTLS for service-to-service

**Audit:**
- All user actions logged
- Immutable audit trail
- Regular audit report generation

### Compliance Controls

**Segregation of Duties:**
- Same user cannot request and authorize
- Enforced at application layer
- Audit trail for verification

**Data Retention:**
- Settlement data: 7 years
- Audit logs: 7 years
- Event logs: 2 years (hot), archive (cold)

**Access Logging:**
- All queries to sensitive data
- Bulk export tracking
- Failed auth attempts

## Testing Strategy

### Unit Tests

**Service Layer:**
```
- Ingestion validation logic
- Rule evaluation
- Status computation
- Approval workflow state machine
```

**Database Layer:**
```
- Constraint verification
- Index effectiveness
- Transaction behavior
- Concurrent update scenarios
```

### Property-Based Tests

**Implementation:**
```typescript
import * as fc from 'fast-check';

// Property 1: Settlement grouping accuracy
test('Settlements are grouped correctly by dimensions', () => {
  fc.assert(fc.property(
    fc.array(settlementArbitrary),
    (settlements) => {
      const groups = groupSettlements(settlements);
      // Verify all groups have identical dimension values
      groups.forEach(group => {
        const uniqueDimensions = new Set(group.map(s =>
          `${s.pts}::${s.processingEntity}::${s.counterpartyId}::${s.valueDate}`
        ));
        expect(uniqueDimensions.size).toBe(1);
      });
    }
  ));
});

// Property 2: Subtotal accuracy
test('Group subtotal equals sum of USD-eligible amounts', () => {
  fc.assert(fc.property(
    fc.array(settlementArbitrary),
    (settlements) => {
      const groups = calculateSubtotals(settlements);
      groups.forEach(group => {
        const expectedSum = group.settlements
          .filter(s => s.isEligible)
          .reduce((sum, s) => sum + convertToUSD(s.amount, s.currency), 0);
        expect(group.subtotal).toBeCloseTo(expectedSum, 2);
      });
    }
  ));
});
```

### Load Testing

**Scenarios:**
1. **Peak Volume**: 200K settlements in 30 minutes
2. **Burst Traffic**: 10K settlements in 1 minute
3. **Concurrent Updates**: Multiple versions of same settlement
4. **Bulk Operations**: 100 settlements approved simultaneously

**Success Criteria:**
- Ingestion: < 5ms per settlement
- Status update: < 10 seconds average
- API query: < 1 second p99
- Zero data loss

## Database Stored Procedures

For performance-critical operations, use stored procedures:

### Atomic Subtotal Update

```sql
CREATE OR REPLACE FUNCTION update_settlement_group(
    p_group_id VARCHAR(255),
    p_usd_amount DECIMAL(20,2),
    p_is_add BOOLEAN
)
RETURNS VOID AS $$
BEGIN
    IF p_is_add THEN
        UPDATE settlement_groups
        SET subtotal_usd = subtotal_usd + p_usd_amount,
            settlement_count = settlement_count + 1,
            last_updated = NOW()
        WHERE group_id = p_group_id;
    ELSE
        UPDATE settlement_groups
        SET subtotal_usd = subtotal_usd - p_usd_amount,
            settlement_count = settlement_count - 1,
            last_updated = NOW()
        WHERE group_id = p_group_id;
    END IF;
END;
$$ LANGUAGE plpgsql;
```

### Idempotent Version Update

```sql
CREATE OR REPLACE FUNCTION update_settlement_version(
    p_settlement_id VARCHAR(100),
    p_old_version INTEGER,
    p_new_version INTEGER,
    p_new_amount DECIMAL(20,2),
    p_currency VARCHAR(3)
)
RETURNS BOOLEAN AS $$
DECLARE
    v_exchange_rate DECIMAL(20,10);
    v_old_usd DECIMAL(20,2);
    v_new_usd DECIMAL(20,2);
    v_group_id VARCHAR(255);
BEGIN
    -- Get exchange rate
    SELECT rate INTO v_exchange_rate
    FROM exchange_rates
    WHERE from_currency = p_currency AND to_currency = 'USD'
    ORDER BY effective_date DESC LIMIT 1;

    v_new_usd := p_new_amount * v_exchange_rate;

    -- Check if new version is higher
    IF p_new_version <= p_old_version THEN
        RETURN FALSE; -- Reject stale version
    END IF;

    -- Get old contribution
    SELECT (amount * v_exchange_rate) INTO v_old_usd
    FROM settlements
    WHERE settlement_id = p_settlement_id
      AND settlement_version = p_old_version;

    -- Build group ID (simplified)
    v_group_id := 'computed_group_id';

    -- Atomic update
    UPDATE settlement_groups
    SET subtotal_usd = subtotal_usd - v_old_usd + v_new_usd,
        last_updated = NOW()
    WHERE group_id = v_group_id;

    RETURN TRUE;
END;
$$ LANGUAGE plpgsql;
```

## Comparison with Design Approach 1

| Aspect | Design Document 1 | Design2 (This Document) |
|--------|-------------------|-------------------------|
| **Architecture** | Microservices, event-driven | Monolith-friendly, pragmatic |
| **Concurrency** | Complex locking patterns | Single-threaded processor |
| **Performance** | Optimistic locking | Atomic operations + batches |
| **Data Model** | Event store + materialized views | Immutable settlements + group aggregates |
| **Complexity** | Higher (distributed systems concerns) | Lower (easier to implement correctly) |
| **Scaling** | Horizontal scaling focus | Vertical + read replicas |
| **Status** | Dynamic computation | Dynamic computation |
| **Audit** | Event sourcing | Immutable audit table |

**Recommendation:**
- Use **Design2** for MVP and initial implementation
- Evolve to **Design1** patterns if scaling requirements demand distributed architecture

## Implementation Roadmap

### Phase 1: Core Ingestion (Weeks 1-2)
- [ ] Database schema creation
- [ ] Settlement ingestion API
- [ ] Basic validation
- [ ] Filtering rule application

### Phase 2: Background Processing (Weeks 3-4)
- [ ] Single-threaded processor
- [ ] Group aggregation logic
- [ ] Currency conversion
- [ ] Status computation

### Phase 3: Approval Workflow (Weeks 5-6)
- [ ] Two-step approval API
- [ ] Segregation of duties
- [ ] Audit logging
- [ ] Status transitions

### Phase 4: Search & UI (Weeks 7-8)
- [ ] Search API with filters
- [ ] Basic web UI
- [ ] Bulk operations
- [ ] Excel export

### Phase 5: External Integration (Weeks 9-10)
- [ ] External query API
- [ ] Notification system
- [ ] Manual recalculation
- [ ] Configuration management

### Phase 6: Production Hardening (Weeks 11-12)
- [ ] Performance testing
- [ ] Monitoring & alerting
- [ ] Security hardening
- [ ] Deployment automation

## Appendix: Sample Code Snippets

### Status Computation Helper

```typescript
class SettlementStatusComputer {
  static async computeStatus(
    settlementId: string,
    db: Database,
    cache: Redis
  ): Promise<SettlementStatus> {
    const cacheKey = `status:${settlementId}`;

    // Try cache first
    const cached = await cache.get(cacheKey);
    if (cached) {
      return cached as SettlementStatus;
    }

    // Get latest settlement
    const settlement = await db.query(
      'SELECT * FROM settlements WHERE settlement_id = ? ORDER BY settlement_version DESC LIMIT 1',
      [settlementId]
    );

    if (!settlement) {
      throw new Error('Settlement not found');
    }

    // Check approval
    const approval = await db.query(
      'SELECT * FROM settlement_approval WHERE settlement_id = ?',
      [settlementId]
    );

    if (approval.length > 0) {
      if (approval[0].authorized_by) {
        return 'AUTHORISED';
      } else if (approval[0].requested_by) {
        return 'PENDING_AUTHORISE';
      }
    }

    // Check group limit
    const groupId = `${settlement.pts}::${settlement.processingEntity}::${settlement.counterpartyId}::${settlement.valueDate}`;
    const group = await db.query(
      'SELECT subtotal_usd, exposure_limit FROM settlement_groups WHERE group_id = ?',
      [groupId]
    );

    if (group.length === 0) {
      return 'CREATED';
    }

    const status = group[0].subtotal_usd > group[0].exposure_limit
      ? 'BLOCKED'
      : 'CREATED';

    // Cache for 30 seconds
    await cache.set(cacheKey, status, 30);

    return status;
  }
}
```

### Group Subtotal Calculator

```typescript
class GroupSubtotalCalculator {
  static async recalculateGroup(
    groupId: string,
    db: Database,
    exchangeRateService: ExchangeRateService
  ): Promise<number> {
    // Get all eligible settlements for this group
    const settlements = await db.query(`
      SELECT settlement_id, settlement_version, currency, amount
      FROM settlements
      WHERE pts = ?
        AND processing_entity = ?
        AND counterparty_id = ?
        AND value_date = ?
        AND is_eligible = TRUE
      ORDER BY settlement_id, settlement_version DESC
    `, parseGroupId(groupId));

    // Get latest version of each settlement (deduplicate)
    const latestVersions = deduplicateSettlements(settlements);

    // Calculate USD sum
    let totalUsd = 0;
    for (const settlement of latestVersions) {
      const rate = await exchangeRateService.getRate(settlement.currency, 'USD');
      totalUsd += settlement.amount * rate;
    }

    // Update group atomically
    await db.query(`
      UPDATE settlement_groups
      SET subtotal_usd = ?,
          settlement_count = ?,
          last_updated = NOW()
      WHERE group_id = ?
    `, [totalUsd, latestVersions.length, groupId]);

    return totalUsd;
  }
}
```

## End of Design2 Document

This design document provides a practical, implementable approach for the Payment Limit Monitoring System, balancing performance, correctness, and development complexity.
