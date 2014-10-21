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

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.SharedPreferences.Editor;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.os.Handler;
import android.preference.PreferenceManager;
import android.util.Log;
import com.ksmaze.android.preference.ListPreferenceMultiSelect;
import org.proxydroid.utils.Constraints;
import org.proxydroid.utils.Utils;

public class ConnectivityBroadcastReceiver extends BroadcastReceiver {

  private Handler mHandler = new Handler();

  private static final String TAG = "ConnectivityBroadcastReceiver";

  @Override
  public void onReceive(final Context context, final Intent intent) {

    if (Utils.isConnecting()) return;

    if (!intent.getAction().equals(ConnectivityManager.CONNECTIVITY_ACTION)) return;

    mHandler.post(new Runnable() {
      @Override
      public void run() {

        // only switching profiles when needed
        ConnectivityManager manager = (ConnectivityManager) context
            .getSystemService(Context.CONNECTIVITY_SERVICE);
        NetworkInfo networkInfo = manager.getActiveNetworkInfo();

        if (networkInfo != null) {
          if (networkInfo.getState() == NetworkInfo.State.CONNECTING
              || networkInfo.getState() == NetworkInfo.State.DISCONNECTING
              || networkInfo.getState() == NetworkInfo.State.UNKNOWN)
            return;
        } else {
          if (!Utils.isWorking()) return;
        }

        SharedPreferences settings = PreferenceManager
            .getDefaultSharedPreferences(context);
        Profile mProfile = new Profile();
        mProfile.getProfile(settings);

        // Store current settings first
        String oldProfile = settings.getString("profile", "1");
        Editor ed = settings.edit();
        ed.putString(oldProfile, mProfile.toString());
        ed.commit();

        // Load all profiles
        String[] profileValues = settings.getString("profileValues", "").split(
            "\\|");
        String curSSID = null;
        String lastSSID = settings.getString("lastSSID", "-1");
        boolean autoConnect = false;

        // Test on each profile
        for (String profile : profileValues) {
          String profileString = settings.getString(profile, "");
          mProfile.decodeJson(profileString);
          curSSID = onlineSSID(context, mProfile.getSsid());
          if (mProfile.isAutoConnect() && curSSID != null) {
            // Enable auto connect
            autoConnect = true;

            // XXX: Switch profile first
            ed = settings.edit();
            ed.putString("profile", profile);
            ed.commit();

            // Then switch profile values
            mProfile.setProfile(settings);
            break;
          }
        }

        if (networkInfo == null) {
          if (!lastSSID.equals(Constraints.ONLY_3G)
              && !lastSSID.equals(Constraints.WIFI_AND_3G)
              && !lastSSID.equals(Constraints.ONLY_WIFI)) {
            if (Utils.isWorking()) {
              context.stopService(new Intent(context,
                  ProxyDroidService.class));
            }
          }
        } else {
          // no network available now
          if (networkInfo.getState() != NetworkInfo.State.CONNECTED)
            return;

          if (networkInfo.getType() == ConnectivityManager.TYPE_WIFI) {
            // if no last SSID, should give up here
            if (!lastSSID.equals("-1")) {
              // get WIFI info
              WifiManager wm = (WifiManager) context
                  .getSystemService(Context.WIFI_SERVICE);
              WifiInfo wInfo = wm.getConnectionInfo();
              if (wInfo != null) {
                // compare with the current SSID
                String current = wInfo.getSSID();
                if (current != null) current = current.replace("\"", "");
                if (current != null && !current.equals(lastSSID)) {
                  // need to switch profile, so stop service first
                  if (Utils.isWorking())
                    context.stopService(new Intent(context,
                        ProxyDroidService.class));
                }
              }
            }
          } else {
            // still satisfy the last trigger
            if (!lastSSID.equals(Constraints.ONLY_3G)
                && !lastSSID.equals(Constraints.WIFI_AND_3G)) {
              if (Utils.isWorking())
                context.stopService(new Intent(context,
                    ProxyDroidService.class));
            }
          }
        }

        if (autoConnect) {
          if (!Utils.isWorking()) {
            ProxyDroidReceiver pdr = new ProxyDroidReceiver();
            ed = settings.edit();
            ed.putString("lastSSID", curSSID);
            ed.commit();
            Utils.setConnecting(true);
            pdr.onReceive(context, intent);
          }
        }
      }
    });
  }

  public String onlineSSID(Context context, String ssid) {
    String ssids[] = ListPreferenceMultiSelect.parseStoredValue(ssid);
    if (ssids == null)
      return null;
    if (ssids.length < 1)
      return null;
    ConnectivityManager manager = (ConnectivityManager) context
        .getSystemService(Context.CONNECTIVITY_SERVICE);
    NetworkInfo networkInfo = manager.getActiveNetworkInfo();
    if (networkInfo == null)
      return null;
    if (networkInfo.getType() != ConnectivityManager.TYPE_WIFI) {
      for (String item : ssids) {
        if (Constraints.WIFI_AND_3G.equals(item))
          return item;
        if (Constraints.ONLY_3G.equals(item))
          return item;
      }
      return null;
    }
    WifiManager wm = (WifiManager) context
        .getSystemService(Context.WIFI_SERVICE);
    WifiInfo wInfo = wm.getConnectionInfo();
    if (wInfo == null || wInfo.getSSID() == null)
      return null;
    String current = wInfo.getSSID();
    if (current == null || "".equals(current))
      return null;
    current = current.replace("\"", "");
    for (String item : ssids) {
      if (Constraints.WIFI_AND_3G.equals(item))
        return item;
      if (Constraints.ONLY_WIFI.equals(item))
        return item;
      if (current.equals(item))
        return item;
    }
    return null;
  }

}
