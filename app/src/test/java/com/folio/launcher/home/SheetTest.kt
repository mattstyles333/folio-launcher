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
    fun peek_isTwoRowsPlusSliver() {
        val peek = sheetPeekPx(rowPx = 80f, maxPx = 1000f)
        assertEquals(80f * SheetPeekRowCount, peek, 0.01f)
        assertTrue(peek > 160f)
        assertTrue(peek < 200f)
    }

    @Test
    fun peek_capsBelowHalfFull() {
        val peek = sheetPeekPx(rowPx = 400f, maxPx = 500f)
        assertEquals(225f, peek, 0.01f)
    }

    @Test
    fun settle_slowRelease_snapsToNearest() {
        assertEquals(0f, sheetSettleTarget(80f, peek = 400f, max = 1000f, velocityY = 0f), 0.01f)
        assertEquals(400f, sheetSettleTarget(300f, peek = 400f, max = 1000f, velocityY = 0f), 0.01f)
        assertEquals(400f, sheetSettleTarget(500f, peek = 400f, max = 1000f, velocityY = 0f), 0.01f)
        assertEquals(1000f, sheetSettleTarget(850f, peek = 400f, max = 1000f, velocityY = 0f), 0.01f)
    }

    @Test
    fun settle_casualFlingUp_fromIdle_goesPeek() {
        assertEquals(400f, sheetSettleTarget(40f, peek = 400f, max = 1000f, velocityY = -1200f), 0.01f)
    }

    @Test
    fun settle_hardFlingUp_fromIdle_stillPeek() {
        assertEquals(400f, sheetSettleTarget(40f, peek = 400f, max = 1000f, velocityY = -2800f), 0.01f)
    }

    @Test
    fun settle_flingDown_fromIdle_staysClosed() {
        assertEquals(0f, sheetSettleTarget(40f, peek = 400f, max = 1000f, velocityY = 1200f), 0.01f)
    }

    @Test
    fun settle_casualFlingDown_fromFull_goesPeek() {
        assertEquals(400f, sheetSettleTarget(900f, peek = 400f, max = 1000f, velocityY = 1200f), 0.01f)
    }

    @Test
    fun settle_hardFlingDown_closes() {
        assertEquals(0f, sheetSettleTarget(900f, peek = 400f, max = 1000f, velocityY = 2800f), 0.01f)
    }

    @Test
    fun settle_flingUp_fromPeek_opensFull() {
        assertEquals(1000f, sheetSettleTarget(400f, peek = 400f, max = 1000f, velocityY = -1200f), 0.01f)
    }

    @Test
    fun settle_flingDown_fromPeek_closes() {
        assertEquals(0f, sheetSettleTarget(400f, peek = 400f, max = 1000f, velocityY = 1200f), 0.01f)
    }

    @Test
    fun full_detectsLandedMax() {
        assertTrue(sheetIsFull(1000f, 1000f))
        assertTrue(sheetIsFull(999.6f, 1000f))
        assertFalse(sheetIsFull(400f, 1000f))
        assertFalse(sheetIsFull(0f, 1000f))
    }
}
