package com.folio.launcher.data

import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.media.AudioManager
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

class DeviceSignals(private val context: Context) {
    private val app = context.applicationContext
    private val batteryReceiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context?, intent: Intent?) {
            if (intent != null) readBattery(intent)
        }
    }
    private val _charge = MutableStateFlow(ChargeState())
    val charge: StateFlow<ChargeState> = _charge.asStateFlow()

    private val _musicPlaying = MutableStateFlow(false)
    val musicPlaying: StateFlow<Boolean> = _musicPlaying.asStateFlow()

    private val sessions = app.getSystemService(MediaSessionManager::class.java)
    private val audio = app.getSystemService(AudioManager::class.java)
    private val listenerName = ComponentName(app, FolioSessionListener::class.java)
    private val main = Handler(Looper.getMainLooper())
    private var controller: MediaController? = null
    private val controllerCallback = object : MediaController.Callback() {
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

    fun playPause(host: Context = app) {
        val controls = controller?.transportControls
        val state = controller?.playbackState?.state
        if (controls != null && playingOrPaused(state)) {
            if (state == PlaybackState.STATE_PLAYING) controls.pause() else controls.play()
            return
        }
        startSpotify(host)
    }

    fun openSession(host: Context = app): Boolean {
        val sent = controller?.sessionActivity?.let { pi ->
            runCatching {
                pi.send()
                true
            }.getOrDefault(false)
        } ?: false
        if (sent) return true
        val pkg = controller?.packageName?.takeIf { it.isNotBlank() }
            ?: SPOTIFY_PACKAGE
        return launchPlayer(host, pkg)
    }

    private fun startSpotify(host: Context) {
        dispatchKey(KeyEvent.KEYCODE_MEDIA_PLAY)
        sendMediaButton(host, KeyEvent.KEYCODE_MEDIA_PLAY)
        if (!launchPlayer(host, SPOTIFY_PACKAGE)) {
            dispatchKey(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE)
        }
    }

    private fun sendMediaButton(host: Context, code: Int) {
        fun fire(action: Int) {
            val intent = Intent(Intent.ACTION_MEDIA_BUTTON).apply {
                `package` = SPOTIFY_PACKAGE
                putExtra(Intent.EXTRA_KEY_EVENT, KeyEvent(action, code))
            }
            runCatching { host.sendBroadcast(intent) }
        }
        fire(KeyEvent.ACTION_DOWN)
        fire(KeyEvent.ACTION_UP)
    }

    private fun launchPlayer(host: Context, pkg: String): Boolean {
        val flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED
        if (pkg.contains("spotify", ignoreCase = true) || pkg == SPOTIFY_PACKAGE) {
            val spotify = Intent(Intent.ACTION_VIEW, Uri.parse("spotify:")).apply {
                addFlags(flags)
                `package` = SPOTIFY_PACKAGE
            }
            if (spotify.resolveActivity(host.packageManager) != null) {
                val ok = runCatching { host.startActivity(spotify); true }.getOrDefault(false)
                if (ok) return true
            }
        }
        val launch = host.packageManager.getLaunchIntentForPackage(pkg)?.addFlags(flags) ?: return false
        return runCatching { host.startActivity(launch); true }.getOrDefault(false)
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
        _musicPlaying.value = controller?.playbackState?.state == PlaybackState.STATE_PLAYING
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
        private const val SPOTIFY_PACKAGE = "com.spotify.music"

        private fun playingOrPaused(state: Int?): Boolean {
            return state == PlaybackState.STATE_PLAYING || state == PlaybackState.STATE_PAUSED
        }
    }
}
