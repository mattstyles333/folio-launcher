package com.folio.launcher.home

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.folio.launcher.data.AppShortcut
import com.folio.launcher.data.AppShortcuts
import com.folio.launcher.data.LaunchableApp
import com.folio.launcher.ui.AppIcon
import com.folio.launcher.ui.PrintInk
import com.folio.launcher.ui.VoidBlack
import kotlin.math.roundToInt
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/** A held app's menu as shown: up to four of its shortcuts, then App info, by the icon it was held on. */
data class AppMenu(
    val app: LaunchableApp,
    val shortcuts: List<AppShortcut>,
    /** The held icon (a sheet cell or a search row), in the root's pixels. */
    val anchor: Rect,
)

/**
 * The long-press menu: closed, reading a held app's shortcuts, or [shown]. It shows once they're read,
 * so rows never arrive under the finger.
 */
class AppMenuState internal constructor(private val scope: CoroutineScope) {
    var shown by mutableStateOf<AppMenu?>(null)
        private set
    private var reading: Job? = null

    fun open(app: LaunchableApp, anchor: Rect, shortcuts: suspend (LaunchableApp) -> List<AppShortcut>) {
        close()
        reading = scope.launch { shown = AppMenu(app, shortcuts(app), anchor) }
    }

    fun close() {
        reading?.cancel()
        reading = null
        shown = null
    }
}

@Composable
fun rememberAppMenuState(): AppMenuState {
    val scope = rememberCoroutineScope()
    return remember(scope) { AppMenuState(scope) }
}

/** Where a held icon is: kept as it's placed, read only when it's held, so nothing recomposes as it moves. */
class MenuAnchor {
    internal var coordinates: LayoutCoordinates? = null

    /** The icon's bounds in the root's pixels (empty once it has left the screen). */
    fun bounds(): Rect = coordinates?.takeIf { it.isAttached }?.boundsInRoot() ?: Rect.Zero
}

@Composable
fun rememberMenuAnchor(): MenuAnchor = remember { MenuAnchor() }

fun Modifier.menuAnchor(anchor: MenuAnchor): Modifier = onPlaced { anchor.coordinates = it }

/**
 * A held app's menu over the sheet or search: its shortcuts, then App info. A touch anywhere off the
 * card closes it, and so does Back, before the sheet or search hears it.
 */
@Composable
fun AppMenuOverlay(
    menu: AppMenu,
    iconSaturation: Float,
    onShortcut: (AppShortcut) -> Unit,
    onAppInfo: () -> Unit,
    onDismiss: () -> Unit,
) {
    BackHandler(onBack = onDismiss)
    val dismiss by rememberUpdatedState(onDismiss)
    val appear = remember(menu) { Animatable(0f) }
    LaunchedEffect(menu) { appear.animateTo(1f, tween(140)) }
    // The bars and the keyboard: the card stays clear of them.
    val insets = WindowInsets.safeDrawing
    Box(Modifier.fillMaxSize()) {
        Box(
            Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.2f))
                // A touch off the card closes the menu and reaches nothing underneath, the sheet's icons included.
                .pointerInput(Unit) {
                    awaitEachGesture {
                        awaitFirstDown(requireUnconsumed = false)
                        dismiss()
                    }
                },
        )
        Layout(
            modifier = Modifier.fillMaxSize(),
            content = {
                Column(
                    Modifier
                        .width(MenuWidth)
                        .graphicsLayer { alpha = appear.value }
                        .clip(MenuShape)
                        .background(VoidBlack.copy(alpha = 0.94f))
                        .border(1.dp, PrintInk.copy(alpha = 0.10f), MenuShape)
                        // A touch between the rows is the card's: it closes nothing.
                        .pointerInput(Unit) {}
                        .semantics { paneTitle = menu.app.label }
                        .padding(vertical = 6.dp),
                ) {
                    menu.shortcuts.forEach { shortcut ->
                        ShortcutRow(shortcut, iconSaturation) { onShortcut(shortcut) }
                    }
                    if (menu.shortcuts.isNotEmpty()) {
                        Box(
                            Modifier
                                .padding(vertical = 4.dp)
                                .fillMaxWidth()
                                .height(1.dp)
                                .background(PrintInk.copy(alpha = 0.08f)),
                        )
                    }
                    MenuRow("App info", onClick = onAppInfo) {
                        InfoGlyph(PrintInk.copy(alpha = 0.72f), Modifier.size(20.dp))
                    }
                }
            },
        ) { measurables, constraints ->
            val card = measurables.single().measure(constraints.copy(minWidth = 0, minHeight = 0))
            val top = insets.getTop(this)
            val bottom = constraints.maxHeight - insets.getBottom(this)
            val side = MenuSide.roundToPx()
            val gap = MenuGap.roundToPx()
            layout(constraints.maxWidth, constraints.maxHeight) {
                // The anchor is in the root's pixels; this layer needn't start at the root's corner.
                val origin = coordinates?.positionInRoot() ?: Offset.Zero
                card.place(
                    menuOffset(
                        anchor = menu.anchor.translate(-origin),
                        card = IntSize(card.width, card.height),
                        width = constraints.maxWidth,
                        top = top,
                        bottom = bottom,
                        side = side,
                        gap = gap,
                    ),
                )
            }
        }
    }
}

/**
 * Where the menu's card goes: centred on the held icon, just above it, or below it when the top is too
 * near; never closer than [side] to either edge, nor above [top] or below [bottom] (the bars, the keyboard).
 */
internal fun menuOffset(anchor: Rect, card: IntSize, width: Int, top: Int, bottom: Int, side: Int, gap: Int): IntOffset {
    val x = (anchor.center.x - card.width / 2f).roundToInt()
        .coerceIn(side, (width - side - card.width).coerceAtLeast(side))
    val above = (anchor.top - gap - card.height).roundToInt()
    val y = if (above >= top) above else (anchor.bottom + gap).roundToInt()
    return IntOffset(x, y.coerceIn(top, (bottom - card.height).coerceAtLeast(top)))
}

@Composable
private fun ShortcutRow(shortcut: AppShortcut, saturation: Float, onClick: () -> Unit) {
    // The long label where it fits on the row, else the short one: what Android asks of a launcher.
    var short by remember(shortcut) { mutableStateOf(shortcut.longLabel == null) }
    MenuRow(
        label = if (short) shortcut.label else shortcut.longLabel ?: shortcut.label,
        onClick = onClick,
        onOverflow = { short = true },
    ) {
        val icon = shortcut.icon
        if (icon != null) {
            AppIcon(
                bitmap = icon,
                contentDescription = null,
                saturation = saturation,
                modifier = Modifier
                    .size(AppShortcuts.ICON_DP.dp)
                    .clip(CircleShape),
            )
        } else {
            Box(
                Modifier
                    .size(AppShortcuts.ICON_DP.dp)
                    .clip(CircleShape)
                    .background(Color.White.copy(alpha = 0.08f)),
            )
        }
    }
}

/** One row: a button named by its label (TalkBack reads it so), its icon to the start. */
@Composable
private fun MenuRow(
    label: String,
    onClick: () -> Unit,
    onOverflow: () -> Unit = {},
    icon: @Composable () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(AppShortcuts.ICON_DP.dp), contentAlignment = Alignment.Center) { icon() }
        Spacer(Modifier.width(14.dp))
        Text(
            text = label,
            color = PrintInk.copy(alpha = 0.92f),
            fontSize = 15.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            onTextLayout = { if (it.hasVisualOverflow) onOverflow() },
        )
    }
}

/** A thin-stroke (i), drawn like the search glyph: the system's page for the app. */
@Composable
private fun InfoGlyph(color: Color, modifier: Modifier = Modifier) {
    Canvas(modifier) {
        val stroke = size.minDimension * 0.09f
        val radius = size.minDimension / 2f - stroke / 2f
        drawCircle(color = color, radius = radius, center = center, style = Stroke(width = stroke))
        drawLine(
            color = color,
            start = Offset(center.x, center.y - radius * 0.08f),
            end = Offset(center.x, center.y + radius * 0.5f),
            strokeWidth = stroke,
            cap = StrokeCap.Round,
        )
        drawCircle(color = color, radius = stroke * 0.7f, center = Offset(center.x, center.y - radius * 0.42f))
    }
}

private val MenuWidth = 240.dp
private val MenuShape = RoundedCornerShape(16.dp)
private val MenuSide = 16.dp
private val MenuGap = 6.dp
