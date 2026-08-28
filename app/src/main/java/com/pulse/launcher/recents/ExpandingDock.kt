package com.pulse.launcher.recents

import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pulse.launcher.data.LaunchableApp
import com.pulse.launcher.data.RailSlot
import com.pulse.launcher.home.Rail
import com.pulse.launcher.ui.AppIcon
import com.pulse.launcher.ui.PrintInk

val RailHeight = 80.dp
val DockRowHeight = 80.dp

@Composable
fun ExpandingDock(
    pullPx: Float,
    maxSheetPx: Float,
    rail: List<RailSlot>,
    extras: List<LaunchableApp>,
    iconSaturation: Float,
    scrollEnabled: Boolean,
    onSheetPull: (Float) -> Unit,
    onSheetRelease: (Float) -> Unit,
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
    val gridState = rememberLazyGridState()
    val enter = (extraPx / (rowPx * 0.9f)).coerceIn(0f, 1f)

    LaunchedEffect(scrollEnabled) {
        if (!scrollEnabled && extraPx < 8f) {
            gridState.scrollToItem(0)
        }
    }

    val pullNow = rememberUpdatedState(pullPx)
    val maxNow = rememberUpdatedState(maxSheetPx)
    val pullCb = rememberUpdatedState(onSheetPull)
    val releaseCb = rememberUpdatedState(onSheetRelease)
    val nested = remember(gridState) {
        object : NestedScrollConnection {
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                if (available.y <= 0f) return Offset.Zero
                val atTop = gridState.firstVisibleItemIndex == 0 &&
                    gridState.firstVisibleItemScrollOffset == 0
                if (!atTop) return Offset.Zero
                val next = (pullNow.value - available.y).coerceAtLeast(0f)
                pullCb.value(next)
                return Offset(0f, available.y)
            }

            override suspend fun onPostFling(consumed: Velocity, available: Velocity): Velocity {
                val shrinking = pullNow.value < maxNow.value - 8f
                val atTop = gridState.firstVisibleItemIndex == 0 &&
                    gridState.firstVisibleItemScrollOffset == 0
                if (shrinking || (atTop && available.y > 400f)) {
                    releaseCb.value(available.y)
                    return if (available.y > 0f) available else Velocity.Zero
                }
                return Velocity.Zero
            }
        }
    }

    Box(
        modifier
            .fillMaxWidth()
            .height(with(density) { viewportPx.toDp() })
            .clipToBounds()
            .then(if (scrollEnabled) Modifier.nestedScroll(nested) else Modifier),
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
            if (extraPx > 0.5f && extras.isNotEmpty()) {
                LazyVerticalGrid(
                    columns = GridCells.Fixed(4),
                    state = gridState,
                    userScrollEnabled = scrollEnabled,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(with(density) { extraPx.toDp() })
                        .graphicsLayer {
                            alpha = 0.22f + 0.78f * enter
                            translationY = (1f - enter) * 16f
                        },
                    contentPadding = PaddingValues(horizontal = 20.dp, vertical = 2.dp),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                ) {
                    items(extras, key = { it.key }) { app ->
                        DrawerCell(
                            app = app,
                            iconSaturation = iconSaturation,
                            onLaunch = onLaunch,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun DrawerCell(
    app: LaunchableApp,
    iconSaturation: Float,
    onLaunch: (LaunchableApp) -> Unit,
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .fillMaxWidth()
            .height(DockRowHeight)
            .padding(horizontal = 2.dp, vertical = 4.dp)
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
