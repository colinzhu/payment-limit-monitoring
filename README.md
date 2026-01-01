# Payment Limit Monitoring System

A financial risk management application that tracks settlement flows from trading systems, calculates aggregated exposure by counterparty and value date, and flags transactions exceeding predefined limits.

## Status

**Implementation**: Settlement Ingestion Flow (Phase 1) ✅

This is the first phase of implementation, covering Requirement 1 from the specifications.

## Technology Stack

- **Framework**: Vert.x 4.5.8
- **Language**: Java 21
- **Database**: H2 (development), Oracle Database (production)
- **Build Tool**: Maven
- **Testing**: JUnit 5, Mockito

## Architecture

The system follows **Hexagonal Architecture** and **Event-Driven** design principles:

### Core Components

1. **Domain Layer**: Entities and enums
   - `Settlement`, `SettlementDirection`, `SettlementType`, `BusinessStatus`

2. **Repository Layer**: Database access
   - `SettlementRepository`, `RunningTotalRepository`, `ExchangeRateRepository`, `ActivityRepository`

3. **Service Layer**: Business logic
   - `SettlementIngestionService` - 5-step ingestion flow
   - `ConfigurationService` - Rules and limits management

4. **API Layer**: HTTP endpoints
   - `HttpServerVerticle` - HTTP server
   - `SettlementIngestionHandler` - Request handler

5. **Event System**: Asynchronous processing
   - `SettlementEvent` - Event data structure
   - `EventPublisher` - Event bus publisher
   - `RunningTotalProcessorVerticle` - Single-threaded event processor

## Settlement Ingestion Flow (5 Steps)

The implementation follows the technical design exactly:

```
Step 0: Validate
  ↓
Step 1: Save Settlement → Get SEQ_ID
  ↓
Step 2: Mark Old Versions (IS_OLD = true)
  ↓
Step 3: Detect Counterparty Changes
  ↓
Step 4: Generate Events (1 or 2 events)
  ↓
Step 5: Calculate Running Total (complete recalculation)
```

### Key Features

- **Complete Recalculation**: Always recalculates full group totals
- **Version Management**: Maintains latest version + historical versions
- **Counterparty Change Detection**: Triggers dual events when counterparty changes
- **Transaction Safety**: All steps in single database transaction
- **Concurrency Control**: Single-threaded processor eliminates race conditions

## Project Structure

```
src/main/java/com/tvpc/
├── domain/              # Entities and enums
│   ├── Settlement.java
│   ├── SettlementDirection.java
│   ├── SettlementType.java
│   └── BusinessStatus.java
├── dto/                 # API data transfer objects
│   ├── SettlementRequest.java
│   ├── SettlementResponse.java
│   └── ValidationResult.java
├── repository/          # Database access layer
│   ├── SettlementRepository.java
│   ├── RunningTotalRepository.java
│   ├── ExchangeRateRepository.java
│   ├── ActivityRepository.java
│   └── impl/            # JDBC implementations
│       ├── JdbcSettlementRepository.java
│       ├── JdbcRunningTotalRepository.java
│       ├── JdbcExchangeRateRepository.java
│       └── JdbcActivityRepository.java
├── validation/          # Input validation
│   └── SettlementValidator.java
├── event/               # Event system
│   ├── SettlementEvent.java
│   └── EventPublisher.java
├── service/             # Business services
│   ├── SettlementIngestionService.java
│   ├── ConfigurationService.java
│   └── impl/
│       └── InMemoryConfigurationService.java
├── handler/             # HTTP handlers
│   └── SettlementIngestionHandler.java
├── router/              # HTTP routing
│   └── SettlementRouter.java
├── processor/           # Event processors
│   └── RunningTotalProcessorVerticle.java
├── HttpServerVerticle.java
└── Main.java            # Application entry point

src/main/resources/
├── db/schema.sql        # Oracle DDL
├── application.yml      # Configuration
└── logback.xml          # Logging

pom.xml                  # Maven build
```

## Database Schema

### Core Tables

1. **SETTLEMENT** - Latest versions only
   - `ID` (auto-increment, becomes REF_ID)
   - `SETTLEMENT_ID`, `SETTLEMENT_VERSION`
   - Group fields: `PTS`, `PROCESSING_ENTITY`, `COUNTERPARTY_ID`, `VALUE_DATE`
   - Transaction fields: `CURRENCY`, `AMOUNT`, `DIRECTION`, `BUSINESS_STATUS`, `GROSS_NET`
   - Version flag: `IS_OLD`

2. **SETTLEMENT_HIST** - Archived old versions
3. **EXCHANGE_RATE** - Currency conversion rates
4. **RUNNING_TOTAL** - Aggregated exposure per group
5. **ACTIVITIES** - Audit trail for approvals
6. **NOTIFICATION_QUEUE** - External notifications with retry

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

## Building and Running

### Prerequisites
- Java 21
- Maven 3.8+

### Build
```bash
mvn clean compile
```

### Run
```bash
mvn exec:java -Dexec.mainClass="com.tvpc.Main"
```

### Test
```bash
mvn test
```

## Configuration

Edit `src/main/resources/application.yml`:

```yaml
http:
  port: 8080

database:
  # Development (H2)
  url: jdbc:h2:mem:test;DB_CLOSE_DELAY=-1
  driver_class: org.h2.Driver
  user: sa
  password: ""

  # Production (Oracle)
  # url: jdbc:oracle:thin:@//localhost:1521/XE
  # driver_class: oracle.jdbc.OracleDriver
  # user: tvpc_user
  # password: tvpc_password

app:
  mvp_mode: true  # Fixed 500M limit
```

## Validation Rules

The system validates:

1. **Required Fields**: All 11 fields must be present
2. **Currency**: Valid ISO 4217 code (3 characters)
3. **Amount**: Non-negative, max 2 decimal places
4. **Date**: ISO format (YYYY-MM-DD), not in past
5. **Direction**: PAY or RECEIVE
6. **Type**: GROSS or NET
7. **Status**: PENDING, INVALID, VERIFIED, or CANCELLED
8. **Version**: Positive timestamp

## Performance Targets

| Metric | Target | Status |
|--------|--------|--------|
| Settlement ingestion | 200K / 30 min | Design ✅ |
| Status availability | < 30 sec | Design ✅ |
| Running total calc | < 10 sec | Design ✅ |
| API response | < 3 sec | Design ✅ |

## Critical Design Principles

✅ **Complete Recalculation** - Always recalculate full group totals
✅ **On-Demand Status** - Status computed at query time
✅ **Sequence ID Ordering** - Monotonic ID for version control
✅ **Single-Threaded Processor** - Eliminates race conditions
✅ **Event-Driven** - Handles high volume with consistency
✅ **Atomic Operations** - All critical operations in transactions

## Next Phases

### Phase 2: Status Calculation & Approval Workflow
- Query-time status calculation
- Two-step approval workflow (REQUEST RELEASE → AUTHORISE)
- User segregation enforcement

### Phase 3: Search & Export
- Multi-criteria search interface
- Excel export functionality
- Settlement group display

### Phase 4: External APIs
- Query by Settlement ID endpoint
- Manual recalculation trigger
- Notification system with retry

### Phase 5: Configuration Management
- Exchange rate fetching
- Rule system integration
- Counterparty-specific limits

### Phase 6: Performance & Monitoring
- Performance testing
- Distributed processing safeguards
- Audit and compliance features

## Implementation Checklist

- [x] Maven project structure
- [x] Database schema
- [x] Domain entities
- [x] DTOs
- [x] Repository layer
- [x] Validation logic
- [x] Event system
- [x] SettlementIngestionService (5-step flow)
- [x] HTTP server and handlers
- [x] Event processor verticle
- [x] Main application class
- [x] Configuration files
- [ ] Unit tests
- [ ] Integration tests

## Common Pitfalls Avoided

❌ **Don't store status fields** - Compute on-demand
❌ **Don't use incremental updates** - Always complete recalculation
❌ **Don't skip version history** - Audit requirement
❌ **Don't allow same user to request and authorize** - Security violation
❌ **Don't recalculate historical data on rate changes** - Rates fixed at processing time
❌ **Don't process events concurrently** - Use single-threaded processor
❌ **Don't ignore counterparty changes** - Must trigger dual events

## License

This is a design specification and implementation for the Payment Limit Monitoring System.
