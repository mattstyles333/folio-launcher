package com.pulse.launcher.home

import androidx.compose.animation.core.spring
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import kotlin.math.abs
import kotlinx.coroutines.Job

/** Pull of the app sheet in pixels. Read this from layout/draw, not from composition. */
class SheetPull {
    var px by mutableFloatStateOf(0f)
    var locked by mutableStateOf(false)
    var settleJob: Job? = null

    fun cancelSettle() {
        settleJob?.cancel()
        settleJob = null
    }
}

fun rubberBand(offset: Float, limit: Float): Float {
    if (offset <= 0f) return 0f
    if (offset <= limit) return offset
    val extra = offset - limit
    val dim = 520f
    return limit + extra * dim / (dim + extra)
}

fun sheetShouldOpen(pull: Float, max: Float, velocityY: Float): Boolean {
    val pullVel = -velocityY
    return if (abs(pullVel) > 800f) pullVel > 0f else pull > max * 0.2f
}

fun sheetSpring() = spring<Float>(
    dampingRatio = 0.90f,
    stiffness = 300f,
    visibilityThreshold = 0.5f,
)

/**
 * 1:1 vertical tracking after a small slop. Does not recompose the caller;
 * it writes [SheetPull.px] which layout/draw observe.
 */
fun Modifier.pulseSheetPull(
    enabled: Boolean,
    pull: SheetPull,
    maxPx: Float,
    searchSlop: Float,
    onGrab: () -> Unit,
    onSettle: (velocityY: Float) -> Unit,
    onSwipeDownSearch: () -> Unit,
): Modifier = composed {
    val settle = rememberUpdatedState(onSettle)
    val search = rememberUpdatedState(onSwipeDownSearch)
    val grab = rememberUpdatedState(onGrab)
    val touchSlop = LocalViewConfiguration.current.touchSlop * 0.45f
    pointerInput(enabled, maxPx, searchSlop) {
        if (!enabled) return@pointerInput
        val slop = touchSlop.coerceAtLeast(4.dp.toPx())
        val tracker = VelocityTracker()
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false)
            tracker.resetTracking()
            tracker.addPosition(down.uptimeMillis, down.position)
            val originY = down.position.y
            val originX = down.position.x
            val startPull = pull.px
            var dragging = false
            var totalDy = 0f
            while (true) {
                val event = awaitPointerEvent(PointerEventPass.Main)
                val change = event.changes.firstOrNull { it.id == down.id } ?: break
                tracker.addPosition(change.uptimeMillis, change.position)
                val dy = change.positionChange().y
                totalDy += dy
                if (!dragging) {
                    val dx = change.position.x - originX
                    if (abs(totalDy) < slop && abs(dx) < slop) {
                        if (!change.pressed) break
                        continue
                    }
                    if (abs(dx) > abs(totalDy)) break
                    dragging = true
                    grab.value()
                }
                if (dragging) {
                    change.consume()
                    pull.px = rubberBand(startPull - (change.position.y - originY), maxPx)
                }
                if (!change.pressed) {
                    if (dragging) {
                        val velocityY = tracker.calculateVelocity().y
                        if (pull.px < 8f && totalDy > searchSlop) {
                            pull.px = 0f
                            search.value()
                        } else {
                            settle.value(velocityY)
                        }
                    }
                    break
                }
            }
        }
    }
}
