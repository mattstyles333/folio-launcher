package com.folio.launcher.data

import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class QuoteFitTest {
    private val bank = List(872) { Quote("Line $it", if (it % 2 == 0) "Author $it" else "") }

    @Test
    fun shortlist_headsWithTheDailyPickAndSpreads() {
        val list = QuoteFit.shortlist(bank.size, epochDay = 20_000, salt = 3)
        assertEquals(QuoteFit.SHORTLIST, list.size)
        assertEquals(list.size, list.toSet().size)
        assertEquals(bank.indexOf(QuoteBank.pick(bank, 20_000, 3)), list.first())
        // Spread across the bank, not a run of neighbours.
        assertTrue(list.maxOrNull()!! - list.minOrNull()!! > bank.size / 2)
    }

    @Test
    fun shortlist_smallOrEmptyBanks() {
        assertEquals(emptyList<Int>(), QuoteFit.shortlist(0, 1, 0))
        assertEquals(setOf(0, 1, 2), QuoteFit.shortlist(3, 1, 0).toSet())
    }

    @Test
    fun describe_dropsThePhotographer() {
        assertEquals(
            "Lake Magadi — Lesser flamingos at Lake Magadi, Kenya",
            QuoteFit.describe("Lake Magadi", "Lesser flamingos at Lake Magadi, Kenya (© Martin Harvey/Getty Images)"),
        )
        assertEquals("Info", QuoteFit.describe("Info", "info"))
        assertEquals("Fjord at dawn", QuoteFit.describe("", "Fjord at dawn © Someone"))
    }

    @Test
    fun request_offersTheShortlistByIndex() {
        val (state, questions) = QuoteFit.request("Lake Magadi", bank, listOf(4, 9))
        assertEquals("Lake Magadi", state["photograph"]!!.jsonPrimitive.content)
        val criteria = questions[QuoteFit.QUESTION]!!.jsonObject["criteria"]!!.jsonObject
        assertEquals(setOf("q4", "q9"), criteria.keys)
        assertEquals("“Line 4” — Author 4", criteria["q4"]!!.jsonPrimitive.content)
        assertEquals("“Line 9”", criteria["q9"]!!.jsonPrimitive.content)
    }

    @Test
    fun pick_onlyWhatWasOffered() {
        fun answer(choice: String) = mapOf(QuoteFit.QUESTION to JevAnswer(type = "choice", choice = choice))
        assertEquals(9, QuoteFit.pick(answer("q9"), listOf(4, 9)))
        assertNull(QuoteFit.pick(answer("q5"), listOf(4, 9)))
        assertNull(QuoteFit.pick(answer("nine"), listOf(4, 9)))
        assertNull(QuoteFit.pick(emptyMap(), listOf(4, 9)))
    }
}
