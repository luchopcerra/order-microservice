FROM gradle:8.10-jdk21 AS build
WORKDIR /app
COPY settings.gradle.kts build.gradle.kts ./
COPY src ./src
RUN gradle installDist --no-daemon

FROM eclipse-temurin:21-jre
WORKDIR /app
RUN useradd --system --create-home app
COPY --from=build /app/build/install/orders-service-kotlin ./
USER app
EXPOSE 8080
CMD ["bin/orders-service-kotlin"]
