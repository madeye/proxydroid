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

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import android.util.Log
import androidx.core.app.NotificationCompat
import org.proxydroid.utils.LocalProxyServer
import org.proxydroid.utils.Tun2SocksHelper
import org.proxydroid.utils.Utils

class ProxyDroidVpnService : VpnService() {

    companion object {
        private const val TAG = "ProxyDroidVpnService"
        private const val NOTIFICATION_ID = 2
        private const val CHANNEL_ID = "proxydroid_vpn_channel"
        private const val VPN_MTU = 1500
        private const val VPN_ADDRESS = "10.0.0.1"
        private const val VPN_ROUTE = "0.0.0.0"
        private const val LOCAL_SOCKS_PORT = 1080
    }

    private var vpnInterface: ParcelFileDescriptor? = null
    private var tun2SocksHelper: Tun2SocksHelper? = null
    private var localProxyServer: LocalProxyServer? = null

    private var host: String = ""
    private var port: Int = 0
    private var user: String = ""
    private var password: String = ""
    private var proxyType: String = "socks5"
    private var proxyApps: String = ""
    private var isBypassApps: Boolean = false

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "VPN Service created")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent == null) {
            stopSelf()
            return START_NOT_STICKY
        }

        val bundle = intent.extras
        if (bundle != null) {
            host = bundle.getString("host", "")
            port = bundle.getInt("port", 0)
            user = bundle.getString("user", "")
            password = bundle.getString("password", "")
            proxyType = bundle.getString("proxyType", "socks5")
            proxyApps = bundle.getString("proxyApps", "")
            isBypassApps = bundle.getBoolean("isBypassApps", false)
        }

        startForeground(NOTIFICATION_ID, createNotification())

        Thread {
            startVpn()
        }.start()

        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        stopVpn()
        Utils.setWorking(false)
        Utils.setConnecting(false)
        Log.d(TAG, "VPN Service destroyed")
    }

    override fun onRevoke() {
        super.onRevoke()
        stopVpn()
        stopSelf()
    }

    private fun createNotification(): Notification {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "ProxyDroid VPN Service",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "ProxyDroid VPN service notification"
            }
            val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.createNotificationChannel(channel)
        }

        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, ProxyDroid::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(getString(R.string.vpn_running))
            .setSmallIcon(R.drawable.ic_stat_proxydroid)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()
    }

    private fun startVpn() {
        Log.d(TAG, "Starting VPN with proxy: $host:$port")
        Utils.setConnecting(true)

        try {
            // Start local SOCKS proxy if needed (for HTTP proxy)
            if (proxyType == "http") {
                localProxyServer = LocalProxyServer(
                    LOCAL_SOCKS_PORT,
                    host,
                    port,
                    if (user.isNotEmpty()) user else null,
                    if (password.isNotEmpty()) password else null
                )
                localProxyServer?.start()
            }

            // Configure VPN
            val builder = Builder()
                .setSession(getString(R.string.app_name))
                .setMtu(VPN_MTU)
                .addAddress(VPN_ADDRESS, 24)
                .addRoute(VPN_ROUTE, 0)
                .addDnsServer("8.8.8.8")
                .addDnsServer("8.8.4.4")

            // Add per-app proxy if configured
            if (proxyApps.isNotEmpty()) {
                val apps = proxyApps.split("|")
                if (isBypassApps) {
                    // Bypass mode: exclude these apps from VPN
                    for (app in apps) {
                        if (app.isNotEmpty()) {
                            try {
                                builder.addDisallowedApplication(app)
                            } catch (e: Exception) {
                                Log.w(TAG, "Failed to exclude app: $app", e)
                            }
                        }
                    }
                } else {
                    // Proxy mode: only proxy these apps
                    for (app in apps) {
                        if (app.isNotEmpty()) {
                            try {
                                builder.addAllowedApplication(app)
                            } catch (e: Exception) {
                                Log.w(TAG, "Failed to allow app: $app", e)
                            }
                        }
                    }
                }
            }

            vpnInterface = builder.establish()

            if (vpnInterface == null) {
                Log.e(TAG, "Failed to establish VPN interface")
                Utils.setConnecting(false)
                return
            }

            // Start tun2socks
            tun2SocksHelper = Tun2SocksHelper()
            val socksHost = if (proxyType == "http") "127.0.0.1" else host
            val socksPort = if (proxyType == "http") LOCAL_SOCKS_PORT else port

            val started = tun2SocksHelper?.start(
                vpnInterface!!.fd,
                VPN_MTU,
                socksHost,
                socksPort,
                if (user.isNotEmpty() && proxyType != "http") user else null,
                if (password.isNotEmpty() && proxyType != "http") password else null
            ) ?: false

            if (started) {
                Utils.setWorking(true)
                Log.d(TAG, "VPN started successfully")
            } else {
                Log.e(TAG, "Failed to start tun2socks")
            }

        } catch (e: Exception) {
            Log.e(TAG, "Error starting VPN", e)
        } finally {
            Utils.setConnecting(false)
        }
    }

    private fun stopVpn() {
        Log.d(TAG, "Stopping VPN")

        try {
            tun2SocksHelper?.stop()
            tun2SocksHelper = null

            localProxyServer?.stop()
            localProxyServer = null

            vpnInterface?.close()
            vpnInterface = null
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping VPN", e)
        }
    }
}
