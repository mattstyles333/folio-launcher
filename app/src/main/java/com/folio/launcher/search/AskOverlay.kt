package com.folio.launcher.search

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
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
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.folio.launcher.ui.PrintInk

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AskOverlay(
    visible: Boolean,
    aiLabel: String,
    query: String,
    onQueryChange: (String) -> Unit,
    suggestions: List<Pair<String, String>>,
    accent: Color,
    onAsk: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(tween(180)) + slideInVertically(tween(280)) { -it / 14 },
        exit = fadeOut(tween(140)) + slideOutVertically(tween(180)) { -it / 18 },
    ) {
        val focus = remember { FocusRequester() }
        val ready = aiLabel.isNotEmpty()
        LaunchedEffect(visible, ready) {
            if (visible && ready) focus.requestFocus()
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
                .padding(horizontal = 22.dp, vertical = 18.dp),
        ) {
            Column(Modifier.fillMaxSize()) {
                Text(
                    if (ready) aiLabel else "Ask",
                    color = PrintInk.copy(alpha = 0.5f),
                    fontFamily = FontFamily.Serif,
                    fontWeight = FontWeight.Light,
                    fontSize = 28.sp,
                )
                Spacer(Modifier.height(18.dp))
                if (!ready) {
                    Text(
                        "Install Grok, ChatGPT, Gemini or Claude, then pick it in Settings.",
                        color = PrintInk.copy(alpha = 0.55f),
                        fontSize = 16.sp,
                    )
                } else {
                    BasicTextField(
                        value = query,
                        onValueChange = onQueryChange,
                        modifier = Modifier
                            .fillMaxWidth()
                            .focusRequester(focus),
                        singleLine = true,
                        cursorBrush = SolidColor(accent),
                        textStyle = TextStyle(
                            color = PrintInk,
                            fontSize = 20.sp,
                            fontWeight = FontWeight.Light,
                        ),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go),
                        keyboardActions = KeyboardActions(
                            onGo = { onAsk(query) },
                        ),
                        decorationBox = { inner ->
                            Box {
                                if (query.isEmpty()) {
                                    Text(
                                        "Ask $aiLabel",
                                        color = PrintInk.copy(alpha = 0.32f),
                                        fontSize = 20.sp,
                                        fontWeight = FontWeight.Light,
                                    )
                                }
                                inner()
                            }
                        },
                    )
                    Spacer(Modifier.height(8.dp))
                    Box(Modifier.fillMaxWidth().height(1.dp).background(accent.copy(alpha = 0.4f)))
                    Spacer(Modifier.height(18.dp))
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        suggestions.forEach { (label, prompt) ->
                            Text(
                                text = label,
                                color = PrintInk.copy(alpha = 0.88f),
                                fontSize = 14.sp,
                                modifier = Modifier
                                    .clip(RoundedCornerShape(20.dp))
                                    .background(Color.White.copy(alpha = 0.12f))
                                    .clickable { onAsk(prompt) }
                                    .padding(horizontal = 14.dp, vertical = 9.dp),
                            )
                        }
                    }
                }
                Spacer(Modifier.weight(1f))
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        "Cancel",
                        color = accent.copy(alpha = 0.92f),
                        fontSize = 16.sp,
                        modifier = Modifier.clickable(onClick = onDismiss),
                    )
                }
            }
        }
    }
}
