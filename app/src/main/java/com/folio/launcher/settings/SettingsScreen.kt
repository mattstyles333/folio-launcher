package com.folio.launcher.settings

import android.content.ClipboardManager
import android.content.Intent
import android.provider.Settings
import androidx.activity.compose.BackHandler
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
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.folio.launcher.data.BrainStatus
import com.folio.launcher.data.HomeUiState
import com.folio.launcher.data.LaunchableApp
import com.folio.launcher.search.AppPicker
import com.folio.launcher.ui.PrintInk
import com.folio.launcher.ui.VoidBlack

@Composable
fun SettingsScreen(
    state: HomeUiState,
    onBack: () -> Unit,
    onPickPhoto: () -> Unit,
    onNextBing: () -> Unit,
    onPreviousPrint: () -> Unit,
    onShowClock: (Boolean) -> Unit,
    onResetPins: () -> Unit,
    onSetDefault: () -> Unit,
    onUnhideApp: (LaunchableApp) -> Unit,
    onCycleAi: () -> Unit,
    onSetOpenRouterKey: (String) -> Boolean,
    onClearOpenRouterKey: () -> Unit,
) {
    val context = LocalContext.current
    var hiddenOpen by remember { mutableStateOf(false) }
    var keyMissing by remember { mutableStateOf(false) }
    val hiddenApps = remember(state.apps, state.hiddenPackages) {
        state.apps.filter { it.packageName in state.hiddenPackages }
            .distinctBy { it.packageName }
            .sortedBy { it.label.lowercase() }
    }
    BackHandler(enabled = !hiddenOpen, onBack = onBack)
    Box(Modifier.fillMaxSize().background(VoidBlack)) {
    Column(
        Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .navigationBarsPadding()
            .padding(horizontal = 24.dp, vertical = 28.dp),
    ) {
        Text(
            "Settings",
            color = PrintInk,
            fontFamily = FontFamily.Serif,
            fontWeight = FontWeight.Light,
            fontSize = 28.sp,
        )
        Spacer(Modifier.height(28.dp))
        Column(
            Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState()),
        ) {
            SettingsRow("Choose photo", "Replace the print") { onPickPhoto() }
            SettingsRow("Bing print", "Double-tap the print for the next one") { onNextBing() }
            if (state.hasPreviousPrint) {
                SettingsRow("Previous print", "The one before this") { onPreviousPrint() }
            }
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(vertical = 16.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column {
                    Text("Show clock", color = PrintInk, fontSize = 16.sp)
                    Text("Time and date", color = PrintInk.copy(0.4f), fontSize = 12.sp)
                }
                Switch(
                    checked = state.showClock,
                    onCheckedChange = onShowClock,
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = VoidBlack,
                        checkedTrackColor = state.accent,
                        uncheckedTrackColor = Color.White.copy(alpha = 0.15f),
                    ),
                )
            }
            SettingsRow("Reset pins", "Four slots fill from use") { onResetPins() }
            SettingsRow(
                "Ask",
                when {
                    state.aiInstalled.isEmpty() ->
                        "Install Grok, ChatGPT, Gemini or Claude"
                    else ->
                        "${state.aiLabel} · swipe left, or triple-tap"
                },
            ) { onCycleAi() }
            SettingsRow(
                "Jev",
                when {
                    keyMissing -> "No OpenRouter key on the clipboard"
                    else -> when (state.brain) {
                        BrainStatus.Off -> "Copy an OpenRouter key, then tap. Search learns what you mean; quotes suit the print."
                        BrainStatus.Checking -> "Checking the key…"
                        BrainStatus.Ready -> "Search and quotes ask Jev; DeepSeek answers. Hold to remove the key."
                        BrainStatus.Unverified -> "Key saved. OpenRouter didn’t answer. Hold to remove."
                        BrainStatus.Rejected -> "OpenRouter refused that key. Copy another, then tap."
                    }
                },
                onLongClick = if (state.brain == BrainStatus.Off) {
                    null
                } else {
                    {
                        keyMissing = false
                        onClearOpenRouterKey()
                    }
                },
            ) {
                val clip = context.getSystemService(ClipboardManager::class.java)?.primaryClip
                val text = clip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(context)?.toString().orEmpty()
                keyMissing = !onSetOpenRouterKey(text)
            }
            SettingsRow(
                "Hidden apps",
                if (hiddenApps.isEmpty()) {
                    "Swipe an app right on the home screen to hide it"
                } else {
                    "${hiddenApps.size} hidden. Tap one to unhide."
                },
            ) { hiddenOpen = true }
            SettingsRow(
                "Better ranking",
                if (state.hasUsageAccess) {
                    "Last 30 days of opens, including before Folio"
                } else {
                    "Without this, ranking is only apps you open from Folio"
                },
            ) {
                context.startActivity(
                    Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                )
            }
            SettingsRow(
                "Now playing",
                if (state.hasNowPlayingAccess) {
                    "Previous, play, next above the four icons. Play starts Spotify."
                } else {
                    "Allow notification access so Folio can see what’s playing"
                },
            ) {
                context.startActivity(
                    Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                )
            }
            SettingsRow(
                "Set as default launcher",
                if (state.isDefaultHome) "Folio is Home" else "Open system picker",
            ) { onSetDefault() }
        }
        Spacer(Modifier.height(12.dp))
        Text(
            "Folio  ${state.versionName}",
            color = PrintInk.copy(alpha = 0.28f),
            fontSize = 12.sp,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            "Back",
            color = state.accent,
            modifier = Modifier.clickable(onClick = onBack),
            fontSize = 14.sp,
        )
    }
    if (hiddenOpen) {
        AppPicker(
            title = "Hidden",
            placeholder = "Unhide",
            empty = "Nothing hidden. Swipe an app right on the home screen.",
            apps = hiddenApps,
            launches = state.launches,
            accent = state.accent,
            onPick = { app ->
                onUnhideApp(app)
                if (hiddenApps.size <= 1) hiddenOpen = false
            },
            onDismiss = { hiddenOpen = false },
        )
    }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun SettingsRow(
    title: String,
    subtitle: String,
    onLongClick: (() -> Unit)? = null,
    onClick: () -> Unit,
) {
    Column(
        Modifier
            .fillMaxWidth()
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(vertical = 16.dp),
    ) {
        Text(title, color = PrintInk, fontSize = 16.sp)
        Spacer(Modifier.height(4.dp))
        Text(subtitle, color = PrintInk.copy(alpha = 0.4f), fontSize = 12.sp)
    }
}
