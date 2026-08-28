package com.pulse.launcher.home

import android.os.Build
import android.view.HapticFeedbackConstants
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.util.lerp
import kotlin.math.roundToInt
import com.pulse.launcher.data.HomeUiState
import com.pulse.launcher.data.LaunchableApp
import com.pulse.launcher.data.Ranking
import com.pulse.launcher.data.RingerVisual
import com.pulse.launcher.onboarding.Onboarding
import com.pulse.launcher.recents.DockRowHeight
import com.pulse.launcher.recents.ExpandingDock
import com.pulse.launcher.search.AppPicker
import com.pulse.launcher.search.SearchOverlay
import com.pulse.launcher.ui.PrintInk
import com.pulse.launcher.ui.VoidBlack
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
fun HomeScreen(
    state: HomeUiState,
    idleEpoch: Int,
    launches: Map<String, List<Long>>,
    onLaunch: (LaunchableApp) -> Unit,
    onDismissRecent: (String) -> Unit,
    onPin: (Int, LaunchableApp) -> Unit,
    onReorder: (Int, Int) -> Unit,
    onCycleRinger: () -> Unit,
    onSetRinger: (RingerVisual) -> Unit,
    onOpenSettings: () -> Unit,
    onPickPhoto: () -> Unit,
    onSetDefault: () -> Unit,
    onSkipRole: () -> Unit,
    onSkipWallpaper: () -> Unit,
    onUseSystemWallpaper: () -> Unit,
    onOpenDnd: () -> Unit,
    onSkipAccess: () -> Unit,
    onSilentHint: () -> Unit,
    onMediaHint: () -> Unit,
    onNextBing: () -> Unit,
    onPreviousTrack: () -> Unit,
    onPlayPause: () -> Unit,
    onSkipTrack: () -> Unit,
    onOpenPlayer: () -> Unit,
    onSeekTrack: (Float) -> Unit,
) {
    val density = LocalDensity.current
    val scope = rememberCoroutineScope()
    val view = LocalView.current
    val pull = remember { SheetPull() }
    val page = remember { SheetPull() }
    var searchOpen by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    var pinSlot by remember { mutableIntStateOf(-1) }
    var lookOverride by remember { mutableStateOf<RingerVisual?>(null) }
    var flashLook by remember { mutableStateOf(false) }
    var lookReady by remember { mutableStateOf(false) }
    var revealOrigin by remember { mutableStateOf(Offset.Zero) }
    var revealTarget by remember { mutableStateOf<RingerVisual?>(null) }
    val revealProgress = remember { Animatable(0f) }

    val look = lookOverride ?: state.mode
    val developing = revealTarget != null
    LaunchedEffect(state.mode) {
        if (lookOverride == state.mode) lookOverride = null
        if (!lookReady) {
            lookReady = true
            return@LaunchedEffect
        }
        if (developing) return@LaunchedEffect
        flashLook = true
        delay(1100)
        flashLook = false
    }

    LaunchedEffect(idleEpoch) {
        if (idleEpoch == 0) return@LaunchedEffect
        searchOpen = false
        query = ""
        pinSlot = -1
        revealTarget = null
        revealProgress.snapTo(0f)
        pull.cancelSettle()
        pull.locked = false
        pull.px = 0f
        page.cancelSettle()
        page.locked = false
        page.px = 0f
    }

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val rowPx = with(density) { DockRowHeight.toPx() }
        val railPkgs = remember(state.rail) { state.rail.mapNotNull { it.app?.packageName }.toSet() }
        val extras = remember(state.apps, railPkgs, launches) {
            Ranking.drawer(state.apps, railPkgs, launches)
        }
        val paneWidth = maxWidth
        val paneHeight = maxHeight
        val maxSheetPx = with(density) { (paneHeight * 0.62f).toPx() }.coerceAtLeast(rowPx * 3.2f)
        val maxPagePx = with(density) { paneWidth.toPx() }.coerceAtLeast(1f)
        val searchSlop = with(density) { 56.dp.toPx() }

        fun grabSheet() {
            pull.cancelSettle()
            pull.locked = false
        }

        fun grabPage() {
            page.cancelSettle()
            page.locked = false
        }

        fun settleSheet(velocityY: Float = 0f) {
            pull.cancelSettle()
            pull.settleJob = scope.launch {
                val start = pull.px
                val open = sheetShouldOpen(start, maxSheetPx, velocityY)
                val target = if (open) maxSheetPx else 0f
                animate(
                    initialValue = start,
                    targetValue = target,
                    initialVelocity = -velocityY,
                    animationSpec = sheetSpring(),
                ) { value, _ ->
                    pull.px = value
                }
                pull.px = target
                pull.locked = open
                if (open) {
                    val tick = if (Build.VERSION.SDK_INT >= 34) {
                        HapticFeedbackConstants.GESTURE_START
                    } else {
                        HapticFeedbackConstants.KEYBOARD_TAP
                    }
                    view.performHapticFeedback(tick)
                }
            }
        }

        fun settlePage(velocityX: Float = 0f) {
            page.cancelSettle()
            page.settleJob = scope.launch {
                val start = page.px
                val open = pageShouldOpen(start, maxPagePx, velocityX)
                val target = if (open) maxPagePx else 0f
                animate(
                    initialValue = start,
                    targetValue = target,
                    initialVelocity = velocityX,
                    animationSpec = sheetSpring(),
                ) { value, _ ->
                    page.px = value
                }
                page.px = target
                page.locked = open
                if (open) {
                    view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                }
            }
        }

        fun openPlayerPage() {
            grabPage()
            settlePage(1800f)
        }

        val gesturesEnabled = state.onboarding == null && !searchOpen && pinSlot < 0
        fun homeDrag(): Modifier = Modifier.pulseHomeGestures(
            enabled = gesturesEnabled,
            sheet = pull,
            page = page,
            maxSheetPx = maxSheetPx,
            maxPagePx = maxPagePx,
            searchSlop = searchSlop,
            onGrabSheet = { grabSheet() },
            onSettleSheet = { settleSheet(it) },
            onGrabPage = { grabPage() },
            onSettlePage = { settlePage(it) },
            onSwipeDownSearch = {
                view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                searchOpen = true
            },
        )

        fun collapse() {
            grabSheet()
            pull.settleJob = scope.launch {
                val start = pull.px
                animate(start, 0f, 0f, sheetSpring()) { value, _ ->
                    pull.px = value
                }
                pull.px = 0f
                pull.locked = false
            }
        }

        fun openSearch() {
            view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
            searchOpen = true
        }

        BackHandler(enabled = searchOpen || pull.locked || page.locked || pinSlot >= 0) {
            when {
                pinSlot >= 0 -> pinSlot = -1
                searchOpen -> {
                    searchOpen = false
                    query = ""
                }
                page.locked -> {
                    grabPage()
                    settlePage(-1800f)
                }
                else -> collapse()
            }
        }

        val targetLook = revealTarget
        val iconSat = if (targetLook != null) {
            lerp(look.iconSaturation(), targetLook.iconSaturation(), revealProgress.value)
        } else {
            look.iconSaturation()
        }

        val parallax = rememberParallax(
            enabled = gesturesEnabled && !developing && !page.locked,
        )
        val namedLook = targetLook ?: look
        NowPlayingPage(
            line = state.nowPlaying,
            title = state.musicTitle,
            artist = state.musicArtist,
            playing = state.musicPlaying,
            art = state.musicArt,
            positionMs = state.musicPositionMs,
            durationMs = state.musicDurationMs,
            hasAccess = state.hasNowPlayingAccess,
            accent = state.accent,
            onPrevious = onPreviousTrack,
            onPlayPause = onPlayPause,
            onNext = onSkipTrack,
            onSeek = onSeekTrack,
            onOpenApp = onOpenPlayer,
            onAllowAccess = onMediaHint,
            modifier = Modifier
                .fillMaxSize()
                .offset { IntOffset((page.px - maxPagePx).roundToInt(), 0) }
                .then(homeDrag()),
        )
        Box(
            Modifier
                .fillMaxSize()
                .offset { IntOffset(page.px.roundToInt(), 0) },
        ) {
        WallpaperLayer(
            photo = state.wallpaper,
            blurred = state.blurredWallpaper,
            mode = look,
            accent = state.accent,
            parallax = parallax,
            reveal = targetLook?.let {
                GradeReveal(
                    origin = revealOrigin,
                    progress = revealProgress.value,
                    target = it,
                )
            },
            modifier = Modifier.fillMaxSize(),
        )

        Box(
            Modifier
                .fillMaxSize()
                .graphicsLayer {
                    val open = if (maxSheetPx <= 0f) 0f else (pull.px / maxSheetPx).coerceIn(0f, 1f)
                    alpha = open * 0.38f
                }
                .background(Color.Black),
        )

        Box(
            Modifier
                .fillMaxSize()
                .then(homeDrag())
                .pointerInput(gesturesEnabled, look) {
                    if (!gesturesEnabled) return@pointerInput
                    detectTapGestures(
                        onDoubleTap = {
                            view.performHapticFeedback(HapticFeedbackConstants.CONFIRM)
                            onNextBing()
                        },
                        onLongPress = { origin ->
                            if (revealTarget != null) return@detectTapGestures
                            if (pull.px > 8f || page.px > 8f) return@detectTapGestures
                            val current = look
                            val next = current.nextRinger()
                            lookOverride = current
                            revealOrigin = origin
                            revealTarget = next
                            flashLook = true
                            view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                            onSetRinger(next)
                            scope.launch {
                                revealProgress.snapTo(0f)
                                revealProgress.animateTo(1f, developSpec())
                                lookOverride = next
                                revealTarget = null
                                revealProgress.snapTo(0f)
                                delay(1100)
                                flashLook = false
                            }
                        },
                    )
                },
        )

        if (!searchOpen &&
            state.onboarding == null &&
            (state.showClock || state.wallpaperCaption.isNotEmpty() || developing || flashLook || state.quote.isNotEmpty())
        ) {
            ClockCluster(
                showClock = state.showClock,
                accent = state.accent,
                dimClock = namedLook == RingerVisual.Silent && (!developing || revealProgress.value > 0.45f),
                charging = state.charging,
                charge = state.charge,
                quote = state.quote,
                quoteAuthor = state.quoteAuthor,
                lookName = if (developing || flashLook) namedLook.label() else null,
                caption = state.wallpaperCaption,
                captionBusy = state.wallpaperBusy,
                onClockTap = { openSearch() },
                onClockLongPress = onOpenSettings,
                haloSize = (paneWidth * 0.56f).coerceIn(196.dp, 224.dp),
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .fillMaxWidth()
                    .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top))
                    .padding(top = (paneHeight * 0.045f).coerceIn(18.dp, 32.dp))
                    .graphicsLayer {
                        val open = if (maxSheetPx <= 0f) 0f else (pull.px / maxSheetPx).coerceIn(0f, 1f)
                        alpha = 1f - open * 0.92f
                        translationY = -open * 18f
                    },
            )
        }

        Box(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .windowInsetsPadding(
                    WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom + WindowInsetsSides.Horizontal),
                ),
        ) {
            ExpandingDock(
                pull = pull,
                maxSheetPx = maxSheetPx,
                rail = state.rail,
                extras = extras,
                iconSaturation = iconSat,
                onSettle = { settleSheet(it) },
                onLaunch = {
                    onLaunch(it)
                    searchOpen = false
                    collapse()
                },
                onPinRequest = { pinSlot = it },
                onReorder = onReorder,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .padding(bottom = 64.dp)
                    .then(if (pull.locked) Modifier else homeDrag()),
            )
            if (state.nowPlaying.isNotEmpty() &&
                state.onboarding == null &&
                !searchOpen
            ) {
                val namedLook = targetLook ?: look
                PlaybackStrip(
                    line = state.nowPlaying,
                    title = state.musicTitle,
                    artist = state.musicArtist,
                    playing = state.musicPlaying,
                    art = state.musicArt,
                    positionMs = state.musicPositionMs,
                    durationMs = state.musicDurationMs,
                    accent = state.accent,
                    dim = namedLook == RingerVisual.Silent,
                    onPrevious = onPreviousTrack,
                    onPlayPause = onPlayPause,
                    onNext = onSkipTrack,
                    onOpen = { openPlayerPage() },
                    onSeek = onSeekTrack,
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .padding(bottom = 156.dp)
                        .graphicsLayer {
                            val open = if (maxSheetPx <= 0f) 0f else (pull.px / maxSheetPx).coerceIn(0f, 1f)
                            alpha = 1f - open * 0.95f
                            translationY = open * 28f
                        },
                )
            }
            if (state.onboarding == null) {
                SearchButton(
                    onClick = { if (!searchOpen && pinSlot < 0) openSearch() },
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(bottom = 12.dp)
                        .graphicsLayer {
                            val open = if (maxSheetPx <= 0f) 0f else (pull.px / maxSheetPx).coerceIn(0f, 1f)
                            alpha = if (searchOpen) 0f else 1f - open * 0.12f
                        },
                )
            }
        }
        }

        val results = remember(query, state.apps, launches, state.rail, state.recents) {
            if (query.isBlank()) {
                Ranking.suggest(
                    apps = state.apps,
                    rail = state.rail.mapNotNull { it.app },
                    recents = state.recents.map { it.app },
                    launches = launches,
                )
            } else {
                Ranking.search(query, state.apps, launches)
            }
        }
        SearchOverlay(
            visible = searchOpen,
            query = query,
            onQueryChange = { query = it },
            results = results,
            accent = state.accent,
            iconSaturation = iconSat,
            onLaunch = {
                onLaunch(it)
                searchOpen = false
                query = ""
            },
            onDismiss = {
                searchOpen = false
                query = ""
            },
        )

        if (pinSlot >= 0) {
            AppPicker(
                apps = state.apps,
                launches = launches,
                accent = state.accent,
                onPick = { app ->
                    onPin(pinSlot, app)
                    pinSlot = -1
                },
                onDismiss = { pinSlot = -1 },
            )
        }

        val onboardStep = state.onboarding
        var lastOnboard by remember { mutableStateOf(onboardStep) }
        SideEffect {
            if (onboardStep != null) lastOnboard = onboardStep
        }
        AnimatedVisibility(
            visible = onboardStep != null,
            enter = fadeIn(tween(280)),
            exit = fadeOut(tween(340)),
        ) {
            val step = onboardStep ?: lastOnboard
            if (step != null) {
                Onboarding(
                    step = step,
                    accent = state.accent,
                    systemWallpaperReadable = state.systemWallpaperReadable,
                    hasDndAccess = state.hasDndAccess,
                    hasUsageAccess = state.hasUsageAccess,
                    hasNowPlayingAccess = state.hasNowPlayingAccess,
                    wallpaperBusy = state.wallpaperBusy,
                    onSetDefault = onSetDefault,
                    onSkipRole = onSkipRole,
                    onPickPhoto = onPickPhoto,
                    onUseBing = onNextBing,
                    onUseSystem = onUseSystemWallpaper,
                    onSkipWallpaper = onSkipWallpaper,
                    onAllowAccess = onOpenDnd,
                    onSkipAccess = onSkipAccess,
                )
            }
        }

        val hint = when {
            state.silentHint &&
                state.mode == RingerVisual.Silent &&
                state.onboarding == null &&
                !searchOpen &&
                !pull.locked ->
                "Tap to let Silent mute the ringer." to onSilentHint
            state.mediaHint &&
                state.onboarding == null &&
                !searchOpen &&
                !pull.locked &&
                state.nowPlaying.isEmpty() ->
                "Tap to show Spotify on the print." to onMediaHint
            else -> null
        }
        hint?.let { (text, action) ->
            Box(
                Modifier
                    .align(Alignment.TopCenter)
                    .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top))
                    .padding(top = 10.dp, start = 20.dp, end = 20.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(VoidBlack.copy(alpha = 0.75f))
                    .clickable(onClick = action)
                    .padding(horizontal = 14.dp, vertical = 8.dp),
            ) {
                Text(
                    text,
                    color = PrintInk.copy(alpha = 0.7f),
                    fontSize = 12.sp,
                )
            }
        }
    }
}

private fun developSpec() = tween<Float>(
    durationMillis = 580,
    easing = CubicBezierEasing(0.16f, 1f, 0.3f, 1f),
)

private fun RingerVisual.nextRinger(): RingerVisual = when (this) {
    RingerVisual.Sound -> RingerVisual.Vibrate
    RingerVisual.Vibrate -> RingerVisual.Silent
    RingerVisual.Silent -> RingerVisual.Sound
}

private fun RingerVisual.iconSaturation(): Float = when (this) {
    RingerVisual.Sound -> 1f
    RingerVisual.Vibrate -> 0.5f
    RingerVisual.Silent -> 0f
}

private fun RingerVisual.label(): String = when (this) {
    RingerVisual.Sound -> "Sound"
    RingerVisual.Vibrate -> "Vibrate"
    RingerVisual.Silent -> "Silent"
}


