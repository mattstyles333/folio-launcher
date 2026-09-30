package com.folio.launcher.data

import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PetMindTest {
    private val x = "com.twitter.android"
    private val yt = "com.google.android.youtube"
    private val labels = mapOf(x to "X", yt to "YouTube")
    private val label = { pkg: String -> labels[pkg] ?: pkg }

    private fun choice(pick: String, confidence: Double) =
        JevAnswer(type = "choice", choice = pick, confidence = confidence)

    @Test
    fun words_keepNumbersOutOfJevsWay() {
        assertEquals("no time", PetMind.minutes(0))
        assertEquals("a couple of minutes", PetMind.minutes(2))
        assertEquals("47 minutes", PetMind.minutes(47))
        assertEquals("1 hour", PetMind.minutes(60))
        assertEquals("2 hours 5 minutes", PetMind.minutes(125))
        assertEquals("late at night", PetMind.partOfDay(1))
        assertEquals("early morning", PetMind.partOfDay(6))
        assertEquals("evening", PetMind.partOfDay(22))
        assertEquals("late at night", PetMind.partOfDay(23))
    }

    @Test
    fun comparisons_areDoneInCode() {
        assertEquals("", PetMind.versusUsual(30, 0))
        assertEquals("under their usual 40 minutes a day", PetMind.versusUsual(20, 40))
        assertEquals("about their usual 40 minutes a day", PetMind.versusUsual(40, 40))
        assertEquals("over their usual 40 minutes a day", PetMind.versusUsual(55, 40))
        assertEquals("already more than double their usual 40 minutes a day", PetMind.versusUsual(80, 40))
        assertEquals("within their 60-minute daily goal", PetMind.versusGoal(60, 60))
        assertEquals("over their 60-minute daily goal by 15 minutes", PetMind.versusGoal(75, 60))
        assertEquals("", PetMind.versusGoal(75, 0))
    }

    @Test
    fun facts_sayWhatJustHappened() {
        val back = ScrollDay(mapOf(x to 47), justLeft = AppSession(x, 0, 42 * 60_000L))
        fun facts(day: ScrollDay, justNow: String?) = PetMind.facts(
            hour = 21, weekend = false, day = day, usual = 35, goal = 60, stays = 2,
            label = label, justNow = justNow, ringer = RingerVisual.Sound, weather = "light rain, cool, dark outside",
        )
        val f = facts(back, null)
        assertEquals("evening on a weekday", f.time)
        assertEquals("just came back after 42 minutes on X", f.justNow)
        assertEquals(
            "47 minutes on X; over their usual 35 minutes a day; within their 60-minute daily goal; " +
                "chose not to open an endless app 2 times today",
            f.scrolling,
        )
        assertEquals("just chose not to open X", facts(back, "just chose not to open X").justNow)
        assertEquals("has opened endless apps 4 times in the last hour", facts(ScrollDay(opensLastHour = 4), null).justNow)
        assertEquals("just glanced at the home screen", facts(ScrollDay(), null).justNow)
    }

    @Test
    fun request_asksMoodMoveAndSpeak() {
        val facts = PetFacts("evening on a weekday", "47 minutes on X", "just glanced", "on Sound", "clear sky, mild, daylight")
        val (state, questions) = PetMind.request(facts)
        val seen = state["dog_sees"]!!.jsonObject
        assertEquals("47 minutes on X", seen["owner_scrolling_today"]!!.jsonPrimitive.content)
        assertEquals("clear sky, mild, daylight", seen["weather"]!!.jsonPrimitive.content)
        val moods = questions[PetMind.MOOD]!!.jsonObject["criteria"]!!.jsonObject.keys
        assertEquals(setOf("bright", "content", "restless", "mopey", "sleepy"), moods)
        val moves = questions[PetMind.MOVE]!!.jsonObject["criteria"]!!.jsonObject.keys
        assertTrue("waiting" in moves && "failed" in moves && "idle" in moves)
        assertEquals("noul", questions[PetMind.SPEAK]!!.jsonObject["type"]!!.jsonPrimitive.content)
        assertFalse("weather" in PetMind.request(facts.copy(weather = "")).first["dog_sees"]!!.jsonObject)
    }

    @Test
    fun read_trustsOnlySureAnswers() {
        val sure = PetMind.read(
            mapOf(
                PetMind.MOOD to choice("mopey", 0.8),
                PetMind.MOVE to choice("failed", 0.7),
                PetMind.SPEAK to JevAnswer(type = "noul", noul = 0.9),
            ),
            current = PetMood.Content,
        )!!
        assertEquals(PetMoment(PetMood.Mopey, PetMove.Failed, speak = true), sure)
        val unsure = PetMind.read(
            mapOf(PetMind.MOOD to choice("bright", 0.3), PetMind.MOVE to choice("jumping", 0.2)),
            current = PetMood.Restless,
        )!!
        assertEquals(PetMoment(PetMood.Restless, PetMove.Idle, speak = false), unsure)
        assertNull(PetMind.read(emptyMap(), PetMood.Content))
    }

    @Test
    fun fallback_sleepsAndSulksWithoutAKey() {
        val quiet = ScrollDay()
        assertEquals(PetMood.Sleepy, PetMind.fallback(hour = 23, silent = false, day = quiet, usual = 30).mood)
        assertEquals(PetMood.Sleepy, PetMind.fallback(hour = 14, silent = true, day = quiet, usual = 30).mood)
        val longScroll = ScrollDay(mapOf(x to 50), justLeft = AppSession(x, 0, 45 * 60_000L))
        assertEquals(PetMoment(PetMood.Mopey, PetMove.Failed, false), PetMind.fallback(14, false, longScroll, 30))
        assertEquals(PetMood.Content, PetMind.fallback(14, false, quiet, 30).mood)
    }

    @Test
    fun endless_oneNoulPerApp() {
        val (state, questions) = PetMind.endlessRequest(listOf("Reddit (com.reddit.frontpage)", "Monzo (co.uk.getmondo)"))
        assertEquals(2, state["apps"]!!.jsonArray.size)
        assertEquals(setOf("e0", "e1"), questions.keys)
        assertTrue(questions["e1"]!!.jsonObject["instructions"]!!.jsonPrimitive.content.contains("`apps[1]`"))
        val answers = mapOf("e0" to JevAnswer(type = "noul", noul = 0.93), "e1" to JevAnswer(type = "noul", noul = 0.4))
        assertEquals(setOf(0), PetMind.endlessRead(answers, 2))
    }

    @Test
    fun cleanLine_tidiesOrDrops() {
        assertEquals("Forty minutes. I counted.", PetMind.cleanLine("\"Forty minutes. I counted.\"\n"))
        assertNull(PetMind.cleanLine(" "))
        assertNull(PetMind.cleanLine("x".repeat(120)))
    }
}
