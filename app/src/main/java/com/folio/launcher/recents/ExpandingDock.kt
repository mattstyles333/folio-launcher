package com.folio.launcher.recents

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
import androidx.compose.foundation.lazy.grid.GridItemSpan
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
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.folio.launcher.data.DrawerEntry
import com.folio.launcher.data.LaunchableApp
import com.folio.launcher.data.RailSlot
import com.folio.launcher.home.Rail
import com.folio.launcher.home.SheetPull
import com.folio.launcher.home.swipeAwayToHide
import com.folio.launcher.ui.AppIcon
import com.folio.launcher.ui.PrintInk
import kotlin.math.roundToInt

val RailHeight = 80.dp
val DockRowHeight = 80.dp

@Composable
fun ExpandingDock(
    pull: SheetPull,
    maxSheetPx: Float,
    rail: List<RailSlot>,
    extras: List<DrawerEntry>,
    iconSaturation: Float,
    onSettle: (Float) -> Unit,
    onLaunch: (LaunchableApp) -> Unit,
    onPinRequest: (Int) -> Unit,
    onReorder: (Int, Int) -> Unit,
    onHide: (LaunchableApp) -> Unit,
    modifier: Modifier = Modifier,
) {
    val gridState = rememberLazyGridState()
    val settle = rememberUpdatedState(onSettle)

    LaunchedEffect(pull.locked) {
        if (!pull.locked) gridState.scrollToItem(0)
    }

    val nested = remember(gridState, pull, maxSheetPx) {
        object : NestedScrollConnection {
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                if (available.y <= 0f) return Offset.Zero
                val atTop = gridState.firstVisibleItemIndex == 0 &&
                    gridState.firstVisibleItemScrollOffset == 0
                if (!atTop) return Offset.Zero
                val next = (pull.px - available.y).coerceAtLeast(0f)
                val consumed = pull.px - next
                if (consumed == 0f) return Offset.Zero
                pull.px = next
                return Offset(0f, consumed)
            }

            override suspend fun onPostFling(consumed: Velocity, available: Velocity): Velocity {
                val shrinking = pull.px < maxSheetPx - 8f
                val atTop = gridState.firstVisibleItemIndex == 0 &&
                    gridState.firstVisibleItemScrollOffset == 0
                if (shrinking || (atTop && available.y > 350f)) {
                    settle.value(available.y)
                    return if (available.y > 0f) available else Velocity.Zero
                }
                return Velocity.Zero
            }
        }
    }

    Layout(
        modifier = modifier
            .fillMaxWidth()
            .clipToBounds()
            .then(if (pull.locked) Modifier.nestedScroll(nested) else Modifier),
        content = {
            Rail(
                slots = rail,
                iconSaturation = iconSaturation,
                onLaunch = onLaunch,
                onPinRequest = onPinRequest,
                onReorder = onReorder,
                onHide = onHide,
                modifier = Modifier.fillMaxWidth(),
            )
            Box(
                Modifier
                    .fillMaxWidth()
                    .graphicsLayer(),
            ) {
                if (extras.isNotEmpty() && maxSheetPx > 1f) {
                    LazyVerticalGrid(
                        columns = GridCells.Fixed(4),
                        state = gridState,
                        userScrollEnabled = pull.locked,
                        modifier = Modifier.fillMaxWidth(),
                        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp),
                        horizontalArrangement = Arrangement.spacedBy(0.dp),
                    ) {
                        items(
                            extras,
                            key = { it.key },
                            span = { GridItemSpan(if (it is DrawerEntry.Letter) maxLineSpan else 1) },
                            contentType = {
                                when (it) {
                                    is DrawerEntry.Letter -> "letter"
                                    is DrawerEntry.App -> "app"
                                }
                            },
                        ) { entry ->
                            when (entry) {
                                is DrawerEntry.App -> DrawerCell(
                                    app = entry.app,
                                    iconSaturation = iconSaturation,
                                    onLaunch = onLaunch,
                                    onHide = onHide,
                                )
                                is DrawerEntry.Letter -> DrawerLetter(entry.letter)
                            }
                        }
                    }
                }
            }
        },
    ) { measurables, constraints ->
        val width = constraints.maxWidth
        val extra = pull.px.coerceAtLeast(0f)
        val railPlaceable = measurables[0].measure(
            Constraints.fixedWidth(width),
        )
        val gridHeight = maxSheetPx.roundToInt().coerceAtLeast(0)
        val gridPlaceable = measurables[1].measure(
            Constraints.fixed(width, gridHeight),
        )
        val height = (railPlaceable.height + extra).roundToInt()
            .coerceAtLeast(railPlaceable.height)
        layout(width, height) {
            railPlaceable.placeRelative(0, 0)
            gridPlaceable.placeRelative(0, railPlaceable.height)
        }
    }
}

@Composable
private fun DrawerLetter(letter: String) {
    Text(
        text = letter,
        color = PrintInk.copy(alpha = 0.34f),
        fontFamily = FontFamily.Serif,
        fontSize = 13.sp,
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 4.dp, top = 10.dp, bottom = 4.dp),
    )
}

@Composable
private fun DrawerCell(
    app: LaunchableApp,
    iconSaturation: Float,
    onLaunch: (LaunchableApp) -> Unit,
    onHide: (LaunchableApp) -> Unit,
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .fillMaxWidth()
            .height(DockRowHeight)
            .swipeAwayToHide(app.key) { onHide(app) }
            .pointerInput(app.key) {
                detectTapGestures(onTap = { onLaunch(app) })
            }
            .padding(horizontal = 2.dp, vertical = 4.dp),
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
