# StockGuard – Concurrent Inventory Reservation System

StockGuard is a backend application built with Java and Spring Boot to manage inventory and reserve stock safely under concurrent requests.

The project focuses on preventing inventory overselling, handling duplicate reservation requests through idempotency, and maintaining reliable reservation records using PostgreSQL.

## Key Features

- Inventory creation and retrieval
- Paginated inventory listing
- Stock reservation through REST APIs
- Atomic stock updates to prevent overselling
- Idempotent reservation requests using an idempotency key
- PostgreSQL advisory transaction locks for coordinating duplicate requests
- Reservation status tracking for successful and rejected requests
- Request validation and centralized exception handling
- Standardized API responses
- OpenAPI/Swagger API documentation
- Spring Boot Actuator health endpoint

## Tech Stack

- **Language:** Java 25
- **Framework:** Spring Boot 4.1.2
- **Database:** PostgreSQL
- **Database hosting:** Neon
- **Data access:** Spring Data JPA / Hibernate
- **Build tool:** Maven
- **API documentation:** OpenAPI / Swagger UI
- **Monitoring:** Spring Boot Actuator
- **Testing:** JUnit and Mockito
- **Version control:** Git and GitHub

## System Architecture

The application follows a layered backend architecture:

```text
Client
  |
  v
InventoryController
  |
  v
InventoryService
  |
  v
Repositories
  |
  v
PostgreSQL (Neon)
```

- **Controller layer:** Receives HTTP requests and returns API responses.
- **Service layer:** Implements inventory and reservation business logic.
- **Repository layer:** Executes database operations.
- **PostgreSQL:** Stores inventory and reservation data.

## Database Design

### Inventory

Stores inventory information, including the stock-keeping unit (SKU) and available quantity.

### Reservation

Stores reservation information, including the idempotency key, SKU, requested quantity, reservation status, and response details.

The database enforces uniqueness for reservation idempotency keys so that a key cannot create multiple independent reservations.

## API Documentation

The application exposes REST endpoints for inventory management and stock reservation.

| Method | Endpoint | Purpose |
|---|---|---|
| POST | `/api/v1/inventory` | Create inventory |
| GET | `/api/v1/inventory/getAll` | Retrieve inventory, with optional pagination |
| POST | `/api/v1/inventory/reserve` | Reserve inventory using an idempotency key |

Reservation requests use the `Idempotency-Key` HTTP header.

Example reservation request:

```http
POST /api/v1/inventory/reserve
Content-Type: application/json
Idempotency-Key: order-1001
```

```json
{
  "sku": "ITEM-001",
  "quantity": 2
}
```

Check the controller and Swagger UI for the exact request fields and any additional endpoint details.

## Concurrency and Idempotency

StockGuard is designed to handle simultaneous reservation requests without allowing available stock to become negative.

### Atomic stock updates

Stock is decremented through a conditional database update that checks whether sufficient inventory is available. The check and decrement happen in the same SQL operation, avoiding the unsafe pattern of reading a quantity and updating it later without an atomic condition.

### Idempotent reservations

Clients supply an `Idempotency-Key` header to identify a reservation request.

PostgreSQL advisory transaction locks coordinate requests using the same key. The database also enforces uniqueness of reservation keys. Together with persisted reservation results, this allows repeated requests to return the existing outcome rather than create duplicate reservations.

### Reservation outcomes

Reservation requests can produce successful or rejected outcomes. The reservation record preserves the outcome so that retries can be handled consistently.

## How to Run Locally

### Prerequisites

- Java 25
- Git
- A PostgreSQL database, such as a Neon database
- Internet connectivity for the remote database

### 1. Clone the repository

```bash
git clone https://github.com/Vikasgabale9/StockGuard.git
cd StockGuard
```

### 2. Configure environment variables

Set the following environment variables before starting the application:

```text
DB_URL=jdbc:postgresql://your-neon-host/your-database?sslmode=require
DB_USERNAME=your_username
DB_PASSWORD=your_password
```

Replace the example values with your own database connection details. Never commit real credentials.

### 3. Run the application

On Windows PowerShell:

```powershell
.\mvnw.cmd spring-boot:run
```

The application uses port `8080` by default, unless configured otherwise.

### 4. Check application health

```http
GET http://localhost:8080/actuator/health
```

### 5. Open API documentation

If Swagger UI is enabled, open:

```text
http://localhost:8080/swagger-ui/index.html
```

## Environment Variables

| Variable | Description |
|---|---|
| `DB_URL` | PostgreSQL JDBC connection URL |
| `DB_USERNAME` | PostgreSQL database username |
| `DB_PASSWORD` | PostgreSQL database password |

The repository includes `.env.example` as a template. It is not a real credentials file and does not automatically load environment variables into the application.

## Testing

The project includes tests intended to cover inventory concurrency, idempotency, and request validation.

Run the test suite with:

```powershell
.\mvnw.cmd clean test
```

Review the Maven output to confirm that compilation and all tests succeed.

## Future Enhancements

- Automated CI checks using GitHub Actions
- Expanded integration and concurrency testing
- Authentication and authorization
- Metrics and operational monitoring
- Optional event-driven inventory processing using Apache Kafka

## Author

**Vikas Vitthalrao Gabale**

GitHub: https://github.com/Vikasgabale9
