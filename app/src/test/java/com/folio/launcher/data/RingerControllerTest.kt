package com.folio.launcher.data

import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.folio.launcher.FolioApp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.shadows.ShadowAudioManager

/**
 * The user's own DND policy and ring level through Folio's Silent and Vibrate: on disk before
 * Folio overwrites them, handed back on every way out, a kill included. `app.ringer` is the
 * running process's controller; a new RingerController stands for the next process. Robolectric
 * doesn't model Android 15's per-app rules, so this is the Android 12–14 protocol the stash is for.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class RingerControllerTest {
    private val app: FolioApp = ApplicationProvider.getApplicationContext()
    private val nm = app.getSystemService(NotificationManager::class.java)
    private val audio = app.getSystemService(AudioManager::class.java)
    private val dnd = app.getSharedPreferences("folio_dnd", Context.MODE_PRIVATE)

    /** The user's own DND: starred callers ring, alarms sound. */
    private val users = NotificationManager.Policy(
        NotificationManager.Policy.PRIORITY_CATEGORY_CALLS or NotificationManager.Policy.PRIORITY_CATEGORY_ALARMS,
        NotificationManager.Policy.PRIORITY_SENDERS_STARRED,
        NotificationManager.Policy.PRIORITY_SENDERS_CONTACTS,
        0,
        NotificationManager.Policy.CONVERSATION_SENDERS_NONE,
    )

    @Before
    fun dndAccessAndTheUsersPolicy() {
        shadowOf(nm).setNotificationPolicyAccessGranted(true)
        nm.notificationPolicy = users
        nm.setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_ALL)
    }

    private fun livePolicy() = SavedDndPolicy.from(nm.notificationPolicy)

    private fun ring() = audio.getStreamVolume(AudioManager.STREAM_RING)

    @Test
    fun silent_stashesTheUsersPolicy_thenWritesFolios() {
        app.ringer.set(RingerVisual.Silent)

        assertEquals(SavedDndPolicy.from(users).encode(), dnd.getString("saved_policy", null))
        assertEquals(SavedDndPolicy.FOLIO_SILENT_CATEGORIES, nm.notificationPolicy.priorityCategories)
        assertEquals(NotificationManager.INTERRUPTION_FILTER_PRIORITY, nm.currentInterruptionFilter)
        assertEquals(RingerVisual.Silent, app.ringer.visual.value)
    }

    @Test
    fun silentAgain_keepsTheFirstStash() {
        app.ringer.set(RingerVisual.Silent)
        // Applied again (DND access back with Silent still wanted): Folio's own policy is live
        // now, and must never become the stash.
        app.ringer.set(RingerVisual.Silent)

        assertEquals(SavedDndPolicy.from(users).encode(), dnd.getString("saved_policy", null))
    }

    @Test
    fun sound_handsBackTheUsersPolicy_andDropsTheStash() {
        app.ringer.set(RingerVisual.Silent)
        app.ringer.set(RingerVisual.Sound)

        assertEquals(SavedDndPolicy.from(users), livePolicy())
        assertEquals(NotificationManager.INTERRUPTION_FILTER_ALL, nm.currentInterruptionFilter)
        assertFalse(dnd.contains("saved_policy"))
    }

    @Test
    fun dndEndedElsewhere_handsBackTheUsersPolicyOnTheBroadcast() {
        app.ringer.set(RingerVisual.Silent)
        // Quick Settings turns DND off; the policy left in force is still Folio's.
        nm.setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_ALL)
        app.sendBroadcast(Intent(NotificationManager.ACTION_INTERRUPTION_FILTER_CHANGED))
        shadowOf(Looper.getMainLooper()).idle()

        assertEquals(SavedDndPolicy.from(users), livePolicy())
        assertFalse(dnd.contains("saved_policy"))
    }

    @Test
    fun dndEndedWhileFolioWasDead_theNextProcessHandsBackTheUsersPolicy() {
        app.ringer.set(RingerVisual.Silent)
        // No broadcast reaches a dead process.
        nm.setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_ALL)

        RingerController(app).start()

        assertEquals(SavedDndPolicy.from(users), livePolicy())
        assertFalse(dnd.contains("saved_policy"))
    }

    @Test
    fun silentWithoutDndAccess_leavesTheUsersPolicyAlone() {
        shadowOf(nm).setNotificationPolicyAccessGranted(false)

        app.ringer.set(RingerVisual.Silent)

        assertFalse(dnd.contains("saved_policy"))
        assertEquals(SavedDndPolicy.from(users), livePolicy())
    }

    @Test
    fun soundAfterAKill_putsBackTheRingLevelVibrateZeroed() {
        audio.setStreamVolume(AudioManager.STREAM_RING, 5, 0)
        app.ringer.set(RingerVisual.Vibrate)
        assertEquals(0, ring())

        // The process dies in Vibrate (an update, say); the next one brings Sound back.
        RingerController(app).set(RingerVisual.Sound)

        assertEquals(5, ring())
        assertFalse(dnd.contains("ring_volume"))
    }

    @Test
    fun soundAfterTheCycle_liftsARingUnmutedAtOneStep() {
        audio.setStreamVolume(AudioManager.STREAM_RING, 5, 0)
        app.ringer.set(RingerVisual.Vibrate)
        app.ringer.set(RingerVisual.Silent)
        // Stock Android unmutes a zeroed ring at one step as the ringer goes back to normal;
        // Robolectric doesn't, so start Sound from there.
        audio.setStreamVolume(AudioManager.STREAM_RING, 1, 0)

        app.ringer.set(RingerVisual.Sound)

        assertEquals(5, ring())
    }

    /**
     * A Bedtime mode: calls blocked, so the look is Silent. Announced the way Android 12–14 do; the
     * consolidated broadcast Android 15 adds is pinned by the policy-in-force test below.
     */
    private fun bedtimeStarts() {
        nm.setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_PRIORITY)
        shadowOf(nm).setConsolidatedNotificationPolicy(
            NotificationManager.Policy(NotificationManager.Policy.PRIORITY_CATEGORY_ALARMS, 0, 0),
        )
        app.sendBroadcast(Intent(NotificationManager.ACTION_NOTIFICATION_POLICY_CHANGED))
        shadowOf(Looper.getMainLooper()).idle()
    }

    @Test
    fun soundAfterVibrateWasLeftElsewhere_leavesTheRingWhereTheUserPutIt() {
        audio.setStreamVolume(AudioManager.STREAM_RING, 6, 0)
        app.ringer.set(RingerVisual.Vibrate)
        // Volume up leaves Vibrate, and the user settles the ring at 3.
        audio.ringerMode = AudioManager.RINGER_MODE_NORMAL
        audio.setStreamVolume(AudioManager.STREAM_RING, 3, 0)
        app.sendBroadcast(Intent(AudioManager.RINGER_MODE_CHANGED_ACTION))
        shadowOf(Looper.getMainLooper()).idle()
        bedtimeStarts()
        assertEquals(RingerVisual.Silent, app.ringer.visual.value)

        app.ringer.set(RingerVisual.Sound)

        // Not back up to the 6 that Vibrate zeroed: that level was spent once the phone rang again.
        assertEquals(3, ring())
    }

    @Test
    fun aSoundThatDidntTake_keepsTheRingLevelForTheNextOne() {
        audio.setStreamVolume(AudioManager.STREAM_RING, 6, 0)
        app.ringer.set(RingerVisual.Vibrate)
        bedtimeStarts()
        // Without DND access Folio can't end the mode, so Sound doesn't take.
        shadowOf(nm).setNotificationPolicyAccessGranted(false)
        app.ringer.set(RingerVisual.Sound)
        assertEquals(6, dnd.getInt("ring_volume", 0))

        // Where the system refuses the ringer and the ring too (Robolectric doesn't), the mode ends
        // with the phone still in Vibrate. Sound from there brings back the user's level.
        audio.ringerMode = AudioManager.RINGER_MODE_VIBRATE
        audio.setStreamVolume(AudioManager.STREAM_RING, 0, 0)
        nm.setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_ALL)
        app.sendBroadcast(Intent(NotificationManager.ACTION_INTERRUPTION_FILTER_CHANGED))
        shadowOf(Looper.getMainLooper()).idle()
        app.ringer.set(RingerVisual.Sound)

        assertEquals(6, ring())
        assertFalse(dnd.contains("ring_volume"))
    }

    @Test
    fun aSoundTheSystemRefused_showsTheRingerAsItIs() {
        audio.setStreamVolume(AudioManager.STREAM_RING, 5, 0)
        bedtimeStarts()
        // Without DND access Folio can't end the mode, so Sound doesn't take. Nothing would explain a
        // ringing look held over a muted phone (only a refused Silent has a hint): the look stays Silent.
        shadowOf(nm).setNotificationPolicyAccessGranted(false)
        app.ringer.set(RingerVisual.Sound)

        assertEquals(RingerVisual.Silent, app.ringer.visual.value)
        assertFalse(app.ringer.refusedSilent.value)
    }

    @Test
    @Config(shadows = [RefusesSilentAudioManager::class])
    fun aRefusedSilent_isHeldOnlyUntilTheRingerMoves_evenIntoARealSilent() {
        RefusesSilentAudioManager.refusing = true
        shadowOf(nm).setNotificationPolicyAccessGranted(false)
        audio.ringerMode = AudioManager.RINGER_MODE_VIBRATE
        app.ringer.refresh()

        app.ringer.set(RingerVisual.Silent)
        // Refused: the look (and its hint) say Silent while the phone still vibrates.
        assertEquals(RingerVisual.Vibrate, app.ringer.readSystem())
        assertEquals(RingerVisual.Silent, app.ringer.visual.value)
        assertTrue(app.ringer.refusedSilent.value)

        // One UI's Mute gets there another way. The look was Silent already, so only the hold can say
        // the refused one is over (and take its hint with it).
        RefusesSilentAudioManager.refusing = false
        audio.ringerMode = AudioManager.RINGER_MODE_SILENT
        app.sendBroadcast(Intent(AudioManager.RINGER_MODE_CHANGED_ACTION))
        shadowOf(Looper.getMainLooper()).idle()

        assertEquals(RingerVisual.Silent, app.ringer.visual.value)
        assertFalse(app.ringer.refusedSilent.value)
    }

    @Test
    fun theLook_followsThePolicyInForce_evenWhenOnlyThePolicyChanges() {
        // Robolectric starts every stream at 0, which Folio rightly reads as Vibrate: a phone that rings.
        audio.setStreamVolume(AudioManager.STREAM_RING, 5, 0)
        nm.setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_PRIORITY)
        shadowOf(nm).setConsolidatedNotificationPolicy(users)
        app.ringer.refresh()
        assertEquals(RingerVisual.Sound, app.ringer.visual.value)

        // A Bedtime mode starts and blocks calls: only the merged policy changes.
        shadowOf(nm).setConsolidatedNotificationPolicy(
            NotificationManager.Policy(NotificationManager.Policy.PRIORITY_CATEGORY_ALARMS, 0, 0),
        )
        app.sendBroadcast(Intent(NotificationManager.ACTION_CONSOLIDATED_NOTIFICATION_POLICY_CHANGED))
        shadowOf(Looper.getMainLooper()).idle()

        assertEquals(RingerVisual.Silent, app.ringer.visual.value)
    }
}

/**
 * Android without DND access: an app can't put the ringer into Silent (AudioService throws, and
 * Folio's setMode lets it go). Robolectric's AudioManager never refuses, so this one does, until
 * [refusing] is off for a mute that comes from elsewhere.
 */
@Implements(AudioManager::class)
class RefusesSilentAudioManager : ShadowAudioManager() {
    @Implementation
    override fun setRingerMode(ringerMode: Int) {
        if (ringerMode != AudioManager.RINGER_MODE_SILENT || !refusing) super.setRingerMode(ringerMode)
    }

    companion object {
        var refusing = true
    }
}
