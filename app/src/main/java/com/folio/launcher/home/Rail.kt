package com.folio.launcher.home

import android.view.HapticFeedbackConstants
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.onLongClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.folio.launcher.data.LaunchableApp
import com.folio.launcher.data.RailSlot
import com.folio.launcher.ui.AppIcon
import kotlin.math.abs
import kotlin.math.roundToInt

@Composable
fun Rail(
    slots: List<RailSlot>,
    iconSaturation: Float,
    onLaunch: (LaunchableApp) -> Unit,
    onPinRequest: (Int) -> Unit,
    onReorder: (Int, Int) -> Unit,
    onHide: (LaunchableApp) -> Unit,
    modifier: Modifier = Modifier,
    /** Open, pin and hide as accessibility actions; off while an overlay covers the rail and takes its taps. */
    accessibilityActions: Boolean = true,
) {
    val view = LocalView.current
    // Pointer deltas run left to right; slot order (and offset) follow the layout direction.
    val dir = if (LocalLayoutDirection.current == LayoutDirection.Rtl) -1f else 1f
    var widthPx by remember { mutableIntStateOf(0) }
    var dragging by remember { mutableStateOf<Int?>(null) }
    var dragX by remember { mutableFloatStateOf(0f) }
    var origin by remember { mutableIntStateOf(0) }
    var moved by remember { mutableStateOf(false) }

    Row(
        modifier
            .fillMaxWidth()
            .height(80.dp)
            .onSizeChanged { widthPx = it.width }
            .padding(horizontal = 28.dp),
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val slotWidth = if (slots.isEmpty()) 1f else widthPx / slots.size.toFloat()
        slots.forEachIndexed { index, slot ->
            val isDragging = dragging == index
            val app = slot.app
            Box(
                Modifier
                    .size(56.dp)
                    .offset {
                        if (isDragging) IntOffset(dragX.roundToInt(), 0) else IntOffset.Zero
                    }
                    .graphicsLayer {
                        scaleX = if (isDragging) 1.08f else 1f
                        scaleY = if (isDragging) 1.08f else 1f
                    }
                    // Tap, hold and swipe are pointer input only: assistive tech gets them as actions.
                    .semantics(mergeDescendants = true) {
                        if (!accessibilityActions) return@semantics
                        if (app != null) {
                            role = Role.Button
                            onClick(label = "Open") {
                                onLaunch(app)
                                true
                            }
                            customActions = listOf(
                                CustomAccessibilityAction("Hide from home") {
                                    onHide(app)
                                    true
                                },
                            )
                        } else {
                            contentDescription = "Empty slot"
                        }
                        onLongClick(label = "Pin") {
                            onPinRequest(index)
                            true
                        }
                    }
                    .then(if (app != null) Modifier.swipeAwayToHide(app.key) { onHide(app) } else Modifier)
                    .pointerInput(app?.key) {
                        detectTapGestures(onTap = { app?.let(onLaunch) })
                    }
                    .pointerInput(index, slotWidth, dir) {
                        detectDragGesturesAfterLongPress(
                            onDragStart = {
                                view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                                dragging = index
                                origin = index
                                dragX = 0f
                                moved = false
                            },
                            onDragEnd = {
                                if (!moved) onPinRequest(origin)
                                dragging = null
                                dragX = 0f
                                moved = false
                            },
                            onDragCancel = {
                                dragging = null
                                dragX = 0f
                                moved = false
                            },
                            onDrag = { change, amount ->
                                change.consume()
                                dragX += amount.x * dir
                                // A finger rolls a little as it lifts; that is still a hold to pin.
                                if (abs(dragX) > viewConfiguration.touchSlop) moved = true
                                val from = dragging ?: index
                                val target = (origin + (dragX / slotWidth.coerceAtLeast(1f)))
                                    .roundToInt()
                                    .coerceIn(0, slots.lastIndex)
                                if (target != from && moved) {
                                    onReorder(from, target)
                                    dragging = target
                                    origin = target
                                    dragX -= (target - from) * slotWidth
                                }
                            },
                        )
                    },
                contentAlignment = Alignment.Center,
            ) {
                if (app != null) {
                    AppIcon(
                        bitmap = app.icon,
                        contentDescription = app.label,
                        saturation = iconSaturation,
                        paused = app.paused,
                        modifier = Modifier
                            .size(52.dp)
                            .clip(CircleShape),
                    )
                } else {
                    Box(
                        Modifier
                            .size(44.dp)
                            .clip(CircleShape)
                            .background(Color.White.copy(alpha = 0.08f)),
                    )
                }
            }
        }
    }
}
