package com.pulse.launcher.home

import android.os.SystemClock
import android.view.HapticFeedbackConstants
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pulse.launcher.ui.ClockDateStyle
import com.pulse.launcher.ui.PrintInk
import com.pulse.launcher.ui.QuoteAuthorStyle
import com.pulse.launcher.ui.QuoteStyle
import com.pulse.launcher.ui.VoidBlack
import kotlinx.coroutines.delay

@Composable
fun NowPlayingPage(
    line: String,
    title: String,
    artist: String,
    playing: Boolean,
    art: ImageBitmap?,
    positionMs: Long,
    durationMs: Long,
    hasAccess: Boolean,
    accent: Color,
    onPrevious: () -> Unit,
    onPlayPause: () -> Unit,
    onNext: () -> Unit,
    onSeek: (Float) -> Unit,
    onOpenApp: () -> Unit,
    onAllowAccess: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val view = LocalView.current
    val ink = PrintInk
    val titleCopy = title.ifBlank { line }
    fun tap(action: () -> Unit) {
        view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
        action()
    }
    Box(
        modifier
            .fillMaxSize()
            .background(VoidBlack),
    ) {
        if (art != null) {
            Image(
                bitmap = art,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
            Box(
                Modifier
                    .fillMaxSize()
                    .background(
                        Brush.verticalGradient(
                            0f to Color.Black.copy(alpha = 0.28f),
                            0.45f to Color.Black.copy(alpha = 0.18f),
                            1f to Color.Black.copy(alpha = 0.78f),
                        ),
                    ),
            )
        }
        if (titleCopy.isNotEmpty()) {
            Column(
                Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .statusBarsPadding()
                    .navigationBarsPadding()
                    .padding(horizontal = 28.dp, vertical = 28.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    text = titleCopy,
                    style = QuoteStyle.copy(fontSize = 28.sp, lineHeight = 34.sp),
                    color = ink,
                    textAlign = TextAlign.Center,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                if (artist.isNotEmpty()) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = artist,
                        style = QuoteAuthorStyle.copy(fontSize = 13.sp, letterSpacing = 1.1.sp),
                        color = ink.copy(alpha = 0.62f),
                        textAlign = TextAlign.Center,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Spacer(Modifier.height(28.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    PageButton(48.dp, onClick = { tap(onPrevious) }) { prevGlyph(ink) }
                    Box(
                        Modifier
                            .size(74.dp)
                            .clip(CircleShape)
                            .clickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = null,
                                onClick = { tap(onPlayPause) },
                            ),
                        contentAlignment = Alignment.Center,
                    ) {
                        Canvas(Modifier.fillMaxSize()) {
                            drawCircle(
                                color = ink.copy(alpha = 0.92f),
                                radius = size.minDimension / 2f - 1.6.dp.toPx(),
                                style = Stroke(width = 1.6.dp.toPx()),
                            )
                            if (playing) pauseGlyph(ink) else playGlyph(ink)
                        }
                    }
                    PageButton(48.dp, onClick = { tap(onNext) }) { nextGlyph(ink) }
                }
                if (durationMs > 1_000L) {
                    Spacer(Modifier.height(22.dp))
                    PageProgress(
                        playing = playing,
                        positionMs = positionMs,
                        durationMs = durationMs,
                        color = ink,
                        onSeek = {
                            view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                            onSeek(it)
                        },
                    )
                }
                Spacer(Modifier.height(18.dp))
                Text(
                    "Open Spotify",
                    style = ClockDateStyle.copy(letterSpacing = 1.6.sp, fontSize = 12.sp),
                    color = accent.copy(alpha = 0.72f),
                    modifier = Modifier.clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = { tap(onOpenApp) },
                    ),
                )
            }
        } else {
            Column(
                Modifier
                    .align(Alignment.Center)
                    .padding(horizontal = 36.dp)
                    .statusBarsPadding(),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    if (!hasAccess) "Spotify lives here." else "Nothing playing.",
                    style = QuoteStyle.copy(fontSize = 26.sp, lineHeight = 32.sp),
                    color = PrintInk,
                    textAlign = TextAlign.Center,
                )
                Spacer(Modifier.height(14.dp))
                Text(
                    if (!hasAccess) "Allow Pulse to see the session." else "Open Spotify, play a track, swipe back.",
                    style = ClockDateStyle.copy(letterSpacing = 0.4.sp),
                    color = PrintInk.copy(alpha = 0.5f),
                    textAlign = TextAlign.Center,
                )
                Spacer(Modifier.height(28.dp))
                Text(
                    if (!hasAccess) "Allow" else "Open Spotify",
                    color = accent,
                    fontSize = 15.sp,
                    modifier = Modifier
                        .clip(RoundedCornerShape(14.dp))
                        .background(Color.White.copy(alpha = 0.08f))
                        .clickable {
                            tap(if (!hasAccess) onAllowAccess else onOpenApp)
                        }
                        .padding(horizontal = 22.dp, vertical = 12.dp),
                )
            }
        }
    }
}

@Composable
private fun PageButton(
    size: androidx.compose.ui.unit.Dp,
    onClick: () -> Unit,
    glyph: androidx.compose.ui.graphics.drawscope.DrawScope.() -> Unit,
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
private fun PageProgress(
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
    val track = color.copy(alpha = 0.22f)
    val fill = color.copy(alpha = 0.94f)
    Canvas(
        Modifier
            .fillMaxWidth()
            .height(18.dp)
            .pointerInput(durationMs) {
                detectTapGestures { offset ->
                    onSeek((offset.x / size.width).coerceIn(0f, 1f))
                }
            },
    ) {
        val y = size.height / 2f
        val stroke = 2.dp.toPx()
        val fraction = if (durationMs <= 0L) 0f else (live / durationMs).coerceIn(0f, 1f)
        drawLine(track, Offset(0f, y), Offset(size.width, y), stroke, StrokeCap.Round)
        if (fraction > 0.002f) {
            drawLine(fill, Offset(0f, y), Offset(size.width * fraction, y), stroke, StrokeCap.Round)
        }
        drawCircle(fill, 3.dp.toPx(), Offset(size.width * fraction, y))
    }
}
