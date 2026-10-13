package com.folio.launcher.data

import com.folio.launcher.hermes.HermesLive

/**
 * Who answers Ask. Hermes is not a launched app: it answers inside Folio's overlay, and a swipe
 * starts a Hermes Live call. The AI apps keep [AiApps]' behaviour exactly.
 */
sealed interface AskProvider {
    /** What Prefs.aiPackage stores for an explicit choice. */
    val key: String
    val label: String

    fun matches(stored: String): Boolean

    data object Hermes : AskProvider {
        override val key: String = HermesLive.PACKAGE
        override val label: String = "Hermes"
        override fun matches(stored: String): Boolean = stored == key
    }

    data class App(val kind: AiKind) : AskProvider {
        override val key: String get() = kind.packages.first()
        override val label: String get() = kind.label
        override fun matches(stored: String): Boolean = stored in kind.packages
    }
}

object AskProviders {
    /** Hermes first when Hermes Live is installed and signed in, then the installed AI apps. */
    fun list(hermesReady: Boolean, installed: List<AiKind>): List<AskProvider> =
        (if (hermesReady) listOf<AskProvider>(AskProvider.Hermes) else emptyList()) + installed.map { AskProvider.App(it) }

    /**
     * The stored choice if it is still available, else the first provider. An empty choice (never
     * picked) therefore means Hermes when it is ready; without Hermes this is [AiApps.resolve].
     */
    fun resolve(stored: String, providers: List<AskProvider>): AskProvider? {
        if (providers.isEmpty()) return null
        return providers.firstOrNull { it.matches(stored) } ?: providers.first()
    }

    /** Settings → Ask: the next provider's key. Without Hermes this is [AiApps.cyclePackage]. */
    fun cycle(stored: String, providers: List<AskProvider>): String {
        if (providers.isEmpty()) return ""
        val idx = providers.indexOfFirst { it.matches(stored) }.let { if (it < 0) 0 else it }
        return providers[(idx + 1).mod(providers.size)].key
    }

    /** Chips under the Hermes field. An empty prompt means "start a voice call". */
    fun hermesSuggestions(quote: String): List<Pair<String, String>> {
        val chips = ArrayList<Pair<String, String>>(4)
        chips += "Talk to Hermes" to ""
        chips += "Catch me up" to "Catch me up: anything I should know today? Keep it short."
        val q = quote.trim()
        if (q.isNotEmpty()) {
            val label = if (q.length <= 36) q else q.take(34).trimEnd() + "…"
            chips += label to "What do you make of this: “$q”"
        }
        chips += "Where were we" to "Remind me where we left off, in two lines."
        return chips
    }
}
