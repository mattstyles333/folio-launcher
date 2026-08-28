package com.pulse.launcher.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

val Context.pulsePrefs by preferencesDataStore(name = "pulse_prefs")

@Serializable
data class SlotPref(
    val packageName: String? = null,
    val activityName: String? = null,
    val pinned: Boolean = false,
)

const val DEFAULT_ACCENT: Int = 0xFFC4B8A8.toInt()

@Serializable
data class Prefs(
    val showClock: Boolean = true,
    val onboardingComplete: Boolean = false,
    val skippedRole: Boolean = false,
    val wallpaperSet: Boolean = false,
    val skippedWallpaper: Boolean = false,
    val slots: List<SlotPref> = List(4) { SlotPref() },
    val accent: Int = DEFAULT_ACCENT,
    val lastRailDay: Int = -1,
    val firstSeen: Map<String, Long> = emptyMap(),
    val dismissedRecents: Map<String, Long> = emptyMap(),
    val silentHint: Boolean = false,
    val bingIndex: Int = -1,
    val quoteSalt: Int = 0,
)

class PrefsStore(context: Context) {
    private val ds = context.applicationContext.pulsePrefs
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    val data: Flow<Prefs> = ds.data.map { prefs ->
        val raw = prefs[KEY] ?: return@map Prefs()
        runCatching { json.decodeFromString<Prefs>(raw) }.getOrDefault(Prefs())
    }.distinctUntilChanged()

    suspend fun update(transform: (Prefs) -> Prefs) {
        ds.edit { prefs ->
            val current = prefs[KEY]?.let {
                runCatching { json.decodeFromString<Prefs>(it) }.getOrNull()
            } ?: Prefs()
            val next = transform(current).let { p ->
                p.copy(slots = (p.slots + List(4) { SlotPref() }).take(4))
            }
            prefs[KEY] = json.encodeToString(next)
        }
    }

    companion object {
        private val KEY = stringPreferencesKey("prefs_json")
    }
}
