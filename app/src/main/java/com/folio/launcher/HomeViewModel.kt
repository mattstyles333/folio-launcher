package com.folio.launcher

import android.app.Application
import android.content.Context
import android.content.Intent
import android.graphics.Rect
import android.net.Uri
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.folio.launcher.data.DefaultApps
import com.folio.launcher.data.HomeUiState
import com.folio.launcher.data.LaunchableApp
import com.folio.launcher.data.OnboardingStep
import com.folio.launcher.data.Prefs
import com.folio.launcher.data.FolioBuzz
import com.folio.launcher.data.QuoteBank
import com.folio.launcher.data.RailSlot
import com.folio.launcher.data.Ranking
import com.folio.launcher.data.RecentItem
import com.folio.launcher.data.RingerController
import com.folio.launcher.data.RingerVisual
import com.folio.launcher.data.SlotPref
import com.folio.launcher.data.UsageData
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class HomeViewModel(application: Application) : AndroidViewModel(application) {
    private val app = application as FolioApp
    private val prefsStore = app.prefsStore
    private val usageStore = app.usageStore
    private val appsRepo = app.appRepository
    private val ringer = app.ringer
    private val wallpaperRepo = app.wallpaper

    private val wallpaperBits = MutableStateFlow<WallpaperBits?>(null)
    private val extra = MutableStateFlow(
        ExtraState(
            isDefaultHome = RingerController.isDefaultHome(app),
            hasUsageAccess = RingerController.hasUsageAccess(app),
            hasDndAccess = ringer.hasPolicyAccess(),
            systemWallpaperReadable = wallpaperRepo.systemWallpaperReadable(),
        ),
    )

    private var latestPrefs = Prefs()
    private var latestUsage = UsageData()
    private var latestApps: List<LaunchableApp> = emptyList()
    private var railJobRunning = false

    private val _idleTick = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val idleTick = _idleTick.asSharedFlow()

    val state: StateFlow<HomeUiState> = combine(
        prefsStore.data,
        usageStore.data,
        appsRepo.apps,
        ringer.visual,
        combine(ringer.needsDndAccess, wallpaperBits, extra) { dnd, wall, extraState ->
            Triple(dnd, wall, extraState)
        },
    ) { prefs, usage, apps, visual, triple ->
        val (needsDnd, wall, extraState) = triple
        latestPrefs = prefs
        latestUsage = usage
        latestApps = apps
        buildState(prefs, usage, apps, visual, needsDnd, wall, extraState)
    }.stateIn(
        viewModelScope,
        SharingStarted.Eagerly,
        HomeUiState(versionName = BuildConfig.VERSION_NAME),
    )

    init {
        appsRepo.start()
        ringer.start()
        viewModelScope.launch {
            loadWallpaper()
            ensureFirstPrint()
        }
        viewModelScope.launch {
            combine(prefsStore.data, usageStore.data, appsRepo.apps) { p, u, a -> Triple(p, u, a) }
                .collect { (prefs, usage, apps) ->
                    noteFirstSeen(apps, prefs)
                    recomputeRailIfNeeded(prefs, usage, apps, force = false)
                }
        }
        viewModelScope.launch {
            combine(app.signals.charge, app.signals.nowPlaying) { charge, now -> charge to now }
                .collect { (charge, now) ->
                    extra.value = extra.value.copy(
                        charging = charge.charging,
                        charge = charge.fraction,
                        nowPlaying = now.line,
                        musicTitle = now.title,
                        musicArtist = now.artist,
                        musicPlaying = now.playing,
                        musicArt = now.art?.asImageBitmap(),
                        musicPositionMs = now.positionMs,
                        musicDurationMs = now.durationMs,
                        nowPlayingPackage = now.packageName,
                        hasNowPlayingAccess = app.signals.hasNowPlayingAccess(),
                    )
                }
        }
    }

    override fun onCleared() {
        appsRepo.stop()
        ringer.stop()
        super.onCleared()
    }

    fun skipTrack() {
        app.signals.skip()
    }

    fun previousTrack() {
        app.signals.previous()
    }

    fun playPause() {
        app.signals.playPause()
    }

    fun seekTrack(fraction: Float) {
        app.signals.seek(fraction)
    }

    fun openPlayer(host: Context) {
        app.signals.openSession(host)
    }

    fun ensureSpotifyWidget(): Intent? {
        val binder = app.spotifyWidget
        val info = binder.provider() ?: return null
        var id = latestPrefs.spotifyWidgetId
        if (id != 0 && binder.infoFor(id) != null) return null
        if (id != 0) binder.delete(id)
        id = binder.allocate()
        latestPrefs = latestPrefs.copy(spotifyWidgetId = id)
        viewModelScope.launch { prefsStore.update { it.copy(spotifyWidgetId = id) } }
        if (binder.bindIfAllowed(id, info)) return null
        return binder.bindIntent(id, info)
    }

    fun onSpotifyWidgetBindResult(ok: Boolean) {
        val id = latestPrefs.spotifyWidgetId
        if (ok && id != 0 && app.spotifyWidget.infoFor(id) != null) {
            extra.value = extra.value.copy(widgetRev = extra.value.widgetRev + 1)
            return
        }
        if (id != 0) app.spotifyWidget.delete(id)
        latestPrefs = latestPrefs.copy(spotifyWidgetId = 0)
        viewModelScope.launch { prefsStore.update { it.copy(spotifyWidgetId = 0) } }
    }

    fun requestIdle() {
        _idleTick.tryEmit(Unit)
    }

    fun refreshSystemState() {
        ringer.refresh()
        val granted = RingerController.hasUsageAccess(app)
        val wasGranted = extra.value.hasUsageAccess
        extra.value = extra.value.copy(
            isDefaultHome = RingerController.isDefaultHome(app),
            hasUsageAccess = granted,
            hasDndAccess = ringer.hasPolicyAccess(),
            systemWallpaperReadable = wallpaperRepo.systemWallpaperReadable(),
            hasNowPlayingAccess = app.signals.hasNowPlayingAccess(),
        )
        app.signals.refresh()
        viewModelScope.launch {
            val stats = if (granted) {
                withContext(Dispatchers.IO) { RingerController.queryUsageTimestamps(app) }
            } else {
                emptyMap()
            }
            extra.value = extra.value.copy(usageTimestamps = stats)
            recomputeRailIfNeeded(
                latestPrefs,
                latestUsage,
                latestApps,
                force = granted && !wasGranted,
            )
            val media = extra.value.hasNowPlayingAccess
            if (media && !latestPrefs.mediaHintDismissed) {
                prefsStore.update { it.copy(mediaHintDismissed = true) }
            }
            if (!latestPrefs.accessOffered && ringer.hasPolicyAccess() && granted && media) {
                prefsStore.update { finishOnboarding(it.copy(accessOffered = true)) }
            }
        }
    }

    fun consumeDndRequest() = ringer.consumeDndRequest()

    fun onDndAccessReturned() {
        ringer.onDndAccessReturned()
        viewModelScope.launch {
            val granted = ringer.hasPolicyAccess()
            prefsStore.update { it.copy(silentHint = if (granted) false else it.silentHint) }
        }
    }

    fun dismissSilentHint() {
        viewModelScope.launch { prefsStore.update { it.copy(silentHint = false) } }
    }

    fun dismissMediaHint() {
        viewModelScope.launch { prefsStore.update { it.copy(mediaHintDismissed = true) } }
    }

    fun setRinger(visual: RingerVisual) {
        ringer.set(visual)
        if (visual == RingerVisual.Vibrate) FolioBuzz.play(app)
        viewModelScope.launch {
            if (visual == RingerVisual.Silent && !ringer.hasPolicyAccess()) {
                prefsStore.update { it.copy(silentHint = true) }
            } else {
                prefsStore.update { it.copy(silentHint = false) }
            }
        }
    }

    fun cycleRinger() {
        val next = when (state.value.mode) {
            RingerVisual.Sound -> RingerVisual.Vibrate
            RingerVisual.Vibrate -> RingerVisual.Silent
            RingerVisual.Silent -> RingerVisual.Sound
        }
        setRinger(next)
    }

    fun launch(app: LaunchableApp, bounds: Rect? = null): Boolean {
        val ok = appsRepo.launch(app, bounds)
        if (ok) {
            viewModelScope.launch {
                usageStore.record(app.packageName)
                prefsStore.update { it.copy(dismissedRecents = it.dismissedRecents - app.packageName) }
            }
        }
        return ok
    }

    fun dismissRecent(packageName: String) {
        viewModelScope.launch {
            prefsStore.update {
                it.copy(dismissedRecents = it.dismissedRecents + (packageName to System.currentTimeMillis()))
            }
        }
    }

    fun pin(slotIndex: Int, app: LaunchableApp) {
        viewModelScope.launch {
            prefsStore.update { prefs ->
                val slots = prefs.slots.toMutableList()
                while (slots.size < 4) slots += SlotPref()
                for (i in slots.indices) {
                    if (i != slotIndex && slots[i].packageName == app.packageName) {
                        slots[i] = SlotPref()
                    }
                }
                slots[slotIndex] = SlotPref(app.packageName, app.activityName, pinned = true)
                prefs.copy(slots = slots.take(4))
            }
        }
    }

    fun reorderRail(from: Int, to: Int) {
        if (from == to || from !in 0..3 || to !in 0..3) return
        viewModelScope.launch {
            prefsStore.update { prefs ->
                val slots = prefs.slots.toMutableList()
                while (slots.size < 4) slots += SlotPref()
                val item = slots.removeAt(from)
                slots.add(to, item)
                prefs.copy(slots = slots.take(4), lastRailDay = LocalDate.now(ZoneId.systemDefault()).toEpochDay().toInt())
            }
        }
    }

    fun resetPins() {
        viewModelScope.launch {
            prefsStore.update { it.copy(slots = List(4) { SlotPref() }, lastRailDay = -1) }
            recomputeRailIfNeeded(latestPrefs.copy(slots = List(4) { SlotPref() }, lastRailDay = -1), latestUsage, latestApps, force = true)
        }
    }

    fun setShowClock(show: Boolean) {
        viewModelScope.launch { prefsStore.update { it.copy(showClock = show) } }
    }

    fun skipRole() {
        viewModelScope.launch {
            prefsStore.update { finishOnboarding(it.copy(skippedRole = true)) }
        }
    }

    fun skipWallpaper() {
        viewModelScope.launch {
            prefsStore.update { finishOnboarding(it.copy(skippedWallpaper = true)) }
        }
    }

    fun skipAccess() {
        viewModelScope.launch {
            prefsStore.update { finishOnboarding(it.copy(accessOffered = true)) }
        }
    }

    fun setWallpaper(uri: Uri) {
        viewModelScope.launch {
            if (wallpaperRepo.importFromUri(uri)) {
                prefsStore.update { finishOnboarding(it.copy(wallpaperSet = true, bingIndex = -1)) }
                loadWallpaper()
            }
        }
    }

    fun useSystemWallpaper() {
        viewModelScope.launch {
            if (wallpaperRepo.importSystem()) {
                prefsStore.update { finishOnboarding(it.copy(wallpaperSet = true, bingIndex = -1)) }
                loadWallpaper()
            }
        }
    }

    fun nextBingPrint() {
        if (extra.value.wallpaperBusy) return
        viewModelScope.launch {
            extra.value = extra.value.copy(wallpaperBusy = true, wallpaperCaption = "New print…")
            val count = wallpaperRepo.bingCount().let { if (it <= 0) 8 else it }
            val next = if (latestPrefs.bingIndex < 0) 0 else (latestPrefs.bingIndex + 1) % count
            val shot = wallpaperRepo.importBing(next)
            if (shot != null) {
                prefsStore.update {
                    finishOnboarding(
                        it.copy(
                            wallpaperSet = true,
                            bingIndex = shot.index,
                            quoteSalt = it.quoteSalt + 1,
                        ),
                    )
                }
                loadWallpaper()
                showCaption(shot.caption)
            } else {
                showCaption("Couldn't reach Bing")
            }
        }
    }

    private suspend fun showCaption(text: String) {
        extra.value = extra.value.copy(wallpaperBusy = false, wallpaperCaption = text)
        delay(3200)
        if (extra.value.wallpaperCaption == text) {
            extra.value = extra.value.copy(wallpaperCaption = "")
        }
    }

    private fun finishOnboarding(prefs: Prefs): Prefs {
        val roleDone = prefs.skippedRole || RingerController.isDefaultHome(app)
        val wallDone = prefs.wallpaperSet || prefs.skippedWallpaper
        val accessDone = prefs.accessOffered ||
            (ringer.hasPolicyAccess() && extra.value.hasUsageAccess && extra.value.hasNowPlayingAccess)
        return prefs.copy(
            accessOffered = if (accessDone) true else prefs.accessOffered,
            onboardingComplete = roleDone && wallDone && accessDone,
        )
    }

    private suspend fun ensureFirstPrint() {
        if (wallpaperRepo.exists() || extra.value.wallpaperBusy) return
        extra.value = extra.value.copy(wallpaperBusy = true)
        val shot = wallpaperRepo.importBing(0)
        if (shot != null) {
            prefsStore.update {
                finishOnboarding(it.copy(wallpaperSet = true, bingIndex = shot.index))
            }
            loadWallpaper()
        }
        extra.value = extra.value.copy(wallpaperBusy = false)
    }

    private suspend fun loadWallpaper() {
        val dm = app.resources.displayMetrics
        val loaded = wallpaperRepo.load(dm.widthPixels, dm.heightPixels)
        if (loaded == null) {
            wallpaperBits.value = null
            return
        }
        val accent = argb(loaded.accent)
        wallpaperBits.value = WallpaperBits(loaded.photo, loaded.blurred, accent)
        prefsStore.update { it.copy(accent = loaded.accent) }
    }

    private suspend fun noteFirstSeen(apps: List<LaunchableApp>, prefs: Prefs) {
        if (apps.isEmpty()) return
        val known = prefs.firstSeen
        val isInitial = known.isEmpty()
        val now = System.currentTimeMillis()
        val added = HashMap<String, Long>()
        for (item in apps) {
            if (item.packageName !in known && item.packageName !in added) {
                added[item.packageName] = if (isInitial) 0L else now
            }
        }
        if (added.isNotEmpty()) {
            prefsStore.update { it.copy(firstSeen = it.firstSeen + added) }
        }
    }

    private suspend fun recomputeRailIfNeeded(
        prefs: Prefs,
        usage: UsageData,
        apps: List<LaunchableApp>,
        force: Boolean,
    ) {
        if (apps.isEmpty() || railJobRunning) return
        val today = LocalDate.now(ZoneId.systemDefault()).toEpochDay().toInt()
        val currentSlots = (prefs.slots + List(4) { SlotPref() }).take(4)
        val stale = currentSlots.any { slot ->
            slot.packageName != null && appsRepo.find(slot.packageName, slot.activityName, apps) == null
        }
        if (!force && !stale && prefs.lastRailDay == today && currentSlots.all { it.packageName != null }) return
        railJobRunning = true
        try {
            val launches = Ranking.combinedLaunches(usage.launches, extra.value.usageTimestamps)
            val defaults = DefaultApps.pick(apps, app.packageManager)
            val ranked = Ranking.rankForRail(apps, launches, prefs.firstSeen, defaults)
            val used = mutableSetOf<String>()
            val next = MutableList(4) { SlotPref() }
            for (i in 0..3) {
                val slot = currentSlots[i]
                if (slot.pinned && slot.packageName != null) {
                    val still = appsRepo.find(slot.packageName, slot.activityName, apps)
                    if (still != null) {
                        next[i] = slot
                        used += still.key
                    }
                }
            }
            var idx = 0
            for (i in 0..3) {
                if (next[i].pinned && next[i].packageName != null) continue
                while (idx < ranked.size && ranked[idx].key in used) idx++
                val pick = ranked.getOrNull(idx) ?: continue
                next[i] = SlotPref(pick.packageName, pick.activityName, pinned = false)
                used += pick.key
                idx++
            }
            if (next != currentSlots || prefs.lastRailDay != today) {
                prefsStore.update { it.copy(slots = next, lastRailDay = today) }
            }
        } finally {
            railJobRunning = false
        }
    }

    private fun buildState(
        prefs: Prefs,
        usage: UsageData,
        apps: List<LaunchableApp>,
        visual: RingerVisual,
        needsDnd: Boolean,
        wall: WallpaperBits?,
        extraState: ExtraState,
    ): HomeUiState {
        val now = System.currentTimeMillis()
        val launches = Ranking.combinedLaunches(usage.launches, extraState.usageTimestamps)

        val lastUsed = HashMap<String, Long>()
        for ((pkg, times) in launches) {
            val t = times.maxOrNull() ?: continue
            lastUsed[pkg] = t
        }

        val recents = lastUsed.entries
            .asSequence()
            .filter { it.key !in prefs.dismissedRecents }
            .sortedByDescending { it.value }
            .mapNotNull { (pkg, t) ->
                val item = appsRepo.find(pkg, null, apps) ?: return@mapNotNull null
                if (item.isHome) return@mapNotNull null
                RecentItem(item, t)
            }
            .distinctBy { it.app.packageName }
            .take(20)
            .toList()

        val recentsPkgs = recents.map { it.app.packageName }.toSet()
        val reveal = apps
            .filter { !it.isHome && it.packageName !in recentsPkgs }
            .sortedWith(
                compareByDescending<LaunchableApp> {
                    Ranking.score(launches[it.packageName].orEmpty(), now)
                }.thenBy { it.label.lowercase() },
            )

        val rail = (prefs.slots + List(4) { SlotPref() }).take(4).map { slot ->
            RailSlot(
                app = slot.packageName?.let { appsRepo.find(it, slot.activityName, apps) },
                pinned = slot.pinned,
            )
        }

        val quote = QuoteBank.pick(
            QuoteBank.load(app),
            QuoteBank.todayIndex(),
            prefs.quoteSalt,
        )

        val roleDone = prefs.skippedRole || extraState.isDefaultHome
        val wallDone = prefs.wallpaperSet || prefs.skippedWallpaper
        val accessDone = prefs.accessOffered ||
            (extraState.hasDndAccess && extraState.hasUsageAccess && extraState.hasNowPlayingAccess)
        val onboarding = when {
            !roleDone -> OnboardingStep.Role
            !wallDone -> OnboardingStep.Wallpaper
            !accessDone -> OnboardingStep.Access
            else -> null
        }

        return HomeUiState(
            apps = apps,
            rail = rail,
            recents = recents,
            reveal = reveal,
            mode = visual,
            showClock = prefs.showClock,
            wallpaper = wall?.photo,
            blurredWallpaper = wall?.blurred,
            accent = wall?.accent ?: argb(prefs.accent),
            launches = launches,
            onboarding = onboarding,
            silentHint = prefs.silentHint,
            mediaHint = !extraState.hasNowPlayingAccess && !prefs.mediaHintDismissed,
            needsDndAccess = needsDnd,
            isDefaultHome = extraState.isDefaultHome,
            hasUsageAccess = extraState.hasUsageAccess,
            hasDndAccess = extraState.hasDndAccess,
            systemWallpaperReadable = extraState.systemWallpaperReadable,
            versionName = BuildConfig.VERSION_NAME,
            wallpaperBusy = extraState.wallpaperBusy,
            wallpaperCaption = extraState.wallpaperCaption,
            charging = extraState.charging,
            charge = extraState.charge,
            nowPlaying = extraState.nowPlaying,
            musicTitle = extraState.musicTitle,
            musicArtist = extraState.musicArtist,
            musicPlaying = extraState.musicPlaying,
            musicArt = extraState.musicArt,
            musicPositionMs = extraState.musicPositionMs,
            musicDurationMs = extraState.musicDurationMs,
            hasNowPlayingAccess = extraState.hasNowPlayingAccess,
            quote = quote?.text.orEmpty(),
            quoteAuthor = quote?.author.orEmpty(),
            spotifyWidgetId = prefs.spotifyWidgetId.takeIf { app.spotifyWidget.infoFor(it) != null } ?: 0,
            spotifyWidgetAvailable = app.spotifyWidget.provider() != null,
        )
    }

    private data class WallpaperBits(
        val photo: ImageBitmap,
        val blurred: ImageBitmap,
        val accent: Color,
    )

    private data class ExtraState(
        val isDefaultHome: Boolean = false,
        val hasUsageAccess: Boolean = false,
        val hasDndAccess: Boolean = false,
        val systemWallpaperReadable: Boolean = false,
        val usageTimestamps: Map<String, List<Long>> = emptyMap(),
        val wallpaperBusy: Boolean = false,
        val wallpaperCaption: String = "",
        val charging: Boolean = false,
        val charge: Float = 0f,
        val nowPlaying: String = "",
        val musicTitle: String = "",
        val musicArtist: String = "",
        val musicPlaying: Boolean = false,
        val musicArt: ImageBitmap? = null,
        val musicPositionMs: Long = 0L,
        val musicDurationMs: Long = 0L,
        val nowPlayingPackage: String = "",
        val hasNowPlayingAccess: Boolean = false,
        val widgetRev: Int = 0,
    )

    companion object {
        fun argb(color: Int): Color = Color(color.toLong() and 0xFFFFFFFFL)
    }
}
