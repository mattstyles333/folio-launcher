package com.folio.launcher.home

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.folio.launcher.ui.ClockDateStyle
import com.folio.launcher.ui.PrintInk
import com.folio.launcher.ui.QuoteAuthorStyle
import com.folio.launcher.ui.QuoteStyle

@Composable
fun ClockCluster(
    showClock: Boolean,
    accent: Color,
    dimClock: Boolean,
    charging: Boolean,
    charge: Float,
    quote: String,
    quoteAuthor: String,
    lookName: String?,
    caption: String,
    captionBusy: Boolean,
    onClockTap: () -> Unit,
    onClockLongPress: () -> Unit,
    haloSize: Dp = 220.dp,
    modifier: Modifier = Modifier,
) {
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        if (showClock) {
            Box(contentAlignment = Alignment.Center) {
                if (charging) {
                    ChargeHairline(
                        accent = accent,
                        fraction = charge,
                        modifier = Modifier.size(haloSize),
                    )
                }
                ClockDisplay(
                    accent = accent,
                    dim = dimClock,
                    onTap = onClockTap,
                    onLongPress = onClockLongPress,
                )
            }
        }
        if (lookName != null) {
            Spacer(Modifier.height(10.dp))
            Text(
                text = lookName,
                style = ClockDateStyle,
                color = accent.copy(alpha = 0.72f),
                letterSpacing = 3.sp,
            )
        } else if (quote.isNotEmpty()) {
            Spacer(Modifier.height(12.dp))
            Text(
                text = "“$quote”",
                style = QuoteStyle.copy(
                    shadow = Shadow(
                        color = Color.Black.copy(alpha = 0.72f),
                        offset = Offset(0f, 1f),
                        blurRadius = 0.8f,
                    ),
                ),
                color = accent.copy(alpha = if (dimClock) 0.52f else 0.94f),
                textAlign = TextAlign.Center,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(horizontal = 40.dp),
            )
            if (quoteAuthor.isNotEmpty()) {
                Spacer(Modifier.height(5.dp))
                Text(
                    text = quoteAuthor,
                    style = QuoteAuthorStyle.copy(
                        shadow = Shadow(
                            color = Color.Black.copy(alpha = 0.70f),
                            offset = Offset(0f, 1f),
                            blurRadius = 0.8f,
                        ),
                    ),
                    color = accent.copy(alpha = if (dimClock) 0.36f else 0.58f),
                )
            }
        }
        if (lookName == null && caption.isNotEmpty()) {
            Spacer(Modifier.height(10.dp))
            Text(
                text = caption,
                style = ClockDateStyle,
                color = PrintInk.copy(alpha = if (captionBusy) 0.42f else 0.62f),
                textAlign = TextAlign.Center,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(horizontal = 28.dp),
            )
        }
    }
}

@Composable
private fun ChargeHairline(
    accent: Color,
    fraction: Float,
    modifier: Modifier = Modifier,
) {
    val sweep by animateFloatAsState(
        targetValue = 260f * fraction.coerceIn(0f, 1f),
        animationSpec = tween(420),
        label = "charge",
    )
    Canvas(modifier) {
        val stroke = 1.6.dp.toPx()
        val pad = stroke / 2f + 1.dp.toPx()
        val arcSize = Size(size.width - pad * 2f, size.height - pad * 2f)
        val topLeft = Offset(pad, pad)
        drawArc(
            color = accent.copy(alpha = 0.18f),
            startAngle = 140f,
            sweepAngle = 260f,
            useCenter = false,
            topLeft = topLeft,
            size = arcSize,
            style = Stroke(width = stroke, cap = StrokeCap.Round),
        )
        if (sweep > 0.5f) {
            drawArc(
                color = accent.copy(alpha = 0.88f),
                startAngle = 140f,
                sweepAngle = sweep,
                useCenter = false,
                topLeft = topLeft,
                size = arcSize,
                style = Stroke(width = stroke, cap = StrokeCap.Round),
            )
        }
    }
}
