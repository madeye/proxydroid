package org.proxydroid.ui

import android.app.Application
import android.content.SharedPreferences
import android.preference.PreferenceManager
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.proxydroid.Profile
import org.proxydroid.utils.Utils

data class ProfileEntry(val id: String, val name: String)

data class MainUiState(
    val profile: Profile = Profile().apply { init() },
    val profiles: List<ProfileEntry> = emptyList(),
    val currentProfileId: String = "1",
    val isWorking: Boolean = false,
    val isConnecting: Boolean = false,
    val advancedExpanded: Boolean = false,
)

class MainViewModel(app: Application) : AndroidViewModel(app) {

    private val settings: SharedPreferences =
        PreferenceManager.getDefaultSharedPreferences(app)

    private val _state = MutableStateFlow(MainUiState())
    val state: StateFlow<MainUiState> = _state.asStateFlow()

    private val prefListener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ -> }

    init {
        settings.registerOnSharedPreferenceChangeListener(prefListener)
        // Mirror service-side flags into UI state reactively.
        viewModelScope.launch {
            combine(Utils.working, Utils.connecting) { w, c -> w to c }
                .collect { (w, c) ->
                    _state.value = _state.value.copy(isWorking = w, isConnecting = c)
                }
        }
        reload()
    }

    override fun onCleared() {
        super.onCleared()
        settings.unregisterOnSharedPreferenceChangeListener(prefListener)
    }

    fun reload() {
        viewModelScope.launch {
            val (profile, list, current) = withContext(Dispatchers.IO) {
                val current = settings.getString("profile", "1") ?: "1"
                val p = Profile().also { it.getProfile(settings) }
                if (p.proxyType.isEmpty()) p.proxyType = "socks5"
                Triple(p, loadProfileList(), current)
            }
            _state.value = _state.value.copy(
                profile = profile,
                profiles = list,
                currentProfileId = current,
                isWorking = Utils.isWorking(),
                isConnecting = Utils.isConnecting(),
            )
        }
    }

    fun updateProfile(transform: Profile.() -> Unit) {
        val next = _state.value.profile.copy().apply(transform)
        _state.value = _state.value.copy(profile = next)
        viewModelScope.launch(Dispatchers.IO) {
            next.setProfile(settings)
            persistCurrentProfileJson(next)
        }
    }

    fun toggleAdvanced() {
        _state.value = _state.value.copy(advancedExpanded = !_state.value.advancedExpanded)
    }

    fun selectProfile(id: String) {
        if (id == _state.value.currentProfileId) return
        viewModelScope.launch(Dispatchers.IO) {
            // Save current profile JSON under its ID, then load the new one.
            persistCurrentProfileJson(_state.value.profile)
            settings.edit().putString("profile", id).apply()
            val raw = settings.getString(id, "").orEmpty()
            val next = Profile().apply {
                if (raw.isEmpty()) {
                    init()
                    name = profileNameFor(id)
                } else {
                    decodeJson(raw)
                }
                if (proxyType.isEmpty()) proxyType = "socks5"
            }
            next.setProfile(settings)
            _state.value = _state.value.copy(
                profile = next,
                currentProfileId = id,
                profiles = loadProfileList(),
            )
        }
    }

    fun newProfile(name: String) {
        viewModelScope.launch(Dispatchers.IO) {
            persistCurrentProfileJson(_state.value.profile)
            val nextId = nextProfileId()
            val p = Profile().apply {
                init()
                this.name = if (name.isBlank()) profileNameFor(nextId) else name
                proxyType = "socks5"
            }
            settings.edit().putString("profile", nextId).apply()
            p.setProfile(settings)
            settings.edit().putString(nextId, p.toString()).apply()
            _state.value = _state.value.copy(
                profile = p,
                currentProfileId = nextId,
                profiles = loadProfileList(),
            )
        }
    }

    fun renameCurrent(newName: String) {
        if (newName.isBlank()) return
        updateProfile { name = newName }
        viewModelScope.launch(Dispatchers.IO) {
            _state.value = _state.value.copy(profiles = loadProfileList())
        }
    }

    fun deleteCurrent() {
        viewModelScope.launch(Dispatchers.IO) {
            val cur = _state.value.currentProfileId
            settings.edit().remove(cur).apply()
            // Pick the first remaining profile (or create a fresh one).
            val remaining = loadProfileList().filter { it.id != cur }
            val next = remaining.firstOrNull()?.id ?: "1"
            val raw = settings.getString(next, "").orEmpty()
            val p = Profile().apply {
                if (raw.isEmpty()) {
                    init(); name = profileNameFor(next); proxyType = "socks5"
                } else decodeJson(raw)
            }
            settings.edit().putString("profile", next).apply()
            p.setProfile(settings)
            _state.value = _state.value.copy(
                profile = p,
                currentProfileId = next,
                profiles = remaining,
            )
        }
    }

    private fun persistCurrentProfileJson(p: Profile) {
        val cur = _state.value.currentProfileId
        settings.edit().putString(cur, p.toString()).apply()
    }

    private fun nextProfileId(): String {
        val raw = settings.getString("profileValues", "") ?: ""
        val ids = raw.split("|").mapNotNull { it.trim().takeIf { s -> s.isNotEmpty() }?.toIntOrNull() }
        val next = ((ids.maxOrNull() ?: 0) + 1).toString()
        // Maintain the legacy profileEntries / profileValues bookkeeping.
        val values = (ids + next.toInt()).joinToString("|") + "|"
        val entries = ids.joinToString("|") { profileNameFor(it.toString()) } +
            (if (ids.isNotEmpty()) "|" else "") + profileNameFor(next) + "|"
        settings.edit()
            .putString("profileValues", values)
            .putString("profileEntries", entries)
            .apply()
        return next
    }

    private fun profileNameFor(id: String): String =
        settings.getString("profile$id", null) ?: "Profile $id"

    private fun loadProfileList(): List<ProfileEntry> {
        val raw = settings.getString("profileValues", null)
        val ids = if (raw.isNullOrEmpty()) {
            // Bootstrap a single default profile.
            listOf("1").also {
                settings.edit()
                    .putString("profileValues", "1|")
                    .putString("profileEntries", profileNameFor("1") + "|")
                    .apply()
            }
        } else {
            raw.split("|").map { it.trim() }.filter { it.isNotEmpty() }
        }
        return ids.map { id ->
            val storedName = settings.getString(id, null)?.let { json ->
                runCatching {
                    Profile().apply { decodeJson(json) }.name.takeIf { it.isNotBlank() }
                }.getOrNull()
            }
            ProfileEntry(id, storedName ?: profileNameFor(id))
        }
    }
}
