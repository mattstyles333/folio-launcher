package com.folio.launcher.data

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

val Context.folioUsage by preferencesDataStore(name = "folio_usage")

@Serializable
data class UsageData(
    val launches: Map<String, List<Long>> = emptyMap(),
)

class UsageStore(context: Context) {
    private val ds = context.applicationContext.folioUsage
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    val data: Flow<UsageData> = ds.data.map { prefs ->
        val raw = prefs[KEY] ?: return@map UsageData()
        runCatching { json.decodeFromString<UsageData>(raw) }.getOrDefault(UsageData())
    }.distinctUntilChanged()

    suspend fun record(packageName: String, at: Long = System.currentTimeMillis()) {
        ds.edit { prefs ->
            val current = prefs[KEY]?.let {
                runCatching { json.decodeFromString<UsageData>(it) }.getOrNull()
            } ?: UsageData()
            val cutoff = at - 30 * Ranking.DAY_MS
            val next = current.launches.toMutableMap()
            next[packageName] = (next[packageName].orEmpty() + at).filter { it >= cutoff }
            prefs[KEY] = json.encodeToString(UsageData(next))
        }
    }

    companion object {
        private val KEY = stringPreferencesKey("usage_json")
    }
}
