package com.folio.launcher.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CoverCropTest {
    @Test
    fun landscapeUhd_cropsPortraitStrip() {
        val win = CoverCrop.window(srcW = 3840, srcH = 2160, dstW = 1080, dstH = 2340)
        assertEquals(2160, win.height)
        assertTrue(win.width in 990..1010)
        assertEquals(0, win.y)
        assertEquals((3840 - win.width) / 2, win.x)
    }

    @Test
    fun alreadyMatching_usesFullFrame() {
        val win = CoverCrop.window(1080, 2340, 1080, 2340)
        assertEquals(CoverWindow(0, 0, 1080, 2340), win)
    }
}
