# Adding a Language

New implementations live in `implementations/<lang>/` and must provide:

- A Dockerfile exposing port `8080`.
- Native build/test/lint/fmt targets in a local `Makefile`.
- Support for `SERVER_PORT` and `DATABASE_URL`.
- The exact shared API behavior in `api-spec.yaml`.
- Status lifecycle enforcement:
  - `pending -> confirmed | cancelled`
  - `confirmed -> shipped | cancelled`
  - `shipped -> delivered`
  - `delivered` and `cancelled` are terminal.

Add a Compose service profile and root Makefile mapping, then run the contract tests against the service.
