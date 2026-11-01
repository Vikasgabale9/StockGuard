# StockGuard — Concurrent Inventory Reservation System

[![StockGuard CI](https://github.com/Vikasgabale9/stockguard/actions/workflows/maven.yml/badge.svg)](https://github.com/Vikasgabale9/stockguard/actions/workflows/maven.yml)
[![Java](https://img.shields.io/badge/Java-25-orange)](https://www.oracle.com/java/)
[![Spring Boot](https://img.shields.io/badge/Spring%20Boot-4.1.2-brightgreen)](https://spring.io/projects/spring-boot)
[![PostgreSQL](https://img.shields.io/badge/Database-PostgreSQL-336791)](https://www.postgresql.org/)
[![Kafka](https://img.shields.io/badge/Messaging-Kafka-231F20)](https://kafka.apache.org/)
[![Docker](https://img.shields.io/badge/Container-Docker-2496ED)](https://www.docker.com/)

StockGuard is a backend inventory reservation system designed to prevent overselling during concurrent requests and make client retries safe through idempotency keys. It uses PostgreSQL for inventory consistency and Apache Kafka for asynchronous reservation-event handling.

**Repository:** [Vikasgabale9/stockguard](https://github.com/Vikasgabale9/stockguard)

## Table of Contents

- [Problem Statement](#problem-statement)
- [Features](#features)
- [Technology Stack](#technology-stack)
- [Architecture](#architecture)
- [Reservation and Idempotency Flow](#reservation-and-idempotency-flow)
- [Kafka and Transactional Outbox](#kafka-and-transactional-outbox)
- [Retry and Dead-Letter Handling](#retry-and-dead-letter-handling)
- [API Reference](#api-reference)
- [Getting Started](#getting-started)
- [Configuration](#configuration)
- [Testing and Verification](#testing-and-verification)
- [CI and Docker Image Publishing](#ci-and-docker-image-publishing)
- [Design Decisions](#design-decisions)
- [Known Limitations](#known-limitations)
- [Future Improvements](#future-improvements)
- [Author](#author)

## Problem Statement

When multiple clients reserve the last available units at the same time, a naive read-check-update implementation can allow more than one request to observe the same stock and oversell it.

StockGuard addresses this with:

1. **Atomic conditional stock updates** so the stock check and decrement happen in one database statement.
2. **Idempotency keys** so retries of the same logical request do not reserve stock again.
3. **Database transactions and constraints** to keep inventory and reservation state consistent.
4. **A transactional outbox** to record reservation events in the same transaction as the reservation.
5. **Kafka consumers with duplicate-event protection** to handle asynchronous event delivery.

## Features

- Create inventory items and retrieve inventory by SKU.
- Paginated inventory listing.
- Atomic stock reservation using a conditional SQL update.
- Idempotent reservation requests using the `Idempotency-Key` header.
- PostgreSQL transaction-scoped advisory locks to coordinate requests using the same idempotency key.
- Persisted successful and rejected reservation outcomes.
- Transactional outbox for reservation-event publication.
- Kafka producer and consumer integration.
- Consumer-side event deduplication using a PostgreSQL `processed_events` table.
- Retry handling and dead-letter topic publishing for failed main-topic processing.
- Persistence of dead-letter event metadata in PostgreSQL.
- OpenAPI / Swagger UI documentation.
- Spring Boot Actuator health and info endpoints.
- JUnit tests and real PostgreSQL-backed integration testing.
- GitHub Actions build/test workflow and Docker image publishing workflow.

## Technology Stack

| Category | Technology |
|---|---|
| Language | Java 25 |
| Framework | Spring Boot 4.1.2 |
| Database | PostgreSQL (Neon) |
| Persistence | Spring Data JPA, Hibernate |
| Messaging | Apache Kafka / Redpanda Cloud |
| Build | Maven |
| API documentation | OpenAPI 3 / Swagger UI |
| Monitoring | Spring Boot Actuator |
| Testing | JUnit 5, Mockito, Spring Boot Test |
| CI | GitHub Actions |
| Containerization | Docker, multi-stage build |
| Container registry | GitHub Container Registry (GHCR) |

## Architecture

StockGuard is a layered Spring Boot application. PostgreSQL remains the source of truth for inventory and reservation state. Kafka is used for asynchronous event delivery.

```text
                         Client
                           |
                           v
                    Inventory REST API
                           |
                           v
                    Inventory Service
                     @Transactional
                           |
             +-------------+-------------+
             |                           |
             v                           v
      Inventory update             Reservation record
      (conditional SQL)             (idempotency key)
             |                           |
             +-------------+-------------+
                           |
                           v
                      PostgreSQL
                           |
              Reservation + Outbox row
                committed atomically
                           |
                           v
                    Outbox Publisher
                           |
                           v
                    Kafka topic:
                  inventory.reserved
                           |
                           v
                  Inventory Event Consumer
                           |
                           v
                 processed_events table
                  (duplicate protection)


       Failed main-topic processing
                    |
                    v
             Retry / Error Handler
                    |
                    v
        Kafka topic: inventory.reserved-dlt
                    |
                    v
              DLT Consumer
                    |
                    v
          dead_letter_events table
```

### Main components

- **Controller layer:** validates HTTP input and exposes inventory endpoints.
- **Service layer:** owns reservation transactions, idempotency coordination, and business rules.
- **Repository layer:** performs JPA operations and database-specific atomic SQL.
- **Outbox publisher:** polls pending outbox records, publishes them to Kafka, and updates publication status after broker acknowledgement.
- **Main Kafka consumer:** records processed event IDs to prevent the same event from being processed more than once by the consumer's database operation.
- **DLT consumer:** reads failed messages from the dead-letter topic and stores their payload and failure metadata.

## Reservation and Idempotency Flow

### 1. Atomic stock update

The stock check and decrement are performed together:

```sql
UPDATE inventory
SET available_quantity = available_quantity - :quantity
WHERE sku = :sku
  AND available_quantity >= :quantity;
```

If no row is updated, the requested quantity cannot be reserved. This prevents the available quantity from becoming negative through competing reservation requests.

### 2. Idempotency coordination

Every reservation request must include an `Idempotency-Key` header.

The reservation flow:

1. Acquires a PostgreSQL transaction-scoped advisory lock for the idempotency key.
2. Checks whether a reservation already exists for that key.
3. If the key exists with the same request data, returns the stored reservation.
4. If the key exists with different request data, rejects the request.
5. If the key is new, attempts the conditional stock update and stores the outcome.

A unique database constraint on the idempotency key provides an additional safeguard.

| Request | Expected behavior |
|---|---|
| Same key, same payload | Return the original reservation; do not deduct stock again |
| Same key, different payload | Reject the request |
| Different key | Process as a separate reservation |

Both successful and rejected reservation outcomes are persisted. Therefore, retrying a previously rejected request with the same key returns the stored rejection rather than silently trying again after stock changes.

## Kafka and Transactional Outbox

The reservation transaction writes both the reservation result and an outbox event to PostgreSQL. The outbox record is committed in the same database transaction as the reservation.

A scheduled outbox publisher:

1. Finds pending outbox records.
2. Publishes each event to the `inventory.reserved` Kafka topic.
3. Uses the event's aggregate ID as the Kafka message key.
4. Waits for the Kafka send result.
5. Marks the outbox record as published after successful acknowledgement.
6. Records a failure on the outbox record when publication fails, allowing a later scheduled poll to try again.

This avoids the dual-write problem where a database transaction succeeds but event publication fails independently.

### Consumer idempotency

The main consumer uses the event ID as a unique key in the PostgreSQL `processed_events` table. It inserts the event ID only if it has not already been recorded.

If the same event is delivered again, the insert does not create another row and the consumer logs that the duplicate event was ignored.

**Scope note:** In the current implementation, the inventory quantity is changed by the synchronous reservation API. The Kafka consumer currently demonstrates event consumption and duplicate-event persistence/logging; it does not perform a second inventory deduction or a separate substantial downstream business operation.

### Delivery semantics

Kafka delivery and outbox publication should be treated as **at-least-once**, not exactly-once. A publisher can send an event and fail before recording the published status, which can result in another publication. Consumer-side event-ID deduplication protects the database operation that records processed events; it does not make the entire distributed system exactly-once.

## Retry and Dead-Letter Handling

The main Kafka listener uses a `DefaultErrorHandler` with a fixed backoff:

- Initial processing attempt: 1
- Retries: 2
- Total attempts before recovery: up to 3

After retries are exhausted, `DeadLetterPublishingRecoverer` publishes the failed record to `inventory.reserved-dlt`, preserving the source partition number.

The DLT consumer reads the failed message and stores details such as:

- Event ID
- Original topic
- Message key
- DLT partition and offset
- Original payload
- Exception class and message
- Failure status and timestamp

The DLT listener uses a container-stopping error handler if persistence of a DLT record fails. This prevents a persistence failure from endlessly republishing the same record to the DLT. It also means the listener container requires operational attention and restart/recovery after the underlying database issue is resolved.

## API Reference

Local base URL: `http://localhost:8080`

| Method | Endpoint | Purpose |
|---|---|---|
| `POST` | `/api/v1/inventory/create` | Create an inventory item |
| `GET` | `/api/v1/inventory/getAll` | Get a paginated inventory list |
| `GET` | `/api/v1/inventory/sku/{sku}` | Get inventory by SKU |
| `POST` | `/api/v1/inventory/reserve` | Reserve inventory using an idempotency key |
| `GET` | `/actuator/health` | Application health |
| `GET` | `/actuator/info` | Application information |

The inventory listing accepts `page` and `size` query parameters. The default values are `page=0` and `size=10`; the maximum page size is 100.

### Example requests

#### 1. Create inventory

```bash
curl -X POST http://localhost:8080/api/v1/inventory/create \
  -H "Content-Type: application/json" \
  -d '{"sku":"ITEM-001","availableQuantity":100}'
```

The request field is `availableQuantity` (not `initialQuantity`).

#### 2. Get inventory by SKU

```bash
curl http://localhost:8080/api/v1/inventory/sku/ITEM-001
```

#### 3. Reserve stock

```bash
curl -X POST http://localhost:8080/api/v1/inventory/reserve \
  -H "Content-Type: application/json" \
  -H "Idempotency-Key: order-1001" \
  -d '{"sku":"ITEM-001","quantity":2}'
```

#### 4. Retry the same reservation

Send the same request again with the same `Idempotency-Key`. Stock should not be deducted a second time.

#### 5. Try a conflicting payload

Reuse `order-1001` with a different quantity. The API rejects reuse of an idempotency key with different request data.

### Response behavior

The API wraps successful responses in its `ApiResponse<T>` response structure.

Observed behavior during manual API testing:

- Inventory creation returns `201 Created`.
- Inventory retrieval returns `200 OK`.
- Reservation processing returns `200 OK`, including persisted `SUCCESS` and `REJECTED` reservation outcomes.
- Missing idempotency headers and request validation errors return `400 Bad Request`.
- Reusing an idempotency key with different request data returned `400 Bad Request` in the tested API flow.

Do not assume every business rejection is an HTTP error: insufficient stock and unknown SKU are represented as persisted rejected reservation outcomes in the current implementation.

### Swagger UI

```text
http://localhost:8080/swagger-ui/index.html
```

## Getting Started

### Prerequisites

- Java 25
- Git
- PostgreSQL database
- Kafka-compatible broker (for Kafka/outbox functionality)
- Maven wrapper (included in the repository)

### 1. Clone the repository

```bash
git clone https://github.com/Vikasgabale9/stockguard.git
cd stockguard
```

### 2. Configure environment variables

Configure the database:

```text
DB_URL=jdbc:postgresql://<host>:5432/<database>?sslmode=require
DB_USERNAME=<database-username>
DB_PASSWORD=<database-password>
```

Configure Kafka:

```text
KAFKA_BOOTSTRAP_SERVERS=<broker-host>:9092
KAFKA_USERNAME=<kafka-username>
KAFKA_PASSWORD=<kafka-password>
```

Use your own PostgreSQL and Kafka-compatible broker credentials. Do not commit real credentials to Git.

The repository includes an `.env.example` template. Environment-variable loading depends on how you launch the application; exporting variables in your shell or configuring your IDE/hosting environment is also valid.

### 3. Database schema

The repository currently contains the Flyway migration `V2__create_outbox_events.sql` for the outbox table. The base inventory, reservation, processed-event, and dead-letter tables have been provisioned in the development database separately.

For a fresh database, ensure the required base tables and constraints exist before starting the application. Do not assume that the current repository contains a complete set of migrations for creating every table.

The production profile uses Hibernate schema validation; it does not automatically create missing production tables.

### 4. Run the application

**Windows Command Prompt:**

```bat
mvnw.cmd spring-boot:run
```

**Linux / macOS:**

```bash
chmod +x mvnw
./mvnw spring-boot:run
```

Ensure the required database and Kafka environment variables are configured before startup.

### 5. Verify

- Health endpoint: `http://localhost:8080/actuator/health`
- Swagger UI: `http://localhost:8080/swagger-ui/index.html`

## Configuration

| Environment variable | Purpose |
|---|---|
| `DB_URL` | PostgreSQL JDBC URL |
| `DB_USERNAME` | PostgreSQL username |
| `DB_PASSWORD` | PostgreSQL password |
| `KAFKA_BOOTSTRAP_SERVERS` | Kafka bootstrap server address |
| `KAFKA_USERNAME` | Kafka SASL username |
| `KAFKA_PASSWORD` | Kafka SASL password |

The production profile configures database connection-pool settings and validates the database schema at startup. Kafka uses SASL over SSL with SCRAM-SHA-256 in the configured hosted-broker setup.

## Testing and Verification

Run the Maven verification lifecycle:

**Windows:**

```bat
mvnw.cmd --batch-mode clean verify
```

**Linux / macOS:**

```bash
./mvnw --batch-mode clean verify
```

The project includes JUnit tests for inventory behavior, concurrency, idempotency, validation, and application startup. The test suite was reported as passing during development. Exact test counts are intentionally not stated here because a current machine-readable test report was not included with this README update.

### Manual verification performed

The following behaviors were exercised during development:

- Inventory creation and retrieval.
- Successful reservation and stock decrement.
- Repeating the same idempotency key and payload without a second stock decrement.
- Rejecting reuse of an idempotency key with different request data.
- Insufficient-stock and unknown-SKU rejection.
- Missing idempotency header and invalid quantity validation.
- Duplicate SKU rejection.
- A 15-request concurrent reservation run against 100 units: 10 requests succeeded, 5 were rejected, and final available quantity was 0.
- Outbox event publication and consumer processing.
- Republishing an event with the same event ID and observing the consumer ignore it as a duplicate.
- A failed main-topic event being routed to the dead-letter topic and consumed by the DLT consumer.

The 15-request run is one manual concurrency experiment, not a sustained load test or proof that every possible concurrency workload is safe. The duplicate-event test demonstrates the tested event-ID deduplication path, not global exactly-once processing.

## CI and Docker Image Publishing

Workflow: `.github/workflows/maven.yml`

The GitHub Actions workflow is configured to:

1. Run on pushes and pull requests targeting `main`.
2. Check out the repository.
3. Set up Java 25 (Temurin) and Maven dependency caching.
4. Run `./mvnw --batch-mode clean verify`, using database credentials stored as GitHub Actions secrets.
5. After the build/test job succeeds on a push to `main`, build a Docker image and publish it to GitHub Container Registry.

The configured image tag is:

```text
ghcr.io/vikasgabale9/stockguard:latest
```

The Docker build uses the repository-root `dockerfile` path. The filename is lowercase in the current repository; Linux build runners are case-sensitive.

**Important:** This documents the workflow configuration. A green Actions run and a successfully published GHCR package must be verified in GitHub before claiming that the pipeline has completed successfully.

- [GitHub Actions runs](https://github.com/Vikasgabale9/stockguard/actions)
- [GitHub Packages](https://github.com/Vikasgabale9?tab=packages)

### Docker image design

The Docker build uses two stages:

- **Build stage:** Maven with Eclipse Temurin 25 compiles and packages the application.
- **Runtime stage:** Eclipse Temurin 25 JRE Alpine runs the packaged JAR as a non-root user.

The container uses the `prod` Spring profile and reads the port from the `PORT` environment variable, defaulting to `8080`.

The JVM memory flags are deployment tuning choices, not a guarantee that the process will stay within a particular memory limit. Actual memory usage must be monitored in the target hosting environment.

## Design Decisions

### Why PostgreSQL conditional updates?

A conditional update makes the stock check and decrement one atomic database operation. This avoids relying on an application-level read followed by a separate write.

### Why not Java `synchronized`?

A Java monitor coordinates threads only inside one JVM. It cannot coordinate multiple application instances. Database-level concurrency control works across application instances sharing the same database.

### Why idempotency keys?

Clients may retry after timeouts even when the server has already committed the reservation. The idempotency key lets the server return the stored outcome instead of executing the same logical reservation again.

### Why the transactional outbox?

Writing to PostgreSQL and publishing to Kafka are two separate operations. The outbox stores the event in the same database transaction as the reservation; a separate publisher sends it to Kafka after commit.

### Why consumer-side deduplication?

Kafka messages can be delivered more than once. A unique event ID in the database allows the consumer to detect a previously recorded event and avoid repeating that database operation.

## Known Limitations

- **At-least-once event delivery:** the outbox publisher can publish a record again if it crashes after broker acknowledgement but before marking the row as published. Consumer deduplication handles the demonstrated event-recording path; this is not end-to-end exactly-once processing.
- **Multiple publisher instances:** the current outbox polling design does not establish a distributed claim/lease mechanism for coordinating multiple application instances. Multiple instances may publish the same pending row.
- **Outbox retry policy:** failed outbox records are eligible for later polling; there is no documented exponential backoff or maximum-attempt policy in the current implementation.
- **DLT persistence recovery:** if saving a DLT record fails, the DLT listener container stops. It requires operational recovery; automatic restart is not implemented.
- **Limited downstream consumer work:** the main Kafka consumer currently demonstrates event consumption and duplicate-event recording/logging. Inventory is reserved synchronously by the REST API.
- **Database bootstrap:** the repository currently includes the outbox migration, but not a complete migration history for all base tables.
- **No API authentication:** inventory endpoints do not currently require JWT/OAuth2 authentication.
- **No sustained load benchmark:** no reproducible throughput or latency benchmark has been completed.
- **Cloud-dependent integration testing:** development integration tests use a hosted PostgreSQL database; a local Testcontainers-based test environment is not currently documented.
- **CI/CD verification:** workflow configuration alone does not prove that a GitHub Actions run or GHCR publication succeeded.

## Future Improvements

- Add complete versioned database migrations for every table and constraint.
- Add a robust outbox claiming strategy for multiple application instances.
- Add bounded retry/backoff and operational monitoring for outbox failures.
- Add DLT replay and recovery tooling.
- Add meaningful downstream business processing for reservation events.
- Add JWT-based authentication and authorization.
- Add Testcontainers-based integration testing.
- Add repeatable load tests and publish measured results.
- Add image version tags and an automated deployment stage after validating the GHCR workflow.

## Author

**Vikas Vitthalrao Gabale**

- GitHub: [Vikasgabale9](https://github.com/Vikasgabale9)
- Project: [StockGuard](https://github.com/Vikasgabale9/stockguard)

---

StockGuard is a portfolio project focused on database concurrency, idempotent API design, asynchronous event processing, and practical backend reliability patterns.
