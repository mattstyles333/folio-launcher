package com.folio.launcher.home

import java.time.LocalDateTime
import java.time.ZoneId
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ClockCopyTest {
    private val zone = ZoneId.of("Europe/London")
    private val zoned = LocalDateTime.of(2026, 8, 27, 17, 45).atZone(zone)

    @Test
    fun twelveHour_hasLowercaseAmPm() {
        val copy = ClockCopy.of(zoned, is24 = false, locale = Locale.US)
        assertEquals("5:45", copy.time)
        assertEquals("pm", copy.ampm)
        assertEquals("Thursday, August 27", copy.date)
    }

    @Test
    fun twentyFourHour_omitsAmPm() {
        val copy = ClockCopy.of(zoned, is24 = true, locale = Locale.UK)
        assertEquals("17:45", copy.time)
        assertNull(copy.ampm)
        assertEquals("Thursday, August 27", copy.date)
    }

    @Test
    fun chargeHairline_whenPluggedOrLow() {
        assertTrue(chargeHairlineVisible(charging = true, fraction = 0.8f))
        assertTrue(chargeHairlineVisible(charging = false, fraction = 0.15f))
        assertTrue(chargeHairlineVisible(charging = false, fraction = 0.04f))
        assertFalse(chargeHairlineVisible(charging = false, fraction = 0.16f))
        assertFalse(chargeHairlineVisible(charging = false, fraction = 1f))
    }
}
