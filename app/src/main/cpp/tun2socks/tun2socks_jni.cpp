/*
 * JNI wrapper for tun2socks functionality
 * This provides a simple interface to redirect TUN traffic through a SOCKS proxy
 */

#include <jni.h>
#include <android/log.h>
#include <string>
#include <thread>
#include <atomic>
#include <unistd.h>
#include <sys/socket.h>
#include <netinet/in.h>
#include <arpa/inet.h>
#include <linux/if.h>
#include <linux/if_tun.h>
#include <sys/ioctl.h>
#include <fcntl.h>
#include <errno.h>
#include <cstring>
#include <poll.h>

#define LOG_TAG "tun2socks-jni"
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, LOG_TAG, __VA_ARGS__)
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

// Global state
static std::atomic<bool> g_running(false);
static int g_tun_fd = -1;
static int g_mtu = 1500;
static std::string g_tun_addr;
static std::string g_tun_gateway;
static std::string g_proxy_url;
static std::string g_dns_addr;

// Forward declarations
static void tun2socks_main_loop();
static int parse_socks_url(const std::string& url, std::string& host, int& port,
                           std::string& user, std::string& pass);
static int connect_to_socks(const std::string& host, int port,
                            const std::string& user, const std::string& pass,
                            const std::string& dest_host, int dest_port);

extern "C" {

JNIEXPORT jint JNICALL
Java_org_proxydroid_utils_Tun2SocksHelper_startTun2Socks(
        JNIEnv *env,
        jclass clazz,
        jint tunFd,
        jint mtu,
        jstring tunAddr,
        jstring tunGateway,
        jstring proxyUrl,
        jstring dnsAddr) {

    if (g_running.load()) {
        LOGW("tun2socks already running");
        return -1;
    }

    g_tun_fd = tunFd;
    g_mtu = mtu;

    const char *tunAddrStr = env->GetStringUTFChars(tunAddr, nullptr);
    const char *tunGatewayStr = env->GetStringUTFChars(tunGateway, nullptr);
    const char *proxyUrlStr = env->GetStringUTFChars(proxyUrl, nullptr);
    const char *dnsAddrStr = env->GetStringUTFChars(dnsAddr, nullptr);

    g_tun_addr = tunAddrStr;
    g_tun_gateway = tunGatewayStr;
    g_proxy_url = proxyUrlStr;
    g_dns_addr = dnsAddrStr;

    env->ReleaseStringUTFChars(tunAddr, tunAddrStr);
    env->ReleaseStringUTFChars(tunGateway, tunGatewayStr);
    env->ReleaseStringUTFChars(proxyUrl, proxyUrlStr);
    env->ReleaseStringUTFChars(dnsAddr, dnsAddrStr);

    LOGI("Starting tun2socks");
    LOGI("  TUN FD: %d", g_tun_fd);
    LOGI("  MTU: %d", g_mtu);
    LOGI("  TUN Address: %s", g_tun_addr.c_str());
    LOGI("  TUN Gateway: %s", g_tun_gateway.c_str());
    LOGI("  Proxy URL: %s", g_proxy_url.c_str());
    LOGI("  DNS: %s", g_dns_addr.c_str());

    g_running.store(true);

    // Run main loop
    tun2socks_main_loop();

    g_running.store(false);

    LOGI("tun2socks stopped");
    return 0;
}

JNIEXPORT void JNICALL
Java_org_proxydroid_utils_Tun2SocksHelper_stopTun2Socks(
        JNIEnv *env,
        jclass clazz) {
    LOGI("Stopping tun2socks");
    g_running.store(false);
}

} // extern "C"

// IP header structure
struct ip_header {
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
};

// TCP header structure
struct tcp_header {
    uint16_t src_port;
    uint16_t dst_port;
    uint32_t seq;
    uint32_t ack;
    uint8_t data_offset;
    uint8_t flags;
    uint16_t window;
    uint16_t checksum;
    uint16_t urgent_ptr;
};

// Connection tracking entry
struct connection {
    int socks_fd;
    uint32_t src_addr;
    uint16_t src_port;
    uint32_t dst_addr;
    uint16_t dst_port;
    uint32_t seq;
    uint32_t ack;
    bool established;
};

#include <map>
#include <mutex>

static std::map<uint64_t, connection> g_connections;
static std::mutex g_conn_mutex;

static uint64_t make_conn_key(uint32_t src, uint16_t sport, uint32_t dst, uint16_t dport) {
    return ((uint64_t)src << 32) | ((uint64_t)sport << 16) | ((uint64_t)dport);
}

static int parse_socks_url(const std::string& url, std::string& host, int& port,
                           std::string& user, std::string& pass) {
    // Format: socks5://[user:pass@]host:port
    std::string s = url;

    // Remove protocol prefix
    size_t proto_end = s.find("://");
    if (proto_end != std::string::npos) {
        s = s.substr(proto_end + 3);
    }

    // Check for auth
    size_t at_pos = s.find('@');
    if (at_pos != std::string::npos) {
        std::string auth = s.substr(0, at_pos);
        s = s.substr(at_pos + 1);

        size_t colon = auth.find(':');
        if (colon != std::string::npos) {
            user = auth.substr(0, colon);
            pass = auth.substr(colon + 1);
        } else {
            user = auth;
        }
    }

    // Parse host:port
    size_t colon = s.rfind(':');
    if (colon != std::string::npos) {
        host = s.substr(0, colon);
        port = std::stoi(s.substr(colon + 1));
    } else {
        host = s;
        port = 1080;
    }

    return 0;
}

static int connect_to_socks(const std::string& host, int port,
                            const std::string& user, const std::string& pass,
                            const std::string& dest_host, int dest_port) {
    int sock = socket(AF_INET, SOCK_STREAM, 0);
    if (sock < 0) {
        LOGE("Failed to create socket: %s", strerror(errno));
        return -1;
    }

    // Set non-blocking temporarily for connect timeout
    int flags = fcntl(sock, F_GETFL, 0);
    fcntl(sock, F_SETFL, flags | O_NONBLOCK);

    struct sockaddr_in addr;
    memset(&addr, 0, sizeof(addr));
    addr.sin_family = AF_INET;
    addr.sin_port = htons(port);
    inet_pton(AF_INET, host.c_str(), &addr.sin_addr);

    int ret = connect(sock, (struct sockaddr*)&addr, sizeof(addr));
    if (ret < 0 && errno != EINPROGRESS) {
        LOGE("Failed to connect to SOCKS server: %s", strerror(errno));
        close(sock);
        return -1;
    }

    // Wait for connection with timeout
    struct pollfd pfd;
    pfd.fd = sock;
    pfd.events = POLLOUT;
    ret = poll(&pfd, 1, 10000);
    if (ret <= 0) {
        LOGE("SOCKS connect timeout");
        close(sock);
        return -1;
    }

    // Set back to blocking
    fcntl(sock, F_SETFL, flags);

    // SOCKS5 handshake
    uint8_t handshake[3] = {0x05, 0x01, 0x00}; // Version 5, 1 method, no auth
    if (!user.empty()) {
        handshake[1] = 0x02; // 2 methods
        handshake[2] = 0x02; // Username/password auth
        uint8_t handshake_auth[4] = {0x05, 0x02, 0x00, 0x02};
        write(sock, handshake_auth, 4);
    } else {
        write(sock, handshake, 3);
    }

    uint8_t response[2];
    read(sock, response, 2);

    if (response[0] != 0x05) {
        LOGE("Invalid SOCKS version in response");
        close(sock);
        return -1;
    }

    // Handle auth if needed
    if (response[1] == 0x02 && !user.empty()) {
        // Username/password auth
        std::vector<uint8_t> auth;
        auth.push_back(0x01); // Version
        auth.push_back(user.length());
        auth.insert(auth.end(), user.begin(), user.end());
        auth.push_back(pass.length());
        auth.insert(auth.end(), pass.begin(), pass.end());
        write(sock, auth.data(), auth.size());

        uint8_t auth_resp[2];
        read(sock, auth_resp, 2);
        if (auth_resp[1] != 0x00) {
            LOGE("SOCKS auth failed");
            close(sock);
            return -1;
        }
    } else if (response[1] != 0x00) {
        LOGE("SOCKS auth method not supported: %d", response[1]);
        close(sock);
        return -1;
    }

    // Send connect request
    std::vector<uint8_t> conn_req;
    conn_req.push_back(0x05); // Version
    conn_req.push_back(0x01); // Connect
    conn_req.push_back(0x00); // Reserved

    // Check if dest_host is IP or domain
    struct in_addr ip;
    if (inet_pton(AF_INET, dest_host.c_str(), &ip) == 1) {
        conn_req.push_back(0x01); // IPv4
        conn_req.push_back((ip.s_addr >> 0) & 0xFF);
        conn_req.push_back((ip.s_addr >> 8) & 0xFF);
        conn_req.push_back((ip.s_addr >> 16) & 0xFF);
        conn_req.push_back((ip.s_addr >> 24) & 0xFF);
    } else {
        conn_req.push_back(0x03); // Domain
        conn_req.push_back(dest_host.length());
        conn_req.insert(conn_req.end(), dest_host.begin(), dest_host.end());
    }

    conn_req.push_back((dest_port >> 8) & 0xFF);
    conn_req.push_back(dest_port & 0xFF);

    write(sock, conn_req.data(), conn_req.size());

    // Read response
    uint8_t conn_resp[10];
    ret = read(sock, conn_resp, 4);
    if (ret < 4 || conn_resp[1] != 0x00) {
        LOGE("SOCKS connect failed: %d", conn_resp[1]);
        close(sock);
        return -1;
    }

    // Skip rest of response based on address type
    if (conn_resp[3] == 0x01) {
        read(sock, conn_resp + 4, 6); // IPv4 + port
    } else if (conn_resp[3] == 0x03) {
        uint8_t len;
        read(sock, &len, 1);
        uint8_t buf[256];
        read(sock, buf, len + 2);
    } else if (conn_resp[3] == 0x04) {
        read(sock, conn_resp, 18); // IPv6 + port
    }

    LOGD("SOCKS connection established to %s:%d", dest_host.c_str(), dest_port);
    return sock;
}

static void tun2socks_main_loop() {
    std::string socks_host;
    int socks_port;
    std::string socks_user, socks_pass;

    parse_socks_url(g_proxy_url, socks_host, socks_port, socks_user, socks_pass);

    LOGI("SOCKS proxy: %s:%d", socks_host.c_str(), socks_port);

    uint8_t buffer[65536];
    struct pollfd pfd;
    pfd.fd = g_tun_fd;
    pfd.events = POLLIN;

    while (g_running.load()) {
        int ret = poll(&pfd, 1, 1000);
        if (ret <= 0) {
            continue;
        }

        ssize_t len = read(g_tun_fd, buffer, sizeof(buffer));
        if (len <= 0) {
            if (errno == EAGAIN || errno == EWOULDBLOCK) {
                continue;
            }
            LOGE("TUN read error: %s", strerror(errno));
            break;
        }

        // Parse IP header
        if (len < 20) continue;

        struct ip_header* ip = (struct ip_header*)buffer;
        uint8_t version = (ip->version_ihl >> 4) & 0x0F;
        uint8_t ihl = (ip->version_ihl & 0x0F) * 4;

        if (version != 4) continue; // Only handle IPv4 for now

        uint8_t protocol = ip->protocol;
        uint32_t src_addr = ip->src_addr;
        uint32_t dst_addr = ip->dst_addr;

        // Handle TCP
        if (protocol == 6 && len >= ihl + 20) {
            struct tcp_header* tcp = (struct tcp_header*)(buffer + ihl);
            uint16_t src_port = ntohs(tcp->src_port);
            uint16_t dst_port = ntohs(tcp->dst_port);
            uint8_t tcp_flags = tcp->flags;

            // Get destination as string
            char dst_str[INET_ADDRSTRLEN];
            inet_ntop(AF_INET, &dst_addr, dst_str, sizeof(dst_str));

            uint64_t conn_key = make_conn_key(src_addr, src_port, dst_addr, dst_port);

            // Handle SYN - new connection
            if (tcp_flags & 0x02) {
                LOGD("TCP SYN to %s:%d", dst_str, dst_port);

                // Connect through SOCKS
                int socks_fd = connect_to_socks(socks_host, socks_port,
                                                 socks_user, socks_pass,
                                                 dst_str, dst_port);
                if (socks_fd >= 0) {
                    std::lock_guard<std::mutex> lock(g_conn_mutex);
                    connection conn;
                    conn.socks_fd = socks_fd;
                    conn.src_addr = src_addr;
                    conn.src_port = src_port;
                    conn.dst_addr = dst_addr;
                    conn.dst_port = dst_port;
                    conn.seq = ntohl(tcp->seq);
                    conn.ack = 0;
                    conn.established = false;
                    g_connections[conn_key] = conn;
                }
            }
            // Handle data and other packets
            else {
                std::lock_guard<std::mutex> lock(g_conn_mutex);
                auto it = g_connections.find(conn_key);
                if (it != g_connections.end()) {
                    uint8_t data_offset = (tcp->data_offset >> 4) * 4;
                    int data_len = len - ihl - data_offset;

                    if (data_len > 0 && it->second.socks_fd >= 0) {
                        // Forward data to SOCKS connection
                        write(it->second.socks_fd, buffer + ihl + data_offset, data_len);
                    }

                    // Handle FIN
                    if (tcp_flags & 0x01) {
                        if (it->second.socks_fd >= 0) {
                            close(it->second.socks_fd);
                        }
                        g_connections.erase(it);
                    }
                }
            }
        }
        // Handle UDP
        else if (protocol == 17 && len >= ihl + 8) {
            // UDP handling - simplified, mainly for DNS
            uint16_t src_port = ntohs(*(uint16_t*)(buffer + ihl));
            uint16_t dst_port = ntohs(*(uint16_t*)(buffer + ihl + 2));

            char dst_str[INET_ADDRSTRLEN];
            inet_ntop(AF_INET, &dst_addr, dst_str, sizeof(dst_str));

            // DNS queries (port 53) - forward through proxy or direct
            if (dst_port == 53) {
                LOGD("DNS query to %s", dst_str);
                // TODO: Implement DNS-over-TCP through SOCKS or use configured DNS
            }
        }
    }

    // Cleanup connections
    std::lock_guard<std::mutex> lock(g_conn_mutex);
    for (auto& pair : g_connections) {
        if (pair.second.socks_fd >= 0) {
            close(pair.second.socks_fd);
        }
    }
    g_connections.clear();
}
