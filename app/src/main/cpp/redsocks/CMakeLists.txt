add_executable(redsocks
        base.c dnstc.c http-connect.c
        log.c md5.c socks5.c
        base64.c http-auth.c http-relay.c main.c
        parser.c redsocks.c socks4.c utils.c
        )

target_include_directories(redsocks
        PUBLIC
        ${CMAKE_SOURCE_DIR}/libevent
        ${CMAKE_SOURCE_DIR}/libevent/include
        )

# Include libraries needed for exec lib
target_link_libraries(redsocks
        event
        android
        dl
        log)
