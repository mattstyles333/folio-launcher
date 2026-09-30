package com.folio.launcher.data

import android.os.UserHandle
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap

enum class RingerVisual { Sound, Vibrate, Silent }

enum class OnboardingStep { Role, Wallpaper, Access }

/** The dog on the print, and the door it guards. */
data class PetUi(
    val sheet: PetSheet? = null,
    val mood: PetMood = PetMood.Content,
    /** The move to play now; [beat] changes to replay the same one. */
    val move: PetMove = PetMove.Idle,
    val beat: Int = 0,
    val line: String = "",
    val tag: String = "",
    val door: Door? = null,
)

/** An endless app about to open from Folio, held for a moment. */
data class Door(val app: LaunchableApp, val today: String)

/** Jev in search: no key, key being checked, working, refused, or saved but unreachable. */
enum class BrainStatus { Off, Checking, Ready, Rejected, Unverified }

data class LaunchableApp(
    val packageName: String,
    val activityName: String,
    val user: UserHandle,
    val label: String,
    val icon: ImageBitmap,
    val isHome: Boolean = false,
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
    val mode: RingerVisual = RingerVisual.Sound,
    val showClock: Boolean = true,
    val wallpaper: ImageBitmap? = null,
    val accent: Color = Color(0xFFC4B8A8),
    val onboarding: OnboardingStep? = null,
    val silentHint: Boolean = false,
    val mediaHint: Boolean = false,
    val needsDndAccess: Boolean = false,
    val isDefaultHome: Boolean = false,
    val hasUsageAccess: Boolean = false,
    val hasDndAccess: Boolean = false,
    val systemWallpaperReadable: Boolean = false,
    val versionName: String = "1.0.0",
    val launches: Map<String, List<Long>> = emptyMap(),
    val wallpaperBusy: Boolean = false,
    val wallpaperCaption: String = "",
    val hasPreviousPrint: Boolean = false,
    val charging: Boolean = false,
    val charge: Float = 0f,
    val musicPlaying: Boolean = false,
    val hasNowPlayingAccess: Boolean = false,
    val quote: String = "",
    val quoteAuthor: String = "",
    val hiddenPackages: Set<String> = emptySet(),
    val aiPackage: String = "",
    val aiLabel: String = "",
    val aiInstalled: List<AiKind> = emptyList(),
    val brain: BrainStatus = BrainStatus.Off,
    val hasPet: Boolean = false,
    /** Why the last pet import failed, for Settings. */
    val petNote: String = "",
    val endless: Set<String> = emptySet(),
    val scrollGoal: Int = 60,
    val doorPause: Boolean = true,
    val petWeather: Boolean = false,
)
