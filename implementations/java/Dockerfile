FROM gradle:8.10-jdk21 AS build
WORKDIR /app
COPY settings.gradle build.gradle ./
COPY src ./src
RUN gradle installDist --no-daemon

FROM eclipse-temurin:21-jre
WORKDIR /app
COPY --from=build /app/build/install/orders-service-java ./
EXPOSE 8080
CMD ["bin/orders-service-java"]
