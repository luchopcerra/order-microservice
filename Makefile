LANG ?= go

LANGS := go nodejs python scala kotlin java rust cpp
SERVICE_go := app-go
SERVICE_nodejs := app-nodejs
SERVICE_python := app-python
SERVICE_scala := app-scala
SERVICE_kotlin := app-kotlin
SERVICE_java := app-java
SERVICE_rust := app-rust
SERVICE_cpp := app-cpp
SERVICE := $(SERVICE_$(LANG))

.PHONY: start stop logs test test-contract lint fmt build check-lang

check-lang:
	@if [ -z "$(SERVICE)" ]; then \
		echo "Unsupported LANG=$(LANG). Use one of: $(LANGS)"; \
		exit 2; \
	fi

start: check-lang
	docker compose --profile $(LANG) up --build postgres migrate $(SERVICE)

stop:
	docker compose --profile go --profile nodejs --profile python --profile scala --profile kotlin --profile java --profile rust --profile cpp down

logs: check-lang
	docker compose logs -f $(SERVICE)

test-contract:
	node shared/contract-tests/orders-contract.test.mjs

test: check-lang
	$(MAKE) -C implementations/$(LANG) test

lint: check-lang
	$(MAKE) -C implementations/$(LANG) lint

fmt: check-lang
	$(MAKE) -C implementations/$(LANG) fmt

build: check-lang
	docker compose --profile $(LANG) build $(SERVICE)
