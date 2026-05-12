/* proxydroid - Global / Individual Proxy App for Android
 * Copyright (C) 2011 Max Lv <max.c.lv@gmail.com>
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package org.proxydroid

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkInfo
import android.net.wifi.WifiManager
import android.preference.PreferenceManager
import com.ksmaze.android.preference.ListPreferenceMultiSelect
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.proxydroid.utils.Constraints
import org.proxydroid.utils.ProxyController
import org.proxydroid.utils.Utils

class ConnectivityBroadcastReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (Utils.isConnecting()) return
        if (intent.action != ConnectivityManager.CONNECTIVITY_ACTION) return

        // BroadcastReceiver runs on the main thread; offload to IO with goAsync().
        val pending = goAsync()
        scope.launch {
            try {
                handle(context.applicationContext, intent)
            } finally {
                pending.finish()
            }
        }
    }

    private fun handle(context: Context, intent: Intent) {
        val manager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val networkInfo = manager.activeNetworkInfo

        if (networkInfo != null) {
            val state = networkInfo.state
            if (state == NetworkInfo.State.CONNECTING ||
                state == NetworkInfo.State.DISCONNECTING ||
                state == NetworkInfo.State.UNKNOWN
            ) return
        } else if (!Utils.isWorking()) {
            return
        }

        val settings = PreferenceManager.getDefaultSharedPreferences(context)
        val profile = Profile().apply { getProfile(settings) }

        val oldProfile = settings.getString("profile", "1") ?: "1"
        settings.edit().putString(oldProfile, profile.toString()).apply()

        val profileValues = settings.getString("profileValues", "")
            ?.split("|")?.filter { it.isNotEmpty() }
            .orEmpty()
        var curSSID: String? = null
        val lastSSID = settings.getString("lastSSID", "-1") ?: "-1"
        var autoConnect = false

        for (profileId in profileValues) {
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
                lastSSID != Constraints.ONLY_WIFI &&
                Utils.isWorking()
            ) {
                ProxyController.stop(context)
            }
        } else {
            if (networkInfo.state != NetworkInfo.State.CONNECTED) return

            if (networkInfo.type == ConnectivityManager.TYPE_WIFI) {
                if (lastSSID != "-1") {
                    val wm = context.applicationContext
                        .getSystemService(Context.WIFI_SERVICE) as WifiManager
                    val current = wm.connectionInfo?.ssid?.replace("\"", "")
                    if (current != null && current != lastSSID && Utils.isWorking()) {
                        ProxyController.stop(context)
                    }
                }
            } else if (lastSSID != Constraints.ONLY_3G &&
                lastSSID != Constraints.WIFI_AND_3G &&
                Utils.isWorking()
            ) {
                ProxyController.stop(context)
            }
        }

        if (autoConnect && !Utils.isWorking()) {
            settings.edit().putString("lastSSID", curSSID).apply()
            Utils.setConnecting(true)
            ProxyDroidReceiver().onReceive(context, intent)
        }
    }

    private fun onlineSSID(context: Context, ssid: String, excludedSsid: String): String? {
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

        val wm = context.applicationContext
            .getSystemService(Context.WIFI_SERVICE) as WifiManager
        val current = wm.connectionInfo?.ssid?.takeIf { it.isNotEmpty() }
            ?.replace("\"", "") ?: return null

        if (excludedSsids != null) {
            for (item in excludedSsids) {
                if (current == item) return null
            }
        }

        for (item in ssids) {
            if (Constraints.WIFI_AND_3G == item) return item
            if (Constraints.ONLY_WIFI == item) return item
            if (current == item) return item
        }
        return null
    }

    companion object {
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    }
}
