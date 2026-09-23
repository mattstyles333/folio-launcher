package com.folio.launcher.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PrefsMigrationTest {
    @Test
    fun legacyBingIndex_staysABingPrint() {
        val old = Prefs(bingIndex = 3, bingPrevIndex = 5)
        val now = old.migrated()
        assertTrue(now.isBingPrint)
        assertEquals(Prefs.LEGACY_BING_ID, now.bingId)
        assertEquals(Prefs.LEGACY_BING_ID, now.bingPrevId)
        assertEquals(-1, now.bingIndex)
        assertEquals(-1, now.bingPrevIndex)
    }

    @Test
    fun legacyLocalPhoto_staysLocal() {
        val now = Prefs(bingIndex = -1, bingPrevIndex = 2).migrated()
        assertFalse(now.isBingPrint)
        assertEquals(Prefs.LEGACY_BING_ID, now.bingPrevId)
    }

    @Test
    fun migratedPrefs_areUntouched() {
        val prefs = Prefs(bingId = "LakeMagadi", bingPrevId = "")
        assertEquals(prefs, prefs.migrated())
    }
}
