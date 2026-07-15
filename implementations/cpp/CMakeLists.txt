cmake_minimum_required(VERSION 3.22)
project(orders_service_cpp)

set(CMAKE_CXX_STANDARD 20)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

find_package(Drogon CONFIG REQUIRED)

add_executable(orders-service-cpp src/main.cpp)
target_link_libraries(orders-service-cpp PRIVATE Drogon::Drogon)
