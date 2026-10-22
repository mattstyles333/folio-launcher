package com.folio.launcher.home

import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.unit.Density
import com.folio.launcher.data.RingerVisual
import com.folio.launcher.ui.ChargeGreen
import com.folio.launcher.ui.PrintInk
import com.folio.launcher.ui.printShadow
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
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
    fun amPm_followsThePassedLocale_notTheJvmDefault() {
        val saved = Locale.getDefault()
        try {
            Locale.setDefault(Locale.JAPAN)
            assertEquals("pm", ClockCopy.of(zoned, is24 = false, locale = Locale.US).ampm)
            Locale.setDefault(Locale.US)
            val japanese = zoned.format(DateTimeFormatter.ofPattern("a", Locale.JAPAN)).lowercase(Locale.JAPAN)
            assertEquals(japanese, ClockCopy.of(zoned, is24 = false, locale = Locale.JAPAN).ampm)
        } finally {
            Locale.setDefault(saved)
        }
    }

    @Test
    fun date_isTheHouseOrderInEnglish_andTheLocalOrderElsewhere() {
        assertEquals(ClockCopy.EnglishDate, ClockCopy.datePattern(Locale.UK) { error("English keeps the house order") })
        val german = ClockCopy.datePattern(Locale.GERMANY) { "EEEE, d. MMMM" }
        assertEquals(
            "Donnerstag, 27. August",
            ClockCopy.of(zoned, is24 = true, locale = Locale.GERMANY, datePattern = german).date,
        )
        // A pattern java.time can't read keeps the house order rather than take the clock down.
        assertEquals(ClockCopy.EnglishDate, ClockCopy.datePattern(Locale.GERMANY) { "EEEE {d}" })
    }

    @Test
    fun chargeHairline_whenPluggedOrLow() {
        assertTrue(chargeHairlineVisible(charging = true, fraction = 0.8f))
        assertTrue(chargeHairlineVisible(charging = false, fraction = 0.15f))
        assertTrue(chargeHairlineVisible(charging = false, fraction = 0.04f))
        assertFalse(chargeHairlineVisible(charging = false, fraction = 0.16f))
        assertFalse(chargeHairlineVisible(charging = false, fraction = 1f))
    }

    @Test
    fun chargeOval_isGreenOnlyWhileChargingOnSound() {
        assertEquals(ChargeGreen, chargeOvalColor(charging = true, look = RingerVisual.Sound))
        assertEquals(PrintInk, chargeOvalColor(charging = true, look = RingerVisual.Vibrate))
        assertEquals(PrintInk, chargeOvalColor(charging = true, look = RingerVisual.Silent))
        // Low and unplugged: the same oval, in ink, even on Sound.
        assertEquals(PrintInk, chargeOvalColor(charging = false, look = RingerVisual.Sound))
    }

    @Test
    fun chargeOval_isClosedAtFull() {
        assertEquals(StrokeCap.Butt, chargeCap(sweep = 360f))
        assertEquals(StrokeCap.Butt, chargeCap(sweep = 359.5f))
        assertEquals(StrokeCap.Round, chargeCap(sweep = 0.4f * 360f))
    }

    @Test
    fun grades_areFullColourHalfAndGrey() {
        assertEquals(1f, RingerVisual.Sound.saturation(), 0f)
        assertEquals(0.5f, RingerVisual.Vibrate.saturation(), 0f)
        assertEquals(0f, RingerVisual.Silent.saturation(), 0f)
    }

    @Test
    fun printShadow_isAHardDropInDp_notPixels() {
        val xxhdpi = printShadow(Density(3f))
        assertEquals(3.6f, xxhdpi.offset.y, 0.001f)
        assertEquals(0f, xxhdpi.offset.x, 0f)
        assertEquals(0f, xxhdpi.blurRadius, 0f)
        assertEquals(1.2f, printShadow(Density(1f)).offset.y, 0.001f)
    }
}
