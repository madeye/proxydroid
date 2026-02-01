/*
 * tun2socks - TUN interface to SOCKS5 proxy converter
 * Copyright (C) 2011 Max Lv <max.c.lv@gmail.com>
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

#ifndef TUN2SOCKS_H
#define TUN2SOCKS_H

#include <stdint.h>

#ifdef __cplusplus
extern "C" {
#endif

/**
 * Configuration structure for tun2socks
 */
typedef struct {
    int tun_fd;              // TUN interface file descriptor
    int mtu;                 // Maximum transmission unit
    char *socks_host;        // SOCKS5 proxy host
    int socks_port;          // SOCKS5 proxy port
    char *socks_user;        // SOCKS5 username (can be NULL)
    char *socks_password;    // SOCKS5 password (can be NULL)
} tun2socks_config_t;

/**
 * Start the tun2socks event loop.
 * This function blocks until tun2socks_stop() is called.
 *
 * @param config Configuration structure
 * @return 0 on success, -1 on error
 */
int tun2socks_start(tun2socks_config_t *config);

/**
 * Stop the tun2socks event loop.
 * This function can be called from any thread.
 */
void tun2socks_stop(void);

/**
 * Check if tun2socks is running.
 *
 * @return 1 if running, 0 otherwise
 */
int tun2socks_is_running(void);

#ifdef __cplusplus
}
#endif

#endif /* TUN2SOCKS_H */
