package com.folio.launcher.data

import android.app.ActivityOptions
import android.app.Application
import android.app.PendingIntent
import android.content.ComponentName
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioManager
import android.media.session.MediaController
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers

/** The play button: what a tap does, what a long-press opens, and never a system-wide PLAY. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class DeviceSignalsTest {
    private val app: Application = ApplicationProvider.getApplicationContext()
    private val audio = app.getSystemService(AudioManager::class.java)

    private fun state(code: Int) = PlaybackState.Builder().setState(code, 0L, 1f).build()

    @Test
    fun tap_pausesAnythingUnderWay_resumesAPausedPlayer_elseStartsSpotify() {
        // A tap during a track change or a stall pauses; it doesn't open Spotify.
        for (underWay in listOf(
            PlaybackState.STATE_PLAYING,
            PlaybackState.STATE_BUFFERING,
            PlaybackState.STATE_CONNECTING,
            PlaybackState.STATE_SKIPPING_TO_NEXT,
            PlaybackState.STATE_SKIPPING_TO_PREVIOUS,
        )) {
            assertEquals("state $underWay", DeviceSignals.PlayTap.Pause, DeviceSignals.playTap(state(underWay)))
        }
        assertEquals(DeviceSignals.PlayTap.Play, DeviceSignals.playTap(state(PlaybackState.STATE_PAUSED)))
        for (idle in listOf(PlaybackState.STATE_STOPPED, PlaybackState.STATE_NONE, PlaybackState.STATE_ERROR)) {
            assertEquals("state $idle", DeviceSignals.PlayTap.StartSpotify, DeviceSignals.playTap(state(idle)))
        }
        assertEquals(DeviceSignals.PlayTap.StartSpotify, DeviceSignals.playTap(null))
    }

    @Test
    fun longPressPlay_optsInToOpeningThePlayersOwnScreen() {
        val nowPlaying = PendingIntent.getActivity(
            app,
            0,
            Intent("com.spotify.music.NOW_PLAYING").setPackage("com.spotify.music"),
            PendingIntent.FLAG_IMMUTABLE,
        )
        val spotify = MediaController(app, MediaSession(app, "spotify").sessionToken)
        shadowOf(spotify).setPackageName("com.spotify.music")
        shadowOf(spotify).setSessionActivity(nowPlaying)
        val signals = DeviceSignals(app)
        // Bound as the session listener binds a paused Spotify (which needs notification access).
        ReflectionHelpers.setField(signals, "controller", spotify)

        assertTrue(signals.openSession(app))

        // Android 14+ drops the start unless the sender opts in, and send() succeeds either way.
        val sent = shadowOf(app).nextStartedActivityForResult
        assertEquals("com.spotify.music.NOW_PLAYING", sent.intent.action)
        // The opt-in itself, under ComponentOptions' own key (ActivityOptions.fromBundle isn't public API).
        @Suppress("DEPRECATION")
        val allowed = ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED
        assertEquals(allowed, sent.options!!.getInt("android.pendingIntent.backgroundActivityAllowed", -1))
    }

    /** Media-button broadcasts sent so far: each must go to Spotify alone. */
    private fun mediaButtons() = shadowOf(app).broadcastIntents.filter { it.action == Intent.ACTION_MEDIA_BUTTON }

    @Test
    fun startingSpotify_opensIt_withNoSystemWideKey() {
        val spotify = ComponentName("com.spotify.music", "com.spotify.music.MainActivity")
        val pm = shadowOf(app.packageManager)
        pm.addActivityIfNotPresent(spotify)
        pm.addIntentFilterForActivity(
            spotify,
            IntentFilter(Intent.ACTION_VIEW).apply {
                addCategory(Intent.CATEGORY_DEFAULT)
                addDataScheme("spotify")
            },
        )

        DeviceSignals(app).playPause(app)

        val started = shadowOf(app).nextStartedActivity
        assertEquals(Uri.parse("spotify:"), started.data)
        assertEquals("com.spotify.music", started.`package`)
        // A system-wide PLAY resumes whichever app played last, which may not be Spotify.
        assertTrue(shadowOf(audio).dispatchedMediaKeyEvents.isEmpty())
        assertTrue(mediaButtons().isNotEmpty())
        assertTrue(mediaButtons().all { it.`package` == "com.spotify.music" })
    }

    @Test
    fun withoutSpotify_aTapSendsNoSystemWideKey() {
        DeviceSignals(app).playPause(app)

        assertTrue(shadowOf(audio).dispatchedMediaKeyEvents.isEmpty())
        // Spotify's play button still goes out, to Spotify alone: no other player can take it.
        assertTrue(mediaButtons().all { it.`package` == "com.spotify.music" })
    }
}
