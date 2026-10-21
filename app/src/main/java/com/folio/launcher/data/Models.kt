package com.folio.launcher.data

import android.os.UserHandle
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import com.folio.launcher.hermes.HermesAvailability

enum class RingerVisual { Sound, Vibrate, Silent }

enum class OnboardingStep { Role, Wallpaper, Access }

/** Jev in search: no key, key being checked, working, refused, saved but unreachable, or out of credit. */
enum class BrainStatus { Off, Checking, Ready, Rejected, Unverified, NoCredit }

data class LaunchableApp(
    val packageName: String,
    val activityName: String,
    val user: UserHandle,
    val label: String,
    val icon: ImageBitmap,
    val isHome: Boolean = false,
    /** Paused by an app timer, Focus mode or an admin: opening it only shows the system's "App paused". */
    val paused: Boolean = false,
) {
    val key: String get() = "$packageName/$activityName"
}

data class RailSlot(
    val app: LaunchableApp? = null,
    val pinned: Boolean = false,
)

data class HomeUiState(
    val apps: List<LaunchableApp> = emptyList(),
    val rail: List<RailSlot> = List(Ranking.RAIL_SLOTS) { RailSlot() },
    val mode: RingerVisual = RingerVisual.Sound,
    val showClock: Boolean = true,
    /** Send crash reports, errors and start-up time (Settings switch; on by default). */
    val diagnostics: Boolean = true,
    /** This build has a diagnostics endpoint (release CI); false in local and debug builds. */
    val diagnosticsBuiltIn: Boolean = false,
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
    /** Hermes Live installed and signed in? Never holds the token. */
    val hermes: HermesAvailability = HermesAvailability.NotInstalled,
    /** Ask is answered by Hermes (in the overlay; swipe left calls it). */
    val askHermes: Boolean = false,
    val brain: BrainStatus = BrainStatus.Off,
)
