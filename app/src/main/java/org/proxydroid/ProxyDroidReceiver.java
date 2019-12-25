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
import android.os.Bundle;
import android.preference.PreferenceManager;

public class ProxyDroidReceiver extends BroadcastReceiver {

	@Override
	public void onReceive(Context context, Intent intent) {

		SharedPreferences settings = PreferenceManager
				.getDefaultSharedPreferences(context);
		Profile mProfile = new Profile();
		mProfile.getProfile(settings);

		if (mProfile.isAutoConnect()) {

			Intent it = new Intent(context, ProxyDroidService.class);
			Bundle bundle = new Bundle();
			bundle.putString("host", mProfile.getHost());
			bundle.putString("user", mProfile.getUser());
			bundle.putString("bypassAddrs", mProfile.getBypassAddrs());
			bundle.putString("password", mProfile.getPassword());
			bundle.putString("domain", mProfile.getDomain());

			bundle.putString("proxyType", mProfile.getProxyType());
			bundle.putBoolean("isAutoSetProxy", mProfile.isAutoSetProxy());
			bundle.putBoolean("isBypassApps", mProfile.isBypassApps());
			bundle.putBoolean("isAuth", mProfile.isAuth());
			bundle.putBoolean("isNTLM", mProfile.isNTLM());
			bundle.putBoolean("isDNSProxy", mProfile.isDNSProxy());
			bundle.putBoolean("isPAC", mProfile.isPAC());

			bundle.putInt("port", mProfile.getPort());

			it.putExtras(bundle);
			context.startService(it);
		}
	}

}
