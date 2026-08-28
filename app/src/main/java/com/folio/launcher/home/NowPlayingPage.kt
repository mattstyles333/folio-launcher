package com.folio.launcher.home

import android.view.HapticFeedbackConstants
import android.widget.FrameLayout
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.folio.launcher.FolioApp
import com.folio.launcher.data.RingerVisual
import com.folio.launcher.ui.ClockDateStyle
import com.folio.launcher.ui.PrintInk
import com.folio.launcher.ui.QuoteStyle

@Composable
fun NowPlayingPage(
    photo: ImageBitmap?,
    blurred: ImageBitmap?,
    mode: RingerVisual,
    accent: Color,
    widgetId: Int,
    widgetAvailable: Boolean,
    onBindWidget: () -> Unit,
    onOpenApp: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val view = LocalView.current
    fun tap(action: () -> Unit) {
        view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
        action()
    }
    Box(modifier.fillMaxSize()) {
        WallpaperLayer(
            photo = photo,
            blurred = blurred,
            mode = mode,
            accent = accent,
            modifier = Modifier.fillMaxSize(),
        )
        if (widgetId != 0) {
            key(widgetId) {
                SpotifyWidgetCard(
                    widgetId = widgetId,
                    modifier = Modifier
                        .align(Alignment.Center)
                        .fillMaxWidth()
                        .fillMaxHeight(0.5f)
                        .padding(horizontal = 16.dp),
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
                    if (widgetAvailable) "Spotify’s player." else "Spotify lives here.",
                    style = QuoteStyle.copy(fontSize = 26.sp, lineHeight = 32.sp),
                    color = PrintInk,
                    textAlign = TextAlign.Center,
                )
                Spacer(Modifier.height(14.dp))
                Text(
                    if (widgetAvailable) {
                        "Allow Folio to place Spotify’s widget on this page."
                    } else {
                        "Open Spotify, play a track, swipe back."
                    },
                    style = ClockDateStyle.copy(letterSpacing = 0.4.sp),
                    color = PrintInk.copy(alpha = 0.5f),
                    textAlign = TextAlign.Center,
                )
                Spacer(Modifier.height(28.dp))
                Text(
                    if (widgetAvailable) "Show widget" else "Open Spotify",
                    color = accent,
                    fontSize = 15.sp,
                    modifier = Modifier
                        .clip(RoundedCornerShape(14.dp))
                        .background(Color.White.copy(alpha = 0.08f))
                        .clickable {
                            tap(if (widgetAvailable) onBindWidget else onOpenApp)
                        }
                        .padding(horizontal = 22.dp, vertical = 12.dp),
                )
            }
        }
    }
}

@Composable
private fun SpotifyWidgetCard(
    widgetId: Int,
    modifier: Modifier = Modifier,
) {
    val app = LocalContext.current.applicationContext as FolioApp
    BoxWithConstraints(modifier) {
        val widthPx = constraints.maxWidth
        val heightPx = constraints.maxHeight
        AndroidView(
            factory = { ctx ->
                app.spotifyWidget.createView(ctx, widgetId, widthPx, heightPx)
                    ?: FrameLayout(ctx)
            },
            modifier = Modifier.fillMaxSize(),
        )
    }
}
