package com.folio.launcher.home

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.isOutOfBounds
import androidx.compose.ui.input.pointer.pointerInput
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Double-tap the print for the next Bing. Triple-tap for Ask.
 * The double waits a beat so a third tap can steal it.
 */
fun Modifier.detectPrintTaps(
    enabled: Boolean,
    onDoubleTap: () -> Unit,
    onTripleTap: () -> Unit,
): Modifier = composed {
    val doubleTap = rememberUpdatedState(onDoubleTap)
    val tripleTap = rememberUpdatedState(onTripleTap)
    val scope = rememberCoroutineScope()
    pointerInput(enabled) {
        if (!enabled) return@pointerInput
        val timeout = viewConfiguration.doubleTapTimeoutMillis
        val longPress = viewConfiguration.longPressTimeoutMillis
        val slop = viewConfiguration.touchSlop
        var taps = 0
        var lastUp = 0L
        var pending: Job? = null
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false)
            // The window runs from the last lift to this touch, as the platform's does.
            if (down.uptimeMillis - lastUp > timeout) {
                taps = 0
            } else if (taps == 2) {
                // A third touch in time stops the waiting double: it ends as the triple or as nothing.
                pending?.cancel()
                pending = null
            }
            val origin = down.position
            var moved = false
            while (true) {
                val event = awaitPointerEvent(PointerEventPass.Main)
                val change = event.changes.firstOrNull { it.id == down.id } ?: break
                if ((change.position - origin).getDistance() > slop) moved = true
                if (!change.pressed) {
                    // Held into a long-press (the ringer): not a tap.
                    val held = change.uptimeMillis - down.uptimeMillis >= longPress
                    if (!moved && !held) {
                        taps += 1
                        lastUp = change.uptimeMillis
                        pending?.cancel()
                        pending = null
                        when (taps) {
                            3 -> {
                                taps = 0
                                tripleTap.value()
                            }
                            2 -> {
                                pending = scope.launch {
                                    delay(timeout)
                                    if (taps == 2) {
                                        taps = 0
                                        doubleTap.value()
                                    }
                                }
                            }
                        }
                    } else {
                        pending?.cancel()
                        pending = null
                        taps = 0
                    }
                    break
                }
            }
        }
    }
}

/**
 * Long-press the print (or the quote) for the ringer. A press that travels past four touch slops,
 * whichever way, is a drag and never a hold, however its first few dp leaned (the sheet decides on
 * those alone); nor is one a detector outside this one takes (the sheet's pull, a swipe). A smaller
 * creep is still a hold.
 */
fun Modifier.detectPrintHold(
    enabled: Boolean,
    onHold: (Offset) -> Unit,
): Modifier = composed {
    val hold = rememberUpdatedState(onHold)
    pointerInput(enabled) {
        if (!enabled) return@pointerInput
        // The same give PrintSwipe and the sheet's pull allow.
        val holdSlop = viewConfiguration.touchSlop * 4f
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false)
            // Null: the timeout ran out with the finger still down, near where it landed, and no one else's.
            val letGo = withTimeoutOrNull(viewConfiguration.longPressTimeoutMillis) {
                var gone = false
                while (!gone) {
                    val change = awaitPointerEvent(PointerEventPass.Main).changes.firstOrNull { it.id == down.id }
                    gone = change == null || !change.pressed || change.isConsumed ||
                        change.isOutOfBounds(size, extendedTouchPadding) ||
                        (change.position - down.position).getDistance() > holdSlop ||
                        // Taken on the way out by a detector outside this one.
                        awaitPointerEvent(PointerEventPass.Final).changes.any { it.id == down.id && it.isConsumed }
                }
                true
            }
            if (letGo == null) {
                hold.value(down.position)
                // The rest of the press is the hold's: no swipe, pull or tap after it.
                do {
                    val event = awaitPointerEvent()
                    event.changes.forEach { it.consume() }
                } while (event.changes.any { it.pressed })
            }
        }
    }
}
