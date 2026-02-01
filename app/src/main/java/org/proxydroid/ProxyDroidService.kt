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
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import org.proxydroid.utils.Utils

class ProxyDroidService : Service() {

    companion object {
        private const val TAG = "ProxyDroidService"
        private const val NOTIFICATION_ID = 1
        private const val CHANNEL_ID = "proxydroid_channel"
    }

    private var host: String = ""
    private var port: Int = 0
    private var user: String = ""
    private var password: String = ""
    private var domain: String = ""
    private var proxyType: String = "http"
    private var bypassAddrs: String = ""
    private var isAuth: Boolean = false
    private var isNTLM: Boolean = false
    private var isDNSProxy: Boolean = false
    private var isPAC: Boolean = false
    private var isAutoSetProxy: Boolean = false
    private var isBypassApps: Boolean = false

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "ProxyDroid service created")
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
            domain = bundle.getString("domain", "")
            proxyType = bundle.getString("proxyType", "http")
            bypassAddrs = bundle.getString("bypassAddrs", "")
            isAuth = bundle.getBoolean("isAuth", false)
            isNTLM = bundle.getBoolean("isNTLM", false)
            isDNSProxy = bundle.getBoolean("isDNSProxy", false)
            isPAC = bundle.getBoolean("isPAC", false)
            isAutoSetProxy = bundle.getBoolean("isAutoSetProxy", false)
            isBypassApps = bundle.getBoolean("isBypassApps", false)
        }

        startForeground(NOTIFICATION_ID, createNotification())

        Thread {
            startProxy()
        }.start()

        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        stopProxy()
        Utils.setWorking(false)
        Utils.setConnecting(false)
        Log.d(TAG, "ProxyDroid service destroyed")
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createNotification(): Notification {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "ProxyDroid Service",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "ProxyDroid proxy service notification"
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
            .setContentText(getString(R.string.service_running))
            .setSmallIcon(R.drawable.ic_stat_proxydroid)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()
    }

    private fun startProxy() {
        Log.d(TAG, "Starting proxy: $host:$port type=$proxyType")
        Utils.setConnecting(true)

        try {
            if (Utils.isRoot()) {
                setupIptables()
            }
            Utils.setWorking(true)
        } catch (e: Exception) {
            Log.e(TAG, "Error starting proxy", e)
        } finally {
            Utils.setConnecting(false)
        }
    }

    private fun stopProxy() {
        Log.d(TAG, "Stopping proxy")
        try {
            if (Utils.isRoot()) {
                clearIptables()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping proxy", e)
        }
    }

    private fun setupIptables() {
        val iptables = Utils.getIptablesPath()
        val commands = StringBuilder()

        // Clear existing rules
        commands.append("$iptables -t nat -F OUTPUT\n")

        // Add bypass rules for localhost
        commands.append("$iptables -t nat -A OUTPUT -d 127.0.0.1 -j RETURN\n")

        // Add bypass rules for configured addresses
        val addrs = Profile.decodeAddrs(bypassAddrs)
        for (addr in addrs) {
            if (addr.isNotEmpty()) {
                commands.append("$iptables -t nat -A OUTPUT -d $addr -j RETURN\n")
            }
        }

        // Add proxy redirect rule based on proxy type
        val localPort = when (proxyType) {
            "http" -> 8123
            "socks4", "socks5" -> 1080
            else -> 8123
        }

        commands.append("$iptables -t nat -A OUTPUT -p tcp -j REDIRECT --to-ports $localPort\n")

        Utils.runRootCommand(commands.toString())
    }

    private fun clearIptables() {
        val iptables = Utils.getIptablesPath()
        Utils.runRootCommand("$iptables -t nat -F OUTPUT")
    }
}
