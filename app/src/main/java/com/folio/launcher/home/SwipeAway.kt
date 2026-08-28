package com.folio.launcher.home

import androidx.compose.animation.core.animate
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.input.pointer.util.VelocityTracker
import kotlin.math.abs
import kotlin.math.hypot
import kotlinx.coroutines.launch

/** Swipe an icon right to hide it. Vertical motion is left for the sheet and the grid. */
fun Modifier.swipeAwayToHide(key: Any? = Unit, onHide: () -> Unit): Modifier = composed {
    var offsetX by remember(key) { mutableFloatStateOf(0f) }
    val hide = rememberUpdatedState(onHide)
    val scope = rememberCoroutineScope()
    graphicsLayer {
        translationX = offsetX
        alpha = 1f - (offsetX / 240f).coerceIn(0f, 0.85f)
    }.pointerInput(key) {
        val slop = viewConfiguration.touchSlop
        val dismissAt = size.width * 0.38f
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
                    if (dx > abs(dy)) {
                        dragging = true
                    } else {
                        break
                    }
                }
                change.consume()
                offsetX = dx.coerceAtLeast(0f)
                if (!change.pressed) {
                    val flung = tracker.calculateVelocity().x > 900f
                    val start = offsetX
                    val width = size.width.toFloat()
                    if (dx > dismissAt || flung) {
                        scope.launch {
                            animate(start, width + 80f, 0f, sheetSpring()) { value, _ ->
                                offsetX = value
                            }
                            hide.value()
                            offsetX = 0f
                        }
                    } else {
                        scope.launch {
                            animate(start, 0f, 0f, sheetSpring()) { value, _ ->
                                offsetX = value
                            }
                        }
                    }
                    break
                }
            }
        }
    }
}
