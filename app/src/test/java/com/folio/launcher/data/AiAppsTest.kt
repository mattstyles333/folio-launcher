package com.folio.launcher.data

import android.app.Application
import android.content.ComponentName
import android.content.Intent
import android.content.IntentFilter
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
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
    fun open_sharesWithTheAppWhenItDoesNotClaimTheLink_neverTheBrowser() {
        val app: Application = ApplicationProvider.getApplicationContext()
        val pm = shadowOf(app.packageManager)
        val browser = ComponentName("com.android.chrome", "com.android.chrome.Main")
        pm.addActivityIfNotPresent(browser)
        pm.addIntentFilterForActivity(
            browser,
            IntentFilter(Intent.ACTION_VIEW).apply {
                addCategory(Intent.CATEGORY_DEFAULT)
                addCategory(Intent.CATEGORY_BROWSABLE)
                addDataScheme("https")
            },
        )
        // This Claude build doesn't claim claude.ai/new, but takes shared text.
        val claude = ComponentName("com.anthropic.claude", "com.anthropic.claude.Share")
        pm.addActivityIfNotPresent(claude)
        pm.addIntentFilterForActivity(
            claude,
            IntentFilter(Intent.ACTION_SEND).apply {
                addCategory(Intent.CATEGORY_DEFAULT)
                addDataType("text/plain")
            },
        )

        assertTrue(AiApps.open(app, AiKind.Claude, " hi ", "com.anthropic.claude"))

        val started = shadowOf(app).nextStartedActivity
        assertEquals(Intent.ACTION_SEND, started.action)
        assertEquals("com.anthropic.claude", started.`package`)
        assertEquals("hi", started.getStringExtra(Intent.EXTRA_TEXT))
    }

    @Test
    fun suggestions_openFirstAndQuote() {
        val chips = AiApps.suggestions("Grok", "Stillness is the move")
        assertEquals("Open Grok", chips.first().first)
        assertEquals("", chips.first().second)
        assertTrue(chips.any { it.second.contains("Stillness is the move") })
    }
}
