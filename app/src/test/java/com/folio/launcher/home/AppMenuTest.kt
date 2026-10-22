package com.folio.launcher.home

import android.graphics.Bitmap
import android.os.Process
import androidx.activity.OnBackPressedDispatcher
import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotDisplayed
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipe
import androidx.compose.ui.test.swipeRight
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.folio.launcher.data.AppShortcut
import com.folio.launcher.data.HomeUiState
import com.folio.launcher.data.LaunchableApp
import com.folio.launcher.ui.FolioTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** Long-press an app in the sheet or in search: its shortcuts, then App info. Driven against the real HomeScreen. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-xxhdpi")
class AppMenuTest {
    @get:Rule
    val compose = createComposeRule()

    private val calls = mutableListOf<String>()

    /** Bumped by MainActivity when Home is pressed (or the screen comes back on). */
    private var idleEpoch by mutableIntStateOf(0)
    private var back: OnBackPressedDispatcher? = null

    private fun app(label: String) = LaunchableApp(
        packageName = "com.example.${label.lowercase()}",
        activityName = "com.example.${label.lowercase()}.Main",
        user = Process.myUserHandle(),
        label = label,
        icon = Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888).asImageBitmap(),
    )

    private val maps = app("Maps")
    private val notes = app("Notes")

    /** Side by side in the sheet's first row (A–Z, neither used yet). */
    private val apps = HomeUiState(apps = listOf(maps, notes))

    private fun shortcut(of: LaunchableApp, id: String, label: String) = AppShortcut(
        packageName = of.packageName,
        id = id,
        label = label,
        user = of.user,
        icon = Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888).asImageBitmap(),
    )

    /** What Folio reads while it's Home: Maps has two shortcuts, Notes none. */
    private val published = mapOf(
        maps.packageName to listOf(
            shortcut(maps, "home", "Directions home"),
            shortcut(maps, "work", "Directions to work"),
        ),
    )

    private fun show(state: HomeUiState = apps) {
        compose.setContent {
            back = LocalOnBackPressedDispatcherOwner.current?.onBackPressedDispatcher
            FolioTheme {
                HomeScreen(
                    state = state,
                    idleEpoch = idleEpoch,
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
                    shortcuts = { app ->
                        calls += "shortcuts:${app.label}"
                        published[app.packageName].orEmpty()
                    },
                    onShortcut = { calls += "shortcut:${it.packageName}/${it.id}" },
                )
            }
        }
        compose.waitForIdle()
    }

    /** A quick pull up from the print: the sheet's peek, its first row on screen. */
    private fun peek() {
        compose.onRoot().performTouchInput {
            val at = Offset(width / 2f, height * 0.45f)
            swipe(start = at, end = at - Offset(0f, 100.dp.toPx()), durationMillis = 200)
        }
        compose.mainClock.advanceTimeBy(1_000)
        compose.waitForIdle()
    }

    /** Hold a drawer icon past the long-press timeout. */
    private fun hold(label: String) {
        compose.onNodeWithContentDescription(label).performTouchInput { longClick() }
        compose.waitForIdle()
    }

    /** The system Back, as the activity hands it to HomeScreen's handlers. */
    private fun pressBack() {
        compose.runOnIdle { checkNotNull(back).onBackPressed() }
        compose.waitForIdle()
    }

    /**
     * A Back swipe from the screen's left edge under gesture navigation, as Folio sees it: its first
     * touch and a little travel, then a cancel once the system takes the swipe. Back follows ([pressBack]).
     */
    private fun edgeSwipeTakenBySystem() {
        compose.onRoot().performTouchInput {
            down(Offset(2f, centerY))
            moveBy(Offset(40f, 0f))
            cancel()
        }
        compose.waitForIdle()
    }

    /** A press on [node]'s centre that slides 30 dp left over 600 ms (past the hold's timeout), then lifts. */
    private fun driftLeft(node: SemanticsNodeInteraction) {
        node.performTouchInput {
            down(center)
            repeat(10) {
                advanceEventTime(60)
                moveBy(Offset(-3.dp.toPx(), 0f))
            }
            up()
        }
        compose.mainClock.advanceTimeBy(1_000)
        compose.waitForIdle()
    }

    private fun top(text: String) = compose.onNodeWithText(text).assertIsDisplayed().fetchSemanticsNode().boundsInRoot.top

    @Test
    fun holdADrawerIcon_showsItsShortcuts_thenAppInfoLast_justAboveIt() {
        show()
        peek()
        hold("Maps")
        val rows = listOf("Directions home", "Directions to work", "App info").map(::top)
        assertEquals(rows.sorted(), rows)
        // Above the held icon, where the finger isn't.
        val icon = compose.onNodeWithContentDescription("Maps").fetchSemanticsNode().boundsInRoot
        assertTrue(compose.onNodeWithText("App info").fetchSemanticsNode().boundsInRoot.bottom <= icon.top)
        assertEquals(listOf("shortcuts:Maps"), calls)
    }

    @Test
    fun eachRow_isAButtonNamedByItsLabel() {
        show()
        peek()
        hold("Maps")
        listOf("Directions home", "Directions to work", "App info").forEach {
            compose.onNodeWithText(it).assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Button))
        }
    }

    @Test
    fun tapAShortcut_startsIt_andClosesTheMenuAndTheSheet() {
        show()
        peek()
        hold("Maps")
        compose.onNodeWithText("Directions to work").performClick()
        compose.mainClock.advanceTimeBy(1_000)
        compose.waitForIdle()
        assertEquals(listOf("shortcuts:Maps", "shortcut:com.example.maps/work"), calls)
        compose.onNodeWithText("App info").assertDoesNotExist()
        compose.onNodeWithContentDescription("Maps").assertIsNotDisplayed()
    }

    @Test
    fun noShortcuts_appInfoAlone_whichOpensAppInfo() {
        show()
        peek()
        hold("Notes")
        compose.onNodeWithText("Directions home").assertDoesNotExist()
        compose.onNodeWithText("App info").assertIsDisplayed().performClick()
        compose.waitForIdle()
        assertEquals(listOf("shortcuts:Notes", "appInfo:Notes"), calls)
        compose.onNodeWithText("App info").assertDoesNotExist()
        // The sheet stays as it was, for when App info comes back.
        compose.onNodeWithContentDescription("Notes").assertIsDisplayed()
    }

    @Test
    fun back_closesTheMenuFirst_thenTheSheet() {
        show()
        peek()
        hold("Maps")
        pressBack()
        compose.onNodeWithText("App info").assertDoesNotExist()
        compose.onNodeWithContentDescription("Maps").assertIsDisplayed()
        pressBack()
        compose.onNodeWithContentDescription("Maps").assertIsNotDisplayed()
        assertEquals(listOf("shortcuts:Maps"), calls)
    }

    @Test
    fun anEdgeBackSwipe_closesOnlyTheMenu() {
        show()
        peek()
        hold("Maps")
        // Its first touch lands on the scrim, but the system takes the swipe: the menu is still open for its Back.
        edgeSwipeTakenBySystem()
        compose.onNodeWithText("App info").assertIsDisplayed()
        pressBack()
        compose.onNodeWithText("App info").assertDoesNotExist()
        // The sheet is still at its peek.
        compose.onNodeWithContentDescription("Maps").assertIsDisplayed()
        assertEquals(listOf("shortcuts:Maps"), calls)
    }

    @Test
    fun aTapOffTheMenu_closesIt_andReachesNothingUnderneath() {
        show()
        peek()
        hold("Maps")
        // Notes sits beside Maps, under the menu's scrim: the tap closes the menu and opens nothing.
        compose.onNodeWithContentDescription("Notes").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("App info").assertDoesNotExist()
        assertEquals(listOf("shortcuts:Maps"), calls)
    }

    @Test
    fun aSlowPullOnADrawerIcon_neverOpensTheMenu() {
        show()
        peek()
        // Longer than a hold, but moving: the sheet takes the finger, so the hold never fires.
        compose.onNodeWithContentDescription("Maps").performTouchInput {
            down(center)
            repeat(10) {
                advanceEventTime(60)
                moveBy(Offset(0f, -8.dp.toPx()))
            }
            up()
        }
        compose.mainClock.advanceTimeBy(1_000)
        compose.waitForIdle()
        compose.onNodeWithText("App info").assertDoesNotExist()
        assertEquals(emptyList<String>(), calls)
    }

    @Test
    fun aPressThatSlidesSideways_neverOpensTheMenu() {
        show()
        peek()
        // Left, inside the cell: swipe-to-hide only takes a move right, the sheet and the grid only up
        // and down, so nothing else takes it. Held past the timeout, it's still a drag, not a hold.
        driftLeft(compose.onNodeWithContentDescription("Maps"))
        compose.onNodeWithText("App info").assertDoesNotExist()
        assertEquals(emptyList<String>(), calls)
    }

    @Test
    fun aSlowPullThatLeansSidewaysFirst_neverOpensTheMenu() {
        show()
        peek()
        // Its first few dp lean left, past the sheet's slop, so the sheet lets it go (and swipe-to-hide only
        // takes a move right); then up, slowly, still inside the cell.
        compose.onNodeWithContentDescription("Maps").performTouchInput {
            down(center)
            moveBy(Offset(-10.dp.toPx(), 0f))
            repeat(8) {
                advanceEventTime(60)
                moveBy(Offset(0f, -3.5f.dp.toPx()))
            }
            up()
        }
        compose.mainClock.advanceTimeBy(1_000)
        compose.waitForIdle()
        compose.onNodeWithText("App info").assertDoesNotExist()
        assertEquals(emptyList<String>(), calls)
    }

    @Test
    fun aTapStillOpens_andASwipeRightStillHides() {
        show()
        peek()
        compose.onNodeWithContentDescription("Notes").performTouchInput {
            swipeRight(startX = width * 0.1f, endX = width * 0.9f)
        }
        compose.mainClock.advanceTimeBy(1_000)
        compose.waitForIdle()
        compose.onNodeWithContentDescription("Maps").performClick()
        compose.waitForIdle()
        assertEquals(listOf("hide:Notes", "launch:Maps"), calls)
    }

    @Test
    fun holdASearchResult_showsItsMenu_andAShortcutLeavesSearch() {
        show()
        compose.onNodeWithContentDescription("Search").performClick()
        compose.waitForIdle()
        compose.onNode(hasSetTextAction()).performTextInput("map")
        compose.waitForIdle()
        // The result row; Maps' drawer icon under search has no actions.
        val row = compose.onNode(hasText("Maps") and hasClickAction())
        assertEquals("Shortcuts", row.fetchSemanticsNode().config[SemanticsActions.OnLongClick].label)
        row.performTouchInput { longClick() }
        compose.waitForIdle()
        compose.onNodeWithText("App info").assertIsDisplayed()
        compose.onNodeWithText("Directions home").performClick()
        compose.waitForIdle()
        assertEquals(listOf("shortcuts:Maps", "shortcut:com.example.maps/home"), calls)
        compose.onNode(hasSetTextAction()).assertDoesNotExist()
    }

    @Test
    fun back_inSearch_closesTheMenu_andKeepsWhatWasTyped() {
        show()
        compose.onNodeWithContentDescription("Search").performClick()
        compose.waitForIdle()
        compose.onNode(hasSetTextAction()).performTextInput("map")
        compose.waitForIdle()
        compose.onNode(hasText("Maps") and hasClickAction()).performSemanticsAction(SemanticsActions.OnLongClick)
        compose.waitForIdle()
        compose.onNodeWithText("App info").assertIsDisplayed()
        pressBack()
        compose.onNodeWithText("App info").assertDoesNotExist()
        compose.onNode(hasSetTextAction() and hasText("map")).assertExists()
    }

    @Test
    fun anEdgeBackSwipe_inSearch_closesOnlyTheMenu_andKeepsWhatWasTyped() {
        show()
        compose.onNodeWithContentDescription("Search").performClick()
        compose.waitForIdle()
        compose.onNode(hasSetTextAction()).performTextInput("map")
        compose.waitForIdle()
        compose.onNode(hasText("Maps") and hasClickAction()).performSemanticsAction(SemanticsActions.OnLongClick)
        compose.waitForIdle()
        edgeSwipeTakenBySystem()
        compose.onNodeWithText("App info").assertIsDisplayed()
        pressBack()
        compose.onNodeWithText("App info").assertDoesNotExist()
        compose.onNode(hasSetTextAction() and hasText("map")).assertExists()
    }

    @Test
    fun aSearchResultPressThatSlidesSideways_neverOpensItsMenu() {
        show()
        compose.onNodeWithContentDescription("Search").performClick()
        compose.waitForIdle()
        compose.onNode(hasSetTextAction()).performTextInput("map")
        compose.waitForIdle()
        // Along the row: the list only scrolls up and down, so nothing else takes it.
        driftLeft(compose.onNode(hasText("Maps") and hasClickAction()))
        compose.onNodeWithText("App info").assertDoesNotExist()
        assertEquals(emptyList<String>(), calls)
        compose.onNode(hasSetTextAction() and hasText("map")).assertExists()
    }

    @Test
    fun typingUnderTheMenu_closesIt() {
        show()
        compose.onNodeWithContentDescription("Search").performClick()
        compose.waitForIdle()
        compose.onNode(hasSetTextAction()).performTextInput("map")
        compose.waitForIdle()
        compose.onNode(hasText("Maps") and hasClickAction()).performSemanticsAction(SemanticsActions.OnLongClick)
        compose.waitForIdle()
        compose.onNodeWithText("App info").assertIsDisplayed()
        // The keyboard stays up under the menu: a new search is no longer this result's.
        compose.onNode(hasSetTextAction()).performTextInput("s")
        compose.waitForIdle()
        compose.onNodeWithText("App info").assertDoesNotExist()
    }

    @Test
    fun home_closesTheMenu() {
        show()
        peek()
        hold("Maps")
        // Home pressed: MainActivity bumps idleEpoch.
        idleEpoch++
        compose.waitForIdle()
        compose.onNodeWithText("App info").assertDoesNotExist()
        compose.onNodeWithText("Directions home").assertDoesNotExist()
    }
}
