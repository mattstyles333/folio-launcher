package com.folio.launcher.search

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.folio.launcher.data.LaunchableApp
import com.folio.launcher.data.Ranking
import com.folio.launcher.ui.PrintInk

@Composable
fun AppPicker(
    title: String = "Pin",
    placeholder: String = "App",
    empty: String = "No apps",
    apps: List<LaunchableApp>,
    launches: Map<String, List<Long>> = emptyMap(),
    accent: Color,
    onPick: (LaunchableApp) -> Unit,
    onDismiss: () -> Unit,
) {
    BackHandler(onBack = onDismiss)
    var query by remember { mutableStateOf("") }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    val results = remember(query, apps) {
        if (query.isBlank()) apps.sortedBy { it.label.lowercase() }
        else Ranking.search(query, apps, launches)
    }
    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.88f))
            .statusBarsPadding()
            .imePadding()
            .padding(horizontal = 22.dp, vertical = 18.dp),
    ) {
        Column(Modifier.fillMaxSize()) {
            Text(title, color = PrintInk.copy(alpha = 0.5f), fontSize = 13.sp)
            Spacer(Modifier.height(8.dp))
            BasicTextField(
                value = query,
                onValueChange = { query = it },
                modifier = Modifier
                    .fillMaxWidth()
                    .focusRequester(focus),
                singleLine = true,
                cursorBrush = SolidColor(accent),
                textStyle = TextStyle(color = PrintInk, fontSize = 20.sp, fontWeight = FontWeight.Light),
                decorationBox = { inner ->
                    Box {
                        if (query.isEmpty()) {
                            Text(placeholder, color = PrintInk.copy(alpha = 0.32f), fontSize = 20.sp)
                        }
                        inner()
                    }
                },
            )
            Spacer(Modifier.height(8.dp))
            Box(Modifier.fillMaxWidth().height(1.dp).background(accent.copy(alpha = 0.4f)))
            Spacer(Modifier.height(12.dp))
            if (results.isEmpty()) {
                Text(empty, color = PrintInk.copy(alpha = 0.4f), fontSize = 15.sp)
            } else {
                LazyColumn(Modifier.fillMaxSize()) {
                    items(results, key = { it.key }) { app ->
                        SearchRow(app, onPick)
                    }
                }
            }
        }
    }
}
