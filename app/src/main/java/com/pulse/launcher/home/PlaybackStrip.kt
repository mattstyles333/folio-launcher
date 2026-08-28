package com.pulse.launcher.home

import android.os.SystemClock
import android.view.HapticFeedbackConstants
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pulse.launcher.ui.QuoteAuthorStyle
import com.pulse.launcher.ui.QuoteStyle
import kotlinx.coroutines.delay

@Composable
fun PlaybackStrip(
    line: String,
    title: String,
    artist: String,
    playing: Boolean,
    art: ImageBitmap?,
    positionMs: Long,
    durationMs: Long,
    accent: Color,
    dim: Boolean,
    onPrevious: () -> Unit,
    onPlayPause: () -> Unit,
    onNext: () -> Unit,
    onOpen: () -> Unit,
    onSeek: (Float) -> Unit,
    modifier: Modifier = Modifier,
) {
    val view = LocalView.current
    val ink = accent.copy(alpha = if (dim) 0.62f else 0.94f)
    val muted = accent.copy(alpha = if (dim) 0.42f else 0.62f)
    val titleCopy = title.ifBlank { line }
    val plate = RoundedCornerShape(20.dp)
    fun tap(action: () -> Unit) {
        view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
        action()
    }
    Column(
        modifier
            .widthIn(max = 360.dp)
            .fillMaxWidth()
            .padding(horizontal = 20.dp)
            .clip(plate)
            .background(Color.Black.copy(alpha = if (dim) 0.48f else 0.58f))
            .border(1.dp, Color.White.copy(alpha = 0.16f), plate)
            .padding(horizontal = 14.dp, vertical = 13.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = { tap(onOpen) },
                ),
        ) {
            val artShape = RoundedCornerShape(10.dp)
            if (art != null) {
                Image(
                    bitmap = art,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .size(52.dp)
                        .clip(artShape),
                )
            } else {
                Box(
                    Modifier
                        .size(52.dp)
                        .clip(artShape)
                        .background(Color.White.copy(alpha = 0.06f)),
                )
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    text = titleCopy,
                    style = QuoteStyle.copy(fontSize = 16.sp, lineHeight = 20.sp),
                    color = ink,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (artist.isNotEmpty()) {
                    Spacer(Modifier.height(2.dp))
                    Text(
                        text = artist,
                        style = QuoteAuthorStyle.copy(letterSpacing = 0.5.sp),
                        color = muted,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
        Spacer(Modifier.height(10.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            MediaButton(34.dp, onClick = { tap(onPrevious) }) { prevGlyph(ink) }
            PlayPauseButton(playing = playing, color = ink, onClick = { tap(onPlayPause) })
            MediaButton(34.dp, onClick = { tap(onNext) }) { nextGlyph(ink) }
        }
        if (durationMs > 1_000L) {
            Spacer(Modifier.height(8.dp))
            ProgressHairline(
                playing = playing,
                positionMs = positionMs,
                durationMs = durationMs,
                color = ink,
                onSeek = { fraction ->
                    view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                    onSeek(fraction)
                },
            )
        }
    }
}

@Composable
private fun PlayPauseButton(
    playing: Boolean,
    color: Color,
    onClick: () -> Unit,
) {
    Canvas(
        Modifier
            .size(52.dp)
            .clip(CircleShape)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick,
            ),
    ) {
        drawCircle(
            color = color.copy(alpha = color.alpha * 0.92f),
            radius = size.minDimension / 2f - 1.2.dp.toPx(),
            style = Stroke(width = 1.4.dp.toPx()),
        )
        if (playing) pauseGlyph(color) else playGlyph(color)
    }
}

@Composable
private fun MediaButton(
    size: Dp,
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

@Composable
private fun ProgressHairline(
    playing: Boolean,
    positionMs: Long,
    durationMs: Long,
    color: Color,
    onSeek: (Float) -> Unit,
) {
    var live by remember(positionMs, playing) { mutableFloatStateOf(positionMs.toFloat()) }
    LaunchedEffect(playing, positionMs, durationMs) {
        live = positionMs.toFloat()
        if (!playing || durationMs <= 0L) return@LaunchedEffect
        val origin = SystemClock.elapsedRealtime()
        val base = positionMs
        while (true) {
            delay(200)
            val next = (base + (SystemClock.elapsedRealtime() - origin)).toFloat()
            live = next.coerceAtMost(durationMs.toFloat())
            if (next >= durationMs) break
        }
    }
    val track = color.copy(alpha = color.alpha * 0.22f)
    val fill = color.copy(alpha = color.alpha * 0.92f)
    Canvas(
        Modifier
            .fillMaxWidth()
            .height(16.dp)
            .pointerInput(durationMs) {
                detectTapGestures { offset ->
                    val f = (offset.x / size.width).coerceIn(0f, 1f)
                    onSeek(f)
                }
            },
    ) {
        val y = size.height / 2f
        val stroke = 1.6.dp.toPx()
        val fraction = if (durationMs <= 0L) 0f else (live / durationMs).coerceIn(0f, 1f)
        drawLine(track, Offset(0f, y), Offset(size.width, y), stroke, StrokeCap.Round)
        if (fraction > 0.002f) {
            drawLine(
                fill,
                Offset(0f, y),
                Offset(size.width * fraction, y),
                stroke,
                StrokeCap.Round,
            )
        }
        drawCircle(
            color = fill,
            radius = 2.4.dp.toPx(),
            center = Offset(size.width * fraction, y),
        )
    }
}

private fun DrawScope.playGlyph(color: Color) {
    val path = Path().apply {
        val cx = size.width * 0.54f
        val cy = size.height * 0.5f
        val h = size.height * 0.28f
        moveTo(cx - h * 0.42f, cy - h)
        lineTo(cx + h * 0.72f, cy)
        lineTo(cx - h * 0.42f, cy + h)
        close()
    }
    drawPath(path, color)
}

private fun DrawScope.pauseGlyph(color: Color) {
    val w = size.width * 0.07f
    val h = size.height * 0.32f
    val y = (size.height - h) / 2f
    val r = CornerRadius(w / 2f, w / 2f)
    drawRoundRect(color, Offset(size.width * 0.36f, y), Size(w, h), r)
    drawRoundRect(color, Offset(size.width * 0.57f, y), Size(w, h), r)
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
