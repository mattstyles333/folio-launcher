package com.pulse.launcher.data

import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioManager
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.BatteryManager
import android.os.Build
import android.os.Handler
import android.os.Looper
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
    val playing: Boolean = false,
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

    fun skip() {
        val controls = controller?.transportControls
        if (controls != null) {
            controls.skipToNext()
            return
        }
        val down = KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_MEDIA_NEXT)
        val up = KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_MEDIA_NEXT)
        audio.dispatchMediaKeyEvent(down)
        audio.dispatchMediaKeyEvent(up)
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
        return list.firstOrNull { it.playbackState?.state == PlaybackState.STATE_PLAYING }
            ?: list.firstOrNull { playingOrPaused(it.playbackState?.state) }
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
        _now.value = NowPlaying(line = if (active) line else "", playing = state == PlaybackState.STATE_PLAYING)
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
