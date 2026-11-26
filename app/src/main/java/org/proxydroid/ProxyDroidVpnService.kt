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
import android.content.pm.PackageManager
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import android.preference.PreferenceManager
import android.util.Log
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.proxydroid.utils.Tun2SocksHelper
import org.proxydroid.utils.Utils

class ProxyDroidVpnService : VpnService() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var vpnJob: Job? = null


    companion object {
        private const val TAG = "ProxyDroidVpnService"
        private const val NOTIFICATION_ID = 2
        private const val CHANNEL_ID = "proxydroid_vpn_channel"
        private const val VPN_MTU = 1500

        // The tunnel's own point-to-point subnet: this device and the virtual
        // DNS server tun2socks answers for. It must not overlap any network the
        // device is really on, because the device treats these addresses as
        // local: with the former 10.0.0.1/24, a phone on a 10.0.0.0/24 Wi-Fi
        // could no longer reach its own gateway or the LAN host at 10.0.0.2.
        // 198.18.0.0/15 is reserved for benchmarking (RFC 2544) and is not
        // routed on real networks; the lower /16 is tun2socks' placeholder
        // address pool, so the tunnel takes a /30 from the upper half.
        private const val VPN_ADDRESS = "198.19.0.1"
        private const val VPN_PREFIX_LENGTH = 30
        private const val VPN_DNS = "198.19.0.2"
        private const val VPN_ROUTE = "0.0.0.0"
        const val ACTION_STOP = "org.proxydroid.action.STOP_VPN"
    }

    /** Immutable snapshot of one start request, handed to the worker coroutine. */
    private data class VpnConfig(
        val host: String = "",
        val port: Int = 0,
        val user: String = "",
        val password: String = "",
        val proxyType: String = "socks5",
        val proxyApps: String = "",
        val isBypassApps: Boolean = false,
    )

    /** Guards [vpnInterface] and [tun2SocksHelper]; start and stop never interleave. */
    private val vpnLock = Any()
    private var vpnInterface: ParcelFileDescriptor? = null
    private var tun2SocksHelper: Tun2SocksHelper? = null
    private var config = VpnConfig()

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "VPN Service created")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent == null) {
            stopSelf()
            return START_NOT_STICKY
        }

        if (intent.action == ACTION_STOP) {
            Log.d(TAG, "ACTION_STOP received")
            // Cancel first so a start request that has not run yet is dropped
            // instead of bringing the tunnel back up after this stop.
            vpnJob?.cancel()
            stopVpn()
            Utils.setWorking(false)
            Utils.setConnecting(false)
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }

        val bundle = intent.extras
        if (bundle != null) {
            config = VpnConfig(
                host = bundle.getString("host", ""),
                port = bundle.getInt("port", 0),
                user = bundle.getString("user", ""),
                password = bundle.getString("password", ""),
                proxyType = bundle.getString("proxyType", "socks5"),
                proxyApps = bundle.getString("proxyApps", ""),
                isBypassApps = bundle.getBoolean("isBypassApps", false),
            )
        }

        startForeground(NOTIFICATION_ID, createNotification())

        val request = config
        vpnJob?.cancel()
        vpnJob = serviceScope.launch { startVpn(request, startId) { isActive } }

        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        vpnJob?.cancel()
        stopVpn()
        Utils.setWorking(false)
        Utils.setConnecting(false)
        serviceScope.cancel()
        Log.d(TAG, "VPN Service destroyed")
    }

    override fun onRevoke() {
        super.onRevoke()
        vpnJob?.cancel()
        stopVpn()
        Utils.setWorking(false)
        Utils.setConnecting(false)
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

    /**
     * Brings the tunnel up for [cfg]. Runs on the service's IO scope.
     *
     * A start request that arrives while a tunnel is already up replaces it
     * make-before-break: the new interface is established and handed to
     * tun2socks while the old one is still open, and only then is the old one
     * closed. The order matters. If the old interface were closed first, the
     * new one could be given the same name (`tun0`); Android then treats the
     * handover as "nothing changed", keeps its routing pointed at the destroyed
     * device, and traffic silently bypasses the VPN. With the old interface
     * still alive the new one always gets a different name.
     *
     * A request that fails leaves a running tunnel alone whenever it can. Up
     * to the point where `establish()` succeeds nothing in the system has
     * changed, so a request rejected before that (e.g. its app selection is
     * invalid) keeps the existing tunnel serving traffic instead of dropping
     * the device back onto the bare network. Only a failure after the new
     * interface exists has to take everything down.
     */
    private fun startVpn(cfg: VpnConfig, startId: Int, isActive: () -> Boolean) {
        var failed = false
        // True when the failure left no usable tunnel, so the service must go.
        var stopService = false
        synchronized(vpnLock) {
            // Superseded by a newer start request or by a stop.
            if (!isActive()) return

            Log.d(TAG, "Starting VPN with proxy: ${cfg.host}:${cfg.port}")
            Utils.setConnecting(true)

            // Established but not yet adopted as [vpnInterface].
            var pendingTun: ParcelFileDescriptor? = null
            // Set once establish() has succeeded: from then on Android routes
            // through the new interface and the previous one is dead.
            var routingSwitched = false
            try {
                // Configure VPN. DNS server is in-tunnel; the Rust tun2socks
                // intercepts UDP/53 and forwards via DoH through the upstream proxy.
                val builder = Builder()
                    .setSession(getString(R.string.app_name))
                    .setMtu(VPN_MTU)
                    .addAddress(VPN_ADDRESS, VPN_PREFIX_LENGTH)
                    .addRoute(VPN_ROUTE, 0)
                    .addDnsServer(VPN_DNS)

                applyAppRouting(builder, cfg)

                val tun = builder.establish()
                if (tun == null) {
                    Log.e(TAG, "Failed to establish VPN interface")
                    failed = true
                } else {
                    pendingTun = tun
                    routingSwitched = true

                    // Start tun2socks. The Rust crate speaks to the user-configured
                    // upstream directly — no in-process HTTP/SOCKS bridge needed.
                    // If a session is already running, this replaces it.
                    val helper = tun2SocksHelper ?: Tun2SocksHelper().also { tun2SocksHelper = it }
                    val started = helper.start(
                        this,
                        tun.fd,
                        VPN_MTU,
                        cfg.proxyType,
                        cfg.host,
                        cfg.port,
                        cfg.user.takeIf { it.isNotEmpty() },
                        cfg.password.takeIf { it.isNotEmpty() },
                    )

                    if (started) {
                        // The new session owns the tunnel now; retire the
                        // interface it replaced, if any.
                        val previousTun = vpnInterface
                        vpnInterface = tun
                        pendingTun = null
                        closeQuietly(previousTun)
                        Utils.setWorking(true)
                        Log.d(TAG, "VPN started successfully")
                    } else {
                        Log.e(TAG, "Failed to start tun2socks")
                        failed = true
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error starting VPN", e)
                failed = true
            } finally {
                if (failed) {
                    closeQuietly(pendingTun)
                    if (!routingSwitched && vpnInterface != null) {
                        // Rejected before anything changed: the tunnel that
                        // was already up keeps running on its old settings.
                        Log.w(TAG, "Start request rejected; keeping the running tunnel")
                    } else {
                        // Never leave a tun interface up with nothing reading
                        // it: that would silently drop all traffic of the
                        // routed apps. And once Android has switched routing
                        // to the new interface, a tunnel that was running
                        // before this request cannot be kept either.
                        stopVpnLocked()
                        Utils.setWorking(false)
                        stopService = true
                    }
                }
                Utils.setConnecting(false)
            }
        }

        if (stopService) {
            // Stop the service only if no newer request has arrived since this
            // one: stopSelf(startId) is a no-op otherwise, so a failed request
            // can never take down the tunnel a later request just brought up.
            // Stopping also removes the foreground notification.
            stopSelf(startId)
        }
    }

    /**
     * Applies per-app routing. `VpnService.Builder` rejects mixing
     * `addAllowedApplication` with `addDisallowedApplication`, so each mode
     * uses exactly one of them:
     *
     * - "Proxy only selected apps": allow-list. Our own package is not on it,
     *   so tun2socks still reaches the upstream proxy outside the tunnel.
     * - Otherwise: disallow-list holding our own package, plus the selected
     *   apps when "Bypass selected apps" is on.
     */
    private fun applyAppRouting(builder: Builder, cfg: VpnConfig) {
        val tokens = selectionTokens(cfg)
        val selected = resolvePackages(tokens)

        // An empty selection means "proxy everything". A selection that no
        // longer resolves to any installed app does not: see below.
        if (!cfg.isBypassApps && tokens.isNotEmpty()) {
            // Fail closed. The user asked for specific apps only; if none of
            // them is installed any more, neither widening the tunnel to every
            // app nor running a tunnel that applies to nothing is what they
            // asked for, so refuse to start.
            check(selected.isNotEmpty()) {
                "\"Proxy only selected apps\" is on but none of the selected apps is installed"
            }
            for (pkg in selected) {
                builder.addAllowedApplication(pkg)
            }
            return
        }

        // Always exclude our own UID so tun2socks can reach the upstream
        // proxy without the packets looping back into our own tun.
        builder.addDisallowedApplication(packageName)
        if (cfg.isBypassApps) {
            for (pkg in selected) {
                builder.addDisallowedApplication(pkg)
            }
        }
    }

    /**
     * Raw entries picked on the per-app routing screen. That screen stores
     * `PackageManager.getNameForUid` values: plain package names for most
     * apps, `sharedUserId:uid` for apps sharing a UID.
     */
    private fun selectionTokens(cfg: VpnConfig): List<String> {
        val stored = PreferenceManager.getDefaultSharedPreferences(this)
            .getString(AppManager.PREFS_KEY_PROXYED, "").orEmpty()
        return (stored.split("|") + cfg.proxyApps.split("|")).filter { it.isNotEmpty() }
    }

    /**
     * Installed packages for [tokens]; a `sharedUserId:uid` entry expands to
     * every package of that UID. Our own package is never included.
     *
     * Entries that are no longer installed are dropped here, explicitly:
     * `VpnService.Builder` does not reject unknown package names, it accepts
     * them silently and the resulting tunnel simply applies to no app.
     */
    private fun resolvePackages(tokens: List<String>): Set<String> {
        val result = LinkedHashSet<String>()
        for (token in tokens) {
            val uid = token.substringAfterLast(':', "").toIntOrNull()
            val candidates = if (uid != null) {
                packageManager.getPackagesForUid(uid)?.toList().orEmpty()
            } else {
                listOf(token)
            }
            for (pkg in candidates) {
                if (isInstalled(pkg)) {
                    result.add(pkg)
                } else {
                    Log.w(TAG, "Selected app is not installed: $pkg")
                }
            }
        }
        result.remove(packageName)
        return result
    }

    private fun isInstalled(pkg: String): Boolean = try {
        packageManager.getApplicationInfo(pkg, 0)
        true
    } catch (e: PackageManager.NameNotFoundException) {
        false
    }

    private fun stopVpn() {
        synchronized(vpnLock) { stopVpnLocked() }
    }

    /** Caller must hold [vpnLock]. Safe to call when nothing is running. */
    private fun stopVpnLocked() {
        val helper = tun2SocksHelper
        if (vpnInterface == null && helper?.isRunning() != true) return
        Log.d(TAG, "Stopping VPN")

        try {
            helper?.stop()
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping tun2socks", e)
        }

        closeQuietly(vpnInterface)
        vpnInterface = null
    }

    private fun closeQuietly(tun: ParcelFileDescriptor?) {
        try {
            tun?.close()
        } catch (e: Exception) {
            Log.e(TAG, "Error closing VPN interface", e)
        }
    }
}
