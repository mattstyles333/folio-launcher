package com.folio.launcher.home

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onLongClick
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.folio.launcher.data.RingerVisual
import com.folio.launcher.ui.ChargeGreen
import com.folio.launcher.ui.ClockDateStyle
import com.folio.launcher.ui.PrintInk
import com.folio.launcher.ui.QuoteAuthorStyle
import com.folio.launcher.ui.QuoteStyle
import com.folio.launcher.ui.printShadow

const val LowBatteryFraction = 0.15f

fun chargeHairlineVisible(charging: Boolean, fraction: Float): Boolean =
    charging || fraction <= LowBatteryFraction

/** Green only while charging on Sound; PrintInk otherwise, low and unplugged included. */
fun chargeOvalColor(charging: Boolean, look: RingerVisual): Color =
    if (charging && look == RingerVisual.Sound) ChargeGreen else PrintInk

/** At 100% the oval is closed: round caps would overlap into a bump at 12 o'clock. */
fun chargeCap(sweep: Float): StrokeCap = if (sweep >= 359.5f) StrokeCap.Butt else StrokeCap.Round

@Composable
fun ClockCluster(
    showClock: Boolean,
    dimClock: Boolean,
    showCharge: Boolean,
    charge: Float,
    chargeColor: Color,
    quote: String,
    quoteAuthor: String,
    lookName: String?,
    caption: String,
    captionBusy: Boolean,
    onClockTap: () -> Unit,
    onClockLongPress: () -> Unit,
    onPrintLongPress: (Offset) -> Unit,
    modifier: Modifier = Modifier,
) {
    var originInParent by remember { mutableStateOf(Offset.Zero) }
    val shadow = printShadow()
    Column(
        modifier.onGloballyPositioned { originInParent = it.positionInParent() },
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        if (lookName == null && quote.isNotEmpty()) {
            Text(
                text = "“$quote”",
                style = QuoteStyle.copy(shadow = shadow),
                color = PrintInk.copy(alpha = if (dimClock) 0.78f else 1f),
                textAlign = TextAlign.Center,
                // Two lines cut about a fifth of the bank at 16sp on the S23; every quote fits in three.
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .widthIn(max = 340.dp)
                    .padding(horizontal = 28.dp)
                    .pointerInput(onPrintLongPress) {
                        detectTapGestures(
                            onLongPress = { local -> onPrintLongPress(originInParent + local) },
                        )
                    },
            )
            if (quoteAuthor.isNotEmpty()) {
                Spacer(Modifier.height(4.dp))
                Text(
                    text = quoteAuthor,
                    style = QuoteAuthorStyle.copy(shadow = shadow),
                    color = PrintInk.copy(alpha = if (dimClock) 0.52f else 0.78f),
                )
            }
            Spacer(Modifier.height(10.dp))
        }
        Box(contentAlignment = Alignment.Center) {
            if (showClock && showCharge) {
                ChargeHairline(
                    color = chargeColor,
                    fraction = charge,
                    modifier = Modifier.matchParentSize(),
                )
            }
            // Hidden, the clock keeps its slot and long-press: it is the only way into Settings.
            // TalkBack doesn't read the hidden time, but still finds Settings there.
            ClockDisplay(
                dim = dimClock,
                onTap = onClockTap,
                onLongPress = onClockLongPress,
                modifier = Modifier
                    .padding(horizontal = 28.dp, vertical = 20.dp)
                    .testTag("clock")
                    .then(
                        if (showClock) {
                            Modifier
                        } else {
                            Modifier.alpha(0f).clearAndSetSemantics {
                                contentDescription = "Settings"
                                onLongClick(label = "Open Settings") {
                                    onClockLongPress()
                                    true
                                }
                            }
                        },
                    ),
            )
        }
        if (lookName != null) {
            Spacer(Modifier.height(10.dp))
            Text(
                text = lookName,
                style = ClockDateStyle.copy(shadow = shadow),
                color = PrintInk.copy(alpha = 0.88f),
                letterSpacing = 3.sp,
            )
        }
        // The caption fades as it clears (two seconds after it shows), keeping its last words while
        // it goes; the hint below slides up rather than jumps.
        var lastCaption by remember { mutableStateOf(caption) }
        SideEffect {
            if (caption.isNotEmpty()) lastCaption = caption
        }
        AnimatedVisibility(
            visible = lookName == null && caption.isNotEmpty(),
            exit = fadeOut(tween(400)) + shrinkVertically(),
        ) {
            Text(
                text = caption.ifEmpty { lastCaption },
                style = ClockDateStyle.copy(shadow = shadow),
                color = PrintInk.copy(alpha = if (captionBusy) 0.58f else 0.82f),
                textAlign = TextAlign.Center,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(start = 28.dp, top = 10.dp, end = 28.dp),
            )
        }
    }
}

@Composable
private fun ChargeHairline(
    color: Color,
    fraction: Float,
    modifier: Modifier = Modifier,
) {
    val sweep by animateFloatAsState(
        targetValue = 360f * fraction.coerceIn(0f, 1f),
        animationSpec = tween(420),
        label = "charge",
    )
    Canvas(modifier) {
        val stroke = 1.6.dp.toPx()
        val pad = stroke / 2f + 1.dp.toPx()
        val arcSize = Size(size.width - pad * 2f, size.height - pad * 2f)
        val topLeft = Offset(pad, pad)
        val cap = chargeCap(sweep)
        drawArc(
            color = color.copy(alpha = 0.22f),
            startAngle = -90f,
            sweepAngle = 360f,
            useCenter = false,
            topLeft = topLeft,
            size = arcSize,
            style = Stroke(width = stroke, cap = StrokeCap.Butt),
        )
        if (sweep > 0.5f) {
            drawArc(
                color = color.copy(alpha = 0.95f),
                startAngle = -90f,
                sweepAngle = sweep,
                useCenter = false,
                topLeft = topLeft,
                size = arcSize,
                style = Stroke(width = stroke, cap = cap),
            )
        }
    }
}
