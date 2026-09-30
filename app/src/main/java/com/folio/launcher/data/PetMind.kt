package com.folio.launcher.data

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

enum class PetMood { Bright, Content, Restless, Mopey, Sleepy }

/** What the dog feels, what it does right now, and whether it has something to say. */
data class PetMoment(val mood: PetMood, val move: PetMove, val speak: Boolean)

/** What the dog "sees", already in words: Jev reads words better than numbers and times. */
data class PetFacts(
    val time: String,
    val scrolling: String,
    val justNow: String,
    val phone: String,
    val weather: String = "",
)

/**
 * Jev decides how the dog feels and what it does; code only gathers the facts and turns
 * numbers into words. DeepSeek writes the rare line it says.
 */
object PetMind {
    internal const val MOOD = "mood"
    internal const val MOVE = "move"
    internal const val SPEAK = "speak"
    const val SURE = 0.5
    const val SPEAK_AT = 0.6

    private val MOODS = linkedMapOf(
        PetMood.Bright to "Delighted: the owner has kept scrolling low, stayed away from an endless app when tempted, or it's a lovely day",
        PetMood.Content to "Calm and settled: an ordinary moment with nothing much to react to",
        PetMood.Restless to "Fidgety and wanting attention: the owner keeps opening endless apps, or has been scrolling indoors on a nice day",
        PetMood.Mopey to "Low and a bit neglected: the owner just came back from a long scroll, or is well over their usual today",
        PetMood.Sleepy to "Drowsy: it's late at night, or the phone is on Silent",
    )

    private val MOVES = linkedMapOf(
        PetMove.Idle to "Sit quietly, breathing and blinking",
        PetMove.Waving to "Look up and greet the owner warmly",
        PetMove.Jumping to "Jump for joy because something good happened",
        PetMove.Failed to "Flop down with a sigh, sulking",
        PetMove.Waiting to "Sit by the door holding its lead, hoping for a walk",
        PetMove.Running to "Dash around in excitement",
        PetMove.Review to "Tilt its head and watch the owner closely, a little worried",
    )

    fun request(facts: PetFacts): Pair<JsonObject, JsonObject> {
        val state = buildJsonObject {
            putJsonObject("dog_sees") {
                put("time", facts.time)
                put("owner_scrolling_today", facts.scrolling)
                put("just_now", facts.justNow)
                put("phone", facts.phone)
                if (facts.weather.isNotEmpty()) put("weather", facts.weather)
            }
        }
        val questions = buildJsonObject {
            putJsonObject(MOOD) {
                put("type", "choice")
                put(
                    "instructions",
                    "A loyal dog lives on its owner's phone home screen and wants the best for them. " +
                        "Given `dog_sees`, how does the dog feel right now?",
                )
                putJsonObject("criteria") { MOODS.forEach { (mood, what) -> put(mood.name.lowercase(), what) } }
            }
            putJsonObject(MOVE) {
                put("type", "choice")
                put("instructions", "Given `dog_sees`, what does the dog do as its owner looks at the home screen?")
                putJsonObject("criteria") { MOVES.forEach { (move, what) -> put(move.name.lowercase(), what) } }
            }
            putJsonObject(SPEAK) {
                put("type", "noul")
                put("instructions", "Given `dog_sees`, is this a moment for the dog to say one short line to its owner?")
                putJsonObject("criteria") {
                    put(
                        "true",
                        "Something notable in `just_now`: back from a long scroll, scrolling late at night, " +
                            "choosing not to open an endless app, or the first look of the morning",
                    )
                    put("false", "An ordinary glance at the home screen")
                }
            }
        }
        return state to questions
    }

    /** Jev's moment; an unsure mood keeps [current], an unsure move just sits. */
    fun read(answers: Map<String, JevAnswer>, current: PetMood): PetMoment? {
        val moodAnswer = answers[MOOD] ?: return null
        val mood = moodAnswer.takeIf { it.confidence >= SURE }
            ?.choice
            ?.let { pick -> PetMood.entries.firstOrNull { it.name.equals(pick, ignoreCase = true) } }
            ?: current
        val move = answers[MOVE]
            ?.takeIf { it.confidence >= SURE }
            ?.choice
            ?.let { pick -> PetMove.entries.firstOrNull { it.name.equals(pick, ignoreCase = true) } }
            ?: PetMove.Idle
        val speak = (answers[SPEAK]?.noul ?: 0.0) >= SPEAK_AT
        return PetMoment(mood, move, speak)
    }

    /** Without a key (or when OpenRouter is down): a quiet dog that still sleeps and sulks. */
    fun fallback(hour: Int, silent: Boolean, day: ScrollDay, usual: Int): PetMoment {
        val back = day.justLeft?.minutes ?: 0
        val mood = when {
            silent || hour >= 23 || hour < 5 -> PetMood.Sleepy
            back >= 30 || (usual > 0 && day.totalMinutes >= usual * 2) -> PetMood.Mopey
            usual > 0 && day.totalMinutes > usual -> PetMood.Restless
            else -> PetMood.Content
        }
        val move = when {
            mood == PetMood.Mopey -> PetMove.Failed
            back in 1..29 -> PetMove.Waving
            else -> PetMove.Idle
        }
        return PetMoment(mood, move, speak = false)
    }

    fun facts(
        hour: Int,
        weekend: Boolean,
        day: ScrollDay,
        usual: Int,
        goal: Int,
        stays: Int,
        label: (String) -> String,
        justNow: String?,
        ringer: RingerVisual,
        weather: String,
    ): PetFacts = PetFacts(
        time = "${partOfDay(hour)} on a ${if (weekend) "weekend" else "weekday"}",
        scrolling = scrolling(day, usual, goal, stays, label),
        justNow = justNow ?: day.justLeft?.let { "just came back after ${minutes(it.minutes)} on ${label(it.pkg)}" }
            ?: day.opensLastHour.takeIf { it >= 3 }?.let { "has opened endless apps $it times in the last hour" }
            ?: "just glanced at the home screen",
        phone = "on ${ringer.name}",
        weather = weather,
    )

    internal fun scrolling(day: ScrollDay, usual: Int, goal: Int, stays: Int, label: (String) -> String): String {
        val total = day.totalMinutes
        val apps = day.minutesByApp.entries
            .sortedByDescending { it.value }
            .joinToString(", ") { "${minutes(it.value)} on ${label(it.key)}" }
        val head = if (total == 0) "no time on endless apps yet" else apps
        val resisted = when (stays) {
            0 -> ""
            1 -> "chose not to open an endless app once today"
            else -> "chose not to open an endless app $stays times today"
        }
        val parts = listOf(head, versusUsual(total, usual), versusGoal(total, goal), resisted).filter { it.isNotEmpty() }
        return parts.joinToString("; ")
    }

    internal fun partOfDay(hour: Int): String = when (hour) {
        in 5..7 -> "early morning"
        in 8..11 -> "morning"
        in 12..16 -> "afternoon"
        in 17..22 -> "evening"
        else -> "late at night"
    }

    internal fun minutes(m: Int): String = when {
        m <= 0 -> "no time"
        m < 3 -> "a couple of minutes"
        m < 60 -> "$m minutes"
        else -> {
            val h = m / 60
            val r = m % 60
            val hours = if (h == 1) "1 hour" else "$h hours"
            if (r == 0) hours else "$hours $r minutes"
        }
    }

    internal fun versusUsual(today: Int, usual: Int): String = when {
        usual <= 0 -> ""
        today >= usual * 2 -> "already more than double their usual ${minutes(usual)} a day"
        today * 5 > usual * 6 -> "over their usual ${minutes(usual)} a day"
        today * 5 >= usual * 4 -> "about their usual ${minutes(usual)} a day"
        else -> "under their usual ${minutes(usual)} a day"
    }

    internal fun versusGoal(today: Int, goal: Int): String = when {
        goal <= 0 -> ""
        today <= goal -> "within their ${goal}-minute daily goal"
        else -> "over their ${goal}-minute daily goal by ${minutes(today - goal)}"
    }

    const val LINE_SYSTEM =
        "You are a small dog who lives on your owner's phone home screen. Say one line to them about " +
            "this moment, at most 10 words. Warm, a little dry and funny; never preachy, never " +
            "guilt-tripping. No emoji, hashtags or quotation marks. Only use numbers that appear in the facts."

    fun linePrompt(facts: PetFacts, mood: PetMood): String = buildJsonObject {
        put("you_feel", mood.name.lowercase())
        put("time", facts.time)
        put("owner_scrolling_today", facts.scrolling)
        put("just_now", facts.justNow)
        if (facts.weather.isNotEmpty()) put("weather", facts.weather)
    }.toString()

    fun cleanLine(raw: String): String? {
        val line = raw.trim().lineSequence().firstOrNull { it.isNotBlank() }?.trim()
            ?.trim('"', '“', '”', '*')
            ?.trim()
            ?: return null
        if (line.isEmpty() || line.length > 90) return null
        return line
    }

    // Sorting apps: endless-scroll or not.

    internal const val ENDLESS_AT = 0.7
    const val ENDLESS_BATCH = 80

    /** One Noul per app; [apps] are "Label (package)" strings. */
    fun endlessRequest(apps: List<String>): Pair<JsonObject, JsonObject> {
        val state = buildJsonObject { putJsonArray("apps") { apps.forEach { add(it) } } }
        val questions = buildJsonObject {
            apps.indices.forEach { i ->
                putJsonObject("e$i") {
                    put("type", "noul")
                    put(
                        "instructions",
                        "Is `apps[$i]` an endless-scroll app: a social media, short-video, or bottomless news or video feed?",
                    )
                    putJsonObject("criteria") {
                        put("true", "Its main use is scrolling a feed that never ends")
                        put("false", "A tool, game, messenger, shop, bank, or anything with an end")
                    }
                }
            }
        }
        return state to questions
    }

    /** Indices of [endlessRequest]'s apps that Jev flagged. */
    fun endlessRead(answers: Map<String, JevAnswer>, count: Int): Set<Int> =
        (0 until count).filterTo(HashSet()) { (answers["e$it"]?.noul ?: 0.0) >= ENDLESS_AT }
}
