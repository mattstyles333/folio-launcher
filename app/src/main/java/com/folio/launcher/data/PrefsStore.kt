package com.folio.launcher.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.folio.hermes.telemetry.Telemetry
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject

val Context.folioPrefs by preferencesDataStore(name = "folio_prefs", corruptionHandler = startOver("prefs.corrupt"))

/**
 * A store file that can't be parsed (flash damage, say) starts over empty instead of throwing:
 * Folio is the home app, and an error escaping its state would crash it on every start.
 */
internal fun startOver(event: String) = ReplaceFileCorruptionHandler { e ->
    Telemetry.warn(event, null, mapOf("type" to (e.cause ?: e).javaClass.simpleName))
    emptyPreferences()
}

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
    val skippedRole: Boolean = false,
    val wallpaperSet: Boolean = false,
    val skippedWallpaper: Boolean = false,
    val accessOffered: Boolean = false,
    val slots: List<SlotPref> = List(Ranking.RAIL_SLOTS) { SlotPref() },
    val accent: Int = DEFAULT_ACCENT,
    val lastRailDay: Int = -1,
    val firstSeen: Map<String, Long> = emptyMap(),
    val silentHint: Boolean = false,
    val mediaHintDismissed: Boolean = false,
    /** Bing identity (OHR name) of the current print; empty when it's a local photo. */
    val bingId: String = "",
    val bingPrevId: String = "",
    val bingDay: Int = -1,
    /** The last Bing prints, newest first (the current one too): a random pick stays away from them. */
    val recentBingIds: List<String> = emptyList(),
    /** Your photos: the roll's files in Folio's own storage, in the order picked; empty when there's none. */
    val rollFiles: List<String> = emptyList(),
    /** Position in [rollFiles] of the current print; -1 when it isn't one of your photos. */
    val rollAt: Int = -1,
    /** The same for the previous print: Previous print swaps the two, as it does [bingId] and [bingPrevId]. */
    val rollPrevAt: Int = -1,
    /** Day the current one of your photos became the print: the first unlock after it shows the next. */
    val rollDay: Int = -1,
    /** Old installs stored archive list positions. Only [migrated] reads them. */
    val bingIndex: Int = -1,
    val bingPrevIndex: Int = -1,
    val quoteSalt: Int = 0,
    /** Bank index Jev fitted to the current / previous Bing print; -1 is the plain daily pick. */
    val quoteFit: Int = -1,
    val quotePrevFit: Int = -1,
    val hiddenPackages: List<String> = emptyList(),
    /** Explicit Ask choice: an AI app package, or Hermes Live's. Empty = never picked (Hermes first). */
    val aiPackage: String = "",
    /** Hermes's stored session id for Folio's Ask (not a credential). Empty = start a new one. */
    val hermesSessionId: String = "",
    /** Pasted in Settings. Never built into the APK: the repo and its Releases are public. */
    val openRouterKey: String = "",
    /** Crash reports, errors and start-up time to the build's diagnostics endpoint (never content). On by default. */
    val diagnostics: Boolean = true,
) {
    val isBingPrint: Boolean get() = bingId.isNotEmpty()

    val isRollPrint: Boolean get() = rollAt in rollFiles.indices

    /** Old installs stored a position in the day's archive; keep "this is a Bing print" across the upgrade. */
    fun migrated(): Prefs {
        if (bingIndex < 0 && bingPrevIndex < 0) return this
        return copy(
            bingId = bingId.ifEmpty { if (bingIndex >= 0) LEGACY_BING_ID else "" },
            bingPrevId = bingPrevId.ifEmpty { if (bingPrevIndex >= 0) LEGACY_BING_ID else "" },
            bingIndex = -1,
            bingPrevIndex = -1,
        )
    }

    companion object {
        const val LEGACY_BING_ID = "legacy"
    }
}

/** Prefs as one JSON blob. */
class PrefsStore private constructor(private val ds: DataStore<Preferences>) {
    constructor(context: Context) : this(context.applicationContext.folioPrefs)

    val data: Flow<Prefs> = ds.data.map { prefs ->
        prefs[KEY]?.let { read(it) } ?: Prefs()
    }.distinctUntilChanged()

    suspend fun update(transform: (Prefs) -> Prefs) {
        ds.edit { prefs ->
            val current = prefs[KEY]?.let { readOrKeep(it, prefs) } ?: Prefs()
            val next = transform(current).let { p -> p.copy(slots = Ranking.railSlots(p.slots)) }
            // Removing the key in Settings removes every copy of it, a kept blob's too.
            if (current.openRouterKey.isNotEmpty() && next.openRouterKey.isEmpty()) prefs.remove(BAD_KEY)
            prefs[KEY] = json.encodeToString(next)
        }
    }

    companion object {
        /** On a store of the caller's own: tests hand in a file of their own. */
        internal fun on(ds: DataStore<Preferences>) = PrefsStore(ds)

        private val KEY = stringPreferencesKey("prefs_json")

        /** A blob this build couldn't read, kept as it was for a build that can. */
        private val BAD_KEY = stringPreferencesKey("prefs_json_bad")

        private val json = Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
            // An explicit null (from a field that was nullable once) reads as the default.
            coerceInputValues = true
        }

        /** The stored blob; if it doesn't read as a whole, every field of it that still does. */
        internal fun read(raw: String): Prefs? =
            (runCatching { json.decodeFromString<Prefs>(raw) }.getOrNull() ?: salvage(raw))?.migrated()

        /**
         * Before a write. A blob this build can't read (a field changed type, say) is kept as it was,
         * and the write goes on from every field that still reads: the defaults would replace the
         * key, pins and hidden apps for good.
         */
        private fun readOrKeep(raw: String, prefs: MutablePreferences): Prefs {
            val whole = runCatching { json.decodeFromString<Prefs>(raw) }
            whole.exceptionOrNull()?.let { e ->
                prefs[BAD_KEY] = raw
                Telemetry.warn("prefs.decode_failed", null, mapOf("type" to e.javaClass.simpleName))
            }
            return (whole.getOrNull() ?: salvage(raw) ?: Prefs()).migrated()
        }

        /** Each top-level field that reads on its own; the others take their defaults. Null if it isn't JSON. */
        private fun salvage(raw: String): Prefs? = runCatching {
            val readable = json.parseToJsonElement(raw).jsonObject.filter { (name, value) ->
                runCatching { json.decodeFromJsonElement(Prefs.serializer(), JsonObject(mapOf(name to value))) }.isSuccess
            }
            json.decodeFromJsonElement(Prefs.serializer(), JsonObject(readable))
        }.getOrNull()
    }
}
