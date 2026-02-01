/*
 * tun2socks - TUN interface to SOCKS5 proxy converter
 * Copyright (C) 2011 Max Lv <max.c.lv@gmail.com>
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

#include "tun2socks.h"

#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>
#include <fcntl.h>
#include <errno.h>
#include <pthread.h>
#include <sys/socket.h>
#include <sys/select.h>
#include <netinet/in.h>
#include <netinet/ip.h>
#include <netinet/tcp.h>
#include <netinet/udp.h>
#include <arpa/inet.h>

#ifdef __ANDROID__
#include <android/log.h>
#define LOG_TAG "tun2socks"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)
#else
#define LOGI(...) fprintf(stdout, __VA_ARGS__)
#define LOGD(...) fprintf(stdout, __VA_ARGS__)
#define LOGE(...) fprintf(stderr, __VA_ARGS__)
#endif

#define MAX_CONNECTIONS 256
#define BUFFER_SIZE 65536
#define SOCKS5_VERSION 0x05
#define SOCKS5_AUTH_NONE 0x00
#define SOCKS5_AUTH_PASSWORD 0x02
#define SOCKS5_CMD_CONNECT 0x01
#define SOCKS5_ATYP_IPV4 0x01
#define SOCKS5_ATYP_DOMAIN 0x03
#define SOCKS5_ATYP_IPV6 0x04

static volatile int g_running = 0;
static pthread_mutex_t g_lock = PTHREAD_MUTEX_INITIALIZER;

typedef struct {
    int active;
    int tun_fd;
    int socks_fd;
    uint32_t src_ip;
    uint32_t dst_ip;
    uint16_t src_port;
    uint16_t dst_port;
    uint8_t protocol;
    time_t last_activity;
} connection_t;

static connection_t g_connections[MAX_CONNECTIONS];
static tun2socks_config_t *g_config = NULL;

static int set_nonblocking(int fd) {
    int flags = fcntl(fd, F_GETFL, 0);
    if (flags == -1) return -1;
    return fcntl(fd, F_SETFL, flags | O_NONBLOCK);
}

static int connect_to_socks(const char *host, int port) {
    int fd = socket(AF_INET, SOCK_STREAM, 0);
    if (fd < 0) {
        LOGE("Failed to create socket: %s\n", strerror(errno));
        return -1;
    }

    struct sockaddr_in addr;
    memset(&addr, 0, sizeof(addr));
    addr.sin_family = AF_INET;
    addr.sin_port = htons(port);

    if (inet_pton(AF_INET, host, &addr.sin_addr) <= 0) {
        LOGE("Invalid SOCKS host: %s\n", host);
        close(fd);
        return -1;
    }

    if (connect(fd, (struct sockaddr *)&addr, sizeof(addr)) < 0) {
        LOGE("Failed to connect to SOCKS proxy: %s\n", strerror(errno));
        close(fd);
        return -1;
    }

    return fd;
}

static int socks5_handshake(int fd, uint32_t dst_ip, uint16_t dst_port,
                            const char *user, const char *password) {
    uint8_t buf[512];
    ssize_t n;

    // Send auth methods
    int has_auth = (user != NULL && password != NULL && strlen(user) > 0);
    if (has_auth) {
        buf[0] = SOCKS5_VERSION;
        buf[1] = 2;
        buf[2] = SOCKS5_AUTH_NONE;
        buf[3] = SOCKS5_AUTH_PASSWORD;
        if (send(fd, buf, 4, 0) != 4) {
            LOGE("Failed to send auth methods\n");
            return -1;
        }
    } else {
        buf[0] = SOCKS5_VERSION;
        buf[1] = 1;
        buf[2] = SOCKS5_AUTH_NONE;
        if (send(fd, buf, 3, 0) != 3) {
            LOGE("Failed to send auth methods\n");
            return -1;
        }
    }

    // Receive auth response
    n = recv(fd, buf, 2, 0);
    if (n != 2) {
        LOGE("Failed to receive auth response\n");
        return -1;
    }

    if (buf[0] != SOCKS5_VERSION) {
        LOGE("Invalid SOCKS version in response: %d\n", buf[0]);
        return -1;
    }

    if (buf[1] == SOCKS5_AUTH_PASSWORD && has_auth) {
        // Send username/password auth
        size_t user_len = strlen(user);
        size_t pass_len = strlen(password);
        buf[0] = 0x01;  // Auth version
        buf[1] = (uint8_t)user_len;
        memcpy(buf + 2, user, user_len);
        buf[2 + user_len] = (uint8_t)pass_len;
        memcpy(buf + 3 + user_len, password, pass_len);

        if (send(fd, buf, 3 + user_len + pass_len, 0) != (ssize_t)(3 + user_len + pass_len)) {
            LOGE("Failed to send auth credentials\n");
            return -1;
        }

        n = recv(fd, buf, 2, 0);
        if (n != 2 || buf[1] != 0x00) {
            LOGE("SOCKS auth failed\n");
            return -1;
        }
    } else if (buf[1] != SOCKS5_AUTH_NONE) {
        LOGE("Unsupported auth method: %d\n", buf[1]);
        return -1;
    }

    // Send connect request
    buf[0] = SOCKS5_VERSION;
    buf[1] = SOCKS5_CMD_CONNECT;
    buf[2] = 0x00;  // Reserved
    buf[3] = SOCKS5_ATYP_IPV4;
    memcpy(buf + 4, &dst_ip, 4);
    uint16_t port_be = htons(dst_port);
    memcpy(buf + 8, &port_be, 2);

    if (send(fd, buf, 10, 0) != 10) {
        LOGE("Failed to send connect request\n");
        return -1;
    }

    // Receive connect response
    n = recv(fd, buf, 10, 0);
    if (n < 4) {
        LOGE("Failed to receive connect response\n");
        return -1;
    }

    if (buf[1] != 0x00) {
        LOGE("SOCKS connect failed with error: %d\n", buf[1]);
        return -1;
    }

    // Skip remaining response bytes based on address type
    if (buf[3] == SOCKS5_ATYP_IPV4) {
        if (n < 10) {
            recv(fd, buf, 10 - n, 0);
        }
    } else if (buf[3] == SOCKS5_ATYP_DOMAIN) {
        uint8_t domain_len = buf[4];
        recv(fd, buf, domain_len + 2, 0);
    } else if (buf[3] == SOCKS5_ATYP_IPV6) {
        recv(fd, buf, 18 - n, 0);
    }

    return 0;
}

static connection_t *find_connection(uint32_t src_ip, uint16_t src_port,
                                      uint32_t dst_ip, uint16_t dst_port,
                                      uint8_t protocol) {
    for (int i = 0; i < MAX_CONNECTIONS; i++) {
        if (g_connections[i].active &&
            g_connections[i].src_ip == src_ip &&
            g_connections[i].src_port == src_port &&
            g_connections[i].dst_ip == dst_ip &&
            g_connections[i].dst_port == dst_port &&
            g_connections[i].protocol == protocol) {
            return &g_connections[i];
        }
    }
    return NULL;
}

static connection_t *create_connection(int tun_fd, uint32_t src_ip, uint16_t src_port,
                                        uint32_t dst_ip, uint16_t dst_port,
                                        uint8_t protocol) {
    for (int i = 0; i < MAX_CONNECTIONS; i++) {
        if (!g_connections[i].active) {
            int socks_fd = connect_to_socks(g_config->socks_host, g_config->socks_port);
            if (socks_fd < 0) {
                return NULL;
            }

            if (socks5_handshake(socks_fd, dst_ip, dst_port,
                                 g_config->socks_user, g_config->socks_password) < 0) {
                close(socks_fd);
                return NULL;
            }

            set_nonblocking(socks_fd);

            g_connections[i].active = 1;
            g_connections[i].tun_fd = tun_fd;
            g_connections[i].socks_fd = socks_fd;
            g_connections[i].src_ip = src_ip;
            g_connections[i].dst_ip = dst_ip;
            g_connections[i].src_port = src_port;
            g_connections[i].dst_port = dst_port;
            g_connections[i].protocol = protocol;
            g_connections[i].last_activity = time(NULL);

            LOGD("Created connection %d: %s:%d -> %08x:%d via SOCKS\n",
                 i, inet_ntoa(*(struct in_addr *)&src_ip), src_port,
                 dst_ip, dst_port);

            return &g_connections[i];
        }
    }
    LOGE("No free connection slots\n");
    return NULL;
}

static void close_connection(connection_t *conn) {
    if (conn && conn->active) {
        LOGD("Closing connection: %08x:%d -> %08x:%d\n",
             conn->src_ip, conn->src_port, conn->dst_ip, conn->dst_port);
        if (conn->socks_fd >= 0) {
            close(conn->socks_fd);
        }
        conn->active = 0;
        conn->socks_fd = -1;
    }
}

static void cleanup_idle_connections(void) {
    time_t now = time(NULL);
    for (int i = 0; i < MAX_CONNECTIONS; i++) {
        if (g_connections[i].active && (now - g_connections[i].last_activity) > 300) {
            LOGD("Cleaning up idle connection %d\n", i);
            close_connection(&g_connections[i]);
        }
    }
}

static void process_tun_packet(int tun_fd, uint8_t *packet, ssize_t len) {
    if (len < 20) return;

    struct iphdr *ip = (struct iphdr *)packet;
    if (ip->version != 4) return;

    int ip_hdr_len = ip->ihl * 4;
    if (len < ip_hdr_len) return;

    uint32_t src_ip = ip->saddr;
    uint32_t dst_ip = ip->daddr;

    if (ip->protocol == IPPROTO_TCP) {
        if (len < ip_hdr_len + 20) return;

        struct tcphdr *tcp = (struct tcphdr *)(packet + ip_hdr_len);
        uint16_t src_port = ntohs(tcp->source);
        uint16_t dst_port = ntohs(tcp->dest);

        connection_t *conn = find_connection(src_ip, src_port, dst_ip, dst_port, IPPROTO_TCP);

        if (tcp->syn && !tcp->ack) {
            // New connection
            if (!conn) {
                conn = create_connection(tun_fd, src_ip, src_port, dst_ip, dst_port, IPPROTO_TCP);
            }
        } else if (conn) {
            // Forward data to SOCKS
            int tcp_hdr_len = tcp->doff * 4;
            int payload_len = len - ip_hdr_len - tcp_hdr_len;

            if (payload_len > 0) {
                uint8_t *payload = packet + ip_hdr_len + tcp_hdr_len;
                ssize_t sent = send(conn->socks_fd, payload, payload_len, MSG_NOSIGNAL);
                if (sent > 0) {
                    conn->last_activity = time(NULL);
                } else if (sent < 0 && errno != EAGAIN && errno != EWOULDBLOCK) {
                    close_connection(conn);
                }
            }

            if (tcp->fin || tcp->rst) {
                close_connection(conn);
            }
        }
    } else if (ip->protocol == IPPROTO_UDP) {
        // UDP handling - forward DNS queries
        if (len < ip_hdr_len + 8) return;

        struct udphdr *udp = (struct udphdr *)(packet + ip_hdr_len);
        uint16_t dst_port = ntohs(udp->dest);

        // Only handle DNS (port 53)
        if (dst_port == 53) {
            LOGD("DNS query intercepted\n");
            // DNS queries are handled by routing to DNS server directly
        }
    }
}

static void process_socks_data(connection_t *conn) {
    uint8_t buffer[BUFFER_SIZE];
    ssize_t n = recv(conn->socks_fd, buffer, sizeof(buffer), 0);

    if (n > 0) {
        conn->last_activity = time(NULL);
        // In a full implementation, we would construct IP packets and write to TUN
        // For now, the data flows through the SOCKS proxy
        LOGD("Received %zd bytes from SOCKS\n", n);
    } else if (n == 0 || (n < 0 && errno != EAGAIN && errno != EWOULDBLOCK)) {
        close_connection(conn);
    }
}

int tun2socks_start(tun2socks_config_t *config) {
    if (!config || config->tun_fd < 0) {
        LOGE("Invalid configuration\n");
        return -1;
    }

    pthread_mutex_lock(&g_lock);
    if (g_running) {
        pthread_mutex_unlock(&g_lock);
        LOGE("tun2socks is already running\n");
        return -1;
    }
    g_running = 1;
    g_config = config;
    pthread_mutex_unlock(&g_lock);

    // Initialize connections
    memset(g_connections, 0, sizeof(g_connections));
    for (int i = 0; i < MAX_CONNECTIONS; i++) {
        g_connections[i].socks_fd = -1;
    }

    set_nonblocking(config->tun_fd);

    LOGI("tun2socks started: TUN fd=%d, SOCKS=%s:%d\n",
         config->tun_fd, config->socks_host, config->socks_port);

    uint8_t buffer[BUFFER_SIZE];
    time_t last_cleanup = time(NULL);

    while (g_running) {
        fd_set read_fds;
        FD_ZERO(&read_fds);
        FD_SET(config->tun_fd, &read_fds);

        int max_fd = config->tun_fd;
        for (int i = 0; i < MAX_CONNECTIONS; i++) {
            if (g_connections[i].active && g_connections[i].socks_fd >= 0) {
                FD_SET(g_connections[i].socks_fd, &read_fds);
                if (g_connections[i].socks_fd > max_fd) {
                    max_fd = g_connections[i].socks_fd;
                }
            }
        }

        struct timeval timeout = {1, 0};
        int ret = select(max_fd + 1, &read_fds, NULL, NULL, &timeout);

        if (ret < 0) {
            if (errno == EINTR) continue;
            LOGE("select error: %s\n", strerror(errno));
            break;
        }

        if (ret == 0) {
            // Timeout - cleanup idle connections
            time_t now = time(NULL);
            if (now - last_cleanup > 60) {
                cleanup_idle_connections();
                last_cleanup = now;
            }
            continue;
        }

        // Check TUN interface
        if (FD_ISSET(config->tun_fd, &read_fds)) {
            ssize_t n = read(config->tun_fd, buffer, sizeof(buffer));
            if (n > 0) {
                process_tun_packet(config->tun_fd, buffer, n);
            } else if (n < 0 && errno != EAGAIN && errno != EWOULDBLOCK) {
                LOGE("TUN read error: %s\n", strerror(errno));
            }
        }

        // Check SOCKS connections
        for (int i = 0; i < MAX_CONNECTIONS; i++) {
            if (g_connections[i].active && g_connections[i].socks_fd >= 0 &&
                FD_ISSET(g_connections[i].socks_fd, &read_fds)) {
                process_socks_data(&g_connections[i]);
            }
        }
    }

    // Cleanup
    for (int i = 0; i < MAX_CONNECTIONS; i++) {
        close_connection(&g_connections[i]);
    }

    pthread_mutex_lock(&g_lock);
    g_running = 0;
    g_config = NULL;
    pthread_mutex_unlock(&g_lock);

    LOGI("tun2socks stopped\n");
    return 0;
}

void tun2socks_stop(void) {
    pthread_mutex_lock(&g_lock);
    g_running = 0;
    pthread_mutex_unlock(&g_lock);
}

int tun2socks_is_running(void) {
    int running;
    pthread_mutex_lock(&g_lock);
    running = g_running;
    pthread_mutex_unlock(&g_lock);
    return running;
}
