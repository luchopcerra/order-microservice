FROM rust:latest AS build
WORKDIR /app
COPY Cargo.toml ./
COPY src ./src
RUN cargo build --release

FROM debian:bookworm-slim
RUN apt-get update && apt-get install -y --no-install-recommends ca-certificates libssl3 && rm -rf /var/lib/apt/lists/*
WORKDIR /app
RUN useradd --system --create-home app
COPY --from=build /app/target/release/orders-service-rust ./server
USER app
EXPOSE 8080
CMD ["./server"]
