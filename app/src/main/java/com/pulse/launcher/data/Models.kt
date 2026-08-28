package com.pulse.launcher.data

import android.os.UserHandle
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap

enum class RingerVisual { Sound, Vibrate, Silent }

enum class OnboardingStep { Role, Wallpaper, Access }

data class LaunchableApp(
    val packageName: String,
    val activityName: String,
    val user: UserHandle,
    val label: String,
    val icon: ImageBitmap,
) {
    val key: String get() = "$packageName/$activityName"
}

data class RailSlot(
    val app: LaunchableApp? = null,
    val pinned: Boolean = false,
)

data class RecentItem(
    val app: LaunchableApp,
    val lastUsed: Long,
)

data class HomeUiState(
    val apps: List<LaunchableApp> = emptyList(),
    val rail: List<RailSlot> = List(4) { RailSlot() },
    val recents: List<RecentItem> = emptyList(),
    val reveal: List<LaunchableApp> = emptyList(),
    val mode: RingerVisual = RingerVisual.Sound,
    val showClock: Boolean = true,
    val wallpaper: ImageBitmap? = null,
    val blurredWallpaper: ImageBitmap? = null,
    val accent: Color = Color(0xFFC4B8A8),
    val onboarding: OnboardingStep? = null,
    val silentHint: Boolean = false,
    val needsDndAccess: Boolean = false,
    val isDefaultHome: Boolean = false,
    val hasUsageAccess: Boolean = false,
    val hasDndAccess: Boolean = false,
    val systemWallpaperReadable: Boolean = false,
    val versionName: String = "1.0.0",
    val launches: Map<String, List<Long>> = emptyMap(),
    val wallpaperBusy: Boolean = false,
    val wallpaperCaption: String = "",
    val charging: Boolean = false,
    val charge: Float = 0f,
    val nowPlaying: String = "",
    val musicPlaying: Boolean = false,
    val musicArt: ImageBitmap? = null,
    val hasNowPlayingAccess: Boolean = false,
    val quote: String = "",
    val quoteAuthor: String = "",
)
