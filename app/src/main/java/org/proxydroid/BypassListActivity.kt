/* proxydroid - Global / Individual Proxy App for Android
 * Copyright (C) 2011 Max Lv <max.c.lv@gmail.com>
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package org.proxydroid

import android.content.Context
import android.net.Uri
import android.os.Bundle
import android.preference.PreferenceManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.Upload
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import org.proxydroid.ui.theme.ProxyDroidTheme
import org.proxydroid.utils.Constraints
import java.io.BufferedOutputStream
import java.io.BufferedReader
import java.io.InputStreamReader

class BypassListActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            ProxyDroidTheme {
                BypassListScreen(
                    onBack = { finish() },
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun BypassListScreen(onBack: () -> Unit) {
    val ctx = LocalContext.current
    val settings = remember { PreferenceManager.getDefaultSharedPreferences(ctx) }
    val profile = remember { Profile() }
    val items = remember { mutableStateListOfBypass(settings, profile) }

    var addrToEdit by remember { mutableStateOf<Pair<Int, String>?>(null) }
    var addrToDelete by remember { mutableStateOf<Int?>(null) }
    var showAdd by remember { mutableStateOf(false) }
    var showPreset by remember { mutableStateOf(false) }

    val importLauncher = rememberImportLauncher { uri ->
        importFromUri(ctx, uri, items)
        persist(settings, profile, items)
    }
    val exportLauncher = rememberExportLauncher { uri ->
        exportToUri(ctx, uri, items)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Bypass addresses") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize(),
        ) {
            ActionBar(
                onAdd = { showAdd = true },
                onPreset = { showPreset = true },
                onImport = { importLauncher.launch(arrayOf("text/*", "application/octet-stream")) },
                onExport = {
                    val name = (profile.host.takeIf { it.isNotBlank() } ?: "bypass") + ".opt"
                    exportLauncher.launch(name)
                },
            )
            if (items.isEmpty()) {
                EmptyState()
            } else {
                LazyColumn(modifier = Modifier.fillMaxSize()) {
                    items(items.size, key = { i -> "$i:${items[i]}" }) { i ->
                        ListItem(
                            headlineContent = { Text(items[i]) },
                            trailingContent = {
                                IconButton(onClick = { addrToDelete = i }) {
                                    Icon(Icons.Default.Delete, contentDescription = "Delete")
                                }
                            },
                            modifier = Modifier.clickable { addrToEdit = i to items[i] },
                        )
                    }
                }
            }
        }
    }

    if (showAdd) {
        AddrEditDialog(
            title = "Add bypass address",
            initial = "0.0.0.0/0",
            onDismiss = { showAdd = false },
            onConfirm = { input ->
                showAdd = false
                val v = Profile.validateAddr(input)
                if (v == null) {
                    Toast.makeText(ctx, ctx.getString(R.string.err_addr), Toast.LENGTH_LONG).show()
                } else {
                    items.add(v)
                    persist(settings, profile, items)
                }
            },
        )
    }
    addrToEdit?.let { (idx, current) ->
        AddrEditDialog(
            title = "Edit bypass address",
            initial = current,
            onDismiss = { addrToEdit = null },
            onConfirm = { input ->
                addrToEdit = null
                val v = Profile.validateAddr(input)
                if (v == null) {
                    Toast.makeText(ctx, ctx.getString(R.string.err_addr), Toast.LENGTH_LONG).show()
                } else if (idx in items.indices) {
                    items[idx] = v
                    persist(settings, profile, items)
                }
            },
        )
    }
    addrToDelete?.let { idx ->
        AlertDialog(
            onDismissRequest = { addrToDelete = null },
            title = { Text(items.getOrNull(idx) ?: "") },
            text = { Text("Delete this bypass address?") },
            confirmButton = {
                TextButton(onClick = {
                    addrToDelete = null
                    if (idx in items.indices) {
                        items.removeAt(idx)
                        persist(settings, profile, items)
                    }
                }) { Text("Delete") }
            },
            dismissButton = {
                TextButton(onClick = { addrToDelete = null }) { Text("Cancel") }
            },
        )
    }
    if (showPreset) {
        PresetDialog(
            onDismiss = { showPreset = false },
            onConfirm = { which ->
                showPreset = false
                if (which in Constraints.PRESETS.indices) {
                    items.clear()
                    Constraints.PRESETS[which].forEach { addr ->
                        Profile.validateAddr(addr)?.let { items.add(it) }
                    }
                    persist(settings, profile, items)
                }
            },
        )
    }
}

private fun mutableStateListOfBypass(
    settings: android.content.SharedPreferences,
    profile: Profile,
): SnapshotStateList<String> {
    profile.getProfile(settings)
    val list = androidx.compose.runtime.mutableStateListOf<String>()
    Profile.decodeAddrs(profile.bypassAddrs).forEach { list.add(it) }
    return list
}

private fun persist(
    settings: android.content.SharedPreferences,
    profile: Profile,
    items: List<String>,
) {
    profile.getProfile(settings)
    profile.bypassAddrs = Profile.encodeAddrs(items.toTypedArray())
    profile.setProfile(settings)
}

private fun importFromUri(ctx: Context, uri: Uri?, items: SnapshotStateList<String>) {
    if (uri == null) return
    try {
        ctx.contentResolver.openInputStream(uri)?.use { input ->
            BufferedReader(InputStreamReader(input)).use { br ->
                val fresh = mutableListOf<String>()
                var line: String?
                while (br.readLine().also { line = it } != null) {
                    Profile.validateAddr(line)?.let { fresh.add(it) }
                }
                items.clear()
                items.addAll(fresh)
            }
        }
        Toast.makeText(ctx, "Imported ${items.size} entries", Toast.LENGTH_SHORT).show()
    } catch (e: Exception) {
        Toast.makeText(ctx, "Import failed: ${e.message}", Toast.LENGTH_LONG).show()
    }
}

private fun exportToUri(ctx: Context, uri: Uri?, items: List<String>) {
    if (uri == null) return
    try {
        ctx.contentResolver.openOutputStream(uri)?.use { out ->
            BufferedOutputStream(out).use { bw ->
                items.forEach { bw.write("$it\n".toByteArray()) }
                bw.flush()
            }
        }
        Toast.makeText(ctx, "Exported ${items.size} entries", Toast.LENGTH_SHORT).show()
    } catch (e: Exception) {
        Toast.makeText(ctx, "Export failed: ${e.message}", Toast.LENGTH_LONG).show()
    }
}

@Composable
private fun rememberImportLauncher(onResult: (Uri?) -> Unit) =
    androidx.activity.compose.rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { onResult(it) }

@Composable
private fun rememberExportLauncher(onResult: (Uri?) -> Unit) =
    androidx.activity.compose.rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("text/plain"),
    ) { onResult(it) }

@Composable
private fun ActionBar(
    onAdd: () -> Unit,
    onPreset: () -> Unit,
    onImport: () -> Unit,
    onExport: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        AssistChip(onClick = onAdd,
            label = { Text("Add") },
            leadingIcon = { Icon(Icons.Default.Add, null) })
        AssistChip(onClick = onPreset,
            label = { Text("Preset") },
            leadingIcon = { Icon(Icons.Default.Tune, null) })
        AssistChip(onClick = onImport,
            label = { Text("Import") },
            leadingIcon = { Icon(Icons.Default.Download, null) })
        AssistChip(onClick = onExport,
            label = { Text("Export") },
            leadingIcon = { Icon(Icons.Default.Upload, null) })
    }
}

@Composable
private fun EmptyState() {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            "No bypass addresses yet.\nUse Add or Preset to get started.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun AddrEditDialog(
    title: String,
    initial: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var v by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = v,
                onValueChange = { v = it },
                singleLine = true,
                label = { Text("e.g. 192.168.1.0/24") },
            )
        },
        confirmButton = { TextButton(onClick = { onConfirm(v.trim()) }) { Text("OK") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun PresetDialog(
    onDismiss: () -> Unit,
    onConfirm: (Int) -> Unit,
) {
    val ctx = LocalContext.current
    val labels = remember { ctx.resources.getStringArray(R.array.presets_list) }
    var picked by remember { mutableStateOf(-1) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Choose a preset") },
        text = {
            Column {
                labels.forEachIndexed { i, label ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = picked == i, onClick = { picked = i })
                        Text(label, modifier = Modifier.padding(start = 8.dp))
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(picked) },
                enabled = picked >= 0,
            ) { Text("Apply") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

