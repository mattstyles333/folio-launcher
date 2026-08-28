package com.pulse.launcher.recents

import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pulse.launcher.data.LaunchableApp
import com.pulse.launcher.data.RailSlot
import com.pulse.launcher.home.Rail
import com.pulse.launcher.ui.AppIcon
import com.pulse.launcher.ui.PrintInk
import kotlin.math.ceil
import kotlin.math.roundToInt

val RailHeight = 80.dp
val DockRowHeight = 80.dp

fun dockSnapPx(
    pull: Float,
    rowPx: Float,
    maxRows: Int,
): Float {
    if (maxRows <= 0) return 0f
    if (pull < rowPx * 0.32f) return 0f
    val rows = (pull / rowPx).roundToInt().coerceIn(0, maxRows)
    return rows * rowPx
}

fun visibleExtraRows(pull: Float, rowPx: Float): Int {
    if (rowPx <= 0f) return 0
    return (pull / rowPx).toInt()
}

@Composable
fun ExpandingDock(
    pullPx: Float,
    rail: List<RailSlot>,
    extras: List<LaunchableApp>,
    iconSaturation: Float,
    onLaunch: (LaunchableApp) -> Unit,
    onPinRequest: (Int) -> Unit,
    onReorder: (Int, Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val density = LocalDensity.current
    val rowPx = with(density) { DockRowHeight.toPx() }
    val railPx = with(density) { RailHeight.toPx() }
    val extraPx = pullPx.coerceAtLeast(0f)
    val viewportPx = railPx + extraPx
    val rows = extras.chunked(4)
    val composeCount = if (rows.isEmpty()) {
        0
    } else {
        ceil((extraPx / rowPx).toDouble()).toInt().plus(1).coerceIn(1, rows.size)
    }

    Box(
        modifier
            .fillMaxWidth()
            .height(with(density) { viewportPx.toDp() })
            .clipToBounds(),
    ) {
        Column(Modifier.fillMaxWidth()) {
            Rail(
                slots = rail,
                iconSaturation = iconSaturation,
                onLaunch = onLaunch,
                onPinRequest = onPinRequest,
                onReorder = onReorder,
                modifier = Modifier.fillMaxWidth(),
            )
            rows.take(composeCount).forEachIndexed { index, apps ->
                val rowTop = index * rowPx
                val shown = ((extraPx - rowTop) / rowPx).coerceIn(0f, 1f)
                val enter = shown * shown * (3f - 2f * shown)
                ExtraRow(
                    apps = apps,
                    iconSaturation = iconSaturation,
                    onLaunch = onLaunch,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(DockRowHeight)
                        .graphicsLayer {
                            alpha = 0.18f + 0.82f * enter
                            val s = 0.94f + 0.06f * enter
                            scaleX = s
                            scaleY = s
                        },
                )
            }
        }
    }
}

@Composable
private fun ExtraRow(
    apps: List<LaunchableApp>,
    iconSaturation: Float,
    onLaunch: (LaunchableApp) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier.padding(horizontal = 28.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.Top,
    ) {
        repeat(4) { i ->
            val app = apps.getOrNull(i)
            Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                if (app != null) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier
                            .fillMaxWidth()
                            .pointerInput(app.key) {
                                detectTapGestures { onLaunch(app) }
                            },
                    ) {
                        AppIcon(
                            bitmap = app.icon,
                            contentDescription = app.label,
                            saturation = iconSaturation,
                            modifier = Modifier
                                .size(48.dp)
                                .clip(CircleShape),
                        )
                        Spacer(Modifier.height(5.dp))
                        Text(
                            text = app.label,
                            color = PrintInk.copy(alpha = 0.42f + 0.30f * iconSaturation),
                            fontSize = 10.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 2.dp),
                        )
                    }
                }
            }
        }
    }
}
