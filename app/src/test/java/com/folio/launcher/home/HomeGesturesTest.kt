package com.folio.launcher.home

import android.graphics.Bitmap
import android.os.Process
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotDisplayed
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
import androidx.compose.ui.test.swipeDown
import androidx.compose.ui.test.swipeLeft
import androidx.compose.ui.test.swipeRight
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.folio.launcher.data.HomeUiState
import com.folio.launcher.data.LaunchableApp
import com.folio.launcher.data.OnboardingStep
import com.folio.launcher.data.RailSlot
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

    private fun app(label: String, pkg: String) = LaunchableApp(
        packageName = pkg,
        activityName = "$pkg.Main",
        user = Process.myUserHandle(),
        label = label,
        icon = Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888).asImageBitmap(),
    )

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
                    onExpandShade = { calls += "shade" },
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
    fun longPressPrint_withASmallDrift_stillCyclesRinger() {
        show(HomeUiState(mode = RingerVisual.Sound))
        val at = printPoint()
        compose.onRoot().performTouchInput {
            down(at)
            // A resting finger creeps a little: still a hold.
            moveBy(Offset(0f, viewConfiguration.touchSlop * 2))
            advanceEventTime(viewConfiguration.longPressTimeoutMillis + 100)
            up()
        }
        compose.waitForIdle()
        assertEquals(listOf("ringer:Vibrate"), calls)
    }

    @Test
    fun slowPullDownOnPrint_doesNotCycleRinger() {
        show(HomeUiState(mode = RingerVisual.Sound))
        val at = printPoint()
        // Slower than a long-press: the pull for the shade must not also count as a hold.
        compose.onRoot().performTouchInput {
            swipeDown(startY = at.y, endY = at.y + height * 0.3f, durationMillis = 800)
        }
        compose.mainClock.advanceTimeBy(1_000)
        compose.waitForIdle()
        assertEquals(listOf("shade"), calls)
    }

    @Test
    fun swipeDownOnPrint_opensShadeNotSearch() {
        show()
        val at = printPoint()
        compose.onRoot().performTouchInput {
            swipeDown(startY = at.y, endY = at.y + height * 0.2f, durationMillis = 200)
        }
        compose.mainClock.advanceTimeBy(1_000)
        compose.waitForIdle()
        assertEquals(listOf("shade"), calls)
        compose.onNode(hasSetTextAction()).assertDoesNotExist()
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
    fun shortFlickLeftOnPrint_withHermes_stillStartsACall() {
        show(hermesState)
        val at = printPoint()
        compose.onRoot().performTouchInput {
            // Just past PrintSwipe's drag slop (four touch slops) in 48 ms: short of the 18%
            // swipe, so the fling is what counts.
            val travel = viewConfiguration.touchSlop * 4 + 4.dp.toPx()
            check(travel < width * 0.18f && travel > 48.dp.toPx())
            down(at)
            repeat(3) { moveBy(Offset(-travel / 3, 0f)) }
            up()
        }
        compose.waitForIdle()
        assertEquals(listOf("callHermes:"), calls)
    }

    @Test
    fun swipeRightThenFlickBack_withHermes_startsNoCall() {
        show(hermesState)
        val at = printPoint()
        compose.onRoot().performTouchInput {
            // Start a swipe right, just past PrintSwipe's drag slop (four touch slops)...
            val out = viewConfiguration.touchSlop * 4 + 4.dp.toPx()
            down(at)
            repeat(5) { moveBy(Offset(out / 5, 0f)) }
            // ...then think better of it: a quick flick back that still ends right of the start.
            repeat(2) { moveBy(Offset(-16.dp.toPx(), 0f)) }
            check(out - 2 * 16.dp.toPx() > 0f)
            up()
        }
        compose.waitForIdle()
        assertEquals(emptyList<String>(), calls)
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
    fun tripleTapPrint_withoutHermes_opensAppAskOverlay() {
        show(grokState)
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
        compose.onNodeWithText("Ask Grok").assertExists()
        assertEquals(emptyList<String>(), calls)
    }

    @Test
    fun tripleTapPrint_whenTheThirdTouchRests_stillOpensAsk() {
        show(hermesState)
        val at = printPoint()
        compose.onRoot().performTouchInput {
            click(at)
            advanceEventTime(90)
            click(at)
            // The third finger lands late in the window and is still down when it closes.
            advanceEventTime(250)
            down(at)
            advanceEventTime(120)
            up()
        }
        compose.mainClock.advanceTimeBy(1_000)
        compose.waitForIdle()
        compose.onNodeWithText("Ask Hermes").assertExists()
        assertEquals(listOf("hermesVisible:true"), calls)
    }

    @Test
    fun slowDoubleTapPrint_fetchesNextBing() {
        show()
        val at = printPoint()
        // The second touch lands in time but rests: the window runs lift to touch, not lift to lift.
        compose.onRoot().performTouchInput {
            click(at)
            advanceEventTime(250)
            down(at)
            advanceEventTime(120)
            up()
        }
        compose.mainClock.advanceTimeBy(1_000)
        compose.waitForIdle()
        assertEquals(listOf("nextBing"), calls)
    }

    @Test
    fun tapThenLongPressPrint_onlyCyclesRinger() {
        show(HomeUiState(mode = RingerVisual.Sound))
        val at = printPoint()
        compose.onRoot().performTouchInput {
            click(at)
            advanceEventTime(90)
            longClick(at)
        }
        compose.mainClock.advanceTimeBy(1_000)
        compose.waitForIdle()
        assertEquals(listOf("ringer:Vibrate"), calls)
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

    @Test
    fun pullUpOnPrint_landsOnPeek() {
        show(HomeUiState(apps = listOf(app("Maps", "com.google.android.apps.maps"))))
        val at = printPoint()
        compose.onRoot().performTouchInput {
            down(at)
            repeat(3) { moveBy(Offset(0f, -8.dp.toPx())) }
            up()
        }
        compose.mainClock.advanceTimeBy(1_000)
        compose.waitForIdle()
        compose.onNodeWithContentDescription("Maps").assertIsDisplayed()
    }

    @Test
    fun pullUpTakenBySystem_leavesSheetClosed() {
        show(HomeUiState(apps = listOf(app("Maps", "com.google.android.apps.maps"))))
        val at = printPoint()
        // Home or Recents takes the swipe: Android cancels it rather than lifting.
        compose.onRoot().performTouchInput {
            down(at)
            repeat(3) { moveBy(Offset(0f, -8.dp.toPx())) }
            cancel()
        }
        compose.mainClock.advanceTimeBy(1_000)
        compose.waitForIdle()
        compose.onNodeWithContentDescription("Maps").assertIsNotDisplayed()
    }

    @Test
    fun pullUpThatSwervesSideways_thenFlicksUp_landsOnPeek() {
        show(HomeUiState(apps = listOf(app("Maps", "com.google.android.apps.maps"))))
        val at = printPoint()
        compose.onRoot().performTouchInput {
            down(at)
            // Up far enough to grab the sheet, then sideways past four touch slops: PrintSwipe takes
            // the gesture too and consumes the rest of it, the lift included.
            moveBy(Offset(0f, -viewConfiguration.touchSlop))
            moveBy(Offset(viewConfiguration.touchSlop * 4.4f, 0f))
            repeat(3) { moveBy(Offset(0f, -20.dp.toPx())) }
            // A real lift comes a frame after the last move; only the system's cancel copies it.
            advanceEventTime(8)
            up()
        }
        compose.mainClock.advanceTimeBy(1_000)
        compose.waitForIdle()
        compose.onNodeWithContentDescription("Maps").assertIsDisplayed()
        assertEquals(emptyList<String>(), calls)
    }

    @Test
    fun longPressRailIcon_withASmallDrift_opensPinPicker() {
        val maps = app("Maps", "com.google.android.apps.maps")
        show(HomeUiState(rail = listOf(RailSlot(maps)) + List(3) { RailSlot() }))
        compose.onNodeWithContentDescription("Maps").performTouchInput {
            down(center)
            advanceEventTime(viewConfiguration.longPressTimeoutMillis + 100)
            // A finger rolls a little as it lifts.
            moveBy(Offset(6.dp.toPx(), 0f))
            up()
        }
        compose.waitForIdle()
        compose.onNodeWithText("Pin").assertExists()
        assertEquals(emptyList<String>(), calls)
    }

    @Test
    fun dragRailIcon_rightToLeft_followsTheFinger() {
        val moves = mutableListOf<String>()
        // The rail alone, laid out right to left (Arabic, Hebrew): slot 0 is the rightmost.
        compose.setContent {
            CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
                Rail(
                    slots = listOf("A", "B", "C", "D").map { RailSlot(app(it, "com.example.$it")) },
                    iconSaturation = 1f,
                    onLaunch = {},
                    onPinRequest = {},
                    onReorder = { from, to -> moves += "$from>$to" },
                    onHide = {},
                )
            }
        }
        compose.waitForIdle()
        val slot = compose.onRoot().fetchSemanticsNode().size.width / 4f
        // Hold B (slot 1) and drag it left, toward C (slot 2).
        compose.onNodeWithContentDescription("B").performTouchInput {
            down(center)
            advanceEventTime(viewConfiguration.longPressTimeoutMillis + 100)
            moveBy(Offset(-slot * 0.75f, 0f))
            up()
        }
        compose.waitForIdle()
        assertEquals(listOf("1>2"), moves)
    }

    @Test
    fun onboarding_tapOnARailIconLaunchesNothing() {
        val maps = app("Maps", "com.google.android.apps.maps")
        show(HomeUiState(onboarding = OnboardingStep.Access, rail = listOf(RailSlot(maps)) + List(3) { RailSlot() }))
        // The rail shows through the onboarding scrim; a tap there is onboarding's, not the app's.
        compose.onNodeWithContentDescription("Maps").performClick()
        compose.waitForIdle()
        assertEquals(emptyList<String>(), calls.filter { it == "launch" || it == "hide" || it == "pin" })
    }
}
