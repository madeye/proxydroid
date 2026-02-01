/*
 * tun2socks JNI wrapper
 * Copyright (C) 2011 Max Lv <max.c.lv@gmail.com>
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

#include <jni.h>
#include <string>
#include <android/log.h>

#include "core/tun2socks.h"

#define LOG_TAG "tun2socks-jni"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

static tun2socks_config_t g_config;
static char g_socks_host[256];
static char g_socks_user[256];
static char g_socks_password[256];

extern "C" {

JNIEXPORT jint JNICALL
Java_org_proxydroid_utils_Tun2SocksHelper_nativeStart(
        JNIEnv *env,
        jobject thiz,
        jint tun_fd,
        jint mtu,
        jstring socks_host,
        jint socks_port,
        jstring socks_user,
        jstring socks_password) {

    LOGI("nativeStart called: tun_fd=%d, mtu=%d, port=%d", tun_fd, mtu, socks_port);

    // Get SOCKS host
    const char *host = env->GetStringUTFChars(socks_host, nullptr);
    if (host == nullptr) {
        LOGE("Failed to get SOCKS host string");
        return -1;
    }
    strncpy(g_socks_host, host, sizeof(g_socks_host) - 1);
    g_socks_host[sizeof(g_socks_host) - 1] = '\0';
    env->ReleaseStringUTFChars(socks_host, host);

    // Get SOCKS user (optional)
    g_socks_user[0] = '\0';
    if (socks_user != nullptr) {
        const char *user = env->GetStringUTFChars(socks_user, nullptr);
        if (user != nullptr) {
            strncpy(g_socks_user, user, sizeof(g_socks_user) - 1);
            g_socks_user[sizeof(g_socks_user) - 1] = '\0';
            env->ReleaseStringUTFChars(socks_user, user);
        }
    }

    // Get SOCKS password (optional)
    g_socks_password[0] = '\0';
    if (socks_password != nullptr) {
        const char *password = env->GetStringUTFChars(socks_password, nullptr);
        if (password != nullptr) {
            strncpy(g_socks_password, password, sizeof(g_socks_password) - 1);
            g_socks_password[sizeof(g_socks_password) - 1] = '\0';
            env->ReleaseStringUTFChars(socks_password, password);
        }
    }

    // Configure tun2socks
    memset(&g_config, 0, sizeof(g_config));
    g_config.tun_fd = tun_fd;
    g_config.mtu = mtu;
    g_config.socks_host = g_socks_host;
    g_config.socks_port = socks_port;
    g_config.socks_user = strlen(g_socks_user) > 0 ? g_socks_user : nullptr;
    g_config.socks_password = strlen(g_socks_password) > 0 ? g_socks_password : nullptr;

    LOGI("Starting tun2socks with host=%s, port=%d", g_socks_host, socks_port);

    // Start tun2socks (this will block)
    int result = tun2socks_start(&g_config);

    LOGI("tun2socks_start returned: %d", result);
    return result;
}

JNIEXPORT void JNICALL
Java_org_proxydroid_utils_Tun2SocksHelper_nativeStop(
        JNIEnv *env,
        jobject thiz) {

    LOGI("nativeStop called");
    tun2socks_stop();
}

} // extern "C"
