package com.folio.launcher.home

import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown

/** Quiet ask: two fingers down and up without wandering. One finger is left for Bing and the ringer. */
fun Modifier.detectTwoFingerTap(
    enabled: Boolean,
    onTap: () -> Unit,
): Modifier = composed {
    val tap = rememberUpdatedState(onTap)
    pointerInput(enabled) {
        if (!enabled) return@pointerInput
        val slop = viewConfiguration.touchSlop * 3f
        awaitEachGesture {
            val first = awaitFirstDown(requireUnconsumed = false)
            val origins = mutableMapOf(first.id to first.position)
            var two = false
            var moved = false
            while (true) {
                val event = awaitPointerEvent(PointerEventPass.Main)
                for (change in event.changes) {
                    if (change.pressed) {
                        val origin = origins.getOrPut(change.id) { change.position }
                        if ((change.position - origin).getDistance() > slop) moved = true
                    } else {
                        origins.remove(change.id)
                    }
                }
                if (origins.size >= 2) two = true
                if (two) {
                    event.changes.forEach { it.consume() }
                }
                if (origins.isEmpty()) {
                    if (two && !moved) tap.value()
                    break
                }
            }
        }
    }
}
