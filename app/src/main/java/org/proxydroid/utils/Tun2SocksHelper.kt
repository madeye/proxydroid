/* proxydroid - Global / Individual Proxy App for Android
 * Copyright (C) 2011 Max Lv <max.c.lv@gmail.com>
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package org.proxydroid.utils

import android.util.Log

class Tun2SocksHelper {
    private var running = false

    companion object {
        private const val TAG = "Tun2SocksHelper"

        init {
            System.loadLibrary("tun2socks")
        }
    }

    @Synchronized
    fun start(
        tunFd: Int,
        mtu: Int,
        socksHost: String,
        socksPort: Int,
        socksUser: String?,
        socksPassword: String?
    ): Boolean {
        if (running) {
            Log.w(TAG, "tun2socks already running")
            return false
        }
        val result = nativeStart(tunFd, mtu, socksHost, socksPort, socksUser ?: "", socksPassword ?: "")
        running = result == 0
        return running
    }

    @Synchronized
    fun stop() {
        if (!running) {
            Log.w(TAG, "tun2socks not running")
            return
        }
        nativeStop()
        running = false
    }

    fun isRunning(): Boolean = running

    private external fun nativeStart(
        tunFd: Int,
        mtu: Int,
        socksHost: String,
        socksPort: Int,
        socksUser: String,
        socksPassword: String
    ): Int

    private external fun nativeStop()
}
