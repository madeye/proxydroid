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

package org.proxydroid;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.net.VpnService;
import android.os.Build;
import android.os.Bundle;
import android.os.ParcelFileDescriptor;
import android.preference.PreferenceManager;
import android.util.Log;

import androidx.core.app.NotificationCompat;

import org.proxydroid.utils.LocalProxyServer;
import org.proxydroid.utils.Tun2SocksHelper;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

public class ProxyDroidVpnService extends VpnService {

    private static final String TAG = "ProxyDroidVpnService";
    private static final String CHANNEL_ID = "proxydroid_vpn";
    private static final int NOTIFICATION_ID = 1;

    private static final String VPN_ADDRESS = "10.0.0.2";
    private static final String VPN_ROUTE = "0.0.0.0";
    private static final int VPN_MTU = 1500;
    private static final String DNS_SERVER = "8.8.8.8";

    private static volatile boolean isRunning = false;

    private ParcelFileDescriptor vpnInterface;
    private Tun2SocksHelper tun2SocksHelper;
    private LocalProxyServer localProxyServer;
    private AtomicBoolean isStarted = new AtomicBoolean(false);

    // Configuration
    private String proxyHost;
    private int proxyPort;
    private String proxyType;
    private String proxyUser;
    private String proxyPassword;
    private boolean isBypassApps;
    private Set<String> proxyedApps;

    public static boolean isServiceRunning() {
        return isRunning;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        createNotificationChannel();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null) {
            stopSelf();
            return START_NOT_STICKY;
        }

        Bundle bundle = intent.getExtras();
        if (bundle != null) {
            proxyHost = bundle.getString("host", "");
            proxyPort = bundle.getInt("port", 1080);
            proxyType = bundle.getString("proxyType", "socks5");
            proxyUser = bundle.getString("user", "");
            proxyPassword = bundle.getString("password", "");
            isBypassApps = bundle.getBoolean("isBypassApps", false);
        }

        SharedPreferences settings = PreferenceManager.getDefaultSharedPreferences(this);
        String proxyedAppsString = settings.getString("proxyedApps", "");
        if (!proxyedAppsString.isEmpty()) {
            proxyedApps = Set.of(proxyedAppsString.split("\\|"));
        }

        startVpn();

        return START_STICKY;
    }

    private void startVpn() {
        if (isStarted.getAndSet(true)) {
            return;
        }

        new Thread(() -> {
            try {
                startForeground(NOTIFICATION_ID, buildNotification());
                isRunning = true;

                // Start local SOCKS proxy if needed (for HTTP proxy conversion)
                int localSocksPort = 10800;
                if ("http".equals(proxyType) || "https".equals(proxyType) || "http-tunnel".equals(proxyType)) {
                    localProxyServer = new LocalProxyServer(
                            localSocksPort,
                            proxyHost,
                            proxyPort,
                            proxyType,
                            proxyUser,
                            proxyPassword
                    );
                    localProxyServer.start();
                    // Point tun2socks to local SOCKS proxy
                    proxyHost = "127.0.0.1";
                    proxyPort = localSocksPort;
                    proxyType = "socks5";
                }

                // Establish VPN interface
                vpnInterface = establishVpn();
                if (vpnInterface == null) {
                    Log.e(TAG, "Failed to establish VPN interface");
                    stopVpn();
                    return;
                }

                // Start tun2socks
                tun2SocksHelper = new Tun2SocksHelper(
                        vpnInterface.getFd(),
                        VPN_MTU,
                        proxyHost,
                        proxyPort,
                        proxyUser,
                        proxyPassword
                );
                tun2SocksHelper.start();

                Log.i(TAG, "VPN started successfully");

            } catch (Exception e) {
                Log.e(TAG, "Error starting VPN", e);
                stopVpn();
            }
        }).start();
    }

    private ParcelFileDescriptor establishVpn() {
        Builder builder = new Builder();
        builder.setSession(getString(R.string.app_name));
        builder.setMtu(VPN_MTU);
        builder.addAddress(VPN_ADDRESS, 24);
        builder.addRoute(VPN_ROUTE, 0);
        builder.addDnsServer(DNS_SERVER);

        // Handle per-app proxy
        if (proxyedApps != null && !proxyedApps.isEmpty()) {
            PackageManager pm = getPackageManager();
            for (String packageName : proxyedApps) {
                try {
                    pm.getPackageInfo(packageName, 0);
                    if (isBypassApps) {
                        builder.addDisallowedApplication(packageName);
                    } else {
                        builder.addAllowedApplication(packageName);
                    }
                } catch (PackageManager.NameNotFoundException e) {
                    Log.w(TAG, "Package not found: " + packageName);
                }
            }
        }

        // Don't route our own app through VPN
        try {
            builder.addDisallowedApplication(getPackageName());
        } catch (PackageManager.NameNotFoundException e) {
            Log.e(TAG, "Cannot exclude own package", e);
        }

        Intent configureIntent = new Intent(this, ProxyDroid.class);
        PendingIntent pendingIntent = PendingIntent.getActivity(
                this, 0, configureIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );
        builder.setConfigureIntent(pendingIntent);

        try {
            return builder.establish();
        } catch (Exception e) {
            Log.e(TAG, "Error establishing VPN", e);
            return null;
        }
    }

    private void stopVpn() {
        isRunning = false;
        isStarted.set(false);

        if (tun2SocksHelper != null) {
            tun2SocksHelper.stop();
            tun2SocksHelper = null;
        }

        if (localProxyServer != null) {
            localProxyServer.stop();
            localProxyServer = null;
        }

        if (vpnInterface != null) {
            try {
                vpnInterface.close();
            } catch (IOException e) {
                Log.e(TAG, "Error closing VPN interface", e);
            }
            vpnInterface = null;
        }

        stopForeground(true);
        stopSelf();
    }

    @Override
    public void onDestroy() {
        stopVpn();
        super.onDestroy();
    }

    @Override
    public void onRevoke() {
        stopVpn();
        super.onRevoke();
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID,
                    "ProxyDroid VPN",
                    NotificationManager.IMPORTANCE_LOW
            );
            channel.setDescription("ProxyDroid VPN service notification");
            NotificationManager manager = getSystemService(NotificationManager.class);
            if (manager != null) {
                manager.createNotificationChannel(channel);
            }
        }
    }

    private Notification buildNotification() {
        Intent notificationIntent = new Intent(this, ProxyDroid.class);
        PendingIntent pendingIntent = PendingIntent.getActivity(
                this, 0, notificationIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );

        NotificationCompat.Builder builder = new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle(getString(R.string.app_name))
                .setContentText(getString(R.string.service_running))
                .setSmallIcon(R.drawable.ic_stat_proxydroid)
                .setContentIntent(pendingIntent)
                .setOngoing(true);

        return builder.build();
    }
}
