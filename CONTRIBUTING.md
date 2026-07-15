# Contributing

Every language implementation must pass the shared OpenAPI contract and preserve the same JSON response shapes:

- Single order: `{ "data": { ...order } }`
- List: `{ "data": [...], "total": n, "page": n, "limit": n }`
- Error: `{ "error": "...", "code": "..." }`

Run:

```sh
make test-contract
```

Non-trivial AI-assisted commits must include:

```text
Assisted-by: codex:gpt-5 [shell] [apply_patch]
```
