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

/**
 * Owns the print on the idle screen: the first Bing, the daily Bing, double-tap for another,
 * Previous print, and local photos. Reads prefs fresh from the store rather than a cached copy.
 */
class PrintController(
    private val scope: CoroutineScope,
    private val repo: WallpaperRepository,
    private val prefsStore: PrefsStore,
    /** Marks onboarding steps done once a print is in place. */
    private val finishOnboarding: (Prefs) -> Prefs,
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

    /** Double-tap, or "Use Bing" in onboarding. */
    fun next() {
        if (!claim(caption = "New print…")) return
        scope.launch {
            val prefs = prefsStore.data.first()
            val shot = repo.importBing(avoid = setOf(prefs.bingId, prefs.bingPrevId))
            if (shot != null) {
                commitBing(shot)
                release(shot.caption)
            } else {
                release("Couldn't reach Bing")
            }
        }
    }

    fun previous() {
        if (!claim()) return
        scope.launch {
            if (repo.swapWithPrev()) {
                prefsStore.update { it.copy(bingId = it.bingPrevId, bingPrevId = it.bingId) }
                load()
            }
            release()
        }
    }

    /** First unlock after midnight: a new Bing, but only if today's print is a Bing. */
    fun ensureToday() {
        scope.launch { ensureTodayNow() }
    }

    fun setFromUri(uri: Uri) = importLocal { repo.importFromUri(uri) }

    fun useSystem() = importLocal { repo.importSystem() }

    private fun importLocal(import: suspend () -> Boolean) {
        if (!claim()) return
        scope.launch {
            if (import()) {
                prefsStore.update {
                    finishOnboarding(it.copy(wallpaperSet = true, bingPrevId = it.bingId, bingId = ""))
                }
                load()
            }
            release()
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
        val shot = repo.importBing(avoid = setOf(prefs.bingId, prefs.bingPrevId))
        if (shot != null) {
            commitBing(shot)
            release(shot.caption)
        } else {
            release()
        }
    }

    private suspend fun ensureFirst() {
        if (repo.exists() || !claim()) return
        val shot = repo.importBing(avoid = emptySet())
        if (shot != null) {
            val day = today()
            prefsStore.update {
                finishOnboarding(it.copy(wallpaperSet = true, bingId = shot.id, bingDay = day))
            }
            load()
        }
        release()
    }

    private suspend fun commitBing(shot: WallpaperRepository.BingShot) {
        val day = today()
        prefsStore.update {
            finishOnboarding(
                it.copy(
                    wallpaperSet = true,
                    bingPrevId = it.bingId,
                    bingId = shot.id,
                    bingDay = day,
                    quoteSalt = it.quoteSalt + 1,
                ),
            )
        }
        load()
    }

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
        _state.update { it.copy(busy = true, caption = caption ?: it.caption) }
        return true
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

        fun argb(color: Int): Color = Color(color.toLong() and 0xFFFFFFFFL)
    }
}
