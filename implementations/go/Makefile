.PHONY: build run start stop test lint clean migrate docker-up docker-down

build:
	go build -o bin/server ./cmd/server

run:
	go run ./cmd/server

test:
	go test -v ./...

test-integration:
	go test -v -tags=integration ./test/integration/...

lint:
	golangci-lint run ./...

clean:
	rm -rf bin/

migrate-up:
	migrate -path db/migrations -database "postgres://postgres:postgres@localhost:5432/orders?sslmode=disable" up

migrate-down:
	migrate -path db/migrations -database "postgres://postgres:postgres@localhost:5432/orders?sslmode=disable" down

start:
	docker-compose up -d --build
	@echo ""
	@echo "Waiting for PostgreSQL to be ready..."
	@sleep 3
	@echo "✅ Services started!"
	@echo ""
	@echo "   App:      http://localhost:8080"
	@echo "   Health:   http://localhost:8080/health"
	@echo "   Postgres: localhost:5432"
	@echo ""
	@echo "Run 'make logs' to see output"

stop:
	docker-compose down
	@echo "Services stopped"

logs:
	docker-compose logs -f app

docker-up:
	docker-compose up -d --build

docker-down:
	docker-compose down

docker-logs:
	docker-compose logs -f app

deps:
	go mod tidy
	go mod download

fmt:
	gofmt -s -w .

proto:
	@echo "No protobuf definitions"
