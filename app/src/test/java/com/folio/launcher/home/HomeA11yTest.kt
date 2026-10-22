package com.folio.launcher.home

import android.graphics.Bitmap
import android.os.Process
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performCustomAccessibilityActionWithLabel
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipe
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.folio.launcher.data.HomeUiState
import com.folio.launcher.data.LaunchableApp
import com.folio.launcher.data.OnboardingStep
import com.folio.launcher.data.RailSlot
import com.folio.launcher.data.RingerVisual
import com.folio.launcher.ui.FolioTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * Home for TalkBack, Switch Access and Voice Access. They act through accessibility actions, not
 * touches, so each everyday gesture has one; the gestures themselves are HomeGesturesTest's.
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-xxhdpi")
class HomeA11yTest {
    @get:Rule
    val compose = createComposeRule()

    private val calls = mutableListOf<String>()

    /** No Hermes Live: Grok answers Ask. */
    private val grok = HomeUiState(aiLabel = "Grok", aiPackage = "ai.x.grok")

    private fun app(label: String) = LaunchableApp(
        packageName = "com.example.${label.lowercase()}",
        activityName = "com.example.${label.lowercase()}.Main",
        user = Process.myUserHandle(),
        label = label,
        icon = Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888).asImageBitmap(),
    )

    private fun show(state: HomeUiState) {
        compose.setContent {
            FolioTheme {
                HomeScreen(
                    state = state,
                    idleEpoch = 0,
                    launches = emptyMap(),
                    onLaunch = { calls += "launch:${it.label}" },
                    onAppInfo = { calls += "appInfo:${it.label}" },
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
                    onHideApp = { calls += "hide:${it.label}" },
                    onAskAi = { calls += "askAi:$it" },
                    onOpenGoogleSearch = { calls += "google" },
                    onExpandShade = { calls += "shade" },
                )
            }
        }
        compose.waitForIdle()
    }

    private fun railWithMaps() = listOf(RailSlot(app("Maps"))) + List(3) { RailSlot() }

    @Test
    fun searchPill_tapIsSearch() {
        show(grok)
        compose.onNodeWithContentDescription("Search").performSemanticsAction(SemanticsActions.OnClick)
        compose.waitForIdle()
        compose.onNode(hasSetTextAction()).assertExists()
    }

    @Test
    fun searchPill_holdIsAsk() {
        show(grok)
        val pill = compose.onNodeWithContentDescription("Search")
        assertEquals("Ask", pill.fetchSemanticsNode().config[SemanticsActions.OnLongClick].label)
        pill.performSemanticsAction(SemanticsActions.OnLongClick)
        compose.waitForIdle()
        compose.onNodeWithText("Ask Grok").assertExists()
    }

    @Test
    fun print_offersNextPrintRingerAskAndSettings() {
        show(grok.copy(mode = RingerVisual.Sound))
        val print = compose.onNodeWithContentDescription("Print")
        assertEquals(
            listOf("Next print", "Set ringer to Vibrate", "Ask Grok", "Open Settings"),
            print.fetchSemanticsNode().config[SemanticsActions.CustomActions].map { it.label },
        )
        print.performCustomAccessibilityActionWithLabel("Next print")
        print.performCustomAccessibilityActionWithLabel("Open Settings")
        print.performCustomAccessibilityActionWithLabel("Set ringer to Vibrate")
        compose.waitForIdle()
        assertEquals(listOf("nextBing", "settings", "ringer:Vibrate"), calls)
    }

    @Test
    fun print_askOpensTheAskOverlay_whichThenHasThePrintsTaps() {
        show(grok)
        compose.onNodeWithContentDescription("Print").performCustomAccessibilityActionWithLabel("Ask Grok")
        compose.waitForIdle()
        compose.onNodeWithText("Ask Grok").assertExists()
        compose.onNodeWithContentDescription("Print").assertDoesNotExist()
    }

    @Test
    fun playback_buttonsAreNamedAndAct() {
        show(grok)
        compose.onNodeWithContentDescription("Previous track").performSemanticsAction(SemanticsActions.OnClick)
        compose.onNodeWithContentDescription("Play").performSemanticsAction(SemanticsActions.OnClick)
        compose.onNodeWithContentDescription("Play").performSemanticsAction(SemanticsActions.OnLongClick)
        compose.onNodeWithContentDescription("Next track").performSemanticsAction(SemanticsActions.OnClick)
        compose.waitForIdle()
        assertEquals(listOf("previousTrack", "playPause", "openPlayer", "skipTrack"), calls)
    }

    @Test
    fun playback_whilePlaying_isPause() {
        show(grok.copy(musicPlaying = true))
        compose.onNodeWithContentDescription("Pause").assertExists()
        compose.onNodeWithContentDescription("Play").assertDoesNotExist()
    }

    @Test
    fun railIcon_opensHidesAndPins() {
        show(grok.copy(rail = railWithMaps()))
        val maps = compose.onNodeWithContentDescription("Maps")
        maps.performSemanticsAction(SemanticsActions.OnClick)
        maps.performCustomAccessibilityActionWithLabel("Hide from home")
        compose.waitForIdle()
        assertEquals(listOf("launch:Maps", "hide:Maps"), calls)
        assertEquals("Pin", maps.fetchSemanticsNode().config[SemanticsActions.OnLongClick].label)
        maps.performSemanticsAction(SemanticsActions.OnLongClick)
        compose.waitForIdle()
        compose.onNodeWithText("Pin").assertExists()
    }

    @Test
    fun emptyRailSlot_holdIsPin() {
        show(grok)
        compose.onAllNodesWithContentDescription("Empty slot")[0].performSemanticsAction(SemanticsActions.OnLongClick)
        compose.waitForIdle()
        compose.onNodeWithText("Pin").assertExists()
    }

    @Test
    fun underThePinPicker_playbackCantBeReached() {
        show(grok)
        compose.onAllNodesWithContentDescription("Empty slot")[0].performSemanticsAction(SemanticsActions.OnLongClick)
        compose.waitForIdle()
        compose.onNodeWithText("Pin").assertExists()
        // The picker's scrim takes the strip's taps; TalkBack's too.
        compose.onNodeWithContentDescription("Previous track").assertDoesNotExist()
        compose.onNodeWithContentDescription("Play").assertDoesNotExist()
        compose.onNodeWithContentDescription("Next track").assertDoesNotExist()
    }

    @Test
    fun drawerIcon_opensShowsItsMenuAndHides() {
        show(grok.copy(apps = listOf(app("Maps"))))
        // Up to the peek, where the drawer's icons are on screen.
        compose.onRoot().performTouchInput {
            val at = Offset(width / 2f, height * 0.45f)
            swipe(start = at, end = at - Offset(0f, 100.dp.toPx()), durationMillis = 200)
        }
        compose.mainClock.advanceTimeBy(1_000)
        compose.waitForIdle()
        val maps = compose.onNodeWithContentDescription("Maps")
        assertEquals("Shortcuts", maps.fetchSemanticsNode().config[SemanticsActions.OnLongClick].label)
        maps.performSemanticsAction(SemanticsActions.OnLongClick)
        compose.waitForIdle()
        // The hold's menu: its rows are buttons, App info the last (here the only one: no shortcuts).
        compose.onNodeWithText("App info")
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Button))
            .performSemanticsAction(SemanticsActions.OnClick)
        compose.waitForIdle()
        maps.performCustomAccessibilityActionWithLabel("Hide from home")
        maps.performSemanticsAction(SemanticsActions.OnClick)
        compose.waitForIdle()
        assertEquals(listOf("appInfo:Maps", "hide:Maps", "launch:Maps"), calls)
    }

    @Test
    fun underOnboarding_neitherTheRailNorThePrintActs() {
        show(grok.copy(onboarding = OnboardingStep.Access, rail = railWithMaps()))
        // The rail shows through onboarding's scrim, which takes its taps; TalkBack's too.
        compose.onNodeWithContentDescription("Maps")
            .assert(SemanticsMatcher.keyNotDefined(SemanticsActions.OnClick))
            .assert(SemanticsMatcher.keyNotDefined(SemanticsActions.OnLongClick))
            .assert(SemanticsMatcher.keyNotDefined(SemanticsActions.CustomActions))
        compose.onNodeWithContentDescription("Print").assertDoesNotExist()
    }
}
