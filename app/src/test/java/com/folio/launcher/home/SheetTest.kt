package com.folio.launcher.home

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SheetTest {
    @Test
    fun rubberBand_isIdentityInsideLimit() {
        assertEquals(0f, rubberBand(-10f, 1000f))
        assertEquals(0f, rubberBand(0f, 1000f))
        assertEquals(400f, rubberBand(400f, 1000f), 0.01f)
        assertEquals(1000f, rubberBand(1000f, 1000f), 0.01f)
    }

    @Test
    fun rubberBand_softensPastLimit() {
        val over = rubberBand(1400f, 1000f)
        assertTrue(over > 1000f)
        assertTrue(over < 1400f)
    }

    @Test
    fun open_whenPulledPastThreshold() {
        assertTrue(sheetShouldOpen(300f, 1000f, velocityY = 0f))
        assertFalse(sheetShouldOpen(100f, 1000f, velocityY = 0f))
    }

    @Test
    fun open_whenFlingUpEvenIfShort() {
        assertTrue(sheetShouldOpen(40f, 1000f, velocityY = -1200f))
        assertFalse(sheetShouldOpen(40f, 1000f, velocityY = 1200f))
    }

    @Test
    fun close_whenFlingDownEvenIfMostlyOpen() {
        assertFalse(sheetShouldOpen(900f, 1000f, velocityY = 1200f))
    }

    @Test
    fun page_opensOnSwipeRight() {
        assertTrue(pageShouldOpen(40f, 1080f, velocityX = 1200f))
        assertFalse(pageShouldOpen(40f, 1080f, velocityX = -1200f))
        assertTrue(pageShouldOpen(300f, 1080f, velocityX = 0f))
        assertFalse(pageShouldOpen(100f, 1080f, velocityX = 0f))
    }
}
