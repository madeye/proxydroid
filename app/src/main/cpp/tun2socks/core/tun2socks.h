/*
 * tun2socks - Convert TUN traffic to SOCKS proxy
 * Core header file
 */

#ifndef TUN2SOCKS_H
#define TUN2SOCKS_H

#include <stdint.h>
#include <stdbool.h>
#include <netinet/in.h>

#ifdef __cplusplus
extern "C" {
#endif

// Configuration
#define TUN2SOCKS_MTU 1500
#define TUN2SOCKS_MAX_CONNECTIONS 1024
#define TUN2SOCKS_BUFFER_SIZE 65536
#define TUN2SOCKS_CONNECT_TIMEOUT 10
#define TUN2SOCKS_IDLE_TIMEOUT 300

// SOCKS5 constants
#define SOCKS5_VERSION 0x05
#define SOCKS5_AUTH_NONE 0x00
#define SOCKS5_AUTH_PASSWORD 0x02
#define SOCKS5_CMD_CONNECT 0x01
#define SOCKS5_CMD_UDP_ASSOCIATE 0x03
#define SOCKS5_ATYP_IPV4 0x01
#define SOCKS5_ATYP_DOMAIN 0x03
#define SOCKS5_ATYP_IPV6 0x04
#define SOCKS5_REP_SUCCESS 0x00

// IP protocol numbers
#define IPPROTO_ICMP 1
#define IPPROTO_TCP 6
#define IPPROTO_UDP 17

// TCP flags
#define TCP_FLAG_FIN 0x01
#define TCP_FLAG_SYN 0x02
#define TCP_FLAG_RST 0x04
#define TCP_FLAG_PSH 0x08
#define TCP_FLAG_ACK 0x10
#define TCP_FLAG_URG 0x20

// TCP states
typedef enum {
    TCP_STATE_CLOSED,
    TCP_STATE_SYN_RECEIVED,
    TCP_STATE_ESTABLISHED,
    TCP_STATE_FIN_WAIT_1,
    TCP_STATE_FIN_WAIT_2,
    TCP_STATE_CLOSING,
    TCP_STATE_TIME_WAIT,
    TCP_STATE_CLOSE_WAIT,
    TCP_STATE_LAST_ACK
} tcp_state_t;

// IP header structure
typedef struct __attribute__((packed)) {
    uint8_t version_ihl;
    uint8_t tos;
    uint16_t total_length;
    uint16_t identification;
    uint16_t flags_offset;
    uint8_t ttl;
    uint8_t protocol;
    uint16_t checksum;
    uint32_t src_addr;
    uint32_t dst_addr;
} ip_header_t;

// TCP header structure
typedef struct __attribute__((packed)) {
    uint16_t src_port;
    uint16_t dst_port;
    uint32_t seq;
    uint32_t ack;
    uint8_t data_offset;
    uint8_t flags;
    uint16_t window;
    uint16_t checksum;
    uint16_t urgent_ptr;
} tcp_header_t;

// UDP header structure
typedef struct __attribute__((packed)) {
    uint16_t src_port;
    uint16_t dst_port;
    uint16_t length;
    uint16_t checksum;
} udp_header_t;

// SOCKS5 configuration
typedef struct {
    char host[256];
    int port;
    char username[256];
    char password[256];
    bool auth_required;
} socks5_config_t;

// TCP connection structure
typedef struct tcp_connection {
    uint32_t src_addr;
    uint16_t src_port;
    uint32_t dst_addr;
    uint16_t dst_port;

    tcp_state_t state;

    uint32_t local_seq;
    uint32_t local_ack;
    uint32_t remote_seq;
    uint32_t remote_ack;

    uint16_t local_window;
    uint16_t remote_window;

    int socks_fd;
    bool socks_connected;

    uint8_t *send_buffer;
    size_t send_buffer_len;
    size_t send_buffer_cap;

    uint8_t *recv_buffer;
    size_t recv_buffer_len;
    size_t recv_buffer_cap;

    time_t last_activity;

    struct tcp_connection *next;
    struct tcp_connection *prev;
} tcp_connection_t;

// UDP session structure
typedef struct udp_session {
    uint32_t src_addr;
    uint16_t src_port;

    int socks_fd;
    struct sockaddr_in udp_relay;

    time_t last_activity;

    struct udp_session *next;
    struct udp_session *prev;
} udp_session_t;

// Tun2socks context
typedef struct {
    int tun_fd;
    int mtu;
    uint32_t tun_addr;
    uint32_t tun_netmask;
    char dns_addr[64];

    socks5_config_t socks_config;

    tcp_connection_t *tcp_connections;
    udp_session_t *udp_sessions;

    volatile bool running;

    // Statistics
    uint64_t packets_in;
    uint64_t packets_out;
    uint64_t bytes_in;
    uint64_t bytes_out;
} tun2socks_ctx_t;

// Function prototypes
tun2socks_ctx_t *tun2socks_create(int tun_fd, int mtu, const char *tun_addr,
                                   const char *proxy_url, const char *dns_addr);
void tun2socks_destroy(tun2socks_ctx_t *ctx);
int tun2socks_run(tun2socks_ctx_t *ctx);
void tun2socks_stop(tun2socks_ctx_t *ctx);

// Internal functions
int process_ip_packet(tun2socks_ctx_t *ctx, uint8_t *packet, size_t len);
int process_tcp_packet(tun2socks_ctx_t *ctx, ip_header_t *ip, uint8_t *payload, size_t payload_len);
int process_udp_packet(tun2socks_ctx_t *ctx, ip_header_t *ip, uint8_t *payload, size_t payload_len);

tcp_connection_t *find_tcp_connection(tun2socks_ctx_t *ctx, uint32_t src, uint16_t sport,
                                       uint32_t dst, uint16_t dport);
tcp_connection_t *create_tcp_connection(tun2socks_ctx_t *ctx, uint32_t src, uint16_t sport,
                                         uint32_t dst, uint16_t dport);
void destroy_tcp_connection(tun2socks_ctx_t *ctx, tcp_connection_t *conn);

int socks5_connect(socks5_config_t *config, const char *dest_host, int dest_port);
int socks5_udp_associate(socks5_config_t *config, struct sockaddr_in *relay_addr);

int send_tcp_packet(tun2socks_ctx_t *ctx, tcp_connection_t *conn, uint8_t flags,
                    uint8_t *data, size_t data_len);
int send_tcp_rst(tun2socks_ctx_t *ctx, ip_header_t *ip, tcp_header_t *tcp);

uint16_t ip_checksum(void *data, size_t len);
uint16_t tcp_checksum(ip_header_t *ip, tcp_header_t *tcp, uint8_t *data, size_t data_len);
uint16_t udp_checksum(ip_header_t *ip, udp_header_t *udp, uint8_t *data, size_t data_len);

#ifdef __cplusplus
}
#endif

#endif // TUN2SOCKS_H
