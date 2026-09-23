package com.folio.launcher.data

import org.junit.Assert.assertEquals
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
    fun candidateUrls_preferUhdThenExactScreen() {
        val image = BingImage(
            url = "/th?id=OHR.LakeMagadi_EN-US1_UHD.jpg&w=1080&h=1920",
            urlbase = "/th?id=OHR.LakeMagadi_EN-US1",
        )
        val urls = image.candidateUrls(1080, 2340)
        assertEquals("https://www.bing.com/th?id=OHR.LakeMagadi_EN-US1_UHD.jpg", urls.first())
        assertTrue(urls.any { it.contains("w=1080") && it.contains("h=2340") })
        assertTrue(urls.any { it.endsWith("_1080x1920.jpg") })
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
