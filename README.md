# StockGuard — Concurrent Inventory Reservation System

[![StockGuard CI](https://github.com/Vikasgabale9/StockGuard/actions/workflows/maven.yml/badge.svg)](https://github.com/Vikasgabale9/StockGuard/actions/workflows/maven.yml)
[![Java](https://img.shields.io/badge/Java-25-orange)](https://www.oracle.com/java/)
[![Spring Boot](https://img.shields.io/badge/Spring%20Boot-4.1.2-brightgreen)](https://spring.io/projects/spring-boot)
[![Database](https://img.shields.io/badge/Database-PostgreSQL-336791)](https://www.postgresql.org/)

**StockGuard is a backend inventory reservation system that prevents overselling under concurrent load and makes retries safe through idempotency keys.**

Built with Java 25, Spring Boot 4, and PostgreSQL. The project demonstrates database-level concurrency control, idempotent API design, integration testing with a real database, and continuous integration.

**Repository:** [Vikasgabale9/StockGuard](https://github.com/Vikasgabale9/StockGuard)

## Table of Contents

- [Problem Statement](#problem-statement)
- [Concurrency Evidence](#concurrency-evidence)
- [Key Features](#key-features)
- [Technology Stack](#technology-stack)
- [Architecture](#architecture)
- [How Reservation Works](#how-reservation-works)
- [API Reference](#api-reference)
- [Getting Started](#getting-started)
- [Configuration](#configuration)
- [Testing](#testing)
- [Continuous Integration](#continuous-integration)
- [Design Decisions](#design-decisions)
- [Known Limitations](#known-limitations)
- [Future Enhancements](#future-enhancements)
- [Author](#author)

## Problem Statement

An inventory item has one unit left. Two clients send a reservation request at the same moment. A naive implementation reads `available_quantity = 1` in both requests, both pass the check, and both write `available_quantity = 0`. The item is now sold twice, and the inventory is wrong.

The same category of bug appears in ticket booking, flight and hotel reservations, flash sales, and any system where a finite resource is claimed concurrently.

StockGuard solves this with three mechanisms working together:

1. **Conditional database update** — the stock check and the decrement happen in a single atomic SQL statement.
2. **Idempotency keys** — retries of the same logical operation reuse the stored outcome instead of reserving again.
3. **Persisted reservation outcomes** — rejected and successful reservations are both recorded, so retries see the same result.

## Concurrency Evidence

The core invariant is:

> **A successful reservation must never cause `available_quantity` to fall below zero.**

This is proven by `InventoryConcurrencyTest`:

- 20 threads, each requesting 10 units, against an item with 100 units in stock.
- All 20 threads are released simultaneously using `CountDownLatch` so the race condition is exercised.
- Assertions: exactly 10 reservations succeed, 10 are rejected with insufficient stock, and `available_quantity` ends at exactly `0`.
- The test runs against a real PostgreSQL database, not a mock.

`IdempotencyConcurrencyTest` additionally proves:

- Two concurrent requests with the same `Idempotency-Key` reserve stock only once.
- Same key with a different payload is rejected.
- A previously rejected reservation remains rejected on retry.

Both tests execute in GitHub Actions CI on every push.

## Key Features

- Create and retrieve inventory records.
- Paginated inventory listing.
- Conditional, atomic stock reservation at the database level.
- Idempotent reservation requests using the `Idempotency-Key` header.
- PostgreSQL advisory transaction locks to serialise duplicate requests.
- Persisted reservation outcomes so retries return the original result.
- Centralized validation and error handling with consistent `ApiResponse<T>` envelopes.
- OpenAPI / Swagger UI documentation.
- Spring Boot Actuator health and info endpoints.
- JUnit 5 + Mockito + real-database integration tests.
- GitHub Actions CI on every push and pull request targeting `main`.

## Technology Stack

| Category | Technology |
|---|---|
| Language | Java 25 |
| Framework | Spring Boot 4.1.2 |
| Database | PostgreSQL (hosted on Neon) |
| Persistence | Spring Data JPA, Hibernate |
| Build tool | Maven (wrapper included) |
| API documentation | OpenAPI 3 / Swagger UI (springdoc) |
| Monitoring | Spring Boot Actuator |
| Testing | JUnit 5, Mockito, Spring Boot Test |
| CI | GitHub Actions |
| Version control | Git, GitHub |

## Architecture

StockGuard is a single Spring Boot application with a layered structure:

```text
                Client
                  |
                  v
         REST API / HTTP Request
                  |
                  v
        +---------------------+
        | InventoryController |
        +---------------------+
                  |
                  v
        +---------------------+
        |  InventoryService   |
        | Business Logic      |
        | @Transactional      |
        +---------------------+
                  |
          +-------+--------+
          |                |
          v                v
  Conditional Stock   Idempotency
      Update          (pg_advisory_xact_lock
                       + unique constraint)
          |                |
          +-------+--------+
                  |
                  v
        +---------------------+
        | Repository Layer    |
        +---------------------+
                  |
                  v
        +---------------------+
        | PostgreSQL (Neon)   |
        | inventory           |
        | reservation         |
        +---------------------+
```

- **Controller layer** — receives HTTP requests, validates input, returns `ApiResponse<T>`.
- **Service layer** — owns the transaction boundary, performs the conditional stock update, coordinates idempotency.
- **Repository layer** — executes atomic SQL and JPA operations.
- **PostgreSQL** — the single source of truth for stock and reservation state.

## How Reservation Works

### Step 1 — Conditional stock update

The stock check and decrement happen in a single statement:

```sql
UPDATE inventory
SET available_quantity = available_quantity - :quantity
WHERE sku = :sku
  AND available_quantity >= :quantity;
```

If zero rows are updated, the reservation is rejected. This statement alone prevents overselling because PostgreSQL serialises the write to the row, and any competing request either sees the updated value or blocks until the first transaction commits.

### Step 2 — Idempotency coordination

Each reservation request carries a unique `Idempotency-Key` header. Inside the transaction, StockGuard:

1. Acquires `pg_advisory_xact_lock` on the hash of the idempotency key. Concurrent requests with the same key serialise here.
2. Looks up an existing reservation for that key.
3. If found and the payload matches, returns the existing reservation without touching stock.
4. If found and the payload differs, rejects with `409 Conflict`.
5. If not found, proceeds with the conditional update and persists the reservation.

The `unique` constraint on `reservation.idempotency_key` is the final backstop: even if two identical requests slip past the advisory lock (for example, during a failover), only one `INSERT` can succeed.

### Idempotency rules

| Same key, same payload | Return the original reservation; do not reserve again |
| Same key, different payload | Reject with `409 Conflict` |
| Different key | Independent reservation |

### Step 3 — Persisted outcomes

Both successful and rejected reservations are stored. When a client retries after a network timeout, the outcome is read from the database rather than re-executed. A previously rejected reservation therefore remains rejected on retry — retries do not silently succeed.

## API Reference

Base URL for local development: `http://localhost:8080`

| Method | Endpoint | Purpose |
|---|---|---|
| `POST` | `/api/v1/inventory` | Create an inventory record |
| `GET` | `/api/v1/inventory/getAll` | List inventory records with pagination |
| `POST` | `/api/v1/inventory/reserve` | Reserve stock using an `Idempotency-Key` |
| `GET` | `/actuator/health` | Application health |
| `GET` | `/actuator/info` | Application metadata |

### Walkthrough with `curl`

```bash
# 1. Create inventory
curl -X POST http://localhost:8080/api/v1/inventory \
  -H "Content-Type: application/json" \
  -d '{"sku":"ITEM-001","initialQuantity":100}'

# 2. Reserve 2 units
curl -X POST http://localhost:8080/api/v1/inventory/reserve \
  -H "Content-Type: application/json" \
  -H "Idempotency-Key: order-1001" \
  -d '{"sku":"ITEM-001","quantity":2}'

# 3. Retry with the same key — same response, no double deduction
curl -X POST http://localhost:8080/api/v1/inventory/reserve \
  -H "Content-Type: application/json" \
  -H "Idempotency-Key: order-1001" \
  -d '{"sku":"ITEM-001","quantity":2}'

# 4. Same key, different quantity — rejected
curl -X POST http://localhost:8080/api/v1/inventory/reserve \
  -H "Content-Type: application/json" \
  -H "Idempotency-Key: order-1001" \
  -d '{"sku":"ITEM-001","quantity":5}'
```

### Sample responses

**Successful reservation — `200 OK`**

```json
{
  "reservationId": "a1b2c3d4-...",
  "sku": "ITEM-001",
  "quantity": 2,
  "status": "CONFIRMED"
}
```

**Insufficient stock — `409 Conflict`**

```json
{
  "error": "INSUFFICIENT_STOCK",
  "message": "Requested 2 units but only 1 available"
}
```

**Conflicting idempotency payload — `409 Conflict`**

```json
{
  "error": "IDEMPOTENCY_CONFLICT",
  "message": "Idempotency-Key 'order-1001' was previously used with a different payload"
}
```

**Validation failure — `400 Bad Request`**

```json
{
  "error": "VALIDATION_FAILED",
  "message": "quantity must be greater than 0"
}
```

### Swagger UI

```text
http://localhost:8080/swagger-ui/index.html
```

## Getting Started

### Prerequisites

- Java 25
- Git
- A PostgreSQL database (Neon free tier is sufficient)

### 1. Clone

```bash
git clone https://github.com/Vikasgabale9/StockGuard.git
cd StockGuard
```

### 2. Configure the database

Set these environment variables:

```text
DB_URL=jdbc:postgresql://your-host/your-database?sslmode=require
DB_USERNAME=your_username
DB_PASSWORD=your_password
```

`.env.example` is provided as a template. **Never commit real credentials.**

### 3. Run

**Windows Command Prompt**

```bat
mvnw.cmd spring-boot:run
```

**Linux / macOS**

```bash
chmod +x mvnw
./mvnw spring-boot:run
```

### 4. Verify

- Health: `http://localhost:8080/actuator/health`
- Swagger UI: `http://localhost:8080/swagger-ui/index.html`

## Configuration

| Environment variable | Purpose |
|---|---|
| `DB_URL` | PostgreSQL JDBC connection URL |
| `DB_USERNAME` | Database username |
| `DB_PASSWORD` | Database password |

HikariCP is tuned for Neon's serverless behaviour (`keepalive-time: 60000`, `max-lifetime: 240000`) so pooled connections are recycled before Neon suspends compute.

## Testing

Run the full suite:

```bash
./mvnw --batch-mode clean verify
```

### Test summary

| Check | Result |
|---|---|
| Local test suite | 18 passed, 0 failures, 0 errors, 0 skipped |
| GitHub Actions CI | Passing |
| `mvn verify` | `BUILD SUCCESS` |

### What each test proves

| Test class | What it verifies |
|---|---|
| `InventoryConcurrencyTest` | 20 threads × 10 units against 100 stock → exactly 10 succeed, stock ends at 0. |
| `InventoryConcurrencyTest` (rollback case) | When reservation persistence fails, the stock decrement is rolled back. |
| `IdempotencyConcurrencyTest` | Two concurrent requests with the same key reserve stock only once. |
| `IdempotencyConcurrencyTest` (conflict case) | Same key with a different payload is rejected. |
| `IdempotencyConcurrencyTest` (rejected retry) | A previously rejected reservation remains rejected on retry. |
| `InventoryValidationTest` | Blank SKU, zero/negative quantity, duplicate SKU, missing idempotency header are all rejected with `400`. |
| `StockguardApplicationTests` | Spring context loads successfully. |

All integration tests run against a real PostgreSQL instance (Neon), not a mock.

## Continuous Integration

Workflow file: `.github/workflows/maven.yml`

The workflow:

1. Checks out the repository.
2. Configures Java 25 (Temurin) with Maven caching.
3. Makes `./mvnw` executable.
4. Injects `DB_URL`, `DB_USERNAME`, `DB_PASSWORD` from GitHub Actions secrets.
5. Runs `./mvnw --batch-mode clean verify`.

Triggers on push and pull request to `main`.

**View CI runs:** [StockGuard GitHub Actions](https://github.com/Vikasgabale9/StockGuard/actions)

## Design Decisions

### Why PostgreSQL?

PostgreSQL provides what this problem needs: atomic conditional updates, uniqueness constraints, and advisory transaction locks. All three are used in the reservation path.

### Why not `synchronized`?

`synchronized` only serialises threads within one JVM. In production, multiple app instances run behind a load balancer. Two threads on two different instances would bypass the JVM lock and still oversell. The database is the only shared authority, so the lock must live in the database.

### Why not rely on `@Transactional` alone?

`@Transactional` gives atomicity but does not prevent two concurrent transactions from both reading `available_quantity = 10` before either writes. The conditional `UPDATE ... WHERE available_quantity >= :quantity` and the advisory lock force serialisation on the contested row.

### Why idempotency keys?

Network timeouts, client retries, and mobile reconnects can deliver the same logical request more than once. Without idempotency, a retry after a successful-but-unacknowledged reservation would deduct stock twice. Storing the outcome and keying on `Idempotency-Key` prevents this.

### Why advisory transaction locks (`pg_advisory_xact_lock`)?

The unique constraint on `idempotency_key` is the correct final guarantee, but without coordination the second request would fail with a raw `DataIntegrityViolationException` after doing work. `pg_advisory_xact_lock` serialises the requests up front so the loser waits, reads the existing reservation, and returns it cleanly. Transaction-scoped locking is compatible with PgBouncer's transaction pooling mode, which is what Neon's pooled endpoint uses.

## Known Limitations

- **At-least-once semantics at the transport layer.** The HTTP endpoint does not guarantee exactly-once execution. Idempotency keys plus database atomicity provide the observable "exactly-once from the client's perspective" behaviour; a client that changes its idempotency key between retries loses this guarantee.
- **Cloud database in CI.** Integration tests run against Neon. Neon's cold-start and network latency can occasionally slow the concurrency tests; the Hikari configuration mitigates stale-connection failures but not network jitter. A Testcontainers-based run would be more deterministic at the cost of not testing against the real environment.
- **No authentication.** All endpoints are public. Production would require JWT or OAuth2 on the reservation and inventory APIs.
- **No load testing.** Throughput and latency under sustained traffic have not been benchmarked, so no RPS claims are made.

## Future Enhancements

Not implemented today — listed so the scope is explicit:

- Apache Kafka for asynchronous reservation events, using the transactional outbox pattern to guarantee event publication consistency with the reservation transaction.
- Dead-letter queue and retry policy for event consumers.
- Load testing with a reproducible benchmark.
- JWT authentication for admin endpoints.
- Testcontainers-based CI to remove dependency on a cloud database.
- Public deployment with a documented live demo.

## Author

**Vikas Vitthalrao Gabale**

- GitHub: [Vikasgabale9](https://github.com/Vikasgabale9)
- Project: [StockGuard](https://github.com/Vikasgabale9/StockGuard)

---

StockGuard is a learning and portfolio project focused on reliable backend engineering, database concurrency, idempotent APIs, and automated testing.