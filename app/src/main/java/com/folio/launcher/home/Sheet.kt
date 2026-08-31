package com.folio.launcher.home

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
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import kotlin.math.abs
import kotlinx.coroutines.Job

/** Pull of the app sheet in pixels. Read this from layout/draw, not from composition. */
class SheetPull {
    var px by mutableFloatStateOf(0f)
    /** Full height: the grid may scroll. Do not read [px] from composition. */
    var locked by mutableStateOf(false)
    /** Any non-closed rest (peek or full). Updated on settle, not during the drag. */
    var showing by mutableStateOf(false)
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

/** Two drawer rows plus a sliver of the next — thumb-zone rest. */
const val SheetPeekRowCount = 2.28f

const val SheetFlingStep = 800f
const val SheetFlingSkip = 2200f

fun sheetPeekPx(rowPx: Float, maxPx: Float): Float {
    if (rowPx <= 0f || maxPx <= 0f) return 0f
    val ideal = rowPx * SheetPeekRowCount
    val lo = rowPx * 1.7f
    val hi = maxPx * 0.45f
    if (hi <= 0f) return 0f
    if (lo >= hi) return hi
    return ideal.coerceIn(lo, hi)
}

fun sheetIsFull(target: Float, max: Float): Boolean = target >= max - 1f

/**
 * Three rests: closed, peek (most-used, thumb reach), full.
 * A swipe or fling from idle lands on peek — opening never skips, so a
 * casual flick does not slam the full grid. A hard fling down from above
 * peek still closes. Slow release snaps to the nearest rest.
 */
fun sheetSettleTarget(
    pull: Float,
    peek: Float,
    max: Float,
    velocityY: Float,
    stepVelocity: Float = SheetFlingStep,
    skipVelocity: Float = SheetFlingSkip,
): Float {
    val lo = 0f
    val hi = max.coerceAtLeast(lo)
    val mid = peek.coerceIn(lo, hi)
    val speed = abs(velocityY)
    val opening = -velocityY > 0f
    if (!opening && speed >= skipVelocity && pull > mid + 8f) return lo
    if (speed >= stepVelocity) {
        return if (opening) {
            if (pull + 8f < mid) mid else hi
        } else {
            if (pull - 8f > mid) mid else lo
        }
    }
    val dLo = abs(pull - lo)
    val dMid = abs(pull - mid)
    val dHi = abs(pull - hi)
    return when {
        dMid <= dLo && dMid <= dHi -> mid
        dHi <= dLo -> hi
        else -> lo
    }
}

fun sheetSpring() = spring<Float>(
    dampingRatio = 0.88f,
    stiffness = 400f,
    visibilityThreshold = 0.5f,
)

/**
 * 1:1 vertical tracking after a small slop. Does not recompose the caller;
 * it writes [SheetPull.px] which layout/draw observe.
 */
fun Modifier.folioSheetPull(
    enabled: Boolean,
    pull: SheetPull,
    maxPx: Float,
    onGrab: () -> Unit,
    onSettle: (velocityY: Float) -> Unit,
    onSwipeDownShade: () -> Unit,
): Modifier = composed {
    val settle = rememberUpdatedState(onSettle)
    val shade = rememberUpdatedState(onSwipeDownShade)
    val grab = rememberUpdatedState(onGrab)
    val touchSlop = LocalViewConfiguration.current.touchSlop * 0.45f
    val shadeSlop = with(LocalDensity.current) { 56.dp.toPx() }
    pointerInput(enabled, maxPx, shadeSlop) {
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
            var shading = false
            var totalDy = 0f
            while (true) {
                val event = awaitPointerEvent(PointerEventPass.Main)
                val change = event.changes.firstOrNull { it.id == down.id } ?: break
                tracker.addPosition(change.uptimeMillis, change.position)
                val dy = change.positionChange().y
                totalDy += dy
                if (!dragging && !shading) {
                    val pressed = event.changes.count { it.pressed }
                    if (pressed >= 2) break
                    val dx = change.position.x - originX
                    if (abs(totalDy) < slop && abs(dx) < slop) {
                        if (!change.pressed) break
                        continue
                    }
                    if (abs(dx) > abs(totalDy)) break
                    if (startPull < 8f && totalDy > 0f) {
                        shading = true
                    } else {
                        dragging = true
                        grab.value()
                    }
                }
                if (dragging) {
                    change.consume()
                    pull.px = rubberBand(startPull - (change.position.y - originY), maxPx)
                }
                if (!change.pressed) {
                    if (dragging) {
                        settle.value(tracker.calculateVelocity().y)
                    } else if (shading && (change.position.y - originY) > shadeSlop) {
                        shade.value()
                    }
                    break
                }
            }
        }
    }
}
