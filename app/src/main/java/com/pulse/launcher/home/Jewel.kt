package com.pulse.launcher.home

import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.view.HapticFeedbackConstants
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.drag
import com.pulse.launcher.data.RingerVisual
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.roundToInt

private val Modes = listOf(RingerVisual.Sound, RingerVisual.Vibrate, RingerVisual.Silent)

@Composable
fun Jewel(
    mode: RingerVisual,
    accent: Color,
    onPreview: (RingerVisual) -> Unit,
    onSelect: (RingerVisual) -> Unit,
    onCycle: () -> Unit,
    onLongPress: () -> Unit,
    onHeld: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val view = LocalView.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var held by remember { mutableStateOf(false) }
    val thumb = remember { Animatable(mode.ordinal.toFloat()) }

    LaunchedEffect(mode, held) {
        if (!held) thumb.animateTo(mode.ordinal.toFloat(), thumbSpring())
    }
    val press by animateFloatAsState(
        targetValue = if (held) 1f else 0f,
        animationSpec = spring(dampingRatio = 0.86f, stiffness = Spring.StiffnessMedium),
        label = "jewelPress",
    )

    fun snapTo(index: Int, preview: Boolean) {
        val next = Modes[index.coerceIn(0, 2)]
        tick(view, heavy = false)
        if (preview) {
            scope.launch { thumb.snapTo(index.toFloat()) }
            onPreview(next)
        } else {
            scope.launch { thumb.animateTo(index.toFloat(), thumbSpring()) }
            onSelect(next)
            if (next == RingerVisual.Vibrate) buzz(context)
        }
    }

    Box(
        modifier
            .width(52.dp)
            .height(168.dp)
            .pointerInput(Unit) {
                coroutineScope {
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        var dragged = false
                        var longPressed = false
                        var lastIdx = thumb.value.roundToInt().coerceIn(0, 2)
                        held = true
                        onHeld(true)
                        val longPressJob = launch {
                            delay(viewConfiguration.longPressTimeoutMillis)
                            if (!dragged) {
                                longPressed = true
                                held = false
                                onHeld(false)
                                tick(view, heavy = true)
                                onLongPress()
                            }
                        }
                        drag(down.id) { change ->
                            val dy = change.position.y - down.position.y
                            if (abs(dy) > viewConfiguration.touchSlop) {
                                dragged = true
                                longPressJob.cancel()
                            }
                            if (dragged) {
                                val pad = size.height * 0.12f
                                val frac = ((change.position.y - pad) / (size.height - 2f * pad))
                                    .coerceIn(0f, 1f)
                                val idx = (frac * (Modes.size - 1) + 0.5f).toInt().coerceIn(0, 2)
                                if (idx != lastIdx) {
                                    lastIdx = idx
                                    snapTo(idx, preview = true)
                                }
                            }
                            change.consume()
                        }
                        longPressJob.cancel()
                        if (dragged) {
                            snapTo(lastIdx, preview = false)
                        } else if (!longPressed) {
                            onCycle()
                            tick(view, heavy = false)
                            val cycled = Modes[(mode.ordinal + 1) % 3]
                            if (cycled == RingerVisual.Vibrate) buzz(context)
                        }
                        held = false
                        onHeld(false)
                    }
                }
            },
    ) {
        Canvas(Modifier.matchParentSize()) {
            val cx = size.width * 0.62f
            val pad = size.height * 0.12f
            val span = size.height - 2f * pad
            val trackW = (2.5f + 2.5f * press).dp.toPx()
            val trackColor = accent.copy(alpha = 0.18f + 0.16f * press)
            drawRoundRect(
                color = trackColor,
                topLeft = Offset(cx - trackW / 2f, pad),
                size = Size(trackW, span),
                cornerRadius = CornerRadius(trackW),
            )
            Modes.forEachIndexed { i, visual ->
                val y = pad + i * (span / 2f)
                val selected = i == thumb.value.roundToInt()
                val markAlpha = if (selected) 0f else 0.38f + 0.2f * press
                val color = accent.copy(alpha = markAlpha)
                when (visual) {
                    RingerVisual.Sound -> drawCircle(color, 3.2.dp.toPx(), Offset(cx, y))
                    RingerVisual.Vibrate -> drawCircle(
                        color = color,
                        radius = 4.2.dp.toPx(),
                        center = Offset(cx, y),
                        style = Stroke(1.4.dp.toPx()),
                    )
                    RingerVisual.Silent -> {
                        val w = 9.dp.toPx()
                        val h = 2.2.dp.toPx()
                        drawRoundRect(
                            color = color,
                            topLeft = Offset(cx - w / 2f, y - h / 2f),
                            size = Size(w, h),
                            cornerRadius = CornerRadius(h),
                        )
                    }
                }
            }
            val ty = pad + thumb.value * (span / 2f)
            val thumbR = (7.5f + 2.5f * press).dp.toPx()
            drawCircle(color = accent.copy(alpha = 0.95f), radius = thumbR, center = Offset(cx, ty))
            when (Modes[thumb.value.roundToInt().coerceIn(0, 2)]) {
                RingerVisual.Sound -> Unit
                RingerVisual.Vibrate -> drawCircle(
                    color = Color.Black.copy(alpha = 0.35f),
                    radius = thumbR * 0.38f,
                    center = Offset(cx, ty),
                    style = Stroke(width = 1.6.dp.toPx()),
                )
                RingerVisual.Silent -> {
                    val w = thumbR * 1.1f
                    val h = 2.2.dp.toPx()
                    drawRoundRect(
                        color = Color.Black.copy(alpha = 0.4f),
                        topLeft = Offset(cx - w / 2f, ty - h / 2f),
                        size = Size(w, h),
                        cornerRadius = CornerRadius(h),
                    )
                }
            }
        }
    }
}

private fun thumbSpring() = spring<Float>(
    dampingRatio = 0.78f,
    stiffness = Spring.StiffnessMediumLow,
)

private fun tick(view: android.view.View, heavy: Boolean) {
    val code = when {
        heavy -> HapticFeedbackConstants.LONG_PRESS
        Build.VERSION.SDK_INT >= 34 -> HapticFeedbackConstants.SEGMENT_TICK
        else -> HapticFeedbackConstants.CLOCK_TICK
    }
    view.performHapticFeedback(code)
}

private fun buzz(context: android.content.Context) {
    runCatching {
        val vibrator = context.getSystemService(Vibrator::class.java) ?: return
        vibrator.vibrate(VibrationEffect.createOneShot(36, VibrationEffect.DEFAULT_AMPLITUDE))
    }
}
