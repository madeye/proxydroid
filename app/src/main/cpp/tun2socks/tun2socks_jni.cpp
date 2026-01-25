/*
 * JNI wrapper for tun2socks functionality
 */

#include <jni.h>
#include <android/log.h>
#include <string>
#include <cstring>

extern "C" {
#include "core/tun2socks.h"
}

#define LOG_TAG "tun2socks-jni"
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, LOG_TAG, __VA_ARGS__)
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

// Global context
static tun2socks_ctx_t *g_ctx = nullptr;

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

    if (g_ctx != nullptr) {
        LOGW("tun2socks already running");
        return -1;
    }

    const char *tunAddrStr = env->GetStringUTFChars(tunAddr, nullptr);
    const char *tunGatewayStr = env->GetStringUTFChars(tunGateway, nullptr);
    const char *proxyUrlStr = env->GetStringUTFChars(proxyUrl, nullptr);
    const char *dnsAddrStr = env->GetStringUTFChars(dnsAddr, nullptr);

    LOGI("Starting tun2socks");
    LOGI("  TUN FD: %d", tunFd);
    LOGI("  MTU: %d", mtu);
    LOGI("  TUN Address: %s", tunAddrStr);
    LOGI("  TUN Gateway: %s", tunGatewayStr);
    LOGI("  Proxy URL: %s", proxyUrlStr);
    LOGI("  DNS: %s", dnsAddrStr);

    // Create context
    g_ctx = tun2socks_create(tunFd, mtu, tunAddrStr, proxyUrlStr, dnsAddrStr);

    env->ReleaseStringUTFChars(tunAddr, tunAddrStr);
    env->ReleaseStringUTFChars(tunGateway, tunGatewayStr);
    env->ReleaseStringUTFChars(proxyUrl, proxyUrlStr);
    env->ReleaseStringUTFChars(dnsAddr, dnsAddrStr);

    if (g_ctx == nullptr) {
        LOGE("Failed to create tun2socks context");
        return -1;
    }

    // Run (blocking)
    int result = tun2socks_run(g_ctx);

    // Cleanup
    tun2socks_destroy(g_ctx);
    g_ctx = nullptr;

    LOGI("tun2socks exited with code: %d", result);
    return result;
}

JNIEXPORT void JNICALL
Java_org_proxydroid_utils_Tun2SocksHelper_stopTun2Socks(
        JNIEnv *env,
        jclass clazz) {
    LOGI("Stopping tun2socks");
    if (g_ctx != nullptr) {
        tun2socks_stop(g_ctx);
    }
}

} // extern "C"
