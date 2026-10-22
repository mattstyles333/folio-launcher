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

    @Test
    fun oldBlobs_stillRead_withTheFieldsSinceDropped() {
        // Written before onboardingComplete and dismissedRecents went: nothing reads them now.
        val old = """{"showClock":false,"onboardingComplete":true,"dismissedRecents":{"com.chat":1700000000000},""" +
            """"hiddenPackages":["com.hidden"],"bingId":"LakeMagadi"}"""
        val prefs = PrefsStore.read(old)!!
        assertFalse(prefs.showClock)
        assertEquals(listOf("com.hidden"), prefs.hiddenPackages)
        assertEquals("LakeMagadi", prefs.bingId)
        assertEquals(emptyList<String>(), prefs.recentBingIds)
    }

    @Test
    fun blobsFromBeforeYourPhotos_haveNoRoll() {
        val prefs = PrefsStore.read("""{"bingId":"LakeMagadi","bingDay":20000,"quoteFit":12}""")!!
        assertEquals(emptyList<String>(), prefs.rollFiles)
        assertEquals(-1, prefs.rollAt)
        assertEquals(-1, prefs.rollPrevAt)
        assertEquals(-1, prefs.rollDay)
        assertFalse(prefs.isRollPrint)
        // The print is still the Bing it was, with its fitted quote.
        assertTrue(prefs.isBingPrint)
        assertEquals(12, prefs.quoteFit)
    }

    @Test
    fun aRoll_readsBack_andOnlyAPositionInItIsARollPrint() {
        val prefs = PrefsStore.read(
            """{"rollFiles":["photo1.jpg","photo2.jpg"],"rollAt":1,"rollPrevAt":0,"rollDay":20000}""",
        )!!
        assertEquals(listOf("photo1.jpg", "photo2.jpg"), prefs.rollFiles)
        assertEquals(20000, prefs.rollDay)
        assertTrue(prefs.isRollPrint)
        assertFalse(prefs.isBingPrint)
        assertFalse(prefs.copy(rollAt = 2).isRollPrint)
        assertFalse(prefs.copy(rollAt = -1).isRollPrint)
    }
}
