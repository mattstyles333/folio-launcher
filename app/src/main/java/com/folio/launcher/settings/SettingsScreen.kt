package com.folio.launcher.settings

import android.content.Intent
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.folio.launcher.data.HomeUiState
import com.folio.launcher.ui.PrintInk
import com.folio.launcher.ui.VoidBlack

@Composable
fun SettingsScreen(
    state: HomeUiState,
    onBack: () -> Unit,
    onPickPhoto: () -> Unit,
    onNextBing: () -> Unit,
    onShowClock: (Boolean) -> Unit,
    onResetPins: () -> Unit,
    onSetDefault: () -> Unit,
) {
    val context = LocalContext.current
    BackHandler(onBack = onBack)
    Column(
        Modifier
            .fillMaxSize()
            .background(VoidBlack)
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
        SettingsRow("Choose photo", "Replace the print") { onPickPhoto() }
        SettingsRow("Bing print", "Double-tap the print for the next one") { onNextBing() }
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
                "Previous, album art, next above the four icons. Long-press the art to open Spotify."
            } else {
                "Allow notification access so Folio can show Spotify controls"
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
        Spacer(Modifier.weight(1f))
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
}

@Composable
private fun SettingsRow(title: String, subtitle: String, onClick: () -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 16.dp),
    ) {
        Text(title, color = PrintInk, fontSize = 16.sp)
        Spacer(Modifier.height(4.dp))
        Text(subtitle, color = PrintInk.copy(alpha = 0.4f), fontSize = 12.sp)
    }
}
