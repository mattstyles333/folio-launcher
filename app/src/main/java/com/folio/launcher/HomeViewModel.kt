package com.folio.launcher

import android.app.Application
import android.content.Context
import android.graphics.Rect
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import com.folio.launcher.data.AiApps
import com.folio.launcher.data.AppIndex
import com.folio.launcher.data.DefaultApps
import com.folio.launcher.data.FolioBuzz
import com.folio.launcher.data.GoogleSearch
import com.folio.launcher.data.HomeUiState
import com.folio.launcher.data.LaunchableApp
import com.folio.launcher.data.OnboardingStep
import com.folio.launcher.data.Prefs
import com.folio.launcher.data.PrintController
import com.folio.launcher.data.PrintState
import com.folio.launcher.data.QuoteBank
import com.folio.launcher.data.RailSlot
import com.folio.launcher.data.Ranking
import com.folio.launcher.data.RecentItem
import com.folio.launcher.data.RingerController
import com.folio.launcher.data.RingerVisual
import com.folio.launcher.data.SlotPref
import com.folio.launcher.data.UsageData
import com.folio.launcher.onboarding.AccessGrants
import com.folio.launcher.onboarding.AccessScreen
import com.folio.launcher.onboarding.AccessWalk
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

class HomeViewModel(
    application: Application,
    private val saved: SavedStateHandle,
) : AndroidViewModel(application) {
    private val app = application as FolioApp
    private val prefsStore = app.prefsStore
    private val usageStore = app.usageStore
    private val appsRepo = app.appRepository
    private val ringer = app.ringer

    private val print = PrintController(
        scope = viewModelScope,
        repo = app.wallpaper,
        prefsStore = prefsStore,
        finishOnboarding = ::finishOnboarding,
    )

    private val extra = MutableStateFlow(
        ExtraState(
            isDefaultHome = RingerController.isDefaultHome(app),
            grants = grants(),
            systemWallpaperReadable = app.wallpaper.systemWallpaperReadable(),
        ),
    )

    private val railLock = Mutex()

    private val _idleTick = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val idleTick = _idleTick.asSharedFlow()

    private val _accessRequests = Channel<AccessScreen>(Channel.BUFFERED)

    /** Settings screens the activity should open; it reports back with [onAccessReturned]. */
    val accessRequests: Flow<AccessScreen> = _accessRequests.receiveAsFlow()

    /** Screen currently open in Settings, and whether it's part of the onboarding walk. */
    private var pendingAccess: AccessScreen?
        get() = saved.get<String>(KEY_PENDING_ACCESS)?.let { name -> AccessScreen.entries.find { it.name == name } }
        set(value) = saved.set(KEY_PENDING_ACCESS, value?.name)
    private var walking: Boolean
        get() = saved.get<Boolean>(KEY_WALKING) ?: false
        set(value) = saved.set(KEY_WALKING, value)

    val state: StateFlow<HomeUiState> = combine(
        prefsStore.data,
        usageStore.data,
        appsRepo.apps,
        combine(ringer.visual, ringer.needsDndAccess) { visual, dnd -> visual to dnd },
        combine(print.state, extra) { p, e -> p to e },
    ) { prefs, usage, apps, (visual, needsDnd), (printState, extraState) ->
        buildState(prefs, usage, apps, visual, needsDnd, printState, extraState)
    }
        .flowOn(Dispatchers.Default)
        .stateIn(
            viewModelScope,
            SharingStarted.Eagerly,
            HomeUiState(versionName = BuildConfig.VERSION_NAME),
        )

    init {
        appsRepo.start()
        ringer.start()
        print.start()
        viewModelScope.launch {
            combine(prefsStore.data, usageStore.data, appsRepo.apps) { p, u, a -> Triple(p, u, a) }
                .collect { (prefs, usage, apps) ->
                    noteFirstSeen(apps, prefs)
                    recomputeRail(prefs, usage, apps, force = false)
                }
        }
        viewModelScope.launch {
            combine(app.signals.charge, app.signals.musicPlaying) { charge, playing -> charge to playing }
                .collect { (charge, playing) ->
                    extra.value = extra.value.copy(
                        charging = charge.charging,
                        charge = charge.fraction,
                        musicPlaying = playing,
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

    fun playPause(host: Context) {
        app.signals.playPause(host)
    }

    fun openPlayer(host: Context) {
        app.signals.openSession(host)
    }

    fun hideApp(target: LaunchableApp) {
        viewModelScope.launch {
            prefsStore.update { prefs ->
                prefs.copy(
                    hiddenPackages = (prefs.hiddenPackages + target.packageName).distinct(),
                    slots = prefs.slots.map { slot ->
                        if (slot.packageName == target.packageName) SlotPref() else slot
                    },
                )
            }
        }
    }

    fun unhideApp(app: LaunchableApp) {
        viewModelScope.launch {
            prefsStore.update {
                it.copy(hiddenPackages = it.hiddenPackages.filterNot { pkg -> pkg == app.packageName })
            }
        }
    }

    fun requestIdle() {
        _idleTick.tryEmit(Unit)
    }

    private fun grants() = AccessGrants(
        dnd = ringer.hasPolicyAccess(),
        usage = RingerController.hasUsageAccess(app),
        media = app.signals.hasNowPlayingAccess(),
    )

    fun refreshSystemState() {
        ringer.refresh()
        val grants = grants()
        val wasGranted = extra.value.grants.usage
        extra.value = extra.value.copy(
            isDefaultHome = RingerController.isDefaultHome(app),
            grants = grants,
            systemWallpaperReadable = app.wallpaper.systemWallpaperReadable(),
        )
        app.signals.refresh()
        viewModelScope.launch {
            val stats = if (grants.usage) {
                withContext(Dispatchers.IO) { RingerController.queryUsageTimestamps(app) }
            } else {
                emptyMap()
            }
            extra.value = extra.value.copy(usageTimestamps = stats)
            recomputeRail(
                prefsStore.data.first(),
                usageStore.data.first(),
                appsRepo.apps.value,
                force = grants.usage && !wasGranted,
            )
            val prefs = prefsStore.data.first()
            if (grants.media && !prefs.mediaHintDismissed) {
                prefsStore.update { it.copy(mediaHintDismissed = true) }
            }
            if (!prefs.accessOffered && grants.all) {
                prefsStore.update { finishOnboarding(it.copy(accessOffered = true)) }
            }
        }
    }

    /** Onboarding "Allow": walk DND → usage → notification access, skipping what's granted. */
    fun startAccessWalk() {
        val next = AccessWalk.next(null, grants())
        if (next == null) {
            skipAccess()
            return
        }
        walking = true
        requestAccess(next)
    }

    /** A single screen, from a hint. */
    fun openAccess(screen: AccessScreen) {
        walking = false
        requestAccess(screen)
    }

    private fun requestAccess(screen: AccessScreen) {
        pendingAccess = screen
        _accessRequests.trySend(screen)
    }

    fun onAccessReturned() {
        val returned = pendingAccess
        pendingAccess = null
        if (returned == AccessScreen.Dnd) onDndAccessReturned()
        refreshSystemState()
        if (returned == AccessScreen.Media) {
            walking = false
            skipAccess()
            return
        }
        if (!walking || returned == null) return
        val next = AccessWalk.next(returned, grants())
        if (next == null) {
            walking = false
            skipAccess()
        } else {
            requestAccess(next)
        }
    }

    fun consumeDndRequest() = ringer.consumeDndRequest()

    private fun onDndAccessReturned() {
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
            val hint = visual == RingerVisual.Silent && !ringer.hasPolicyAccess()
            prefsStore.update { it.copy(silentHint = hint) }
        }
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

    fun openAppInfo(app: LaunchableApp, bounds: Rect? = null): Boolean {
        return appsRepo.openAppInfo(app, bounds)
    }

    fun pin(slotIndex: Int, app: LaunchableApp) {
        viewModelScope.launch {
            prefsStore.update { prefs ->
                val slots = Ranking.railSlots(prefs.slots).map { slot ->
                    if (slot.packageName == app.packageName) SlotPref() else slot
                }.toMutableList()
                slots[slotIndex] = SlotPref(app.packageName, app.activityName, pinned = true)
                prefs.copy(slots = slots)
            }
        }
    }

    fun reorderRail(from: Int, to: Int) {
        val last = Ranking.RAIL_SLOTS - 1
        if (from == to || from !in 0..last || to !in 0..last) return
        viewModelScope.launch {
            prefsStore.update { prefs ->
                val slots = Ranking.railSlots(prefs.slots).toMutableList()
                val item = slots.removeAt(from)
                slots.add(to, item)
                prefs.copy(slots = slots, lastRailDay = today())
            }
        }
    }

    fun resetPins() {
        viewModelScope.launch {
            prefsStore.update { it.copy(slots = Ranking.railSlots(emptyList()), lastRailDay = -1) }
            recomputeRail(prefsStore.data.first(), usageStore.data.first(), appsRepo.apps.value, force = true)
        }
    }

    fun setShowClock(show: Boolean) {
        viewModelScope.launch { prefsStore.update { it.copy(showClock = show) } }
    }

    fun cycleAi() {
        viewModelScope.launch {
            val installed = AiApps.installed(appsRepo.apps.value)
            prefsStore.update { prefs ->
                prefs.copy(aiPackage = AiApps.cyclePackage(prefs.aiPackage, installed))
            }
        }
    }

    fun openAi(host: Context, prompt: String) {
        val packages = appsRepo.apps.value.map { it.packageName }.toSet()
        val kind = AiApps.resolve(state.value.aiPackage, AiApps.installedFrom(packages)) ?: return
        val pkg = AiApps.matchedPackage(kind, packages) ?: return
        if (AiApps.open(host, kind, prompt, pkg)) {
            viewModelScope.launch { usageStore.record(pkg) }
        }
    }

    fun openGoogleSearch(host: Context) {
        if (GoogleSearch.open(host)) {
            viewModelScope.launch { usageStore.record(GoogleSearch.PACKAGE) }
        }
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

    fun setWallpaper(uri: Uri) = print.setFromUri(uri)

    fun useSystemWallpaper() = print.useSystem()

    fun nextBingPrint() = print.next()

    fun previousPrint() = print.previous()

    fun ensureTodaysPrint() = print.ensureToday()

    private fun finishOnboarding(prefs: Prefs): Prefs {
        val roleDone = prefs.skippedRole || RingerController.isDefaultHome(app)
        val wallDone = prefs.wallpaperSet || prefs.skippedWallpaper
        val accessDone = prefs.accessOffered || extra.value.grants.all
        return prefs.copy(
            accessOffered = if (accessDone) true else prefs.accessOffered,
            onboardingComplete = roleDone && wallDone && accessDone,
        )
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

    private suspend fun recomputeRail(
        prefs: Prefs,
        usage: UsageData,
        apps: List<LaunchableApp>,
        force: Boolean,
    ) {
        if (apps.isEmpty() || !railLock.tryLock()) return
        try {
            val today = today()
            val index = AppIndex(apps)
            val currentSlots = Ranking.railSlots(prefs.slots)
            val stale = currentSlots.any { slot ->
                slot.packageName != null && (
                    index.find(slot.packageName, slot.activityName) == null ||
                        slot.packageName in prefs.hiddenPackages
                    )
            }
            if (!force && !stale && prefs.lastRailDay == today && currentSlots.all { it.packageName != null }) return
            val ranked = withContext(Dispatchers.Default) {
                Ranking.rankForRail(
                    apps,
                    Ranking.combinedLaunches(usage.launches, extra.value.usageTimestamps),
                    prefs.firstSeen,
                    DefaultApps.pick(apps, app.packageManager),
                    hidden = prefs.hiddenPackages.toSet(),
                )
            }
            val next = Ranking.fillRail(
                current = currentSlots,
                ranked = ranked.map { SlotPref(it.packageName, it.activityName) },
                resolveKey = { slot -> slot.packageName?.let { index.find(it, slot.activityName)?.key } },
            )
            if (next != currentSlots || prefs.lastRailDay != today) {
                prefsStore.update { it.copy(slots = next, lastRailDay = today) }
            }
        } finally {
            railLock.unlock()
        }
    }

    private fun buildState(
        prefs: Prefs,
        usage: UsageData,
        apps: List<LaunchableApp>,
        visual: RingerVisual,
        needsDnd: Boolean,
        printState: PrintState,
        extraState: ExtraState,
    ): HomeUiState {
        val index = AppIndex(apps)
        val hidden = prefs.hiddenPackages.toSet()
        val launches = Ranking.combinedLaunches(usage.launches, extraState.usageTimestamps)

        val recents = launches.entries
            .asSequence()
            .filter { it.key !in prefs.dismissedRecents && it.value.isNotEmpty() }
            .map { (pkg, times) -> pkg to times.max() }
            .sortedByDescending { it.second }
            .mapNotNull { (pkg, t) ->
                val item = index.find(pkg, null) ?: return@mapNotNull null
                if (item.isHome || item.packageName in hidden) return@mapNotNull null
                RecentItem(item, t)
            }
            .take(20)
            .toList()

        val rail = Ranking.railSlots(prefs.slots).map { slot ->
            RailSlot(
                app = slot.packageName?.let { index.find(it, slot.activityName) },
                pinned = slot.pinned,
            )
        }

        val quote = QuoteBank.pick(
            QuoteBank.load(app),
            QuoteBank.todayIndex(),
            prefs.quoteSalt,
        )
        val packages = apps.mapTo(HashSet()) { it.packageName }
        val aiInstalled = AiApps.installedFrom(packages)
        val ai = AiApps.resolve(prefs.aiPackage, aiInstalled)
        val aiPackage = ai?.let { AiApps.matchedPackage(it, packages) }.orEmpty()

        val grants = extraState.grants
        val roleDone = prefs.skippedRole || extraState.isDefaultHome
        val wallDone = prefs.wallpaperSet || prefs.skippedWallpaper
        val accessDone = prefs.accessOffered || grants.all
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
            mode = visual,
            showClock = prefs.showClock,
            wallpaper = printState.photo,
            accent = printState.accent ?: PrintController.argb(prefs.accent),
            launches = launches,
            onboarding = onboarding,
            silentHint = prefs.silentHint,
            mediaHint = !grants.media && !prefs.mediaHintDismissed,
            needsDndAccess = needsDnd,
            isDefaultHome = extraState.isDefaultHome,
            hasUsageAccess = grants.usage,
            hasDndAccess = grants.dnd,
            systemWallpaperReadable = extraState.systemWallpaperReadable,
            versionName = BuildConfig.VERSION_NAME,
            wallpaperBusy = printState.busy,
            wallpaperCaption = printState.caption,
            hasPreviousPrint = printState.hasPrevious,
            charging = extraState.charging,
            charge = extraState.charge,
            musicPlaying = extraState.musicPlaying,
            hasNowPlayingAccess = grants.media,
            quote = quote?.text.orEmpty(),
            quoteAuthor = quote?.author.orEmpty(),
            hiddenPackages = hidden,
            aiPackage = aiPackage,
            aiLabel = ai?.label.orEmpty(),
            aiInstalled = aiInstalled,
        )
    }

    private data class ExtraState(
        val isDefaultHome: Boolean = false,
        val grants: AccessGrants = AccessGrants(dnd = false, usage = false, media = false),
        val systemWallpaperReadable: Boolean = false,
        val usageTimestamps: Map<String, List<Long>> = emptyMap(),
        val charging: Boolean = false,
        val charge: Float = 0f,
        val musicPlaying: Boolean = false,
    )

    companion object {
        private const val KEY_PENDING_ACCESS = "pending_access"
        private const val KEY_WALKING = "access_walking"

        private fun today(): Int = LocalDate.now(ZoneId.systemDefault()).toEpochDay().toInt()
    }
}
