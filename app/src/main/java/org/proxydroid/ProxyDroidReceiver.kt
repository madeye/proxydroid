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
import android.os.Bundle
import android.preference.PreferenceManager

class ProxyDroidReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val settings = PreferenceManager.getDefaultSharedPreferences(context)
        val profile = Profile()
        profile.getProfile(settings)

        if (profile.isAutoConnect) {
            val serviceIntent = Intent(context, ProxyDroidService::class.java)
            val bundle = Bundle().apply {
                putString("host", profile.host)
                putString("user", profile.user)
                putString("bypassAddrs", profile.bypassAddrs)
                putString("password", profile.password)
                putString("domain", profile.domain)
                putString("proxyType", profile.proxyType)
                putBoolean("isAutoSetProxy", profile.isAutoSetProxy)
                putBoolean("isBypassApps", profile.isBypassApps)
                putBoolean("isAuth", profile.isAuth)
                putBoolean("isNTLM", profile.isNTLM)
                putBoolean("isDNSProxy", profile.isDNSProxy)
                putBoolean("isPAC", profile.isPAC)
                putInt("port", profile.port)
            }
            serviceIntent.putExtras(bundle)
            context.startService(serviceIntent)
        }
    }
}
