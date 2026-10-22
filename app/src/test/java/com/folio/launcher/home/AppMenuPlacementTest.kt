package com.folio.launcher.home

import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import org.junit.Assert.assertEquals
import org.junit.Test

/** Where a held icon's menu goes: by the icon, on screen, clear of the bars and the keyboard. */
class AppMenuPlacementTest {
    private val card = IntSize(600, 500)

    /** A 1080 px wide screen; [bottom] is where the nav bar (or the keyboard) starts. */
    private fun place(anchor: Rect, bottom: Int = 2200) =
        menuOffset(anchor, card, width = 1080, top = 100, bottom = bottom, side = 48, gap = 16)

    @Test
    fun justAboveTheIcon_centredOnIt_whenThereIsRoom() {
        assertEquals(IntOffset(200, 1800 - 16 - 500), place(Rect(400f, 1800f, 600f, 2000f)))
    }

    @Test
    fun justBelowTheIcon_whenTheTopIsTooNear() {
        assertEquals(IntOffset(200, 460 + 16), place(Rect(400f, 300f, 600f, 460f)))
    }

    @Test
    fun neverPastEitherSide() {
        assertEquals(48, place(Rect(0f, 1800f, 100f, 2000f)).x)
        assertEquals(1080 - 48 - 600, place(Rect(1000f, 1800f, 1080f, 2000f)).x)
    }

    @Test
    fun neverUnderTheKeyboard() {
        // Too near the top to go above, too low to fit below: held up against the keyboard.
        assertEquals(1300 - 500, place(Rect(400f, 560f, 600f, 900f), bottom = 1300).y)
    }
}
