package org.proxydroid.utils

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.os.Bundle
import org.proxydroid.Profile
import org.proxydroid.ProxyDroid
import org.proxydroid.ProxyDroidVpnService

object ProxyController {

    const val EXTRA_AUTO_START = "auto_start"

    fun buildExtras(profile: Profile): Bundle = Bundle().apply {
        putString("host", profile.host)
        putString("user", profile.user)
        putString("bypassAddrs", profile.bypassAddrs)
        putString("password", profile.password)
        putString("domain", profile.domain)
        putString("proxyType", profile.proxyType)
        putString("proxyApps", profile.proxyApps)
        putBoolean("isAutoSetProxy", profile.isAutoSetProxy)
        putBoolean("isBypassApps", profile.isBypassApps)
        putBoolean("isAuth", profile.isAuth)
        putBoolean("isNTLM", profile.isNTLM)
        putBoolean("isDNSProxy", profile.isDNSProxy)
        putBoolean("isPAC", profile.isPAC)
        putInt("port", profile.port)
    }

    /** Start the VPN service if consent is already granted; otherwise launch the
     *  activity with EXTRA_AUTO_START so it can run the consent flow. */
    fun startWithConsent(context: Context, profile: Profile) {
        val consent = VpnService.prepare(context)
        if (consent == null) {
            val intent = Intent(context, ProxyDroidVpnService::class.java)
                .putExtras(buildExtras(profile))
            context.startService(intent)
        } else {
            val launch = Intent(context, ProxyDroid::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                putExtra(EXTRA_AUTO_START, true)
            }
            context.startActivity(launch)
        }
    }

    fun stop(context: Context) {
        try {
            // Send an explicit STOP action to the service so it can close its tun
            // interface and call stopSelf. Calling context.stopService() alone is
            // not enough — the system holds VpnService alive while the tun is open.
            val intent = Intent(context, ProxyDroidVpnService::class.java).apply {
                action = ProxyDroidVpnService.ACTION_STOP
            }
            context.startService(intent)
        } catch (_: Exception) {
        }
    }
}
