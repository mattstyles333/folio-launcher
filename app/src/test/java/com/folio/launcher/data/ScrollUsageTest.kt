package com.folio.launcher.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ScrollUsageTest {
    private val min = 60_000L
    private val day = Ranking.DAY_MS
    private val x = "com.twitter.android"
    private val yt = "com.google.android.youtube"
    private val home = "com.folio.launcher"
    private val endless = setOf(x, yt)

    private fun on(pkg: String, at: Long) = UsageEvt(pkg, resumed = true, at = at)
    private fun off(pkg: String, at: Long) = UsageEvt(pkg, resumed = false, at = at)

    @Test
    fun sessions_pairResumeAndPause() {
        val events = listOf(on(x, 0), off(x, 10 * min), on(home, 10 * min), on(yt, 20 * min), off(yt, 25 * min))
        assertEquals(
            listOf(AppSession(x, 0, 10 * min), AppSession(yt, 20 * min, 25 * min)),
            ScrollUsage.sessions(events, endless, now = 30 * min),
        )
    }

    @Test
    fun sessions_joinActivitySwitchesAndRunToNow() {
        val events = listOf(on(x, 0), off(x, 5 * min), on(x, 5 * min + 5_000), on(x, 6 * min))
        assertEquals(listOf(AppSession(x, 0, 12 * min)), ScrollUsage.sessions(events, endless, now = 12 * min))
    }

    @Test
    fun sessions_anotherAppClosesAMissingPause() {
        val events = listOf(on(x, 0), on(home, 7 * min))
        assertEquals(listOf(AppSession(x, 0, 7 * min)), ScrollUsage.sessions(events, endless, now = 30 * min))
    }

    @Test
    fun sessions_ignoreOtherApps() {
        val events = listOf(on(home, 0), off(home, 5 * min), on("com.bank", 6 * min))
        assertEquals(emptyList<AppSession>(), ScrollUsage.sessions(events, endless, now = 10 * min))
    }

    @Test
    fun day_countsTodayAndNoticesAComeback() {
        val todayStart = 10 * day
        val now = todayStart + 120 * min
        val sessions = listOf(
            AppSession(x, todayStart - 30 * min, todayStart + 15 * min), // straddles midnight
            AppSession(yt, todayStart + 60 * min, todayStart + 72 * min),
            AppSession(x, now - 45 * min, now - 1 * min),
        )
        val today = ScrollUsage.day(sessions, todayStart, now)
        assertEquals(mapOf(x to 59, yt to 12), today.minutesByApp)
        assertEquals(71, today.totalMinutes)
        assertEquals(2, today.opensLastHour)
        assertEquals(44, today.justLeft!!.minutes)
    }

    @Test
    fun day_noComebackWhileStillOpenOrLongAgo() {
        val todayStart = 10 * day
        val now = todayStart + 120 * min
        assertNull(ScrollUsage.day(listOf(AppSession(x, now - 5 * min, now)), todayStart, now).justLeft)
        assertNull(ScrollUsage.day(listOf(AppSession(x, now - 30 * min, now - 20 * min)), todayStart, now).justLeft)
    }

    @Test
    fun usualMinutes_averagesKnownDays() {
        val todayStart = 20 * day
        val sessions = listOf(
            AppSession(x, todayStart - day, todayStart - day + 40 * min),
            AppSession(x, todayStart - 2 * day, todayStart - 2 * day + 20 * min),
            // Three days ago: nothing. Earlier: no history at all.
            AppSession(x, todayStart + 5 * min, todayStart + 50 * min), // today doesn't count
        )
        // Yesterday 40, two days ago 20, then nothing: days before any history don't count,
        // so it's (40 + 20) / 2.
        assertEquals(30, ScrollUsage.usualMinutes(sessions, todayStart))
        assertEquals(0, ScrollUsage.usualMinutes(emptyList(), todayStart))
    }

    @Test
    fun tag_biggestFirst() {
        val labels = mapOf(x to "X", yt to "YouTube")
        assertEquals("X 1h12 · YouTube 9m", ScrollUsage.tag(mapOf(yt to 9, x to 72)) { labels.getValue(it) })
        assertEquals("", ScrollUsage.tag(emptyMap()) { it })
    }

    @Test
    fun endless_knownPlusPickedMinusOff() {
        val installed = setOf(x, yt, "com.reddit.frontpage", "com.news.feed", "com.bank")
        assertEquals(
            setOf(x, "com.reddit.frontpage", "com.news.feed"),
            EndlessApps.effective(installed, picked = listOf("com.news.feed", "com.notinstalled"), off = listOf(yt)),
        )
    }
}
