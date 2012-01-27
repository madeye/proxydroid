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

import java.util.ArrayList;

import com.ksmaze.android.preference.ListPreferenceMultiSelect;

import android.app.ActivityManager;
import android.app.AlertDialog;
import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.ActivityManager.RunningServiceInfo;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.SharedPreferences.Editor;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.preference.PreferenceManager;
import android.util.Log;

public class ConnectivityBroadcastReceiver extends BroadcastReceiver {

	private static final String TAG = "ConnectivityBroadcastReceiver";

	@Override
	public synchronized void onReceive(Context context, Intent intent) {
		String action = intent.getAction();

		if (!action.equals(ConnectivityManager.CONNECTIVITY_ACTION)) {
			Log.w(TAG, "onReceived() called uncorrectly");
			return;
		}

		if (Utils.isConnecting())
			return;

		Log.e(TAG, "Connection Test");

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
		boolean autoConnect = false;

		// Test on each profile
		for (String profile : profileValues) {
			String profileString = settings.getString(profile, "");
			mProfile.decodeJson(profileString);
			curSSID = onlineSSID(context, mProfile.getSsid());
			if (mProfile.isAutoConnect() && curSSID != null) {

				// XXX: Switch profile first
				ed = settings.edit();
				ed.putString("profile", profile);
				ed.commit();

				autoConnect = true;

				// Then switch profile values
				mProfile.setProfile(settings);
				break;
			}
		}

		// only switching profiles when needed
		ConnectivityManager manager = (ConnectivityManager) context
				.getSystemService(Context.CONNECTIVITY_SERVICE);
		NetworkInfo networkInfo = manager.getActiveNetworkInfo();
		if (networkInfo == null) {
			if (Utils.isWorked()) {
				context.stopService(new Intent(context, ProxyDroidService.class));
			}
		} else {
			String lastSSID = settings.getString("lastSSID", "-1");

			if (networkInfo.getTypeName().equals("WIFI")) {
				if (!lastSSID.equals("-1")) {
					WifiManager wm = (WifiManager) context
							.getSystemService(Context.WIFI_SERVICE);
					WifiInfo wInfo = wm.getConnectionInfo();
					if (wInfo != null) {
						String current = wInfo.getSSID();
						if (current != null && !current.equals(lastSSID)) {
							if (Utils.isWorked()) {
								context.stopService(new Intent(context,
										ProxyDroidService.class));
							}
						}
					}
				}
			} else {
				if (!lastSSID.equals("2G/3G")) {
					if (Utils.isWorked()) {
						context.stopService(new Intent(context,
								ProxyDroidService.class));
					}
				}
			}
		}

		if (autoConnect && curSSID != null) {
			if (!Utils.isWorked()) {
				ProxyDroidReceiver pdr = new ProxyDroidReceiver();
				ed = settings.edit();
				ed.putString("lastSSID", curSSID);
				ed.commit();
				Utils.setConnecting(true);
				pdr.onReceive(context, intent);
			}
		}

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
		if (!networkInfo.getTypeName().equals("WIFI")) {
			for (String item : ssids) {
				if (item.equals("2G/3G"))
					return "2G/3G";
			}
			return null;
		}
		WifiManager wm = (WifiManager) context
				.getSystemService(Context.WIFI_SERVICE);
		WifiInfo wInfo = wm.getConnectionInfo();
		if (wInfo == null)
			return null;
		String current = wInfo.getSSID();
		if (current == null || current.equals(""))
			return null;
		for (String item : ssids) {
			if (item.equals(current))
				return item;
		}
		return null;
	}

}