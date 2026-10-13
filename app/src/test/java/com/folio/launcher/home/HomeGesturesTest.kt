package com.folio.launcher.home

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.click
import androidx.compose.ui.test.doubleClick
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performTextInput
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
import com.folio.launcher.data.SearchHint
import com.folio.launcher.data.SearchRoute
import com.folio.launcher.hermes.HermesAvailability
import com.folio.launcher.search.HermesAskActions
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

    private val hermesActions = HermesAskActions(
        ask = { calls += "hermesAsk:$it" },
        stop = { calls += "hermesStop" },
        newSession = { calls += "hermesNew" },
        approve = { calls += "hermesApprove:$it" },
        clarify = { calls += "hermesClarify:$it" },
        call = { calls += "callHermes:$it" },
        visible = { calls += "hermesVisible:$it" },
    )

    /** Hermes Live installed, signed in and chosen for Ask. */
    private val hermesState = HomeUiState(
        aiLabel = "Hermes",
        aiPackage = "com.folio.hermeslive",
        hermes = HermesAvailability.Ready,
        askHermes = true,
    )

    /** No Hermes Live: Grok answers Ask, as in 2.1.0. */
    private val grokState = HomeUiState(aiLabel = "Grok", aiPackage = "ai.x.grok")

    private fun show(state: HomeUiState = HomeUiState(), searchHint: SearchHint = SearchHint()) {
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
                    searchHint = searchHint,
                    onSearchRoute = { route, query -> calls += "route:$route:$query" },
                    hermesActions = hermesActions,
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

    @Test
    fun swipeLeftOnPrint_withHermes_startsAHermesLiveCall() {
        show(hermesState)
        compose.onRoot().performTouchInput {
            swipeLeft(startX = width * 0.9f, endX = width * 0.1f, durationMillis = 200)
        }
        compose.waitForIdle()
        assertEquals(listOf("callHermes:"), calls)
    }

    @Test
    fun longPressSearch_withoutHermes_isTheAppAskOverlay() {
        show(grokState)
        compose.onNodeWithContentDescription("Search").performTouchInput { longClick() }
        compose.waitForIdle()
        compose.onNodeWithText("Ask Grok").assertExists()
        compose.onNodeWithContentDescription("Talk to Hermes").assertDoesNotExist()
        compose.onNodeWithText("Open Grok").performClick()
        compose.waitForIdle()
        assertEquals(listOf("askAi:"), calls)
    }

    @Test
    fun longPressSearch_withHermes_opensHermesOverlayAndConnectsOnlyWhileShown() {
        show(hermesState)
        compose.onNodeWithContentDescription("Search").performTouchInput { longClick() }
        compose.waitForIdle()
        compose.onNodeWithText("Ask Hermes").assertExists()
        compose.onNodeWithContentDescription("Talk to Hermes").assertExists()
        assertEquals(listOf("hermesVisible:true"), calls)
        compose.onNodeWithText("Cancel").performClick()
        compose.waitForIdle()
        assertEquals(listOf("hermesVisible:true", "hermesVisible:false"), calls)
    }

    @Test
    fun tripleTapPrint_withHermes_opensHermesOverlay() {
        show(hermesState)
        val at = printPoint()
        compose.onRoot().performTouchInput {
            click(at)
            advanceEventTime(90)
            click(at)
            advanceEventTime(90)
            click(at)
        }
        compose.mainClock.advanceTimeBy(1_000)
        compose.waitForIdle()
        compose.onNodeWithText("Ask Hermes").assertExists()
        assertEquals(listOf("hermesVisible:true"), calls)
    }

    @Test
    fun searchAskRoute_withHermes_asksHermesInTheOverlay() {
        show(hermesState, SearchHint("what is stoicism", SearchRoute.Ask))
        compose.onNodeWithContentDescription("Search").performClick()
        compose.waitForIdle()
        compose.onNode(hasSetTextAction()).performTextInput("what is stoicism")
        compose.waitForIdle()
        compose.onNode(hasSetTextAction()).performImeAction()
        compose.waitForIdle()
        assertEquals(listOf("hermesAsk:what is stoicism", "hermesVisible:true"), calls)
        compose.onNodeWithText("Ask Hermes").assertExists()
    }

    @Test
    fun searchAskRoute_withoutHermes_isUnchanged() {
        show(grokState, SearchHint("what is stoicism", SearchRoute.Ask))
        compose.onNodeWithContentDescription("Search").performClick()
        compose.waitForIdle()
        compose.onNode(hasSetTextAction()).performTextInput("what is stoicism")
        compose.waitForIdle()
        compose.onNode(hasSetTextAction()).performImeAction()
        compose.waitForIdle()
        assertEquals(listOf("route:Ask:what is stoicism"), calls)
    }
}
