package com.pulse.launcher.data

import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.media.AudioManager
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.BatteryManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.view.KeyEvent
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class ChargeState(
    val charging: Boolean = false,
    val fraction: Float = 0f,
)

data class NowPlaying(
    val line: String = "",
    val title: String = "",
    val artist: String = "",
    val playing: Boolean = false,
    val packageName: String = "",
    val art: Bitmap? = null,
    val positionMs: Long = 0L,
    val durationMs: Long = 0L,
)

class DeviceSignals(private val context: Context) {
    private val app = context.applicationContext
    private val batteryReceiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context?, intent: Intent?) {
            if (intent != null) readBattery(intent)
        }
    }
    private val _charge = MutableStateFlow(ChargeState())
    val charge: StateFlow<ChargeState> = _charge.asStateFlow()

    private val _now = MutableStateFlow(NowPlaying())
    val nowPlaying: StateFlow<NowPlaying> = _now.asStateFlow()

    private val sessions = app.getSystemService(MediaSessionManager::class.java)
    private val audio = app.getSystemService(AudioManager::class.java)
    private val listenerName = ComponentName(app, PulseSessionListener::class.java)
    private val main = Handler(Looper.getMainLooper())
    private var controller: MediaController? = null
    private var artKey: String = ""
    private var artCache: Bitmap? = null
    private val controllerCallback = object : MediaController.Callback() {
        override fun onMetadataChanged(metadata: MediaMetadata?) = publish()
        override fun onPlaybackStateChanged(state: PlaybackState?) = publish()
        override fun onSessionDestroyed() {
            bind(null)
            refreshSessions()
        }
    }
    private val sessionListener = MediaSessionManager.OnActiveSessionsChangedListener { list ->
        bind(pick(list.orEmpty()))
    }
    private var listeningSessions = false

    fun start() {
        val sticky = if (Build.VERSION.SDK_INT >= 33) {
            app.registerReceiver(
                batteryReceiver,
                IntentFilter(Intent.ACTION_BATTERY_CHANGED),
                Context.RECEIVER_NOT_EXPORTED,
            )
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            app.registerReceiver(batteryReceiver, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        }
        sticky?.let(::readBattery)
        attachSessions()
    }

    fun stop() {
        runCatching { app.unregisterReceiver(batteryReceiver) }
        detachSessions()
    }

    fun refresh() {
        attachSessions()
    }

    fun hasNowPlayingAccess(): Boolean = listenerEnabled()

    fun skip() = transportOrKey(MediaController.TransportControls::skipToNext, KeyEvent.KEYCODE_MEDIA_NEXT)

    fun previous() = transportOrKey(MediaController.TransportControls::skipToPrevious, KeyEvent.KEYCODE_MEDIA_PREVIOUS)

    fun playPause() {
        val controls = controller?.transportControls
        if (controls != null) {
            if (controller?.playbackState?.state == PlaybackState.STATE_PLAYING) {
                controls.pause()
            } else {
                controls.play()
            }
            return
        }
        dispatchKey(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE)
    }

    fun seek(fraction: Float) {
        val dur = controller?.metadata?.getLong(MediaMetadata.METADATA_KEY_DURATION) ?: 0L
        if (dur <= 0L) return
        val ms = (dur * fraction.coerceIn(0f, 1f)).toLong()
        controller?.transportControls?.seekTo(ms)
    }

    private fun transportOrKey(
        call: MediaController.TransportControls.() -> Unit,
        keyCode: Int,
    ) {
        val controls = controller?.transportControls
        if (controls != null) {
            controls.call()
            return
        }
        dispatchKey(keyCode)
    }

    private fun dispatchKey(code: Int) {
        audio.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, code))
        audio.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, code))
    }

    private fun attachSessions() {
        if (!listenerEnabled()) {
            detachSessions()
            _now.value = NowPlaying()
            return
        }
        if (!listeningSessions) {
            runCatching {
                sessions.addOnActiveSessionsChangedListener(sessionListener, listenerName)
                listeningSessions = true
            }
        }
        refreshSessions()
    }

    private fun detachSessions() {
        if (listeningSessions) {
            runCatching { sessions.removeOnActiveSessionsChangedListener(sessionListener) }
            listeningSessions = false
        }
        bind(null)
        _now.value = NowPlaying()
    }

    private fun refreshSessions() {
        val list = runCatching { sessions.getActiveSessions(listenerName) }.getOrDefault(emptyList())
        bind(pick(list))
    }

    private fun pick(list: List<MediaController>): MediaController? {
        val playing = list.filter { it.playbackState?.state == PlaybackState.STATE_PLAYING }
        val paused = list.filter { playingOrPaused(it.playbackState?.state) }
        return playing.firstOrNull { it.packageName.contains("spotify", ignoreCase = true) }
            ?: playing.firstOrNull()
            ?: paused.firstOrNull { it.packageName.contains("spotify", ignoreCase = true) }
            ?: paused.firstOrNull()
    }

    private fun bind(next: MediaController?) {
        val current = controller
        if (current?.sessionToken == next?.sessionToken) {
            publish()
            return
        }
        current?.unregisterCallback(controllerCallback)
        controller = next
        next?.registerCallback(controllerCallback, main)
        publish()
    }

    private fun publish() {
        val c = controller
        val state = c?.playbackState?.state
        val meta = c?.metadata
        val title = meta?.getString(MediaMetadata.METADATA_KEY_TITLE)?.trim().orEmpty()
        val artist = meta?.getString(MediaMetadata.METADATA_KEY_ARTIST)?.trim().orEmpty()
        val line = when {
            title.isNotEmpty() && artist.isNotEmpty() -> "$title  ·  $artist"
            title.isNotEmpty() -> title
            artist.isNotEmpty() -> artist
            else -> ""
        }
        val active = playingOrPaused(state) && line.isNotEmpty()
        val pkg = c?.packageName.orEmpty()
        val art = if (active) albumArt(meta, "$pkg|$line") else {
            artKey = ""
            artCache = null
            null
        }
        val duration = meta?.getLong(MediaMetadata.METADATA_KEY_DURATION) ?: 0L
        val position = c?.playbackState?.let { playbackPosition(it) } ?: 0L
        _now.value = NowPlaying(
            line = if (active) line else "",
            title = if (active) title else "",
            artist = if (active) artist else "",
            playing = state == PlaybackState.STATE_PLAYING,
            packageName = if (active) pkg else "",
            art = art,
            positionMs = if (active) position else 0L,
            durationMs = if (active) duration else 0L,
        )
    }

    private fun playbackPosition(state: PlaybackState): Long {
        if (state.state != PlaybackState.STATE_PLAYING) return state.position.coerceAtLeast(0L)
        val delta = SystemClock.elapsedRealtime() - state.lastPositionUpdateTime
        return (state.position + (delta * state.playbackSpeed).toLong()).coerceAtLeast(0L)
    }

    private fun albumArt(meta: MediaMetadata?, key: String): Bitmap? {
        if (key == artKey) return artCache
        artKey = key
        val src = meta?.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART)
            ?: meta?.getBitmap(MediaMetadata.METADATA_KEY_ART)
        artCache = src?.let { Bitmap.createScaledBitmap(it, 720, 720, true) }
        return artCache
    }

    private fun readBattery(intent: Intent) {
        val plugged = intent.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) != 0
        val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, 0).coerceAtLeast(0)
        val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, 100).coerceAtLeast(1)
        _charge.value = ChargeState(charging = plugged, fraction = (level / scale.toFloat()).coerceIn(0f, 1f))
    }

    private fun listenerEnabled(): Boolean {
        val raw = Settings.Secure.getString(app.contentResolver, "enabled_notification_listeners") ?: return false
        return raw.split(":").any { it.contains(app.packageName, ignoreCase = true) }
    }

    companion object {
        private fun playingOrPaused(state: Int?): Boolean {
            return state == PlaybackState.STATE_PLAYING || state == PlaybackState.STATE_PAUSED
        }
    }
}
