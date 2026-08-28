package com.pulse.launcher.home

import android.view.HapticFeedbackConstants
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pulse.launcher.ui.ClockDateStyle

@Composable
fun PlaybackStrip(
    line: String,
    playing: Boolean,
    art: ImageBitmap?,
    accent: Color,
    dim: Boolean,
    onPrevious: () -> Unit,
    onPlayPause: () -> Unit,
    onNext: () -> Unit,
    onOpen: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val view = LocalView.current
    val ink = accent.copy(alpha = if (dim) 0.42f else 0.88f)
    val muted = accent.copy(alpha = if (dim) 0.32f else 0.62f)
    val shadow = Shadow(Color.Black.copy(alpha = 0.70f), Offset(0f, 1f), 0.8f)
    fun tap(action: () -> Unit) {
        view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
        action()
    }
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = { tap(onOpen) },
                )
                .padding(horizontal = 28.dp),
        ) {
            if (art != null) {
                Image(
                    bitmap = art,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .size(22.dp)
                        .clip(CircleShape),
                )
                Spacer(Modifier.size(8.dp))
            }
            Text(
                text = line,
                style = ClockDateStyle.copy(shadow = shadow, fontSize = 13.sp, letterSpacing = 0.4.sp),
                color = muted,
                textAlign = TextAlign.Center,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.widthIn(max = 280.dp),
            )
        }
        Spacer(Modifier.height(6.dp))
        Row(
            horizontalArrangement = Arrangement.spacedBy(18.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            MediaButton(onClick = { tap(onPrevious) }) { prevGlyph(ink) }
            MediaButton(size = 46.dp, onClick = { tap(onPlayPause) }) {
                if (playing) pauseGlyph(ink) else playGlyph(ink)
            }
            MediaButton(onClick = { tap(onNext) }) { nextGlyph(ink) }
        }
    }
}

@Composable
private fun MediaButton(
    size: androidx.compose.ui.unit.Dp = 40.dp,
    onClick: () -> Unit,
    glyph: DrawScope.() -> Unit,
) {
    Canvas(
        Modifier
            .size(size)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick,
            ),
    ) { glyph() }
}

private fun DrawScope.playGlyph(color: Color) {
    val path = Path().apply {
        moveTo(size.width * 0.34f, size.height * 0.22f)
        lineTo(size.width * 0.78f, size.height * 0.5f)
        lineTo(size.width * 0.34f, size.height * 0.78f)
        close()
    }
    drawPath(path, color)
}

private fun DrawScope.pauseGlyph(color: Color) {
    val w = size.width * 0.12f
    val h = size.height * 0.48f
    val y = (size.height - h) / 2f
    val r = CornerRadius(w / 2f, w / 2f)
    drawRoundRect(color, Offset(size.width * 0.32f, y), Size(w, h), r)
    drawRoundRect(color, Offset(size.width * 0.56f, y), Size(w, h), r)
}

private fun DrawScope.prevGlyph(color: Color) {
    val bar = size.width * 0.08f
    drawRoundRect(
        color = color,
        topLeft = Offset(size.width * 0.24f, size.height * 0.28f),
        size = Size(bar, size.height * 0.44f),
        cornerRadius = CornerRadius(bar / 2f, bar / 2f),
    )
    val path = Path().apply {
        moveTo(size.width * 0.72f, size.height * 0.24f)
        lineTo(size.width * 0.36f, size.height * 0.5f)
        lineTo(size.width * 0.72f, size.height * 0.76f)
        close()
    }
    drawPath(path, color)
}

private fun DrawScope.nextGlyph(color: Color) {
    val bar = size.width * 0.08f
    val path = Path().apply {
        moveTo(size.width * 0.28f, size.height * 0.24f)
        lineTo(size.width * 0.64f, size.height * 0.5f)
        lineTo(size.width * 0.28f, size.height * 0.76f)
        close()
    }
    drawPath(path, color)
    drawRoundRect(
        color = color,
        topLeft = Offset(size.width * 0.68f, size.height * 0.28f),
        size = Size(bar, size.height * 0.44f),
        cornerRadius = CornerRadius(bar / 2f, bar / 2f),
    )
}
