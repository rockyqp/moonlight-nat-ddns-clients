cmake_minimum_required(VERSION 3.22.1)

project(moonlightnat)

add_library(moonlightnat SHARED
    moonlight_nat.cpp
)

target_link_libraries(moonlightnat
    android
    log
)
