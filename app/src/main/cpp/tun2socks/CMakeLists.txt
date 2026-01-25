cmake_minimum_required(VERSION 3.4.1)

add_library(tun2socks SHARED
    tun2socks_jni.cpp
    core/tun2socks.c
)

target_include_directories(tun2socks PRIVATE
    ${CMAKE_CURRENT_SOURCE_DIR}
    ${CMAKE_CURRENT_SOURCE_DIR}/core
)

target_link_libraries(tun2socks
    android
    log
)

# Enable C++11 for JNI wrapper
set_target_properties(tun2socks PROPERTIES
    CXX_STANDARD 11
    CXX_STANDARD_REQUIRED ON
)

# C99 for core implementation
set_source_files_properties(core/tun2socks.c PROPERTIES
    COMPILE_FLAGS "-std=c99"
)
