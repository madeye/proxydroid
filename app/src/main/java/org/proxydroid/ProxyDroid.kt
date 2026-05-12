/* proxydroid - Global / Individual Proxy App for Android
 * Copyright (C) 2011 Max Lv <max.c.lv@gmail.com>
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package org.proxydroid

import android.content.Intent
import android.net.VpnService
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import org.proxydroid.ui.MainScreen
import org.proxydroid.ui.MainViewModel
import org.proxydroid.ui.theme.ProxyDroidTheme
import org.proxydroid.utils.ProxyController

class ProxyDroid : ComponentActivity() {

    private val viewModel: MainViewModel by viewModels()

    private val vpnConsentLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            val extras = ProxyController.buildExtras(viewModel.state.value.profile)
            startService(
                Intent(this, ProxyDroidVpnService::class.java).putExtras(extras)
            )
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            ProxyDroidTheme {
                val state by viewModel.state.collectAsState()
                MainScreen(
                    state = state,
                    onToggle = { wantOn ->
                        if (wantOn) startVpn() else stopVpn()
                    },
                    onProfileEdit = { viewModel.updateProfile(it) },
                    onSelectProfile = viewModel::selectProfile,
                    onNewProfile = { viewModel.newProfile("") },
                    onRenameProfile = viewModel::renameCurrent,
                    onDeleteProfile = viewModel::deleteCurrent,
                    onToggleAdvanced = viewModel::toggleAdvanced,
                )
            }
        }

        // MainViewModel observes Utils.working / Utils.connecting StateFlows
        // directly, so the Compose UI auto-refreshes without polling.

        if (intent?.getBooleanExtra(ProxyController.EXTRA_AUTO_START, false) == true) {
            startVpn()
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (intent.getBooleanExtra(ProxyController.EXTRA_AUTO_START, false)) {
            startVpn()
        }
    }

    override fun onResume() {
        super.onResume()
        viewModel.reload()
    }

    private fun startVpn() {
        val consent = VpnService.prepare(this)
        if (consent != null) {
            vpnConsentLauncher.launch(consent)
        } else {
            val extras = ProxyController.buildExtras(viewModel.state.value.profile)
            startService(
                Intent(this, ProxyDroidVpnService::class.java).putExtras(extras)
            )
        }
    }

    private fun stopVpn() {
        ProxyController.stop(this)
    }
}
