package com.folio.launcher

import android.app.Application
import android.content.Context
import android.database.ContentObserver
import android.graphics.Rect
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import com.folio.launcher.data.AiApps
import com.folio.launcher.data.AppIndex
import com.folio.launcher.data.AskProvider
import com.folio.launcher.data.AskProviders
import com.folio.launcher.data.BrainStatus
import com.folio.launcher.data.DefaultApps
import com.folio.launcher.data.FolioBuzz
import com.folio.launcher.data.GoogleSearch
import com.folio.launcher.data.HomeUiState
import com.folio.launcher.data.LaunchableApp
import com.folio.launcher.data.OnboardingStep
import com.folio.launcher.data.OpenRouter
import com.folio.launcher.data.Prefs
import com.folio.launcher.data.PrintController
import com.folio.launcher.data.PrintState
import com.folio.launcher.data.QuoteBank
import com.folio.launcher.data.QuoteFit
import com.folio.launcher.data.RailSlot
import com.folio.launcher.data.Ranking
import com.folio.launcher.data.RingerController
import com.folio.launcher.data.RingerVisual
import com.folio.launcher.data.SearchBrain
import com.folio.launcher.data.SearchHint
import com.folio.launcher.data.SearchRoute
import com.folio.launcher.data.SlotPref
import com.folio.launcher.data.Spotify
import com.folio.launcher.data.UsageData
import com.folio.launcher.data.WallpaperRepository
import com.folio.launcher.hermes.HermesAsk
import com.folio.launcher.hermes.HermesAskState
import com.folio.launcher.hermes.HermesAvailability
import com.folio.launcher.hermes.HermesLive
import com.folio.launcher.hermes.Tailnet
import com.folio.hermes.ApprovalChoice
import com.folio.launcher.onboarding.AccessGrants
import com.folio.launcher.onboarding.AccessScreen
import com.folio.launcher.onboarding.AccessWalk
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
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
        fitQuote = ::fitQuote,
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

    private val searchQuery = MutableStateFlow("")
    private val _searchHint = MutableStateFlow(SearchHint())

    /** Jev's route (and DeepSeek's line) for what's typed in search. Empty without a key. */
    val searchHint: StateFlow<SearchHint> = _searchHint.asStateFlow()

    private val hermesAsk = HermesAsk(
        parent = viewModelScope,
        connection = { withContext(Dispatchers.IO) { hermesConnection() } },
        renewedConnection = { withContext(Dispatchers.IO) { hermesConnection(forceRefresh = true) } },
        loadSessionId = { prefsStore.data.first().hermesSessionId },
        saveSessionId = { id -> prefsStore.update { it.copy(hermesSessionId = id) } },
        tailnet = Tailnet.of(app),
    )

    /** The Hermes Ask overlay: exchanges, tool line, approvals. */
    val hermes: StateFlow<HermesAskState> = hermesAsk.state

    private var hermesJob: Job? = null
    private var hermesCheckedAt = 0L

    /** When system usage was last read (elapsedRealtime); null until the first read. */
    private var usageQueriedAt: Long? = null

    /** OpenRouter said too many calls: Jev waits until then (elapsedRealtime). */
    private var jevPausedUntil = 0L

    /** A pasted key being checked. Removing the key, or pasting another, drops it before it's saved. */
    private var keyCheck: Job? = null

    /** Hermes Live signed in or out: check again now, not on the next resume. */
    private val hermesObserver = object : ContentObserver(Handler(Looper.getMainLooper())) {
        override fun onChange(selfChange: Boolean) = refreshHermes(force = true)
    }
    private var hermesObserved = false
    private var hermesVisible = false
    private var started = false

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
            // The ringer is known now. A Sound placeholder would flash "Vibrate" or "Silent"
            // under the clock when the real state lands.
            HomeUiState(versionName = BuildConfig.VERSION_NAME, mode = ringer.visual.value),
        )

    init {
        print.start()
        viewModelScope.launch {
            combine(prefsStore.data, usageStore.data, appsRepo.apps) { p, u, a -> Triple(p, u, a) }
                .collect { (prefs, usage, apps) ->
                    noteFirstSeen(apps, prefs)
                    recomputeRail(prefs, usage, apps, force = false)
                }
        }
        viewModelScope.launch {
            // Hermes Live installed, updated or removed: check again.
            appsRepo.apps.map { list -> list.any { it.packageName == HermesLive.PACKAGE } }
                .distinctUntilChanged()
                .collect { installed ->
                    watchHermes(installed)
                    refreshHermes(force = true)
                }
        }
        viewModelScope.launch {
            // A Silent the system refused (no DND access) lasts only until the ringer moves; its hint
            // goes with it, or it would come back over a later Silent that took.
            ringer.visual.collect { visual ->
                if (visual == RingerVisual.Silent || !prefsStore.data.first().silentHint) return@collect
                prefsStore.update { p ->
                    if (p.silentHint && ringer.visual.value != RingerVisual.Silent) p.copy(silentHint = false) else p
                }
            }
        }
        viewModelScope.launch { searchQuery.collectLatest(::think) }
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

    /**
     * Watch Hermes Live's sign-in only while it's installed: Android throws if Folio registers an
     * observer for a provider that isn't there (that crashed Folio 2.3.0 at launch).
     */
    private fun watchHermes(installed: Boolean) {
        if (installed && !hermesObserved) {
            hermesObserved = HermesLive.observe(app, hermesObserver)
        } else if (!installed && hermesObserved) {
            app.contentResolver.unregisterContentObserver(hermesObserver)
            hermesObserved = false
        }
    }

    // The app list and ringer listeners stay: they are FolioApp's, and another MainActivity may still need them.
    override fun onCleared() {
        if (hermesObserved) app.contentResolver.unregisterContentObserver(hermesObserver)
        hermesAsk.close()
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
        refreshHermes(force = false)
        // Each read makes the system parse every day of events it keeps, and Home resumes all day.
        val now = SystemClock.elapsedRealtime()
        val readUsage = grants.usage && usageQueryDue(now, usageQueriedAt, justGranted = !wasGranted)
        if (readUsage) usageQueriedAt = now
        viewModelScope.launch {
            // Wall clock, as Folio's own launch stamps are: a launch after it is not in this read.
            val readAt = System.currentTimeMillis()
            val stats = when {
                readUsage -> withContext(Dispatchers.IO) { RingerController.queryUsageTimestamps(app, now = readAt) }
                grants.usage -> extra.value.usageTimestamps
                else -> emptyMap()
            }
            extra.value = extra.value.copy(
                usageTimestamps = stats,
                usageReadAt = if (readUsage) readAt else extra.value.usageReadAt,
            )
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
                prefsStore.update { it.copy(accessOffered = true) }
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
        // A paused app (app timer, Focus mode) only shows the system's "App paused": that isn't an open.
        if (ok && !appsRepo.isPaused(app.packageName)) {
            viewModelScope.launch { usageStore.record(app.packageName) }
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

    fun setDiagnostics(on: Boolean) {
        viewModelScope.launch { prefsStore.update { it.copy(diagnostics = on) } }
    }

    private fun askProviders(): List<AskProvider> = AskProviders.list(
        // Signed in counts while the server is out of reach: Ask stays on Hermes and says so.
        hermesReady = extra.value.hermes.signedIn,
        installed = AiApps.installed(appsRepo.apps.value),
    )

    fun cycleAi() {
        viewModelScope.launch {
            val providers = askProviders()
            prefsStore.update { prefs ->
                prefs.copy(aiPackage = AskProviders.cycle(prefs.aiPackage, providers))
            }
        }
    }

    /** Hand [prompt] to the chosen Ask provider. With Hermes chosen this is a Hermes Live call, never another app. */
    fun openAi(host: Context, prompt: String) {
        val packages = appsRepo.apps.value.map { it.packageName }.toSet()
        // state.aiPackage is the resolved provider's key, so this lands on the same provider the UI shows.
        when (val provider = AskProviders.resolve(state.value.aiPackage, askProviders()) ?: return) {
            AskProvider.Hermes -> callHermes(host, prompt)
            is AskProvider.App -> {
                val pkg = AiApps.matchedPackage(provider.kind, packages) ?: return
                if (AiApps.open(host, provider.kind, prompt, pkg) && !appsRepo.isPaused(pkg)) {
                    viewModelScope.launch { usageStore.record(pkg) }
                }
            }
        }
    }

    /** Start a Hermes Live voice call, with [prompt] as its first request when typed. */
    fun callHermes(host: Context, prompt: String) {
        if (HermesLive.startCall(host, prompt)) {
            viewModelScope.launch { usageStore.record(HermesLive.PACKAGE) }
        }
    }

    /** Settings → Hermes: open Hermes Live (to sign in), or its release page when absent. */
    fun openHermesLive(host: Context) {
        if (!HermesLive.open(host)) HermesLive.openReleases(host)
    }

    /** Re-read whether Hermes Live is installed and signed in. Off the main thread; never blocks resume. */
    fun refreshHermes(force: Boolean = true) {
        val now = SystemClock.elapsedRealtime()
        if (!force && now - hermesCheckedAt < HERMES_RECHECK_MS) return
        if (hermesJob?.isActive == true) return
        hermesCheckedAt = now
        hermesJob = viewModelScope.launch {
            val availability = withContext(Dispatchers.IO) {
                val installed = HermesLive.isInstalled(app)
                HermesLive.availability(installed, if (installed) HermesLive.query(app) else null)
            }
            extra.value = extra.value.copy(hermes = availability)
        }
    }

    /** Only Hermes Live's own provider; nothing when it is not installed. */
    private fun hermesConnection(forceRefresh: Boolean = false) =
        if (HermesLive.isInstalled(app)) HermesLive.query(app, forceRefresh) else null

    /** The Hermes overlay is on screen (or gone). The gateway is connected only while it is, and Folio is started. */
    fun onHermesVisible(visible: Boolean) {
        hermesVisible = visible
        if (visible) refreshHermes(force = true)
        syncHermes()
    }

    fun onStarted(isStarted: Boolean) {
        started = isStarted
        syncHermes()
    }

    private fun syncHermes() {
        if (hermesVisible && started) hermesAsk.attach() else hermesAsk.detach()
    }

    fun askHermes(text: String) = hermesAsk.ask(text)

    fun stopHermes() = hermesAsk.stop()

    fun newHermesSession() = hermesAsk.newSession()

    fun answerHermesApproval(choice: ApprovalChoice) = hermesAsk.approve(choice)

    fun answerHermesClarify(answers: Map<String, String?>) = hermesAsk.clarify(answers)

    fun openGoogleSearch(host: Context) {
        if (GoogleSearch.open(host)) {
            viewModelScope.launch { usageStore.record(GoogleSearch.PACKAGE) }
        }
    }

    fun onSearchQuery(query: String) {
        searchQuery.value = query.trim()
    }

    /** Once per keystroke; collectLatest drops a call still in flight when the next one arrives. */
    private suspend fun think(query: String) {
        _searchHint.value = SearchHint(query = query)
        val key = prefsStore.data.first().openRouterKey
        if (!jevMayAsk(key)) return
        if (query.length < SearchBrain.MIN_CHARS) return
        delay(SearchBrain.PAUSE_MS)
        val apps = appsRepo.apps.value
        val options = withContext(Dispatchers.Default) {
            if (!SearchBrain.worthAsking(query, apps.map { it.label })) return@withContext null
            SearchBrain.candidates(apps, state.value.launches)
        } ?: return
        val (brainState, questions) = SearchBrain.request(
            query,
            options.mapValues { it.value.packageName },
            music = apps.any { it.packageName == Spotify.PACKAGE },
        )
        val router = OpenRouter(key)
        val answers = router.decide(brainState, questions)
        noteJevStatus(key, router.lastStatus)
        if (answers == null) return
        val route = when (val picked = SearchBrain.route(query, answers)) {
            SearchRoute.Ask -> if (state.value.aiLabel.isEmpty()) SearchRoute.Web else picked
            else -> picked
        } ?: return
        when (route) {
            is SearchRoute.App -> {
                val app = options[route.name] ?: return
                _searchHint.value = SearchHint(query, route, app = app)
            }
            SearchRoute.Answer -> {
                _searchHint.value = SearchHint(query, route, answering = true)
                val line = router.write(SearchBrain.ANSWER_SYSTEM, query)?.let(SearchBrain::cleanAnswer)
                noteJevStatus(key, router.lastStatus)
                _searchHint.value = if (line != null) {
                    SearchHint(query, route, answer = line)
                } else {
                    SearchHint(query, SearchRoute.Web)
                }
            }
            else -> _searchHint.value = SearchHint(query, route)
        }
    }

    /** A search route that was tapped or entered. App routes launch through [launch]. */
    fun follow(host: Context, route: SearchRoute, query: String) {
        when (route) {
            is SearchRoute.App -> Unit
            SearchRoute.Web -> openWeb(host, query)
            // HomeScreen sends these to the Hermes overlay when Hermes is chosen; openAi never opens another app then.
            SearchRoute.Answer, SearchRoute.Ask ->
                if (state.value.aiLabel.isNotEmpty()) openAi(host, query) else openWeb(host, query)
            is SearchRoute.Music -> if (Spotify.search(host, route.term)) {
                viewModelScope.launch { usageStore.record(Spotify.PACKAGE) }
            }
        }
    }

    private fun openWeb(host: Context, query: String) {
        if (GoogleSearch.search(host, query)) {
            viewModelScope.launch { usageStore.record(GoogleSearch.PACKAGE) }
        }
    }

    /** Jev's quote for a new Bing print, from the shortlist the daily pick heads. Null without a key. */
    private suspend fun fitQuote(shot: WallpaperRepository.BingShot, salt: Int): Int? {
        val key = prefsStore.data.first().openRouterKey
        if (!jevMayAsk(key)) return null
        val quotes = QuoteBank.load(app)
        val shortlist = QuoteFit.shortlist(quotes.size, QuoteBank.todayIndex(), salt)
        val (photo, questions) = QuoteFit.request(QuoteFit.describe(shot.caption, shot.credit), quotes, shortlist)
        val router = OpenRouter(key)
        val answers = router.decide(photo, questions)
        noteJevStatus(key, router.lastStatus)
        return answers?.let { QuoteFit.pick(it, shortlist) }
    }

    /** With a key OpenRouter hasn't refused or found out of credit, and not just after "too many calls". */
    private fun jevMayAsk(key: String): Boolean =
        jevMayAsk(key, extra.value.brain) && SystemClock.elapsedRealtime() >= jevPausedUntil

    /**
     * After a call with [key]. A refused key, or one out of credit, stops Jev until Settings: every
     * call would fail the same way. Too many calls pauses it for a minute.
     */
    private suspend fun noteJevStatus(key: String, httpStatus: Int) {
        if (httpStatus == OpenRouter.RATE_LIMITED) jevPausedUntil = SystemClock.elapsedRealtime() + JEV_PAUSE_MS
        val status = OpenRouter.keyStatus(httpStatus) ?: return
        // A call that went out with the key before a new paste says nothing about the new one.
        if (extra.value.pasted == BrainStatus.Checking || prefsStore.data.first().openRouterKey != key) return
        // News about the saved key: the row says so over an older refused paste.
        extra.value = extra.value.copy(brain = status, pasted = null)
    }

    /** Text from the clipboard in Settings. False if it isn't an OpenRouter key. */
    fun setOpenRouterKey(text: String): Boolean {
        val key = text.trim()
        if (!OpenRouter.looksLikeKey(key)) return false
        extra.value = extra.value.copy(pasted = BrainStatus.Checking)
        keyCheck?.cancel()
        keyCheck = viewModelScope.launch {
            val status = when (OpenRouter(key).check()) {
                true -> BrainStatus.Ready
                false -> BrainStatus.Rejected
                null -> BrainStatus.Unverified
            }
            // A key OpenRouter refuses isn't kept (after a restart it would read as Ready again), and
            // says nothing about the key already saved: Jev goes on asking with that one.
            if (status == BrainStatus.Rejected) {
                extra.value = extra.value.copy(pasted = status)
            } else {
                prefsStore.update { it.copy(openRouterKey = key) }
                jevPausedUntil = 0L
                extra.value = extra.value.copy(brain = status, pasted = null)
            }
        }
        return true
    }

    /** Settings › Jev tapped with no new key after OpenRouter said this one is out of credit: try it again. */
    fun retryOpenRouterKey() {
        if (extra.value.brain == BrainStatus.NoCredit) extra.value = extra.value.copy(brain = null)
    }

    fun clearOpenRouterKey() {
        keyCheck?.cancel()
        extra.value = extra.value.copy(brain = null, pasted = null)
        _searchHint.value = SearchHint()
        viewModelScope.launch { prefsStore.update { it.copy(openRouterKey = "") } }
    }

    fun skipRole() {
        viewModelScope.launch {
            prefsStore.update { it.copy(skippedRole = true) }
        }
    }

    fun skipWallpaper() {
        viewModelScope.launch {
            prefsStore.update { it.copy(skippedWallpaper = true) }
        }
    }

    fun skipAccess() {
        viewModelScope.launch {
            prefsStore.update { it.copy(accessOffered = true) }
        }
    }

    fun setWallpaper(uri: Uri) = print.setFromUri(uri)

    fun useSystemWallpaper() = print.useSystem()

    fun nextBingPrint() = print.next()

    fun previousPrint() = print.previous()

    fun ensureTodaysPrint() = print.ensureToday()

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
                    Ranking.combinedLaunches(usage.launches, extra.value.usageTimestamps, extra.value.usageReadAt),
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
        val launches = Ranking.combinedLaunches(usage.launches, extraState.usageTimestamps, extraState.usageReadAt)

        val rail = Ranking.railSlots(prefs.slots).map { slot ->
            RailSlot(
                app = slot.packageName?.let { index.find(it, slot.activityName) },
                pinned = slot.pinned,
            )
        }

        val bank = QuoteBank.load(app)
        val quote = bank.getOrNull(prefs.quoteFit)?.takeIf { prefs.isBingPrint }
            ?: QuoteBank.pick(bank, QuoteBank.todayIndex(), prefs.quoteSalt)
        val packages = apps.mapTo(HashSet()) { it.packageName }
        val aiInstalled = AiApps.installedFrom(packages)
        val provider = AskProviders.resolve(
            prefs.aiPackage,
            AskProviders.list(extraState.hermes.signedIn, aiInstalled),
        )
        val aiPackage = when (provider) {
            AskProvider.Hermes -> HermesLive.PACKAGE
            is AskProvider.App -> AiApps.matchedPackage(provider.kind, packages).orEmpty()
            null -> ""
        }

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
            mode = visual,
            showClock = prefs.showClock,
            diagnostics = prefs.diagnostics,
            diagnosticsBuiltIn = BuildConfig.DIAGNOSTICS_URL.isNotBlank(),
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
            aiLabel = provider?.label.orEmpty(),
            aiInstalled = aiInstalled,
            hermes = extraState.hermes,
            askHermes = provider == AskProvider.Hermes,
            brain = brainShown(prefs.openRouterKey, extraState.brain, extraState.pasted),
        )
    }

    private data class ExtraState(
        val isDefaultHome: Boolean = false,
        val grants: AccessGrants = AccessGrants(dnd = false, usage = false, media = false),
        val systemWallpaperReadable: Boolean = false,
        val usageTimestamps: Map<String, List<Long>> = emptyMap(),
        /** When [usageTimestamps] was read (wall clock): launches from Folio since then aren't in it. */
        val usageReadAt: Long = 0L,
        val charging: Boolean = false,
        val charge: Float = 0f,
        val musicPlaying: Boolean = false,
        /** What OpenRouter said this session about the saved key (its check when pasted, or a call); null means trust it. */
        val brain: BrainStatus? = null,
        /** A pasted key being checked, or refused and so never saved; null once one is saved. Jev keeps the saved key meanwhile. */
        val pasted: BrainStatus? = null,
        val hermes: HermesAvailability = HermesAvailability.NotInstalled,
    )

    companion object {
        private const val KEY_PENDING_ACCESS = "pending_access"
        private const val KEY_WALKING = "access_walking"
        private const val HERMES_RECHECK_MS = 10_000L
        private const val USAGE_RECHECK_MS = 60_000L
        private const val JEV_PAUSE_MS = 60_000L

        private fun today(): Int = LocalDate.now(ZoneId.systemDefault()).toEpochDay().toInt()

        /** System usage on resume: the first time, then at most once a minute; at once when access was just granted. */
        internal fun usageQueryDue(now: Long, queriedAt: Long?, justGranted: Boolean): Boolean =
            justGranted || queriedAt == null || now - queriedAt >= USAGE_RECHECK_MS

        /**
         * Jev's row in Settings. A pasted key being checked, or refused ([pasted]; a refused key is
         * never saved), says so whatever is saved; otherwise a saved key is trusted until a call says
         * not ([checked]).
         */
        internal fun brainShown(savedKey: String, checked: BrainStatus?, pasted: BrainStatus?): BrainStatus = when {
            pasted != null -> pasted
            savedKey.isEmpty() -> BrainStatus.Off
            else -> checked ?: BrainStatus.Ready
        }

        /** Jev asks with [savedKey] until a call finds it refused or out of credit ([checked]); a refused paste was never saved. */
        internal fun jevMayAsk(savedKey: String, checked: BrainStatus?): Boolean =
            savedKey.isNotEmpty() && checked != BrainStatus.Rejected && checked != BrainStatus.NoCredit
    }
}
