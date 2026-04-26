package org.proxydroid.ui

import android.content.Intent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Divider
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberTopAppBarState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import org.proxydroid.AppManager
import org.proxydroid.BypassListActivity
import org.proxydroid.Profile
import org.proxydroid.R

private val PROXY_TYPES = listOf("socks5", "socks4", "http", "https")

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(
    state: MainUiState,
    onToggle: (Boolean) -> Unit,
    onProfileEdit: (Profile.() -> Unit) -> Unit,
    onSelectProfile: (String) -> Unit,
    onNewProfile: () -> Unit,
    onRenameProfile: (String) -> Unit,
    onDeleteProfile: () -> Unit,
    onToggleAdvanced: () -> Unit,
) {
    val scroll = rememberScrollState()
    val topBarState = rememberTopAppBarState()
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior(topBarState)
    var menuOpen by remember { mutableStateOf(false) }
    var showRename by remember { mutableStateOf(false) }
    var showDeleteConfirm by remember { mutableStateOf(false) }

    Scaffold(
        modifier = Modifier
            .fillMaxSize()
            .nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            LargeTopAppBar(
                title = { Text(stringRes(R.string.app_name)) },
                actions = {
                    IconButton(onClick = { menuOpen = true }) {
                        Icon(Icons.Default.MoreVert, contentDescription = "More")
                    }
                    DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                        DropdownMenuItem(
                            text = { Text("Rename profile") },
                            onClick = { menuOpen = false; showRename = true },
                            leadingIcon = { Icon(Icons.Default.Edit, null) },
                        )
                        DropdownMenuItem(
                            text = { Text("Delete profile") },
                            onClick = { menuOpen = false; showDeleteConfirm = true },
                            leadingIcon = { Icon(Icons.Default.Delete, null) },
                        )
                    }
                },
                scrollBehavior = scrollBehavior,
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .padding(horizontal = 16.dp)
                .verticalScroll(scroll),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            ConnectionCard(state = state, onToggle = onToggle)
            ProfileCard(
                state = state,
                onSelectProfile = onSelectProfile,
                onNewProfile = onNewProfile,
            )
            ProxyForm(profile = state.profile, edit = onProfileEdit)
            AdvancedSection(
                profile = state.profile,
                expanded = state.advancedExpanded,
                onToggle = onToggleAdvanced,
                edit = onProfileEdit,
            )
            Spacer(Modifier.height(24.dp))
        }
    }

    if (showRename) {
        TextEditDialog(
            title = "Rename profile",
            initial = state.profile.name,
            onDismiss = { showRename = false },
            onConfirm = { showRename = false; onRenameProfile(it) },
        )
    }
    if (showDeleteConfirm) {
        ConfirmDialog(
            title = "Delete this profile?",
            confirmText = "Delete",
            onDismiss = { showDeleteConfirm = false },
            onConfirm = { showDeleteConfirm = false; onDeleteProfile() },
        )
    }
}

@Composable
private fun ConnectionCard(state: MainUiState, onToggle: (Boolean) -> Unit) {
    val on = state.isWorking
    val tone = if (on) MaterialTheme.colorScheme.primaryContainer
    else MaterialTheme.colorScheme.surfaceVariant
    val onTone = if (on) MaterialTheme.colorScheme.onPrimaryContainer
    else MaterialTheme.colorScheme.onSurfaceVariant

    Card(
        colors = CardDefaults.cardColors(containerColor = tone, contentColor = onTone),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier
                .padding(20.dp)
                .fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(14.dp)
                    .background(
                        if (on) Color(0xFF2BAA63) else Color(0xFF9E9E9E),
                        CircleShape,
                    ),
            )
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    if (on) "Connected" else "Disconnected",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    secondaryStatus(state),
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            if (state.isConnecting) {
                CircularProgressIndicator(
                    modifier = Modifier.size(28.dp),
                    strokeWidth = 3.dp,
                )
            } else {
                Switch(
                    checked = on,
                    onCheckedChange = onToggle,
                )
            }
        }
    }
}

private fun secondaryStatus(state: MainUiState): String {
    val p = state.profile
    val host = p.host.ifBlank { "—" }
    val port = if (p.port > 0) ":${p.port}" else ""
    return "${p.proxyType.uppercase()} • $host$port"
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ProfileCard(
    state: MainUiState,
    onSelectProfile: (String) -> Unit,
    onNewProfile: () -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    val current = state.profiles.firstOrNull { it.id == state.currentProfileId }
        ?: ProfileEntry(state.currentProfileId, "Profile ${state.currentProfileId}")

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text("Profile", style = MaterialTheme.typography.labelLarge)
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                ExposedDropdownMenuBox(
                    expanded = expanded,
                    onExpandedChange = { expanded = !expanded },
                    modifier = Modifier.weight(1f),
                ) {
                    OutlinedTextField(
                        value = current.name,
                        onValueChange = {},
                        readOnly = true,
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) },
                        modifier = Modifier
                            .menuAnchor()
                            .fillMaxWidth(),
                    )
                    DropdownMenu(
                        expanded = expanded,
                        onDismissRequest = { expanded = false },
                    ) {
                        state.profiles.forEach { p ->
                            DropdownMenuItem(
                                text = { Text(p.name) },
                                onClick = {
                                    expanded = false
                                    onSelectProfile(p.id)
                                },
                            )
                        }
                    }
                }
                Spacer(Modifier.width(8.dp))
                IconButton(onClick = onNewProfile) {
                    Icon(Icons.Default.Add, contentDescription = "New profile")
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ProxyForm(profile: Profile, edit: (Profile.() -> Unit) -> Unit) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text("Proxy", style = MaterialTheme.typography.labelLarge)
            Spacer(Modifier.height(12.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                PROXY_TYPES.forEach { t ->
                    FilterChip(
                        selected = profile.proxyType == t,
                        onClick = { edit { proxyType = t } },
                        label = { Text(t.uppercase()) },
                        modifier = Modifier.weight(1f),
                    )
                }
            }
            Spacer(Modifier.height(12.dp))

            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedTextField(
                    value = profile.host,
                    onValueChange = { v -> edit { host = v.trim() } },
                    label = { Text("Host") },
                    singleLine = true,
                    modifier = Modifier.weight(2f),
                )
                OutlinedTextField(
                    value = if (profile.port == 0) "" else profile.port.toString(),
                    onValueChange = { v ->
                        val n = v.filter { it.isDigit() }.take(5).toIntOrNull() ?: 0
                        edit { port = n }
                    },
                    label = { Text("Port") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.weight(1f),
                )
            }

            Spacer(Modifier.height(8.dp))
            ToggleRow(
                title = "Authentication",
                checked = profile.isAuth,
                onChange = { edit { isAuth = it } },
            )
            AnimatedVisibility(visible = profile.isAuth) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = profile.user,
                        onValueChange = { v -> edit { user = v } },
                        label = { Text("Username") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        value = profile.password,
                        onValueChange = { v -> edit { password = v } },
                        label = { Text("Password") },
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    ToggleRow(
                        title = "NTLM",
                        checked = profile.isNTLM,
                        onChange = { edit { isNTLM = it } },
                    )
                    AnimatedVisibility(visible = profile.isNTLM) {
                        OutlinedTextField(
                            value = profile.domain,
                            onValueChange = { v -> edit { domain = v } },
                            label = { Text("NTLM Domain") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun AdvancedSection(
    profile: Profile,
    expanded: Boolean,
    onToggle: () -> Unit,
    edit: (Profile.() -> Unit) -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("Advanced", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                IconButton(onClick = onToggle) {
                    Icon(
                        if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                        contentDescription = if (expanded) "Collapse" else "Expand",
                    )
                }
            }
            AnimatedVisibility(visible = expanded) {
                Column(
                    modifier = Modifier.padding(horizontal = 16.dp).padding(bottom = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Divider()
                    ToggleRow(
                        title = "PAC (Auto Setting)",
                        subtitle = "Treat host as a PAC URL",
                        checked = profile.isPAC,
                        onChange = { edit { isPAC = it } },
                    )
                    ToggleRow(
                        title = "DNS proxy",
                        subtitle = "Forward DNS queries through the proxy",
                        checked = profile.isDNSProxy,
                        onChange = { edit { isDNSProxy = it } },
                    )
                    ToggleRow(
                        title = "Auto-connect on selected SSIDs",
                        checked = profile.isAutoConnect,
                        onChange = { edit { isAutoConnect = it } },
                    )
                    AnimatedVisibility(visible = profile.isAutoConnect) {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedTextField(
                                value = profile.ssid,
                                onValueChange = { v -> edit { ssid = v } },
                                label = { Text("Bind SSID (comma-separated)") },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth(),
                            )
                            OutlinedTextField(
                                value = profile.excludedSsid,
                                onValueChange = { v -> edit { excludedSsid = v } },
                                label = { Text("Exclude SSID (comma-separated)") },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                    }
                    Divider()
                    LinkRow(
                        icon = Icons.Default.Block,
                        title = "Bypass addresses",
                        targetActivity = BypassListActivity::class.java,
                    )
                    LinkRow(
                        icon = Icons.Default.Apps,
                        title = "Per-app routing",
                        subtitle = if (profile.isBypassApps) "Bypass selected apps" else "Proxy only selected apps",
                        targetActivity = AppManager::class.java,
                    )
                    ToggleRow(
                        title = "Bypass selected apps",
                        subtitle = "Off = proxy only the selected apps",
                        checked = profile.isBypassApps,
                        onChange = { edit { isBypassApps = it } },
                    )
                }
            }
        }
    }
}

@Composable
private fun ToggleRow(
    title: String,
    subtitle: String? = null,
    checked: Boolean,
    onChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            if (subtitle != null) Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
private fun LinkRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    subtitle: String? = null,
    targetActivity: Class<*>,
) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            if (subtitle != null) Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        TextButton(onClick = { ctx.startActivity(Intent(ctx, targetActivity)) }) {
            Text("Open")
        }
    }
}

@Composable
private fun TextEditDialog(
    title: String,
    initial: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var value by remember { mutableStateOf(initial) }
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = value,
                onValueChange = { value = it },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
        },
        confirmButton = { TextButton(onClick = { onConfirm(value) }) { Text("OK") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun ConfirmDialog(
    title: String,
    confirmText: String,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        confirmButton = { TextButton(onClick = onConfirm) { Text(confirmText) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun stringRes(id: Int): String =
    androidx.compose.ui.res.stringResource(id = id)
