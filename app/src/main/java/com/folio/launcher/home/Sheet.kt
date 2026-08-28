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

/** Finger moving right (positive X) opens the player page. */
fun pageShouldOpen(pull: Float, max: Float, velocityX: Float): Boolean {
    return if (abs(velocityX) > 800f) velocityX > 0f else pull > max * 0.2f
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
fun Modifier.folioSheetPull(
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

/**
 * After slop, lock to vertical (app sheet) or horizontal (player page).
 * Swipe right opens the player; swipe left closes it.
 */
fun Modifier.folioHomeGestures(
    enabled: Boolean,
    sheet: SheetPull,
    page: SheetPull,
    maxSheetPx: Float,
    maxPagePx: Float,
    searchSlop: Float,
    onGrabSheet: () -> Unit,
    onSettleSheet: (Float) -> Unit,
    onGrabPage: () -> Unit,
    onSettlePage: (Float) -> Unit,
    onSwipeDownSearch: () -> Unit,
): Modifier = composed {
    val settleSheet = rememberUpdatedState(onSettleSheet)
    val settlePage = rememberUpdatedState(onSettlePage)
    val search = rememberUpdatedState(onSwipeDownSearch)
    val grabSheet = rememberUpdatedState(onGrabSheet)
    val grabPage = rememberUpdatedState(onGrabPage)
    val touchSlop = LocalViewConfiguration.current.touchSlop * 0.45f
    pointerInput(enabled, maxSheetPx, maxPagePx, searchSlop) {
        if (!enabled) return@pointerInput
        val slop = touchSlop.coerceAtLeast(4.dp.toPx())
        val tracker = VelocityTracker()
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false)
            tracker.resetTracking()
            tracker.addPosition(down.uptimeMillis, down.position)
            val originY = down.position.y
            val originX = down.position.x
            val startSheet = sheet.px
            val startPage = page.px
            var axis = 0
            var totalDy = 0f
            var totalDx = 0f
            while (true) {
                val event = awaitPointerEvent(PointerEventPass.Main)
                val change = event.changes.firstOrNull { it.id == down.id } ?: break
                tracker.addPosition(change.uptimeMillis, change.position)
                val dy = change.positionChange().y
                val dx = change.positionChange().x
                totalDy += dy
                totalDx += dx
                if (axis == 0) {
                    if (abs(totalDy) < slop && abs(totalDx) < slop) {
                        if (!change.pressed) break
                        continue
                    }
                    val vertical = abs(totalDy) >= abs(totalDx)
                    when {
                        sheet.px > 12f || (vertical && page.px < 12f && !page.locked) -> {
                            axis = 1
                            grabSheet.value()
                        }
                        !vertical && (page.px > 12f || totalDx > 0f) && sheet.px < 12f && !sheet.locked -> {
                            axis = 2
                            grabPage.value()
                        }
                        else -> break
                    }
                }
                if (axis == 1) {
                    change.consume()
                    sheet.px = rubberBand(startSheet - (change.position.y - originY), maxSheetPx)
                } else if (axis == 2) {
                    change.consume()
                    page.px = rubberBand(startPage + (change.position.x - originX), maxPagePx)
                }
                if (!change.pressed) {
                    when (axis) {
                        1 -> {
                            val velocityY = tracker.calculateVelocity().y
                            if (sheet.px < 8f && totalDy > searchSlop) {
                                sheet.px = 0f
                                search.value()
                            } else {
                                settleSheet.value(velocityY)
                            }
                        }
                        2 -> settlePage.value(tracker.calculateVelocity().x)
                    }
                    break
                }
            }
        }
    }
}
