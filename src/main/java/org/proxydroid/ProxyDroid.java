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

import android.app.AlertDialog;
import android.app.ProgressDialog;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.SharedPreferences.Editor;
import android.content.SharedPreferences.OnSharedPreferenceChangeListener;
import android.content.pm.PackageManager.NameNotFoundException;
import android.content.res.AssetManager;
import android.net.ConnectivityManager;
import android.net.Uri;
import android.net.wifi.WifiConfiguration;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Message;
import android.preference.CheckBoxPreference;
import android.preference.EditTextPreference;
import android.preference.ListPreference;
import android.preference.Preference;
import android.preference.PreferenceManager;
import android.preference.PreferenceScreen;
import android.preference.SwitchPreference;
import android.util.Log;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewParent;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.Toast;
import com.actionbarsherlock.app.SherlockPreferenceActivity;
import com.actionbarsherlock.view.Menu;
import com.actionbarsherlock.view.MenuItem;
import com.flurry.android.FlurryAgent;
import com.google.ads.AdRequest;
import com.google.ads.AdSize;
import com.google.ads.AdView;
import com.j256.ormlite.android.apptools.OpenHelperManager;
import com.j256.ormlite.dao.Dao;
import com.ksmaze.android.preference.ListPreferenceMultiSelect;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.List;
import org.proxydroid.db.DNSResponse;
import org.proxydroid.db.DatabaseHelper;
import org.proxydroid.utils.Constraints;
import org.proxydroid.utils.Utils;

public class ProxyDroid extends SherlockPreferenceActivity
    implements OnSharedPreferenceChangeListener {

  private static final String TAG = "ProxyDroid";
  private static final int MSG_UPDATE_FINISHED = 0;
  private static final int MSG_NO_ROOT = 1;
  final Handler handler = new Handler() {
    @Override
    public void handleMessage(Message msg) {
      switch (msg.what) {
        case MSG_UPDATE_FINISHED:
          Toast.makeText(ProxyDroid.this, getString(R.string.update_finished), Toast.LENGTH_LONG)
              .show();
          break;
        case MSG_NO_ROOT:
          showAToast(getString(R.string.require_root_alert));
          break;
      }
      super.handleMessage(msg);
    }
  };
  private ProgressDialog pd = null;
  private String profile;
  private Profile mProfile = new Profile();
  private CheckBoxPreference isAutoConnectCheck;
  private CheckBoxPreference isAutoSetProxyCheck;
  private CheckBoxPreference isAuthCheck;
  private CheckBoxPreference isNTLMCheck;
  private CheckBoxPreference isDNSProxyCheck;
  private CheckBoxPreference isPACCheck;
  private ListPreference profileList;
  private EditTextPreference hostText;
  private EditTextPreference portText;
  private EditTextPreference userText;
  private EditTextPreference passwordText;
  private EditTextPreference domainText;
  private EditTextPreference certificateText;
  private ListPreferenceMultiSelect ssidList;
  private ListPreference proxyTypeList;
  private Preference isRunningCheck;
  private CheckBoxPreference isBypassAppsCheck;
  private Preference proxyedApps;
  private Preference bypassAddrs;
  private AdView adView;
  private BroadcastReceiver ssidReceiver = new BroadcastReceiver() {
    @Override
    public void onReceive(Context context, Intent intent) {
      String action = intent.getAction();

      if (!action.equals(ConnectivityManager.CONNECTIVITY_ACTION)) {
        Log.w(TAG, "onReceived() called uncorrectly");
        return;
      }

      loadNetworkList();
    }
  };

  private void showAbout() {

    WebView web = new WebView(this);
    web.loadUrl("file:///android_asset/pages/about.html");
    web.setWebViewClient(new WebViewClient() {
      @Override
      public boolean shouldOverrideUrlLoading(WebView view, String url) {
        startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
        return true;
      }
    });

    String versionName = "";
    try {
      versionName = getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
    } catch (NameNotFoundException ex) {
      versionName = "";
    }

    new AlertDialog.Builder(this).setTitle(
        String.format(getString(R.string.about_title), versionName))
        .setCancelable(false)
        .setNegativeButton(getString(R.string.ok_iknow), new DialogInterface.OnClickListener() {
          @Override
          public void onClick(DialogInterface dialog, int id) {
            dialog.cancel();
          }
        })
        .setView(web)
        .create()
        .show();
  }

  private void CopyAssets() {
    AssetManager assetManager = getAssets();
    String[] files = null;
    try {
      if (Build.VERSION.SDK_INT >= 21)
          files = assetManager.list("api-16");
      else
          files = assetManager.list("");
    } catch (IOException e) {
      Log.e(TAG, e.getMessage());
    }
    if (files != null) {
      for (String file : files) {
        InputStream in = null;
        OutputStream out = null;
        try {

          if (Build.VERSION.SDK_INT >= 21)
            in = assetManager.open("api-16/" + file);
          else
            in = assetManager.open(file);
          out = new FileOutputStream("/data/data/org.proxydroid/" + file);
          copyFile(in, out);
          in.close();
          in = null;
          out.flush();
          out.close();
          out = null;
        } catch (Exception e) {
          Log.e(TAG, e.getMessage());
        }
      }
    }
  }

  private void copyFile(InputStream in, OutputStream out) throws IOException {
    byte[] buffer = new byte[1024];
    int read;
    while ((read = in.read(buffer)) != -1) {
      out.write(buffer, 0, read);
    }
  }

  private boolean isTextEmpty(String s, String msg) {
    if (s == null || s.length() <= 0) {
      showAToast(msg);
      return true;
    }
    return false;
  }

  private void loadProfileList() {
    SharedPreferences settings = PreferenceManager.getDefaultSharedPreferences(this);
    String[] profileEntries = settings.getString("profileEntries", "").split("\\|");
    String[] profileValues = settings.getString("profileValues", "").split("\\|");

    profileList.setEntries(profileEntries);
    profileList.setEntryValues(profileValues);
  }

  private void loadNetworkList() {
    WifiManager wm = (WifiManager) this.getSystemService(Context.WIFI_SERVICE);
    List<WifiConfiguration> wcs = wm.getConfiguredNetworks();
    String[] ssidEntries = null;
    int n = 3;

    if (wcs == null) {
      ssidEntries = new String[n];

      ssidEntries[0] = Constraints.WIFI_AND_3G;
      ssidEntries[1] = Constraints.ONLY_WIFI;
      ssidEntries[2] = Constraints.ONLY_3G;
    } else {
      ssidEntries = new String[wcs.size() + n];

      ssidEntries[0] = Constraints.WIFI_AND_3G;
      ssidEntries[1] = Constraints.ONLY_WIFI;
      ssidEntries[2] = Constraints.ONLY_3G;

      for (WifiConfiguration wc : wcs) {
        if (wc != null && wc.SSID != null) {
          ssidEntries[n++] = wc.SSID.replace("\"", "");
        } else {
          ssidEntries[n++] = "unknown";
        }
      }
    }
    ssidList.setEntries(ssidEntries);
    ssidList.setEntryValues(ssidEntries);
  }

  @Override
  public void onStart() {
    super.onStart();
    FlurryAgent.onStartSession(this, "AV372I7R5YYD52NWPUPE");
  }

  @Override
  public void onStop() {
    super.onStop();
    FlurryAgent.onEndSession(this);
  }

  private LinearLayout getLayout(ViewParent parent) {
    if (parent instanceof LinearLayout) return (LinearLayout) parent;
    if (parent != null) {
      return getLayout(parent.getParent());
    } else {
      return null;
    }
  }

  /** Called when the activity is first created. */
  @Override
  public void onCreate(Bundle savedInstanceState) {
    super.onCreate(savedInstanceState);
    addPreferencesFromResource(R.xml.proxydroid_preference);

    // Create the adView
    adView = new AdView(this, AdSize.SMART_BANNER, "a14db2c016cb9b6");
    // Lookup your LinearLayout assuming it’s been given
    // the attribute android:id="@+id/mainLayout"
    ViewParent parent = getListView().getParent();
    LinearLayout layout = getLayout(parent);
    if (layout != null) {
      // Add the adView to it
      layout.addView(adView, 0);
      // Initiate a generic request to load it with an ad
      AdRequest aq = new AdRequest();
      adView.loadAd(aq);
    }

    hostText = (EditTextPreference) findPreference("host");
    portText = (EditTextPreference) findPreference("port");
    userText = (EditTextPreference) findPreference("user");
    passwordText = (EditTextPreference) findPreference("password");
    domainText = (EditTextPreference) findPreference("domain");
    certificateText = (EditTextPreference) findPreference("certificate");
    bypassAddrs = findPreference("bypassAddrs");
    ssidList = (ListPreferenceMultiSelect) findPreference("ssid");
    proxyTypeList = (ListPreference) findPreference("proxyType");
    proxyedApps = findPreference("proxyedApps");
    profileList = (ListPreference) findPreference("profile");

    isRunningCheck = (Preference) findPreference("isRunning");
    isAutoSetProxyCheck = (CheckBoxPreference) findPreference("isAutoSetProxy");
    isAuthCheck = (CheckBoxPreference) findPreference("isAuth");
    isNTLMCheck = (CheckBoxPreference) findPreference("isNTLM");
    isDNSProxyCheck = (CheckBoxPreference) findPreference("isDNSProxy");
    isPACCheck = (CheckBoxPreference) findPreference("isPAC");
    isAutoConnectCheck = (CheckBoxPreference) findPreference("isAutoConnect");
    isBypassAppsCheck = (CheckBoxPreference) findPreference("isBypassApps");

    final SharedPreferences settings = PreferenceManager.getDefaultSharedPreferences(this);

    String profileValuesString = settings.getString("profileValues", "");

    if (profileValuesString.equals("")) {
      Editor ed = settings.edit();
      profile = "1";
      ed.putString("profileValues", "1|0");
      ed.putString("profileEntries",
          getString(R.string.profile_default) + "|" + getString(R.string.profile_new));
      ed.putString("profile", "1");
      ed.commit();

      profileList.setDefaultValue("1");
    }

    registerReceiver(ssidReceiver,
        new IntentFilter(android.net.ConnectivityManager.CONNECTIVITY_ACTION));

    loadProfileList();

    loadNetworkList();

    new Thread() {
      @Override
      public void run() {

        try {
          // Try not to block activity
          Thread.sleep(2000);
        } catch (InterruptedException ignore) {
          // Nothing
        }

        if (!Utils.isRoot()) {
          handler.sendEmptyMessage(MSG_NO_ROOT);
        }

        String versionName;
        try {
          versionName = getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
        } catch (NameNotFoundException e) {
          versionName = "NONE";
        }

        if (!settings.getBoolean(versionName, false)) {

          String version;
          try {
            version = getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
          } catch (NameNotFoundException e) {
            version = "NONE";
          }

          reset();

          Editor edit = settings.edit();
          edit.putBoolean(version, true);
          edit.commit();

          handler.sendEmptyMessage(MSG_UPDATE_FINISHED);
        }
      }
    }.start();
  }

  /** Called when the activity is closed. */
  @Override
  public void onDestroy() {

    if (adView != null) adView.destroy();

    if (ssidReceiver != null) unregisterReceiver(ssidReceiver);

    super.onDestroy();
  }

  private boolean serviceStop() {

    if (!Utils.isWorking()) return false;

    try {
      stopService(new Intent(ProxyDroid.this, ProxyDroidService.class));
    } catch (Exception e) {
      return false;
    }
    return true;
  }

  /** Called when connect button is clicked. */
  private boolean serviceStart() {

    if (Utils.isWorking()) return false;

    SharedPreferences settings = PreferenceManager.getDefaultSharedPreferences(this);

    mProfile.getProfile(settings);

    try {

      Intent it = new Intent(ProxyDroid.this, ProxyDroidService.class);
      Bundle bundle = new Bundle();
      bundle.putString("host", mProfile.getHost());
      bundle.putString("user", mProfile.getUser());
      bundle.putString("bypassAddrs", mProfile.getBypassAddrs());
      bundle.putString("password", mProfile.getPassword());
      bundle.putString("domain", mProfile.getDomain());
      bundle.putString("certificate", mProfile.getCertificate());

      bundle.putString("proxyType", mProfile.getProxyType());
      bundle.putBoolean("isAutoSetProxy", mProfile.isAutoSetProxy());
      bundle.putBoolean("isBypassApps", mProfile.isBypassApps());
      bundle.putBoolean("isAuth", mProfile.isAuth());
      bundle.putBoolean("isNTLM", mProfile.isNTLM());
      bundle.putBoolean("isDNSProxy", mProfile.isDNSProxy());
      bundle.putBoolean("isPAC", mProfile.isPAC());

      bundle.putInt("port", mProfile.getPort());

      it.putExtras(bundle);
      startService(it);
    } catch (Exception ignore) {
      // Nothing
      return false;
    }

    return true;
  }

  private void onProfileChange(String oldProfileName) {

    SharedPreferences settings = PreferenceManager.getDefaultSharedPreferences(this);

    mProfile.getProfile(settings);
    Editor ed = settings.edit();
    ed.putString(oldProfileName, mProfile.toString());
    ed.commit();

    String profileString = settings.getString(profile, "");

    if (profileString.equals("")) {
      mProfile.init();
      mProfile.setName(getProfileName(profile));
    } else {
      mProfile.decodeJson(profileString);
    }

    hostText.setText(mProfile.getHost());
    userText.setText(mProfile.getUser());
    passwordText.setText(mProfile.getPassword());
    domainText.setText(mProfile.getDomain());
    certificateText.setText(mProfile.getCertificate());
    proxyTypeList.setValue(mProfile.getProxyType());
    ssidList.setValue(mProfile.getSsid());

    isAuthCheck.setChecked(mProfile.isAuth());
    isNTLMCheck.setChecked(mProfile.isNTLM());
    isAutoConnectCheck.setChecked(mProfile.isAutoConnect());
    isAutoSetProxyCheck.setChecked(mProfile.isAutoSetProxy());
    isBypassAppsCheck.setChecked(mProfile.isBypassApps());
    isDNSProxyCheck.setChecked(mProfile.isDNSProxy());
    isPACCheck.setChecked(mProfile.isPAC());

    portText.setText(Integer.toString(mProfile.getPort()));

    Log.d(TAG, mProfile.toString());

    mProfile.setProfile(settings);
  }

  private void showAToast(String msg) {
    if (!isFinishing()) {
      AlertDialog.Builder builder = new AlertDialog.Builder(this);
      builder.setMessage(msg)
          .setCancelable(false)
          .setNegativeButton(getString(R.string.ok_iknow), new DialogInterface.OnClickListener() {
            @Override
            public void onClick(DialogInterface dialog, int id) {
              dialog.cancel();
            }
          });
      AlertDialog alert = builder.create();
      alert.show();
    }
  }

  private void disableAll() {
    hostText.setEnabled(false);
    portText.setEnabled(false);
    userText.setEnabled(false);
    passwordText.setEnabled(false);
    domainText.setEnabled(false);
    certificateText.setEnabled(false);
    ssidList.setEnabled(false);
    proxyTypeList.setEnabled(false);
    proxyedApps.setEnabled(false);
    profileList.setEnabled(false);
    bypassAddrs.setEnabled(false);

    isAuthCheck.setEnabled(false);
    isNTLMCheck.setEnabled(false);
    isDNSProxyCheck.setEnabled(false);
    isAutoSetProxyCheck.setEnabled(false);
    isAutoConnectCheck.setEnabled(false);
    isPACCheck.setEnabled(false);
    isBypassAppsCheck.setEnabled(false);
  }

  private void enableAll() {
    hostText.setEnabled(true);

    if (!isPACCheck.isChecked()) {
      portText.setEnabled(true);
      proxyTypeList.setEnabled(true);
    }

    bypassAddrs.setEnabled(true);

    if (isAuthCheck.isChecked()) {
      userText.setEnabled(true);
      passwordText.setEnabled(true);
      isNTLMCheck.setEnabled(true);
      if (isNTLMCheck.isChecked()) domainText.setEnabled(true);
    }
    if ("https".equals(proxyTypeList.getValue())){
        certificateText.setEnabled(true);
    }
    if (!isAutoSetProxyCheck.isChecked()) {
      proxyedApps.setEnabled(true);
      isBypassAppsCheck.setEnabled(true);
    }
    if (isAutoConnectCheck.isChecked()) ssidList.setEnabled(true);

    isDNSProxyCheck.setEnabled(true);
    profileList.setEnabled(true);
    isAutoSetProxyCheck.setEnabled(true);
    isAuthCheck.setEnabled(true);
    isAutoConnectCheck.setEnabled(true);
    isPACCheck.setEnabled(true);
  }

  @Override
  public boolean onPreferenceTreeClick(PreferenceScreen preferenceScreen, Preference preference) {

    if (preference.getKey() != null && preference.getKey().equals("bypassAddrs")) {
      Intent intent = new Intent(this, BypassListActivity.class);
      startActivity(intent);
    } else if (preference.getKey() != null && preference.getKey().equals("proxyedApps")) {
      Intent intent = new Intent(this, AppManager.class);
      startActivity(intent);
    }

    return super.onPreferenceTreeClick(preferenceScreen, preference);
  }

  private String getProfileName(String profile) {
    SharedPreferences settings = PreferenceManager.getDefaultSharedPreferences(this);
    return settings.getString("profile" + profile,
        getString(R.string.profile_base) + " " + profile);
  }

  @Override
  protected void onResume() {
    super.onResume();
    SharedPreferences settings = PreferenceManager.getDefaultSharedPreferences(this);

    if (settings.getBoolean("isAutoSetProxy", false)) {
      proxyedApps.setEnabled(false);
      isBypassAppsCheck.setEnabled(false);
    } else {
      proxyedApps.setEnabled(true);
      isBypassAppsCheck.setEnabled(true);
    }

    if (settings.getBoolean("isAutoConnect", false)) {
      ssidList.setEnabled(true);
    } else {
      ssidList.setEnabled(false);
    }

    if (settings.getBoolean("isPAC", false)) {
      portText.setEnabled(false);
      proxyTypeList.setEnabled(false);
      hostText.setTitle(R.string.host_pac);
      hostText.setSummary(R.string.host_pac_summary);
    }

    if (!settings.getBoolean("isAuth", false)) {
      userText.setEnabled(false);
      passwordText.setEnabled(false);
      isNTLMCheck.setEnabled(false);
    }

    if (!settings.getBoolean("isAuth", false) || !settings.getBoolean("isNTLM", false)) {
      domainText.setEnabled(false);
    }

    if (!"https".equals(settings.getString("proxyType", ""))){
      certificateText.setEnabled(false);
    }

    Editor edit = settings.edit();

    if (Utils.isWorking()) {
      if (settings.getBoolean("isConnecting", false)) isRunningCheck.setEnabled(false);
      edit.putBoolean("isRunning", true);
    } else {
      if (settings.getBoolean("isRunning", false)) {
        new Thread() {
          @Override
          public void run() {
            reset();
          }
        }.start();
      }
      edit.putBoolean("isRunning", false);
    }

    edit.commit();

    if (settings.getBoolean("isRunning", false)) {
      if (Build.VERSION.SDK_INT >= 14) {
        ((SwitchPreference) isRunningCheck).setChecked(true);
      } else {
        ((CheckBoxPreference) isRunningCheck).setChecked(true);
      }
      disableAll();
    } else {
      if (Build.VERSION.SDK_INT >= 14) {
        ((SwitchPreference) isRunningCheck).setChecked(false);
      } else {
        ((CheckBoxPreference) isRunningCheck).setChecked(false);
      }
      enableAll();
    }

    // Setup the initial values
    profile = settings.getString("profile", "1");
    profileList.setValue(profile);

    profileList.setSummary(getProfileName(profile));

    if (!settings.getString("ssid", "").equals("")) {
      ssidList.setSummary(settings.getString("ssid", ""));
    }
    if (!settings.getString("user", "").equals("")) {
      userText.setSummary(settings.getString("user", getString(R.string.user_summary)));
    }
    if (!settings.getString("certificate", "").equals("")) {
      certificateText.setSummary(settings.getString("certificate", getString(R.string.certificate_summary)));
    }
    if (!settings.getString("bypassAddrs", "").equals("")) {
      bypassAddrs.setSummary(
          settings.getString("bypassAddrs", getString(R.string.set_bypass_summary))
              .replace("|", ", "));
    } else {
      bypassAddrs.setSummary(R.string.set_bypass_summary);
    }
    if (!settings.getString("port", "-1").equals("-1") && !settings.getString("port", "-1")
        .equals("")) {
      portText.setSummary(settings.getString("port", getString(R.string.port_summary)));
    }
    if (!settings.getString("host", "").equals("")) {
      hostText.setSummary(settings.getString("host", getString(
          settings.getBoolean("isPAC", false) ? R.string.host_pac_summary
              : R.string.host_summary)));
    }
    if (!settings.getString("password", "").equals("")) passwordText.setSummary("*********");
    if (!settings.getString("proxyType", "").equals("")) {
      proxyTypeList.setSummary(settings.getString("proxyType", "").toUpperCase());
    }
    if (!settings.getString("domain", "").equals("")) {
      domainText.setSummary(settings.getString("domain", ""));
    }

    // Set up a listener whenever a key changes
    getPreferenceScreen().getSharedPreferences().registerOnSharedPreferenceChangeListener(this);
  }

  @Override
  protected void onPause() {
    super.onPause();

    // Unregister the listener whenever a key changes
    getPreferenceScreen().getSharedPreferences().unregisterOnSharedPreferenceChangeListener(this);
  }

  @Override
  public void onSharedPreferenceChanged(SharedPreferences settings, String key) {
    // Let's do something a preference value changes

    if (key.equals("profile")) {
      String profileString = settings.getString("profile", "");
      if (profileString.equals("0")) {
        String[] profileEntries = settings.getString("profileEntries", "").split("\\|");
        String[] profileValues = settings.getString("profileValues", "").split("\\|");
        int newProfileValue = Integer.valueOf(profileValues[profileValues.length - 2]) + 1;

        StringBuilder profileEntriesBuffer = new StringBuilder();
        StringBuilder profileValuesBuffer = new StringBuilder();

        for (int i = 0; i < profileValues.length - 1; i++) {
          profileEntriesBuffer.append(profileEntries[i]).append("|");
          profileValuesBuffer.append(profileValues[i]).append("|");
        }
        profileEntriesBuffer.append(getProfileName(Integer.toString(newProfileValue))).append("|");
        profileValuesBuffer.append(newProfileValue).append("|");
        profileEntriesBuffer.append(getString(R.string.profile_new));
        profileValuesBuffer.append("0");

        Editor ed = settings.edit();
        ed.putString("profileEntries", profileEntriesBuffer.toString());
        ed.putString("profileValues", profileValuesBuffer.toString());
        ed.putString("profile", Integer.toString(newProfileValue));
        ed.commit();

        loadProfileList();
      } else {
        String oldProfile = profile;
        profile = profileString;
        profileList.setValue(profile);
        onProfileChange(oldProfile);
        profileList.setSummary(getProfileName(profileString));
      }
    }

    if (key.equals("isConnecting")) {
      if (settings.getBoolean("isConnecting", false)) {
        Log.d(TAG, "Connecting start");
        isRunningCheck.setEnabled(false);
        pd = ProgressDialog.show(this, "", getString(R.string.connecting), true, true);
      } else {
        Log.d(TAG, "Connecting finish");
        if (pd != null) {
          pd.dismiss();
          pd = null;
        }
        isRunningCheck.setEnabled(true);
      }
    }

    if (key.equals("isPAC")) {
      if (settings.getBoolean("isPAC", false)) {
        portText.setEnabled(false);
        proxyTypeList.setEnabled(false);
        hostText.setTitle(R.string.host_pac);
      } else {
        portText.setEnabled(true);
        proxyTypeList.setEnabled(true);
        hostText.setTitle(R.string.host);
      }
      if (settings.getString("host", "").equals("")) {
        hostText.setSummary(settings.getBoolean("isPAC", false) ? R.string.host_pac_summary
            : R.string.host_summary);
      } else {
        hostText.setSummary(settings.getString("host", ""));
      }
    }

    if (key.equals("isAuth")) {
      if (!settings.getBoolean("isAuth", false)) {
        userText.setEnabled(false);
        passwordText.setEnabled(false);
        isNTLMCheck.setEnabled(false);
        domainText.setEnabled(false);
      } else {
        userText.setEnabled(true);
        passwordText.setEnabled(true);
        isNTLMCheck.setEnabled(true);
        if (isNTLMCheck.isChecked()) {
          domainText.setEnabled(true);
        } else {
          domainText.setEnabled(false);
        }
      }
    }

    if (key.equals("isNTLM")) {
      if (!settings.getBoolean("isAuth", false) || !settings.getBoolean("isNTLM", false)) {
        domainText.setEnabled(false);
      } else {
        domainText.setEnabled(true);
      }
    }
    
    if (key.equals("proxyType")){
      if (!"https".equals(settings.getString("proxyType", ""))){
        certificateText.setEnabled(false);
      } else {
        certificateText.setEnabled(true);
      }
    }

    if (key.equals("isAutoConnect")) {
      if (settings.getBoolean("isAutoConnect", false)) {
        loadNetworkList();
        ssidList.setEnabled(true);
      } else {
        ssidList.setEnabled(false);
      }
    }

    if (key.equals("isAutoSetProxy")) {
      if (settings.getBoolean("isAutoSetProxy", false)) {
        proxyedApps.setEnabled(false);
        isBypassAppsCheck.setEnabled(false);
      } else {
        proxyedApps.setEnabled(true);
        isBypassAppsCheck.setEnabled(true);
      }
    }

    if (key.equals("isRunning")) {
      if (settings.getBoolean("isRunning", false)) {
        disableAll();
        if (Build.VERSION.SDK_INT >= 14) {
          ((SwitchPreference) isRunningCheck).setChecked(true);
        } else {
          ((CheckBoxPreference) isRunningCheck).setChecked(true);
        }
        if (!Utils.isConnecting()) serviceStart();
      } else {
        enableAll();
        if (Build.VERSION.SDK_INT >= 14) {
          ((SwitchPreference) isRunningCheck).setChecked(false);
        } else {
          ((CheckBoxPreference) isRunningCheck).setChecked(false);
        }
        if (!Utils.isConnecting()) serviceStop();
      }
    }

    if (key.equals("ssid")) {
      if (settings.getString("ssid", "").equals("")) {
        ssidList.setSummary(getString(R.string.ssid_summary));
      } else {
        ssidList.setSummary(settings.getString("ssid", ""));
      }
    } else if (key.equals("user")) {
      if (settings.getString("user", "").equals("")) {
        userText.setSummary(getString(R.string.user_summary));
      } else {
        userText.setSummary(settings.getString("user", ""));
      }
    } else if (key.equals("domain")) {
      if (settings.getString("domain", "").equals("")) {
        domainText.setSummary(getString(R.string.domain_summary));
      } else {
        domainText.setSummary(settings.getString("domain", ""));
      }
    } else if (key.equals("proxyType")) {
      if (settings.getString("proxyType", "").equals("")) {
        certificateText.setSummary(getString(R.string.certificate_summary));
      } else {
        certificateText.setSummary(settings.getString("certificate", ""));
      }
    } else if (key.equals("bypassAddrs")) {
      if (settings.getString("bypassAddrs", "").equals("")) {
        bypassAddrs.setSummary(getString(R.string.set_bypass_summary));
      } else {
        bypassAddrs.setSummary(settings.getString("bypassAddrs", "").replace("|", ", "));
      }
    } else if (key.equals("port")) {
      if (settings.getString("port", "-1").equals("-1") || settings.getString("port", "-1")
          .equals("")) {
        portText.setSummary(getString(R.string.port_summary));
      } else {
        portText.setSummary(settings.getString("port", ""));
      }
    } else if (key.equals("host")) {
      if (settings.getString("host", "").equals("")) {
        hostText.setSummary(settings.getBoolean("isPAC", false) ? R.string.host_pac_summary
            : R.string.host_summary);
      } else {
        hostText.setSummary(settings.getString("host", ""));
      }
    } else if (key.equals("proxyType")) {
      if (settings.getString("proxyType", "").equals("")) {
        proxyTypeList.setSummary(getString(R.string.proxy_type_summary));
      } else {
        proxyTypeList.setSummary(settings.getString("proxyType", "").toUpperCase());
      }
    } else if (key.equals("password")) {
      if (!settings.getString("password", "").equals("")) {
        passwordText.setSummary("*********");
      } else {
        passwordText.setSummary(getString(R.string.password_summary));
      }
    }
  }

  // 点击Menu时，系统调用当前Activity的onCreateOptionsMenu方法，并传一个实现了一个Menu接口的menu对象供你使用
  @Override
  public boolean onCreateOptionsMenu(Menu menu) {
    /*
     * add()方法的四个参数，依次是： 1、组别，如果不分组的话就写Menu.NONE,
		 * 2、Id，这个很重要，Android根据这个Id来确定不同的菜单 3、顺序，那个菜单现在在前面由这个参数的大小决定
		 * 4、文本，菜单的显示文本
		 */
    menu.add(Menu.NONE, Menu.FIRST + 1, 4, getString(R.string.recovery))
        .setIcon(android.R.drawable.ic_menu_close_clear_cancel)
        .setShowAsAction(MenuItem.SHOW_AS_ACTION_NEVER);
    menu.add(Menu.NONE, Menu.FIRST + 2, 2, getString(R.string.profile_del))
        .setIcon(android.R.drawable.ic_menu_delete)
        .setShowAsAction(MenuItem.SHOW_AS_ACTION_IF_ROOM | MenuItem.SHOW_AS_ACTION_WITH_TEXT);
    menu.add(Menu.NONE, Menu.FIRST + 3, 5, getString(R.string.about))
        .setIcon(android.R.drawable.ic_menu_info_details)
        .setShowAsAction(MenuItem.SHOW_AS_ACTION_IF_ROOM | MenuItem.SHOW_AS_ACTION_WITH_TEXT);
    menu.add(Menu.NONE, Menu.FIRST + 4, 1, getString(R.string.change_name))
        .setIcon(android.R.drawable.ic_menu_edit)
        .setShowAsAction(MenuItem.SHOW_AS_ACTION_IF_ROOM | MenuItem.SHOW_AS_ACTION_WITH_TEXT);
    menu.add(Menu.NONE, Menu.FIRST + 5, 3, getString(R.string.use_system_iptables))
        .setIcon(android.R.drawable.ic_menu_revert)
        .setShowAsAction(MenuItem.SHOW_AS_ACTION_NEVER);

    // return true才会起作用
    return super.onCreateOptionsMenu(menu);
  }

  // 菜单项被选择事件
  @Override
  public boolean onOptionsItemSelected(MenuItem item) {
    switch (item.getItemId()) {
      case Menu.FIRST + 1:
        new Thread() {
          @Override
          public void run() {
            reset();
          }
        }.start();
        return true;
      case Menu.FIRST + 2:
        AlertDialog ad = new AlertDialog.Builder(this).setTitle(R.string.profile_del)
            .setMessage(R.string.profile_del_confirm)
            .setPositiveButton(R.string.alert_dialog_ok, new DialogInterface.OnClickListener() {
              @Override
              public void onClick(DialogInterface dialog, int whichButton) {
                  /* User clicked OK so do some stuff */
                delProfile(profile);
              }
            })
            .setNegativeButton(R.string.alert_dialog_cancel, new DialogInterface.OnClickListener() {
              @Override
              public void onClick(DialogInterface dialog, int whichButton) {
                  /* User clicked Cancel so do some stuff */
                dialog.dismiss();
              }
            })
            .create();

        ad.show();

        return true;
      case Menu.FIRST + 3:
        showAbout();
        return true;
      case Menu.FIRST + 4:
        rename();
        return true;
      case Menu.FIRST + 5:
        // Use system's instead
        File inFile = new File("/system/bin/iptables");
        if (inFile.exists()) {
          try {
            InputStream in = new FileInputStream(inFile);
            OutputStream out = new FileOutputStream("/data/data/org.proxydroid/iptables");
            copyFile(in, out);
            in.close();
            in = null;
            out.flush();
            out.close();
            out = null;
          } catch (Exception e) {
            // Ignore
          }
        }
        return true;
    }

    return super.onOptionsItemSelected(item);
  }

  private void rename() {
    LayoutInflater factory = LayoutInflater.from(this);
    final View textEntryView = factory.inflate(R.layout.alert_dialog_text_entry, null);
    final EditText profileName = (EditText) textEntryView.findViewById(R.id.text_edit);
    profileName.setText(getProfileName(profile));

    AlertDialog ad = new AlertDialog.Builder(this).setTitle(R.string.change_name)
        .setView(textEntryView)
        .setPositiveButton(R.string.alert_dialog_ok, new DialogInterface.OnClickListener() {
          @Override
          public void onClick(DialogInterface dialog, int whichButton) {
            EditText profileName = (EditText) textEntryView.findViewById(R.id.text_edit);
            SharedPreferences settings =
                PreferenceManager.getDefaultSharedPreferences(ProxyDroid.this);
            String name = profileName.getText().toString();
            if (name == null) return;
            name = name.replace("|", "");
            if (name.length() <= 0) return;
            Editor ed = settings.edit();
            ed.putString("profile" + profile, name);
            ed.commit();

            profileList.setSummary(getProfileName(profile));

            String[] profileEntries = settings.getString("profileEntries", "").split("\\|");
            String[] profileValues = settings.getString("profileValues", "").split("\\|");

            StringBuilder profileEntriesBuffer = new StringBuilder();
            StringBuilder profileValuesBuffer = new StringBuilder();

            for (int i = 0; i < profileValues.length - 1; i++) {
              if (profileValues[i].equals(profile)) {
                profileEntriesBuffer.append(getProfileName(profile)).append("|");
              } else {
                profileEntriesBuffer.append(profileEntries[i]).append("|");
              }
              profileValuesBuffer.append(profileValues[i]).append("|");
            }

            profileEntriesBuffer.append(getString(R.string.profile_new));
            profileValuesBuffer.append("0");

            ed = settings.edit();
            ed.putString("profileEntries", profileEntriesBuffer.toString());
            ed.putString("profileValues", profileValuesBuffer.toString());

            ed.commit();

            loadProfileList();
          }
        })
        .setNegativeButton(R.string.alert_dialog_cancel, new DialogInterface.OnClickListener() {
          @Override
          public void onClick(DialogInterface dialog, int whichButton) {
                /* User clicked cancel so do some stuff */
          }
        })
        .create();
    ad.show();
  }

  private void delProfile(String profile) {
    SharedPreferences settings = PreferenceManager.getDefaultSharedPreferences(this);
    String[] profileEntries = settings.getString("profileEntries", "").split("\\|");
    String[] profileValues = settings.getString("profileValues", "").split("\\|");

    Log.d(TAG, "Profile :" + profile);
    if (profileEntries.length > 2) {
      StringBuilder profileEntriesBuffer = new StringBuilder();
      StringBuilder profileValuesBuffer = new StringBuilder();

      String newProfileValue = "1";

      for (int i = 0; i < profileValues.length - 1; i++) {
        if (!profile.equals(profileValues[i])) {
          profileEntriesBuffer.append(profileEntries[i]).append("|");
          profileValuesBuffer.append(profileValues[i]).append("|");
          newProfileValue = profileValues[i];
        }
      }
      profileEntriesBuffer.append(getString(R.string.profile_new));
      profileValuesBuffer.append("0");

      Editor ed = settings.edit();
      ed.putString("profileEntries", profileEntriesBuffer.toString());
      ed.putString("profileValues", profileValuesBuffer.toString());
      ed.putString("profile", newProfileValue);
      ed.commit();

      loadProfileList();
    }
  }

  private void reset() {
    try {
      stopService(new Intent(ProxyDroid.this, ProxyDroidService.class));
    } catch (Exception e) {
      // Nothing
    }

    CopyAssets();

    try {
      DatabaseHelper helper = OpenHelperManager.getHelper(ProxyDroid.this, DatabaseHelper.class);
      Dao<DNSResponse, String> dnsCacheDao = helper.getDNSCacheDao();
      List<DNSResponse> list = dnsCacheDao.queryForAll();
      for (DNSResponse resp : list) {
        dnsCacheDao.delete(resp);
      }
    } catch (Exception ignore) {
      // Nothing
    }

    Utils.runRootCommand(Utils.getIptables()
        + " -t nat -F OUTPUT\n"
        + ProxyDroidService.BASE
        + "proxy.sh stop\n"
        + "kill -9 `cat /data/data/org.proxydroid/stunnel.pid`\n"
        + "kill -9 `cat /data/data/org.proxydroid/shrpx.pid`\n"
        + "kill -9 `cat /data/data/org.proxydroid/cntlm.pid`\n");

    Utils.runRootCommand("chmod 700 /data/data/org.proxydroid/iptables\n"
        + "chmod 700 /data/data/org.proxydroid/redsocks\n"
        + "chmod 700 /data/data/org.proxydroid/proxy.sh\n"
        + "chmod 700 /data/data/org.proxydroid/cntlm\n"
        + "chmod 700 /data/data/org.proxydroid/stunnel\n"
        + "chmod 700 /data/data/org.proxydroid/shrpx\n");
  }

  @Override
  public boolean onKeyDown(int keyCode, KeyEvent event) {
    if (keyCode == KeyEvent.KEYCODE_BACK && event.getRepeatCount() == 0) { // 按下的如果是BACK，同时没有重复
      try {
        finish();
      } catch (Exception ignore) {
        // Nothing
      }
      return true;
    }
    return super.onKeyDown(keyCode, event);
  }
}
