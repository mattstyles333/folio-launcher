package com.pulse.launcher.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class QuoteBankTest {
    private val quotes = (0 until 20).map { Quote("line-$it", "who") }

    @Test
    fun pick_isStableForSameDayAndSalt() {
        val a = QuoteBank.pick(quotes, epochDay = 20000, salt = 3)
        val b = QuoteBank.pick(quotes, epochDay = 20000, salt = 3)
        assertEquals(a, b)
    }

    @Test
    fun pick_changesWithSaltOrDay() {
        val today = QuoteBank.pick(quotes, 20000, 0)
        val nextDay = QuoteBank.pick(quotes, 20001, 0)
        val nextPrint = QuoteBank.pick(quotes, 20000, 1)
        assertNotEquals(today, nextDay)
        assertNotEquals(today, nextPrint)
    }
}
