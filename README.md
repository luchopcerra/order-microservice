# Order Microservice

Standalone multi-language Orders Service backend examples sharing one OpenAPI contract, PostgreSQL schema, Docker Compose database, and root `make` UX.

## Languages

- Go
- Node.js
- Python
- Scala
- Kotlin
- Java
- Rust
- C++

## Quick Start

```sh
make start LANG=go
make start LANG=nodejs
make start LANG=python
make start LANG=scala
make start LANG=kotlin
make start LANG=java
make start LANG=rust
make start LANG=cpp
```

Each selected app listens on `http://localhost:8080` and uses the shared PostgreSQL container.

## API

The canonical contract is [`api-spec.yaml`](api-spec.yaml). All implementations expose:

- `GET /health`
- `POST /api/v1/orders`
- `GET /api/v1/orders`
- `GET /api/v1/orders/{orderID}`
- `PATCH /api/v1/orders/{orderID}/status`
- `DELETE /api/v1/orders/{orderID}`

`DELETE` cancels eligible orders by setting `status=cancelled`; it does not hard-delete rows. Only `pending` and `confirmed` orders are cancellable.

## Contract Tests

Start one implementation, then run:

```sh
make test-contract
```

## Environment

All implementations use:

- `SERVER_PORT`
- `DATABASE_URL`

Optional local convenience variables are `DB_HOST`, `DB_PORT`, `DB_USER`, `DB_PASSWORD`, `DB_NAME`, and `DB_SSLMODE`.
