package com.pulse.launcher.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RankingTest {
    private val noon = Ranking.startOfDay(1_700_000_000_000L) + 12 * 3_600_000L

    @Test
    fun score_weightsRecentOpens() {
        val today = noon
        val sixDays = noon - 6 * Ranking.DAY_MS
        val twentyDays = noon - 20 * Ranking.DAY_MS
        val fortyDays = noon - 40 * Ranking.DAY_MS
        val score = Ranking.score(listOf(today, sixDays, twentyDays, fortyDays), noon)
        // today + 6d count as 7d and 30d; 20d is 30d only; 40d ignored
        // opens7=2 *4 =8, opens30=3, usedToday=8 → 19
        assertEquals(19, score)
    }

    @Test
    fun match_prefix_word_acronym_fuzzy() {
        assertEquals(Ranking.MatchKind.Prefix, Ranking.match("gma", "Gmail"))
        assertEquals(Ranking.MatchKind.Prefix, Ranking.match("gm", "Gmail"))
        assertEquals(Ranking.MatchKind.WordPrefix, Ranking.match("map", "Google Maps"))
        assertEquals(Ranking.MatchKind.Acronym, Ranking.match("gm", "Google Maps"))
        assertEquals(Ranking.MatchKind.Fuzzy, Ranking.match("gml", "Gmail"))
        assertNull(Ranking.match("zzz", "Gmail"))
        assertNull(Ranking.match("", "Gmail"))
    }

    @Test
    fun eligible_after_first_launch_or_24h() {
        val now = noon
        val launches = mapOf("com.used" to listOf(now))
        val firstSeen = mapOf(
            "com.new" to now,
            "com.old" to now - Ranking.DAY_MS,
            "com.preexisting" to 0L,
        )
        assertTrue(Ranking.isEligible("com.used", firstSeen, launches, now))
        assertFalse(Ranking.isEligible("com.new", firstSeen, launches, now))
        assertTrue(Ranking.isEligible("com.old", firstSeen, launches, now))
        assertTrue(Ranking.isEligible("com.preexisting", firstSeen, launches, now))
        assertTrue(Ranking.isEligible("com.unknown", firstSeen, launches, now))
    }

    @Test
    fun relativeTime_buckets() {
        val now = noon
        assertEquals("now", Ranking.relativeTime(now - 10_000, now))
        assertEquals("3m", Ranking.relativeTime(now - 3 * 60_000, now))
        assertEquals("2h", Ranking.relativeTime(now - 2 * 3_600_000, now))
        assertEquals("3d", Ranking.relativeTime(now - 3 * Ranking.DAY_MS, now))
        assertEquals("2w", Ranking.relativeTime(now - 14 * Ranking.DAY_MS, now))
    }

    @Test
    fun combinedLaunches_mergesDistinct() {
        val local = mapOf("a" to listOf(1L, 2L))
        val extra = mapOf("a" to listOf(2L, 3L), "b" to listOf(9L))
        val merged = Ranking.combinedLaunches(local, extra)
        assertEquals(listOf(1L, 2L, 3L), merged["a"])
        assertEquals(listOf(9L), merged["b"])
    }
}
