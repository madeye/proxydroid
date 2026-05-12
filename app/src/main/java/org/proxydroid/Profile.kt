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

import android.content.SharedPreferences
import android.util.Log
import org.json.simple.JSONObject
import org.json.simple.parser.JSONParser
import org.proxydroid.utils.Base64

/**
 * Persisted proxy profile. Fields exposed here correspond 1:1 to controls in
 * [org.proxydroid.ui.MainScreen]. The legacy `certificate` field (TLS cert
 * pinning, never wired into the VPN-first netstack) has been dropped.
 */
class Profile {
    var name: String = ""
    var host: String = ""
    var port: Int = 0
    var user: String = ""
    var password: String = ""
    var domain: String = ""
    var proxyType: String = "socks5"
    var ssid: String = ""
    var excludedSsid: String = ""
    var proxyApps: String = ""
    var bypassAddrs: String = ""

    var isAuth: Boolean = false
    var isNTLM: Boolean = false
    var isDNSProxy: Boolean = false
    var isPAC: Boolean = false
    var isAutoSetProxy: Boolean = false
    var isBypassApps: Boolean = false
    var isAutoConnect: Boolean = false

    fun init() {
        name = ""
        host = ""
        port = 0
        user = ""
        password = ""
        domain = ""
        proxyType = "socks5"
        ssid = ""
        excludedSsid = ""
        proxyApps = ""
        bypassAddrs = ""
        isAuth = false
        isNTLM = false
        isDNSProxy = false
        isPAC = false
        isAutoSetProxy = false
        isBypassApps = false
        isAutoConnect = false
    }

    fun getProfile(settings: SharedPreferences) {
        name = settings.getString("name", "").orEmpty()
        host = settings.getString("host", "").orEmpty()
        user = settings.getString("user", "").orEmpty()
        password = settings.getString("password", "").orEmpty()
        domain = settings.getString("domain", "").orEmpty()
        proxyType = settings.getString("proxyType", "socks5").orEmpty().ifEmpty { "socks5" }
        ssid = settings.getString("ssid", "").orEmpty()
        excludedSsid = settings.getString("excludedSsid", "").orEmpty()
        proxyApps = settings.getString("proxyApps", "").orEmpty()
        bypassAddrs = settings.getString("bypassAddrs", "").orEmpty()

        port = settings.getString("port", "")?.toIntOrNull() ?: 0

        isAuth = settings.getBoolean("isAuth", false)
        isNTLM = settings.getBoolean("isNTLM", false)
        isDNSProxy = settings.getBoolean("isDNSProxy", false)
        isPAC = settings.getBoolean("isPAC", false)
        isAutoSetProxy = settings.getBoolean("isAutoSetProxy", false)
        isBypassApps = settings.getBoolean("isBypassApps", false)
        isAutoConnect = settings.getBoolean("isAutoConnect", false)
    }

    fun setProfile(settings: SharedPreferences) {
        settings.edit().apply {
            putString("name", name)
            putString("host", host)
            putString("port", port.toString())
            putString("user", user)
            putString("password", password)
            putString("domain", domain)
            putString("proxyType", proxyType)
            putString("ssid", ssid)
            putString("excludedSsid", excludedSsid)
            putString("proxyApps", proxyApps)
            putString("bypassAddrs", bypassAddrs)
            putBoolean("isAuth", isAuth)
            putBoolean("isNTLM", isNTLM)
            putBoolean("isDNSProxy", isDNSProxy)
            putBoolean("isPAC", isPAC)
            putBoolean("isAutoSetProxy", isAutoSetProxy)
            putBoolean("isBypassApps", isBypassApps)
            putBoolean("isAutoConnect", isAutoConnect)
            apply()
        }
    }

    @Suppress("UNCHECKED_CAST")
    override fun toString(): String {
        val json = JSONObject()
        json["name"] = name
        json["host"] = host
        json["port"] = port
        json["user"] = user
        json["password"] = password
        json["domain"] = domain
        json["proxyType"] = proxyType
        json["ssid"] = ssid
        json["excludedSsid"] = excludedSsid
        json["proxyApps"] = proxyApps
        json["bypassAddrs"] = bypassAddrs
        json["isAuth"] = isAuth
        json["isNTLM"] = isNTLM
        json["isDNSProxy"] = isDNSProxy
        json["isPAC"] = isPAC
        json["isAutoSetProxy"] = isAutoSetProxy
        json["isBypassApps"] = isBypassApps
        json["isAutoConnect"] = isAutoConnect
        return json.toJSONString()
    }

    fun decodeJson(encoded: String) {
        if (encoded.isEmpty()) return
        try {
            val json = JSONParser().parse(encoded) as JSONObject
            name = json["name"] as? String ?: ""
            host = json["host"] as? String ?: ""
            port = (json["port"] as? Number)?.toInt() ?: 0
            user = json["user"] as? String ?: ""
            password = json["password"] as? String ?: ""
            domain = json["domain"] as? String ?: ""
            proxyType = (json["proxyType"] as? String)?.takeIf { it.isNotEmpty() } ?: "socks5"
            ssid = json["ssid"] as? String ?: ""
            excludedSsid = json["excludedSsid"] as? String ?: ""
            proxyApps = json["proxyApps"] as? String ?: ""
            bypassAddrs = json["bypassAddrs"] as? String ?: ""
            isAuth = json["isAuth"] as? Boolean ?: false
            isNTLM = json["isNTLM"] as? Boolean ?: false
            isDNSProxy = json["isDNSProxy"] as? Boolean ?: false
            isPAC = json["isPAC"] as? Boolean ?: false
            isAutoSetProxy = json["isAutoSetProxy"] as? Boolean ?: false
            isBypassApps = json["isBypassApps"] as? Boolean ?: false
            isAutoConnect = json["isAutoConnect"] as? Boolean ?: false
        } catch (e: Exception) {
            Log.e(TAG, "Error parsing profile JSON", e)
        }
    }

    fun copy(): Profile = Profile().also { dst ->
        dst.name = name
        dst.host = host
        dst.port = port
        dst.user = user
        dst.password = password
        dst.domain = domain
        dst.proxyType = proxyType
        dst.ssid = ssid
        dst.excludedSsid = excludedSsid
        dst.proxyApps = proxyApps
        dst.bypassAddrs = bypassAddrs
        dst.isAuth = isAuth
        dst.isNTLM = isNTLM
        dst.isDNSProxy = isDNSProxy
        dst.isPAC = isPAC
        dst.isAutoSetProxy = isAutoSetProxy
        dst.isBypassApps = isBypassApps
        dst.isAutoConnect = isAutoConnect
    }

    companion object {
        private const val TAG = "Profile"

        @JvmStatic
        fun validateAddr(addr: String?): String? {
            if (addr.isNullOrEmpty()) return null
            val trimmed = addr.trim()
            if (trimmed.isEmpty()) return null

            val parts = trimmed.split("/")
            if (parts.size > 2) return null

            val ipParts = parts[0].split(".")
            if (ipParts.size != 4) return null
            for (part in ipParts) {
                val num = part.toIntOrNull() ?: return null
                if (num < 0 || num > 255) return null
            }

            if (parts.size == 2) {
                val mask = parts[1].toIntOrNull() ?: return null
                if (mask < 0 || mask > 32) return null
            }

            return trimmed
        }

        @JvmStatic
        fun encodeAddrs(addrs: Array<String>?): String {
            if (addrs.isNullOrEmpty()) return ""
            return buildString {
                for (addr in addrs) {
                    append(Base64.encodeToString(addr.toByteArray(), Base64.NO_WRAP))
                    append('|')
                }
            }
        }

        @JvmStatic
        fun decodeAddrs(encoded: String?): Array<String> {
            if (encoded.isNullOrEmpty()) return emptyArray()
            return encoded.split("|")
                .filter { it.isNotEmpty() }
                .mapNotNull { part ->
                    runCatching { String(Base64.decode(part, Base64.NO_WRAP)) }
                        .getOrNull()
                        ?.takeIf { it.isNotEmpty() }
                }
                .toTypedArray()
        }
    }
}
