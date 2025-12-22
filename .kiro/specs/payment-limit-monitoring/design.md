# Payment Limit Monitoring System - Design Document

## Overview

The Payment Limit Monitoring System is a high-performance financial risk management application designed to process up to 200,000 settlements within 30 minutes during peak trading periods. The system aggregates settlement data by counterparty groups, applies configurable filtering rules, and enforces exposure limits through a two-step approval workflow.

The system handles settlements with different directions (PAY/RECEIVE) and types (NET/GROSS), calculating risk exposure only from PAY settlements with active business status (PENDING, INVALID, or VERIFIED). CANCELLED settlements and RECEIVE settlements are excluded from risk calculations but remain visible for operational transparency.

The architecture emphasizes real-time processing, data consistency, and operational transparency while maintaining audit trails for regulatory compliance. The system integrates with multiple external systems including Primary Trading Systems (PTS), rule engines, exchange rate providers, and limit management systems.

## Architecture

The system follows a microservices architecture with event-driven processing to handle high-volume settlement flows efficiently:

### Core Components
- **Settlement Ingestion Service**: Receives settlement flows and appends them to the event store
- **Event Store**: Immutable append-only log of all settlement events with processing order
- **Background Calculation Service**: Single-threaded processor that reads events and updates materialized views
- **Materialized View Manager**: Maintains read-optimized views of group subtotals and settlement statuses
- **Approval Workflow Service**: Handles two-step approval process with immediate updates
- **API Gateway**: Provides external system integration and manual recalculation endpoints
- **Notification Service**: Sends authorization notifications to downstream systems
- **Rule Engine Integration**: Fetches filtering criteria and exchange rates for calculations

### External Integrations
- **Primary Trading Systems (PTS)**: Source of settlement flows
- **Rule Management System**: Provides filtering criteria (fetched every 5 minutes)
- **Exchange Rate Provider**: Daily currency conversion rates
- **Limit Management System**: Counterparty-specific exposure limits (future enhancement)
- **Downstream Processing Systems**: Receive authorization notifications

### Technology Stack
- **Message Queue**: Apache Kafka for high-throughput settlement ingestion
- **Database**: PostgreSQL with read replicas for scalability
- **Caching**: Redis for frequently accessed data (exchange rates, rules, limits, computed statuses)
- **API Framework**: REST APIs with OpenAPI specification
- **Background Processing**: Scheduled jobs for rule and rate synchronization

### Performance Optimization and Concurrency Strategy

The system design addresses several critical performance and concurrency challenges that arise in high-volume financial processing:

#### Challenge 1: Mass Status Updates During Limit Fluctuations
**Problem**: When a group with 10,000 settlements fluctuates around the 500M USD limit (due to new settlements or version updates), updating the status field of all 10,000 settlement records every few seconds would create severe database performance issues.

**Solution - Dynamic Status Computation**: 
- Settlement status (CREATED/BLOCKED) is computed on-demand based on current group subtotal and exposure limit
- No status field stored in the Settlement table for CREATED/BLOCKED states
- Only approval actions (PENDING_AUTHORISE, AUTHORISED) are persisted in a separate SettlementApproval table
- When group subtotal changes, only the group record is updated, not individual settlements
- Status computation is cached in Redis with 30-second TTL for performance

#### Challenge 2: Mass Updates When Filter Criteria Change
**Problem**: If filtering rules are updated and `isEligibleForCalculation` were stored in the Settlement table, all settlement records in the database would need to be updated, potentially affecting millions of records.

**Solution - Dynamic Eligibility Computation**:
- No `isEligibleForCalculation` field stored in the Settlement table
- Eligibility is computed dynamically during subtotal calculation using current filtering rules
- When rules change, only group subtotals are recalculated, not individual settlement records
- Rule evaluation results are cached in Redis to avoid repeated computation
- Settlement records remain immutable, containing only factual data

#### Challenge 3: Stale Exchange Rate Data
**Problem**: Storing `usdEquivalent` and `exchangeRateUsed` with each settlement creates data consistency issues. If a settlement is created today and another 2 years later, using different historical rates would make subtotal calculations inconsistent and complex.

**Solution - On-Demand Currency Conversion**:
- No `usdEquivalent` or `exchangeRateUsed` fields stored in the Settlement table
- USD conversion is performed on-demand during subtotal calculation using the latest available exchange rate
- Ensures all settlements in a group use consistent, current exchange rates for subtotal calculation
- Simplifies the calculation logic and maintains data consistency
- Exchange rates are cached in Redis for performance

#### Challenge 4: Race Conditions in Concurrent Subtotal Calculations
**Problem**: When multiple settlements for the same group arrive within milliseconds, each triggers a subtotal calculation. Without proper concurrency control, these calculations can overwrite each other randomly, leading to incorrect subtotals.

**Solution - Event-Driven Sequential Processing**:
Instead of trying to handle concurrent calculations with complex locking, we eliminate the race conditions entirely using our event-driven architecture:

```typescript
// Event-driven approach - no race conditions possible
class BackgroundCalculationService {
  processEvents() {
    // Single-threaded processing eliminates race conditions
    const newEvents = getUnprocessedEvents(); // Ordered by processingOrder
    
    for (const event of newEvents) {
      if (event.eventType === 'SETTLEMENT_GROUP_MIGRATION') {
        this.handleGroupMigration(event as SettlementGroupMigrationEvent);
      } else {
        // Standard settlement processing
        const groupId = calculateGroupId(event.settlementData);
        const newSubtotal = calculateCompleteGroupSubtotal(groupId);
        
        // Update materialized view
        updateGroupSubtotal(groupId, newSubtotal);
      }
      
      markEventAsProcessed(event.eventId);
    }
  }
  
  handleGroupMigration(event: SettlementGroupMigrationEvent) {
    const { oldGroupId, newGroupId, settlementData } = event.migrationData;
    
    // 1. Recalculate old group subtotal (settlement no longer included)
    const oldGroupSubtotal = calculateCompleteGroupSubtotal(oldGroupId);
    updateGroupSubtotal(oldGroupId, oldGroupSubtotal);
    
    // 2. Recalculate new group subtotal (settlement now included)
    const newGroupSubtotal = calculateCompleteGroupSubtotal(newGroupId);
    updateGroupSubtotal(newGroupId, newGroupSubtotal);
    
    // 3. Reset approval status as per requirements (new version invalidates previous approvals)
    resetApprovalStatus(settlementData.settlementId, settlementData.settlementVersion);
    
    // 4. Create audit record for the migration
    createMigrationAuditRecord(event);
  }
  
  calculateCompleteGroupSubtotal(groupId: string): number {
    // Always recalculate from scratch using current rules
    return SELECT COALESCE(SUM(s.amount * r.rate), 0) as subtotal
           FROM settlements s
           JOIN exchange_rates r ON s.currency = r.from_currency
           WHERE s.group_id = groupId
             AND s.direction = 'PAY'
             AND s.business_status IN ('PENDING', 'INVALID', 'VERIFIED')
             AND s.settlement_version = (
               SELECT MAX(settlement_version) 
               FROM settlements s2 
               WHERE s2.settlement_id = s.settlement_id
             )
             AND applyFilteringRules(s) = true;
  }
}
```

**Why This Eliminates Race Conditions:**
1. **Single-threaded processing**: Only one calculation happens at a time
2. **Complete recalculation**: Always calculates from current state, not deltas
3. **Event ordering**: Events processed in `processingOrder`, not arrival time
4. **Version handling**: Always uses latest version regardless of processing order

**Race Condition Examples - Solved**:

**Scenario: Multiple Settlements for Same Group**
```
Initial State: Group has subtotal = 400M USD

Concurrent Events:
- Event 1: Settlement A (100M USD) - processingOrder = 1001
- Event 2: Settlement B (150M USD) - processingOrder = 1002

Event-Driven Processing (CORRECT):
1. Process Event 1001: Recalculate group subtotal = 500M USD
2. Process Event 1002: Recalculate group subtotal = 650M USD
3. Result: Correct subtotal = 650M USD (both settlements included)
```

**Scenario: Settlement Group Migration (CounterpartyId Change)**
```
Initial State: 
- Settlement X version 1 in Group A (Counterparty_1, subtotal = 300M USD)
- Settlement X contributes 50M USD to Group A

Migration Event:
- Settlement X version 2 changes counterpartyId from Counterparty_1 to Counterparty_2
- This moves Settlement X from Group A to Group B

Event-Driven Processing (CORRECT):
1. Generate SETTLEMENT_GROUP_MIGRATION event with both old and new group context
2. Process migration event: 
   - Recalculate Group A subtotal = 250M USD (Settlement X removed)
   - Recalculate Group B subtotal = 50M USD (Settlement X added)
   - Reset approval status for Settlement X (new version invalidates previous approvals)
3. Result: Atomic migration with both groups correctly updated
```

**Benefits of Event-Driven Approach:**
- **No Race Conditions**: Single-threaded processing eliminates concurrency issues
- **Handles Complexity**: Versions, directions, business status changes, and group migrations all handled correctly
- **Atomic Group Migration**: Single migration event ensures both groups are updated consistently
- **Simple Logic**: Complete recalculation is easier to understand and debug than incremental updates
- **Consistent Results**: Always produces correct subtotal regardless of event timing
- **Audit Trail**: Complete event history for compliance and debugging, including group migration tracking

### Simple Event-Driven Architecture Solution

Instead of trying to solve complex race conditions with locks and atomic operations, let's use a fundamentally different approach that eliminates the problems entirely.

#### Core Insight: Separate Write Operations from Read Operations

**The Problem with Traditional Approach**: Calculating subtotals on-demand by querying all settlements every time is too slow for high-volume systems.

**Solution**: Pre-calculate and store the results in optimized tables that are updated in the background.

#### Architecture: Event Store with Pre-Computed Summary Tables

**1. Settlement Event Store (Write-Only)**
When settlements arrive, we just store them as events - no calculations:
```typescript
interface SettlementEvent {
  eventId: string;
  settlementId: string;
  settlementVersion: number;
  eventType: 'SETTLEMENT_RECEIVED' | 'SETTLEMENT_UPDATED';
  eventTimestamp: Date;
  processingOrder: number; // Auto-incrementing sequence
  settlementData: {
    pts: string;
    processingEntity: string;
    counterpartyId: string;
    valueDate: Date;
    currency: string;
    amount: number;
    direction: 'PAY' | 'RECEIVE';
    type: 'NET' | 'GROSS';
    businessStatus: 'PENDING' | 'INVALID' | 'VERIFIED' | 'CANCELLED';
  };
}
```

**2. Pre-Computed Summary Tables (Read-Only)**
Background processes calculate and store results in optimized tables:
```typescript
// This table stores pre-calculated group subtotals
interface GroupSubtotal {
  groupId: string;
  pts: string;
  processingEntity: string;
  counterpartyId: string;
  valueDate: Date;
  subtotalUsd: number;           // Pre-calculated!
  settlementCount: number;       // Pre-calculated!
  lastCalculatedAt: Date;
}

// Settlement status is computed on-demand, not stored
// Only approval actions are stored
interface SettlementApproval {
  settlementId: string;
  requestedBy?: string;
  requestedAt?: Date;
  authorizedBy?: string;
  authorizedAt?: Date;
}
```

**3. Background Calculation Service**
- Single-threaded processor that reads new events every 5 seconds
- Calculates group subtotals sequentially (no race conditions possible)
- Updates the pre-computed summary tables
- Much simpler than complex locking mechanisms

#### How This Solves All Problems

**Race Conditions**: Eliminated - single-threaded processing in event order
**Out-of-Order Processing**: Handled - events are processed by `processingOrder`, not arrival time
**Version Updates**: Simple - just another event in the stream
**Performance**: Excellent - writes are just appends, reads are fast lookups + simple computation
**Mass Updates**: Eliminated - no individual settlement status fields to update
**Limit Changes**: Instant - status computed with new limits, no database updates needed

#### Processing Flow

**1. Settlement Ingestion (Very Fast)**
```
Receive Settlement → Validate → Detect Group Changes → Store Event → Return ACK
(No calculations, no locks, just store - extremely fast)
```

**2. Background Processing (Every 5 seconds)**
```
1. Read new events in processingOrder
2. For each event:
   - If SETTLEMENT_GROUP_MIGRATION: Handle atomic group transfer
   - If standard event: Apply current filtering rules and update group subtotal
   - Calculate status based on current limits
   - Update settlement status in summary table
3. Mark events as processed
```

**3. Query Processing (Instant)**
```
UI/API Queries → Read from Summary Tables → Return Results
(No calculations needed, just read pre-computed values)
```

#### Challenge: Limit Updates and Mass Recalculation

**The Problem**: When exposure limits change, we need to recalculate the status for all settlements. With millions of settlements, updating the settlement_status table would be very slow.

**Solution - Dynamic Status Computation with Caching**:

Instead of storing settlement status in a table, we compute it on-demand but cache the results:

```typescript
// Don't store status in database - compute it dynamically
function getSettlementStatus(settlementId: string): SettlementStatus {
  const settlement = getSettlement(settlementId);
  const groupSubtotal = getGroupSubtotal(settlement.groupId); // From materialized view
  const exposureLimit = getCurrentLimit(settlement.counterpartyId); // From config
  
  // Check if there are approval actions
  const approvalRecord = getApprovalRecord(settlementId);
  
  // Compute status based on current data
  if (approvalRecord?.authorizedAt) return 'AUTHORISED';
  if (approvalRecord?.requestedAt) return 'PENDING_AUTHORISE';
  
  // For PAY settlements with eligible business status
  if (settlement.direction === 'PAY' && 
      ['PENDING', 'INVALID', 'VERIFIED'].includes(settlement.businessStatus)) {
    return groupSubtotal > exposureLimit ? 'BLOCKED' : 'CREATED';
  }
  
  // RECEIVE settlements and CANCELLED settlements are always CREATED
  return 'CREATED';
}
```

**Key Design Principle: Store What's Expensive, Compute What's Cheap**

**What We Store (Materialized Views):**
- ✅ Group subtotals (expensive to calculate, changes only when settlements change)
- ✅ Settlement basic data (direction, business status, etc.)
- ✅ Approval actions (REQUEST RELEASE, AUTHORISE)

**What We Compute On-Demand:**
- ✅ Settlement status (cheap to compute: just compare group subtotal vs limit)
- ✅ Whether group exceeds limit

**Caching Strategy:**
```typescript
// Cache status for 30 seconds to avoid repeated calculations
const statusCache = new Map<string, {status: string, cachedAt: Date}>();

function getCachedSettlementStatus(settlementId: string): SettlementStatus {
  const cached = statusCache.get(settlementId);
  if (cached && (Date.now() - cached.cachedAt.getTime()) < 30000) {
    return cached.status;
  }
  
  const status = getSettlementStatus(settlementId);
  statusCache.set(settlementId, {status, cachedAt: new Date()});
  return status;
}
```

**Benefits of This Approach:**
- **Limit Updates**: When limits change, no database updates needed - status is computed with new limits immediately
- **Performance**: Group subtotals are still pre-calculated (the expensive part)
  - Group subtotal lookup: ~1ms (from materialized view)
  - Status computation: ~1ms (simple comparison)
  - Total: ~2ms per query (still very fast)
- **Consistency**: Status always reflects current limits and group subtotals
- **Scalability**: No mass database updates when configuration changes

**When Limits Change:**
1. Update the limit configuration
2. Clear the status cache
3. Next status queries automatically use new limits
4. No database recalculation needed!

**Example Scenario:**
```
Initial State:
- Group ABC has subtotal = 480M USD
- Exposure limit = 500M USD
- 10,000 settlements in this group
- All settlements have status CREATED

Limit Changes to 450M USD:

Traditional Approach (SLOW):
- Update 10,000 settlement records: status = BLOCKED
- Database writes: 10,000 UPDATE statements
- Time: 30-60 seconds

Our Approach (INSTANT):
- Update limit configuration: 450M USD
- Clear status cache
- Next query computes: 480M > 450M = BLOCKED
- Database writes: 0
- Time: < 1 second
```

#### Benefits of This Approach

**Simplicity**: No complex locking, atomic operations, or race condition handling
**Performance**: Writes are O(1) appends, reads are O(1) lookups
**Consistency**: Single-threaded processing guarantees consistent state
**Auditability**: Complete event history for compliance
**Scalability**: Can replay events to rebuild state, easy to debug
**Flexibility**: Can change calculation logic and replay events

This approach is much simpler, more reliable, and easier to understand than complex concurrency control mechanisms.

### Group Migration Handling

The system handles settlement group migrations (when PTS, Processing_Entity, Counterparty_ID, or Value_Date changes) through a specialized event-driven approach that ensures atomic transfers between groups.

#### Migration Detection and Event Generation

**Detection Process:**
During settlement ingestion, the system compares new settlement versions against existing versions to detect group key changes:

```typescript
function createGroupMigrationEvent(
  oldSettlement: SettlementData, 
  newSettlement: SettlementData, 
  changedFields: string[]
): SettlementGroupMigrationEvent {
  const oldGroupId = calculateGroupId(oldSettlement);
  const newGroupId = calculateGroupId(newSettlement);
  
  return {
    eventId: generateEventId(),
    settlementId: newSettlement.settlementId,
    settlementVersion: newSettlement.settlementVersion,
    eventType: 'SETTLEMENT_GROUP_MIGRATION',
    eventTimestamp: new Date(),
    processingOrder: getNextProcessingOrder(),
    settlementData: newSettlement,
    migrationData: {
      oldGroupId,
      newGroupId,
      oldCounterpartyId: oldSettlement.counterpartyId,
      newCounterpartyId: newSettlement.counterpartyId,
      changedFields,
      oldGroupKeys: {
        pts: oldSettlement.pts,
        processingEntity: oldSettlement.processingEntity,
        counterpartyId: oldSettlement.counterpartyId,
        valueDate: oldSettlement.valueDate
      },
      newGroupKeys: {
        pts: newSettlement.pts,
        processingEntity: newSettlement.processingEntity,
        counterpartyId: newSettlement.counterpartyId,
        valueDate: newSettlement.valueDate
      }
    },
    processed: false
  };
}
```

#### Atomic Migration Processing

**Background Processing:**
The Background Calculation Service handles migration events atomically:

```typescript
function handleGroupMigration(event: SettlementGroupMigrationEvent) {
  const { oldGroupId, newGroupId, settlementData, changedFields } = event.migrationData;
  
  // 1. Update settlement record with new group assignment
  updateSettlementGroup(settlementData.settlementId, newGroupId);
  
  // 2. Recalculate both affected groups completely
  const oldGroupSubtotal = calculateCompleteGroupSubtotal(oldGroupId);
  const newGroupSubtotal = calculateCompleteGroupSubtotal(newGroupId);
  
  // 3. Update materialized views
  updateGroupSubtotal(oldGroupId, oldGroupSubtotal);
  updateGroupSubtotal(newGroupId, newGroupSubtotal);
  
  // 4. Reset approval status (new version invalidates previous approvals)
  resetApprovalStatus(settlementData.settlementId, settlementData.settlementVersion);
  
  // 5. Create comprehensive audit record
  createMigrationAuditRecord({
    settlementId: settlementData.settlementId,
    settlementVersion: settlementData.settlementVersion,
    action: 'GROUP_MIGRATION',
    changedFields,
    oldGroupContext: event.migrationData.oldGroupKeys,
    newGroupContext: event.migrationData.newGroupKeys,
    oldGroupSubtotal,
    newGroupSubtotal
  });
}
```

#### Migration Benefits

**Atomicity:** Single event ensures both groups are updated consistently - no risk of partial migration
**Audit Trail:** Complete record of what changed, when, and the impact on both groups
**Performance:** Only two group subtotals need recalculation, not individual settlement records
**Consistency:** Leverages existing event-driven architecture for reliable processing
**Limit Handling:** Automatically applies correct exposure limits for each counterparty group

#### Migration Scenarios

**CounterpartyId Change:**
- Settlement moves from one counterparty group to another
- Both groups recalculated with appropriate exposure limits
- Status computation uses correct limit for new counterparty

**Multiple Field Changes:**
- Single migration event handles multiple group key changes
- Prevents multiple intermediate group assignments
- Ensures settlement ends up in correct final group

**Complex Migrations:**
- PTS + CounterpartyId change handled atomically
- Value date changes across month boundaries
- Processing entity reorganizations

This migration approach ensures data consistency while maintaining the performance benefits of the event-driven architecture.

**Alternative - Event Sourcing for Extreme High Contention**:
For groups with extremely high settlement volumes (>1000 settlements/second), implement event sourcing:
- Store settlement events in an append-only log
- Use background processors to calculate subtotals from event streams
- Provide eventual consistency with conflict resolution
- Maintain real-time approximations with periodic exact calculations

#### Performance Benefits
This approach provides several key benefits:
- **Minimal Database Writes**: Only factual settlement data and group subtotals are persisted
- **Scalable Updates**: Group-level updates scale with number of groups, not individual settlements
- **Consistent Calculations**: All computations use current rules, limits, and exchange rates
- **Cache Efficiency**: Computed values are cached to reduce repeated calculations
- **Data Integrity**: Immutable settlement records prevent data corruption during high-volume processing
- **Concurrency Safety**: Single-threaded processing ensures subtotal calculations are atomic and consistent

## Components and Interfaces

### User Interface Components

**Main Dashboard Layout:**
- **Upper Section**: Settlement groups with aggregated information (PTS, Processing_Entity, Counterparty_ID, Value_Date, group subtotal, status summary)
- **Lower Section**: Individual settlements for selected group (latest version only)
- **Side Panel**: Detailed settlement information with tabbed interface

**Settlement Group Display:**
- Shows group-level information and subtotal status
- Clicking a group filters the lower section to show related settlements
- Only displays latest version of each settlement in the group

**Settlement Detail Side Panel:**
- **Overview Tab**: Settlement details, current status, group context
- **Audit Trail Tab**: Complete action history across all versions of the settlement
- **Version History Tab**: All versions of the settlement with their details

**Audit Trail Tab Requirements:**
- Shows chronological history of all actions for the Settlement_ID
- Includes CREATE actions for each version received
- Includes all approval actions (REQUEST RELEASE, AUTHORISE) with version context and user comments
- Displays user identity, timestamp, action type, settlement version, and mandatory comments for approval actions
- Provides complete traceability across settlement lifecycle

### Settlement Ingestion Service
**Responsibilities:**
- Receive settlement flows from PTS endpoints
- Validate settlement data structure and completeness including direction, type, and business status
- Apply filtering rules to determine calculation eligibility
- Store settlements with versioning support
- Detect group key changes (PTS, Processing_Entity, Counterparty_ID, Value_Date) and generate migration events
- Handle out-of-order settlement version processing using version numbers
- Trigger aggregation calculations for eligible settlements

**Group Migration Detection:**
The service detects when a settlement version changes any group key fields by comparing the new version against the latest existing version:

```typescript
function processSettlementVersion(newSettlement: SettlementData) {
  const existingSettlement = getLatestSettlement(newSettlement.settlementId);
  
  if (existingSettlement) {
    const groupKeysChanged = detectGroupKeyChanges(existingSettlement, newSettlement);
    
    if (groupKeysChanged.length > 0) {
      // Generate migration event for atomic group transfer
      const migrationEvent = createGroupMigrationEvent(
        existingSettlement, 
        newSettlement, 
        groupKeysChanged
      );
      appendToEventStore(migrationEvent);
    } else {
      // Settlement version update within same group - still use SETTLEMENT_RECEIVED
      // The background processor will handle version comparison automatically
      const receiveEvent = createSettlementReceiveEvent(newSettlement);
      appendToEventStore(receiveEvent);
    }
  } else {
    // New settlement
    const receiveEvent = createSettlementReceiveEvent(newSettlement);
    appendToEventStore(receiveEvent);
  }
}

function detectGroupKeyChanges(oldSettlement: SettlementData, newSettlement: SettlementData): string[] {
  const changedFields = [];
  if (oldSettlement.pts !== newSettlement.pts) changedFields.push('pts');
  if (oldSettlement.processingEntity !== newSettlement.processingEntity) changedFields.push('processingEntity');
  if (oldSettlement.counterpartyId !== newSettlement.counterpartyId) changedFields.push('counterpartyId');
  if (oldSettlement.valueDate !== newSettlement.valueDate) changedFields.push('valueDate');
  return changedFields;
}
```

**Key Interfaces:**
```
POST /api/settlements/ingest
- Accepts settlement data from PTS systems including direction, type, and business status
- Detects group key changes and generates appropriate events
- Returns acknowledgment with processing status

GET /api/settlements/{settlementId}/status
- Returns current settlement status and details including direction and business status
- Used by external systems for status queries
```

**Business Logic:**
- Only PAY settlements with business status PENDING, INVALID, or VERIFIED are eligible for subtotal calculations
- RECEIVE settlements and CANCELLED settlements are stored but excluded from risk calculations
- NET settlements can change direction between PAY and RECEIVE based on underlying settlement updates
- Settlement versions are processed based on version number, not arrival time
- Group key changes trigger atomic migration events to ensure consistent group transfers
- Out-of-order version processing is handled correctly using the event-driven architecture

### Aggregation Engine
**Responsibilities:**
- Group PAY settlements with eligible business status by PTS, Processing Entity, Counterparty ID, and Value Date
- Convert currencies to USD using latest exchange rates
- Calculate and maintain group subtotals from PAY settlements with business status PENDING, INVALID, or VERIFIED
- Handle settlement version updates and group migrations
- Compute settlement eligibility using current filtering rules and business status
- Handle direction changes for NET settlements

**Key Operations:**
- Real-time subtotal calculation (< 10 seconds) using materialized group totals
- Atomic group migration when settlement group keys change (PTS, Processing_Entity, Counterparty_ID, Value_Date)
- Group rebalancing when settlement details change
- Dynamic currency conversion with latest available rates
- Efficient handling of high-volume updates without mass settlement record updates
- On-demand status computation to avoid performance bottlenecks
- Complete group recalculation rather than incremental updates to ensure accuracy

**Business Logic:**
- Only PAY settlements contribute to subtotal calculations
- CANCELLED settlements are excluded regardless of direction
- RECEIVE settlements are excluded regardless of business status
- NET settlements require special handling for direction changes
- Complete recalculation ensures accuracy when settlement amounts change or eligibility changes
- Atomic group migration handling ensures settlements are properly moved between groups when key attributes change
- Group migration events provide complete audit trail and prevent partial transfers
- Counterparty changes require recalculation of both old and new groups with appropriate exposure limits

**Performance Optimizations:**
- Maintain materialized subtotals at group level only
- Use Redis caching for frequently accessed group calculations
- Compute individual settlement status dynamically from group state
- Batch process settlement updates to minimize database transactions

### Limit Monitoring Service
**Responsibilities:**
- Compare group subtotals against exposure limits
- Compute settlement status dynamically based on group state, approval records, and business status
- Handle limit updates and re-evaluation at group level
- Support both fixed limits (MVP) and counterparty-specific limits

**Status Computation Logic:**
- CREATED: (Group subtotal within limit OR settlement is RECEIVE OR settlement is CANCELLED) AND no approval record exists
- BLOCKED: Settlement is PAY with business status PENDING/INVALID/VERIFIED AND group subtotal exceeds limit AND no approval record exists  
- PENDING_AUTHORISE: Approval record exists with REQUEST RELEASE action (only for VERIFIED settlements)
- AUTHORISED: Approval record exists with both REQUEST RELEASE and AUTHORISE actions (only for VERIFIED settlements)

**Business Rules:**
- Only VERIFIED settlements are eligible for manual approval workflow
- PENDING and INVALID settlements show as BLOCKED but cannot be manually approved until VERIFIED
- RECEIVE settlements automatically get CREATED status regardless of group subtotal
- CANCELLED settlements automatically get CREATED status and are excluded from group subtotals

**Performance Considerations:**
- Status computed on-demand to avoid mass updates when group totals fluctuate
- Only settlements with approval actions have persistent status records
- Group-level limit evaluation prevents individual settlement updates
- Caching layer reduces computation overhead for frequently queried settlements

### User Interface Components

**Main Dashboard Layout:**
- **Upper Section**: Settlement groups with aggregated information (PTS, Processing_Entity, Counterparty_ID, Value_Date, group subtotal, status summary)
- **Lower Section**: Individual settlements for selected group (latest version only)
- **Side Panel**: Detailed settlement information with tabbed interface

**Settlement Group Display:**
- Shows group-level information and subtotal status
- Clicking a group filters the lower section to show related settlements
- Only displays latest version of each settlement in the group

**Settlement Detail Side Panel:**
- **Overview Tab**: Settlement details, current status, group context
- **Audit Trail Tab**: Complete action history across all versions of the settlement
- **Version History Tab**: All versions of the settlement with their details

**Audit Trail Tab Requirements:**
- Shows chronological history of all actions for the Settlement_ID
- Includes CREATE actions for each version received
- Includes all approval actions (REQUEST RELEASE, AUTHORISE) with version context
- Displays user identity, timestamp, action type, and settlement version
- Provides complete traceability across settlement lifecycle
**Responsibilities:**
- Enforce two-person approval process for VERIFIED settlements only
- Prevent same user from performing both REQUEST RELEASE and AUTHORISE
- Maintain comprehensive audit trails
- Handle bulk operations on same-group VERIFIED settlements
- Reset approvals when settlement versions change business status or direction

**Business Rules:**
- Only VERIFIED PAY settlements that are BLOCKED can be manually approved
- PENDING and INVALID settlements cannot be approved until they become VERIFIED
- RECEIVE and CANCELLED settlements do not require approval workflow
- Approval actions are tied to specific settlement versions - new versions require new approvals
- When a settlement receives a new version, any previous approval actions do not apply to the new version

**Audit Trail Fields:**
- User identity and timestamp
- Action type (REQUEST RELEASE, AUTHORISE)
- Settlement ID and the specific version being approved
- Mandatory user comment explaining the action
- Group context and subtotal at time of action

## Data Models

### Event Store (Write Side)
```typescript
interface SettlementEvent {
  eventId: string;
  settlementId: string;
  settlementVersion: number;
  eventType: 'SETTLEMENT_RECEIVED' | 'SETTLEMENT_GROUP_MIGRATION';
  eventTimestamp: Date;
  processingOrder: number; // Auto-incrementing sequence for ordering
  settlementData: {
    pts: string;
    processingEntity: string;
    counterpartyId: string;
    valueDate: Date;
    currency: string;
    amount: number;
    direction: 'PAY' | 'RECEIVE';
    type: 'NET' | 'GROSS';
    businessStatus: 'PENDING' | 'INVALID' | 'VERIFIED' | 'CANCELLED';
  };
  processed: boolean;
  processedAt?: Date;
}

interface SettlementGroupMigrationEvent extends SettlementEvent {
  eventType: 'SETTLEMENT_GROUP_MIGRATION';
  migrationData: {
    oldGroupId: string;
    newGroupId: string;
    oldCounterpartyId: string;
    newCounterpartyId: string;
    changedFields: ('pts' | 'processingEntity' | 'counterpartyId' | 'valueDate')[];
    oldGroupKeys: {
      pts: string;
      processingEntity: string;
      counterpartyId: string;
      valueDate: Date;
    };
    newGroupKeys: {
      pts: string;
      processingEntity: string;
      counterpartyId: string;
      valueDate: Date;
    };
  };
}
```

### Materialized Views (Read Side)
```typescript
interface GroupSubtotal {
  groupId: string;
  pts: string;
  processingEntity: string;
  counterpartyId: string;
  valueDate: Date;
  subtotalUsd: number;
  settlementCount: number;
  exceedsLimit: boolean;
  exposureLimit: number;
  lastProcessedEventId: string;
  calculatedAt: Date;
}

interface SettlementView {
  settlementId: string;
  currentVersion: number;
  pts: string;
  processingEntity: string;
  counterpartyId: string;
  valueDate: Date;
  currency: string;
  amount: number;
  direction: 'PAY' | 'RECEIVE';
  type: 'NET' | 'GROSS';
  businessStatus: 'PENDING' | 'INVALID' | 'VERIFIED' | 'CANCELLED';
  usdAmount: number;
  status: SettlementStatus;
  groupId: string;
  groupSubtotal: number;
  isEligibleForCalculation: boolean;
  lastUpdated: Date;
}

enum SettlementStatus {
  CREATED = 'CREATED',
  BLOCKED = 'BLOCKED',
  PENDING_AUTHORISE = 'PENDING_AUTHORISE',
  AUTHORISED = 'AUTHORISED'
}

interface SettlementApproval {
  settlementId: string;
  settlementVersion: number;  // Approval is tied to specific version
  requestedBy?: string;
  requestedAt?: Date;
  requestComment?: string;     // Mandatory comment for REQUEST RELEASE
  authorizedBy?: string;
  authorizedAt?: Date;
  authorizeComment?: string;   // Mandatory comment for AUTHORISE
}
```

### Audit Record
```typescript
interface AuditRecord {
  auditId: string;
  settlementId: string;
  settlementVersion: number;
  userId: string;
  action: AuditAction;
  timestamp: Date;
  comment?: string;  // User comment for approval actions (mandatory for REQUEST_RELEASE and AUTHORISE)
  groupContext: {
    pts: string;
    processingEntity: string;
    counterpartyId: string;
    valueDate: Date;
    subtotalAtAction: number;
  };
}

enum AuditAction {
  CREATE = 'CREATE',
  REQUEST_RELEASE = 'REQUEST_RELEASE',
  AUTHORISE = 'AUTHORISE',
  STATUS_RESET = 'STATUS_RESET',
  GROUP_MIGRATION = 'GROUP_MIGRATION'
}
```

### Exchange Rate
```typescript
interface ExchangeRate {
  fromCurrency: string;
  toCurrency: string;
  rate: number;
  effectiveDate: Date;
  fetchedAt: Date;
}
```

### Filtering Rule
```typescript
interface FilteringRule {
  ruleId: string;
  criteria: {
    pts?: string[];
    processingEntity?: string[];
    counterpartyId?: string[];
    currency?: string[];
    amountRange?: {
      min?: number;
      max?: number;
    };
  };
  isActive: boolean;
  lastUpdated: Date;
}
```

## Correctness Properties

*A property is a characteristic or behavior that should hold true across all valid executions of a system-essentially, a formal statement about what the system should do. Properties serve as the bridge between human-readable specifications and machine-verifiable correctness guarantees.*

Based on the requirements analysis, the following correctness properties must be maintained by the Payment Limit Monitoring System:

### Data Management Properties

**Property 1: Settlement Storage Completeness**
*For any* valid settlement data received, the system should store all required fields (PTS, Processing_Entity, Counterparty_ID, Value_Date, Currency, Amount, Settlement_ID, Settlement_Version, Settlement_Direction, Settlement_Type, Business_Status) without loss or corruption
**Validates: Requirements 1.1**

**Property 2: Filtering Rule Application**
*For any* settlement and current filtering rules, the system should correctly determine eligibility for subtotal calculations based on the rule criteria
**Validates: Requirements 1.2**

**Property 3: Settlement Inclusion Logic**
*For any* settlement, it should be included in subtotal calculations if and only if it has direction PAY, business status is PENDING/INVALID/VERIFIED, and matches current filtering rules
**Validates: Requirements 1.3, 1.4, 1.6**

**Property 4: Version Management**
*For any* settlement with multiple versions, only the latest version based on Settlement_Version number should be active in calculations while all historical versions remain accessible for audit
**Validates: Requirements 1.5, 10.2**

**Property 5: Business Status Change Handling**
*For any* settlement that changes business status between CANCELLED and non-CANCELLED states, the group subtotal should be recalculated to reflect the new inclusion status
**Validates: Requirements 1.9, 1.10**

**Property 6: Idempotent Processing**
*For any* settlement submitted multiple times with the same Settlement_ID and Settlement_Version, the system state should remain consistent and not create duplicate effects
**Validates: Requirements 10.6**

### Aggregation and Calculation Properties

**Property 7: Settlement Grouping**
*For any* set of PAY settlements with eligible business status, those with identical PTS, Processing_Entity, Counterparty_ID, and Value_Date should be grouped together
**Validates: Requirements 2.1**

**Property 8: Complete Subtotal Recalculation**
*For any* group subtotal calculation, the result should equal the sum of USD-converted amounts for all currently eligible settlements in the group, using complete recalculation rather than incremental updates
**Validates: Requirements 2.2, 2.4**

**Property 9: NET Settlement Direction Changes**
*For any* NET settlement that changes direction between PAY and RECEIVE, the group subtotal should be recalculated to reflect the current direction and inclusion status
**Validates: Requirements 2.6**

**Property 10: Atomic Group Migration**
*For any* settlement that changes group keys (PTS, Processing_Entity, Counterparty_ID, or Value_Date), it should be atomically moved from the old group to the new group with both subtotals recalculated accurately and approval status reset
**Validates: Requirements 2.7, 2.8**

### Status Management Properties

**Property 11: Status Assignment Based on Risk Contribution**
*For any* settlement, its status should be CREATED if it doesn't contribute to risk exposure (RECEIVE direction, CANCELLED status, or group within limit) and BLOCKED if it contributes to risk exposure and group exceeds limit
**Validates: Requirements 3.1, 3.2, 3.3**

**Property 12: Limit Change Re-evaluation**
*For any* exposure limit update, all settlements should be re-evaluated and their statuses should reflect the new limit comparison
**Validates: Requirements 3.6**

**Property 13: Status Reset on Version Change**
*For any* settlement that receives a new version affecting its risk contribution, the status should reset based on the current group subtotal and business rules, invalidating previous approvals
**Validates: Requirements 2.8, 4.5**

### Approval Workflow Properties

**Property 14: VERIFIED Settlement Approval Eligibility**
*For any* BLOCKED settlement, REQUEST RELEASE functionality should be available if and only if the settlement has business status VERIFIED
**Validates: Requirements 4.1, 4.6**

**Property 15: REQUEST RELEASE Transition**
*For any* BLOCKED VERIFIED settlement, a REQUEST RELEASE action should change the status to PENDING_AUTHORISE and create an audit record
**Validates: Requirements 4.2**

**Property 16: AUTHORISE Transition**
*For any* PENDING_AUTHORISE settlement, an AUTHORISE action by a different user should change the status to AUTHORISED and create an audit record
**Validates: Requirements 4.3**

**Property 17: Segregation of Duties**
*For any* settlement, the same user should not be able to perform both REQUEST RELEASE and AUTHORISE actions
**Validates: Requirements 4.4**

**Property 18: Bulk Action Consistency**
*For any* bulk operation on VERIFIED settlements from the same group, each settlement should receive the action and have an individual audit entry created
**Validates: Requirements 4.7**

**Property 19: Audit Trail Completeness**
*For any* user action (REQUEST RELEASE, AUTHORISE), a complete audit record should be created with user identity, timestamp, settlement details, and version information
**Validates: Requirements 4.8**

### Search and Query Properties

**Property 20: Search Filter Accuracy**
*For any* search criteria including direction, type, and business status, the results should contain only settlements that match all specified criteria
**Validates: Requirements 6.1**

**Property 21: Limit Status Filtering**
*For any* limit status filter, the results should contain only PAY settlements whose groups match the specified limit relationship
**Validates: Requirements 6.2**

**Property 22: Group Selection Display**
*For any* selected settlement group, the detail view should display all settlements (regardless of direction or business status) that belong to that specific group
**Validates: Requirements 6.8**

### API and Integration Properties

**Property 23: Status Query Accuracy**
*For any* valid Settlement_ID queried via API, the system should return the current accurate status and relevant details including direction, type, and business status
**Validates: Requirements 7.1**

**Property 24: Authorization Notification**
*For any* settlement that transitions to AUTHORISED status, a notification should be sent to external systems with the Settlement_ID and authorization details
**Validates: Requirements 7.6**

**Property 25: Manual Recalculation Scope**
*For any* manual recalculation request with scope criteria, all and only the settlements matching the criteria should have their subtotals recalculated using current rules and limits, considering only PAY settlements with eligible business status
**Validates: Requirements 7.7, 7.8**

### Configuration and Rate Management Properties

**Property 26: Exchange Rate Application**
*For any* settlement requiring currency conversion, the latest available exchange rate at processing time should be used for USD equivalent calculation
**Validates: Requirements 8.4**

**Property 27: Rate Update Non-Retroactivity**
*For any* exchange rate update, existing settlement subtotals should remain unchanged and only future settlements should use the new rates
**Validates: Requirements 8.5**

**Property 28: Counterparty Limit Application**
*For any* settlement in advanced mode, the correct counterparty-specific exposure limit should be applied based on the settlement's Counterparty_ID
**Validates: Requirements 8.6**

### Audit and Compliance Properties

**Property 29: Historical Data Accessibility**
*For any* settlement, all versions and their timestamps should remain accessible for audit and compliance queries, including direction, type, and business status information
**Validates: Requirements 9.1**

**Property 30: Audit Trail Immutability**
*For any* historical audit record, it should remain unmodifiable and accessible throughout the compliance retention period
**Validates: Requirements 9.5**

## Error Handling

The system implements comprehensive error handling across all components:

### Settlement Ingestion Errors
- **Invalid Data Format**: Reject settlements with missing required fields or invalid data types
- **Duplicate Processing**: Handle duplicate settlement IDs with proper versioning
- **External System Failures**: Implement retry mechanisms with exponential backoff for PTS connectivity issues
- **Rate Limiting**: Protect against overwhelming settlement volumes with circuit breaker patterns

### Calculation Errors
- **Currency Conversion Failures**: Handle missing exchange rates with fallback mechanisms and error notifications
- **Arithmetic Overflow**: Prevent calculation errors with proper numeric handling for large amounts
- **Group Migration Errors**: Ensure atomic operations when moving settlements between groups
- **Concurrent Update Conflicts**: Use optimistic locking to handle simultaneous updates to the same group

### Approval Workflow Errors
- **Authorization Failures**: Validate user permissions before allowing approval actions
- **Stale Data Operations**: Prevent actions on outdated settlement versions
- **Audit Trail Failures**: Ensure audit records are created atomically with status changes
- **Notification Failures**: Implement retry mechanisms for external system notifications

### Integration Errors
- **Rule System Unavailability**: Continue operations with cached rules when external rule system is unavailable
- **API Rate Limiting**: Implement proper throttling for external API calls
- **Data Consistency**: Ensure eventual consistency across distributed components
- **Timeout Handling**: Implement appropriate timeouts for all external system calls

## Testing Strategy

The Payment Limit Monitoring System requires a comprehensive testing approach combining unit tests and property-based tests to ensure correctness across the complex financial workflows.

### Unit Testing Approach
Unit tests will focus on:
- **Component Integration**: Testing interactions between services and data layers
- **Edge Cases**: Specific scenarios like boundary conditions, error states, and configuration changes
- **Business Logic**: Validation of specific approval workflows and status transitions
- **API Contracts**: Ensuring API responses match expected formats and contain required data

### Property-Based Testing Approach
Property-based tests will verify universal properties using **fast-check** (JavaScript/TypeScript property testing library) with a minimum of 100 iterations per test to ensure comprehensive coverage across the input space.

Each property-based test will:
- Generate random but valid test data (settlements, rules, limits, user actions)
- Execute system operations with the generated data
- Verify that the correctness properties hold regardless of the specific input values
- Include explicit comments referencing the design document properties being tested

**Property Test Requirements:**
- All correctness properties (Property 1-30) must be implemented as individual property-based tests
- Each test must be tagged with the format: `**Feature: payment-limit-monitoring, Property X: [property description]**`
- Tests must use realistic data generators that respect business constraints (valid currencies, reasonable amounts, proper date ranges)
- Complex properties involving multiple operations (like group migration) must test the complete workflow atomically

### Test Data Generation Strategy
- **Settlement Generators**: Create settlements with valid PTS, processing entities, counterparties, currencies, and amounts
- **Rule Generators**: Generate filtering rules with various criteria combinations
- **User Action Generators**: Create approval workflows with proper user segregation
- **Temporal Generators**: Generate realistic value dates and processing timestamps
- **Currency Generators**: Use actual currency codes and reasonable exchange rate ranges

### Integration Testing
- **End-to-End Workflows**: Test complete settlement processing from ingestion to authorization
- **External System Mocking**: Mock PTS, rule systems, and notification endpoints for controlled testing
- **Performance Testing**: Validate system behavior under high-volume settlement loads
- **Failure Recovery**: Test system resilience during external system outages

The dual testing approach ensures both specific business scenarios work correctly (unit tests) and that the system maintains correctness properties across all possible inputs (property-based tests), providing confidence in the system's reliability for high-stakes financial operations.