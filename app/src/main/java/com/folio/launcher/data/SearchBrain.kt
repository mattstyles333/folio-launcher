package com.folio.launcher.data

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

/** Where Jev thinks a search should go. [App] carries the option name Jev picked. */
sealed interface SearchRoute {
    data class App(val name: String) : SearchRoute
    data object Web : SearchRoute
    data object Answer : SearchRoute
    data object Ask : SearchRoute
    data class Music(val term: String) : SearchRoute
}

/** What search shows on top of the local matches, for [query] only. */
data class SearchHint(
    val query: String = "",
    val route: SearchRoute? = null,
    val app: LaunchableApp? = null,
    val answer: String = "",
    val answering: Boolean = false,
)

/**
 * Jev reads what was typed and picks one route; code decides whether it's sure enough to show.
 * An app's name isn't asked about unless it has a space: local matching already has it.
 */
object SearchBrain {
    /** Choice confidence below this changes nothing (TypeSafe's "don't act" floor). */
    const val SURE = 0.5
    const val MIN_CHARS = 3

    /**
     * Typing must pause this long before Jev is asked. About a phone typist's gap between keys,
     * and about a Jev call on an open connection: a call the next key moves past has mostly
     * finished before the next one may start, so its connection is free for that one.
     */
    const val PAUSE_MS = 280L

    /** Opening search opens OpenRouter's connection ahead of Jev's first call, at most this often. */
    const val WARM_MS = 60_000L

    /** A Choice takes 255 options; one of them is [NONE]. */
    const val MAX_APPS = 254

    internal const val INTENT = "intent"
    internal const val APP = "app"
    internal const val NONE = "none"
    private const val OPEN = "open_app"
    private const val WEB = "web"
    private const val ANSWER = "answer"
    private const val ASK = "ask"
    private const val MUSIC = "music"

    const val ANSWER_SYSTEM =
        "You write the answer line under a phone's search box. Reply with one plain sentence of " +
            "at most 25 words that is the answer itself: no preamble, no markdown, no follow-up " +
            "question. If answering needs live, local or personal information you don't have, " +
            "reply with exactly SKIP."

    /** Worth a network call: long enough, and has a space or isn't already an app's name or a word of one. */
    fun worthAsking(query: String, labels: List<String>): Boolean = worthAsking(query) { q ->
        labels.any { label ->
            when (Ranking.match(q, label)) {
                Ranking.MatchKind.Prefix, Ranking.MatchKind.WordPrefix -> true
                else -> false
            }
        }
    }

    /** [worthAsking] for [apps]' labels, folded once per app list: search asks it on every keystroke. */
    @JvmName("worthAskingApps")
    fun worthAsking(query: String, apps: List<LaunchableApp>): Boolean =
        worthAsking(query) { q -> Ranking.namesAnApp(q, apps) }

    private inline fun worthAsking(query: String, namesAnApp: (String) -> Boolean): Boolean {
        val q = query.trim()
        if (q.length < MIN_CHARS) return false
        if (q.any { it.isWhitespace() }) return true
        return !namesAnApp(q)
    }

    /** Search just opened: warm OpenRouter's connection unless that was done in the last [WARM_MS] (at [warmedAt]; null: never). */
    fun warmDue(now: Long, warmedAt: Long?): Boolean = warmedAt == null || now - warmedAt >= WARM_MS

    /** Installed apps as Choice options: most-used first, names made unique, capped at [MAX_APPS]. */
    fun candidates(
        apps: List<LaunchableApp>,
        launches: Map<String, List<Long>>,
        now: Long = System.currentTimeMillis(),
    ): Map<String, LaunchableApp> {
        val today = Ranking.startOfDay(now)
        val ordered = apps
            .filter { !it.isHome }
            // Each app's score worked out once, not on every comparison.
            .map { it to Ranking.score(launches[it.packageName].orEmpty(), now, today) }
            .sortedWith(
                compareByDescending<Pair<LaunchableApp, Int>> { it.second }
                    .thenBy(Ranking.labelOrder()) { it.first.label },
            )
            .take(MAX_APPS)
            .map { it.first }
        return optionNames(ordered.map { it.label }).zip(ordered).toMap(LinkedHashMap())
    }

    /** Labels as distinct option names; a repeat gets " (2)", and nothing may be called [NONE]. */
    internal fun optionNames(labels: List<String>): List<String> {
        val used = HashSet<String>()
        return labels.map { raw ->
            val base = raw.trim().ifEmpty { "App" }
            var name = base
            var n = 2
            while (name.equals(NONE, ignoreCase = true) || !used.add(name)) name = "$base (${n++})"
            name
        }
    }

    /** `state` and `questions` for one System One call; [apps] maps option name → package. */
    fun request(query: String, apps: Map<String, String>, music: Boolean): Pair<JsonObject, JsonObject> =
        state(query) to questions(apps, music)

    /** What was typed: the only part of a call that changes with each query. */
    fun state(query: String): JsonObject = buildJsonObject { put("typed", query.trim()) }

    /** The questions for every query typed over one app list ([apps]: option name → package). */
    fun questions(apps: Map<String, String>, music: Boolean): JsonObject =
        buildJsonObject {
            putJsonObject(INTENT) {
                put("type", "choice")
                put("instructions", "What does the person want from `typed`, entered in their phone's search box?")
                putJsonObject("criteria") {
                    put(OPEN, "Open an app on the phone, named or described by what the app does")
                    put(WEB, "Look something up on the web: news, scores, weather, opening times, places, products, people")
                    put(ANSWER, "A quick fact, definition, unit conversion or sum that one short sentence answers")
                    put(ASK, "Get an AI assistant to write, rewrite, plan, explain at length, advise or code")
                    if (music) put(MUSIC, "Play or find music: a song, artist, album, playlist, genre or mood")
                }
            }
            putJsonObject(APP) {
                put("type", "choice")
                put("instructions", "Which installed app is `typed` asking for, by its name or by what it does?")
                putJsonObject("criteria") {
                    apps.forEach { (name, pkg) -> put(name, pkg) }
                    put(NONE, "None of these apps fits")
                }
            }
        }

    /** The route, or null when Jev isn't sure — then search stays as it is. */
    fun route(query: String, answers: Map<String, JevAnswer>): SearchRoute? {
        val intent = answers[INTENT]?.takeIf { it.confidence >= SURE } ?: return null
        return when (intent.choice) {
            OPEN -> answers[APP]
                ?.takeIf { it.confidence >= SURE && it.choice != null && it.choice != NONE }
                ?.let { SearchRoute.App(it.choice!!) }
            WEB -> SearchRoute.Web
            ANSWER -> SearchRoute.Answer
            ASK -> SearchRoute.Ask
            MUSIC -> SearchRoute.Music(musicTerm(query))
            else -> null
        }
    }

    private val PLAY_PREFIX = Regex("^(play|listen to|put on|shuffle)\\s+", RegexOption.IGNORE_CASE)
    private val ON_SPOTIFY = Regex("\\s+on spotify$", RegexOption.IGNORE_CASE)

    /** "play lofi on spotify" → "lofi". */
    internal fun musicTerm(query: String): String {
        val q = query.trim()
        val term = q.replace(PLAY_PREFIX, "").replace(ON_SPOTIFY, "").trim()
        return term.ifEmpty { q }
    }

    /** DeepSeek's line, tidied; null when it declined or said nothing. */
    fun cleanAnswer(raw: String): String? {
        val line = raw.trim()
            .removeSurrounding("\"")
            .removeSurrounding("“", "”")
            .replace("**", "")
            .lineSequence()
            .firstOrNull { it.isNotBlank() }
            ?.trim()
            ?: return null
        if (line.isEmpty() || line.trimEnd('.').equals("SKIP", ignoreCase = true)) return null
        return if (line.length <= 240) line else line.take(239).trimEnd() + "…"
    }
}
