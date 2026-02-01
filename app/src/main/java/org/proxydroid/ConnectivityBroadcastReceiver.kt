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

package org.proxydroid

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkInfo
import android.net.wifi.WifiManager
import android.os.Handler
import android.os.Looper
import androidx.preference.PreferenceManager
import com.ksmaze.android.preference.ListPreferenceMultiSelect
import org.proxydroid.utils.Constraints
import org.proxydroid.utils.Utils

class ConnectivityBroadcastReceiver : BroadcastReceiver() {

    private val handler = Handler(Looper.getMainLooper())

    companion object {
        private const val TAG = "ConnectivityBroadcastReceiver"
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (Utils.isConnecting()) return
        if (intent.action != ConnectivityManager.CONNECTIVITY_ACTION) return

        handler.post {
            val manager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val networkInfo = manager.activeNetworkInfo

            if (networkInfo != null) {
                if (networkInfo.state == NetworkInfo.State.CONNECTING ||
                    networkInfo.state == NetworkInfo.State.DISCONNECTING ||
                    networkInfo.state == NetworkInfo.State.UNKNOWN
                ) {
                    return@post
                }
            } else {
                if (!Utils.isWorking()) return@post
            }

            val settings = PreferenceManager.getDefaultSharedPreferences(context)
            val profile = Profile()
            profile.getProfile(settings)

            // Store current settings first
            val oldProfile = settings.getString("profile", "1") ?: "1"
            settings.edit().putString(oldProfile, profile.toString()).apply()

            // Load all profiles
            val profileValues = settings.getString("profileValues", "")?.split("\\|".toRegex()) ?: emptyList()
            var curSSID: String? = null
            val lastSSID = settings.getString("lastSSID", "-1") ?: "-1"
            var autoConnect = false

            // Test on each profile
            for (profileId in profileValues) {
                if (profileId.isEmpty()) continue
                val profileString = settings.getString(profileId, "") ?: ""
                profile.decodeJson(profileString)
                curSSID = onlineSSID(context, profile.ssid, profile.excludedSsid)
                if (profile.isAutoConnect && curSSID != null) {
                    autoConnect = true
                    settings.edit().putString("profile", profileId).apply()
                    profile.setProfile(settings)
                    break
                }
            }

            if (networkInfo == null) {
                if (lastSSID != Constraints.ONLY_3G &&
                    lastSSID != Constraints.WIFI_AND_3G &&
                    lastSSID != Constraints.ONLY_WIFI
                ) {
                    if (Utils.isWorking()) {
                        context.stopService(Intent(context, ProxyDroidService::class.java))
                    }
                }
            } else {
                if (networkInfo.state != NetworkInfo.State.CONNECTED) return@post

                if (networkInfo.type == ConnectivityManager.TYPE_WIFI) {
                    if (lastSSID != "-1") {
                        val wm = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
                        val wInfo = wm.connectionInfo
                        if (wInfo != null) {
                            var current = wInfo.ssid
                            current = current?.replace("\"", "")
                            if (current != null && current != lastSSID) {
                                if (Utils.isWorking()) {
                                    context.stopService(Intent(context, ProxyDroidService::class.java))
                                }
                            }
                        }
                    }
                } else {
                    if (lastSSID != Constraints.ONLY_3G && lastSSID != Constraints.WIFI_AND_3G) {
                        if (Utils.isWorking()) {
                            context.stopService(Intent(context, ProxyDroidService::class.java))
                        }
                    }
                }
            }

            if (autoConnect) {
                if (!Utils.isWorking()) {
                    val pdr = ProxyDroidReceiver()
                    settings.edit().putString("lastSSID", curSSID).apply()
                    Utils.setConnecting(true)
                    pdr.onReceive(context, intent)
                }
            }
        }
    }

    fun onlineSSID(context: Context, ssid: String, excludedSsid: String): String? {
        val ssids = ListPreferenceMultiSelect.parseStoredValue(ssid) ?: return null
        val excludedSsids = ListPreferenceMultiSelect.parseStoredValue(excludedSsid)

        if (ssids.isEmpty()) return null

        val manager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val networkInfo = manager.activeNetworkInfo ?: return null

        if (networkInfo.type != ConnectivityManager.TYPE_WIFI) {
            for (item in ssids) {
                if (Constraints.WIFI_AND_3G == item) return item
                if (Constraints.ONLY_3G == item) return item
            }
            return null
        }

        val wm = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        val wInfo = wm.connectionInfo
        if (wInfo?.ssid == null) return null

        var current = wInfo.ssid
        if (current.isNullOrEmpty()) return null
        current = current.replace("\"", "")

        if (excludedSsids != null) {
            for (item in excludedSsids) {
                if (current == item) {
                    return null // Never connect proxy on excluded ssid
                }
            }
        }

        for (item in ssids) {
            if (Constraints.WIFI_AND_3G == item) return item
            if (Constraints.ONLY_WIFI == item) return item
            if (current == item) return item
        }
        return null
    }
}
