# CLAUDE.md

## Your Role for this project
- World's most top 10 software engineer, architect, tester
- Supper strong at communication to clarify, summarizing and explaining
- Super expert in Java, Vert.x, Oracle
- Always treat code quality very high, always write try unit test to cover most of the code
- Use the best practices and design principles to write clean, efficient, readable, testable, modularized code

## Project Overview

**Payment Limit Monitoring System** - A financial risk management application built with Vert.x/Java that tracks settlement flows from trading systems, calculates aggregated exposure by counterparty and value date, and flags transactions exceeding predefined limits.

**Status**: Phase 1 90% complete - Settlement Ingestion Flow fully implemented. This is a working implementation, needs fine-tuning.
**TO DO**:
- [ ] ingestion flow save settlement to return auto generated REF_ID
- [ ] and fix the duplicate settlement able to be saved issue
- [x] update project structure to use hexagonal architecture

## Technology Stack

- **Framework**: Vert.x 4.5.23.
- **Language**: Java 21
- **Database**: Oracle Database (production, development, testing)
- **Build Tool**: Maven
- **Testing**: JUnit 5, Mockito, Vert.x Unit
- **Messaging**: Cannot use Kafka, Rabbit MQ etc. because of some architecture limitations
- **Architecture**: Hexagonal Architecture with Event-Driven design

## Critical
- **Performance**: Always try to find the best ways to optimize performance, however need to keep the code clean and easy to read and easy to maintain.

## Code Style Guidelines

- **Java 21**: Use modern Java features where appropriate
- **Vert.x patterns**: Use async handlers, avoid blocking operations
- **Hexagonal architecture**: Keep domain pure, dependencies point inward
- **Error handling**: Use proper exception handling, return meaningful error messages
- **Logging**: Use SLF4J with Logback, follow existing log patterns
- **Naming**: Follow existing naming conventions in the codebase
- **Comments**: Add comments for complex business logic
- **Lombok**: Use lombok annotations where appropriate to reduce boilerplate code. Use @Slf4j for logger
- **Unit tests**: Write unit test for every class, except for some orchestration classes which are difficult to test by unit test case 


## Files to Reference

- **Requirements**: `.kiro/specs/payment-limit-monitoring/requirements.md`
- **Tech Design**: `.kiro/specs/payment-limit-monitoring/tech-design.md`

## Vert.x Context for Claude

- **Source code**: Download Vert.x source and docs via Maven to study implementation
- **Official docs**: https://vertx.io/ - comprehensive documentation and guides
- **Pattern reference**: Examine existing code patterns in this project for Vert.x best practices

## Coding best practices
- **Prevent redundant `Promis`**: For logic already returns a `Future`, return it directly instead of creating a `Promise` and `complete` it.
- **When making changes:**
1. Run all tests first to ensure baseline
2. Add tests for new functionality
3. Run tests again to verify
4. Check for any integration issues
5. Always run tests before committing
6. When debugging, add new log messages when necessary to improve the efficiency

## Tools for claude to use
- **WebFetch**: this tool always has error, so use curl instead
- **WebSearch**: this tool always return nothing, so use chrome-devtool-mcp to search in cn.bing.com instead

## Proxy Configuration
If you cannot connect to external sites, apply the HTTP proxy (127.0.0.1:4080) to connect.

## Common Bash Commands

```bash
# Build and test
mvn clean compile
mvn test
mvn clean package

# Run application
mvn exec:java -Dexec.mainClass="com.tvpc.Main"

# Run specific tests (examples)
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

## Stop the app
- when the app is started, it put the PID into app.pid file, can kill the app by using the pid

## Key Files to Reference (Hexagonal Architecture)

**Application Layer (Use Cases):**
- `src/main/java/com/tvpc/application/service/SettlementIngestionService.java` - Main orchestrator
- `src/main/java/com/tvpc/application/service/SettlementValidator.java` - Input validation
- `src/main/java/com/tvpc/application/port/in/SettlementIngestionUseCase.java` - Input port interface

**Domain Layer:**
- `src/main/java/com/tvpc/domain/model/Settlement.java` - Core entity
- `src/main/java/com/tvpc/domain/event/SettlementEvent.java` - Domain events

**Adapter Layer:**
- `src/main/java/com/tvpc/adapter/in/web/SettlementIngestionHandler.java` - HTTP handler
- `src/main/java/com/tvpc/adapter/out/persistence/JdbcSettlementPersistenceAdapter.java` - DB operations

**Infrastructure:**
- `src/main/java/com/tvpc/infrastructure/Main.java` - Application entry point
- `src/main/java/com/tvpc/infrastructure/config/HttpServerVerticle.java` - HTTP server setup

**Configuration:**
- `src/main/resources/application.yml` - App config (HTTP port, DB connection)
- `src/main/resources/db/schema.sql` - Oracle DDL for 6 tables
- `src/main/resources/logback.xml` - Logging config


## Project Structure (Hexagonal Architecture)

```
src/main/java/com/tvpc/
├── application/                    # APPLICATION LAYER (Use Cases)
│   ├── port/
│   │   ├── in/                    # Input Ports (Use Case Interfaces)
│   │   │   └── SettlementIngestionUseCase.java
│   │   └── out/                   # Output Ports (Repository Interfaces)
│   │       ├── SettlementPersistencePort.java
│   │       ├── RunningTotalPersistencePort.java
│   │       ├── ActivityPersistencePort.java
│   │       ├── ExchangeRatePersistencePort.java
│   │       └── ConfigurationPort.java
│   └── service/                   # Use Case Implementations
│       ├── SettlementIngestionService.java
│       ├── SettlementValidator.java
│       └── ValidationResult.java
│
├── domain/                        # DOMAIN LAYER (Pure Business Logic)
│   ├── model/                     # Domain Entities
│   │   ├── Settlement.java
│   │   ├── BusinessStatus.java
│   │   ├── SettlementDirection.java
│   │   └── SettlementType.java
│   └── event/                     # Domain Events
│       └── SettlementEvent.java
│
├── adapter/                       # ADAPTER LAYER
│   ├── in/
│   │   └── web/                   # Input Adapters (HTTP)
│   │       ├── SettlementIngestionHandler.java
│   │       ├── SettlementRouter.java
│   │       └── dto/
│   │           ├── SettlementRequest.java
│   │           └── SettlementResponse.java
│   └── out/
│       └── persistence/           # Output Adapters (Database)
│           ├── JdbcSettlementPersistenceAdapter.java
│           ├── JdbcRunningTotalPersistenceAdapter.java
│           ├── JdbcActivityPersistenceAdapter.java
│           ├── JdbcExchangeRatePersistenceAdapter.java
│           └── InMemoryConfigurationAdapter.java
│
└── infrastructure/                # INFRASTRUCTURE LAYER
    ├── Main.java                  # Application entry point
    └── config/
        ├── HttpServerVerticle.java    # Wires up hexagonal architecture
        └── SettlementEventCodec.java  # Event bus codec

src/main/resources/
├── db/schema.sql        # Oracle DDL for 6 tables
├── application.yml      # App configuration
└── logback.xml          # Logging configuration
```

### Hexagonal Architecture Layers

**Domain Layer** - Pure business logic, no external dependencies
- Entities: Settlement, BusinessStatus, SettlementDirection, SettlementType
- Events: SettlementEvent

**Application Layer** - Use cases and orchestration
- Input Ports: Define what the application can do (SettlementIngestionUseCase)
- Output Ports: Define what the application needs (persistence interfaces)
- Services: Implement use cases using ports

**Adapter Layer** - Connect to external systems
- Input Adapters: HTTP handlers that call input ports
- Output Adapters: JDBC implementations of output ports

**Infrastructure Layer** - Framework and configuration
- Main: Application bootstrap
- HttpServerVerticle: Wires up all components

### Key Class Responsibilities
- **SettlementIngestionService** - Implements 5-step ingestion flow via SettlementIngestionUseCase
- **JdbcSettlementPersistenceAdapter** - Implements SettlementPersistencePort for database operations
- **SettlementIngestionHandler** - HTTP adapter that converts requests to use case commands
- **HttpServerVerticle** - Dependency injection and wiring of all components


## Database Schema (6 Tables)

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


## Common Pitfalls to Avoid

❌ **Don't store status fields** - Compute on-demand
❌ **Don't use incremental updates** - Always complete recalculation
❌ **Don't skip version history** - Audit requirement
❌ **Don't allow same user to request and authorize** - Security violation
❌ **Don't recalculate historical data on rate changes** - Rates fixed at processing time
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


## Database Setup

### Oracle Database
```sql
-- Connect as tvpc user
-- Run schema.sql
@src/main/resources/db/schema.sql
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
