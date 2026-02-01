cmake_minimum_required(VERSION 3.22.1)
project(tun2socks)

set(CMAKE_C_FLAGS "${CMAKE_C_FLAGS} -Wall -O2")
set(CMAKE_CXX_FLAGS "${CMAKE_CXX_FLAGS} -Wall -O2 -std=c++17")

add_library(tun2socks SHARED
    tun2socks_jni.cpp
    core/tun2socks.c
)

target_include_directories(tun2socks PRIVATE
    ${CMAKE_CURRENT_SOURCE_DIR}/core
)

find_library(log-lib log)

target_link_libraries(tun2socks
    ${log-lib}
)
