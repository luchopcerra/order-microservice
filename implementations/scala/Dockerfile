FROM eclipse-temurin:21-jdk AS build
WORKDIR /app
RUN apt-get update && apt-get install -y --no-install-recommends curl ca-certificates && rm -rf /var/lib/apt/lists/* \
    && curl -fsSL https://github.com/sbt/sbt/releases/download/v1.10.2/sbt-1.10.2.tgz | tar -xz -C /opt \
    && ln -s /opt/sbt/bin/sbt /usr/local/bin/sbt
COPY build.sbt ./
COPY project ./project
COPY src ./src
RUN sbt stage

FROM eclipse-temurin:21-jre
WORKDIR /app
RUN useradd --system --create-home app
COPY --from=build /app/target/universal/stage ./
RUN chmod +x /app/bin/orders-service-scala
USER app
EXPOSE 8080
CMD ["bin/orders-service-scala"]
