package com.folio.launcher.home

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.click
import androidx.compose.ui.test.doubleClick
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeLeft
import androidx.compose.ui.test.swipeRight
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.folio.launcher.data.HomeUiState
import com.folio.launcher.data.OnboardingStep
import com.folio.launcher.data.RingerVisual
import com.folio.launcher.ui.FolioTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** The touch contracts in AGENTS.md, driven against the real HomeScreen. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-xxhdpi")
class HomeGesturesTest {
    @get:Rule
    val compose = createComposeRule()

    private val calls = mutableListOf<String>()

    private fun show(state: HomeUiState = HomeUiState()) {
        compose.setContent {
            FolioTheme {
                HomeScreen(
                    state = state,
                    idleEpoch = 0,
                    launches = emptyMap(),
                    onLaunch = { calls += "launch" },
                    onAppInfo = { calls += "appInfo" },
                    onPin = { _, _ -> calls += "pin" },
                    onReorder = { _, _ -> calls += "reorder" },
                    onSetRinger = { calls += "ringer:$it" },
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
        compose.waitForIdle()
    }

    /** Middle of the print: below the clock, above the playback strip and rail. */
    private fun printPoint() = compose.onRoot().fetchSemanticsNode().size.let {
        Offset(it.width / 2f, it.height * 0.45f)
    }

    @Test
    fun doubleTapPrint_fetchesNextBing() {
        show()
        val at = printPoint()
        compose.onRoot().performTouchInput { doubleClick(at) }
        compose.mainClock.advanceTimeBy(1_000)
        compose.waitForIdle()
        assertEquals(listOf("nextBing"), calls)
    }

    @Test
    fun singleTapPrint_doesNothing() {
        show()
        val at = printPoint()
        compose.onRoot().performTouchInput { click(at) }
        compose.mainClock.advanceTimeBy(1_000)
        compose.waitForIdle()
        assertEquals(emptyList<String>(), calls)
    }

    @Test
    fun longPressPrint_cyclesRingerFromCurrentMode() {
        show(HomeUiState(mode = RingerVisual.Sound))
        val at = printPoint()
        compose.onRoot().performTouchInput { longClick(at) }
        compose.waitForIdle()
        assertEquals(listOf("ringer:Vibrate"), calls)
    }

    @Test
    fun longPressPrint_fromSilentGoesToSound() {
        show(HomeUiState(mode = RingerVisual.Silent))
        val at = printPoint()
        compose.onRoot().performTouchInput { longClick(at) }
        compose.waitForIdle()
        assertEquals(listOf("ringer:Sound"), calls)
    }

    @Test
    fun swipeLeftOnPrint_opensAi() {
        show()
        // Swipes run along centerY, which is on the print.
        compose.onRoot().performTouchInput {
            swipeLeft(startX = width * 0.9f, endX = width * 0.1f, durationMillis = 200)
        }
        compose.waitForIdle()
        assertEquals(listOf("askAi:"), calls)
    }

    @Test
    fun swipeRightOnPrint_opensGoogle() {
        show()
        compose.onRoot().performTouchInput {
            swipeRight(startX = width * 0.1f, endX = width * 0.9f, durationMillis = 200)
        }
        compose.waitForIdle()
        assertEquals(listOf("google"), calls)
    }

    @Test
    fun onboarding_blocksPrintGestures() {
        show(HomeUiState(onboarding = OnboardingStep.Access))
        val at = printPoint()
        compose.onRoot().performTouchInput { doubleClick(at) }
        compose.mainClock.advanceTimeBy(1_000)
        compose.onRoot().performTouchInput {
            swipeLeft(startX = width * 0.9f, endX = width * 0.1f, durationMillis = 200)
        }
        compose.waitForIdle()
        assertEquals(emptyList<String>(), calls.filter { it == "nextBing" || it.startsWith("askAi") })
    }
}
