package com.folio.launcher.home

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.input.pointer.PointerEventPass
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
        val slop = viewConfiguration.touchSlop
        var taps = 0
        var lastUp = 0L
        var pending: Job? = null
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false)
            val origin = down.position
            var moved = false
            while (true) {
                val event = awaitPointerEvent(PointerEventPass.Main)
                val change = event.changes.firstOrNull { it.id == down.id } ?: break
                if ((change.position - origin).getDistance() > slop) moved = true
                if (!change.pressed) {
                    if (!moved) {
                        val now = change.uptimeMillis
                        if (now - lastUp > timeout) taps = 0
                        taps += 1
                        lastUp = now
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
