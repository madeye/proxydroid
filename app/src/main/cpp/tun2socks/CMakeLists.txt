cmake_minimum_required(VERSION 3.4.1)

add_library(tun2socks SHARED
    tun2socks_jni.cpp
)

target_include_directories(tun2socks PRIVATE
    ${CMAKE_CURRENT_SOURCE_DIR}
)

target_link_libraries(tun2socks
    android
    log
)

# Enable C++11
set_target_properties(tun2socks PROPERTIES
    CXX_STANDARD 11
    CXX_STANDARD_REQUIRED ON
)
