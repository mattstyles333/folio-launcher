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
import com.folio.launcher.data.BrainStatus
import com.folio.launcher.data.Door
import com.folio.launcher.data.EndlessApps
import com.folio.launcher.data.DefaultApps
import com.folio.launcher.data.FolioBuzz
import com.folio.launcher.data.GoogleSearch
import com.folio.launcher.data.HomeUiState
import com.folio.launcher.data.LaunchableApp
import com.folio.launcher.data.OnboardingStep
import com.folio.launcher.data.OpenRouter
import com.folio.launcher.data.PetMind
import com.folio.launcher.data.PetMove
import com.folio.launcher.data.PetStore
import com.folio.launcher.data.PetUi
import com.folio.launcher.data.Prefs
import com.folio.launcher.data.PrintController
import com.folio.launcher.data.PrintState
import com.folio.launcher.data.QuoteBank
import com.folio.launcher.data.QuoteFit
import com.folio.launcher.data.RailSlot
import com.folio.launcher.data.Ranking
import com.folio.launcher.data.RecentItem
import com.folio.launcher.data.RingerController
import com.folio.launcher.data.RingerVisual
import com.folio.launcher.data.SearchBrain
import com.folio.launcher.data.SearchHint
import com.folio.launcher.data.ScrollDay
import com.folio.launcher.data.ScrollUsage
import com.folio.launcher.data.SearchRoute
import com.folio.launcher.data.SlotPref
import com.folio.launcher.data.Spotify
import com.folio.launcher.data.UsageData
import com.folio.launcher.data.WallpaperRepository
import com.folio.launcher.data.Weather
import com.folio.launcher.data.WeatherClient
import java.time.DayOfWeek
import java.time.Instant
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
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
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

    private val petStore = PetStore(app)
    private val _pet = MutableStateFlow(PetUi())

    /** The dog on the print: its sheet, mood, current move, the odd line, and the door it guards. */
    val pet: StateFlow<PetUi> = _pet.asStateFlow()
    private var petJob: Job? = null
    private var petThoughtAt = 0L
    private var petLineAt = 0L
    private var petPats = ArrayDeque<Long>()

    /** Something that just happened, for the next thought ("just chose not to open X"). */
    private var petMoment: String? = null
    private var scrollToday = ScrollDay()
    private var usualCache: Triple<Int, Int, Int>? = null
    private var weatherCache: Pair<Long, Weather>? = null
    private var doorPassed: Pair<String, Long>? = null
    private var endlessSorting = false

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
                    sortEndless(prefs, apps)
                }
        }
        viewModelScope.launch {
            val sheet = petStore.load()
            _pet.update { it.copy(sheet = sheet) }
            extra.update { it.copy(hasPet = sheet != null) }
            if (sheet != null) sortEndless(prefsStore.data.first(), appsRepo.apps.value)
            petThink(force = true)
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
        petThink()
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
        petMoment = "the owner just set the phone to ${visual.name}"
        petThink()
        if (visual == RingerVisual.Vibrate) FolioBuzz.play(app)
        viewModelScope.launch {
            val hint = visual == RingerVisual.Silent && !ringer.hasPolicyAccess()
            prefsStore.update { it.copy(silentHint = hint) }
        }
    }

    fun launch(app: LaunchableApp, bounds: Rect? = null): Boolean {
        if (holdAtDoor(app)) return false
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

    fun onSearchQuery(query: String) {
        searchQuery.value = query.trim()
    }

    /** Once per keystroke; collectLatest drops a call still in flight when the next one arrives. */
    private suspend fun think(query: String) {
        _searchHint.value = SearchHint(query = query)
        val key = prefsStore.data.first().openRouterKey
        if (key.isEmpty() || extra.value.brain == BrainStatus.Rejected) return
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
        val answers = router.decide(brainState, questions) ?: return
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

    /**
     * Jev looks at the day and decides how the dog feels, what it does, and whether it speaks.
     * At most every few minutes, unless something just happened.
     */
    private fun petThink(force: Boolean = false) {
        if (_pet.value.sheet == null) return
        if (!force && petMoment == null && petJob?.isActive == true) return
        val moment = petMoment.also { petMoment = null }
        petJob?.cancel()
        petJob = viewModelScope.launch {
            val now = System.currentTimeMillis()
            val prefs = prefsStore.data.first()
            val ui = state.value
            val (day, usual) = withContext(Dispatchers.IO) { scrollDay(ui.endless, now) }
            scrollToday = day
            val labels = ui.apps.associate { it.packageName to it.label }
            val label = { pkg: String -> labels[pkg] ?: pkg }
            val tag = ScrollUsage.tag(day.minutesByApp, label)
            // Coming home from an endless app always counts, even right after the last thought.
            val cameBack = (day.justLeft?.end ?: 0L) > petThoughtAt
            if (!force && moment == null && !cameBack && now - petThoughtAt < PET_THINK_MS) {
                _pet.update { it.copy(tag = tag) }
                return@launch
            }
            petThoughtAt = now
            val weather = if (prefs.petWeather) weatherNow(now)?.let(WeatherClient::describe).orEmpty() else ""
            val local = Instant.ofEpochMilli(now).atZone(ZoneId.systemDefault())
            val facts = PetMind.facts(
                hour = local.hour,
                weekend = local.dayOfWeek == DayOfWeek.SATURDAY || local.dayOfWeek == DayOfWeek.SUNDAY,
                day = day,
                usual = usual,
                goal = prefs.scrollGoal,
                stays = if (prefs.staysDay == today()) prefs.stays else 0,
                label = label,
                justNow = moment,
                ringer = ui.mode,
                weather = weather,
            )
            val router = prefs.openRouterKey
                .takeIf { it.isNotEmpty() && extra.value.brain != BrainStatus.Rejected }
                ?.let(::OpenRouter)
            val thought = router?.let { r ->
                val (seen, questions) = PetMind.request(facts)
                r.decide(seen, questions)?.let { PetMind.read(it, _pet.value.mood) }
            }
            val chosen = thought ?: PetMind.fallback(local.hour, ui.mode == RingerVisual.Silent, day, usual)
            _pet.update {
                it.copy(
                    mood = chosen.mood,
                    move = chosen.move,
                    beat = it.beat + 1,
                    tag = tag,
                )
            }
            if (router == null || !chosen.speak || now - petLineAt < PET_LINE_GAP_MS) return@launch
            val line = router.write(PetMind.LINE_SYSTEM, PetMind.linePrompt(facts, chosen.mood), maxTokens = 40)
                ?.let(PetMind::cleanLine)
                ?: return@launch
            petLineAt = now
            _pet.update { it.copy(line = line) }
            // Its own job, so a newer thought can't leave the line stuck on screen.
            viewModelScope.launch {
                delay(PET_LINE_SHOWN_MS)
                _pet.update { if (it.line == line) it.copy(line = "") else it }
            }
        }
    }

    /** Today's endless-app scrolling and the usual day's minutes (cached per day and app set). */
    private fun scrollDay(endless: Set<String>, now: Long): Pair<ScrollDay, Int> {
        val todayStart = Ranking.startOfDay(now)
        val events = ScrollUsage.read(app, todayStart - 3_600_000L, now)
        val day = ScrollUsage.day(ScrollUsage.sessions(events, endless, now), todayStart, now)
        val cacheKey = today() to endless.hashCode()
        val usual = usualCache?.takeIf { it.first == cacheKey.first && it.second == cacheKey.second }?.third
            ?: run {
                val past = ScrollUsage.read(app, todayStart - 7 * Ranking.DAY_MS, todayStart)
                ScrollUsage.usualMinutes(ScrollUsage.sessions(past, endless, todayStart), todayStart)
                    .also { usualCache = Triple(cacheKey.first, cacheKey.second, it) }
            }
        return day to usual
    }

    private suspend fun weatherNow(now: Long): Weather? {
        weatherCache?.takeIf { now - it.first < WEATHER_MS }?.let { return it.second }
        val fresh = WeatherClient.current(app) ?: return weatherCache?.second
        weatherCache = now to fresh
        return fresh
    }

    /** A pat: it waves, and three quick pats make it jump. No network. */
    fun patPet() {
        val now = System.currentTimeMillis()
        petPats.addLast(now)
        while (petPats.isNotEmpty() && now - petPats.first() > 10_000L) petPats.removeFirst()
        val move = if (petPats.size >= 3) PetMove.Jumping else PetMove.Waving
        if (move == PetMove.Jumping) petPats.clear()
        _pet.update { it.copy(move = move, beat = it.beat + 1) }
    }

    /** Endless apps from Folio wait a moment at the door while the dog looks up. */
    private fun holdAtDoor(target: LaunchableApp): Boolean {
        val ui = state.value
        if (_pet.value.sheet == null || !ui.doorPause || target.packageName !in ui.endless) return false
        val now = System.currentTimeMillis()
        val passed = doorPassed
        if (passed != null && passed.first == target.packageName && now - passed.second < DOOR_GRACE_MS) return false
        _pet.update { it.copy(door = Door(target, doorToday(scrollToday, target.packageName))) }
        viewModelScope.launch {
            val (day, _) = withContext(Dispatchers.IO) { scrollDay(ui.endless, now) }
            scrollToday = day
            _pet.update { p ->
                if (p.door?.app?.key != target.key) p else p.copy(door = Door(target, doorToday(day, target.packageName)))
            }
        }
        return true
    }

    private fun doorToday(day: ScrollDay, pkg: String): String {
        val mine = day.minutesByApp[pkg] ?: 0
        return if (mine == 0) "First time today" else "${PetMind.minutes(mine).replaceFirstChar { it.uppercase() }} today"
    }

    fun doorGo() {
        val door = _pet.value.door ?: return
        doorPassed = door.app.packageName to System.currentTimeMillis()
        _pet.update { it.copy(door = null) }
        launch(door.app)
    }

    fun doorStay() {
        val door = _pet.value.door ?: return
        _pet.update { it.copy(door = null, move = PetMove.Jumping, beat = it.beat + 1) }
        petMoment = "just chose not to open ${door.app.label}"
        viewModelScope.launch {
            val day = today()
            prefsStore.update { it.copy(staysDay = day, stays = if (it.staysDay == day) it.stays + 1 else 1) }
            petThink()
        }
    }

    /** Jev sorts new apps into endless-scroll or not, a batch at a time; the user's word wins. */
    private fun sortEndless(prefs: Prefs, apps: List<LaunchableApp>) {
        if (endlessSorting || !extra.value.hasPet) return
        val key = prefs.openRouterKey.takeIf { it.isNotEmpty() && extra.value.brain != BrainStatus.Rejected } ?: return
        val checked = prefs.endlessChecked.toSet()
        val todo = apps
            .filter {
                !it.isHome && it.packageName != app.packageName &&
                    it.packageName !in checked && it.packageName !in EndlessApps.KNOWN
            }
            .distinctBy { it.packageName }
            .take(PetMind.ENDLESS_BATCH)
        if (todo.isEmpty()) return
        endlessSorting = true
        viewModelScope.launch {
            try {
                val (listed, questions) = PetMind.endlessRequest(todo.map { "${it.label} (${it.packageName})" })
                val answers = OpenRouter(key).decide(listed, questions) ?: return@launch
                val flagged = PetMind.endlessRead(answers, todo.size).map { todo[it].packageName }
                prefsStore.update { p ->
                    p.copy(
                        endless = (p.endless + flagged.filter { it !in p.endlessOff }).distinct(),
                        endlessChecked = (p.endlessChecked + todo.map { it.packageName }).distinct(),
                    )
                }
            } finally {
                endlessSorting = false
            }
        }
    }

    fun importPet(uri: Uri) {
        viewModelScope.launch {
            val ok = petStore.import(uri)
            val sheet = if (ok) petStore.load() else _pet.value.sheet
            _pet.update { it.copy(sheet = sheet) }
            extra.update { it.copy(hasPet = sheet != null, petNote = if (ok) "" else "That isn’t a petdex sprite sheet") }
            if (ok) {
                sortEndless(prefsStore.data.first(), appsRepo.apps.value)
                petThink(force = true)
            }
        }
    }

    fun removePet() {
        petStore.remove()
        petJob?.cancel()
        _pet.value = PetUi()
        extra.update { it.copy(hasPet = false, petNote = "") }
    }

    fun toggleEndless(target: LaunchableApp) {
        val pkg = target.packageName
        val on = pkg in state.value.endless
        viewModelScope.launch {
            prefsStore.update { p ->
                if (on) {
                    p.copy(endless = p.endless - pkg, endlessOff = (p.endlessOff + pkg).distinct())
                } else {
                    p.copy(endless = (p.endless + pkg).distinct(), endlessOff = p.endlessOff - pkg)
                }
            }
        }
    }

    fun cycleScrollGoal() {
        viewModelScope.launch {
            prefsStore.update { p ->
                val i = GOALS.indexOf(p.scrollGoal)
                p.copy(scrollGoal = GOALS[(i + 1).mod(GOALS.size)])
            }
        }
    }

    fun setDoorPause(on: Boolean) {
        viewModelScope.launch { prefsStore.update { it.copy(doorPause = on) } }
    }

    fun setPetWeather(on: Boolean) {
        weatherCache = null
        viewModelScope.launch {
            prefsStore.update { it.copy(petWeather = on) }
            if (on) petThink(force = true)
        }
    }

    /** Jev's quote for a new Bing print, from the shortlist the daily pick heads. Null without a key. */
    private suspend fun fitQuote(shot: WallpaperRepository.BingShot, salt: Int): Int? {
        val key = prefsStore.data.first().openRouterKey
        if (key.isEmpty() || extra.value.brain == BrainStatus.Rejected) return null
        val quotes = QuoteBank.load(app)
        val shortlist = QuoteFit.shortlist(quotes.size, QuoteBank.todayIndex(), salt)
        val (photo, questions) = QuoteFit.request(QuoteFit.describe(shot.caption, shot.credit), quotes, shortlist)
        val answers = OpenRouter(key).decide(photo, questions) ?: return null
        return QuoteFit.pick(answers, shortlist)
    }

    /** Text from the clipboard in Settings. False if it isn't an OpenRouter key. */
    fun setOpenRouterKey(text: String): Boolean {
        val key = text.trim()
        if (!OpenRouter.looksLikeKey(key)) return false
        extra.value = extra.value.copy(brain = BrainStatus.Checking)
        viewModelScope.launch {
            prefsStore.update { it.copy(openRouterKey = key) }
            val status = when (OpenRouter(key).check()) {
                true -> BrainStatus.Ready
                false -> BrainStatus.Rejected
                null -> BrainStatus.Unverified
            }
            extra.value = extra.value.copy(brain = status)
        }
        return true
    }

    fun clearOpenRouterKey() {
        extra.value = extra.value.copy(brain = null)
        _searchHint.value = SearchHint()
        viewModelScope.launch { prefsStore.update { it.copy(openRouterKey = "") } }
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

        val bank = QuoteBank.load(app)
        val quote = bank.getOrNull(prefs.quoteFit)?.takeIf { prefs.isBingPrint }
            ?: QuoteBank.pick(bank, QuoteBank.todayIndex(), prefs.quoteSalt)
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
            brain = if (prefs.openRouterKey.isEmpty()) BrainStatus.Off else extraState.brain ?: BrainStatus.Ready,
            hasPet = extraState.hasPet,
            petNote = extraState.petNote,
            endless = EndlessApps.effective(packages, prefs.endless, prefs.endlessOff),
            scrollGoal = prefs.scrollGoal,
            doorPause = prefs.doorPause,
            petWeather = prefs.petWeather,
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
        /** Result of checking a key pasted this session; null means trust the saved key. */
        val brain: BrainStatus? = null,
        val hasPet: Boolean = false,
        val petNote: String = "",
    )

    companion object {
        private const val KEY_PENDING_ACCESS = "pending_access"
        private const val KEY_WALKING = "access_walking"
        private const val PET_THINK_MS = 3 * 60_000L
        private const val PET_LINE_GAP_MS = 20 * 60_000L
        private const val PET_LINE_SHOWN_MS = 6_000L
        private const val DOOR_GRACE_MS = 5 * 60_000L
        private const val WEATHER_MS = 30 * 60_000L
        private val GOALS = listOf(30, 45, 60, 90, 120, 0)

        private fun today(): Int = LocalDate.now(ZoneId.systemDefault()).toEpochDay().toInt()
    }
}
