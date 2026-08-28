package com.pulse.launcher.home

import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.view.HapticFeedbackConstants
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
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
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.util.lerp
import com.pulse.launcher.data.HomeUiState
import com.pulse.launcher.data.LaunchableApp
import com.pulse.launcher.data.Ranking
import com.pulse.launcher.data.RingerVisual
import com.pulse.launcher.onboarding.Onboarding
import com.pulse.launcher.recents.DockRowHeight
import com.pulse.launcher.recents.ExpandingDock
import com.pulse.launcher.recents.dockSnapPx
import com.pulse.launcher.recents.visibleExtraRows
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
    onSilentHint: () -> Unit,
    onNextBing: () -> Unit,
    onSkipTrack: () -> Unit,
) {
    val density = LocalDensity.current
    val scope = rememberCoroutineScope()
    val view = LocalView.current
    val context = LocalContext.current
    val pullAnim = remember { Animatable(0f) }
    var dragging by remember { mutableStateOf(false) }
    var rawPull by remember { mutableFloatStateOf(0f) }
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

    val pullPx = if (dragging) rawPull else pullAnim.value

    LaunchedEffect(idleEpoch) {
        if (idleEpoch == 0) return@LaunchedEffect
        searchOpen = false
        query = ""
        pinSlot = -1
        dragging = false
        revealTarget = null
        revealProgress.snapTo(0f)
        pullAnim.animateTo(0f, settleSpring())
    }

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val rowPx = with(density) { DockRowHeight.toPx() }
        val railPkgs = remember(state.rail) { state.rail.mapNotNull { it.app?.packageName }.toSet() }
        val extras = remember(state.reveal, state.recents, railPkgs) {
            val recentFirst = state.recents
                .map { it.app }
                .filter { it.packageName !in railPkgs }
            val seen = recentFirst.map { it.key }.toSet()
            recentFirst + state.reveal.filter {
                it.packageName !in railPkgs && it.key !in seen
            }
        }
        val maxRows = ((extras.size + 3) / 4).coerceAtMost(6)
        val maxPull = maxRows * rowPx
        val searchSlop = with(density) { 56.dp.toPx() }

        fun settle(to: Float) {
            scope.launch {
                val from = if (dragging) rawPull else pullAnim.value
                dragging = false
                pullAnim.snapTo(from)
                pullAnim.animateTo(to.coerceIn(0f, maxPull), settleSpring())
            }
        }

        var lastRows by remember { mutableIntStateOf(0) }
        fun tickRows(px: Float) {
            val rowsNow = visibleExtraRows(px, rowPx)
            if (rowsNow != lastRows) {
                lastRows = rowsNow
                val code = if (Build.VERSION.SDK_INT >= 34) {
                    HapticFeedbackConstants.SEGMENT_FREQUENT_TICK
                } else {
                    HapticFeedbackConstants.CLOCK_TICK
                }
                view.performHapticFeedback(code)
            }
        }

        val gesturesEnabled = state.onboarding == null && !searchOpen && pinSlot < 0

        fun collapse() = settle(0f)

        fun openSearch() {
            view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
            searchOpen = true
        }

        BackHandler(enabled = searchOpen || pullPx > 4f || pinSlot >= 0) {
            when {
                pinSlot >= 0 -> pinSlot = -1
                searchOpen -> {
                    searchOpen = false
                    query = ""
                }
                else -> collapse()
            }
        }

        fun revealDrag(): Modifier = Modifier.pointerInput(gesturesEnabled, maxPull) {
            if (!gesturesEnabled) return@pointerInput
            var downAccum = 0f
            val tracker = VelocityTracker()
            detectVerticalDragGestures(
                onDragStart = {
                    downAccum = 0f
                    tracker.resetTracking()
                    rawPull = pullAnim.value
                    dragging = true
                },
                onVerticalDrag = { change, dy ->
                    change.consume()
                    tracker.addPosition(change.uptimeMillis, change.position)
                    downAccum += dy
                    rawPull = rubberBand(rawPull - dy, maxPull)
                    tickRows(rawPull)
                },
                onDragEnd = {
                    if (rawPull < 8f && downAccum > searchSlop) {
                        dragging = false
                        openSearch()
                    } else {
                        val projected = (rawPull - tracker.calculateVelocity().y * 0.18f)
                            .coerceIn(0f, maxPull)
                        settle(dockSnapPx(projected, rowPx, maxRows))
                    }
                },
                onDragCancel = {
                    settle(dockSnapPx(rawPull.coerceIn(0f, maxPull), rowPx, maxRows))
                },
            )
        }

        val open = if (maxPull <= 0f) 0f else (pullPx / (rowPx * 2f)).coerceIn(0f, 1f)
        val targetLook = revealTarget
        val iconSat = if (targetLook != null) {
            lerp(look.iconSaturation(), targetLook.iconSaturation(), revealProgress.value)
        } else {
            look.iconSaturation()
        }

        val parallax = rememberParallax(
            enabled = gesturesEnabled && pullPx < 12f && !developing,
        )
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
                .background(Color.Black.copy(alpha = open * 0.38f)),
        )

        Box(
            Modifier
                .fillMaxSize()
                .then(revealDrag())
                .pointerInput(gesturesEnabled, look) {
                    if (!gesturesEnabled) return@pointerInput
                    detectTapGestures(
                        onDoubleTap = {
                            view.performHapticFeedback(HapticFeedbackConstants.CONFIRM)
                            onNextBing()
                        },
                        onLongPress = { origin ->
                            if (revealTarget != null || dragging) return@detectTapGestures
                            val heldPull = if (dragging) rawPull else pullAnim.value
                            if (heldPull > 8f) return@detectTapGestures
                            val next = look.nextRinger()
                            revealOrigin = origin
                            revealTarget = next
                            flashLook = true
                            view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                            if (next == RingerVisual.Vibrate) buzz(context)
                            scope.launch {
                                revealProgress.snapTo(0f)
                                revealProgress.animateTo(1f, developSpec())
                                lookOverride = next
                                revealTarget = null
                                revealProgress.snapTo(0f)
                                onSetRinger(next)
                                delay(1100)
                                flashLook = false
                            }
                        },
                    )
                },
        )

        if (!searchOpen &&
            state.onboarding == null &&
            (state.showClock || state.nowPlaying.isNotEmpty() || state.wallpaperCaption.isNotEmpty() || developing || flashLook || state.quote.isNotEmpty())
        ) {
            val namedLook = targetLook ?: look
            ClockCluster(
                showClock = state.showClock,
                accent = state.accent,
                dimClock = namedLook == RingerVisual.Silent && (!developing || revealProgress.value > 0.45f),
                charging = state.charging,
                charge = state.charge,
                nowPlaying = state.nowPlaying,
                quote = state.quote,
                quoteAuthor = state.quoteAuthor,
                lookName = if (developing || flashLook) namedLook.label() else null,
                caption = state.wallpaperCaption,
                captionBusy = state.wallpaperBusy,
                onClockTap = { openSearch() },
                onClockLongPress = onOpenSettings,
                onSkipTrack = onSkipTrack,
                haloSize = (maxWidth * 0.56f).coerceIn(196.dp, 224.dp),
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top))
                    .padding(top = (maxHeight * 0.045f).coerceIn(18.dp, 32.dp))
                    .graphicsLayer {
                        alpha = 1f - open * 0.92f
                        translationY = -open * 18f
                    },
            )
        }

        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .windowInsetsPadding(
                    WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom + WindowInsetsSides.Horizontal),
                ),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            ExpandingDock(
                pullPx = pullPx,
                rail = state.rail,
                extras = extras,
                iconSaturation = iconSat,
                onLaunch = {
                    onLaunch(it)
                    searchOpen = false
                    collapse()
                },
                onPinRequest = { pinSlot = it },
                onReorder = onReorder,
                modifier = Modifier
                    .fillMaxWidth()
                    .then(revealDrag()),
            )
            if (state.onboarding == null) {
                Spacer(Modifier.height(8.dp))
                SearchButton(
                    onClick = { if (!searchOpen && pinSlot < 0) openSearch() },
                    modifier = Modifier.graphicsLayer {
                        alpha = if (searchOpen) 0f else 1f - open * 0.12f
                    },
                )
            }
            Spacer(Modifier.height(12.dp))
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

        state.onboarding?.let { step ->
            Onboarding(
                step = step,
                accent = state.accent,
                systemWallpaperReadable = state.systemWallpaperReadable,
                onSetDefault = onSetDefault,
                onSkipRole = onSkipRole,
                onPickPhoto = onPickPhoto,
                onUseBing = onNextBing,
                onUseSystem = onUseSystemWallpaper,
                onSkipWallpaper = onSkipWallpaper,
            )
        }

        if (state.silentHint &&
            state.mode == RingerVisual.Silent &&
            state.onboarding == null &&
            !searchOpen &&
            pullPx < 8f
        ) {
            Box(
                Modifier
                    .align(Alignment.TopCenter)
                    .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top))
                    .padding(top = 10.dp, start = 20.dp, end = 20.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(VoidBlack.copy(alpha = 0.75f))
                    .clickable(onClick = onSilentHint)
                    .padding(horizontal = 14.dp, vertical = 8.dp),
            ) {
                Text(
                    "Tap to let Silent mute the ringer.",
                    color = PrintInk.copy(alpha = 0.7f),
                    fontSize = 12.sp,
                )
            }
        }
    }
}

private fun rubberBand(offset: Float, limit: Float): Float {
    if (offset <= 0f) return 0f
    if (offset <= limit) return offset
    val extra = offset - limit
    val dim = 420f
    return limit + extra * dim / (dim + extra)
}

private fun settleSpring() = spring<Float>(
    dampingRatio = 0.90f,
    stiffness = Spring.StiffnessMedium,
)

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

private fun buzz(context: android.content.Context) {
    runCatching {
        val vibrator = context.getSystemService(Vibrator::class.java) ?: return
        vibrator.vibrate(VibrationEffect.createOneShot(36, VibrationEffect.DEFAULT_AMPLITUDE))
    }
}
