FROM golang:1.22-alpine AS builder

WORKDIR /app

COPY go.mod go.sum ./
RUN go mod download

COPY . .
RUN CGO_ENABLED=0 GOOS=linux go build -a -installsuffix cgo -o server ./cmd/server

FROM alpine:3.19

RUN apk --no-cache add ca-certificates
RUN addgroup -S app && adduser -S -G app app

WORKDIR /app

COPY --from=builder /app/server .
USER app

EXPOSE 8080

CMD ["./server"]
