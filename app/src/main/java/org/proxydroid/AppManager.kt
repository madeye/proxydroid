/* Per-app proxy selector. Originally based on Orbot/The Guardian Project. */

package org.proxydroid

import android.content.Context
import android.content.pm.ApplicationInfo
import android.graphics.drawable.Drawable
import android.os.Bundle
import android.preference.PreferenceManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.proxydroid.ui.theme.ProxyDroidTheme
import org.proxydroid.ui.toImageBitmap
import java.util.StringTokenizer

class AppManager : ComponentActivity() {

    companion object {
        const val PREFS_KEY_PROXYED = "Proxyed"

        @JvmStatic
        fun getProxyedApps(context: Context, self: Boolean): Array<ProxyedApp> {
            val prefs = PreferenceManager.getDefaultSharedPreferences(context)
            val raw = prefs.getString(PREFS_KEY_PROXYED, "") ?: ""
            val st = StringTokenizer(raw, "|")
            val tokens = Array(st.countTokens()) { st.nextToken() }.also { it.sort() }

            val pMgr = context.packageManager
            val out = mutableListOf<ProxyedApp>()
            for (info in pMgr.getInstalledApplications(0)) {
                if (info.uid < 10000) continue
                val app = ProxyedApp().apply {
                    uid = info.uid
                    username = pMgr.getNameForUid(uid)
                    isProxyed = when {
                        info.packageName == "org.proxydroid" -> self
                        username != null && tokens.binarySearch(username!!) >= 0 -> true
                        else -> false
                    }
                }
                if (app.isProxyed) out.add(app)
            }
            return out.toTypedArray()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            ProxyDroidTheme {
                AppManagerScreen(onBack = { finish() })
            }
        }
    }
}

private data class AppRow(
    val uid: Int,
    val username: String?,
    val name: String,
    val icon: Drawable?,
    var proxyed: Boolean,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AppManagerScreen(onBack: () -> Unit) {
    val ctx = LocalContext.current
    val rows = remember { mutableStateListOf<AppRow>() }
    var loading by remember { mutableStateOf(true) }
    var query by remember { mutableStateOf("") }
    var selectedOnly by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        val loaded = withContext(Dispatchers.IO) { loadApps(ctx) }
        rows.clear()
        rows.addAll(loaded)
        loading = false
    }

    val visible by remember(query, selectedOnly, rows.size) {
        derivedStateOf {
            rows.filter { r ->
                (!selectedOnly || r.proxyed) &&
                    (query.isBlank() || r.name.contains(query, ignoreCase = true) ||
                        (r.username?.contains(query, ignoreCase = true) == true))
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Per-app routing") },
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
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    label = { Text("Search") },
                    leadingIcon = { Icon(Icons.Default.Search, null) },
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                )
                FilterChip(
                    selected = selectedOnly,
                    onClick = { selectedOnly = !selectedOnly },
                    label = { Text("Selected") },
                )
            }

            if (loading) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            } else if (visible.isEmpty()) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        if (selectedOnly) "No apps selected yet." else "No matches.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                LazyColumn(modifier = Modifier.fillMaxSize()) {
                    items(visible.size, key = { i -> visible[i].uid }) { i ->
                        val row = visible[i]
                        ListItem(
                            headlineContent = { Text(row.name) },
                            supportingContent = {
                                row.username?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                            },
                            leadingContent = {
                                val icon = row.icon
                                if (icon != null) {
                                    Image(
                                        painter = BitmapPainter(remember(row.uid) { icon.toImageBitmap(96, 96) }),
                                        contentDescription = null,
                                        modifier = Modifier.size(40.dp),
                                    )
                                } else {
                                    Box(modifier = Modifier.size(40.dp))
                                }
                            },
                            trailingContent = {
                                Switch(
                                    checked = row.proxyed,
                                    onCheckedChange = { checked ->
                                        val idx = rows.indexOfFirst { it.uid == row.uid }
                                        if (idx >= 0) {
                                            rows[idx] = rows[idx].copy(proxyed = checked)
                                            saveSelection(ctx, rows)
                                        }
                                    },
                                )
                            },
                        )
                    }
                }
            }
        }
    }
}

private fun loadApps(ctx: Context): List<AppRow> {
    val prefs = PreferenceManager.getDefaultSharedPreferences(ctx)
    val raw = prefs.getString(AppManager.PREFS_KEY_PROXYED, "") ?: ""
    val tokens = StringTokenizer(raw, "|").let { st ->
        Array(st.countTokens()) { st.nextToken() }.also { it.sort() }
    }
    val pMgr = ctx.packageManager
    val list = mutableListOf<AppRow>()
    for (info: ApplicationInfo in pMgr.getInstalledApplications(0)) {
        if (info.uid < 10000) continue
        if (info.processName == null) continue
        val label = pMgr.getApplicationLabel(info)?.toString().orEmpty()
        if (label.isBlank()) continue
        val icon = runCatching { pMgr.getApplicationIcon(info) }.getOrNull()
        val username = pMgr.getNameForUid(info.uid)
        val proxyed = username != null && tokens.binarySearch(username) >= 0
        list.add(AppRow(uid = info.uid, username = username, name = label, icon = icon, proxyed = proxyed))
    }
    return list.sortedWith(compareByDescending<AppRow> { it.proxyed }.thenBy { it.name.lowercase() })
}

private fun saveSelection(ctx: Context, rows: List<AppRow>) {
    val prefs = PreferenceManager.getDefaultSharedPreferences(ctx)
    val sb = StringBuilder()
    for (r in rows) {
        if (r.proxyed && r.username != null) {
            sb.append(r.username).append('|')
        }
    }
    prefs.edit().putString(AppManager.PREFS_KEY_PROXYED, sb.toString()).apply()
}

