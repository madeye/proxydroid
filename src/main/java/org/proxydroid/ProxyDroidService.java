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

import android.app.Notification;
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
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Message;
import android.preference.PreferenceManager;
import android.util.Log;
import android.widget.RemoteViews;
import android.widget.Toast;
import com.btr.proxy.selector.pac.PacProxySelector;
import com.btr.proxy.selector.pac.PacScriptSource;
import com.btr.proxy.selector.pac.Proxy;
import com.btr.proxy.selector.pac.UrlPacScriptSource;
import com.flurry.android.FlurryAgent;
import org.proxydroid.utils.Utils;

import java.io.FileOutputStream;
import java.lang.ref.WeakReference;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.*;
import java.util.Enumeration;
import java.util.List;

public class ProxyDroidService extends Service {

  private Notification notification;
  private NotificationManager notificationManager;
  private PendingIntent pendIntent;

  public static final String BASE = "/data/data/org.proxydroid/";
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
  private int port;
  private String bypassAddrs = "";
  private String user;
  private String password;
  private String domain;
  private String proxyType = "http";
  private String certificate;
  private String auth = "false";
  private boolean isAuth = false;
  private boolean isNTLM = false;
  private boolean isDNSProxy = false;
  private boolean isPAC = false;

  private DNSProxy dnsServer = null;
  private int dnsPort = 0;

  Process NTLMProcess;

  private SharedPreferences settings = null;

  private boolean hasRedirectSupport = true;
  private boolean isAutoSetProxy = false;
  private boolean isBypassApps = false;

  private ProxyedApp apps[];

  private static final Class<?>[] mSetForegroundSignature = new Class[]{boolean.class};
  private static final Class<?>[] mStartForegroundSignature = new Class[]{int.class,
      Notification.class};
  private static final Class<?>[] mStopForegroundSignature = new Class[]{boolean.class};

  private Method mSetForeground;
  private Method mStartForeground;
  private Method mStopForeground;

  private Object[] mSetForegroundArgs = new Object[1];
  private Object[] mStartForegroundArgs = new Object[2];
  private Object[] mStopForegroundArgs = new Object[1];

  void invokeMethod(Method method, Object[] args) {
    try {
      method.invoke(this, mStartForegroundArgs);
    } catch (InvocationTargetException e) {
      // Should not happen.
      Log.w("ApiDemos", "Unable to invoke method", e);
    } catch (IllegalAccessException e) {
      // Should not happen.
      Log.w("ApiDemos", "Unable to invoke method", e);
    }
  }

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
   * This is a wrapper around the new startForeground method, using the older
   * APIs if it is not available.
   */
  void startForegroundCompat(int id, Notification notification) {
    // If we have the new startForeground API, then use it.
    if (mStartForeground != null) {
      mStartForegroundArgs[0] = id;
      mStartForegroundArgs[1] = notification;
      invokeMethod(mStartForeground, mStartForegroundArgs);
      return;
    }

    // Fall back on the old API.
    mSetForegroundArgs[0] = Boolean.TRUE;
    invokeMethod(mSetForeground, mSetForegroundArgs);
    notificationManager.notify(id, notification);
  }

  /**
   * This is a wrapper around the new stopForeground method, using the older
   * APIs if it is not available.
   */
  void stopForegroundCompat(int id) {
    // If we have the new stopForeground API, then use it.
    if (mStopForeground != null) {
      mStopForegroundArgs[0] = Boolean.TRUE;
      try {
        mStopForeground.invoke(this, mStopForegroundArgs);
      } catch (InvocationTargetException e) {
        // Should not happen.
        Log.w("ApiDemos", "Unable to invoke stopForeground", e);
      } catch (IllegalAccessException e) {
        // Should not happen.
        Log.w("ApiDemos", "Unable to invoke stopForeground", e);
      }
      return;
    }

    // Fall back on the old API. Note to cancel BEFORE changing the
    // foreground state, since we could be killed at that point.
    notificationManager.cancel(id);
    mSetForegroundArgs[0] = Boolean.FALSE;
    invokeMethod(mSetForeground, mSetForegroundArgs);
  }

  /**
   * Internal method to request actual PTY terminal once we've finished
   * authentication. If called before authenticated, it will just fail.
   */
  private void enableProxy() {

    String proxyHost = host;
    int proxyPort = port;

    try {

      if ("https".equals(proxyType) || "spdy".equals(proxyType)) {

        if ("https".equals(proxyType)) {
          // Configure file for Stunnel
          FileOutputStream fs = new FileOutputStream(BASE + "stunnel.conf");
          String conf = "debug = 0\n" + "client = yes\n" + "pid = " + BASE + "stunnel.pid\n"
              + "[https]\n" + "sslVersion = all\n" + "accept = 127.0.0.1:8126\n"
              + "connect = " + host + ":" + port + "\n";
          if (0 != certificate.length())
              conf = conf + "cert = " + BASE + "client.pem\n";
          fs.write(conf.getBytes());
          fs.flush();
          fs.close();

          // Certificate file for Stunnel
          if (0 != certificate.length()){
              fs = new FileOutputStream(BASE + "client.pem");
              fs.write(certificate.getBytes());
              fs.flush();
              fs.close();
              Utils.runCommand("chmod 0600 " + BASE + "client.pem");
          }

          // Start stunnel here
          Utils.runRootCommand(BASE + "stunnel " + BASE + "stunnel.conf");
        } else if ("spdy".equals(proxyType)) {
          Utils.runRootCommand(BASE + "shrpx -D -k -p -f 127.0.0.1,8126 -b " + host + "," + port
              + " --pid-file=" + BASE + "shrpx.pid");
        }

        // Reset host / port
        proxyHost = "127.0.0.1";
        proxyPort = 8126;
        proxyType = "http";

      }

      if (proxyType.equals("http") && isAuth && isNTLM) {
        Utils.runRootCommand(BASE + "proxy.sh start http 127.0.0.1 8025 false\n" + BASE
            + "cntlm -P " + BASE + "cntlm.pid -l 8025 -u " + user
            + (!domain.equals("") ? "@" + domain : "@local") + " -p " + password + " "
            + proxyHost + ":" + proxyPort + "\n");
      } else {
        final String u = Utils.preserve(user);
        final String p = Utils.preserve(password);

        Utils.runRootCommand(BASE + "proxy.sh start" + " " + proxyType + " " + proxyHost
            + " " + proxyPort + " " + auth + " \"" + u + "\" \"" + p + "\"");
      }

      StringBuilder cmd = new StringBuilder();

      if (isDNSProxy) {
        dnsServer = new DNSProxy(this, dnsPort);
        dnsPort = dnsServer.init();

        Thread dnsThread = new Thread(dnsServer);
        dnsThread.setDaemon(true);
        dnsThread.start();

        if (hasRedirectSupport)
          cmd.append("iptables -t nat -A OUTPUT -p udp --dport 53 -j REDIRECT --to ").append(dnsPort).append("\n");
        else
          cmd.append("iptables -t nat -A OUTPUT -p udp --dport 53 -j DNAT --to-destination 127.0.0.1:").append(dnsPort).append("\n");

      }

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

    Utils.runRootCommand("chmod 700 /data/data/org.proxydroid/iptables\n"
        + "chmod 700 /data/data/org.proxydroid/redsocks\n"
        + "chmod 700 /data/data/org.proxydroid/proxy.sh\n"
        + "chmod 700 /data/data/org.proxydroid/cntlm\n"
        + "chmod 700 /data/data/org.proxydroid/stunnel\n"
        + "chmod 700 /data/data/org.proxydroid/shrpx\n");

    enableProxy();

    return true;
  }

  private void initSoundVibrateLights(Notification notification) {
    final String ringtone = settings.getString("settings_key_notif_ringtone", null);
    AudioManager audioManager = (AudioManager) this.getSystemService(Context.AUDIO_SERVICE);
    if (audioManager.getStreamVolume(AudioManager.STREAM_RING) == 0) {
      notification.sound = null;
    } else if (ringtone != null)
      notification.sound = Uri.parse(ringtone);
    else
      notification.defaults |= Notification.DEFAULT_SOUND;

    if (settings.getBoolean("settings_key_notif_vibrate", false)) {
      notification.vibrate = new long[]{0, 1000, 500, 1000, 500, 1000};
    }

    notification.defaults |= Notification.DEFAULT_LIGHTS;
  }

  private void notifyAlert(String title, String info) {
    notification.icon = R.drawable.ic_stat_proxydroid;
    notification.tickerText = title;
    notification.flags = Notification.FLAG_ONGOING_EVENT;
    initSoundVibrateLights(notification);
    // notification.defaults = Notification.DEFAULT_SOUND;
    notification.setLatestEventInfo(this, getString(R.string.app_name) + " | "
        + getProfileName(), info, pendIntent);
    startForegroundCompat(1, notification);
  }

  private void notifyAlert(String title, String info, int flags) {
    notification.icon = R.drawable.ic_stat_proxydroid;
    notification.tickerText = title;
    notification.flags = flags;
    initSoundVibrateLights(notification);
    notification.setLatestEventInfo(this, getString(R.string.app_name) + " | "
        + getProfileName(), info, pendIntent);
    notificationManager.notify(0, notification);
  }

  @Override
  public IBinder onBind(Intent intent) {
    return null;
  }

  @Override
  public void onCreate() {
    super.onCreate();
    settings = PreferenceManager.getDefaultSharedPreferences(this);
    notificationManager = (NotificationManager) this.getSystemService(NOTIFICATION_SERVICE);

    Intent intent = new Intent(this, ProxyDroid.class);
    intent.setFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP);
    pendIntent = PendingIntent.getActivity(this, 0, intent, 0);
    notification = new Notification();

    try {
      mStartForeground = getClass().getMethod("startForeground", mStartForegroundSignature);
      mStopForeground = getClass().getMethod("stopForeground", mStopForegroundSignature);
    } catch (NoSuchMethodException e) {
      // Running on an older platform.
      mStartForeground = mStopForeground = null;
    }

    try {
      mSetForeground = getClass().getMethod("setForeground", mSetForegroundSignature);
    } catch (NoSuchMethodException e) {
      throw new IllegalStateException(
          "OS doesn't have Service.startForeground OR Service.setForeground!");
    }
  }

  /**
   * Called when the activity is closed.
   */
  @Override
  public void onDestroy() {

    Utils.setConnecting(true);

    stopForegroundCompat(1);

    FlurryAgent.onEndSession(this);

    notifyAlert(getString(R.string.forward_stop), getString(R.string.service_stopped),
        Notification.FLAG_AUTO_CANCEL);

    // Make sure the connection is closed, important here
    onDisconnect();

    try {
      if (dnsServer != null) {
        dnsServer.close();
        dnsServer = null;
      }
    } catch (Exception e) {
      Log.e(TAG, "DNS Server close unexpected");
    }

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
      sb.append("kill -9 `cat /data/data/org.proxydroid/stunnel.pid`\n");
    }

    if (isAuth && isNTLM) {
      sb.append("kill -9 `cat /data/data/org.proxydroid/cntlm.pid`\n");
    }

    sb.append(BASE + "proxy.sh stop\n");

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

    String tmp = host;

    try {
      host = InetAddress.getByName(host).getHostAddress();
    } catch (UnknownHostException e) {
      host = tmp;
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

    FlurryAgent.onStartSession(this, "AV372I7R5YYD52NWPUPE");

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
    isDNSProxy = bundle.getBoolean("isDNSProxy");
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

    if ("https".equals(proxyType))
        certificate = bundle.getString("certificate");

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
