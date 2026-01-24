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

import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.SharedPreferences.Editor;
import android.content.pm.PackageManager;
import android.media.AudioManager;
import android.net.Uri;
import android.net.VpnService;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Message;
import android.os.ParcelFileDescriptor;
import android.preference.PreferenceManager;
import android.util.Log;
import android.widget.RemoteViews;
import android.widget.Toast;

import androidx.core.app.NotificationCompat;

import com.btr.proxy.selector.pac.PacProxySelector;
import com.btr.proxy.selector.pac.PacScriptSource;
import com.btr.proxy.selector.pac.Proxy;
import com.btr.proxy.selector.pac.UrlPacScriptSource;

import org.proxydroid.utils.Tun2SocksHelper;
import org.proxydroid.utils.LocalProxyServer;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.lang.ref.WeakReference;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.SocketException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.UnknownHostException;
import java.util.Enumeration;
import java.util.List;

/**
 * VPN-based proxy service that doesn't require root permissions.
 * Uses tun2socks to redirect traffic through a SOCKS proxy.
 */
public class ProxyDroidVpnService extends VpnService {

    private static final String TAG = "ProxyDroidVpnService";

    // Notification
    private NotificationManager notificationManager;
    private PendingIntent pendIntent;

    // Message constants
    private static final int MSG_CONNECT_START = 0;
    private static final int MSG_CONNECT_FINISH = 1;
    private static final int MSG_CONNECT_SUCCESS = 2;
    private static final int MSG_CONNECT_FAIL = 3;
    private static final int MSG_CONNECT_PAC_ERROR = 4;
    private static final int MSG_CONNECT_RESOLVE_ERROR = 5;

    // VPN Constants
    private static final String VPN_ADDRESS = "10.0.0.2";
    private static final String VPN_ROUTE = "0.0.0.0";
    private static final int VPN_MTU = 1500;
    private static final String VPN_DNS = "8.8.8.8";
    private static final String VPN_DNS_SECONDARY = "8.8.4.4";

    // Proxy configuration
    private String host;
    private String hostName;
    private int port;
    private String bypassAddrs = "";
    private String user;
    private String password;
    private String domain;
    private String proxyType = "socks5";
    private boolean isAuth = false;
    private boolean isNTLM = false;
    private boolean isPAC = false;

    // App filtering
    private boolean isAutoSetProxy = false;
    private boolean isBypassApps = false;
    private ProxyedApp[] apps;

    public String basePath;

    private SharedPreferences settings = null;

    // VPN resources
    private ParcelFileDescriptor vpnInterface = null;
    private Tun2SocksHelper tun2SocksHelper = null;
    private LocalProxyServer localProxyServer = null;

    // Service state tracking
    private static WeakReference<ProxyDroidVpnService> sRunningInstance = null;

    public static boolean isServiceStarted() {
        if (sRunningInstance == null) {
            return false;
        } else if (sRunningInstance.get() == null) {
            sRunningInstance = null;
            return false;
        }
        return true;
    }

    private void markServiceStarted() {
        sRunningInstance = new WeakReference<>(this);
    }

    private void markServiceStopped() {
        sRunningInstance = null;
    }

    @Override
    public void onCreate() {
        super.onCreate();

        basePath = getFilesDir().getAbsolutePath() + "/";

        settings = PreferenceManager.getDefaultSharedPreferences(this);
        notificationManager = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        createNotificationChannel();

        Intent intent = new Intent(this, ProxyDroid.class);
        intent.setFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP);
        int flags = 0;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            flags = PendingIntent.FLAG_IMMUTABLE;
        }
        pendIntent = PendingIntent.getActivity(this, 0, intent, flags);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null || intent.getExtras() == null) {
            return START_NOT_STICKY;
        }

        ((ProxyDroidApplication) getApplication())
                .firebaseAnalytics.logEvent("vpn_service_start", null);

        Log.d(TAG, "VPN Service Start");

        Bundle bundle = intent.getExtras();
        host = bundle.getString("host");
        bypassAddrs = bundle.getString("bypassAddrs");
        proxyType = bundle.getString("proxyType");
        port = bundle.getInt("port");
        isAutoSetProxy = bundle.getBoolean("isAutoSetProxy");
        isBypassApps = bundle.getBoolean("isBypassApps");
        isAuth = bundle.getBoolean("isAuth");
        isNTLM = bundle.getBoolean("isNTLM");
        isPAC = bundle.getBoolean("isPAC");

        if (isAuth) {
            user = bundle.getString("user");
            password = bundle.getString("password");
        } else {
            user = "";
            password = "";
        }

        if (isNTLM) {
            domain = bundle.getString("domain");
        } else {
            domain = "";
        }

        new Thread(new Runnable() {
            @Override
            public void run() {
                handler.sendEmptyMessage(MSG_CONNECT_START);

                if (getAddress() && startVpn()) {
                    notifyAlert(getString(R.string.forward_success) + " | " + getProfileName(),
                            getString(R.string.service_running));

                    handler.sendEmptyMessage(MSG_CONNECT_SUCCESS);

                    // Update widget
                    try {
                        RemoteViews views = new RemoteViews(getPackageName(),
                                R.layout.proxydroid_appwidget);
                        views.setImageViewResource(R.id.serviceToggle, R.drawable.on);
                        AppWidgetManager awm = AppWidgetManager.getInstance(ProxyDroidVpnService.this);
                        awm.updateAppWidget(awm.getAppWidgetIds(new ComponentName(
                                ProxyDroidVpnService.this, ProxyDroidWidgetProvider.class)), views);
                    } catch (Exception ignore) {
                    }
                } else {
                    stopSelf();
                    handler.sendEmptyMessage(MSG_CONNECT_FAIL);
                }

                handler.sendEmptyMessage(MSG_CONNECT_FINISH);
            }
        }).start();

        markServiceStarted();

        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        ((ProxyDroidApplication) getApplication())
                .firebaseAnalytics.logEvent("vpn_service_stop", null);

        notificationManager.cancelAll();
        stopForeground(true);

        stopVpn();

        // Update widget
        try {
            RemoteViews views = new RemoteViews(getPackageName(), R.layout.proxydroid_appwidget);
            views.setImageViewResource(R.id.serviceToggle, R.drawable.off);
            AppWidgetManager awm = AppWidgetManager.getInstance(this);
            awm.updateAppWidget(
                    awm.getAppWidgetIds(new ComponentName(this, ProxyDroidWidgetProvider.class)),
                    views);
        } catch (Exception ignore) {
        }

        Editor ed = settings.edit();
        ed.putBoolean("isRunning", false);
        ed.apply();

        try {
            notificationManager.cancel(0);
        } catch (Exception ignore) {
        }

        markServiceStopped();

        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onRevoke() {
        stopVpn();
        stopSelf();
        super.onRevoke();
    }

    /**
     * Start the VPN and tun2socks
     */
    private boolean startVpn() {
        try {
            // Start local proxy server for HTTP proxy if needed
            int socksPort = port;
            String socksHost = host;

            if ("http".equals(proxyType) || "https".equals(proxyType) || "http-tunnel".equals(proxyType)) {
                // Start local SOCKS server that forwards to HTTP proxy
                localProxyServer = new LocalProxyServer(this, host, port, proxyType,
                        isAuth ? user : null, isAuth ? password : null);
                if (!localProxyServer.start()) {
                    Log.e(TAG, "Failed to start local proxy server");
                    return false;
                }
                socksHost = "127.0.0.1";
                socksPort = localProxyServer.getPort();
                Log.d(TAG, "Local SOCKS server started on port " + socksPort);
            }

            // Build VPN interface
            Builder builder = new Builder();
            builder.setSession(getString(R.string.app_name));
            builder.setMtu(VPN_MTU);
            builder.addAddress(VPN_ADDRESS, 24);
            builder.addRoute(VPN_ROUTE, 0);
            builder.addDnsServer(VPN_DNS);
            builder.addDnsServer(VPN_DNS_SECONDARY);

            // Handle per-app proxy
            if (!isAutoSetProxy) {
                if (apps == null || apps.length <= 0) {
                    apps = AppManager.getProxyedApps(this, !isBypassApps);
                }

                for (ProxyedApp app : apps) {
                    if (app != null && app.isProxyed()) {
                        try {
                            if (isBypassApps) {
                                // Bypass these apps
                                builder.addDisallowedApplication(app.getPackageName());
                            } else {
                                // Only proxy these apps
                                builder.addAllowedApplication(app.getPackageName());
                            }
                        } catch (PackageManager.NameNotFoundException e) {
                            Log.w(TAG, "App not found: " + app.getPackageName());
                        }
                    }
                }
            }

            // Add bypass addresses
            if (bypassAddrs != null && !bypassAddrs.isEmpty()) {
                String[] addrs = Profile.decodeAddrs(bypassAddrs);
                for (String addr : addrs) {
                    try {
                        // Add as excluded routes
                        if (addr.contains("/")) {
                            String[] parts = addr.split("/");
                            // Skip this route from VPN
                        }
                    } catch (Exception e) {
                        Log.w(TAG, "Invalid bypass address: " + addr);
                    }
                }
            }

            // Exclude proxy server from VPN
            try {
                builder.addDisallowedApplication(getPackageName());
            } catch (PackageManager.NameNotFoundException e) {
                Log.w(TAG, "Could not exclude self from VPN");
            }

            // Establish VPN
            vpnInterface = builder.establish();
            if (vpnInterface == null) {
                Log.e(TAG, "Failed to establish VPN interface");
                return false;
            }

            Log.d(TAG, "VPN interface established with fd: " + vpnInterface.getFd());

            // Prepare tun2socks SOCKS proxy URL
            String proxyUrl;
            if (isAuth && user != null && !user.isEmpty()) {
                proxyUrl = String.format("socks5://%s:%s@%s:%d", user, password, socksHost, socksPort);
            } else {
                proxyUrl = String.format("socks5://%s:%d", socksHost, socksPort);
            }

            // Start tun2socks
            tun2SocksHelper = new Tun2SocksHelper(this, vpnInterface.getFd(), VPN_MTU,
                    VPN_ADDRESS, proxyUrl, VPN_DNS);

            if (!tun2SocksHelper.start()) {
                Log.e(TAG, "Failed to start tun2socks");
                vpnInterface.close();
                vpnInterface = null;
                return false;
            }

            Log.d(TAG, "tun2socks started successfully");
            return true;

        } catch (Exception e) {
            Log.e(TAG, "Error starting VPN", e);
            return false;
        }
    }

    /**
     * Stop VPN and clean up resources
     */
    private void stopVpn() {
        // Stop tun2socks
        if (tun2SocksHelper != null) {
            tun2SocksHelper.stop();
            tun2SocksHelper = null;
        }

        // Stop local proxy server
        if (localProxyServer != null) {
            localProxyServer.stop();
            localProxyServer = null;
        }

        // Close VPN interface
        if (vpnInterface != null) {
            try {
                vpnInterface.close();
            } catch (IOException e) {
                Log.e(TAG, "Error closing VPN interface", e);
            }
            vpnInterface = null;
        }
    }

    private boolean getAddress() {
        if (isPAC) {
            try {
                PacScriptSource src = new UrlPacScriptSource(host);
                PacProxySelector ps = new PacProxySelector(src);
                URI uri = new URI("http://gaednsproxy.appspot.com");
                List<Proxy> list = ps.select(uri);
                if (list != null && list.size() != 0) {
                    Proxy p = list.get(0);
                    if (p.equals(Proxy.NO_PROXY) || p.host == null || p.port == 0 || p.type == null) {
                        handler.sendEmptyMessageDelayed(MSG_CONNECT_PAC_ERROR, 3000);
                        return false;
                    }
                    proxyType = p.type;
                    host = p.host;
                    port = p.port;
                } else {
                    handler.sendEmptyMessageDelayed(MSG_CONNECT_PAC_ERROR, 3000);
                    return false;
                }
            } catch (URISyntaxException e) {
                handler.sendEmptyMessageDelayed(MSG_CONNECT_PAC_ERROR, 3000);
                return false;
            }
        }

        hostName = host;

        try {
            host = InetAddress.getByName(host).getHostAddress();
        } catch (UnknownHostException e) {
            host = hostName;
            handler.sendEmptyMessageDelayed(MSG_CONNECT_RESOLVE_ERROR, 3000);
            return false;
        }

        Log.d(TAG, "Proxy: " + host);
        Log.d(TAG, "Port: " + port);

        return true;
    }

    private String getProfileName() {
        SharedPreferences settings = PreferenceManager.getDefaultSharedPreferences(this);
        return settings.getString("profile" + settings.getString("profile", "1"),
                getString(R.string.profile_base) + " " + settings.getString("profile", "1"));
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            CharSequence name = "ProxyDroid VPN Service";
            String description = "ProxyDroid VPN Background Service";
            int importance = NotificationManager.IMPORTANCE_LOW;
            NotificationChannel channel = new NotificationChannel("VpnService", name, importance);
            channel.setDescription(description);
            notificationManager.createNotificationChannel(channel);
        }
    }

    private void initSoundVibrateLights(NotificationCompat.Builder builder) {
        final String ringtone = settings.getString("settings_key_notif_ringtone", null);
        AudioManager audioManager = (AudioManager) this.getSystemService(Context.AUDIO_SERVICE);
        if (audioManager.getStreamVolume(AudioManager.STREAM_RING) == 0) {
            builder.setSound(null);
        } else if (ringtone != null) {
            builder.setSound(Uri.parse(ringtone));
        }

        if (settings.getBoolean("settings_key_notif_vibrate", false)) {
            builder.setVibrate(new long[]{0, 1000, 500, 1000, 500, 1000});
        }
    }

    private void notifyAlert(String title, String info) {
        NotificationCompat.Builder builder = new NotificationCompat.Builder(this, "VpnService");

        initSoundVibrateLights(builder);

        builder.setAutoCancel(false);
        builder.setTicker(title);
        builder.setContentTitle(getString(R.string.app_name) + " | " + getProfileName());
        builder.setContentText(info);
        builder.setSmallIcon(R.drawable.ic_stat_proxydroid);
        builder.setContentIntent(pendIntent);
        builder.setPriority(NotificationCompat.PRIORITY_LOW);
        builder.setOngoing(true);

        startForeground(1, builder.build());
    }

    final Handler handler = new Handler() {
        @Override
        public void handleMessage(Message msg) {
            Editor ed = settings.edit();
            switch (msg.what) {
                case MSG_CONNECT_START:
                    ed.putBoolean("isConnecting", true);
                    break;
                case MSG_CONNECT_FINISH:
                    ed.putBoolean("isConnecting", false);
                    break;
                case MSG_CONNECT_SUCCESS:
                    ed.putBoolean("isRunning", true);
                    break;
                case MSG_CONNECT_FAIL:
                    ed.putBoolean("isRunning", false);
                    break;
                case MSG_CONNECT_PAC_ERROR:
                    Toast.makeText(ProxyDroidVpnService.this, R.string.msg_pac_error,
                            Toast.LENGTH_SHORT).show();
                    break;
                case MSG_CONNECT_RESOLVE_ERROR:
                    Toast.makeText(ProxyDroidVpnService.this, R.string.msg_resolve_error,
                            Toast.LENGTH_SHORT).show();
                    break;
            }
            ed.apply();
            super.handleMessage(msg);
        }
    };

    public String getLocalIpAddress() {
        try {
            for (Enumeration<NetworkInterface> en = NetworkInterface.getNetworkInterfaces();
                    en.hasMoreElements(); ) {
                NetworkInterface intf = en.nextElement();
                for (Enumeration<InetAddress> enumIpAddr = intf.getInetAddresses();
                        enumIpAddr.hasMoreElements(); ) {
                    InetAddress inetAddress = enumIpAddr.nextElement();
                    if (!inetAddress.isLoopbackAddress()) {
                        return inetAddress.getHostAddress();
                    }
                }
            }
        } catch (SocketException ex) {
            Log.e(TAG, ex.toString());
        }
        return null;
    }
}
