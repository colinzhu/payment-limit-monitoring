# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with this repository.

## Project Overview

**Payment Limit Monitoring System** - A financial risk management application built with Vert.x/Java that tracks settlement flows from trading systems, calculates aggregated exposure by counterparty and value date, and flags transactions exceeding predefined limits.

**Status**: Phase 1 complete - Settlement Ingestion Flow fully implemented (31 files). This is a working implementation, not just design/specification.

## Technology Stack

- **Framework**: Vert.x 4.5.23 (async, event-driven)
- **Language**: Java 21
- **Database**: Oracle Database (production, development, testing)
- **Build Tool**: Maven
- **Testing**: JUnit 5, Mockito, Vert.x Unit
- **Architecture**: Hexagonal Architecture with Event-Driven design

## Common Bash Commands

```bash
# Build and test
mvn clean compile
mvn test
mvn clean package

# Run application
mvn exec:java -Dexec.mainClass="com.tvpc.Main"

# Run specific tests
mvn test -Dtest=SettlementValidatorTest
mvn test -Dtest=SettlementValidatorTest#testValidSettlement

# Check Java/Maven versions
java -version
mvn -version

# Test API endpoints
curl http://localhost:8081/health
curl -X POST http://localhost:8081/api/settlements -H "Content-Type: application/json" -d @test-settlement.json

# View database state (requires Oracle running)
podman exec -it oracle-db sqlplus tvpc/tvpc123@//localhost:1521/FREEPDB1
```

## Code Style Guidelines

- **Java 21**: Use modern Java features where appropriate
- **Vert.x patterns**: Use async handlers, avoid blocking operations
- **Hexagonal architecture**: Keep domain pure, dependencies point inward
- **Transaction safety**: All critical operations must be in transactions
- **Error handling**: Use proper exception handling, return meaningful error messages
- **Logging**: Use SLF4J with Logback, follow existing log patterns
- **Naming**: Follow existing naming conventions in the codebase
- **Comments**: Add comments for complex business logic, especially around the 5-step ingestion flow

## Testing Instructions

**Always run tests before committing:**
```bash
mvn clean test
```

**Test structure:**
- Unit tests in `src/test/java/com/tvpc/`
- Validation tests: `SettlementValidatorTest.java` (11 test cases)
- Domain tests: `SettlementTest.java`, `EnumTest.java`
- Service tests: Test the 5-step ingestion flow

**When making changes:**
1. Run all tests first to ensure baseline
2. Add tests for new functionality
3. Run tests again to verify
4. Check for any integration issues

## Workflow Tips

### 1. Understand the 5-Step Ingestion Flow
Before modifying `SettlementIngestionService.java`, understand:
- Step 0: Validation (SettlementValidator)
- Step 1: Save → Get REF_ID
- Step 2: Mark old versions
- Step 3: Detect counterparty changes
- Step 4: Generate events (1 or 2)
- Step 5: Calculate running totals

**All steps execute in a single transaction.**

### 2. Database Operations
- **SETTLEMENT table**: Latest versions only, uses `IS_OLD` flag
- **Unique constraint**: `(SETTLEMENT_ID, PTS, PROCESSING_ENTITY, SETTLEMENT_VERSION)` prevents duplicates
- **Sequence ID (ID column)**: Auto-incrementing, becomes REF_ID, critical for ordering
- **RUNNING_TOTAL**: Aggregated by group, updated via MERGE

### 3. Event System
- Events published to Vert.x event bus
- `RunningTotalProcessorVerticle` consumes events
- Events contain: `PTS`, `PROCESSING_ENTITY`, `COUNTERPARTY_ID`, `VALUE_DATE`, `REF_ID`
- Counterparty changes trigger 2 events (old + new)

### 4. Common Pitfalls to Avoid
- ❌ Don't store status fields - compute on-demand
- ❌ Don't use incremental updates - always complete recalculation
- ❌ Don't skip version history - audit requirement
- ❌ Don't process events concurrently - single-threaded processor
- ❌ Don't ignore counterparty changes - must trigger dual events

### 5. Debugging
- Enable debug logging in `src/main/resources/logback.xml`
- Check database state with SQL queries
- Use curl to test API endpoints
- Vert.x logs show event bus activity

## Key Files to Reference

**Core implementation:**
- `src/main/java/com/tvpc/service/SettlementIngestionService.java` - Main orchestrator
- `src/main/java/com/tvpc/repository/impl/JdbcSettlementRepository.java` - DB operations
- `src/main/java/com/tvpc/processor/RunningTotalProcessorVerticle.java` - Event consumer
- `src/main/java/com/tvpc/validation/SettlementValidator.java` - Input validation

**Configuration:**
- `src/main/resources/application.yml` - App config (HTTP port, DB connection)
- `src/main/resources/db/schema.sql` - Oracle DDL for 6 tables
- `src/main/resources/logback.xml` - Logging config

**Documentation:**
- `.kiro/specs/payment-limit-monitoring/requirements.md` - Requirements
- `.kiro/specs/payment-limit-monitoring/tech-design.md` - Technical design
- `IMPLEMENTATION.md` - Implementation summary
- `SUMMARY.md` - Project summary

## Important Notes

1. **This is a working implementation** - Can be compiled and run immediately
2. **Oracle database required** - Configure in `application.yml`
3. **Event-driven architecture** - Vert.x event bus for async processing
4. **Single-threaded processor** - Critical for consistency
5. **Complete recalculation** - No incremental updates
6. **Transaction safety** - All critical operations atomic

## Proxy Configuration

If you cannot connect to external sites, use the proxy:
```bash
source set-proxy.sh  # Sets HTTP_PROXY and HTTPS_PROXY to 127.0.0.1:4080
```

## Quick Start

1. Check environment: `java -version && mvn -version`
2. Build: `mvn clean compile`
3. Run tests: `mvn test`
4. Set up Oracle database (run schema.sql)
5. Configure: `src/main/resources/application.yml`
6. Run: `mvn exec:java -Dexec.mainClass="com.tvpc.Main"`
7. Test: `curl http://localhost:8081/health`

## Contact/Support

This is a Vert.x-based Java application following hexagonal architecture principles. All critical operations are transactional and event-driven.

```
src/main/java/com/tvpc/
├── domain/              # Entities and enums (4 files)
│   ├── Settlement.java              # Core entity
│   ├── SettlementDirection.java     # PAY/RECEIVE enum
│   ├── SettlementType.java          # GROSS/NET enum
│   └── BusinessStatus.java          # PENDING/INVALID/VERIFIED/CANCELLED enum
├── dto/                 # API data transfer objects (3 files)
│   ├── SettlementRequest.java
│   ├── SettlementResponse.java
│   └── ValidationResult.java
├── repository/          # Database access layer (8 files)
│   ├── SettlementRepository.java (interface)
│   ├── JdbcSettlementRepository.java (JDBC implementation)
│   ├── RunningTotalRepository.java (interface)
│   ├── JdbcRunningTotalRepository.java
│   ├── ExchangeRateRepository.java (interface)
│   ├── JdbcExchangeRateRepository.java
│   ├── ActivityRepository.java (interface)
│   └── JdbcActivityRepository.java
├── validation/          # Input validation (1 file)
│   └── SettlementValidator.java
├── event/               # Event system (2 files)
│   ├── SettlementEvent.java
│   ├── EventPublisher.java
│   └── SettlementEventCodec.java
├── service/             # Business services (3 files)
│   ├── SettlementIngestionService.java  # Main 5-step flow
│   ├── ConfigurationService.java (interface)
│   └── impl/
│       └── InMemoryConfigurationService.java
├── handler/             # HTTP handlers (1 file)
│   └── SettlementIngestionHandler.java
├── router/              # HTTP routing (1 file)
│   └── SettlementRouter.java
├── processor/           # Event processors (1 file)
│   └── RunningTotalProcessorVerticle.java
├── config/              # Configuration classes
├── HttpServerVerticle.java          # HTTP server setup
└── Main.java            # Application entry point

src/main/resources/
├── db/schema.sql        # Oracle DDL for 6 tables
├── application.yml      # App configuration
└── logback.xml          # Logging configuration

src/test/java/com/tvpc/
├── validation/          # Validation tests
├── domain/              # Domain entity tests
└── service/             # Service tests
```

## Core Architecture Concepts

### The Sequence ID (REF_ID) - Critical Concept

Every settlement gets an **auto-incrementing sequence ID** (`ID` column in SETTLEMENT table) that serves as the foundation for consistency:
- Monotonically increasing primary key from Oracle identity column
- **NOT a version number** - it only increases
- Defines scope: "calculate running totals using all settlements with ID ≤ seqId"
- Used as `REF_ID` in `RUNNING_TOTAL` table
- Guarantees ordering and idempotency

### Settlement Ingestion Flow (5 Steps)

All steps execute in a **single database transaction**:

```
Step 0: Validate (SettlementValidator)
  ↓
Step 1: Save Settlement → Get REF_ID (SettlementRepository.save)
  ↓
Step 2: Mark Old Versions (SettlementRepository.markOldVersions)
  ↓
Step 3: Detect Counterparty Changes (SettlementRepository.findPreviousCounterparty)
  ↓
Step 4: Generate Events (EventPublisher) - 1 or 2 events
  ↓
Step 5: Calculate Running Total (SettlementIngestionService.calculateRunningTotal)
```

**Key**: All 5 steps are executed **synchronously** within the HTTP request. So that it doesn't need extra time on event dispatching.

**Duplicate Handling**: The database has a unique constraint on `(SETTLEMENT_ID, PTS, PROCESSING_ENTITY, SETTLEMENT_VERSION)`. If a duplicate is inserted:
- The database throws a constraint violation exception
- The service catches it and queries for the existing settlement's ID
- Uses that ID as REF_ID for the rest of the flow
- Returns HTTP 200 with the existing sequence ID
- This makes the operation idempotent
- This allows settlement resend from client can trigger all the steps in case of a failure

### Event System

- **SettlementEvent**: Contains PTS, PROCESSING_ENTITY, COUNTERPARTY_ID, VALUE_DATE, REF_ID
- **EventPublisher**: Publishes to Vert.x event bus
- **RunningTotalProcessorVerticle**: Multiple instances
- Events trigger async recalculation when needed

### Database Schema (6 Tables)

1. **SETTLEMENT** - Latest versions only
   - `ID` (auto-increment, becomes REF_ID)
   - `SETTLEMENT_ID`, `SETTLEMENT_VERSION`
   - Group fields: `PTS`, `PROCESSING_ENTITY`, `COUNTERPARTY_ID`, `VALUE_DATE`
   - Transaction: `CURRENCY`, `AMOUNT`, `DIRECTION`, `BUSINESS_STATUS`, `GROSS_NET`
   - Version flag: `IS_OLD`
   - **Unique Constraint**: `(SETTLEMENT_ID, PTS, PROCESSING_ENTITY, SETTLEMENT_VERSION)` - prevents duplicates

2. **SETTLEMENT_HIST** - Archived old versions
3. **EXCHANGE_RATE** - Latest currency rates
4. **RUNNING_TOTAL** - Aggregated exposure per group
5. **ACTIVITIES** - Audit trail
6. **NOTIFICATION_QUEUE** - External notifications with retry

## Key Design Principles Implemented

✅ **Complete Recalculation** - Always recalculates full group totals
✅ **On-Demand Status** - Status computed at query time (not stored)
✅ **Sequence ID Ordering** - Monotonic ID for version control
✅ **Single-Threaded Processor** - Eliminates race conditions
✅ **Event-Driven** - Handles high volume with consistency
✅ **Atomic Operations** - All critical operations in transactions
✅ **Version Management** - Latest + historical versions preserved

## Common Pitfalls to Avoid

❌ **Don't store status fields** - Compute on-demand
❌ **Don't use incremental updates** - Always complete recalculation
❌ **Don't skip version history** - Audit requirement
❌ **Don't allow same user to request and authorize** - Security violation
❌ **Don't recalculate historical data on rate changes** - Rates fixed at processing time
❌ **Don't process events concurrently** - Use single-threaded processor
❌ **Don't ignore counterparty changes** - Must trigger dual events

## Build and Run Commands

### Build
```bash
mvn clean compile
```

### Run Tests
```bash
# All tests
mvn test

# Single test class
mvn test -Dtest=SettlementValidatorTest

# Single test method
mvn test -Dtest=SettlementValidatorTest#testValidSettlement
```

### Run Application
```bash
# Using Maven exec plugin
mvn exec:java -Dexec.mainClass="com.tvpc.Main"

# Or compile and run directly
mvn clean package
java -cp target/payment-limit-monitoring-1.0.0.jar com.tvpc.Main
```

### Package
```bash
mvn clean package  # Creates JAR in target/
```

## Configuration

**File**: `src/main/resources/application.yml`

```yaml
http:
  port: 8081

database:
  url: jdbc:oracle:thin:@//localhost:1521/FREEPDB1
  driver_class: oracle.jdbc.OracleDriver
  user: tvpc
  password: tvpc123

app:
  mvp_mode: true  # Fixed 500M limit
```

**Note**: The `Main.java` has hardcoded Oracle defaults if config file is not found:
- URL: `jdbc:oracle:thin:@//localhost:1521/FREEPDB1`
- User: `tvpc`
- Password: `tvpc123`

## API Endpoints

### Settlement Ingestion
```http
POST /api/settlements
Content-Type: application/json

{
  "settlementId": "SETT-12345",
  "settlementVersion": 1735689600000,
  "pts": "PTS-A",
  "processingEntity": "PE-001",
  "counterpartyId": "CP-ABC",
  "valueDate": "2025-12-31",
  "currency": "EUR",
  "amount": 1000000.00,
  "businessStatus": "VERIFIED",
  "direction": "PAY",
  "settlementType": "GROSS"
}

Response (201 Created):
{
  "status": "success",
  "message": "Settlement processed successfully",
  "sequenceId": 12345
}
```

### Health Check
```http
GET /health

Response:
{
  "status": "UP",
  "service": "payment-limit-monitoring"
}
```

## Validation Rules

| Field | Requirement | Error |
|-------|-------------|-------|
| settlementId | Required, max 100 chars | "settlementId is required" |
| settlementVersion | Required, positive timestamp | "settlementVersion is required" |
| pts | Required | "pts is required" |
| processingEntity | Required | "processingEntity is required" |
| counterpartyId | Required | "counterpartyId is required" |
| valueDate | Required, ISO format (YYYY-MM-DD), not past | "valueDate must be in ISO format" |
| currency | Required, 3 chars, ISO 4217 | "currency must be a 3-character ISO 4217 code" |
| amount | Required, non-negative, ≤2 decimals | "amount must be non-negative" |
| businessStatus | Required, valid enum | "businessStatus must be one of: PENDING, INVALID, VERIFIED, CANCELLED" |
| direction | Required, PAY or RECEIVE | "direction must be either PAY or RECEIVE" |
| settlementType | Required, GROSS or NET | "settlementType must be either GROSS or NET" |

## Critical Implementation Details

### 1. Transaction Safety
- save settlement in a transaction
- mark old versions in a transaction
- detect counterparty changes and calculate running total in a transaction


### 2: Mark `IS_OLD` for old versions
**Mark Old**:
```sql
UPDATE SETTLEMENT SET IS_OLD = true, UPDATE_TIME = CURRENT_TIMESTAMP
WHERE SETTLEMENT_ID = ? AND PTS = ? AND PROCESSING_ENTITY = ? 
  AND SETTLEMENT_VERSION < (SELECT MAX(SETTLEMENT_VERSION) FROM SETTLEMENT WHERE SETTLEMENT_ID = ? AND PTS = ? AND PROCESSING_ENTITY = ?)
  AND IS_OLD IS NULL 
```
- Immediate after the new settlement is saved.
- Old records will be moved to `SETTLEMENT_HIST` daily non-busy hours

### 3. Counterparty Change Detection
```sql
-- Find previous counterparty
SELECT COUNTERPARTY_ID FROM SETTLEMENT
WHERE ID = (SELECT MAX(ID) FROM SETTLEMENT
            WHERE SETTLEMENT_ID = ? AND PTS = ? AND PROCESSING_ENTITY = ? AND ID < ?)
```

### 4. Event Generation
- **Event format**: `PTS`, `PROCESSING_ENTITY`, `COUNTERPARTY_ID`, `VALUE_DATE` and `REF_ID` - Sequence ID of current settlement
- **Default**: 1 event
- **If counterparty changed**: 2 events
   - One for old counterparty group
   - One for new counterparty group

### 5. Calculate the running total for the group in the event
- Loop the events, for each event invoke the running total calculator to insert / update the `RUNNING_TOTAL`
- For better performance, use single SQL to SELECT the settlements and CALCULATE the total and UPDATE/INSERT the `RUNNING_TOTAL`.
- **Filter settlements**:
   - `PTS`, `PROCESSING_ENTITY`, `COUNTERPARTY_ID`, `VALUE_DATE` = values from event
   - `BUSINESS_STATUS` values from the rules from external system, e.g. in PENDING, VERIFIED, or CANCELLED
   - `DIRECTION` values from the rules from external system, e.g. = PAY
   - `SETTLEMENT_VERSION` = MAX(SETTLEMENT_VERSION) of current `SETTLEMENT_ID`,`PTS`,`PROCESSING_ENTITY` -- to filter out `COUNTERPARTY_ID` changed record
   - `ID` <= sequence ID of current settlement
- **Calculate running total**:
   - JOIN with `EXCHANGE_RATE` to convert to USD
   - SUM
- **Save to Running Total**: user Oracle MERGE to insert / update. Below is only one SQL to update for reference. 
```sql
UPDATE RUNNING_TOTAL SET RUNNING_TOTAL = ?, UPDATE_TIME = CURRENT_TIMESTAMP, REF_ID = ?
WHERE PTS = ? AND PROCESSING_ENTITY = ? AND COUNTERPARTY_ID = ? AND VALUE_DATE = ?
AND REF_ID <= ? -- make sure it's "<=" so that can use same ID to trigger recalculation; also used to avoid concurrent updates by another thread/instance
``` 


## Performance Targets

| Metric | Target | Implementation |
|--------|--------|----------------|
| Settlement ingestion | 200K / 30 min (~111/sec) | ✅ Transaction-based |
| Status availability | < 30 seconds | ✅ Sync step 5 |
| Subtotal recalculation | < 10 seconds | ✅ Single SQL query |
| API response (p99) | < 3 seconds | ✅ Vert.x async |

## Testing Structure

### Unit Tests (11 test cases)
- `SettlementValidatorTest.java` - All 11 field validations
- `SettlementTest.java` - Entity behavior
- `EnumTest.java` - Enum validation

### Running Specific Tests
```bash
# Run all validation tests
mvn test -Dtest=SettlementValidatorTest

# Run specific test
mvn test -Dtest=SettlementValidatorTest#testInvalidCurrency
```

## Database Setup

### Oracle Database
```sql
-- Connect as tvpc user
-- Run schema.sql
@src/main/resources/db/schema.sql
```

## Key Classes and Their Responsibilities

### SettlementIngestionService.java
**Main orchestrator** - Implements the 5-step flow:
- `processSettlement()` - Entry point, manages transaction
- `executeIngestionSteps()` - Coordinates all steps
- `calculateRunningTotal()` - Complete recalculation logic
- `generateEvents()` - Creates 1 or 2 events

### JdbcSettlementRepository.java
**Database operations**:
- `save()` - Insert settlement, return ID (will be used as REF_ID)
- `markOldVersions()` - Update IS_OLD flag
- `findPreviousCounterparty()` - Detect counterparty changes

### RunningTotalProcessorVerticle.java
**Event consumer**
- Listens on event bus
- Invoke running total calculator for each event

### SettlementValidator.java
**Input validation** - 11 field checks:
- Required fields
- Data types and formats
- Enum values
- Business rules


## Next Phases (Roadmap)

### Phase 2: Status Calculation & Approval Workflow
- Query-time status calculation (CREATED, BLOCKED, PENDING_AUTHORISE, AUTHORISED)
- Two-step approval workflow with user segregation
- Audit trail for approvals

### Phase 3: Search & Export
- Multi-criteria search interface
- Excel export functionality
- Settlement group display

### Phase 4: External APIs
- Query by Settlement ID endpoint
- Manual recalculation trigger
- Notification system with exponential backoff retry

### Phase 5: Configuration Management
- Exchange rate fetching from external system
- Rule system integration (every 5 minutes)
- Counterparty-specific limits

### Phase 6: Performance & Monitoring
- Performance testing and optimization
- Distributed processing safeguards
- Comprehensive audit and compliance features

## Debugging Tips

### Enable Debug Logging
Edit `src/main/resources/logback.xml`:
```xml
<logger name="com.tvpc" level="DEBUG"/>
```

### Check Database State
```sql
-- View latest settlements
SELECT * FROM SETTLEMENT ORDER BY ID DESC;

-- View running totals
SELECT * FROM RUNNING_TOTAL;

-- View activities
SELECT * FROM ACTIVITIES ORDER BY CREATE_TIME DESC;
```

### Test with curl
```bash
# Valid settlement
curl -X POST http://localhost:8081/api/settlements \
  -H "Content-Type: application/json" \
  -d '{"settlementId":"SETT-12345","settlementVersion":1735689600000,"pts":"PTS-A","processingEntity":"PE-001","counterpartyId":"CP-ABC","valueDate":"2025-12-31","currency":"EUR","amount":1000000.00,"businessStatus":"VERIFIED","direction":"PAY","settlementType":"GROSS"}'

# Health check
curl http://localhost:8081/health
```

## Important Notes

1. **This is a working implementation** - Not just design documents
2. **Phase 1 complete** - Settlement ingestion fully functional
3. **Ready for deployment** - Can be compiled and run immediately
4. **Oracle** - Switch via configuration
5. **Event-driven architecture** - Vert.x event bus for async processing
6. **Single-threaded processor** - Critical for consistency
7. **Complete recalculation** - No incremental updates
8. **Transaction safety** - All steps atomic

## Files to Reference

- **Requirements**: `.kiro/specs/payment-limit-monitoring/requirements.md`
- **Tech Design**: `.kiro/specs/payment-limit-monitoring/tech-design.md`
- **Implementation Summary**: `IMPLEMENTATION.md`
- **Project Summary**: `SUMMARY.md`
- **Current Status**: `README.md`

## Quick Start Checklist

- [ ] Check Java 21 is installed: `java -version`
- [ ] Check Maven is installed: `mvn -version`
- [ ] Run: `mvn clean compile`
- [ ] Run tests: `mvn test`
- [ ] Set up database (Oracle)
- [ ] Configure: `src/main/resources/application.yml`
- [ ] Run: `mvn exec:java -Dexec.mainClass="com.tvpc.Main"`
- [ ] Test: `curl http://localhost:8081/health`

## Contact/Support

This is a Vert.x-based Java application following hexagonal architecture principles. All critical operations are transactional and event-driven.

## Tips:
- use 127.0.0.1:4080 for HTTP and HTTPS proxy if not able to connect to any web site
- learn Vert.x by source code and document from maven repository, use maven to download the source and document if they are not available
- learn Vert.x from its website https://vertx.io/