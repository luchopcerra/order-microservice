FROM drogonframework/drogon:latest AS build
WORKDIR /app
COPY CMakeLists.txt ./
COPY src ./src
RUN cmake -S . -B build && cmake --build build --config Release

FROM drogonframework/drogon:latest
WORKDIR /app
RUN useradd --system --create-home app && mkdir -p /tmp/uploads && chown -R app /tmp/uploads
COPY --from=build /app/build/orders-service-cpp ./orders-service-cpp
USER app
EXPOSE 8080
CMD ["./orders-service-cpp"]
