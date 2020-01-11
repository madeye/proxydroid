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
 *
 *
 *                            ___====-_  _-====___
 *                      _--^^^#####//      \\#####^^^--_
 *                   _-^##########// (    ) \\##########^-_
 *                  -############//  |\^^/|  \\############-
 *                _/############//   (@::@)   \\############\_
 *               /#############((     \\//     ))#############\
 *              -###############\\    (oo)    //###############-
 *             -#################\\  / VV \  //#################-
 *            -###################\\/      \//###################-
 *           _#/|##########/\######(   /\   )######/\##########|\#_
 *           |/ |#/\#/\#/\/  \#/\##\  |  |  /##/\#/  \/\#/\#/\#| \|
 *           `  |/  V  V  `   V  \#\| |  | |/#/  V   '  V  V  \|  '
 *              `   `  `      `   / | |  | | \   '      '  '   '
 *                               (  | |  | |  )
 *                              __\ | |  | | /__
 *                             (vvv(VVV)(VVV)vvv)
 *
 *                              HERE BE DRAGONS
 *
 */

package org.proxydroid;

import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.appwidget.AppWidgetManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.SharedPreferences.Editor;
import android.media.AudioManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Message;
import android.preference.PreferenceManager;
import android.util.Log;
import android.widget.RemoteViews;
import android.widget.Toast;

import androidx.core.app.NotificationCompat;

import com.btr.proxy.selector.pac.PacProxySelector;
import com.btr.proxy.selector.pac.PacScriptSource;
import com.btr.proxy.selector.pac.Proxy;
import com.btr.proxy.selector.pac.UrlPacScriptSource;

import org.proxydroid.utils.Utils;

import java.io.FileOutputStream;
import java.lang.ref.WeakReference;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.SocketException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.UnknownHostException;
import java.util.Enumeration;
import java.util.List;

public class ProxyDroidService extends Service {

    private NotificationManager notificationManager;
    private PendingIntent pendIntent;

    private static final int MSG_CONNECT_START = 0;
    private static final int MSG_CONNECT_FINISH = 1;
    private static final int MSG_CONNECT_SUCCESS = 2;
    private static final int MSG_CONNECT_FAIL = 3;
    private static final int MSG_CONNECT_PAC_ERROR = 4;
    private static final int MSG_CONNECT_RESOLVE_ERROR = 5;

    final static String CMD_IPTABLES_RETURN = "iptables -t nat -A OUTPUT -p tcp -d 0.0.0.0 -j RETURN\n";

    final static String CMD_IPTABLES_REDIRECT_ADD_HTTP = "iptables -t nat -A OUTPUT -p tcp --dport 80 -j REDIRECT --to 8123\n"
            + "iptables -t nat -A OUTPUT -p tcp --dport 443 -j REDIRECT --to 8124\n"
            + "iptables -t nat -A OUTPUT -p tcp --dport 5228 -j REDIRECT --to 8124\n";

    final static String CMD_IPTABLES_DNAT_ADD_HTTP = "iptables -t nat -A OUTPUT -p tcp --dport 80 -j DNAT --to-destination 127.0.0.1:8123\n"
            + "iptables -t nat -A OUTPUT -p tcp --dport 443 -j DNAT --to-destination 127.0.0.1:8124\n"
            + "iptables -t nat -A OUTPUT -p tcp --dport 5228 -j DNAT --to-destination 127.0.0.1:8124\n";

    final static String CMD_IPTABLES_REDIRECT_ADD_HTTP_TUNNEL = "iptables -t nat -A OUTPUT -p tcp --dport 80 -j REDIRECT --to 8123\n"
            + "iptables -t nat -A OUTPUT -p tcp --dport 443 -j REDIRECT --to 8123\n"
            + "iptables -t nat -A OUTPUT -p tcp --dport 5228 -j REDIRECT --to 8123\n";

    final static String CMD_IPTABLES_DNAT_ADD_HTTP_TUNNEL = "iptables -t nat -A OUTPUT -p tcp --dport 80 -j DNAT --to-destination 127.0.0.1:8123\n"
            + "iptables -t nat -A OUTPUT -p tcp --dport 443 -j DNAT --to-destination 127.0.0.1:8123\n"
            + "iptables -t nat -A OUTPUT -p tcp --dport 5228 -j DNAT --to-destination 127.0.0.1:8123\n";

    final static String CMD_IPTABLES_REDIRECT_ADD_SOCKS = "iptables -t nat -A OUTPUT -p tcp -j REDIRECT --to 8123\n";

    final static String CMD_IPTABLES_DNAT_ADD_SOCKS = "iptables -t nat -A OUTPUT -p tcp -j DNAT --to-destination 127.0.0.1:8123\n";

    private static final String TAG = "ProxyDroidService";

    private String host;
    private String hostName;
    private int port;
    private String bypassAddrs = "";
    private String user;
    private String password;
    private String domain;
    private String proxyType = "http";
    private String auth = "false";
    private boolean isAuth = false;
    private boolean isNTLM = false;
    private boolean isPAC = false;

    public String basePath = "/data/data/org.proxydroid/";

    private SharedPreferences settings = null;

    private boolean hasRedirectSupport = true;
    private boolean isAutoSetProxy = false;
    private boolean isBypassApps = false;

    private ProxyedApp[] apps;

    /*
     * This is a hack see
     * http://www.mail-archive.com/android-developers@googlegroups
     * .com/msg18298.html we are not really able to decide if the service was
     * started. So we remember a week reference to it. We set it if we are
     * running and clear it if we are stopped. If anything goes wrong, the
     * reference will hopefully vanish
     */
    private static WeakReference<ProxyDroidService> sRunningInstance = null;

    public static boolean isServiceStarted() {
        final boolean isServiceStarted;
        if (sRunningInstance == null) {
            isServiceStarted = false;
        } else if (sRunningInstance.get() == null) {
            isServiceStarted = false;
            sRunningInstance = null;
        } else {
            isServiceStarted = true;
        }
        return isServiceStarted;
    }

    private void markServiceStarted() {
        sRunningInstance = new WeakReference<ProxyDroidService>(this);
    }

    private void markServiceStopped() {
        sRunningInstance = null;
    }

    /**
     * Internal method to request actual PTY terminal once we've finished
     * authentication. If called before authenticated, it will just fail.
     */
    private void enableProxy() {

        String proxyHost = host;
        int proxyPort = port;

        try {
            if ("https".equals(proxyType)) {
                String src = "-L=http://127.0.0.1:8126";
                String auth = "";
                if (!user.isEmpty() && !password.isEmpty()) {
                    auth = user + ":" + password + "@";
                }
                String dst = "-F=https://"  + auth + hostName + ":" + port +"?ip=" + host;

                // Start gost here
                Utils.runRootCommand(basePath + "gost.sh "  + basePath + " " + src + " " + dst);

                // Reset host / port
                proxyHost = "127.0.0.1";
                proxyPort = 8126;
                proxyType = "http";
            }

            if (proxyType.equals("http") && isAuth && isNTLM) {
                Utils.runRootCommand(basePath + "proxy.sh " + basePath + " start http 127.0.0.1 8025 false\n"
                        + basePath + "cntlm -P " + basePath + "cntlm.pid -l 8025 -u " + user
                        + (!domain.equals("") ? "@" + domain : "@local") + " -p " + password + " "
                        + proxyHost + ":" + proxyPort + "\n");
            } else {
                final String u = Utils.preserve(user);
                final String p = Utils.preserve(password);

                Utils.runRootCommand(basePath + "proxy.sh " + basePath + " start" + " " + proxyType + " " + proxyHost
                        + " " + proxyPort + " " + auth + " \"" + u + "\" \"" + p + "\"");
            }

            StringBuilder cmd = new StringBuilder();

            cmd.append(CMD_IPTABLES_RETURN.replace("0.0.0.0", host));

            if (bypassAddrs != null && !bypassAddrs.equals("")) {
                String[] addrs = Profile.decodeAddrs(bypassAddrs);
                for (String addr : addrs)
                    cmd.append(CMD_IPTABLES_RETURN.replace("0.0.0.0", addr));
            }

            String redirectCmd = CMD_IPTABLES_REDIRECT_ADD_HTTP;
            String dnatCmd = CMD_IPTABLES_DNAT_ADD_HTTP;

            if (proxyType.equals("socks4") || proxyType.equals("socks5")) {
                redirectCmd = CMD_IPTABLES_REDIRECT_ADD_SOCKS;
                dnatCmd = CMD_IPTABLES_DNAT_ADD_SOCKS;
            } else if (proxyType.equals("http-tunnel")) {
                redirectCmd = CMD_IPTABLES_REDIRECT_ADD_HTTP_TUNNEL;
                dnatCmd = CMD_IPTABLES_DNAT_ADD_HTTP_TUNNEL;
            }

            if (isBypassApps) {
                // for host specified apps
                if (apps == null || apps.length <= 0)
                    apps = AppManager.getProxyedApps(this, false);

                for (ProxyedApp app : apps) {
                    if (app != null && app.isProxyed()) {
                        cmd.append(CMD_IPTABLES_RETURN.replace("-d 0.0.0.0", "").replace("-t nat",
                                "-t nat -m owner --uid-owner " + app.getUid()));
                    }
                }

            }

            if (isAutoSetProxy || isBypassApps) {
                cmd.append(hasRedirectSupport ? redirectCmd : dnatCmd);
            } else {
                // for host specified apps
                if (apps == null || apps.length <= 0)
                    apps = AppManager.getProxyedApps(this, true);

                for (ProxyedApp app : apps) {
                    if (app != null && app.isProxyed()) {
                        cmd.append((hasRedirectSupport ? redirectCmd : dnatCmd).replace("-t nat",
                                "-t nat -m owner --uid-owner " + app.getUid()));
                    }
                }
            }

            String rules = cmd.toString();

            rules = rules.replace("iptables", Utils.getIptables());

            Utils.runRootCommand(rules);

        } catch (Exception e) {
            Log.e(TAG, "Error setting up port forward during connect", e);
        }

    }

    /**
     * Called when the activity is first created.
     */
    public boolean handleCommand() {

        String filePath = getFilesDir().getAbsolutePath();

        Utils.runRootCommand(
                "chmod 700 " + filePath + "/redsocks\n"
                        + "chmod 700 " + filePath + "/proxy.sh\n"
                        + "chmod 700 " + filePath + "/gost.sh\n"
                        + "chmod 700 " + filePath + "/cntlm\n"
                        + "chmod 700 " + filePath + "/gost\n");

        enableProxy();

        return true;
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

//    notification.defaults |= Notification.DEFAULT_LIGHTS;
    }

    private void createNotificationChannel() {
        // Create the NotificationChannel, but only on API 26+ because
        // the NotificationChannel class is new and not in the support library
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            CharSequence name = "ProxyDroid Service";
            String description = "ProxyDroid Background Service";
            int importance = NotificationManager.IMPORTANCE_DEFAULT;
            NotificationChannel channel = new NotificationChannel("Service", name, importance);
            channel.setDescription(description);
            // Register the channel with the system; you can't change the importance
            // or other notification behaviors after this
            notificationManager.createNotificationChannel(channel);
        }
    }

    private void notifyAlert(String title, String info) {
        NotificationCompat.Builder builder = new NotificationCompat.Builder(this, "Service");

        initSoundVibrateLights(builder);

        builder.setAutoCancel(false);
        builder.setTicker(title);
        builder.setContentTitle(getString(R.string.app_name) + " | "
                + getProfileName());
        builder.setContentText(info);
        builder.setSmallIcon(R.drawable.ic_stat_proxydroid);
        builder.setContentIntent(pendIntent);
        builder.setPriority(NotificationCompat.PRIORITY_LOW);
        builder.setOngoing(true);

        startForeground(1, builder.build());
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
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
        pendIntent = PendingIntent.getActivity(this, 0, intent, 0);
    }

    /**
     * Called when the activity is closed.
     */
    @Override
    public void onDestroy() {

        ((ProxyDroidApplication)getApplication())
                .firebaseAnalytics.logEvent("service_stop", null);

        Utils.setConnecting(true);

        notificationManager.cancelAll();
        stopForeground(true);

        // Make sure the connection is closed, important here
        onDisconnect();

        // for widget, maybe exception here
        try {
            RemoteViews views = new RemoteViews(getPackageName(), R.layout.proxydroid_appwidget);
            views.setImageViewResource(R.id.serviceToggle, R.drawable.off);
            AppWidgetManager awm = AppWidgetManager.getInstance(this);
            awm.updateAppWidget(
                    awm.getAppWidgetIds(new ComponentName(this, ProxyDroidWidgetProvider.class)),
                    views);
        } catch (Exception ignore) {
            // Nothing
        }

        Editor ed = settings.edit();
        ed.putBoolean("isRunning", false);
        ed.commit();

        try {
            notificationManager.cancel(0);
        } catch (Exception ignore) {
            // Nothing
        }

        markServiceStopped();

        Utils.setConnecting(false);

        super.onDestroy();

    }

    private void onDisconnect() {

        final StringBuilder sb = new StringBuilder();

        sb.append(Utils.getIptables()).append(" -t nat -F OUTPUT\n");

        if ("https".equals(proxyType)) {
            sb.append("kill -9 `cat " + basePath + "gost.pid`\n");
        }

        if (isAuth && isNTLM) {
            sb.append("kill -9 `cat " + basePath + "cntlm.pid`\n");
        }

        sb.append(basePath + "proxy.sh " + basePath + " stop\n");

        new Thread() {
            @Override
            public void run() {
                Utils.runRootCommand(sb.toString());
            }
        }.start();

    }

    final Handler handler = new Handler() {
        @Override
        public void handleMessage(Message msg) {
            Editor ed = settings.edit();
            switch (msg.what) {
                case MSG_CONNECT_START:
                    ed.putBoolean("isConnecting", true);
                    Utils.setConnecting(true);
                    break;
                case MSG_CONNECT_FINISH:
                    ed.putBoolean("isConnecting", false);
                    Utils.setConnecting(false);
                    break;
                case MSG_CONNECT_SUCCESS:
                    ed.putBoolean("isRunning", true);
                    break;
                case MSG_CONNECT_FAIL:
                    ed.putBoolean("isRunning", false);
                    break;
                case MSG_CONNECT_PAC_ERROR:
                    Toast.makeText(ProxyDroidService.this, R.string.msg_pac_error, Toast.LENGTH_SHORT)
                            .show();
                    break;
                case MSG_CONNECT_RESOLVE_ERROR:
                    Toast.makeText(ProxyDroidService.this, R.string.msg_resolve_error,
                            Toast.LENGTH_SHORT).show();
                    break;
            }
            ed.commit();
            super.handleMessage(msg);
        }
    };

    // Local Ip address
    public String getLocalIpAddress() {
        try {
            for (Enumeration<NetworkInterface> en = NetworkInterface.getNetworkInterfaces(); en
                    .hasMoreElements(); ) {
                NetworkInterface intf = en.nextElement();
                for (Enumeration<InetAddress> enumIpAddr = intf.getInetAddresses(); enumIpAddr
                        .hasMoreElements(); ) {
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

    private boolean getAddress() {

        if (isPAC) {
            try {
                PacScriptSource src = new UrlPacScriptSource(host);
                PacProxySelector ps = new PacProxySelector(src);
                URI uri = new URI("http://gaednsproxy.appspot.com");
                List<Proxy> list = ps.select(uri);
                if (list != null && list.size() != 0) {

                    Proxy p = list.get(0);

                    // No proxy means error
                    if (p.equals(Proxy.NO_PROXY) || p.host == null || p.port == 0 || p.type == null) {
                        handler.sendEmptyMessageDelayed(MSG_CONNECT_PAC_ERROR, 3000);
                        return false;
                    }

                    proxyType = p.type;
                    host = p.host;
                    port = p.port;

                } else {
                    // No proxy means error
                    handler.sendEmptyMessageDelayed(MSG_CONNECT_PAC_ERROR, 3000);
                    return false;
                }
            } catch (URISyntaxException ignore) {
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
        Log.d(TAG, "Local Port: " + port);

        return true;
    }

    private String getProfileName() {
        SharedPreferences settings = PreferenceManager.getDefaultSharedPreferences(this);
        return settings.getString("profile" + settings.getString("profile", "1"),
                getString(R.string.profile_base) + " " + settings.getString("profile", "1"));
    }

    // This is the old onStart method that will be called on the pre-2.0
    // platform. On 2.0 or later we override onStartCommand() so this
    // method will not be called.
    @Override
    public void onStart(Intent intent, int startId) {

        super.onStart(intent, startId);

        if (intent == null || intent.getExtras() == null) {
            return;
        }

        ((ProxyDroidApplication)getApplication())
                .firebaseAnalytics.logEvent("service_start", null);

        Log.d(TAG, "Service Start");

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
            auth = "true";
            user = bundle.getString("user");
            password = bundle.getString("password");
        } else {
            auth = "false";
            user = "";
            password = "";
        }

        if (isNTLM)
            domain = bundle.getString("domain");
        else
            domain = "";

        new Thread(new Runnable() {
            @Override
            public void run() {

                handler.sendEmptyMessage(MSG_CONNECT_START);

                hasRedirectSupport = Utils.getHasRedirectSupport();

                if (getAddress() && handleCommand()) {
                    // Connection and forward successful
                    notifyAlert(getString(R.string.forward_success) + " | " + getProfileName(),
                            getString(R.string.service_running));

                    handler.sendEmptyMessage(MSG_CONNECT_SUCCESS);

                    // for widget, maybe exception here
                    try {
                        RemoteViews views = new RemoteViews(getPackageName(),
                                R.layout.proxydroid_appwidget);
                        views.setImageViewResource(R.id.serviceToggle, R.drawable.on);
                        AppWidgetManager awm = AppWidgetManager.getInstance(ProxyDroidService.this);
                        awm.updateAppWidget(awm.getAppWidgetIds(new ComponentName(
                                ProxyDroidService.this, ProxyDroidWidgetProvider.class)), views);
                    } catch (Exception ignore) {
                        // Nothing
                    }

                } else {
                    // Connection or forward unsuccessful

                    stopSelf();
                    handler.sendEmptyMessage(MSG_CONNECT_FAIL);
                }

                handler.sendEmptyMessage(MSG_CONNECT_FINISH);

            }
        }).start();

        markServiceStarted();
    }

}
