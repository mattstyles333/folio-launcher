package com.folio.launcher.search

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.folio.launcher.data.LaunchableApp
import com.folio.launcher.data.SearchHint
import com.folio.launcher.data.SearchRoute
import com.folio.launcher.home.SearchGlyph
import com.folio.launcher.ui.AppIcon
import com.folio.launcher.ui.PrintInk

@Composable
fun SearchOverlay(
    visible: Boolean,
    query: String,
    onQueryChange: (String) -> Unit,
    results: List<LaunchableApp>,
    accent: Color,
    onLaunch: (LaunchableApp) -> Unit,
    onAppInfo: (LaunchableApp) -> Unit = {},
    onDismiss: () -> Unit,
    iconSaturation: Float = 1f,
    hint: SearchHint = SearchHint(),
    aiApp: LaunchableApp? = null,
    webApp: LaunchableApp? = null,
    musicApp: LaunchableApp? = null,
    onRoute: (SearchRoute) -> Unit = {},
) {
    val typed = query.trim()
    val live = hint.takeIf { typed.isNotEmpty() && it.query == typed }
    val picked = live?.app
    val shown = if (picked == null) results else listOf(picked) + results.filterNot { it.key == picked.key }
    // Jev's route when it's sure; with nothing local to launch, the web.
    val action = live?.route?.takeUnless { it is SearchRoute.App }
        ?: SearchRoute.Web.takeIf { typed.isNotEmpty() && shown.isEmpty() }
    fun enter() {
        when {
            picked != null -> onLaunch(picked)
            action != null && live?.route != null -> onRoute(action)
            shown.isNotEmpty() -> onLaunch(shown.first())
            action != null -> onRoute(action)
        }
    }
    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(tween(180)) + slideInVertically(tween(280)) { -it / 14 },
        exit = fadeOut(tween(140)) + slideOutVertically(tween(180)) { -it / 18 },
    ) {
        val focus = remember { FocusRequester() }
        LaunchedEffect(visible) {
            if (visible) focus.requestFocus()
        }
        Box(
            Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.90f))
                .pointerInput(Unit) {
                    var total = 0f
                    detectVerticalDragGestures(
                        onDragEnd = { if (total > 80f) onDismiss() },
                        onVerticalDrag = { _, dy -> total += dy },
                    )
                }
                .windowInsetsPadding(WindowInsets.safeDrawing)
                .imePadding()
                .padding(horizontal = 16.dp, vertical = 14.dp),
        ) {
            Column(Modifier.fillMaxSize()) {
                Row(
                    Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Row(
                        Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(12.dp))
                            .background(Color.White.copy(alpha = 0.12f))
                            .padding(horizontal = 12.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        SearchGlyph(
                            color = PrintInk.copy(alpha = 0.55f),
                            modifier = Modifier.size(15.dp),
                        )
                        Spacer(Modifier.width(8.dp))
                        BasicTextField(
                            value = query,
                            onValueChange = onQueryChange,
                            modifier = Modifier
                                .weight(1f)
                                .focusRequester(focus),
                            singleLine = true,
                            cursorBrush = SolidColor(accent),
                            textStyle = TextStyle(
                                color = PrintInk,
                                fontSize = 17.sp,
                                fontWeight = FontWeight.Normal,
                            ),
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                            keyboardActions = KeyboardActions(onSearch = { enter() }),
                            decorationBox = { inner ->
                                Box {
                                    if (query.isEmpty()) {
                                        Text(
                                            "Search",
                                            color = PrintInk.copy(alpha = 0.38f),
                                            fontSize = 17.sp,
                                        )
                                    }
                                    inner()
                                }
                            },
                        )
                    }
                    Spacer(Modifier.width(12.dp))
                    Text(
                        text = "Cancel",
                        color = accent.copy(alpha = 0.92f),
                        fontSize = 16.sp,
                        modifier = Modifier.clickable(onClick = onDismiss),
                    )
                }
                Spacer(Modifier.height(16.dp))
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    if (action != null) {
                        item(key = "route") {
                            Box(Modifier.animateItem()) {
                                RouteRow(
                                    route = action,
                                    typed = typed,
                                    hint = live,
                                    accent = accent,
                                    aiApp = aiApp,
                                    webApp = webApp,
                                    musicApp = musicApp,
                                    saturation = iconSaturation,
                                    onRoute = onRoute,
                                )
                            }
                        }
                    }
                    items(shown, key = { it.key }) { app ->
                        Box(Modifier.animateItem()) {
                            SearchRow(app, onLaunch, onAppInfo, saturation = iconSaturation)
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun SearchRow(
    app: LaunchableApp,
    onLaunch: (LaunchableApp) -> Unit,
    onAppInfo: ((LaunchableApp) -> Unit)? = null,
    saturation: Float = 1f,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .then(
                if (onAppInfo != null) {
                    Modifier.combinedClickable(
                        onClick = { onLaunch(app) },
                        onLongClick = { onAppInfo(app) },
                    )
                } else {
                    Modifier.clickable { onLaunch(app) }
                },
            )
            .padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AppIcon(
            bitmap = app.icon,
            contentDescription = null,
            saturation = saturation,
            modifier = Modifier
                .size(40.dp)
                .clip(CircleShape),
        )
        Spacer(Modifier.width(14.dp))
        Text(
            text = app.label,
            color = PrintInk.copy(alpha = 0.92f),
            fontSize = 16.sp,
            fontWeight = FontWeight.Normal,
        )
    }
}

@Composable
private fun RouteRow(
    route: SearchRoute,
    typed: String,
    hint: SearchHint?,
    accent: Color,
    aiApp: LaunchableApp?,
    webApp: LaunchableApp?,
    musicApp: LaunchableApp?,
    saturation: Float,
    onRoute: (SearchRoute) -> Unit,
) {
    when (route) {
        SearchRoute.Answer -> AnswerLine(
            answer = hint?.answer.orEmpty(),
            answering = hint?.answering == true,
            next = aiApp?.let { "Continue in ${it.label}" } ?: "Search the web",
            accent = accent,
            onClick = { onRoute(route) },
        )
        SearchRoute.Web -> ActionRow(webApp?.icon, "Search the web", "“$typed”", saturation) { onRoute(route) }
        SearchRoute.Ask -> ActionRow(aiApp?.icon, "Ask ${aiApp?.label ?: "AI"}", "“$typed”", saturation) {
            onRoute(route)
        }
        is SearchRoute.Music -> ActionRow(musicApp?.icon, "Play on Spotify", "“${route.term}”", saturation) {
            onRoute(route)
        }
        is SearchRoute.App -> Unit
    }
}

/** DeepSeek's one line, set like the quote on the print. */
@Composable
private fun AnswerLine(
    answer: String,
    answering: Boolean,
    next: String,
    accent: Color,
    onClick: () -> Unit,
) {
    Column(
        Modifier
            .fillMaxWidth()
            .clickable(enabled = !answering, onClick = onClick)
            .padding(top = 6.dp, bottom = 14.dp),
    ) {
        Text(
            text = if (answering) "…" else answer,
            color = PrintInk.copy(alpha = if (answering) 0.4f else 0.95f),
            fontFamily = FontFamily.Serif,
            fontWeight = FontWeight.Light,
            fontSize = 20.sp,
            lineHeight = 27.sp,
        )
        if (!answering) {
            Spacer(Modifier.height(8.dp))
            Text(next, color = accent.copy(alpha = 0.92f), fontSize = 13.sp)
        }
        Spacer(Modifier.height(12.dp))
        Box(Modifier.fillMaxWidth().height(1.dp).background(accent.copy(alpha = 0.3f)))
    }
}

@Composable
private fun ActionRow(
    icon: ImageBitmap?,
    title: String,
    detail: String,
    saturation: Float,
    onClick: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            AppIcon(
                bitmap = icon,
                contentDescription = null,
                saturation = saturation,
                modifier = Modifier
                    .size(40.dp)
                    .clip(CircleShape),
            )
        } else {
            Box(
                Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .background(Color.White.copy(alpha = 0.12f)),
                contentAlignment = Alignment.Center,
            ) {
                SearchGlyph(color = PrintInk.copy(alpha = 0.7f), modifier = Modifier.size(16.dp))
            }
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(title, color = PrintInk.copy(alpha = 0.92f), fontSize = 16.sp)
            Text(
                detail,
                color = PrintInk.copy(alpha = 0.45f),
                fontSize = 13.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
