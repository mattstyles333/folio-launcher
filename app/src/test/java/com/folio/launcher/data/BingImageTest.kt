package com.folio.launcher.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BingImageTest {
    @Test
    fun caption_prefersTitle() {
        val image = BingImage(
            title = "Water, wildlife, and wonder",
            copyright = "Lesser flamingo flock at sunrise, Lake Magadi, Kenya (© Denis-Huot)",
        )
        assertEquals("Water, wildlife, and wonder", image.caption())
    }

    @Test
    fun caption_fallsBackToCopyrightWithoutCredit() {
        val image = BingImage(
            title = "  ",
            copyright = "Auroras over Kirkjufell, Iceland (© Cavan Images/Alamy)",
        )
        assertEquals("Auroras over Kirkjufell, Iceland", image.caption())
    }

    @Test
    fun candidateUrls_uhdOriginalFirst_bingsPortraitFileOnlyAFallback() {
        val image = BingImage(
            url = "/th?id=OHR.LakeMagadi_EN-US1_UHD.jpg&w=1080&h=1920",
            urlbase = "/th?id=OHR.LakeMagadi_EN-US1",
        )
        val urls = image.candidateUrls(1080, 2340)
        // AGENTS: the UHD original, cropped and scaled here; never Bing's own 1080×1920 file first.
        val uhd = urls.indexOf("https://www.bing.com/th?id=OHR.LakeMagadi_EN-US1_UHD.jpg")
        val portrait = urls.indexOfFirst { it.endsWith("_1080x1920.jpg") }
        assertEquals(0, uhd)
        assertTrue("1080×1920 at $portrait, before the UHD original", portrait > uhd)
        // Fallbacks when there's no UHD file: the screen's own size is still asked for.
        assertTrue(urls.any { it.contains("w=1080") && it.contains("h=2340") })
    }

    @Test
    fun identity_usesOhrNameAcrossMarkets() {
        val us = BingImage(urlbase = "/th?id=OHR.LakeMagadi_EN-US1")
        val gb = BingImage(urlbase = "/th?id=OHR.LakeMagadi_EN-GB2")
        assertEquals("LakeMagadi", us.identity())
        assertEquals(us.identity(), gb.identity())
    }

    private fun image(name: String, market: String = "EN-US") =
        BingImage(urlbase = "/th?id=OHR.${name}_${market}1")

    @Test
    fun pickNext_avoidsCurrentAndPrevious() {
        val pool = listOf("A", "B", "C", "D").map { image(it) }
        val random = kotlin.random.Random(7)
        repeat(40) {
            val next = BingClient.pickNext(pool, avoid = setOf("A", "C"), random = random)!!
            assertTrue(next.identity() in setOf("B", "D"))
        }
    }

    @Test
    fun pickNext_fallsBackWhenEverythingIsAvoided() {
        val pool = listOf(image("A"))
        assertEquals("A", BingClient.pickNext(pool, avoid = setOf("A"))?.identity())
        assertEquals(null, BingClient.pickNext(emptyList(), avoid = emptySet()))
    }

    @Test
    fun pickNext_staysAwayFromTheLastTenWhileUnseenOnesRemain() {
        // About a fortnight of archive: one new print a day, the oldest dropping off.
        val random = kotlin.random.Random(3)
        var recent = listOf<String>()
        for (day in 0 until 60) {
            val pool = (day until day + 15).map { image("P$it") }
            val next = BingClient.pickNext(pool, avoid = recent, random = random)!!.identity()
            assertFalse("$next again on day $day", next in recent)
            recent = (listOf(next) + recent).take(BingClient.RECENT_PRINTS)
        }
    }

    @Test
    fun pickNext_withOnlyRecentOnesLeft_stillNeverTheCurrentOrPrevious() {
        // A is current, B previous, C the one before: everything here is recent.
        val pool = listOf("A", "B", "C").map { image(it) }
        val random = kotlin.random.Random(5)
        repeat(20) {
            assertEquals("C", BingClient.pickNext(pool, avoid = listOf("A", "B", "C"), random = random)?.identity())
        }
    }

    @Test
    fun pickNext_firstPrintIsTodays() {
        // The archive lists the local market's newest first; double-tap and the daily print stay random.
        val pool = listOf("Today", "Yesterday", "LastWeek").map { image(it) }
        repeat(20) { seed ->
            val first = BingClient.pickNext(pool, avoid = emptySet(), random = kotlin.random.Random(seed))
            assertEquals("Today", first?.identity())
        }
    }

    @Test
    fun dedupe_dropsSameImageFromAnotherMarketAndBlanks() {
        val list = listOf(image("A", "EN-GB"), image("B"), image("A", "EN-US"), BingImage())
        assertEquals(listOf("A", "B"), BingClient.dedupe(list).map { it.identity() })
    }

    @Test
    fun archive_onlyAsksForOffsetsBingServes() {
        assertTrue(BingClient.OFFSETS.all { it in 0..7 })
        assertEquals(BingClient.OFFSETS.size, BingClient.OFFSETS.toSet().size)
        val url = BingClient.archiveUrl("en-GB", 7)
        assertTrue(url.contains("idx=7") && url.contains("mkt=en-GB") && url.contains("uhd=1"))
    }

    @Test
    fun cacheKey_changesByDayAndMarketNotScreenSize() {
        val day = java.time.LocalDate.of(2026, 9, 23)
        assertEquals(BingClient.cacheKey(day, "en-GB"), BingClient.cacheKey(day, "en-GB"))
        assertTrue(BingClient.cacheKey(day, "en-GB") != BingClient.cacheKey(day.plusDays(1), "en-GB"))
        assertTrue(BingClient.cacheKey(day, "en-GB") != BingClient.cacheKey(day, "en-US"))
    }
}
