# Order Microservice — Copilot Instructions

## Repository Overview

This repository contains multiple reference implementations of the same Orders Service backend in different languages. Every implementation shares a single OpenAPI contract, PostgreSQL schema, Docker Compose setup, and contract-test suite.

- Canonical API contract: [`api-spec.yaml`](../api-spec.yaml)
- Shared migrations: [`shared/db/migrations/`](../shared/db/migrations/)
- Shared contract tests: [`shared/contract-tests/orders-contract.test.mjs`](../shared/contract-tests/orders-contract.test.mjs)
- Language implementations: [`implementations/<lang>/`](../implementations/)
- Supported languages: `go`, `nodejs`, `python`, `scala`, `kotlin`, `java`, `rust`, `cpp`

## Build, Test, Lint, and Run

The root [`Makefile`](../Makefile) provides a unified UX. It defaults to `LANG=go`.

```sh
# Start the service for a language (builds the image, runs Postgres, applies migrations)
make start LANG=go

# Follow logs
make logs LANG=go

# Stop all language stacks
make stop

# Run language-specific tests
make test LANG=go

# Run language-specific lint/format
make lint LANG=go
make fmt LANG=go

# Build the Docker image for a language
make build LANG=go

# Run the shared contract tests against a running service on localhost:8080
make test-contract
```

### Running a Single Test

Each implementation also has its own `Makefile` in `implementations/<lang>/`. Examples for running individual tests:

- **Go**: `go test -v ./internal/domain -run TestOrderStatusTransitions`
- **Node.js**: `node --test src/some-test.test.js` (the project uses the built-in Node test runner)
- **Python**: `python -m unittest tests.test_domain.TestOrder.test_transition`
- **Rust**: `cargo test order_status_transitions`
- **Java / Kotlin**: `./gradlew test --tests "com.example.orders.OrderServiceTest.status transition"`

## High-Level Architecture

### Polyglot Implementations Sharing One Contract

All implementations expose the same HTTP endpoints defined in `api-spec.yaml` and use the same `orders` / `order_items` PostgreSQL schema. The repository is structured so that adding a new language means creating a new folder under `implementations/<lang>/` that satisfies the contract, not changing shared code.

### Runtime Layout

`make start LANG=<lang>` runs Docker Compose with the selected language's profile. Compose always starts:

1. `postgres` — shared PostgreSQL 16 container.
2. `migrate` — runs every `.up.sql` file in `shared/db/migrations/` against Postgres.
3. `app-<lang>` — the selected language service, listening on `http://localhost:8080`.

The service uses two environment variables:

- `SERVER_PORT` (default `8080`)
- `DATABASE_URL` (e.g. `postgres://postgres:postgres@postgres:5432/orders?sslmode=disable`)

### Go Implementation Structure

The Go implementation follows a layered layout under `implementations/go/internal/`:

- `domain/` — entities (`Order`, `OrderItem`), value objects, repository interfaces, domain errors, and status-transition rules.
- `application/` — use cases (`create_order.go`, `list_orders.go`, etc.).
- `interfaces/http/` — HTTP handlers, router, middleware, and response helpers.
- `infrastructure/postgres/` — concrete repository and database connection/migrations.

Other language implementations do not need to mirror this structure; they only need to satisfy the external contract.

## Key Conventions

### Response Envelopes

All JSON responses must use these exact shapes:

- Single resource: `{ "data": { ...order } }`
- Paginated list: `{ "data": [...], "total": n, "page": n, "limit": n }`
- Error: `{ "error": "...", "code": "..." }`

### Order Status Lifecycle

The service enforces a strict state machine. Valid transitions are:

- `pending` → `confirmed` or `cancelled`
- `confirmed` → `shipped` or `cancelled`
- `shipped` → `delivered`
- `delivered` and `cancelled` are terminal

Only `pending` and `confirmed` orders may be cancelled.

### Deletion Is Cancellation

`DELETE /api/v1/orders/{orderID}` does **not** remove the row. It sets `status = 'cancelled'` and returns `204 No Content`. Cancelled orders remain retrievable via `GET /api/v1/orders/{orderID}` and list endpoints.

### Calculated Totals and Validation

- `total_amount` is computed from `quantity * unit_price` for all items.
- `quantity` must be a positive integer.
- `unit_price` must be non-negative.
- `customer_id` and `product_id` are UUIDs.
- List pagination defaults to `page=1`, `limit=20`, with `limit` capped at 100.

### Database Schema

- `orders` table: `id` (UUID PK), `customer_id` (UUID), `status`, `total_amount` (DECIMAL), `created_at`, `updated_at`.
- `order_items` table: `id` (UUID PK), `order_id` (FK → orders ON DELETE CASCADE), `product_id`, `quantity`, `unit_price`, `created_at`.
- Migrations are numbered sequentially (e.g. `000001_create_orders.up.sql`) and applied in filename order by the `migrate` container.

### Adding a New Language

When adding an implementation, satisfy every item in [`docs/adding-a-language.md`](../docs/adding-a-language.md):

- Create `implementations/<lang>/` with a `Dockerfile` exposing port `8080`.
- Add a local `Makefile` with `build`, `test`, `lint`, and `fmt` targets.
- Read `SERVER_PORT` and `DATABASE_URL`.
- Implement all endpoints in `api-spec.yaml` exactly.
- Enforce the status lifecycle and response envelopes above.
- Add a Compose `app-<lang>` service profile and a `SERVICE_<lang>` mapping in the root `Makefile`.
- Run `make start LANG=<lang>` followed by `make test-contract` before submitting.

### Commit Trailers

Non-trivial AI-assisted commits must include an `Assisted-by:` trailer, for example:

```text
Assisted-by: codex:gpt-5 [shell] [apply_patch]
```
