package com.folio.launcher.data

import android.net.Uri
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class PrintState(
    val photo: ImageBitmap? = null,
    val accent: Color? = null,
    val busy: Boolean = false,
    val caption: String = "",
    val hasPrevious: Boolean = false,
)

/** The print files [PrintController] changes: [WallpaperRepository] on the phone, a fake in tests. */
interface PrintFiles {
    fun exists(): Boolean

    fun prevExists(): Boolean

    suspend fun load(): WallpaperRepository.LoadedWallpaper?

    /**
     * A Bing print that isn't one of [avoid], now the current print. [avoid] holds Bing identities
     * newest first: the current and previous prints, then the ones before. Empty means no print
     * yet, so today's.
     */
    suspend fun importBing(avoid: Set<String>): WallpaperRepository.BingShot?

    suspend fun importFromUri(uri: Uri): Boolean

    suspend fun importSystem(): Boolean

    suspend fun swapWithPrev(): Boolean
}

/**
 * Owns the print on the idle screen: the first Bing, the daily Bing, double-tap for another,
 * Previous print, and local photos. Reads prefs fresh from the store rather than a cached copy.
 */
class PrintController(
    private val scope: CoroutineScope,
    private val repo: PrintFiles,
    private val prefsStore: PrefsStore,
    /** Bank index of the quote that suits a new Bing print (with this salt), or null for the daily pick. */
    private val fitQuote: suspend (WallpaperRepository.BingShot, Int) -> Int? = { _, _ -> null },
    private val today: () -> Int = { QuoteBank.todayIndex() },
) {
    private val _state = MutableStateFlow(PrintState())
    val state: StateFlow<PrintState> = _state.asStateFlow()

    private var captionJob: Job? = null

    fun start() {
        scope.launch {
            load()
            ensureFirst()
            ensureTodayNow()
        }
    }

    /** Double-tap, or "Today's Bing print" in onboarding. */
    fun next() {
        if (!claim(caption = "New print…")) return
        scope.launch {
            val prefs = prefsStore.data.first()
            // No print yet (onboarding's button, after the first fetch failed): today's, as the first print is.
            val shot = repo.importBing(avoid = if (repo.exists()) prefs.recentBing() else emptySet())
            if (shot != null) {
                commitBing(shot)
                release(shot.caption)
            } else {
                release("Couldn't reach Bing")
            }
        }
    }

    /** Previous print is a swap: a second tap while one is on its way would only swap back. */
    private var previousPending = false

    fun previous() {
        if (previousPending) return
        previousPending = true
        scope.launch {
            claimWhenFree()
            if (repo.swapWithPrev()) {
                val day = today()
                prefsStore.update {
                    it.copy(
                        bingId = it.bingPrevId,
                        bingPrevId = it.bingId,
                        // A Bing brought back is today's print: the daily fetch mustn't replace it.
                        bingDay = if (it.bingPrevId.isNotEmpty()) day else it.bingDay,
                        quoteFit = it.quotePrevFit,
                        quotePrevFit = it.quoteFit,
                    )
                }
                load()
            }
            release()
            previousPending = false
        }
    }

    /** First unlock after midnight: a new Bing, but only if today's print is a Bing. */
    fun ensureToday() {
        scope.launch { ensureTodayNow() }
    }

    fun setFromUri(uri: Uri) = importLocal { repo.importFromUri(uri) }

    fun useSystem() = importLocal { repo.importSystem() }

    private fun importLocal(import: suspend () -> Boolean) {
        scope.launch {
            claimWhenFree()
            val ok = import()
            if (ok) {
                prefsStore.update {
                    it.copy(
                        wallpaperSet = true,
                        bingPrevId = it.bingId,
                        bingId = "",
                        quotePrevFit = it.quoteFit,
                        quoteFit = -1,
                    )
                }
                load()
            }
            release(if (ok) null else PHOTO_FAILED)
        }
    }

    private suspend fun ensureTodayNow() {
        if (!repo.exists()) {
            ensureFirst()
            return
        }
        val prefs = prefsStore.data.first()
        if (!prefs.isBingPrint) return
        val day = today()
        if (prefs.bingDay < 0) {
            prefsStore.update { it.copy(bingDay = day) }
            return
        }
        if (prefs.bingDay == day || !claim()) return
        val shot = repo.importBing(avoid = prefs.recentBing())
        if (shot != null) {
            commitBing(shot)
            release(shot.caption)
        } else {
            release()
        }
    }

    private suspend fun ensureFirst() {
        if (repo.exists() || !claim()) return
        // Nothing to avoid: the first print is today's, not a random one from the archive.
        val shot = repo.importBing(avoid = emptySet())
        if (shot != null) {
            val day = today()
            val fit = fitQuote(shot, prefsStore.data.first().quoteSalt) ?: -1
            prefsStore.update {
                it.copy(
                    wallpaperSet = true,
                    bingId = shot.id,
                    bingDay = day,
                    quoteFit = fit,
                    recentBingIds = it.recentWith(shot.id),
                )
            }
            load()
        }
        release()
    }

    /** The quote is fitted before the print is committed, so the two change together. */
    private suspend fun commitBing(shot: WallpaperRepository.BingShot) {
        val day = today()
        val salt = prefsStore.data.first().quoteSalt + 1
        val fit = fitQuote(shot, salt) ?: -1
        prefsStore.update {
            it.copy(
                wallpaperSet = true,
                bingPrevId = it.bingId,
                bingId = shot.id,
                bingDay = day,
                recentBingIds = it.recentWith(shot.id),
                quoteSalt = salt,
                quotePrevFit = it.quoteFit,
                quoteFit = fit,
            )
        }
        load()
    }

    /** What a new pick stays away from, newest first: the current and previous prints, then older ones. */
    private fun Prefs.recentBing(): Set<String> = (listOf(bingId, bingPrevId) + recentBingIds).toSet()

    /** The last Bing prints with [id] in front, as many as a pick stays away from. */
    private fun Prefs.recentWith(id: String): List<String> =
        (listOf(id) + recentBingIds).distinct().take(BingClient.RECENT_PRINTS)

    private suspend fun load() {
        val loaded = repo.load()
        val hasPrevious = repo.prevExists()
        if (loaded == null) {
            _state.update { it.copy(photo = null, accent = null, hasPrevious = hasPrevious) }
            return
        }
        _state.update {
            it.copy(photo = loaded.photo, accent = argb(loaded.accent), hasPrevious = hasPrevious)
        }
        prefsStore.update { if (it.accent == loaded.accent) it else it.copy(accent = loaded.accent) }
    }

    /** Main-thread only. False if another print change is already running. */
    private fun claim(caption: String? = null): Boolean {
        if (_state.value.busy) return false
        captionJob?.cancel()
        // A title still showing names the print this change replaces.
        _state.update { it.copy(busy = true, caption = caption ?: "") }
        return true
    }

    /** A picked photo or Previous print waits out a change already running (often the quiet daily Bing). */
    private suspend fun claimWhenFree() {
        while (!claim()) _state.first { !it.busy }
    }

    private fun release(caption: String? = null) {
        _state.update { it.copy(busy = false, caption = caption ?: if (it.caption == "New print…") "" else it.caption) }
        if (caption.isNullOrEmpty()) return
        captionJob = scope.launch {
            delay(CAPTION_MS)
            _state.update { if (it.caption == caption) it.copy(caption = "") else it }
        }
    }

    companion object {
        private const val CAPTION_MS = 2_000L

        /** The caption for a picked photo (or the current wallpaper) that couldn't be read. */
        const val PHOTO_FAILED = "Couldn't open that photo"

        fun argb(color: Int): Color = Color(color.toLong() and 0xFFFFFFFFL)
    }
}
