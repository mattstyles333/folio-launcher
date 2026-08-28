package com.pulse.launcher.home

import android.view.HapticFeedbackConstants
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp

@Composable
fun PlaybackStrip(
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
    val ink = accent.copy(alpha = if (dim) 0.55f else 0.92f)
    fun tap(action: () -> Unit) {
        view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
        action()
    }
    Row(
        modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(28.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        SkipButton(onClick = { tap(onPrevious) }) { prevGlyph(ink) }
        ArtButton(
            playing = playing,
            art = art,
            ink = ink,
            onPlayPause = { tap(onPlayPause) },
            onOpen = { tap(onOpen) },
        )
        SkipButton(onClick = { tap(onNext) }) { nextGlyph(ink) }
    }
}

@Composable
private fun ArtButton(
    playing: Boolean,
    art: ImageBitmap?,
    ink: Color,
    onPlayPause: () -> Unit,
    onOpen: () -> Unit,
) {
    val shape = RoundedCornerShape(12.dp)
    Box(
        Modifier
            .size(52.dp)
            .clip(shape)
            .pointerInput(playing) {
                detectTapGestures(
                    onLongPress = { onOpen() },
                    onTap = { onPlayPause() },
                )
            },
        contentAlignment = Alignment.Center,
    ) {
        if (art != null) {
            Image(
                bitmap = art,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.size(52.dp),
            )
        } else {
            Box(Modifier.size(52.dp).background(Color.White.copy(alpha = 0.10f)))
        }
        if (!playing) {
            Box(Modifier.size(52.dp).background(Color.Black.copy(alpha = 0.38f)))
            Canvas(Modifier.size(22.dp)) { playGlyph(ink) }
        }
    }
}

@Composable
private fun SkipButton(
    onClick: () -> Unit,
    glyph: DrawScope.() -> Unit,
) {
    Canvas(
        Modifier
            .size(40.dp)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick,
            ),
    ) { glyph() }
}

private fun DrawScope.playGlyph(color: Color) {
    val path = Path().apply {
        val cx = size.width * 0.54f
        val cy = size.height * 0.5f
        val h = size.height * 0.32f
        moveTo(cx - h * 0.42f, cy - h)
        lineTo(cx + h * 0.72f, cy)
        lineTo(cx - h * 0.42f, cy + h)
        close()
    }
    drawPath(path, color)
}

private fun DrawScope.prevGlyph(color: Color) {
    val bar = size.width * 0.07f
    drawRoundRect(
        color = color,
        topLeft = Offset(size.width * 0.22f, size.height * 0.30f),
        size = Size(bar, size.height * 0.40f),
        cornerRadius = CornerRadius(bar / 2f, bar / 2f),
    )
    val path = Path().apply {
        moveTo(size.width * 0.78f, size.height * 0.26f)
        lineTo(size.width * 0.34f, size.height * 0.5f)
        lineTo(size.width * 0.78f, size.height * 0.74f)
        close()
    }
    drawPath(path, color)
}

private fun DrawScope.nextGlyph(color: Color) {
    val bar = size.width * 0.07f
    val path = Path().apply {
        moveTo(size.width * 0.22f, size.height * 0.26f)
        lineTo(size.width * 0.66f, size.height * 0.5f)
        lineTo(size.width * 0.22f, size.height * 0.74f)
        close()
    }
    drawPath(path, color)
    drawRoundRect(
        color = color,
        topLeft = Offset(size.width * 0.71f, size.height * 0.30f),
        size = Size(bar, size.height * 0.40f),
        cornerRadius = CornerRadius(bar / 2f, bar / 2f),
    )
}
