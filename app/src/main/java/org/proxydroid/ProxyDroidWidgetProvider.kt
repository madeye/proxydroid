/* proxydroid - Global / Individual Proxy App for Android
 * Copyright (C) 2011 Max Lv <max.c.lv@gmail.com>
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package org.proxydroid

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.preference.PreferenceManager
import android.util.Log
import android.widget.RemoteViews
import org.proxydroid.utils.ProxyController
import org.proxydroid.utils.Utils

class ProxyDroidWidgetProvider : AppWidgetProvider() {

    companion object {
        const val PROXY_SWITCH_ACTION = "org.proxydroid.ProxyDroidWidgetProvider.PROXY_SWITCH_ACTION"
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
            } else {
                views.setImageViewResource(R.id.serviceToggle, R.drawable.off)
            }

            appWidgetManager.updateAppWidget(appWidgetId, views)
        }
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        if (intent.action != PROXY_SWITCH_ACTION) return

        val views = RemoteViews(context.packageName, R.layout.proxydroid_appwidget)
        try {
            views.setImageViewResource(R.id.serviceToggle, R.drawable.ing)
            val awm = AppWidgetManager.getInstance(context)
            awm.updateAppWidget(
                awm.getAppWidgetIds(ComponentName(context, ProxyDroidWidgetProvider::class.java)),
                views
            )
        } catch (_: Exception) {
        }

        Log.d(TAG, "Proxy switch action")

        if (Utils.isWorking()) {
            ProxyController.stop(context)
        } else {
            val settings = PreferenceManager.getDefaultSharedPreferences(context)
            val profile = Profile().apply { getProfile(settings) }
            ProxyController.startWithConsent(context, profile)
        }
    }
}
