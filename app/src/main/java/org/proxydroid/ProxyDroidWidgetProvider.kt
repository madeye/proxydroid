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

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.widget.RemoteViews
import android.preference.PreferenceManager
import org.proxydroid.utils.Utils

class ProxyDroidWidgetProvider : AppWidgetProvider() {

    companion object {
        const val PROXY_SWITCH_ACTION = "org.proxydroid.ProxyDroidWidgetProvider.PROXY_SWITCH_ACTION"
        const val SERVICE_NAME = "org.proxydroid.ProxyDroidService"
        private const val TAG = "ProxyDroidWidgetProvider"
    }

    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        for (appWidgetId in appWidgetIds) {
            val intent = Intent(context, ProxyDroidWidgetProvider::class.java).apply {
                action = PROXY_SWITCH_ACTION
            }
            val pendingIntent = PendingIntent.getBroadcast(
                context, 0, intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            val views = RemoteViews(context.packageName, R.layout.proxydroid_appwidget)
            views.setOnClickPendingIntent(R.id.serviceToggle, pendingIntent)

            if (Utils.isWorking()) {
                views.setImageViewResource(R.id.serviceToggle, R.drawable.on)
                Log.d(TAG, "Service running")
            } else {
                views.setImageViewResource(R.id.serviceToggle, R.drawable.off)
                Log.d(TAG, "Service stopped")
            }

            appWidgetManager.updateAppWidget(appWidgetId, views)
        }
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)

        if (intent.action == PROXY_SWITCH_ACTION) {
            val views = RemoteViews(context.packageName, R.layout.proxydroid_appwidget)
            try {
                views.setImageViewResource(R.id.serviceToggle, R.drawable.ing)
                val awm = AppWidgetManager.getInstance(context)
                awm.updateAppWidget(
                    awm.getAppWidgetIds(ComponentName(context, ProxyDroidWidgetProvider::class.java)),
                    views
                )
            } catch (e: Exception) {
                // Nothing
            }

            Log.d(TAG, "Proxy switch action")

            if (Utils.isWorking()) {
                // Service is working, so stop it
                try {
                    context.stopService(Intent(context, ProxyDroidService::class.java))
                } catch (e: Exception) {
                    // Nothing
                }
            } else {
                // Service is not working, then start it
                val settings = PreferenceManager.getDefaultSharedPreferences(context)
                val profile = Profile()
                profile.getProfile(settings)

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
}
