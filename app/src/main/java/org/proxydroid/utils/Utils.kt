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

import android.content.Context
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * VPN-mode runtime state. The pre-VPN root/iptables helpers
 * (`runRootCommand`, `checkRoot`, `runScript`, `copyAssets`, etc.) were
 * removed when ProxyDroid went VPN-first.
 *
 * State is exposed both as plain getters (for legacy Java / widget callers)
 * and as [StateFlow] (for Compose / coroutine collectors).
 */
object Utils {
    private const val TAG = "ProxyDroid"

    private val _working = MutableStateFlow(false)
    val working: StateFlow<Boolean> = _working.asStateFlow()

    private val _connecting = MutableStateFlow(false)
    val connecting: StateFlow<Boolean> = _connecting.asStateFlow()

    @JvmStatic
    fun isWorking(): Boolean = _working.value

    @JvmStatic
    fun setWorking(working: Boolean) {
        _working.value = working
    }

    @JvmStatic
    fun isConnecting(): Boolean = _connecting.value

    @JvmStatic
    fun setConnecting(connecting: Boolean) {
        _connecting.value = connecting
    }

    @JvmStatic
    fun getAppIcon(context: Context, uid: Int): Drawable? {
        val pm = context.packageManager
        val packages = pm.getPackagesForUid(uid) ?: return null
        if (packages.isEmpty()) return null
        return try {
            val appInfo = pm.getApplicationInfo(packages[0], 0)
            pm.getApplicationIcon(appInfo)
        } catch (e: PackageManager.NameNotFoundException) {
            Log.e(TAG, "Error getting app icon", e)
            null
        }
    }
}
