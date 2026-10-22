package com.folio.launcher.home

import android.graphics.Bitmap
import android.os.Process
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.click
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipe
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.folio.launcher.data.HomeUiState
import com.folio.launcher.data.LaunchableApp
import com.folio.launcher.data.RingerVisual
import com.folio.launcher.ui.FolioTheme
import java.time.LocalDate
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** What the real HomeScreen shows after the ringer, Home, the sheet or a stop changed under it. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-xxhdpi")
class HomeScreenStateTest {
    @get:Rule
    val compose = createComposeRule()

    private val calls = mutableListOf<String>()
    private var state by mutableStateOf(HomeUiState())
    private var idleEpoch by mutableIntStateOf(0)

    private fun show(first: HomeUiState = HomeUiState(), direction: LayoutDirection = LayoutDirection.Ltr) {
        state = first
        compose.setContent {
            CompositionLocalProvider(LocalLayoutDirection provides direction) {
                FolioTheme {
                    HomeScreen(
                        state = state,
                        idleEpoch = idleEpoch,
                        launches = emptyMap(),
                        onLaunch = { calls += "launch:${it.label}" },
                        onAppInfo = { calls += "appInfo" },
                        onPin = { _, _ -> calls += "pin" },
                        onReorder = { _, _ -> calls += "reorder" },
                        // As RingerController.set does: the new mode is published at once.
                        onSetRinger = {
                            calls += "ringer:$it"
                            state = state.copy(mode = it)
                        },
                        onOpenSettings = { calls += "settings" },
                        onPickPhoto = { calls += "pickPhoto" },
                        onSetDefault = { calls += "setDefault" },
                        onSkipRole = { calls += "skipRole" },
                        onSkipWallpaper = { calls += "skipWallpaper" },
                        onUseSystemWallpaper = { calls += "systemWallpaper" },
                        onOpenDnd = { calls += "openDnd" },
                        onSkipAccess = { calls += "skipAccess" },
                        onSilentHint = { calls += "silentHint" },
                        onMediaHint = { calls += "mediaHint" },
                        onNextBing = { calls += "nextBing" },
                        onPreviousTrack = { calls += "previousTrack" },
                        onPlayPause = { calls += "playPause" },
                        onSkipTrack = { calls += "skipTrack" },
                        onOpenPlayer = { calls += "openPlayer" },
                        onHideApp = { calls += "hide" },
                        onAskAi = { calls += "askAi:$it" },
                        onOpenGoogleSearch = { calls += "google" },
                    )
                }
            }
        }
        compose.waitForIdle()
    }

    /** Middle of the print: below the clock, above the playback strip and rail. */
    private fun printPoint() = compose.onRoot().fetchSemanticsNode().size.let {
        Offset(it.width / 2f, it.height * 0.45f)
    }

    private fun app(label: String) = LaunchableApp(
        packageName = "com.example.${label.lowercase()}",
        activityName = "com.example.${label.lowercase()}.Main",
        user = Process.myUserHandle(),
        label = label,
        icon = Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888).asImageBitmap(),
    )

    private fun timeAt(hour: Int, minute: Int): Long =
        LocalDate.of(2026, 10, 21).atTime(hour, minute).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()

    @Test
    fun longPress_thenRingerChangedElsewhere_lookFollowsTheRinger() {
        show(HomeUiState(mode = RingerVisual.Sound))
        val at = printPoint()
        compose.onRoot().performTouchInput { longClick(at) }
        compose.mainClock.advanceTimeBy(2_000)
        compose.waitForIdle()
        // A volume key or Quick Settings puts the phone back on Sound.
        state = state.copy(mode = RingerVisual.Sound)
        compose.waitForIdle()
        compose.mainClock.advanceTimeBy(2_000)
        compose.onRoot().performTouchInput { longClick(at) }
        compose.waitForIdle()
        assertEquals(listOf("ringer:Vibrate", "ringer:Vibrate"), calls)
    }

    @Test
    fun homeDuringADevelop_bringsBackTheQuoteAndTheRealMode() {
        show(HomeUiState(mode = RingerVisual.Sound, quote = "Stay hungry."))
        val at = printPoint()
        compose.onRoot().performTouchInput { longClick(at) }
        // Home lands while the print is still developing.
        idleEpoch++
        compose.waitForIdle()
        compose.mainClock.advanceTimeBy(2_000)
        compose.waitForIdle()
        compose.onNodeWithText("“Stay hungry.”").assertExists()
        compose.onRoot().performTouchInput { longClick(at) }
        compose.waitForIdle()
        assertEquals(listOf("ringer:Vibrate", "ringer:Silent"), calls)
    }

    @Test
    fun hiddenClock_longPressWhereItSat_opensSettings() {
        show(HomeUiState(showClock = false))
        compose.onNodeWithTag("clock").performTouchInput { longClick() }
        compose.waitForIdle()
        assertEquals(listOf("settings"), calls)
    }

    @Test
    fun hiddenClock_talkBackFindsSettingsWhereItSat() {
        show(HomeUiState(showClock = false))
        // TalkBack's double-tap and hold on the focused slot.
        compose.onNodeWithContentDescription("Settings").performSemanticsAction(SemanticsActions.OnLongClick)
        compose.waitForIdle()
        assertEquals(listOf("settings"), calls)
    }

    @Test
    fun hiddenClock_underAQuote_longPressWhereItSat_opensSettings() {
        show(HomeUiState(showClock = false, quote = "Stay hungry."))
        compose.onNodeWithTag("clock").performTouchInput { longClick() }
        compose.waitForIdle()
        assertEquals(listOf("settings"), calls)
    }

    @Test
    fun clock_readsTheTimeAgainWhenHomeStarts() {
        var wall = timeAt(10, 0)
        val owner = object : LifecycleOwner {
            val registry = LifecycleRegistry.createUnsafe(this)
            override val lifecycle: Lifecycle get() = registry
        }
        owner.registry.currentState = Lifecycle.State.RESUMED
        compose.setContent {
            CompositionLocalProvider(LocalLifecycleOwner provides owner) {
                ClockDisplay(dim = false, onTap = {}, onLongPress = {}, clock = { wall })
            }
        }
        compose.onNodeWithText("10:00").assertExists()
        // Screen off: home stops, and the phone sleeps through half an hour of ticks.
        compose.runOnIdle { owner.registry.currentState = Lifecycle.State.CREATED }
        wall = timeAt(10, 30)
        compose.runOnIdle { owner.registry.currentState = Lifecycle.State.RESUMED }
        compose.onNodeWithText("10:30").assertExists()
    }

    @Test
    fun peekSheet_tapOverTheFadedStrip_launchesTheApp() {
        val labels = listOf("Alpha", "Bravo", "Charlie", "Delta", "Echo", "Foxtrot", "Golf", "Hotel")
        show(HomeUiState(apps = labels.map { app(it) }))
        val at = printPoint()
        compose.onRoot().performTouchInput {
            swipe(start = at, end = at - Offset(0f, 100.dp.toPx()), durationMillis = 200)
        }
        compose.mainClock.advanceTimeBy(1_000)
        compose.waitForIdle()
        // At peek, row 1's labels run under the faded play button. The label itself, not its whole
        // cell (which TalkBack reads as one): its middle is under the button.
        compose.onNodeWithText("Bravo", useUnmergedTree = true)
            .performTouchInput { click(Offset(width - 10.dp.toPx(), centerY)) }
        compose.waitForIdle()
        assertEquals(listOf("launch:Bravo"), calls)
    }

    @Test
    fun idleStrip_playStillPlays() {
        show()
        compose.onRoot().performTouchInput { click(Offset(centerX, height - 182.dp.toPx())) }
        compose.waitForIdle()
        assertEquals(listOf("playPause"), calls)
    }

    @Test
    fun rightToLeft_previousStaysOnTheLeft() {
        show(direction = LayoutDirection.Rtl)
        compose.onRoot().performTouchInput { click(Offset(centerX - 78.dp.toPx(), height - 182.dp.toPx())) }
        compose.waitForIdle()
        assertEquals(listOf("previousTrack"), calls)
    }

    @Test
    fun caption_fadesOutWhenItClears() {
        show(HomeUiState(wallpaperCaption = "Lavender fields, Provence"))
        compose.onNodeWithText("Lavender fields, Provence").assertExists()
        compose.mainClock.autoAdvance = false
        // Two seconds after the new print's title showed, PrintController clears it.
        state = state.copy(wallpaperCaption = "")
        // Hands the clear to the recomposer; the paused clock holds its frame until we advance.
        compose.waitForIdle()
        compose.mainClock.advanceTimeBy(150)
        // On its way out, still reading the same words: a fade, not a cut.
        compose.onNodeWithText("Lavender fields, Provence").assertExists()
        compose.mainClock.advanceTimeBy(2_000)
        compose.onNodeWithText("Lavender fields, Provence").assertDoesNotExist()
    }

    @Test
    fun mediaHint_sitsBelowTheQuote_notOverIt() {
        show(HomeUiState(mediaHint = true, quote = "Stay hungry."))
        val quote = compose.onNodeWithText("“Stay hungry.”").getBoundsInRoot()
        val hint = compose.onNodeWithText("Tap so Folio can see what", substring = true)
        assertTrue(hint.getBoundsInRoot().top >= quote.bottom)
        hint.performClick()
        compose.waitForIdle()
        assertEquals(listOf("mediaHint"), calls)
    }
}
