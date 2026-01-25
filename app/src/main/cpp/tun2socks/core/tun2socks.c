/*
 * tun2socks - Convert TUN traffic to SOCKS proxy
 * Main implementation
 */

#include "tun2socks.h"
#include <stdlib.h>
#include <string.h>
#include <unistd.h>
#include <errno.h>
#include <fcntl.h>
#include <time.h>
#include <poll.h>
#include <sys/socket.h>
#include <netinet/in.h>
#include <arpa/inet.h>

#ifdef __ANDROID__
#include <android/log.h>
#define LOG_TAG "tun2socks"
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, LOG_TAG, __VA_ARGS__)
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)
#else
#define LOGD(...) fprintf(stderr, __VA_ARGS__); fprintf(stderr, "\n")
#define LOGI(...) fprintf(stderr, __VA_ARGS__); fprintf(stderr, "\n")
#define LOGW(...) fprintf(stderr, __VA_ARGS__); fprintf(stderr, "\n")
#define LOGE(...) fprintf(stderr, __VA_ARGS__); fprintf(stderr, "\n")
#endif

// Parse SOCKS5 URL: socks5://[user:pass@]host:port
static int parse_socks_url(const char *url, socks5_config_t *config) {
    memset(config, 0, sizeof(socks5_config_t));

    const char *p = url;

    // Skip protocol
    if (strncmp(p, "socks5://", 9) == 0) {
        p += 9;
    } else if (strncmp(p, "socks://", 8) == 0) {
        p += 8;
    }

    // Check for auth
    const char *at = strchr(p, '@');
    if (at != NULL) {
        const char *colon = strchr(p, ':');
        if (colon != NULL && colon < at) {
            size_t user_len = colon - p;
            size_t pass_len = at - colon - 1;
            if (user_len < sizeof(config->username) && pass_len < sizeof(config->password)) {
                strncpy(config->username, p, user_len);
                strncpy(config->password, colon + 1, pass_len);
                config->auth_required = true;
            }
        }
        p = at + 1;
    }

    // Parse host:port
    const char *colon = strrchr(p, ':');
    if (colon != NULL) {
        size_t host_len = colon - p;
        if (host_len < sizeof(config->host)) {
            strncpy(config->host, p, host_len);
            config->port = atoi(colon + 1);
        }
    } else {
        strncpy(config->host, p, sizeof(config->host) - 1);
        config->port = 1080;
    }

    return 0;
}

tun2socks_ctx_t *tun2socks_create(int tun_fd, int mtu, const char *tun_addr,
                                   const char *proxy_url, const char *dns_addr) {
    tun2socks_ctx_t *ctx = calloc(1, sizeof(tun2socks_ctx_t));
    if (ctx == NULL) {
        return NULL;
    }

    ctx->tun_fd = tun_fd;
    ctx->mtu = mtu > 0 ? mtu : TUN2SOCKS_MTU;

    inet_pton(AF_INET, tun_addr, &ctx->tun_addr);
    ctx->tun_netmask = htonl(0xFFFFFF00); // /24

    if (dns_addr != NULL) {
        strncpy(ctx->dns_addr, dns_addr, sizeof(ctx->dns_addr) - 1);
    }

    parse_socks_url(proxy_url, &ctx->socks_config);

    ctx->running = false;
    ctx->tcp_connections = NULL;
    ctx->udp_sessions = NULL;

    LOGI("tun2socks created: tun_fd=%d, mtu=%d, proxy=%s:%d",
         tun_fd, ctx->mtu, ctx->socks_config.host, ctx->socks_config.port);

    return ctx;
}

void tun2socks_destroy(tun2socks_ctx_t *ctx) {
    if (ctx == NULL) return;

    ctx->running = false;

    // Clean up TCP connections
    tcp_connection_t *conn = ctx->tcp_connections;
    while (conn != NULL) {
        tcp_connection_t *next = conn->next;
        if (conn->socks_fd >= 0) {
            close(conn->socks_fd);
        }
        if (conn->send_buffer) free(conn->send_buffer);
        if (conn->recv_buffer) free(conn->recv_buffer);
        free(conn);
        conn = next;
    }

    // Clean up UDP sessions
    udp_session_t *sess = ctx->udp_sessions;
    while (sess != NULL) {
        udp_session_t *next = sess->next;
        if (sess->socks_fd >= 0) {
            close(sess->socks_fd);
        }
        free(sess);
        sess = next;
    }

    free(ctx);
}

void tun2socks_stop(tun2socks_ctx_t *ctx) {
    if (ctx != NULL) {
        ctx->running = false;
    }
}

// Calculate IP header checksum
uint16_t ip_checksum(void *data, size_t len) {
    uint32_t sum = 0;
    uint16_t *ptr = (uint16_t *)data;

    while (len > 1) {
        sum += *ptr++;
        len -= 2;
    }

    if (len == 1) {
        sum += *(uint8_t *)ptr;
    }

    while (sum >> 16) {
        sum = (sum & 0xFFFF) + (sum >> 16);
    }

    return ~sum;
}

// Calculate TCP checksum with pseudo-header
uint16_t tcp_checksum(ip_header_t *ip, tcp_header_t *tcp, uint8_t *data, size_t data_len) {
    uint32_t sum = 0;

    // Pseudo header
    sum += (ip->src_addr >> 16) & 0xFFFF;
    sum += ip->src_addr & 0xFFFF;
    sum += (ip->dst_addr >> 16) & 0xFFFF;
    sum += ip->dst_addr & 0xFFFF;
    sum += htons(IPPROTO_TCP);

    size_t tcp_len = ((tcp->data_offset >> 4) * 4) + data_len;
    sum += htons(tcp_len);

    // TCP header (with checksum field set to 0)
    uint16_t saved_checksum = tcp->checksum;
    tcp->checksum = 0;

    uint16_t *ptr = (uint16_t *)tcp;
    size_t header_len = (tcp->data_offset >> 4) * 4;

    for (size_t i = 0; i < header_len / 2; i++) {
        sum += ptr[i];
    }

    // TCP data
    ptr = (uint16_t *)data;
    size_t remaining = data_len;
    while (remaining > 1) {
        sum += *ptr++;
        remaining -= 2;
    }
    if (remaining == 1) {
        sum += *(uint8_t *)ptr;
    }

    tcp->checksum = saved_checksum;

    while (sum >> 16) {
        sum = (sum & 0xFFFF) + (sum >> 16);
    }

    return ~sum;
}

// Calculate UDP checksum with pseudo-header
uint16_t udp_checksum(ip_header_t *ip, udp_header_t *udp, uint8_t *data, size_t data_len) {
    uint32_t sum = 0;

    // Pseudo header
    sum += (ip->src_addr >> 16) & 0xFFFF;
    sum += ip->src_addr & 0xFFFF;
    sum += (ip->dst_addr >> 16) & 0xFFFF;
    sum += ip->dst_addr & 0xFFFF;
    sum += htons(IPPROTO_UDP);
    sum += udp->length;

    // UDP header
    uint16_t saved_checksum = udp->checksum;
    udp->checksum = 0;

    uint16_t *ptr = (uint16_t *)udp;
    for (int i = 0; i < 4; i++) {
        sum += ptr[i];
    }

    // UDP data
    ptr = (uint16_t *)data;
    size_t remaining = data_len;
    while (remaining > 1) {
        sum += *ptr++;
        remaining -= 2;
    }
    if (remaining == 1) {
        sum += *(uint8_t *)ptr;
    }

    udp->checksum = saved_checksum;

    while (sum >> 16) {
        sum = (sum & 0xFFFF) + (sum >> 16);
    }

    return ~sum;
}

tcp_connection_t *find_tcp_connection(tun2socks_ctx_t *ctx, uint32_t src, uint16_t sport,
                                       uint32_t dst, uint16_t dport) {
    tcp_connection_t *conn = ctx->tcp_connections;
    while (conn != NULL) {
        if (conn->src_addr == src && conn->src_port == sport &&
            conn->dst_addr == dst && conn->dst_port == dport) {
            return conn;
        }
        conn = conn->next;
    }
    return NULL;
}

tcp_connection_t *create_tcp_connection(tun2socks_ctx_t *ctx, uint32_t src, uint16_t sport,
                                         uint32_t dst, uint16_t dport) {
    tcp_connection_t *conn = calloc(1, sizeof(tcp_connection_t));
    if (conn == NULL) return NULL;

    conn->src_addr = src;
    conn->src_port = sport;
    conn->dst_addr = dst;
    conn->dst_port = dport;
    conn->state = TCP_STATE_CLOSED;
    conn->socks_fd = -1;
    conn->local_window = 65535;
    conn->last_activity = time(NULL);

    conn->send_buffer_cap = TUN2SOCKS_BUFFER_SIZE;
    conn->send_buffer = malloc(conn->send_buffer_cap);
    conn->recv_buffer_cap = TUN2SOCKS_BUFFER_SIZE;
    conn->recv_buffer = malloc(conn->recv_buffer_cap);

    if (conn->send_buffer == NULL || conn->recv_buffer == NULL) {
        if (conn->send_buffer) free(conn->send_buffer);
        if (conn->recv_buffer) free(conn->recv_buffer);
        free(conn);
        return NULL;
    }

    // Add to list
    conn->next = ctx->tcp_connections;
    conn->prev = NULL;
    if (ctx->tcp_connections != NULL) {
        ctx->tcp_connections->prev = conn;
    }
    ctx->tcp_connections = conn;

    return conn;
}

void destroy_tcp_connection(tun2socks_ctx_t *ctx, tcp_connection_t *conn) {
    if (conn == NULL) return;

    // Remove from list
    if (conn->prev != NULL) {
        conn->prev->next = conn->next;
    } else {
        ctx->tcp_connections = conn->next;
    }
    if (conn->next != NULL) {
        conn->next->prev = conn->prev;
    }

    if (conn->socks_fd >= 0) {
        close(conn->socks_fd);
    }
    if (conn->send_buffer) free(conn->send_buffer);
    if (conn->recv_buffer) free(conn->recv_buffer);
    free(conn);
}

// Connect to SOCKS5 proxy
int socks5_connect(socks5_config_t *config, const char *dest_host, int dest_port) {
    int sock = socket(AF_INET, SOCK_STREAM, 0);
    if (sock < 0) {
        LOGE("Failed to create socket: %s", strerror(errno));
        return -1;
    }

    // Set non-blocking for connect
    int flags = fcntl(sock, F_GETFL, 0);
    fcntl(sock, F_SETFL, flags | O_NONBLOCK);

    struct sockaddr_in addr;
    memset(&addr, 0, sizeof(addr));
    addr.sin_family = AF_INET;
    addr.sin_port = htons(config->port);
    inet_pton(AF_INET, config->host, &addr.sin_addr);

    int ret = connect(sock, (struct sockaddr *)&addr, sizeof(addr));
    if (ret < 0 && errno != EINPROGRESS) {
        LOGE("Failed to connect to SOCKS server: %s", strerror(errno));
        close(sock);
        return -1;
    }

    // Wait for connection
    struct pollfd pfd;
    pfd.fd = sock;
    pfd.events = POLLOUT;
    ret = poll(&pfd, 1, TUN2SOCKS_CONNECT_TIMEOUT * 1000);
    if (ret <= 0) {
        LOGE("SOCKS connect timeout");
        close(sock);
        return -1;
    }

    // Check connection result
    int error = 0;
    socklen_t len = sizeof(error);
    getsockopt(sock, SOL_SOCKET, SO_ERROR, &error, &len);
    if (error != 0) {
        LOGE("SOCKS connect failed: %s", strerror(error));
        close(sock);
        return -1;
    }

    // Set back to blocking
    fcntl(sock, F_SETFL, flags);

    // SOCKS5 handshake
    uint8_t handshake[4];
    handshake[0] = SOCKS5_VERSION;

    if (config->auth_required) {
        handshake[1] = 2; // 2 methods
        handshake[2] = SOCKS5_AUTH_NONE;
        handshake[3] = SOCKS5_AUTH_PASSWORD;
        write(sock, handshake, 4);
    } else {
        handshake[1] = 1; // 1 method
        handshake[2] = SOCKS5_AUTH_NONE;
        write(sock, handshake, 3);
    }

    uint8_t response[2];
    if (read(sock, response, 2) != 2 || response[0] != SOCKS5_VERSION) {
        LOGE("Invalid SOCKS5 handshake response");
        close(sock);
        return -1;
    }

    // Handle authentication
    if (response[1] == SOCKS5_AUTH_PASSWORD) {
        if (!config->auth_required) {
            LOGE("Server requires auth but none provided");
            close(sock);
            return -1;
        }

        size_t user_len = strlen(config->username);
        size_t pass_len = strlen(config->password);
        uint8_t auth[515];
        auth[0] = 0x01; // Auth version
        auth[1] = user_len;
        memcpy(auth + 2, config->username, user_len);
        auth[2 + user_len] = pass_len;
        memcpy(auth + 3 + user_len, config->password, pass_len);

        write(sock, auth, 3 + user_len + pass_len);

        uint8_t auth_resp[2];
        if (read(sock, auth_resp, 2) != 2 || auth_resp[1] != 0) {
            LOGE("SOCKS5 authentication failed");
            close(sock);
            return -1;
        }
    } else if (response[1] != SOCKS5_AUTH_NONE) {
        LOGE("Unsupported SOCKS5 auth method: %d", response[1]);
        close(sock);
        return -1;
    }

    // Send connect request
    uint8_t request[512];
    size_t req_len = 0;
    request[req_len++] = SOCKS5_VERSION;
    request[req_len++] = SOCKS5_CMD_CONNECT;
    request[req_len++] = 0x00; // Reserved

    // Check if dest_host is IP or domain
    struct in_addr ip;
    if (inet_pton(AF_INET, dest_host, &ip) == 1) {
        request[req_len++] = SOCKS5_ATYP_IPV4;
        memcpy(request + req_len, &ip.s_addr, 4);
        req_len += 4;
    } else {
        size_t host_len = strlen(dest_host);
        request[req_len++] = SOCKS5_ATYP_DOMAIN;
        request[req_len++] = host_len;
        memcpy(request + req_len, dest_host, host_len);
        req_len += host_len;
    }

    request[req_len++] = (dest_port >> 8) & 0xFF;
    request[req_len++] = dest_port & 0xFF;

    write(sock, request, req_len);

    // Read response
    uint8_t conn_resp[4];
    if (read(sock, conn_resp, 4) != 4) {
        LOGE("Failed to read SOCKS5 connect response");
        close(sock);
        return -1;
    }

    if (conn_resp[0] != SOCKS5_VERSION || conn_resp[1] != SOCKS5_REP_SUCCESS) {
        LOGE("SOCKS5 connect failed: %d", conn_resp[1]);
        close(sock);
        return -1;
    }

    // Skip bound address
    if (conn_resp[3] == SOCKS5_ATYP_IPV4) {
        uint8_t skip[6];
        read(sock, skip, 6);
    } else if (conn_resp[3] == SOCKS5_ATYP_DOMAIN) {
        uint8_t len;
        read(sock, &len, 1);
        uint8_t skip[258];
        read(sock, skip, len + 2);
    } else if (conn_resp[3] == SOCKS5_ATYP_IPV6) {
        uint8_t skip[18];
        read(sock, skip, 18);
    }

    LOGD("SOCKS5 connected to %s:%d", dest_host, dest_port);
    return sock;
}

// Send TCP packet to TUN
int send_tcp_packet(tun2socks_ctx_t *ctx, tcp_connection_t *conn, uint8_t flags,
                    uint8_t *data, size_t data_len) {
    uint8_t packet[TUN2SOCKS_MTU];
    size_t packet_len = 0;

    // IP header
    ip_header_t *ip = (ip_header_t *)packet;
    ip->version_ihl = 0x45; // IPv4, 20 bytes header
    ip->tos = 0;
    ip->identification = htons(rand() & 0xFFFF);
    ip->flags_offset = htons(0x4000); // Don't fragment
    ip->ttl = 64;
    ip->protocol = IPPROTO_TCP;
    ip->src_addr = conn->dst_addr; // Swap src/dst
    ip->dst_addr = conn->src_addr;
    ip->checksum = 0;

    packet_len = 20;

    // TCP header
    tcp_header_t *tcp = (tcp_header_t *)(packet + packet_len);
    tcp->src_port = conn->dst_port; // Swap src/dst
    tcp->dst_port = conn->src_port;
    tcp->seq = htonl(conn->local_seq);
    tcp->ack = htonl(conn->local_ack);
    tcp->data_offset = 0x50; // 20 bytes header
    tcp->flags = flags;
    tcp->window = htons(conn->local_window);
    tcp->checksum = 0;
    tcp->urgent_ptr = 0;

    packet_len += 20;

    // Data
    if (data != NULL && data_len > 0) {
        memcpy(packet + packet_len, data, data_len);
        packet_len += data_len;
    }

    // Set lengths
    ip->total_length = htons(packet_len);

    // Calculate checksums
    ip->checksum = ip_checksum(ip, 20);
    tcp->checksum = tcp_checksum(ip, tcp, data, data_len);

    // Write to TUN
    ssize_t written = write(ctx->tun_fd, packet, packet_len);
    if (written < 0) {
        LOGE("Failed to write to TUN: %s", strerror(errno));
        return -1;
    }

    ctx->packets_out++;
    ctx->bytes_out += written;

    return 0;
}

// Send TCP RST
int send_tcp_rst(tun2socks_ctx_t *ctx, ip_header_t *orig_ip, tcp_header_t *orig_tcp) {
    uint8_t packet[40];

    // IP header
    ip_header_t *ip = (ip_header_t *)packet;
    ip->version_ihl = 0x45;
    ip->tos = 0;
    ip->total_length = htons(40);
    ip->identification = htons(rand() & 0xFFFF);
    ip->flags_offset = htons(0x4000);
    ip->ttl = 64;
    ip->protocol = IPPROTO_TCP;
    ip->src_addr = orig_ip->dst_addr;
    ip->dst_addr = orig_ip->src_addr;
    ip->checksum = 0;

    // TCP header
    tcp_header_t *tcp = (tcp_header_t *)(packet + 20);
    tcp->src_port = orig_tcp->dst_port;
    tcp->dst_port = orig_tcp->src_port;
    tcp->seq = orig_tcp->ack;
    tcp->ack = htonl(ntohl(orig_tcp->seq) + 1);
    tcp->data_offset = 0x50;
    tcp->flags = TCP_FLAG_RST | TCP_FLAG_ACK;
    tcp->window = 0;
    tcp->checksum = 0;
    tcp->urgent_ptr = 0;

    ip->checksum = ip_checksum(ip, 20);
    tcp->checksum = tcp_checksum(ip, tcp, NULL, 0);

    write(ctx->tun_fd, packet, 40);
    return 0;
}

// Process incoming TCP packet from TUN
int process_tcp_packet(tun2socks_ctx_t *ctx, ip_header_t *ip, uint8_t *payload, size_t payload_len) {
    if (payload_len < 20) {
        return -1;
    }

    tcp_header_t *tcp = (tcp_header_t *)payload;
    uint8_t header_len = (tcp->data_offset >> 4) * 4;
    uint8_t *data = payload + header_len;
    size_t data_len = payload_len - header_len;

    uint16_t src_port = ntohs(tcp->src_port);
    uint16_t dst_port = ntohs(tcp->dst_port);
    uint32_t seq = ntohl(tcp->seq);
    uint32_t ack = ntohl(tcp->ack);
    uint8_t flags = tcp->flags;

    // Find or create connection
    tcp_connection_t *conn = find_tcp_connection(ctx, ip->src_addr, src_port,
                                                  ip->dst_addr, dst_port);

    // Handle SYN - new connection
    if (flags & TCP_FLAG_SYN) {
        if (conn != NULL) {
            // Reset existing connection
            destroy_tcp_connection(ctx, conn);
        }

        conn = create_tcp_connection(ctx, ip->src_addr, src_port,
                                      ip->dst_addr, dst_port);
        if (conn == NULL) {
            send_tcp_rst(ctx, ip, tcp);
            return -1;
        }

        // Connect to SOCKS proxy
        char dst_str[INET_ADDRSTRLEN];
        inet_ntop(AF_INET, &ip->dst_addr, dst_str, sizeof(dst_str));

        conn->socks_fd = socks5_connect(&ctx->socks_config, dst_str, dst_port);
        if (conn->socks_fd < 0) {
            LOGW("Failed to connect to %s:%d via SOCKS", dst_str, dst_port);
            send_tcp_rst(ctx, ip, tcp);
            destroy_tcp_connection(ctx, conn);
            return -1;
        }

        // Set non-blocking
        int flags_fd = fcntl(conn->socks_fd, F_GETFL, 0);
        fcntl(conn->socks_fd, F_SETFL, flags_fd | O_NONBLOCK);

        conn->socks_connected = true;
        conn->remote_seq = seq;
        conn->local_seq = rand();
        conn->local_ack = seq + 1;
        conn->remote_window = ntohs(tcp->window);
        conn->state = TCP_STATE_SYN_RECEIVED;

        // Send SYN-ACK
        send_tcp_packet(ctx, conn, TCP_FLAG_SYN | TCP_FLAG_ACK, NULL, 0);
        conn->local_seq++;

        LOGD("TCP SYN: %s:%d", dst_str, dst_port);
        return 0;
    }

    if (conn == NULL) {
        // No connection for this packet
        send_tcp_rst(ctx, ip, tcp);
        return -1;
    }

    conn->last_activity = time(NULL);

    // Handle ACK
    if (flags & TCP_FLAG_ACK) {
        if (conn->state == TCP_STATE_SYN_RECEIVED) {
            conn->state = TCP_STATE_ESTABLISHED;
            LOGD("TCP ESTABLISHED");
        }
    }

    // Handle data
    if (data_len > 0 && conn->state == TCP_STATE_ESTABLISHED) {
        // Forward to SOCKS
        if (conn->socks_fd >= 0) {
            ssize_t sent = write(conn->socks_fd, data, data_len);
            if (sent < 0 && errno != EAGAIN && errno != EWOULDBLOCK) {
                LOGE("Failed to send to SOCKS: %s", strerror(errno));
                send_tcp_rst(ctx, ip, tcp);
                destroy_tcp_connection(ctx, conn);
                return -1;
            }
        }

        // Update ack
        conn->local_ack = seq + data_len;

        // Send ACK
        send_tcp_packet(ctx, conn, TCP_FLAG_ACK, NULL, 0);
    }

    // Handle FIN
    if (flags & TCP_FLAG_FIN) {
        conn->local_ack = seq + 1;

        if (conn->state == TCP_STATE_ESTABLISHED) {
            conn->state = TCP_STATE_CLOSE_WAIT;
            // Send ACK
            send_tcp_packet(ctx, conn, TCP_FLAG_ACK, NULL, 0);
            // Send FIN
            send_tcp_packet(ctx, conn, TCP_FLAG_FIN | TCP_FLAG_ACK, NULL, 0);
            conn->local_seq++;
            conn->state = TCP_STATE_LAST_ACK;
        } else if (conn->state == TCP_STATE_FIN_WAIT_1 || conn->state == TCP_STATE_FIN_WAIT_2) {
            send_tcp_packet(ctx, conn, TCP_FLAG_ACK, NULL, 0);
            conn->state = TCP_STATE_TIME_WAIT;
        }

        if (conn->state == TCP_STATE_LAST_ACK || conn->state == TCP_STATE_TIME_WAIT) {
            destroy_tcp_connection(ctx, conn);
        }
    }

    // Handle RST
    if (flags & TCP_FLAG_RST) {
        destroy_tcp_connection(ctx, conn);
    }

    return 0;
}

// Process incoming UDP packet
int process_udp_packet(tun2socks_ctx_t *ctx, ip_header_t *ip, uint8_t *payload, size_t payload_len) {
    if (payload_len < 8) {
        return -1;
    }

    udp_header_t *udp = (udp_header_t *)payload;
    uint8_t *data = payload + 8;
    size_t data_len = payload_len - 8;

    uint16_t src_port = ntohs(udp->src_port);
    uint16_t dst_port = ntohs(udp->dst_port);

    char dst_str[INET_ADDRSTRLEN];
    inet_ntop(AF_INET, &ip->dst_addr, dst_str, sizeof(dst_str));

    // For DNS (port 53), forward directly or through SOCKS UDP associate
    // For simplicity, we'll forward DNS via UDP socket directly to configured DNS
    if (dst_port == 53 && ctx->dns_addr[0] != '\0') {
        int sock = socket(AF_INET, SOCK_DGRAM, 0);
        if (sock < 0) {
            return -1;
        }

        struct sockaddr_in dns_addr;
        memset(&dns_addr, 0, sizeof(dns_addr));
        dns_addr.sin_family = AF_INET;
        dns_addr.sin_port = htons(53);
        inet_pton(AF_INET, ctx->dns_addr, &dns_addr.sin_addr);

        sendto(sock, data, data_len, 0, (struct sockaddr *)&dns_addr, sizeof(dns_addr));

        // Wait for response
        struct pollfd pfd;
        pfd.fd = sock;
        pfd.events = POLLIN;
        if (poll(&pfd, 1, 5000) > 0) {
            uint8_t response[512];
            ssize_t resp_len = recv(sock, response, sizeof(response), 0);
            if (resp_len > 0) {
                // Send response back to TUN
                uint8_t packet[TUN2SOCKS_MTU];

                ip_header_t *resp_ip = (ip_header_t *)packet;
                resp_ip->version_ihl = 0x45;
                resp_ip->tos = 0;
                resp_ip->identification = htons(rand() & 0xFFFF);
                resp_ip->flags_offset = 0;
                resp_ip->ttl = 64;
                resp_ip->protocol = IPPROTO_UDP;
                resp_ip->src_addr = ip->dst_addr;
                resp_ip->dst_addr = ip->src_addr;
                resp_ip->checksum = 0;

                udp_header_t *resp_udp = (udp_header_t *)(packet + 20);
                resp_udp->src_port = udp->dst_port;
                resp_udp->dst_port = udp->src_port;
                resp_udp->length = htons(8 + resp_len);
                resp_udp->checksum = 0;

                memcpy(packet + 28, response, resp_len);

                size_t packet_len = 28 + resp_len;
                resp_ip->total_length = htons(packet_len);
                resp_ip->checksum = ip_checksum(resp_ip, 20);
                resp_udp->checksum = udp_checksum(resp_ip, resp_udp, response, resp_len);

                write(ctx->tun_fd, packet, packet_len);
            }
        }

        close(sock);
    }

    return 0;
}

// Process IP packet from TUN
int process_ip_packet(tun2socks_ctx_t *ctx, uint8_t *packet, size_t len) {
    if (len < 20) {
        return -1;
    }

    ip_header_t *ip = (ip_header_t *)packet;
    uint8_t version = (ip->version_ihl >> 4) & 0x0F;
    uint8_t ihl = (ip->version_ihl & 0x0F) * 4;

    if (version != 4) {
        // Only handle IPv4
        return -1;
    }

    if (len < ihl) {
        return -1;
    }

    uint8_t *payload = packet + ihl;
    size_t payload_len = len - ihl;

    ctx->packets_in++;
    ctx->bytes_in += len;

    switch (ip->protocol) {
        case IPPROTO_TCP:
            return process_tcp_packet(ctx, ip, payload, payload_len);
        case IPPROTO_UDP:
            return process_udp_packet(ctx, ip, payload, payload_len);
        case IPPROTO_ICMP:
            // Ignore ICMP for now
            return 0;
        default:
            return -1;
    }
}

// Check for data from SOCKS connections and send to TUN
static void process_socks_data(tun2socks_ctx_t *ctx) {
    tcp_connection_t *conn = ctx->tcp_connections;
    while (conn != NULL) {
        tcp_connection_t *next = conn->next;

        if (conn->socks_fd >= 0 && conn->state == TCP_STATE_ESTABLISHED) {
            struct pollfd pfd;
            pfd.fd = conn->socks_fd;
            pfd.events = POLLIN;

            if (poll(&pfd, 1, 0) > 0 && (pfd.revents & POLLIN)) {
                uint8_t buffer[4096];
                ssize_t len = read(conn->socks_fd, buffer, sizeof(buffer));

                if (len > 0) {
                    // Send to TUN
                    send_tcp_packet(ctx, conn, TCP_FLAG_ACK | TCP_FLAG_PSH, buffer, len);
                    conn->local_seq += len;
                    conn->last_activity = time(NULL);
                } else if (len == 0) {
                    // Connection closed by remote
                    send_tcp_packet(ctx, conn, TCP_FLAG_FIN | TCP_FLAG_ACK, NULL, 0);
                    conn->local_seq++;
                    conn->state = TCP_STATE_FIN_WAIT_1;
                } else if (errno != EAGAIN && errno != EWOULDBLOCK) {
                    // Error
                    tcp_connection_t tmp;
                    tmp.src_addr = conn->dst_addr;
                    tmp.src_port = conn->dst_port;
                    tmp.dst_addr = conn->src_addr;
                    tmp.dst_port = conn->src_port;
                    // Just close the connection
                    destroy_tcp_connection(ctx, conn);
                }
            }

            // Check for timeout
            if (time(NULL) - conn->last_activity > TUN2SOCKS_IDLE_TIMEOUT) {
                LOGD("Connection timeout");
                destroy_tcp_connection(ctx, conn);
            }
        }

        conn = next;
    }
}

// Main run loop
int tun2socks_run(tun2socks_ctx_t *ctx) {
    ctx->running = true;

    uint8_t buffer[TUN2SOCKS_BUFFER_SIZE];
    struct pollfd pfd;
    pfd.fd = ctx->tun_fd;
    pfd.events = POLLIN;

    LOGI("tun2socks running");

    while (ctx->running) {
        int ret = poll(&pfd, 1, 100);

        if (ret < 0) {
            if (errno == EINTR) continue;
            LOGE("poll error: %s", strerror(errno));
            break;
        }

        if (ret > 0 && (pfd.revents & POLLIN)) {
            ssize_t len = read(ctx->tun_fd, buffer, sizeof(buffer));
            if (len > 0) {
                process_ip_packet(ctx, buffer, len);
            } else if (len < 0 && errno != EAGAIN && errno != EWOULDBLOCK) {
                LOGE("TUN read error: %s", strerror(errno));
                break;
            }
        }

        // Process data from SOCKS connections
        process_socks_data(ctx);
    }

    LOGI("tun2socks stopped (packets: in=%lu out=%lu, bytes: in=%lu out=%lu)",
         ctx->packets_in, ctx->packets_out, ctx->bytes_in, ctx->bytes_out);

    return 0;
}
