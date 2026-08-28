package com.folio.launcher.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AiAppsTest {
    @Test
    fun installedFrom_onlyKnownPackages() {
        val installed = AiApps.installedFrom(
            setOf("ai.x.grok", "com.spotify.music", "com.anthropic.claude"),
        )
        assertEquals(listOf(AiKind.Grok, AiKind.Claude), installed)
    }

    @Test
    fun resolve_prefersGrokThenSelection() {
        val both = listOf(AiKind.Grok, AiKind.ChatGpt)
        assertEquals(AiKind.Grok, AiApps.resolve("", both))
        assertEquals(AiKind.ChatGpt, AiApps.resolve("com.openai.chatgpt", both))
        assertEquals(AiKind.Grok, AiApps.resolve("com.missing", both))
        assertNull(AiApps.resolve("ai.x.grok", emptyList()))
    }

    @Test
    fun gemini_matchesBardOrGemini() {
        assertEquals(
            listOf(AiKind.Gemini),
            AiApps.installedFrom(setOf("com.google.android.apps.bard")),
        )
        assertEquals(
            "com.google.android.apps.bard",
            AiApps.matchedPackage(AiKind.Gemini, setOf("com.google.android.apps.bard")),
        )
    }

    @Test
    fun cyclePackage_wraps() {
        val installed = listOf(AiKind.Grok, AiKind.ChatGpt, AiKind.Claude)
        assertEquals("com.openai.chatgpt", AiApps.cyclePackage("ai.x.grok", installed))
        assertEquals("com.anthropic.claude", AiApps.cyclePackage("com.openai.chatgpt", installed))
        assertEquals("ai.x.grok", AiApps.cyclePackage("com.anthropic.claude", installed))
        assertEquals("com.openai.chatgpt", AiApps.cyclePackage("", installed))
        assertEquals("", AiApps.cyclePackage("ai.x.grok", emptyList()))
    }

    @Test
    fun viewUris_encodeQuery() {
        val uris = AiApps.viewUris(AiKind.ChatGpt, "hello%20world")
        assertTrue(uris.any { it.contains("chatgpt://") })
        assertTrue(uris.any { it.contains("chatgpt.com/?q=hello%20world") })
    }

    @Test
    fun suggestions_openFirstAndQuote() {
        val chips = AiApps.suggestions("Grok", "Stillness is the move")
        assertEquals("Open Grok", chips.first().first)
        assertEquals("", chips.first().second)
        assertTrue(chips.any { it.second.contains("Stillness is the move") })
    }
}
