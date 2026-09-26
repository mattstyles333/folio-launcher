package com.folio.launcher.data

import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SearchBrainTest {
    private val labels = listOf("Spotify", "Google Maps", "Trainline", "Weather")

    private fun choice(pick: String, confidence: Double) =
        JevAnswer(type = "choice", choice = pick, probabilities = mapOf(pick to confidence), confidence = confidence)

    @Test
    fun worthAsking_skipsShortQueriesAndAppNames() {
        assertFalse(SearchBrain.worthAsking("tr", labels))
        assertFalse(SearchBrain.worthAsking("spot", labels))
        assertFalse(SearchBrain.worthAsking("maps", labels))
        assertTrue(SearchBrain.worthAsking("trains", labels))
        assertTrue(SearchBrain.worthAsking("weather tomorrow", labels))
    }

    @Test
    fun optionNames_areUniqueAndNeverNone() {
        assertEquals(
            listOf("Maps", "Maps (2)", "none (2)", "App"),
            SearchBrain.optionNames(listOf("Maps", "Maps", "none", " ")),
        )
    }

    @Test
    fun request_asksIntentAndApp() {
        val (state, questions) = SearchBrain.request(
            "  trains ",
            linkedMapOf("Trainline" to "com.thetrainline"),
            music = false,
        )
        assertEquals("trains", state["typed"]!!.jsonPrimitive.content)
        val intent = questions[SearchBrain.INTENT]!!.jsonObject
        assertEquals("choice", intent["type"]!!.jsonPrimitive.content)
        assertFalse("music" in intent["criteria"]!!.jsonObject)
        val app = questions[SearchBrain.APP]!!.jsonObject["criteria"]!!.jsonObject
        assertEquals("com.thetrainline", app["Trainline"]!!.jsonPrimitive.content)
        assertTrue(SearchBrain.NONE in app)
    }

    @Test
    fun request_offersMusicOnlyWithSpotify() {
        val (_, questions) = SearchBrain.request("lofi", emptyMap(), music = true)
        assertTrue("music" in questions[SearchBrain.INTENT]!!.jsonObject["criteria"]!!.jsonObject)
    }

    @Test
    fun route_followsSureIntents() {
        assertEquals(SearchRoute.Web, SearchBrain.route("q", mapOf(SearchBrain.INTENT to choice("web", 0.9))))
        assertEquals(SearchRoute.Answer, SearchBrain.route("q", mapOf(SearchBrain.INTENT to choice("answer", 0.8))))
        assertEquals(SearchRoute.Ask, SearchBrain.route("q", mapOf(SearchBrain.INTENT to choice("ask", 0.7))))
        assertEquals(
            SearchRoute.Music("lofi beats"),
            SearchBrain.route("play lofi beats", mapOf(SearchBrain.INTENT to choice("music", 0.9))),
        )
    }

    @Test
    fun route_unsureChangesNothing() {
        assertNull(SearchBrain.route("q", mapOf(SearchBrain.INTENT to choice("web", 0.4))))
        assertNull(SearchBrain.route("q", emptyMap()))
    }

    @Test
    fun route_openAppNeedsASureApp() {
        val open = SearchBrain.INTENT to choice("open_app", 0.9)
        assertEquals(
            SearchRoute.App("Trainline"),
            SearchBrain.route("trains", mapOf(open, SearchBrain.APP to choice("Trainline", 0.8))),
        )
        assertNull(SearchBrain.route("trains", mapOf(open, SearchBrain.APP to choice("Trainline", 0.3))))
        assertNull(SearchBrain.route("trains", mapOf(open, SearchBrain.APP to choice(SearchBrain.NONE, 0.9))))
        assertNull(SearchBrain.route("trains", mapOf(open)))
    }

    @Test
    fun musicTerm_dropsPlayAndSpotify() {
        assertEquals("lofi beats", SearchBrain.musicTerm("Play lofi beats on Spotify"))
        assertEquals("Radiohead", SearchBrain.musicTerm("listen to Radiohead"))
        assertEquals("play", SearchBrain.musicTerm("play"))
    }

    @Test
    fun cleanAnswer_tidiesOrDeclines() {
        assertNull(SearchBrain.cleanAnswer("SKIP"))
        assertNull(SearchBrain.cleanAnswer("Skip."))
        assertNull(SearchBrain.cleanAnswer("  "))
        assertEquals("Paris.", SearchBrain.cleanAnswer("\"Paris.\""))
        assertEquals("Paris is the capital.", SearchBrain.cleanAnswer("**Paris** is the capital."))
        assertEquals("First line.", SearchBrain.cleanAnswer("First line.\nSecond line."))
        val long = SearchBrain.cleanAnswer("word ".repeat(80))!!
        assertTrue(long.length <= 240)
        assertTrue(long.endsWith("…"))
    }
}
