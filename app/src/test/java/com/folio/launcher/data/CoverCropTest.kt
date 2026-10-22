package com.folio.launcher.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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

    @Test
    fun shrinks_neverScalesUp() {
        // Bing's UHD strip on the S23's print (1080×2340 plus parallax bleed) stays as decoded:
        // the GPU stretches it once, a CPU copy at print size would only add pixels.
        val bing = CoverCrop.window(3840, 2160, 1166, 2527)
        assertEquals(1, CoverCrop.sampleSize(bing.width, bing.height, 1166, 2527))
        assertFalse(CoverCrop.shrinks(bing.width, bing.height, 1166, 2527))
        assertFalse(CoverCrop.shrinks(1166, 2527, 1166, 2527))
        // A photo window still larger after sampling is brought down to the print.
        assertTrue(CoverCrop.shrinks(1500, 3250, 1166, 2527))
        assertTrue(CoverCrop.shrinks(1167, 2528, 1166, 2527))
    }

    @Test
    fun bingsPrintSizeCrop_isThePrintAlready() {
        // Asked for at the S23's print (1080×2340 plus parallax bleed), it is neither cut nor scaled on load.
        assertEquals(CoverWindow(0, 0, 1166, 2527), CoverCrop.window(1166, 2527, 1166, 2527))
        assertEquals(1, CoverCrop.sampleSize(1166, 2527, 1166, 2527))
        assertFalse(CoverCrop.shrinks(1166, 2527, 1166, 2527))
    }

    @Test
    fun aPhotoLargerThanThePrint_isKeptAtExactlyThePrintsSize() {
        // Choose photo and Your photos keep only the window load() would cut, so load() cuts nothing more.
        val photos = listOf(4000 to 3000, 3000 to 4000, 8160 to 6120, 4080 to 3060, 1200 to 6000)
        for ((w, h) in photos) {
            val (dw, dh) = CoverCrop.coverSize(w, h, 1166, 2527)
            val kept = CoverCrop.window(dw, dh, 1166, 2527)
            assertEquals("$w×$h", 1166 to 2527, kept.width to kept.height)
            assertEquals("$w×$h", CoverWindow(0, 0, 1166, 2527), CoverCrop.window(kept.width, kept.height, 1166, 2527))
        }
    }

    @Test
    fun coverSize_onlyEverShrinks() {
        // A 50 MP photo is decoded just large enough to cover the print...
        assertEquals(3369 to 2527, CoverCrop.coverSize(8160, 6120, 1166, 2527))
        // ...and one smaller than the print as it is: the GPU stretches it once.
        assertEquals(800 to 600, CoverCrop.coverSize(800, 600, 1166, 2527))
        assertEquals(1166 to 2527, CoverCrop.coverSize(1166, 2527, 1166, 2527))
    }
}
