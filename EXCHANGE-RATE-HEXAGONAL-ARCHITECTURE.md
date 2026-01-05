# Exchange Rate Feature - Hexagonal Architecture

## Overview
The exchange rate feature follows hexagonal architecture (ports and adapters pattern), ensuring clean separation of concerns and testability.

## Architecture Layers

```
┌─────────────────────────────────────────────────────────────────┐
│                        DOMAIN LAYER                              │
│  ┌────────────────────────────────────────────────────────────┐  │
│  │  ExchangeRate (Value Object)                               │  │
│  │  - currency: String                                        │  │
│  │  - rateToUsd: BigDecimal                                   │  │
│  │  - updateTime: LocalDateTime                               │  │
│  └────────────────────────────────────────────────────────────┘  │
└─────────────────────────────────────────────────────────────────┘

┌─────────────────────────────────────────────────────────────────┐
│                     APPLICATION LAYER                            │
│  ┌────────────────────────────────────────────────────────────┐  │
│  │  INPUT PORT (Use Case Interface)                          │  │
│  │  ExchangeRateRefreshUseCase                               │  │
│  │  - startPeriodicRefresh(): Future<Void>                   │  │
│  │  - stopPeriodicRefresh(): void                            │  │
│  │  - refreshRates(): Future<Void>                           │  │
│  └────────────────────────────────────────────────────────────┘  │
│                              ▲                                   │
│                              │ implements                        │
│  ┌────────────────────────────────────────────────────────────┐  │
│  │  USE CASE IMPLEMENTATION                                   │  │
│  │  ExchangeRateRefreshService                                │  │
│  │  - Orchestrates refresh logic                              │  │
│  │  - Depends only on ports (interfaces)                      │  │
│  │  - No knowledge of adapters                                │  │
│  └────────────────────────────────────────────────────────────┘  │
│                    │                    │                         │
│                    ▼                    ▼                         │
│  ┌──────────────────────────┐  ┌──────────────────────────┐      │
│  │  OUTPUT PORT             │  │  OUTPUT PORT             │      │
│  │  ExchangeRateProvider    │  │  ExchangeRateRepository  │      │
│  │  - fetchCurrentRates()   │  │  - saveRate()            │      │
│  └──────────────────────────┘  │  - getRate()             │      │
│                                 │  - getAllRates()         │      │
│                                 │  - areRatesStale()       │      │
│                                 └──────────────────────────┘      │
└─────────────────────────────────────────────────────────────────┘

┌─────────────────────────────────────────────────────────────────┐
│                       ADAPTER LAYER                              │
│  ┌────────────────────────────────────────────────────────────┐  │
│  │  OUTPUT ADAPTER (HTTP)                                     │  │
│  │  ExchangeRateHttpAdapter implements ExchangeRateProvider   │  │
│  │  - Fetches rates from external system                      │  │
│  │  - Currently returns dummy data                            │  │
│  └────────────────────────────────────────────────────────────┘  │
│                                                                   │
│  ┌────────────────────────────────────────────────────────────┐  │
│  │  OUTPUT ADAPTER (Database)                                 │  │
│  │  JdbcExchangeRatePersistenceAdapter                        │  │
│  │  implements ExchangeRateRepository                         │  │
│  │  - Persists rates to Oracle database                       │  │
│  └────────────────────────────────────────────────────────────┘  │
│                                                                   │
│  ┌────────────────────────────────────────────────────────────┐  │
│  │  INFRASTRUCTURE (Wiring)                                   │  │
│  │  HttpServerVerticle                                        │  │
│  │  - Creates adapters                                        │  │
│  │  - Injects dependencies                                    │  │
│  │  - Wires ports to adapters                                 │  │
│  └────────────────────────────────────────────────────────────┘  │
└─────────────────────────────────────────────────────────────────┘
```

## Component Details

### Domain Layer
**Location**: `src/main/java/com/tvpc/domain/model/`

- **ExchangeRate.java** - Pure domain entity
  - No dependencies on frameworks
  - Immutable value object using Lombok `@Value`
  - Represents a currency exchange rate

### Application Layer
**Location**: `src/main/java/com/tvpc/application/`

#### Input Port (Use Case Interface)
- **ExchangeRateRefreshUseCase.java** (`port/in/`)
  - Defines what the application can do
  - Contract for exchange rate refresh operations
  - Used by infrastructure to invoke use cases

#### Use Case Implementation
- **ExchangeRateRefreshService.java** (`service/`)
  - Implements `ExchangeRateRefreshUseCase`
  - Orchestrates refresh logic
  - Depends only on output ports (interfaces)
  - No knowledge of concrete adapters
  - Manages 12-hour periodic timer

#### Output Ports (Repository Interfaces)
- **ExchangeRateProvider.java** (`port/out/`)
  - Defines contract for fetching rates from external sources
  - `fetchCurrentRates(): Future<Map<String, BigDecimal>>`

- **ExchangeRateRepository.java** (`port/out/`)
  - Defines contract for persistence operations
  - `saveRate(currency, rate): Future<Void>`
  - `getRate(currency): Future<Optional<BigDecimal>>`
  - `getAllRates(): Future<Map<String, BigDecimal>>`
  - `areRatesStale(): Future<Boolean>`

### Adapter Layer
**Location**: `src/main/java/com/tvpc/adapter/out/`

#### HTTP Adapter (Output)
- **ExchangeRateHttpAdapter.java** (`http/`)
  - Implements `ExchangeRateProvider` port
  - Fetches rates from external system
  - Currently returns dummy data
  - Can be replaced without changing application layer

#### Persistence Adapter (Output)
- **JdbcExchangeRatePersistenceAdapter.java** (`persistence/`)
  - Implements `ExchangeRateRepository` port
  - Uses Vert.x JDBC client
  - Oracle-specific SQL (MERGE statements)
  - Can be replaced with different database

#### Infrastructure (Wiring)
- **HttpServerVerticle.java** (`adapter/in/web/`)
  - Creates all adapters
  - Injects dependencies into use cases
  - Wires ports to concrete adapters
  - Manages lifecycle

## Dependency Flow

```
Infrastructure → Application → Domain
     ↓
  Adapters
```

**Key Principle**: Dependencies point inward
- Domain has no dependencies
- Application depends only on domain and ports (interfaces)
- Adapters depend on application ports
- Infrastructure wires everything together

## Wiring Example

```java
// In HttpServerVerticle.initializeServices()

// 1. Create output adapters
ExchangeRateProvider rateProvider = new ExchangeRateHttpAdapter();
ExchangeRateRepository repository = new JdbcExchangeRatePersistenceAdapter(jdbcPool);

// 2. Create use case with port dependencies
ExchangeRateRefreshUseCase useCase = new ExchangeRateRefreshService(
    vertx,
    rateProvider,    // Port, not concrete class
    repository       // Port, not concrete class
);

// 3. Start the use case
useCase.startPeriodicRefresh();
```

## Benefits of This Architecture

### 1. Testability
- Use cases can be tested with mocks
- No need for real database or HTTP calls
- Fast, isolated unit tests

```java
@Mock
private ExchangeRateProvider rateProvider;

@Mock
private ExchangeRateRepository repository;

@Test
void refreshRates_shouldFetchAndSaveRates() {
    // Mock behavior
    when(rateProvider.fetchCurrentRates())
        .thenReturn(Future.succeededFuture(mockRates));
    
    // Test use case in isolation
    service.refreshRates();
    
    // Verify interactions
    verify(rateProvider).fetchCurrentRates();
    verify(repository).saveRate(any(), any());
}
```

### 2. Flexibility
- Easy to swap implementations
- Replace dummy HTTP adapter with real one
- Switch from Oracle to PostgreSQL
- No changes to application layer

### 3. Maintainability
- Clear separation of concerns
- Business logic isolated in application layer
- Technical details in adapters
- Easy to understand and modify

### 4. Independence
- Domain layer has zero dependencies
- Application layer depends only on abstractions
- Can test business logic without infrastructure

## File Organization

```
src/main/java/com/tvpc/
├── domain/model/
│   └── ExchangeRate.java                    # Domain entity
│
├── application/
│   ├── port/
│   │   ├── in/
│   │   │   └── ExchangeRateRefreshUseCase.java    # Input port
│   │   └── out/
│   │       ├── ExchangeRateProvider.java          # Output port
│   │       └── ExchangeRateRepository.java        # Output port
│   └── service/
│       └── ExchangeRateRefreshService.java        # Use case impl
│
└── adapter/
    ├── in/web/
    │   └── HttpServerVerticle.java          # Wiring
    └── out/
        ├── http/
        │   └── ExchangeRateHttpAdapter.java       # HTTP adapter
        └── persistence/
            └── JdbcExchangeRatePersistenceAdapter.java  # DB adapter
```

## Testing Strategy

### Unit Tests (Application Layer)
**File**: `ExchangeRateRefreshServiceTest.java`
- Test use case with mocked ports
- Fast, no external dependencies
- Verify business logic

### Integration Tests (Adapter Layer)
**File**: `ExchangeRateHttpAdapterTest.java`
- Test adapter implementation
- Can use real or embedded resources
- Verify technical integration

### End-to-End Tests
- Test complete flow through all layers
- Verify wiring is correct
- Use test database

## Comparison with Previous Implementation

### Before (Violated Hexagonal Architecture)
```java
// Service directly depended on concrete adapter
public class ExchangeRateRefreshService {
    private final ExchangeRateHttpAdapter httpAdapter;  // ❌ Concrete class
    
    public ExchangeRateRefreshService(
        Vertx vertx,
        ExchangeRateHttpAdapter httpAdapter,  // ❌ Adapter dependency
        ExchangeRateRepository repository
    ) { ... }
}
```

### After (Follows Hexagonal Architecture)
```java
// Service depends only on port (interface)
public class ExchangeRateRefreshService implements ExchangeRateRefreshUseCase {
    private final ExchangeRateProvider rateProvider;  // ✅ Port interface
    
    public ExchangeRateRefreshService(
        Vertx vertx,
        ExchangeRateProvider rateProvider,  // ✅ Port dependency
        ExchangeRateRepository repository
    ) { ... }
}
```

## Key Takeaways

1. **Ports define contracts** - Interfaces in `application/port/`
2. **Adapters implement contracts** - Concrete classes in `adapter/`
3. **Use cases depend on ports** - Not on adapters
4. **Infrastructure wires everything** - HttpServerVerticle
5. **Domain is pure** - No framework dependencies
6. **Tests use mocks** - Fast, isolated unit tests

This architecture ensures the exchange rate feature is maintainable, testable, and flexible for future changes.
