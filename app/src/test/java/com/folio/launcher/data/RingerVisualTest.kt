package com.folio.launcher.data

import android.app.NotificationManager
import android.media.AudioManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
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
    fun priority_alarmsOnly_isSilent() {
        val cats = NotificationManager.Policy.PRIORITY_CATEGORY_ALARMS
        assertTrue(RingerController.isRingerSilentPolicy(cats))
        // The platform mutes the ring under it, so the ringer alone would read Vibrate.
        assertEquals(
            RingerVisual.Silent,
            RingerController.ringerVisual(
                interruptionFilter = NotificationManager.INTERRUPTION_FILTER_PRIORITY,
                ringerMode = AudioManager.RINGER_MODE_NORMAL,
                ringVolume = 0,
                priorityCategories = cats,
            ),
        )
    }

    @Test
    fun priority_noCallsNoMessagesNoMedia_isSilent() {
        assertTrue(RingerController.isRingerSilentPolicy(0))
        assertTrue(
            RingerController.isRingerSilentPolicy(
                NotificationManager.Policy.PRIORITY_CATEGORY_REMINDERS or
                    NotificationManager.Policy.PRIORITY_CATEGORY_EVENTS,
            ),
        )
    }

    @Test
    fun priority_anythingThatRings_followsRinger() {
        for (rings in listOf(
            NotificationManager.Policy.PRIORITY_CATEGORY_REPEAT_CALLERS,
            NotificationManager.Policy.PRIORITY_CATEGORY_MESSAGES,
            NotificationManager.Policy.PRIORITY_CATEGORY_CONVERSATIONS,
        )) {
            val cats = SavedDndPolicy.FOLIO_SILENT_CATEGORIES or rings
            assertFalse("categories $cats", RingerController.isRingerSilentPolicy(cats))
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

    @Test
    fun ignoredRequest_holdsTheLookOnlyWhileTheSystemStaysPut() {
        val silent = RingerVisual.Silent
        val vibrate = RingerVisual.Vibrate
        // Silent asked for without DND access: the phone stayed on Vibrate, the print looks Silent.
        assertEquals(silent, RingerController.nextVisual(override = silent, overrideFrom = vibrate, sys = vibrate))
        // Volume up: the phone rings now, so the print says so.
        assertEquals(
            RingerVisual.Sound,
            RingerController.nextVisual(override = silent, overrideFrom = vibrate, sys = RingerVisual.Sound),
        )
        // Silent after all (from Quick Settings, say).
        assertEquals(silent, RingerController.nextVisual(override = silent, overrideFrom = vibrate, sys = silent))
        // Nothing held: the system's own look.
        assertEquals(vibrate, RingerController.nextVisual(override = null, overrideFrom = null, sys = vibrate))
    }

    @Test
    fun ringVolumeToRestore_liftsTheRingBackToItsLevel() {
        // Stock Android unmutes a zeroed ring at one step: back to the user's 11.
        assertEquals(11, RingerController.ringVolumeToRestore(current = 1, remembered = 11, max = 15))
        assertEquals(11, RingerController.ringVolumeToRestore(current = 0, remembered = 11, max = 15))
        // Already there, or raised since: left alone.
        assertNull(RingerController.ringVolumeToRestore(current = 11, remembered = 11, max = 15))
        assertNull(RingerController.ringVolumeToRestore(current = 13, remembered = 11, max = 15))
        // Nothing remembered: a silent ring gets 40% of max, an audible one is left alone.
        assertEquals(6, RingerController.ringVolumeToRestore(current = 0, remembered = null, max = 15))
        assertNull(RingerController.ringVolumeToRestore(current = 1, remembered = null, max = 15))
    }
}
