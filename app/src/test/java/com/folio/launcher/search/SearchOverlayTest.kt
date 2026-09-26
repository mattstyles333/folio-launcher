package com.folio.launcher.search

import android.graphics.Bitmap
import android.os.Process
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performImeAction
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.folio.launcher.data.LaunchableApp
import com.folio.launcher.data.SearchHint
import com.folio.launcher.data.SearchRoute
import com.folio.launcher.ui.FolioTheme
import com.folio.launcher.ui.PrintInk
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** Search with Jev's hint on top: what shows, and what Enter does. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-xxhdpi")
class SearchOverlayTest {
    @get:Rule
    val compose = createComposeRule()

    private val calls = mutableListOf<String>()

    private fun app(label: String, pkg: String) = LaunchableApp(
        packageName = pkg,
        activityName = "$pkg.Main",
        user = Process.myUserHandle(),
        label = label,
        icon = Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888).asImageBitmap(),
    )

    private fun show(query: String, results: List<LaunchableApp>, hint: SearchHint) {
        compose.setContent {
            FolioTheme {
                SearchOverlay(
                    visible = true,
                    query = query,
                    onQueryChange = {},
                    results = results,
                    accent = PrintInk,
                    onLaunch = { calls += "launch:${it.label}" },
                    onDismiss = { calls += "dismiss" },
                    hint = hint,
                    onRoute = { calls += "route:$it" },
                )
            }
        }
        compose.waitForIdle()
    }

    @Test
    fun webRoute_showsRowAndEnterFollowsIt() {
        val maps = app("Maps", "com.google.android.apps.maps")
        show("weather tomorrow", listOf(maps), SearchHint("weather tomorrow", SearchRoute.Web))
        compose.onNodeWithText("Search the web").assertIsDisplayed()
        compose.onNode(hasSetTextAction()).performImeAction()
        assertEquals(listOf("route:Web"), calls)
    }

    @Test
    fun appPick_winsEnterOverLocalFirst() {
        val trainline = app("Trainline", "com.thetrainline")
        val tram = app("Tram Times", "com.example.tram")
        show(
            "trains",
            listOf(tram, trainline),
            SearchHint("trains", SearchRoute.App("Trainline"), app = trainline),
        )
        compose.onNode(hasSetTextAction()).performImeAction()
        assertEquals(listOf("launch:Trainline"), calls)
    }

    @Test
    fun answer_showsLineAndTapContinues() {
        show("capital of france", emptyList(), SearchHint("capital of france", SearchRoute.Answer, answer = "Paris."))
        compose.onNodeWithText("Paris.").assertIsDisplayed().performClick()
        assertEquals(listOf("route:Answer"), calls)
    }

    @Test
    fun staleHint_isIgnored_nothingLocalFallsBackToWeb() {
        show("capital of spain", emptyList(), SearchHint("capital of france", SearchRoute.Answer, answer = "Paris."))
        compose.onNodeWithText("Paris.").assertDoesNotExist()
        compose.onNodeWithText("Search the web").assertIsDisplayed()
        compose.onNode(hasSetTextAction()).performImeAction()
        assertEquals(listOf("route:Web"), calls)
    }

    @Test
    fun noHint_enterLaunchesFirstLocal() {
        val spotify = app("Spotify", "com.spotify.music")
        show("spot", listOf(spotify), SearchHint())
        compose.onNodeWithText("Search the web").assertDoesNotExist()
        compose.onNode(hasSetTextAction()).performImeAction()
        assertEquals(listOf("launch:Spotify"), calls)
    }
}
