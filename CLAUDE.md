# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with this repository.

## Project Overview

**Payment Limit Monitoring System** - A financial risk management application built with Vert.x/Java that tracks settlement flows from trading systems, calculates aggregated exposure by counterparty and value date, and flags transactions exceeding predefined limits.

**Status**: Phase 1 90% complete - Settlement Ingestion Flow fully implemented. This is a working implementation, needs fine-tuning.
**TO DO**:
- [ ] ingestion flow save settlement to return auto generated REF_ID
- [ ] and fix the duplicate settlement able to be saved issue
- [ ] update project structure to use hexagonal architecture

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

# Start Oracle DB
podman start oracle-db
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
- Validation tests: `SettlementValidatorTest.java`
- Domain tests: `SettlementTest.java`, `EnumTest.java`
- Service tests: Test the 5-step ingestion flow

**When making changes:**
1. Run all tests first to ensure baseline
2. Add tests for new functionality
3. Run tests again to verify
4. Check for any integration issues


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


## Proxy Configuration

If you cannot connect to external sites, use the proxy:
```bash
source set-proxy.sh  # Sets HTTP_PROXY and HTTPS_PROXY to 127.0.0.1:4080
```

## Project Structure & Key Classes

### Source Code Structure
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
│   ├── JdbcSettlementRepository.java (JDBC impl) - save(), markOldVersions(), findPreviousCounterparty()
│   ├── RunningTotalRepository.java (interface)
│   ├── JdbcRunningTotalRepository.java
│   ├── ExchangeRateRepository.java (interface)
│   ├── JdbcExchangeRateRepository.java
│   ├── ActivityRepository.java (interface)
│   └── JdbcActivityRepository.java
├── validation/          # Input validation (1 file)
│   └── SettlementValidator.java - 11 field checks
├── event/               # Event system (2 files)
│   ├── SettlementEvent.java
│   ├── EventPublisher.java
│   └── SettlementEventCodec.java
├── service/             # Business services (3 files)
│   ├── SettlementIngestionService.java  # Main orchestrator - processSettlement(), executeIngestionSteps(), calculateRunningTotal(), generateEvents()
│   ├── ConfigurationService.java (interface)
│   └── impl/
│       └── InMemoryConfigurationService.java
├── handler/             # HTTP handlers (1 file)
│   └── SettlementIngestionHandler.java
├── router/              # HTTP routing (1 file)
│   └── SettlementRouter.java
├── processor/           # Event processors (1 file)
│   └── RunningTotalProcessorVerticle.java - Event consumer
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

### Key Class Responsibilities
- **SettlementIngestionService.java** - Main orchestrator implementing the 5-step ingestion flow
- **JdbcSettlementRepository.java** - Database operations (save, mark old versions, detect counterparty changes)
- **RunningTotalProcessorVerticle.java** - Event consumer that listens on Vert.x event bus
- **SettlementValidator.java** - Input validation with 11 field checks

## Core Architecture Concepts

### The Sequence ID (REF_ID) - Critical Concept

Every settlement gets an **auto-incrementing sequence ID** (`ID` column in SETTLEMENT table) that serves as the foundation for consistency:
- Monotonically increasing primary key from Oracle identity column
- **NOT a version number** - it only increases
- Defines scope: "calculate running totals using all settlements with ID ≤ seqId"
- Used as `REF_ID` in `RUNNING_TOTAL` table
- Guarantees ordering and idempotency

## Workflow Tips

### 1. Understand the 5-Step Ingestion Flow
Before modifying `SettlementIngestionService.java`, understand:
- Step 0: Validation (SettlementValidator)
- Step 1: Save → Get REF_ID
- Step 2: Mark old versions
- Step 3: Detect counterparty changes
- Step 4: Generate events (1 or 2)
- Step 5: Calculate running totals

**Save settlement should in a separate transaction.**
**Mark old version should in a separate transaction.**
**Calculate running totals should in a separate transaction.**
**Can support reprocessing from client, so need to be idempotent.**
**Key**: All 5 steps are executed **synchronously** within the HTTP request. So that it doesn't need extra time on event dispatching.

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
- Events trigger async recalculation when needed

### 4. Common Pitfalls to Avoid
- ❌ Don't store status fields - compute on-demand
- ❌ Don't use incremental updates - always complete recalculation
- ❌ Don't skip version history - audit requirement
- ❌ Don't process events concurrently - single-threaded processor
- ❌ Don't ignore counterparty changes - must trigger dual events

### **Duplicate Handling**: The database has a unique constraint on `(SETTLEMENT_ID, PTS, PROCESSING_ENTITY, SETTLEMENT_VERSION)`. If a duplicate is inserted:
- The database throws a constraint violation exception
- The service catches it and queries for the existing settlement's ID
- Uses that ID as REF_ID for the rest of the flow
- Returns HTTP 200 with the existing sequence ID
- This makes the operation idempotent
- This allows settlement resend from client can trigger all the steps in case of a failure


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


## Database Setup

### Oracle Database
```sql
-- Connect as tvpc user
-- Run schema.sql
@src/main/resources/db/schema.sql
```

## Debugging Tips

### Enable Debug Logging
Edit `src/main/resources/logback.xml`:
```xml
<logger name="com.tvpc" level="DEBUG"/>
```

### Add new logging logic into the code when necessary


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

## Files to Reference

- **Requirements**: `.kiro/specs/payment-limit-monitoring/requirements.md`
- **Tech Design**: `.kiro/specs/payment-limit-monitoring/tech-design.md`

## Vert.x Context for Claude

- **Source code**: Download Vert.x source and docs via Maven to study implementation
- **Official docs**: https://vertx.io/ - comprehensive documentation and guides
- **Pattern reference**: Examine existing code patterns in this project for Vert.x best practices

## Code best practices
- **Prevent redundant `Promis`**: For logic already returns a `Future`, return it directly instead of creating a `Promise` and `complete` it.