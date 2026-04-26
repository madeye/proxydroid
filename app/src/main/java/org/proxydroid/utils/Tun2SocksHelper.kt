/* proxydroid - Global / Individual Proxy App for Android
 * Copyright (C) 2011 Max Lv <max.c.lv@gmail.com>
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package org.proxydroid.utils

import android.net.VpnService
import android.util.Log

class Tun2SocksHelper {
    @Volatile private var running = false

    companion object {
        private const val TAG = "Tun2SocksHelper"

        init {
            System.loadLibrary("proxydroid_tun2socks")
        }
    }

    /**
     * Starts tun2socks. The Rust side spawns its own tokio runtime and returns
     * once the runtime has been handed the tun fd, so this call is non-blocking.
     * Returns true on success, false if an instance is already running or the
     * native side reported failure.
     */
    @Synchronized
    fun start(
        vpnService: VpnService,
        tunFd: Int,
        mtu: Int,
        proxyType: String,
        socksHost: String,
        socksPort: Int,
        socksUser: String?,
        socksPassword: String?,
    ): Boolean {
        if (running) {
            Log.w(TAG, "tun2socks already running")
            return false
        }
        val rc = try {
            nativeStart(
                vpnService,
                tunFd,
                mtu,
                proxyType,
                socksHost,
                socksPort,
                socksUser ?: "",
                socksPassword ?: "",
            )
        } catch (t: Throwable) {
            Log.e(TAG, "nativeStart threw", t)
            return false
        }
        if (rc != 0) {
            Log.e(TAG, "nativeStart failed rc=$rc")
            return false
        }
        running = true
        return true
    }

    @Synchronized
    fun stop() {
        if (!running) return
        try {
            nativeStop()
        } catch (t: Throwable) {
            Log.e(TAG, "nativeStop threw", t)
        }
        running = false
    }

    fun isRunning(): Boolean = running

    private external fun nativeStart(
        vpnService: VpnService,
        tunFd: Int,
        mtu: Int,
        proxyType: String,
        socksHost: String,
        socksPort: Int,
        socksUser: String,
        socksPassword: String,
    ): Int

    private external fun nativeStop()
}
