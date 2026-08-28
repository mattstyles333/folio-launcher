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
    fun candidateUrls_includePortraitAndUhd() {
        val image = BingImage(
            url = "/th?id=OHR.LakeMagadi_EN-US1_UHD.jpg&w=1080&h=1920",
            urlbase = "/th?id=OHR.LakeMagadi_EN-US1",
        )
        val urls = image.candidateUrls()
        assertTrue(urls.first().startsWith("https://www.bing.com/th?id=OHR.LakeMagadi"))
        assertTrue(urls.any { it.endsWith("_1080x1920.jpg") })
        assertTrue(urls.any { it.endsWith("_UHD.jpg") })
    }
}
