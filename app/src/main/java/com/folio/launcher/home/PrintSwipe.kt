package com.folio.launcher.home

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.input.pointer.util.VelocityTracker
import kotlin.math.abs
import kotlin.math.hypot

/** Idle print: swipe left for the AI app, swipe right for Google. Vertical is the sheet. */
fun Modifier.detectPrintSwipe(
    enabled: Boolean,
    onSwipeLeft: () -> Unit,
    onSwipeRight: () -> Unit,
): Modifier = composed {
    val left = rememberUpdatedState(onSwipeLeft)
    val right = rememberUpdatedState(onSwipeRight)
    pointerInput(enabled) {
        if (!enabled) return@pointerInput
        val slop = viewConfiguration.touchSlop * 4f
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false)
            val tracker = VelocityTracker()
            tracker.addPosition(down.uptimeMillis, down.position)
            var dragging = false
            var dx = 0f
            var dy = 0f
            while (true) {
                val event = awaitPointerEvent(PointerEventPass.Main)
                val change = event.changes.firstOrNull { it.id == down.id } ?: break
                val step = change.positionChange()
                dx += step.x
                dy += step.y
                tracker.addPosition(change.uptimeMillis, change.position)
                if (!dragging) {
                    if (hypot(dx, dy) < slop) {
                        if (!change.pressed) break
                        continue
                    }
                    if (abs(dx) > abs(dy)) {
                        dragging = true
                    } else {
                        break
                    }
                }
                change.consume()
                if (!change.pressed) {
                    val threshold = size.width * 0.18f
                    val vx = tracker.calculateVelocity().x
                    if (dx > threshold || vx > 900f) right.value()
                    else if (dx < -threshold || vx < -900f) left.value()
                    break
                }
            }
        }
    }
}
