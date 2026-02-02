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

class Profile {
    var name: String = ""
    var host: String = ""
    var port: Int = 0
    var user: String = ""
    var password: String = ""
    var domain: String = ""
    var proxyType: String = ""
    var ssid: String = ""
    var excludedSsid: String = ""
    var proxyApps: String = ""
    var bypassAddrs: String = ""
    var certificate: String = ""

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
        proxyType = "http"
        ssid = ""
        excludedSsid = ""
        proxyApps = ""
        bypassAddrs = ""
        certificate = ""
        isAuth = false
        isNTLM = false
        isDNSProxy = false
        isPAC = false
        isAutoSetProxy = false
        isBypassApps = false
        isAutoConnect = false
    }

    fun getProfile(settings: SharedPreferences) {
        name = settings.getString("name", "") ?: ""
        host = settings.getString("host", "") ?: ""
        user = settings.getString("user", "") ?: ""
        password = settings.getString("password", "") ?: ""
        domain = settings.getString("domain", "") ?: ""
        proxyType = settings.getString("proxyType", "http") ?: "http"
        ssid = settings.getString("ssid", "") ?: ""
        excludedSsid = settings.getString("excludedSsid", "") ?: ""
        proxyApps = settings.getString("proxyApps", "") ?: ""
        bypassAddrs = settings.getString("bypassAddrs", "") ?: ""
        certificate = settings.getString("certificate", "") ?: ""

        port = try {
            settings.getString("port", "")?.toIntOrNull() ?: 0
        } catch (e: NumberFormatException) {
            0
        }

        isAuth = settings.getBoolean("isAuth", false)
        isNTLM = settings.getBoolean("isNTLM", false)
        isDNSProxy = settings.getBoolean("isDNSProxy", false)
        isPAC = settings.getBoolean("isPAC", false)
        isAutoSetProxy = settings.getBoolean("isAutoSetProxy", false)
        isBypassApps = settings.getBoolean("isBypassApps", false)
        isAutoConnect = settings.getBoolean("isAutoConnect", false)
    }

    fun setProfile(settings: SharedPreferences) {
        val editor = settings.edit()
        editor.putString("name", name)
        editor.putString("host", host)
        editor.putString("port", port.toString())
        editor.putString("user", user)
        editor.putString("password", password)
        editor.putString("domain", domain)
        editor.putString("proxyType", proxyType)
        editor.putString("ssid", ssid)
        editor.putString("excludedSsid", excludedSsid)
        editor.putString("proxyApps", proxyApps)
        editor.putString("bypassAddrs", bypassAddrs)
        editor.putString("certificate", certificate)
        editor.putBoolean("isAuth", isAuth)
        editor.putBoolean("isNTLM", isNTLM)
        editor.putBoolean("isDNSProxy", isDNSProxy)
        editor.putBoolean("isPAC", isPAC)
        editor.putBoolean("isAutoSetProxy", isAutoSetProxy)
        editor.putBoolean("isBypassApps", isBypassApps)
        editor.putBoolean("isAutoConnect", isAutoConnect)
        editor.apply()
    }

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
        json["certificate"] = certificate
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
            val parser = JSONParser()
            val json = parser.parse(encoded) as JSONObject
            name = json["name"] as? String ?: ""
            host = json["host"] as? String ?: ""
            port = (json["port"] as? Number)?.toInt() ?: 0
            user = json["user"] as? String ?: ""
            password = json["password"] as? String ?: ""
            domain = json["domain"] as? String ?: ""
            proxyType = json["proxyType"] as? String ?: "http"
            ssid = json["ssid"] as? String ?: ""
            excludedSsid = json["excludedSsid"] as? String ?: ""
            proxyApps = json["proxyApps"] as? String ?: ""
            bypassAddrs = json["bypassAddrs"] as? String ?: ""
            certificate = json["certificate"] as? String ?: ""
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

    companion object {
        private const val TAG = "Profile"

        @JvmStatic
        fun validateAddr(addr: String?): String? {
            if (addr.isNullOrEmpty()) return null
            val trimmed = addr.trim()
            if (trimmed.isEmpty()) return null

            // Check for CIDR notation
            val parts = trimmed.split("/")
            if (parts.size > 2) return null

            val ipPart = parts[0]
            val maskPart = if (parts.size == 2) parts[1] else null

            // Validate IP address
            val ipParts = ipPart.split(".")
            if (ipParts.size != 4) return null

            for (part in ipParts) {
                val num = part.toIntOrNull() ?: return null
                if (num < 0 || num > 255) return null
            }

            // Validate mask if present
            if (maskPart != null) {
                val mask = maskPart.toIntOrNull() ?: return null
                if (mask < 0 || mask > 32) return null
            }

            return trimmed
        }

        @JvmStatic
        fun encodeAddrs(addrs: Array<String>?): String {
            if (addrs == null || addrs.isEmpty()) return ""
            val sb = StringBuilder()
            for (addr in addrs) {
                val encoded = Base64.encodeToString(addr.toByteArray(), Base64.NO_WRAP)
                sb.append(encoded).append("|")
            }
            return sb.toString()
        }

        @JvmStatic
        fun decodeAddrs(encoded: String?): Array<String> {
            if (encoded.isNullOrEmpty()) return emptyArray()
            val parts = encoded.split("|")
            val result = mutableListOf<String>()
            for (part in parts) {
                if (part.isNotEmpty()) {
                    try {
                        val decoded = String(Base64.decode(part, Base64.NO_WRAP))
                        if (decoded.isNotEmpty()) {
                            result.add(decoded)
                        }
                    } catch (e: Exception) {
                        // Skip invalid entries
                    }
                }
            }
            return result.toTypedArray()
        }
    }
}
