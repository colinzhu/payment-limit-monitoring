# Payment Limit Monitoring System - Design Document

## Overview

The Payment Limit Monitoring System is a high-performance financial risk management application designed to process up to 200,000 settlements within 30 minutes during peak trading periods. The system aggregates settlement data by counterparty groups, applies configurable filtering rules, and enforces exposure limits through a two-step approval workflow.

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

**Solution - Atomic Increment Operations**:
Instead of reading-calculating-updating, use atomic database operations that work with deltas:

```sql
-- Atomic increment approach - add new settlement
UPDATE settlement_groups 
SET subtotal_usd = subtotal_usd + ?, 
    settlement_count = settlement_count + 1,
    version = version + 1, 
    last_calculated_at = NOW()
WHERE group_id = ?

-- Atomic update approach - replace settlement version
UPDATE settlement_groups 
SET subtotal_usd = subtotal_usd - ? + ?, -- subtract old, add new
    version = version + 1,
    last_calculated_at = NOW()
WHERE group_id = ?
```

**Detailed Implementation Strategy**:
1. **New Settlement**: Atomically increment subtotal by the settlement's USD amount
2. **Settlement Version Update**: Atomically subtract old amount and add new amount in single operation
3. **Settlement Group Migration**: Use database transaction to atomically decrement from old group and increment to new group
4. **Eligibility Changes**: When filtering rules change, recalculate entire group subtotal from scratch using SELECT SUM() with current rules

**Fallback for Complex Scenarios**:
For operations that cannot use atomic increments (like rule changes affecting eligibility), implement distributed locking:
- Use Redis distributed locks per group_id
- Lock acquisition timeout: 5 seconds
- Lock hold time: maximum 30 seconds
- Implement lock renewal for long-running calculations

**Race Condition Examples - Solved**:

**Scenario 1: New Settlements**
```
Initial State: Group has subtotal = 400M USD, count = 5 settlements

Concurrent Scenario:
- Settlement A (100M USD) arrives at time T
- Settlement B (150M USD) arrives at time T+1ms

Old Approach (PROBLEMATIC):
- Both read: subtotal = 400M, count = 5
- A calculates: 400M + 100M = 500M, count = 6
- B calculates: 400M + 150M = 550M, count = 6  
- Result: Incorrect subtotal (either 500M or 550M, missing one settlement)

New Approach (CORRECT):
- A executes: UPDATE SET subtotal = subtotal + 100, count = count + 1
- B executes: UPDATE SET subtotal = subtotal + 150, count = count + 1
- Result: Correct subtotal = 650M USD, count = 7 settlements
```

**Scenario 2: Settlement Version Updates (Out-of-Order Processing)**
```
Initial State: 
- Group has subtotal = 500M USD
- Settlement X version 1: amount = 80M USD (included in subtotal)

Out-of-Order Scenario:
- Settlement X version 3 (amount = 90M USD) arrives at time T
- Settlement X version 2 (amount = 120M USD) arrives at time T+1ms

Challenge: Version 3 arrives before version 2, but version 2 should be ignored since version 3 is newer

Solution - Version-Aware Processing with Compensation:

1. When processing version 3 (arrives first):
   - Check: Is version 3 > current max version (1)? YES
   - Read Settlement X version 1: old_amount = 80M
   - Calculate delta: 90M - 80M = +10M
   - Execute: UPDATE groups SET subtotal = subtotal + 10 WHERE group_id = ?
   - Store Settlement X version 3 with amount = 90M
   - Update max_processed_version = 3

2. When processing version 2 (arrives second):
   - Check: Is version 2 > current max version (3)? NO
   - Action: IGNORE - this is a stale version
   - No group update, no storage

Alternative Implementation - Idempotent Recalculation:
Instead of delta calculations, use idempotent operations:

```sql
-- For any settlement version update, recalculate the entire settlement's contribution
BEGIN TRANSACTION;

-- Lock the settlement to prevent concurrent updates
SELECT settlement_id FROM settlements 
WHERE settlement_id = ? 
FOR UPDATE;

-- Get the current contribution of this settlement to the group
SELECT COALESCE(SUM(amount * exchange_rate), 0) as current_contribution
FROM settlements s
JOIN exchange_rates r ON s.currency = r.from_currency
WHERE s.settlement_id = ? 
AND s.settlement_version = (
    SELECT MAX(settlement_version) 
    FROM settlements 
    WHERE settlement_id = s.settlement_id
);

-- Insert/Update the new version (with conflict resolution)
INSERT INTO settlements (...) VALUES (...)
ON CONFLICT (settlement_id, settlement_version) 
DO NOTHING; -- Ignore if already processed

-- Calculate new contribution after the insert
SELECT COALESCE(SUM(amount * exchange_rate), 0) as new_contribution
FROM settlements s
JOIN exchange_rates r ON s.currency = r.from_currency  
WHERE s.settlement_id = ?
AND s.settlement_version = (
    SELECT MAX(settlement_version) 
    FROM settlements 
    WHERE settlement_id = s.settlement_id
);

-- Apply the net change atomically
UPDATE settlement_groups 
SET subtotal_usd = subtotal_usd - current_contribution + new_contribution,
    version = version + 1
WHERE group_id = ?;

COMMIT;
```

Benefits of Idempotent Approach:
- Handles out-of-order processing correctly
- Duplicate processing is safe (idempotent)
- Always uses the latest version regardless of arrival order
- Compensates for any previous incorrect calculations
```

### Simple Event-Driven Architecture Solution

Instead of trying to solve complex race conditions with locks and atomic operations, let's use a fundamentally different approach that eliminates the problems entirely.

#### Core Insight: Don't Calculate - Just Store Events

**The Problem with Current Approach**: We're trying to maintain real-time calculated state (subtotals, statuses) which creates all the race condition complexity.

**Simple Solution**: Store settlement events in order, calculate everything on-demand from the event stream.

#### New Architecture: Event Sourcing with Materialized Views

**1. Settlement Event Store**
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
  };
}
```

**2. Background Calculation Service**
- Single-threaded processor that reads events in order
- Calculates group subtotals sequentially (no race conditions possible)
- Updates materialized views with current state
- Runs every few seconds, processes all new events

**3. Materialized Views (Read-Only)**
```typescript
interface GroupSubtotal {
  groupId: string;
  pts: string;
  processingEntity: string;
  counterpartyId: string;
  valueDate: Date;
  subtotalUsd: number;
  lastProcessedEventId: string;
  calculatedAt: Date;
}

interface SettlementStatus {
  settlementId: string;
  currentVersion: number;
  status: 'CREATED' | 'BLOCKED' | 'PENDING_AUTHORISE' | 'AUTHORISED';
  groupSubtotal: number;
  exposureLimit: number;
}
```

#### How This Solves All Problems

**Race Conditions**: Eliminated - single-threaded processing in event order
**Out-of-Order Processing**: Handled - events are processed by `processingOrder`, not arrival time
**Version Updates**: Simple - just another event in the stream
**Mass Updates**: Eliminated - no stored status fields to update
**Performance**: Excellent - writes are just appends, reads are from materialized views

#### Processing Flow

**1. Settlement Ingestion (Fast)**
```
Receive Settlement → Validate → Append to Event Store → Return ACK
(No calculations, no locks, just append - extremely fast)
```

**2. Background Processing (Sequential)**
```
Every 5 seconds:
1. Read new events in processingOrder
2. For each event:
   - Apply filtering rules (current rules)
   - Update group subtotal (no race conditions - single thread)
   - Calculate status based on current limits
   - Update materialized views
3. Mark events as processed
```

**3. Query Processing (Fast)**
```
UI/API Queries → Read from Materialized Views → Return Results
(No calculations needed, just read pre-computed state)
```

#### Benefits of This Approach

**Simplicity**: No complex locking, atomic operations, or race condition handling
**Performance**: Writes are O(1) appends, reads are O(1) lookups
**Consistency**: Single-threaded processing guarantees consistent state
**Auditability**: Complete event history for compliance
**Scalability**: Can replay events to rebuild state, easy to debug
**Flexibility**: Can change calculation logic and replay events

#### Handling High Volume

**Event Ingestion**: Can handle 200K settlements in 30 minutes easily (just appends)
**Processing Lag**: 5-second processing window means status updates appear within 5-10 seconds
**Scaling**: If processing can't keep up, add more background processors (partition by group)

#### Real-Time Requirements

**Status Updates**: Available within 5-10 seconds (acceptable per requirements)
**API Queries**: Instant (reading materialized views)
**Approval Actions**: Update approval table immediately, status recalculated in next cycle

This approach is much simpler, more reliable, and easier to understand than complex concurrency control mechanisms.

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
- **Data Integrity**: Immutable settlement records and optimistic locking prevent data corruption during high-volume processing
- **Concurrency Safety**: Optimistic locking ensures subtotal calculations are atomic and consistent

## Components and Interfaces

### Settlement Ingestion Service
**Responsibilities:**
- Receive settlement flows from PTS endpoints
- Validate settlement data structure and completeness
- Apply filtering rules to determine calculation eligibility
- Store settlements with versioning support
- Trigger aggregation calculations

**Key Interfaces:**
```
POST /api/settlements/ingest
- Accepts settlement data from PTS systems
- Returns acknowledgment with processing status

GET /api/settlements/{settlementId}/status
- Returns current settlement status and details
- Used by external systems for status queries
```

### Aggregation Engine
**Responsibilities:**
- Group settlements by PTS, Processing Entity, Counterparty ID, and Value Date
- Convert currencies to USD using latest exchange rates
- Calculate and maintain group subtotals
- Handle settlement version updates and group migrations
- Compute settlement eligibility using current filtering rules

**Key Operations:**
- Real-time subtotal calculation (< 10 seconds) using materialized group totals
- Group rebalancing when settlement details change
- Dynamic currency conversion with latest available rates
- Efficient handling of high-volume updates without mass settlement record updates
- On-demand status computation to avoid performance bottlenecks

**Performance Optimizations:**
- Maintain materialized subtotals at group level only
- Use Redis caching for frequently accessed group calculations
- Compute individual settlement status dynamically from group state
- Batch process settlement updates to minimize database transactions

**Concurrency Control:**
- Use atomic increment/decrement operations for subtotal updates to avoid read-calculate-update race conditions
- Implement distributed locking (Redis) for complex operations that require full recalculation
- Use database transactions for multi-group operations (settlement migration)
- Maintain operation idempotency to handle duplicate processing
- Monitor lock contention and implement backoff strategies for high-volume groups

**Atomic Operations by Scenario:**
- **New Settlement**: `subtotal += settlement_usd_amount`
- **Version Update (Idempotent)**: 
  1. Lock settlement by `settlement_id` (not version)
  2. Calculate current contribution of settlement (latest version)
  3. Insert new version with `ON CONFLICT DO NOTHING` (idempotent)
  4. Calculate new contribution of settlement (after insert)
  5. `subtotal = subtotal - old_contribution + new_contribution` (atomic)
- **Group Migration**: Transaction with `old_group.subtotal -= amount; new_group.subtotal += amount`
- **Rule Changes**: Distributed lock + full recalculation from `SELECT SUM(amount * rate) WHERE eligible`

**Key Insight for Out-of-Order Processing**: 
Instead of tracking deltas between versions, we recalculate the entire settlement's contribution idempotently. This handles out-of-order arrival, duplicate processing, and ensures the group subtotal always reflects the latest version regardless of processing order.

### Limit Monitoring Service
**Responsibilities:**
- Compare group subtotals against exposure limits
- Compute settlement status dynamically based on group state and approval records
- Handle limit updates and re-evaluation at group level
- Support both fixed limits (MVP) and counterparty-specific limits

**Status Computation Logic:**
- CREATED: Group subtotal within limit AND no approval record exists
- BLOCKED: Group subtotal exceeds limit AND no approval record exists  
- PENDING_AUTHORISE: Approval record exists with REQUEST RELEASE action
- AUTHORISED: Approval record exists with both REQUEST RELEASE and AUTHORISE actions

**Performance Considerations:**
- Status computed on-demand to avoid mass updates when group totals fluctuate
- Only settlements with approval actions have persistent status records
- Group-level limit evaluation prevents individual settlement updates
- Caching layer reduces computation overhead for frequently queried settlements

### Approval Workflow Service
**Responsibilities:**
- Enforce two-person approval process
- Prevent same user from performing both REQUEST RELEASE and AUTHORISE
- Maintain comprehensive audit trails
- Handle bulk operations on same-group settlements
- Reset approvals when settlement versions change

**Audit Trail Fields:**
- User identity and timestamp
- Action type (REQUEST RELEASE, AUTHORISE)
- Settlement ID and version
- Group context and subtotal at time of action

## Data Models

### Event Store (Write Side)
```typescript
interface SettlementEvent {
  eventId: string;
  settlementId: string;
  settlementVersion: number;
  eventType: 'SETTLEMENT_RECEIVED' | 'SETTLEMENT_UPDATED';
  eventTimestamp: Date;
  processingOrder: number; // Auto-incrementing sequence for ordering
  settlementData: {
    pts: string;
    processingEntity: string;
    counterpartyId: string;
    valueDate: Date;
    currency: string;
    amount: number;
  };
  processed: boolean;
  processedAt?: Date;
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
  settlementVersion: number;
  requestedBy?: string;
  requestedAt?: Date;
  authorizedBy?: string;
  authorizedAt?: Date;
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
  groupContext: {
    pts: string;
    processingEntity: string;
    counterpartyId: string;
    valueDate: Date;
    subtotalAtAction: number;
  };
}

enum AuditAction {
  REQUEST_RELEASE = 'REQUEST_RELEASE',
  AUTHORISE = 'AUTHORISE',
  STATUS_RESET = 'STATUS_RESET'
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
*For any* valid settlement data received, the system should store all required fields (PTS, Processing_Entity, Counterparty_ID, Value_Date, Currency, Amount, Settlement_ID, Settlement_Version) without loss or corruption
**Validates: Requirements 1.1**

**Property 2: Filtering Rule Application**
*For any* settlement and current filtering rules, the system should correctly determine eligibility for subtotal calculations based on the rule criteria
**Validates: Requirements 1.2**

**Property 3: Version Management**
*For any* settlement with multiple versions, only the latest version should be active in calculations while all historical versions remain accessible for audit
**Validates: Requirements 1.5**

**Property 4: Data Preservation**
*For any* settlement stored, the original currency and amount values should remain unchanged after storage operations
**Validates: Requirements 1.6**

**Property 5: Invalid Data Rejection**
*For any* settlement with invalid or incomplete data, the system should reject it and not include it in any calculations
**Validates: Requirements 1.7**

### Aggregation and Calculation Properties

**Property 6: Settlement Grouping**
*For any* set of settlements, those with identical PTS, Processing_Entity, Counterparty_ID, and Value_Date should be grouped together
**Validates: Requirements 2.1**

**Property 7: Subtotal Calculation Accuracy**
*For any* group of settlements, the subtotal should equal the sum of USD-converted amounts for all eligible settlements in the group
**Validates: Requirements 2.2**

**Property 8: Incremental Subtotal Updates**
*For any* existing group, adding a new eligible settlement should increase the subtotal by exactly the USD equivalent of the new settlement's amount
**Validates: Requirements 2.3**

**Property 9: Version Update Consistency**
*For any* settlement version update, the group subtotal should reflect only the new version's amount and exclude the previous version's contribution
**Validates: Requirements 2.4**

**Property 10: Group Migration Accuracy**
*For any* settlement that changes group keys (PTS, Processing_Entity, Counterparty_ID, or Value_Date), it should be removed from the old group and added to the correct new group, with both subtotals recalculated accurately
**Validates: Requirements 2.5**

### Status Management Properties

**Property 11: Status Assignment for Compliant Groups**
*For any* settlement in a group where the subtotal is within the exposure limit, the settlement status should be CREATED
**Validates: Requirements 3.1**

**Property 12: Status Assignment for Exceeding Groups**
*For any* settlement in a group where the subtotal exceeds the exposure limit, the settlement status should be BLOCKED
**Validates: Requirements 3.2**

**Property 13: Limit Change Re-evaluation**
*For any* exposure limit update, all settlements should be re-evaluated and their statuses should reflect the new limit comparison
**Validates: Requirements 3.3**

**Property 14: Status Reset on Version Change**
*For any* settlement that receives a new version after approval actions, the status should reset to CREATED or BLOCKED based on the current group subtotal, invalidating previous approvals
**Validates: Requirements 2.6, 4.5**

### Approval Workflow Properties

**Property 15: REQUEST RELEASE Transition**
*For any* BLOCKED settlement, a REQUEST RELEASE action should change the status to PENDING_AUTHORISE and create an audit record
**Validates: Requirements 4.2**

**Property 16: AUTHORISE Transition**
*For any* PENDING_AUTHORISE settlement, an AUTHORISE action by a different user should change the status to AUTHORISED and create an audit record
**Validates: Requirements 4.3**

**Property 17: Segregation of Duties**
*For any* settlement, the same user should not be able to perform both REQUEST RELEASE and AUTHORISE actions
**Validates: Requirements 4.4**

**Property 18: Bulk Action Consistency**
*For any* bulk operation on settlements from the same group, each settlement should receive the action and have an individual audit entry created
**Validates: Requirements 4.7**

**Property 19: Audit Trail Completeness**
*For any* user action (REQUEST RELEASE, AUTHORISE), a complete audit record should be created with user identity, timestamp, settlement details, and version information
**Validates: Requirements 4.8**

### Search and Query Properties

**Property 20: Search Filter Accuracy**
*For any* search criteria (PTS, Processing_Entity, Value_Date, Counterparty_ID), the results should contain only settlements that match all specified criteria
**Validates: Requirements 6.1, 6.3**

**Property 21: Limit Status Filtering**
*For any* limit status filter (exceeds/does not exceed), the results should contain only settlements whose groups match the specified limit relationship
**Validates: Requirements 6.2**

**Property 22: Group Selection Display**
*For any* selected settlement group, the detail view should display all and only the settlements that belong to that specific group
**Validates: Requirements 6.8**

### API and Integration Properties

**Property 23: Status Query Accuracy**
*For any* valid Settlement_ID queried via API, the system should return the current accurate status and relevant details
**Validates: Requirements 7.1**

**Property 24: Authorization Notification**
*For any* settlement that transitions to AUTHORISED status, a notification should be sent to external systems with the Settlement_ID and authorization details
**Validates: Requirements 7.6**

**Property 25: Manual Recalculation Scope**
*For any* manual recalculation request with scope criteria, all and only the settlements matching the criteria should have their subtotals recalculated using current rules and limits
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
**Validates: Requirements 8.2**

### Audit and Compliance Properties

**Property 29: Historical Data Accessibility**
*For any* settlement, all versions and their timestamps should remain accessible for audit and compliance queries
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