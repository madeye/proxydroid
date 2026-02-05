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

import android.app.AlertDialog
import android.app.ProgressDialog
import android.content.*
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.Uri
import android.net.wifi.WifiManager
import android.os.*
import android.preference.*
import android.util.Log
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuItem
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.EditText
import android.widget.Toast
import com.ksmaze.android.preference.ListPreferenceMultiSelect
import org.proxydroid.utils.Constraints
import org.proxydroid.utils.Utils
import java.io.FileOutputStream
import java.io.IOException

class ProxyDroid : PreferenceActivity(), SharedPreferences.OnSharedPreferenceChangeListener {

    companion object {
        private const val TAG = "ProxyDroid"
        private const val MSG_UPDATE_FINISHED = 0
        private const val MSG_NO_ROOT = 1
    }

    private val handler = object : Handler(Looper.getMainLooper()) {
        override fun handleMessage(msg: Message) {
            when (msg.what) {
                MSG_UPDATE_FINISHED -> {
                    Toast.makeText(this@ProxyDroid, getString(R.string.update_finished), Toast.LENGTH_LONG).show()
                }
                MSG_NO_ROOT -> {
                    showAToast(getString(R.string.require_root_alert))
                }
            }
            super.handleMessage(msg)
        }
    }

    private var pd: ProgressDialog? = null
    private var profile: String = "1"
    private val mProfile = Profile()

    private lateinit var isAutoConnectCheck: CheckBoxPreference
    private lateinit var isAutoSetProxyCheck: CheckBoxPreference
    private lateinit var isAuthCheck: CheckBoxPreference
    private lateinit var isNTLMCheck: CheckBoxPreference
    private lateinit var isPACCheck: CheckBoxPreference
    private lateinit var profileList: ListPreference
    private lateinit var hostText: EditTextPreference
    private lateinit var portText: EditTextPreference
    private lateinit var userText: EditTextPreference
    private lateinit var passwordText: EditTextPreference
    private lateinit var domainText: EditTextPreference
    private lateinit var certificateText: EditTextPreference
    private lateinit var ssidList: ListPreferenceMultiSelect
    private lateinit var excludedSsidList: ListPreferenceMultiSelect
    private lateinit var proxyTypeList: ListPreference
    private lateinit var isRunningCheck: Preference
    private lateinit var isBypassAppsCheck: CheckBoxPreference
    private lateinit var proxyedApps: Preference
    private lateinit var bypassAddrs: Preference

    private val ssidReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action != ConnectivityManager.CONNECTIVITY_ACTION) {
                Log.w(TAG, "onReceived() called incorrectly")
                return
            }
            loadNetworkList()
        }
    }

    private fun showAbout() {
        val web = WebView(this)
        web.loadUrl("file:///android_asset/pages/about.html")
        web.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, url: String): Boolean {
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
                return true
            }
        }

        val versionName = try {
            packageManager.getPackageInfo(packageName, 0).versionName
        } catch (ex: PackageManager.NameNotFoundException) {
            ""
        }

        AlertDialog.Builder(this)
            .setTitle(String.format(getString(R.string.about_title), versionName))
            .setCancelable(false)
            .setNegativeButton(getString(R.string.ok_iknow)) { dialog, _ -> dialog.cancel() }
            .setView(web)
            .create()
            .show()
    }

    private fun copyAssets() {
        val assetManager = assets
        val abi = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            Build.SUPPORTED_ABIS[0]
        } else {
            @Suppress("DEPRECATION")
            Build.CPU_ABI
        }

        try {
            val files = if (abi.matches(Regex("armeabi-v7a|arm64-v8a"))) {
                assetManager.list("armeabi-v7a")
            } else {
                assetManager.list("x86")
            }

            files?.forEach { file ->
                try {
                    val inputStream = if (abi.matches(Regex("armeabi-v7a|arm64-v8a"))) {
                        assetManager.open("armeabi-v7a/$file")
                    } else {
                        assetManager.open("x86/$file")
                    }
                    val out = FileOutputStream("${filesDir.absolutePath}/$file")
                    inputStream.copyTo(out)
                    inputStream.close()
                    out.flush()
                    out.close()
                } catch (e: Exception) {
                    Log.e(TAG, e.message ?: "Error copying asset")
                }
            }
        } catch (e: IOException) {
            Log.e(TAG, e.message ?: "Error listing assets")
        }
    }

    private fun isTextEmpty(s: String?, msg: String): Boolean {
        if (s.isNullOrEmpty()) {
            showAToast(msg)
            return true
        }
        return false
    }

    private fun loadProfileList() {
        val settings = PreferenceManager.getDefaultSharedPreferences(this)
        val profileEntries = settings.getString("profileEntries", "")?.split("\\|".toRegex())?.toTypedArray() ?: arrayOf()
        val profileValues = settings.getString("profileValues", "")?.split("\\|".toRegex())?.toTypedArray() ?: arrayOf()

        profileList.entries = profileEntries
        profileList.entryValues = profileValues
    }

    private fun loadNetworkList() {
        val wm = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        val wcs = wm.configuredNetworks
        val n = 3
        var wifiIndex = n

        val ssidEntries = if (wcs == null) {
            arrayOf(Constraints.WIFI_AND_3G, Constraints.ONLY_WIFI, Constraints.ONLY_3G)
        } else {
            val entries = mutableListOf(Constraints.WIFI_AND_3G, Constraints.ONLY_WIFI, Constraints.ONLY_3G)
            for (wc in wcs) {
                entries.add(wc?.SSID?.replace("\"", "") ?: "unknown")
            }
            wifiIndex = n
            entries.toTypedArray()
        }

        ssidList.entries = ssidEntries
        ssidList.entryValues = ssidEntries

        val pureSsid = ssidEntries.copyOfRange(wifiIndex, ssidEntries.size)
        excludedSsidList.entries = pureSsid
        excludedSsidList.entryValues = pureSsid
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        addPreferencesFromResource(R.xml.proxydroid_preference)

        hostText = findPreference("host") as EditTextPreference
        portText = findPreference("port") as EditTextPreference
        userText = findPreference("user") as EditTextPreference
        passwordText = findPreference("password") as EditTextPreference
        domainText = findPreference("domain") as EditTextPreference
        certificateText = findPreference("certificate") as EditTextPreference
        bypassAddrs = findPreference("bypassAddrs")!!
        ssidList = findPreference("ssid") as ListPreferenceMultiSelect
        excludedSsidList = findPreference("excludedSsid") as ListPreferenceMultiSelect
        proxyTypeList = findPreference("proxyType") as ListPreference
        proxyedApps = findPreference("proxyedApps")!!
        profileList = findPreference("profile") as ListPreference

        isRunningCheck = findPreference("isRunning")!!
        isAutoSetProxyCheck = findPreference("isAutoSetProxy") as CheckBoxPreference
        isAuthCheck = findPreference("isAuth") as CheckBoxPreference
        isNTLMCheck = findPreference("isNTLM") as CheckBoxPreference
        isPACCheck = findPreference("isPAC") as CheckBoxPreference
        isAutoConnectCheck = findPreference("isAutoConnect") as CheckBoxPreference
        isBypassAppsCheck = findPreference("isBypassApps") as CheckBoxPreference

        val settings = PreferenceManager.getDefaultSharedPreferences(this)
        val profileValuesString = settings.getString("profileValues", "") ?: ""

        if (profileValuesString.isEmpty()) {
            val ed = settings.edit()
            profile = "1"
            ed.putString("profileValues", "1|0")
            ed.putString("profileEntries", "${getString(R.string.profile_default)}|${getString(R.string.profile_new)}")
            ed.putString("profile", "1")
            ed.apply()
            profileList.setDefaultValue("1")
        }

        registerReceiver(ssidReceiver, IntentFilter(ConnectivityManager.CONNECTIVITY_ACTION))

        loadProfileList()
        loadNetworkList()

        Thread {
            try {
                Thread.sleep(2000)
            } catch (ignore: InterruptedException) {
            }

            if (!Utils.isRoot()) {
                handler.sendEmptyMessage(MSG_NO_ROOT)
            }

            val versionName = try {
                packageManager.getPackageInfo(packageName, 0).versionName
            } catch (e: PackageManager.NameNotFoundException) {
                "NONE"
            }

            if (!settings.getBoolean(versionName, false)) {
                reset()
                val edit = settings.edit()
                edit.putBoolean(versionName, true)
                edit.apply()
                handler.sendEmptyMessage(MSG_UPDATE_FINISHED)
            }
        }.start()
    }

    override fun onDestroy() {
        try {
            unregisterReceiver(ssidReceiver)
        } catch (e: Exception) {
            // Ignore
        }
        super.onDestroy()
    }

    private fun serviceStop(): Boolean {
        if (!Utils.isWorking()) return false
        return try {
            stopService(Intent(this@ProxyDroid, ProxyDroidService::class.java))
            true
        } catch (e: Exception) {
            false
        }
    }

    private fun serviceStart(): Boolean {
        if (Utils.isWorking()) return false

        val settings = PreferenceManager.getDefaultSharedPreferences(this)
        mProfile.getProfile(settings)

        return try {
            val it = Intent(this@ProxyDroid, ProxyDroidService::class.java)
            val bundle = Bundle().apply {
                putString("host", mProfile.host)
                putString("user", mProfile.user)
                putString("bypassAddrs", mProfile.bypassAddrs)
                putString("password", mProfile.password)
                putString("domain", mProfile.domain)
                putString("certificate", mProfile.certificate)
                putString("proxyType", mProfile.proxyType)
                putBoolean("isAutoSetProxy", mProfile.isAutoSetProxy)
                putBoolean("isBypassApps", mProfile.isBypassApps)
                putBoolean("isAuth", mProfile.isAuth)
                putBoolean("isNTLM", mProfile.isNTLM)
                putBoolean("isDNSProxy", mProfile.isDNSProxy)
                putBoolean("isPAC", mProfile.isPAC)
                putInt("port", mProfile.port)
            }
            it.putExtras(bundle)
            startService(it)
            true
        } catch (ignore: Exception) {
            false
        }
    }

    private fun onProfileChange(oldProfileName: String) {
        val settings = PreferenceManager.getDefaultSharedPreferences(this)
        mProfile.getProfile(settings)
        settings.edit().putString(oldProfileName, mProfile.toString()).apply()

        val profileString = settings.getString(profile, "") ?: ""
        if (profileString.isEmpty()) {
            mProfile.init()
            mProfile.name = getProfileName(profile)
        } else {
            mProfile.decodeJson(profileString)
        }

        hostText.text = mProfile.host
        userText.text = mProfile.user
        passwordText.text = mProfile.password
        domainText.text = mProfile.domain
        certificateText.text = mProfile.certificate
        proxyTypeList.value = mProfile.proxyType
        ssidList.value = mProfile.ssid
        excludedSsidList.value = mProfile.excludedSsid

        isAuthCheck.isChecked = mProfile.isAuth
        isNTLMCheck.isChecked = mProfile.isNTLM
        isAutoConnectCheck.isChecked = mProfile.isAutoConnect
        isAutoSetProxyCheck.isChecked = mProfile.isAutoSetProxy
        isBypassAppsCheck.isChecked = mProfile.isBypassApps
        isPACCheck.isChecked = mProfile.isPAC

        portText.text = mProfile.port.toString()

        Log.d(TAG, mProfile.toString())
        mProfile.setProfile(settings)
    }

    private fun showAToast(msg: String) {
        if (!isFinishing) {
            AlertDialog.Builder(this)
                .setMessage(msg)
                .setCancelable(false)
                .setNegativeButton(getString(R.string.ok_iknow)) { dialog, _ -> dialog.cancel() }
                .create()
                .show()
        }
    }

    private fun disableAll() {
        hostText.isEnabled = false
        portText.isEnabled = false
        userText.isEnabled = false
        passwordText.isEnabled = false
        domainText.isEnabled = false
        certificateText.isEnabled = false
        ssidList.isEnabled = false
        excludedSsidList.isEnabled = false
        proxyTypeList.isEnabled = false
        proxyedApps.isEnabled = false
        profileList.isEnabled = false
        bypassAddrs.isEnabled = false

        isAuthCheck.isEnabled = false
        isNTLMCheck.isEnabled = false
        isAutoSetProxyCheck.isEnabled = false
        isAutoConnectCheck.isEnabled = false
        isPACCheck.isEnabled = false
        isBypassAppsCheck.isEnabled = false
    }

    private fun enableAll() {
        hostText.isEnabled = true

        if (!isPACCheck.isChecked) {
            portText.isEnabled = true
            proxyTypeList.isEnabled = true
        }

        bypassAddrs.isEnabled = true

        if (isAuthCheck.isChecked) {
            userText.isEnabled = true
            passwordText.isEnabled = true
            isNTLMCheck.isEnabled = true
            if (isNTLMCheck.isChecked) domainText.isEnabled = true
        }
        if ("https" == proxyTypeList.value) {
            certificateText.isEnabled = true
        }
        if (!isAutoSetProxyCheck.isChecked) {
            proxyedApps.isEnabled = true
            isBypassAppsCheck.isEnabled = true
        }
        if (isAutoConnectCheck.isChecked) {
            ssidList.isEnabled = true
            excludedSsidList.isEnabled = true
        }

        profileList.isEnabled = true
        isAutoSetProxyCheck.isEnabled = true
        isAuthCheck.isEnabled = true
        isAutoConnectCheck.isEnabled = true
        isPACCheck.isEnabled = true
    }

    override fun onPreferenceTreeClick(preferenceScreen: PreferenceScreen, preference: Preference): Boolean {
        when (preference.key) {
            "bypassAddrs" -> startActivity(Intent(this, BypassListActivity::class.java))
            "proxyedApps" -> startActivity(Intent(this, AppManager::class.java))
        }
        return super.onPreferenceTreeClick(preferenceScreen, preference)
    }

    private fun getProfileName(profile: String): String {
        val settings = PreferenceManager.getDefaultSharedPreferences(this)
        return settings.getString("profile$profile", "${getString(R.string.profile_base)} $profile") ?: ""
    }

    override fun onResume() {
        super.onResume()
        val settings = PreferenceManager.getDefaultSharedPreferences(this)

        proxyedApps.isEnabled = !settings.getBoolean("isAutoSetProxy", false)
        isBypassAppsCheck.isEnabled = !settings.getBoolean("isAutoSetProxy", false)

        ssidList.isEnabled = settings.getBoolean("isAutoConnect", false)
        excludedSsidList.isEnabled = settings.getBoolean("isAutoConnect", false)

        if (settings.getBoolean("isPAC", false)) {
            portText.isEnabled = false
            proxyTypeList.isEnabled = false
            hostText.setTitle(R.string.host_pac)
            hostText.setSummary(R.string.host_pac_summary)
        }

        if (!settings.getBoolean("isAuth", false)) {
            userText.isEnabled = false
            passwordText.isEnabled = false
            isNTLMCheck.isEnabled = false
        }

        if (!settings.getBoolean("isAuth", false) || !settings.getBoolean("isNTLM", false)) {
            domainText.isEnabled = false
        }

        if ("https" != settings.getString("proxyType", "")) {
            certificateText.isEnabled = false
        }

        val edit = settings.edit()
        if (Utils.isWorking()) {
            if (settings.getBoolean("isConnecting", false)) isRunningCheck.isEnabled = false
            edit.putBoolean("isRunning", true)
        } else {
            if (settings.getBoolean("isRunning", false)) {
                Thread { reset() }.start()
            }
            edit.putBoolean("isRunning", false)
        }
        edit.apply()

        if (settings.getBoolean("isRunning", false)) {
            if (Build.VERSION.SDK_INT >= 14) {
                (isRunningCheck as SwitchPreference).isChecked = true
            } else {
                (isRunningCheck as CheckBoxPreference).isChecked = true
            }
            disableAll()
        } else {
            if (Build.VERSION.SDK_INT >= 14) {
                (isRunningCheck as SwitchPreference).isChecked = false
            } else {
                (isRunningCheck as CheckBoxPreference).isChecked = false
            }
            enableAll()
        }

        profile = settings.getString("profile", "1") ?: "1"
        profileList.value = profile
        profileList.summary = getProfileName(profile)

        settings.getString("ssid", "")?.takeIf { it.isNotEmpty() }?.let { ssidList.summary = it }
        settings.getString("excludedSsid", "")?.takeIf { it.isNotEmpty() }?.let { excludedSsidList.summary = it }
        settings.getString("user", "")?.takeIf { it.isNotEmpty() }?.let { userText.summary = it }
        settings.getString("certificate", "")?.takeIf { it.isNotEmpty() }?.let { certificateText.summary = it }
        settings.getString("bypassAddrs", "")?.takeIf { it.isNotEmpty() }?.let {
            bypassAddrs.summary = it.replace("|", ", ")
        } ?: run { bypassAddrs.setSummary(R.string.set_bypass_summary) }

        settings.getString("port", "-1")?.takeIf { it != "-1" && it.isNotEmpty() }?.let { portText.summary = it }
        settings.getString("host", "")?.takeIf { it.isNotEmpty() }?.let { hostText.summary = it }
        settings.getString("password", "")?.takeIf { it.isNotEmpty() }?.let { passwordText.summary = "*********" }
        settings.getString("proxyType", "")?.takeIf { it.isNotEmpty() }?.let { proxyTypeList.summary = it.uppercase() }
        settings.getString("domain", "")?.takeIf { it.isNotEmpty() }?.let { domainText.summary = it }

        preferenceScreen.sharedPreferences.registerOnSharedPreferenceChangeListener(this)
    }

    override fun onPause() {
        super.onPause()
        preferenceScreen.sharedPreferences.unregisterOnSharedPreferenceChangeListener(this)
    }

    override fun onSharedPreferenceChanged(settings: SharedPreferences, key: String?) {
        when (key) {
            "profile" -> {
                val profileString = settings.getString("profile", "") ?: ""
                if (profileString == "0") {
                    val profileEntries = settings.getString("profileEntries", "")?.split("\\|".toRegex()) ?: emptyList()
                    val profileValues = settings.getString("profileValues", "")?.split("\\|".toRegex()) ?: emptyList()
                    val newProfileValue = (profileValues.getOrNull(profileValues.size - 2)?.toIntOrNull() ?: 0) + 1

                    val profileEntriesBuffer = StringBuilder()
                    val profileValuesBuffer = StringBuilder()

                    for (i in 0 until profileValues.size - 1) {
                        profileEntriesBuffer.append(profileEntries.getOrNull(i) ?: "").append("|")
                        profileValuesBuffer.append(profileValues.getOrNull(i) ?: "").append("|")
                    }
                    profileEntriesBuffer.append(getProfileName(newProfileValue.toString())).append("|")
                    profileValuesBuffer.append(newProfileValue).append("|")
                    profileEntriesBuffer.append(getString(R.string.profile_new))
                    profileValuesBuffer.append("0")

                    settings.edit()
                        .putString("profileEntries", profileEntriesBuffer.toString())
                        .putString("profileValues", profileValuesBuffer.toString())
                        .putString("profile", newProfileValue.toString())
                        .apply()

                    loadProfileList()
                } else {
                    val oldProfile = profile
                    profile = profileString
                    profileList.value = profile
                    onProfileChange(oldProfile)
                    profileList.summary = getProfileName(profileString)
                }
            }
            "isConnecting" -> {
                if (settings.getBoolean("isConnecting", false)) {
                    Log.d(TAG, "Connecting start")
                    isRunningCheck.isEnabled = false
                    pd = ProgressDialog.show(this, "", getString(R.string.connecting), true, true)
                } else {
                    Log.d(TAG, "Connecting finish")
                    pd?.dismiss()
                    pd = null
                    isRunningCheck.isEnabled = true
                }
            }
            "isPAC" -> {
                if (settings.getBoolean("isPAC", false)) {
                    portText.isEnabled = false
                    proxyTypeList.isEnabled = false
                    hostText.setTitle(R.string.host_pac)
                } else {
                    portText.isEnabled = true
                    proxyTypeList.isEnabled = true
                    hostText.setTitle(R.string.host)
                }
                hostText.summary = if (settings.getString("host", "")?.isEmpty() == true) {
                    getString(if (settings.getBoolean("isPAC", false)) R.string.host_pac_summary else R.string.host_summary)
                } else {
                    settings.getString("host", "")
                }
            }
            "isAuth" -> {
                if (!settings.getBoolean("isAuth", false)) {
                    userText.isEnabled = false
                    passwordText.isEnabled = false
                    isNTLMCheck.isEnabled = false
                    domainText.isEnabled = false
                } else {
                    userText.isEnabled = true
                    passwordText.isEnabled = true
                    isNTLMCheck.isEnabled = true
                    domainText.isEnabled = isNTLMCheck.isChecked
                }
            }
            "isNTLM" -> {
                domainText.isEnabled = settings.getBoolean("isAuth", false) && settings.getBoolean("isNTLM", false)
            }
            "proxyType" -> {
                certificateText.isEnabled = "https" == settings.getString("proxyType", "")
            }
            "isAutoConnect" -> {
                if (settings.getBoolean("isAutoConnect", false)) {
                    loadNetworkList()
                    ssidList.isEnabled = true
                    excludedSsidList.isEnabled = true
                } else {
                    ssidList.isEnabled = false
                    excludedSsidList.isEnabled = false
                }
            }
            "isAutoSetProxy" -> {
                proxyedApps.isEnabled = !settings.getBoolean("isAutoSetProxy", false)
                isBypassAppsCheck.isEnabled = !settings.getBoolean("isAutoSetProxy", false)
            }
            "isRunning" -> {
                if (settings.getBoolean("isRunning", false)) {
                    disableAll()
                    if (Build.VERSION.SDK_INT >= 14) {
                        (isRunningCheck as SwitchPreference).isChecked = true
                    } else {
                        (isRunningCheck as CheckBoxPreference).isChecked = true
                    }
                    if (!Utils.isConnecting()) serviceStart()
                } else {
                    enableAll()
                    if (Build.VERSION.SDK_INT >= 14) {
                        (isRunningCheck as SwitchPreference).isChecked = false
                    } else {
                        (isRunningCheck as CheckBoxPreference).isChecked = false
                    }
                    if (!Utils.isConnecting()) serviceStop()
                }
            }
            "ssid" -> ssidList.summary = settings.getString("ssid", "")?.takeIf { it.isNotEmpty() } ?: getString(R.string.ssid_summary)
            "excludedSsid" -> excludedSsidList.summary = settings.getString("excludedSsid", "")?.takeIf { it.isNotEmpty() } ?: getString(R.string.excluded_ssid_summary)
            "user" -> userText.summary = settings.getString("user", "")?.takeIf { it.isNotEmpty() } ?: getString(R.string.user_summary)
            "domain" -> domainText.summary = settings.getString("domain", "")?.takeIf { it.isNotEmpty() } ?: getString(R.string.domain_summary)
            "bypassAddrs" -> bypassAddrs.summary = settings.getString("bypassAddrs", "")?.takeIf { it.isNotEmpty() }?.replace("|", ", ") ?: getString(R.string.set_bypass_summary)
            "port" -> portText.summary = settings.getString("port", "-1")?.takeIf { it != "-1" && it.isNotEmpty() } ?: getString(R.string.port_summary)
            "host" -> hostText.summary = settings.getString("host", "")?.takeIf { it.isNotEmpty() } ?: getString(if (settings.getBoolean("isPAC", false)) R.string.host_pac_summary else R.string.host_summary)
            "password" -> passwordText.summary = if (settings.getString("password", "")?.isNotEmpty() == true) "*********" else getString(R.string.password_summary)
        }
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menu.add(Menu.NONE, Menu.FIRST + 1, 4, getString(R.string.recovery))
            .setIcon(android.R.drawable.ic_menu_close_clear_cancel)
            .setShowAsAction(MenuItem.SHOW_AS_ACTION_NEVER)
        menu.add(Menu.NONE, Menu.FIRST + 2, 2, getString(R.string.profile_del))
            .setIcon(android.R.drawable.ic_menu_delete)
            .setShowAsAction(MenuItem.SHOW_AS_ACTION_IF_ROOM or MenuItem.SHOW_AS_ACTION_WITH_TEXT)
        menu.add(Menu.NONE, Menu.FIRST + 3, 5, getString(R.string.about))
            .setIcon(android.R.drawable.ic_menu_info_details)
            .setShowAsAction(MenuItem.SHOW_AS_ACTION_IF_ROOM or MenuItem.SHOW_AS_ACTION_WITH_TEXT)
        menu.add(Menu.NONE, Menu.FIRST + 4, 1, getString(R.string.change_name))
            .setIcon(android.R.drawable.ic_menu_edit)
            .setShowAsAction(MenuItem.SHOW_AS_ACTION_IF_ROOM or MenuItem.SHOW_AS_ACTION_WITH_TEXT)

        return super.onCreateOptionsMenu(menu)
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            Menu.FIRST + 1 -> {
                Thread { reset() }.start()
                true
            }
            Menu.FIRST + 2 -> {
                AlertDialog.Builder(this)
                    .setTitle(R.string.profile_del)
                    .setMessage(R.string.profile_del_confirm)
                    .setPositiveButton(R.string.alert_dialog_ok) { _, _ -> delProfile(profile) }
                    .setNegativeButton(R.string.alert_dialog_cancel) { dialog, _ -> dialog.dismiss() }
                    .create()
                    .show()
                true
            }
            Menu.FIRST + 3 -> {
                showAbout()
                true
            }
            Menu.FIRST + 4 -> {
                rename()
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    private fun rename() {
        val factory = LayoutInflater.from(this)
        val textEntryView = factory.inflate(R.layout.alert_dialog_text_entry, null)
        val profileName = textEntryView.findViewById<EditText>(R.id.text_edit)
        profileName.setText(getProfileName(profile))

        AlertDialog.Builder(this)
            .setTitle(R.string.change_name)
            .setView(textEntryView)
            .setPositiveButton(R.string.alert_dialog_ok) { _, _ ->
                val settings = PreferenceManager.getDefaultSharedPreferences(this@ProxyDroid)
                var name = profileName.text?.toString() ?: return@setPositiveButton
                name = name.replace("|", "")
                if (name.isEmpty()) return@setPositiveButton

                settings.edit().putString("profile$profile", name).apply()
                profileList.summary = getProfileName(profile)

                val profileEntries = settings.getString("profileEntries", "")?.split("\\|".toRegex()) ?: emptyList()
                val profileValues = settings.getString("profileValues", "")?.split("\\|".toRegex()) ?: emptyList()

                val profileEntriesBuffer = StringBuilder()
                val profileValuesBuffer = StringBuilder()

                for (i in 0 until profileValues.size - 1) {
                    if (profileValues[i] == profile) {
                        profileEntriesBuffer.append(getProfileName(profile)).append("|")
                    } else {
                        profileEntriesBuffer.append(profileEntries.getOrNull(i) ?: "").append("|")
                    }
                    profileValuesBuffer.append(profileValues[i]).append("|")
                }

                profileEntriesBuffer.append(getString(R.string.profile_new))
                profileValuesBuffer.append("0")

                settings.edit()
                    .putString("profileEntries", profileEntriesBuffer.toString())
                    .putString("profileValues", profileValuesBuffer.toString())
                    .apply()

                loadProfileList()
            }
            .setNegativeButton(R.string.alert_dialog_cancel) { _, _ -> }
            .create()
            .show()
    }

    private fun delProfile(profile: String) {
        val settings = PreferenceManager.getDefaultSharedPreferences(this)
        val profileEntries = settings.getString("profileEntries", "")?.split("\\|".toRegex()) ?: emptyList()
        val profileValues = settings.getString("profileValues", "")?.split("\\|".toRegex()) ?: emptyList()

        Log.d(TAG, "Profile: $profile")
        if (profileEntries.size > 2) {
            val profileEntriesBuffer = StringBuilder()
            val profileValuesBuffer = StringBuilder()
            var newProfileValue = "1"

            for (i in 0 until profileValues.size - 1) {
                if (profile != profileValues[i]) {
                    profileEntriesBuffer.append(profileEntries.getOrNull(i) ?: "").append("|")
                    profileValuesBuffer.append(profileValues[i]).append("|")
                    newProfileValue = profileValues[i]
                }
            }
            profileEntriesBuffer.append(getString(R.string.profile_new))
            profileValuesBuffer.append("0")

            settings.edit()
                .putString("profileEntries", profileEntriesBuffer.toString())
                .putString("profileValues", profileValuesBuffer.toString())
                .putString("profile", newProfileValue)
                .apply()

            loadProfileList()
        }
    }

    private fun reset() {
        try {
            stopService(Intent(this@ProxyDroid, ProxyDroidService::class.java))
        } catch (e: Exception) {
            // Nothing
        }

        copyAssets()

        val filePath = filesDir.absolutePath

        Utils.runRootCommand(
            "${Utils.getIptablesPath()} -t nat -F OUTPUT\n" +
                    "$filePath/proxy.sh stop\n" +
                    "kill -9 `cat ${filePath}cntlm.pid`\n"
        )

        Utils.runRootCommand(
            "chmod 700 $filePath/redsocks\n" +
                    "chmod 700 $filePath/proxy.sh\n" +
                    "chmod 700 $filePath/gost.sh\n" +
                    "chmod 700 $filePath/cntlm\n" +
                    "chmod 700 $filePath/gost\n"
        )
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode == KeyEvent.KEYCODE_BACK && event.repeatCount == 0) {
            try {
                finish()
            } catch (ignore: Exception) {
            }
            return true
        }
        return super.onKeyDown(keyCode, event)
    }
}
