package com.folio.launcher.data

import android.app.NotificationManager
import android.media.AudioManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RingerVisualTest {
    @Test
    fun totalSilence_isSilent() {
        assertEquals(
            RingerVisual.Silent,
            RingerController.ringerVisual(
                interruptionFilter = NotificationManager.INTERRUPTION_FILTER_NONE,
                ringerMode = AudioManager.RINGER_MODE_NORMAL,
                ringVolume = 7,
                priorityCategories = 0,
            ),
        )
    }

    @Test
    fun alarmsOnly_isSilent() {
        assertEquals(
            RingerVisual.Silent,
            RingerController.ringerVisual(
                interruptionFilter = NotificationManager.INTERRUPTION_FILTER_ALARMS,
                ringerMode = AudioManager.RINGER_MODE_NORMAL,
                ringVolume = 7,
                priorityCategories = 0,
            ),
        )
    }

    @Test
    fun priority_mediaWithoutCalls_isSilent() {
        val cats = NotificationManager.Policy.PRIORITY_CATEGORY_ALARMS or
            NotificationManager.Policy.PRIORITY_CATEGORY_MEDIA
        assertTrue(RingerController.isRingerSilentPolicy(cats))
        assertEquals(
            RingerVisual.Silent,
            RingerController.ringerVisual(
                interruptionFilter = NotificationManager.INTERRUPTION_FILTER_PRIORITY,
                ringerMode = AudioManager.RINGER_MODE_NORMAL,
                ringVolume = 7,
                priorityCategories = cats,
            ),
        )
    }

    @Test
    fun priority_allowingCalls_followsRinger() {
        val cats = NotificationManager.Policy.PRIORITY_CATEGORY_CALLS or
            NotificationManager.Policy.PRIORITY_CATEGORY_MEDIA
        assertFalse(RingerController.isRingerSilentPolicy(cats))
        assertEquals(
            RingerVisual.Sound,
            RingerController.ringerVisual(
                interruptionFilter = NotificationManager.INTERRUPTION_FILTER_PRIORITY,
                ringerMode = AudioManager.RINGER_MODE_NORMAL,
                ringVolume = 7,
                priorityCategories = cats,
            ),
        )
    }

    @Test
    fun noDnd_usesRingerMode() {
        assertEquals(
            RingerVisual.Sound,
            RingerController.ringerVisual(
                interruptionFilter = NotificationManager.INTERRUPTION_FILTER_ALL,
                ringerMode = AudioManager.RINGER_MODE_NORMAL,
                ringVolume = 4,
                priorityCategories = 0,
            ),
        )
        assertEquals(
            RingerVisual.Vibrate,
            RingerController.ringerVisual(
                interruptionFilter = NotificationManager.INTERRUPTION_FILTER_ALL,
                ringerMode = AudioManager.RINGER_MODE_VIBRATE,
                ringVolume = 0,
                priorityCategories = 0,
            ),
        )
        assertEquals(
            RingerVisual.Silent,
            RingerController.ringerVisual(
                interruptionFilter = NotificationManager.INTERRUPTION_FILTER_ALL,
                ringerMode = AudioManager.RINGER_MODE_SILENT,
                ringVolume = 0,
                priorityCategories = 0,
            ),
        )
    }

    @Test
    fun zeroRingVolume_readsAsVibrate() {
        assertEquals(
            RingerVisual.Vibrate,
            RingerController.ringerVisual(
                interruptionFilter = NotificationManager.INTERRUPTION_FILTER_ALL,
                ringerMode = AudioManager.RINGER_MODE_NORMAL,
                ringVolume = 0,
                priorityCategories = 0,
            ),
        )
    }

    @Test
    fun savedPolicy_roundTrips() {
        val policy = SavedDndPolicy(
            categories = 0x1FF,
            callSenders = 1,
            messageSenders = 2,
            suppressedVisualEffects = 0x80,
            conversationSenders = 3,
        )
        assertEquals(policy, SavedDndPolicy.decode(policy.encode()))
        assertEquals(null, SavedDndPolicy.decode(null))
        assertEquals(null, SavedDndPolicy.decode("1,2,x,4,5"))
        assertEquals(null, SavedDndPolicy.decode("1,2,3"))
    }

    @Test
    fun folioSilent_isRecognised() {
        assertTrue(SavedDndPolicy.isFolioSilent(SavedDndPolicy.FOLIO_SILENT_CATEGORIES))
        assertFalse(
            SavedDndPolicy.isFolioSilent(
                SavedDndPolicy.FOLIO_SILENT_CATEGORIES or NotificationManager.Policy.PRIORITY_CATEGORY_CALLS,
            ),
        )
    }
}
