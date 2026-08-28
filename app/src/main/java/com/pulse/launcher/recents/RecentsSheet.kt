package com.pulse.launcher.recents

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pulse.launcher.data.LaunchableApp
import com.pulse.launcher.data.Ranking
import com.pulse.launcher.data.RecentItem
import com.pulse.launcher.ui.PrintInk
import kotlin.math.abs
import kotlin.math.roundToInt

fun revealCountForBand(band: Int): Int = when (band) {
    0 -> 0
    1 -> 4
    2 -> 12
    3 -> 28
    else -> Int.MAX_VALUE
}

@Composable
fun RecentsBody(
    recents: List<RecentItem>,
    reveal: List<LaunchableApp>,
    band: Int,
    accent: Color,
    blurred: ImageBitmap?,
    onLaunch: (LaunchableApp) -> Unit,
    onDismissRecent: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val extra = revealCountForBand(band)
    val shownReveal = if (extra == Int.MAX_VALUE) reveal else reveal.take(extra)
    Box(modifier.clip(RoundedCornerShape(topStart = 18.dp, topEnd = 18.dp))) {
        if (blurred != null) {
            Image(
                bitmap = blurred,
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
                alpha = 0.85f,
            )
        }
        Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.42f)))
        Column(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
            Box(
                Modifier
                    .align(Alignment.CenterHorizontally)
                    .padding(top = 10.dp, bottom = 8.dp)
                    .width(36.dp)
                    .height(3.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(accent.copy(alpha = 0.55f)),
            )
            if (recents.isNotEmpty()) {
                val maxH = (recents.size.coerceAtMost(6) * 62).dp
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = maxH),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                    userScrollEnabled = band >= 4,
                ) {
                    items(recents, key = { it.app.packageName }) { item ->
                        RecentCard(
                            item = item,
                            onLaunch = { onLaunch(item.app) },
                            onDismiss = { onDismissRecent(item.app.packageName) },
                        )
                    }
                }
            }
            if (shownReveal.isNotEmpty()) {
                Spacer(Modifier.height(10.dp))
                LazyVerticalGrid(
                    columns = GridCells.Fixed(4),
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    contentPadding = PaddingValues(bottom = 8.dp),
                    userScrollEnabled = band >= 4,
                ) {
                    items(shownReveal, key = { it.key }) { app ->
                        RevealCell(app, onLaunch)
                    }
                }
            } else {
                Spacer(Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun RecentCard(
    item: RecentItem,
    onLaunch: () -> Unit,
    onDismiss: () -> Unit,
) {
    var offsetX by remember { mutableFloatStateOf(0f) }
    Row(
        Modifier
            .fillMaxWidth()
            .offset { IntOffset(offsetX.roundToInt(), 0) }
            .clip(RoundedCornerShape(14.dp))
            .background(Color.White.copy(alpha = 0.08f))
            .pointerInput(item.app.packageName) {
                detectHorizontalDragGestures(
                    onDragEnd = {
                        if (abs(offsetX) > size.width * 0.35f) onDismiss()
                        else offsetX = 0f
                    },
                    onDragCancel = { offsetX = 0f },
                    onHorizontalDrag = { change, dx ->
                        change.consume()
                        offsetX += dx
                    },
                )
            }
            .clickable(onClick = onLaunch)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Image(
            bitmap = item.app.icon,
            contentDescription = null,
            modifier = Modifier
                .size(36.dp)
                .clip(CircleShape),
        )
        Spacer(Modifier.width(12.dp))
        Text(
            text = item.app.label,
            color = PrintInk,
            fontSize = 15.sp,
            modifier = Modifier.weight(1f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            text = Ranking.relativeTime(item.lastUsed),
            color = PrintInk.copy(alpha = 0.45f),
            fontSize = 12.sp,
        )
    }
}

@Composable
private fun RevealCell(app: LaunchableApp, onLaunch: (LaunchableApp) -> Unit) {
    Column(
        Modifier
            .padding(vertical = 8.dp)
            .alpha(0.92f)
            .clickable { onLaunch(app) },
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Image(
            bitmap = app.icon,
            contentDescription = app.label,
            modifier = Modifier
                .size(48.dp)
                .clip(CircleShape),
        )
        Spacer(Modifier.height(6.dp))
        Text(
            text = app.label,
            color = PrintInk.copy(alpha = 0.7f),
            fontSize = 10.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            fontWeight = FontWeight.Normal,
        )
    }
}
