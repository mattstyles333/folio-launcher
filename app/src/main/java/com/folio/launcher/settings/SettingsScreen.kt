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
import androidx.compose.foundation.layout.width
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.folio.launcher.data.BrainStatus
import com.folio.launcher.data.HomeUiState
import com.folio.launcher.data.LaunchableApp
import com.folio.launcher.data.PrintController
import com.folio.launcher.data.Ranking
import com.folio.launcher.hermes.HermesAvailability
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
    onDiagnostics: (Boolean) -> Unit = {},
    onResetPins: () -> Unit,
    onSetDefault: () -> Unit,
    onUnhideApp: (LaunchableApp) -> Unit,
    onCycleAi: () -> Unit,
    onSetOpenRouterKey: (String) -> Boolean,
    onClearOpenRouterKey: () -> Unit,
    onOpenHermesLive: () -> Unit = {},
    onRefreshHermes: () -> Unit = {},
) {
    val context = LocalContext.current
    LaunchedEffect(Unit) { onRefreshHermes() }
    var hiddenOpen by remember { mutableStateOf(false) }
    var keyMissing by remember { mutableStateOf(false) }
    val hiddenApps = remember(state.apps, state.hiddenPackages) {
        state.apps.filter { it.packageName in state.hiddenPackages }
            .distinctBy { it.packageName }
            .sortedWith(compareBy(Ranking.labelOrder()) { it.label })
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
            // The picker comes back here, not to the clock, so a photo that can't be read says so here.
            SettingsRow(
                "Choose photo",
                state.wallpaperCaption.takeIf { it == PrintController.PHOTO_FAILED } ?: "Replace the print",
            ) { onPickPhoto() }
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
            SwitchRow(
                "Diagnostics",
                if (state.diagnosticsBuiltIn) DIAGNOSTICS_PRIVACY else "This build has no diagnostics endpoint; nothing is sent.",
                state.diagnostics && state.diagnosticsBuiltIn,
                state.accent,
                enabled = state.diagnosticsBuiltIn,
                tag = "diagnostics",
                onChange = onDiagnostics,
            )
            SettingsRow("Reset pins", "Four slots fill from use") { onResetPins() }
            SettingsRow(
                "Ask",
                when {
                    state.aiLabel.isEmpty() ->
                        "Install Grok, ChatGPT, Gemini or Claude"
                    state.askHermes ->
                        "Hermes · triple-tap or hold Search; swipe left to talk"
                    else ->
                        "${state.aiLabel} · swipe left, or triple-tap"
                },
            ) { onCycleAi() }
            SettingsRow(
                "Hermes",
                when (state.hermes) {
                    HermesAvailability.NotInstalled -> "Install Hermes Live from the Folio release to talk to your Hermes"
                    HermesAvailability.SignedOut -> "Open Hermes Live to sign in"
                    HermesAvailability.Unreachable -> "Signed in, but your Hermes isn't answering. Check the connection"
                    HermesAvailability.Ready -> if (state.askHermes) {
                        "Hermes answers Ask; swipe left to talk"
                    } else {
                        "Ready. Tap Ask until it says Hermes."
                    }
                },
            ) { onOpenHermesLive() }
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
                val clipboard = context.getSystemService(ClipboardManager::class.java)
                // The clip's own text only: coerceToText reads a URI clip's whole stream on the main thread.
                val text = clipboard?.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.text?.toString().orEmpty()
                val saved = onSetOpenRouterKey(text)
                keyMissing = !saved
                // The key lives only in Prefs now; don't leave it on the clipboard for the next paste.
                if (saved) clipboard?.clearPrimaryClip()
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
                    "Recent opens (Android keeps about a week to ten days), including before Folio"
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
                    // Android 13+ greys the switch out for an APK installed from a browser or file manager.
                    "Allow notification access so Folio can see what’s playing. Switch greyed out? " +
                        "Settings › Apps › Folio › ⋮ › Allow restricted settings."
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

const val DIAGNOSTICS_PRIVACY =
    "Crashes, errors and start-up time, with app version, phone model, Android version and a random install ID. " +
        "Never what you type or say, answers, or sign-in details."

@Composable
private fun SwitchRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    accent: Color,
    enabled: Boolean,
    tag: String,
    onChange: (Boolean) -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 16.dp)
            .testTag(tag),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, color = PrintInk, fontSize = 16.sp)
            Text(subtitle, color = PrintInk.copy(0.4f), fontSize = 12.sp)
        }
        Spacer(Modifier.width(12.dp))
        Switch(
            checked = checked,
            onCheckedChange = onChange,
            enabled = enabled,
            colors = SwitchDefaults.colors(
                checkedThumbColor = VoidBlack,
                checkedTrackColor = accent,
                uncheckedTrackColor = Color.White.copy(alpha = 0.15f),
            ),
        )
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
