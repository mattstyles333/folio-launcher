package com.folio.launcher.home

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
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import com.folio.launcher.ui.PrintInk

@Composable
fun PlaybackStrip(
    playing: Boolean,
    art: ImageBitmap?,
    @Suppress("UNUSED_PARAMETER") accent: Color,
    dim: Boolean,
    onPrevious: () -> Unit,
    onPlayPause: () -> Unit,
    onNext: () -> Unit,
    onOpen: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val view = LocalView.current
    val skipInk = PrintInk.copy(alpha = if (dim) 0.78f else 1f)
    val playInk = PrintInk.copy(alpha = if (dim) 0.88f else 1f)
    fun tap(action: () -> Unit) {
        view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
        action()
    }
    Row(
        modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(28.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        SkipButton(onClick = { tap(onPrevious) }) { prevGlyph(skipInk) }
        ArtButton(
            playing = playing,
            art = art,
            ink = playInk,
            onPlayPause = { tap(onPlayPause) },
            onOpen = { tap(onOpen) },
        )
        SkipButton(onClick = { tap(onNext) }) { nextGlyph(skipInk) }
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
            Box(Modifier.size(52.dp).background(Color.Black.copy(alpha = 0.42f)))
            Canvas(Modifier.size(26.dp)) { playGlyph(ink) }
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
            .size(48.dp)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick,
            ),
    ) { glyph() }
}

internal fun DrawScope.playGlyph(color: Color) {
    val path = Path().apply {
        val cx = size.width * 0.54f
        val cy = size.height * 0.5f
        val h = size.height * 0.30f
        moveTo(cx - h * 0.46f, cy - h)
        lineTo(cx + h * 0.78f, cy)
        lineTo(cx - h * 0.46f, cy + h)
        close()
    }
    strokeGlyph(color, path)
}

internal fun DrawScope.pauseGlyph(color: Color) {
    val w = size.width * 0.09f
    val h = size.height * 0.36f
    val y = (size.height - h) / 2f
    val r = CornerRadius(w / 2f, w / 2f)
    val left = Offset(size.width * 0.34f, y)
    val right = Offset(size.width * 0.57f, y)
    val bar = Size(w, h)
    val dy = 1.2.dp.toPx()
    val shadow = Color.Black.copy(alpha = 0.72f)
    translate(0f, dy) {
        drawRoundRect(shadow, left, bar, r)
        drawRoundRect(shadow, right, bar, r)
    }
    drawRoundRect(color, left, bar, r)
    drawRoundRect(color, right, bar, r)
}

internal fun DrawScope.prevGlyph(color: Color) {
    val top = size.height * 0.26f
    val bot = size.height * 0.74f
    val mid = size.height * 0.5f
    val path = Path().apply {
        moveTo(size.width * 0.30f, top)
        lineTo(size.width * 0.30f, bot)
        moveTo(size.width * 0.78f, top)
        lineTo(size.width * 0.40f, mid)
        lineTo(size.width * 0.78f, bot)
    }
    strokeGlyph(color, path)
}

internal fun DrawScope.nextGlyph(color: Color) {
    val top = size.height * 0.26f
    val bot = size.height * 0.74f
    val mid = size.height * 0.5f
    val path = Path().apply {
        moveTo(size.width * 0.22f, top)
        lineTo(size.width * 0.60f, mid)
        lineTo(size.width * 0.22f, bot)
        moveTo(size.width * 0.70f, top)
        lineTo(size.width * 0.70f, bot)
    }
    strokeGlyph(color, path)
}

private fun DrawScope.strokeGlyph(color: Color, path: Path) {
    val stroke = Stroke(
        width = 2.4.dp.toPx(),
        cap = StrokeCap.Round,
        join = StrokeJoin.Round,
    )
    val dy = 1.2.dp.toPx()
    translate(0f, dy) {
        drawPath(path, Color.Black.copy(alpha = 0.72f), style = stroke)
    }
    drawPath(path, color, style = stroke)
}
