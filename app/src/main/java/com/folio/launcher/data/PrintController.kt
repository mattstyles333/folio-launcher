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
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

data class PrintState(
    val photo: ImageBitmap? = null,
    val accent: Color? = null,
    val busy: Boolean = false,
    val caption: String = "",
    val hasPrevious: Boolean = false,
    /** Settings › Your photos: an import under way, or how the last one went, until the print next changes. */
    val rollNote: String = "",
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

    /**
     * Your photos: the photo at [uri], fitted to the print as [importFromUri] fits one, kept in
     * Folio's own storage. Its name there, or null if it can't be read.
     */
    suspend fun addToRoll(uri: Uri): String?

    /** The roll's photo [name], now the current print (the current one becomes the previous). */
    suspend fun showFromRoll(name: String): Boolean

    /** Deletes every roll photo but [keep]. */
    suspend fun pruneRoll(keep: Collection<String>)
}

/**
 * Owns the print on the idle screen: the first Bing, the daily Bing, double-tap for another,
 * Previous print, local photos, and Your photos (the user's own, one a day). Reads prefs fresh
 * from the store rather than a cached copy.
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

    /** The roll's files change under this: an import, and the tidy that drops what the roll no longer names. */
    private val rollLock = Mutex()

    fun start() {
        scope.launch {
            load()
            ensureFirst()
            ensureTodayNow()
            // An import a kill cut short leaves photos the roll doesn't name.
            tidyRoll()
        }
    }

    /**
     * Double-tap, or "Today's Bing print" in onboarding: the next of your photos while the print is
     * one of them, else another Bing.
     */
    fun next() = nextPrint(fromRoll = true)

    /** Settings › Bing print: another Bing, leaving your photos (Previous print still goes back to one). */
    fun nextBing() = nextPrint(fromRoll = false)

    private fun nextPrint(fromRoll: Boolean) {
        if (!claim(caption = "New print…")) return
        scope.launch {
            val prefs = prefsStore.data.first()
            val at = if (fromRoll) rollNext(prefs.rollAt, prefs.rollFiles.size) else null
            if (at != null) {
                // Nothing is on its way: the photo is already on the phone.
                _state.update { it.copy(caption = "") }
                release(if (showRoll(prefs, at)) null else PHOTO_FAILED)
                return@launch
            }
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
                        // So is one of your photos, and the roll goes on from it.
                        rollAt = it.rollPrevAt,
                        rollPrevAt = it.rollAt,
                        rollDay = if (it.rollPrevAt >= 0) day else it.rollDay,
                    )
                }
                load()
            }
            release()
            previousPending = false
        }
    }

    /** First unlock after midnight: a new Bing if today's print is a Bing, the next of your photos if it's one. */
    fun ensureToday() {
        scope.launch { ensureTodayNow() }
    }

    fun setFromUri(uri: Uri) = importLocal { repo.importFromUri(uri) }

    fun useSystem() = importLocal { repo.importSystem() }

    /**
     * Settings › Your photos, in the order picked: each one fitted to the print and kept in Folio's
     * own storage (a picker URI lasts only as long as its grant), the first now the print. One that
     * can't be read is skipped and counted. Two or more are a roll, replacing the one before; a
     * single photo is a print of its own, as Choose photo makes.
     */
    fun setRoll(uris: List<Uri>) {
        if (uris.isEmpty()) return
        scope.launch {
            claimWhenFree()
            // The system picker stops at ROLL_MAX; the document picker it falls back to doesn't.
            rollLock.withLock { importRoll(uris.take(ROLL_MAX)) }
            release()
        }
    }

    private suspend fun importRoll(picked: List<Uri>) {
        val names = picked.mapIndexedNotNull { i, uri ->
            _state.update { it.copy(rollNote = "Adding ${i + 1} of ${picked.size}…") }
            repo.addToRoll(uri)
        }
        val shown = names.isNotEmpty() && repo.showFromRoll(names.first())
        if (shown) {
            val day = today()
            prefsStore.update {
                val photo = it.copy(
                    wallpaperSet = true,
                    bingPrevId = it.bingId,
                    bingId = "",
                    quotePrevFit = it.quoteFit,
                    quoteFit = -1,
                )
                if (names.size > 1) {
                    photo.copy(rollFiles = names, rollAt = 0, rollPrevAt = -1, rollDay = day)
                } else {
                    photo.copy(rollFiles = emptyList(), rollAt = -1, rollPrevAt = -1)
                }
            }
        }
        // What the roll doesn't name now: the roll this one replaced, or these photos if they aren't one after all.
        repo.pruneRoll(prefsStore.data.first().rollFiles)
        if (shown) load()
        _state.update { it.copy(rollNote = rollNote(added = if (shown) names.size else 0, picked = picked.size)) }
    }

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
                        // A photo of its own replaces your photos.
                        rollFiles = emptyList(),
                        rollAt = -1,
                        rollPrevAt = -1,
                    )
                }
                tidyRoll()
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
        if (prefs.isRollPrint) {
            rollOn(prefs)
            return
        }
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

    /** The daily change on your photos: the next of them, offline. */
    private suspend fun rollOn(prefs: Prefs) {
        val day = today()
        if (prefs.rollDay == day || !claim()) return
        // Read again under the claim: a double-tap may have moved the roll on meanwhile.
        val now = prefsStore.data.first()
        val at = rollNext(now.rollAt, now.rollFiles.size)
        if (now.rollDay != day && at != null) showRoll(now, at)
        release()
    }

    /** The roll's photo [at], now the print. Its quote is the plain daily pick: Jev fits only Bing's. */
    private suspend fun showRoll(prefs: Prefs, at: Int): Boolean {
        if (!repo.showFromRoll(prefs.rollFiles[at])) return false
        val day = today()
        prefsStore.update {
            it.copy(
                bingPrevId = it.bingId,
                bingId = "",
                quotePrevFit = it.quoteFit,
                quoteFit = -1,
                rollAt = at,
                rollPrevAt = it.rollAt,
                rollDay = day,
            )
        }
        load()
        return true
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
                ).offRoll(prevAt = it.rollPrevAt)
            }
            tidyRoll()
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
            ).offRoll()
        }
        tidyRoll()
        load()
    }

    /** What a new pick stays away from, newest first: the current and previous prints, then older ones. */
    private fun Prefs.recentBing(): Set<String> = (listOf(bingId, bingPrevId) + recentBingIds).toSet()

    /** The last Bing prints with [id] in front, as many as a pick stays away from. */
    private fun Prefs.recentWith(id: String): List<String> =
        (listOf(id) + recentBingIds).distinct().take(BingClient.RECENT_PRINTS)

    /**
     * A print not from your photos is now current, and the one at [prevAt] of the roll (if any) the
     * previous: the roll stays while Previous print can still go back to it, and is forgotten once
     * it can't.
     */
    private fun Prefs.offRoll(prevAt: Int = rollAt): Prefs =
        if (prevAt < 0) {
            copy(rollFiles = emptyList(), rollAt = -1, rollPrevAt = -1)
        } else {
            copy(rollAt = -1, rollPrevAt = prevAt)
        }

    /** Deletes the photos the roll no longer names: a roll replaced or forgotten, or an import cut short. */
    private suspend fun tidyRoll() {
        rollLock.withLock { repo.pruneRoll(prefsStore.data.first().rollFiles) }
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
        // A title still showing names the print this change replaces; a note on the last import is done with.
        _state.update { it.copy(busy = true, caption = caption ?: "", rollNote = "") }
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

        /** Your photos: the most one roll holds, and the most the picker lets through. */
        const val ROLL_MAX = 30

        fun argb(color: Int): Color = Color(color.toLong() and 0xFFFFFFFFL)

        /** The roll's photo after [at] of [size], round again after the last; null unless the print is one of 2+. */
        internal fun rollNext(at: Int, size: Int): Int? =
            if (size > 1 && at in 0 until size) (at + 1) % size else null

        /** What Settings › Your photos says after an import: how many made it in, and how many couldn't be read. */
        internal fun rollNote(added: Int, picked: Int): String {
            val unread = picked - added
            return when {
                added <= 0 -> if (picked == 1) PHOTO_FAILED else "Couldn't open those photos"
                unread > 0 -> "Added ${photos(added)}, $unread couldn't be read"
                else -> "Added ${photos(added)}"
            }
        }

        private fun photos(n: Int): String = if (n == 1) "1 photo" else "$n photos"
    }
}
