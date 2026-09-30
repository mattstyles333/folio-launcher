package com.folio.launcher.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PetSheetTest {
    @Test
    fun rowsAndFrameCounts_matchPetdex() {
        assertEquals(0, PetMove.Idle.row)
        assertEquals(6, PetMove.Idle.holdsMs.size)
        assertEquals(1100, PetMove.Idle.loopMs)
        assertEquals(3, PetMove.Waving.row)
        assertEquals(listOf(140, 140, 140, 280), PetMove.Waving.holdsMs.toList())
        assertEquals(5, PetMove.Failed.row)
        assertEquals(7, PetMove.Failed.lastColumn)
        assertEquals(8, PetMove.Review.row)
    }

    @Test
    fun column_followsHoldTimesAndLoops() {
        // Idle holds: 280, 110, 110, 140, 140, 320
        assertEquals(0, PetFrames.column(PetMove.Idle, 0))
        assertEquals(0, PetFrames.column(PetMove.Idle, 279))
        assertEquals(1, PetFrames.column(PetMove.Idle, 280))
        assertEquals(5, PetFrames.column(PetMove.Idle, 1099))
        assertEquals(0, PetFrames.column(PetMove.Idle, 1100))
        assertEquals(3, PetFrames.column(PetMove.Waving, 420 + 700))
    }

    @Test
    fun geometry_acceptsV1AndV2AtAnyScale() {
        assertEquals(PetGeometry(192, 208, 9), PetFrames.geometry(1536, 1872))
        assertEquals(PetGeometry(192, 208, 11), PetFrames.geometry(1536, 2288))
        assertEquals(PetGeometry(96, 104, 11), PetFrames.geometry(768, 1144))
    }

    @Test
    fun geometry_rejectsAnythingElse() {
        assertNull(PetFrames.geometry(1080, 2340))
        assertNull(PetFrames.geometry(1536, 1000))
        assertNull(PetFrames.geometry(1537, 1872))
        assertNull(PetFrames.geometry(0, 0))
    }
}
