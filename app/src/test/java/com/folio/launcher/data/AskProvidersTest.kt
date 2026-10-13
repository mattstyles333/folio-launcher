package com.folio.launcher.data

import com.folio.launcher.hermes.HermesLive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AskProvidersTest {
    private val all = AiKind.entries.toList()

    /** Every subset of the AI apps, so "unchanged without Hermes" is checked exhaustively. */
    private fun subsets(): List<List<AiKind>> = (0 until (1 shl all.size)).map { mask ->
        all.filterIndexed { i, _ -> mask and (1 shl i) != 0 }
    }

    private val stored = listOf(
        "", "ai.x.grok", "com.openai.chatgpt", "com.google.android.apps.bard",
        "com.google.android.apps.gemini", "com.anthropic.claude", "com.missing", HermesLive.PACKAGE,
    )

    @Test
    fun withoutHermes_resolveAndCycleMatchAiApps() {
        for (installed in subsets()) {
            val providers = AskProviders.list(hermesReady = false, installed = installed)
            assertEquals(installed.map { AskProvider.App(it) }, providers)
            for (pref in stored) {
                val expected = AiApps.resolve(pref, installed)
                assertEquals("resolve($pref, $installed)", expected?.let { AskProvider.App(it) }, AskProviders.resolve(pref, providers))
                assertEquals("cycle($pref, $installed)", AiApps.cyclePackage(pref, installed), AskProviders.cycle(pref, providers))
            }
        }
    }

    @Test
    fun hermesReady_isFirstAndTheDefault() {
        val providers = AskProviders.list(hermesReady = true, installed = listOf(AiKind.Grok, AiKind.Claude))
        assertEquals(AskProvider.Hermes, providers.first())
        assertEquals(AskProvider.Hermes, AskProviders.resolve("", providers))
        assertEquals(AskProvider.Hermes, AskProviders.resolve("com.missing", providers))
        assertEquals("Hermes", AskProvider.Hermes.label)
    }

    @Test
    fun explicitChoice_isRespectedOverHermes() {
        val providers = AskProviders.list(hermesReady = true, installed = listOf(AiKind.Grok, AiKind.ChatGpt))
        assertEquals(AskProvider.App(AiKind.ChatGpt), AskProviders.resolve("com.openai.chatgpt", providers))
        assertEquals(AskProvider.Hermes, AskProviders.resolve(HermesLive.PACKAGE, providers))
    }

    @Test
    fun hermesChosen_butSignedOut_fallsBackToAnApp() {
        val providers = AskProviders.list(hermesReady = false, installed = listOf(AiKind.Claude))
        assertEquals(AskProvider.App(AiKind.Claude), AskProviders.resolve(HermesLive.PACKAGE, providers))
        assertNull(AskProviders.resolve(HermesLive.PACKAGE, AskProviders.list(false, emptyList())))
    }

    @Test
    fun hermesOnly_isAProviderWithoutAnyAiApp() {
        val providers = AskProviders.list(hermesReady = true, installed = emptyList())
        assertEquals(AskProvider.Hermes, AskProviders.resolve("", providers))
        assertEquals(HermesLive.PACKAGE, AskProviders.cycle("", providers))
    }

    @Test
    fun cycle_includesHermesAndWraps() {
        val providers = AskProviders.list(hermesReady = true, installed = listOf(AiKind.Grok, AiKind.Claude))
        assertEquals("ai.x.grok", AskProviders.cycle("", providers))
        assertEquals("ai.x.grok", AskProviders.cycle(HermesLive.PACKAGE, providers))
        assertEquals("com.anthropic.claude", AskProviders.cycle("ai.x.grok", providers))
        assertEquals(HermesLive.PACKAGE, AskProviders.cycle("com.anthropic.claude", providers))
    }

    @Test
    fun hermesSuggestions_startWithTalkAndCarryTheQuote() {
        val chips = AskProviders.hermesSuggestions("Stillness is the move")
        assertEquals("Talk to Hermes" to "", chips.first())
        assertTrue(chips.any { it.second.contains("Stillness is the move") })
        assertTrue(chips.drop(1).all { it.second.isNotEmpty() })
        assertEquals(chips.size - 1, AskProviders.hermesSuggestions("").size)
    }
}
