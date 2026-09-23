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

    @Test
    fun sampleSize_neverDropsBelowTarget() {
        // UHD strip is shorter than the panel: decode at full size.
        assertEquals(1, CoverCrop.sampleSize(1000, 2160, 1166, 2527))
        // A 50 MP phone photo halves twice and still covers the panel.
        assertEquals(2, CoverCrop.sampleSize(3000, 6500, 1166, 2527))
        assertEquals(4, CoverCrop.sampleSize(4800, 10400, 1166, 2527))
        assertEquals(1, CoverCrop.sampleSize(0, 0, 1166, 2527))
    }
}
