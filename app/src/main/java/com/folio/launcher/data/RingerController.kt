package com.folio.launcher.data

import android.annotation.SuppressLint
import android.app.NotificationManager
import android.app.role.RoleManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioManager
import android.os.Build
import android.app.AppOpsManager
import android.app.usage.UsageStatsManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class RingerController(private val context: Context) {
    private val audio = context.getSystemService(AudioManager::class.java)
    private val notifications = context.getSystemService(NotificationManager::class.java)

    private val _visual = MutableStateFlow(readSystem())
    val visual: StateFlow<RingerVisual> = _visual.asStateFlow()

    private val _needsDndAccess = MutableStateFlow(false)
    val needsDndAccess: StateFlow<Boolean> = _needsDndAccess.asStateFlow()

    private var overrideVisual: RingerVisual? = null

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context?, intent: Intent?) {
            restoreUserPolicyIfIdle()
            val sys = readSystem()
            if (overrideVisual != null && sys != overrideVisual) return
            overrideVisual = null
            _visual.value = sys
        }
    }

    fun start() {
        val filter = IntentFilter().apply {
            addAction(AudioManager.RINGER_MODE_CHANGED_ACTION)
            addAction(NotificationManager.ACTION_INTERRUPTION_FILTER_CHANGED)
        }
        if (Build.VERSION.SDK_INT >= 33) {
            // System ringer / DND broadcasts must be received from the OS.
            context.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            context.registerReceiver(receiver, filter)
        }
        refresh()
    }

    fun stop() {
        runCatching { context.unregisterReceiver(receiver) }
    }

    fun refresh() {
        restoreUserPolicyIfIdle()
        if (overrideVisual == null) {
            _visual.value = readSystem()
        } else {
            _visual.value = overrideVisual!!
        }
    }

    fun consumeDndRequest() {
        _needsDndAccess.value = false
    }

    fun onDndAccessReturned() {
        val wanted = overrideVisual
        if (wanted != null && hasPolicyAccess()) {
            overrideVisual = null
            apply(wanted)
        }
        refresh()
    }

    fun cycle() {
        val next = when (_visual.value) {
            RingerVisual.Sound -> RingerVisual.Vibrate
            RingerVisual.Vibrate -> RingerVisual.Silent
            RingerVisual.Silent -> RingerVisual.Sound
        }
        set(next)
    }

    fun set(visual: RingerVisual) {
        overrideVisual = visual
        _visual.value = visual
        apply(visual)
        val sys = readSystem()
        if (sys == visual) overrideVisual = null
    }

    private fun apply(visual: RingerVisual) {
        when (visual) {
            RingerVisual.Sound -> {
                clearDnd()
                setMode(AudioManager.RINGER_MODE_NORMAL)
                restoreRingVolume()
                if (readSystem() != RingerVisual.Sound) {
                    restoreRingVolume()
                    setMode(AudioManager.RINGER_MODE_NORMAL)
                }
            }
            RingerVisual.Vibrate -> {
                rememberRingVolume()
                clearDnd()
                setMode(AudioManager.RINGER_MODE_VIBRATE)
                setRingVolume(0)
                if (readSystem() != RingerVisual.Vibrate) {
                    setRingVolume(0)
                    setMode(AudioManager.RINGER_MODE_VIBRATE)
                }
            }
            RingerVisual.Silent -> {
                rememberRingVolume()
                val silenced = trySilent()
                if (!silenced && !hasPolicyAccess()) {
                    _needsDndAccess.value = true
                }
            }
        }
    }

    private var lastRingVolume = 0

    private fun ringVolume(): Int =
        runCatching { audio.getStreamVolume(AudioManager.STREAM_RING) }.getOrDefault(0)

    private fun maxRingVolume(): Int =
        runCatching { audio.getStreamMaxVolume(AudioManager.STREAM_RING) }.getOrDefault(7)

    private fun setRingVolume(volume: Int) {
        runCatching {
            audio.setStreamVolume(
                AudioManager.STREAM_RING,
                volume.coerceIn(0, maxRingVolume()),
                0,
            )
        }
    }

    private fun rememberRingVolume() {
        val volume = ringVolume()
        if (volume > 0) lastRingVolume = volume
    }

    private fun restoreRingVolume() {
        val fallback = (maxRingVolume() * 0.4f).toInt().coerceAtLeast(1)
        val target = if (lastRingVolume > 0) lastRingVolume else fallback
        if (ringVolume() == 0) setRingVolume(target)
    }

    private val saved = context.getSharedPreferences("folio_dnd", Context.MODE_PRIVATE)

    /** The user's own DND policy, stashed while Folio's Silent owns DND. Survives process death. */
    private fun loadSavedPolicy(): SavedDndPolicy? =
        SavedDndPolicy.decode(saved.getString(KEY_SAVED_POLICY, null))

    // commit(), not apply(): the stash must be on disk before the live policy is overwritten.
    @SuppressLint("ApplySharedPref")
    private fun storeSavedPolicy(policy: SavedDndPolicy?) {
        saved.edit().apply {
            if (policy == null) remove(KEY_SAVED_POLICY) else putString(KEY_SAVED_POLICY, policy.encode())
        }.commit()
    }

    /** Hand DND back to the user once Folio's Silent is no longer in force. */
    private fun restoreUserPolicyIfIdle() {
        val stash = loadSavedPolicy() ?: return
        if (!hasPolicyAccess()) return
        if (notifications.currentInterruptionFilter != NotificationManager.INTERRUPTION_FILTER_ALL) return
        runCatching {
            val current = notifications.notificationPolicy
            if (SavedDndPolicy.isFolioSilent(current.priorityCategories)) {
                notifications.notificationPolicy = stash.toPolicy()
            }
        }
        storeSavedPolicy(null)
    }

    /**
     * Leaving Silent. Any DND ends here anyway: the platform exits DND as soon as an app sets
     * the ringer to normal or vibrate. What matters is handing back the user's own policy.
     */
    private fun clearDnd() {
        if (!hasPolicyAccess()) return
        val stash = loadSavedPolicy()
        runCatching {
            stash?.let { notifications.notificationPolicy = it.toPolicy() }
            notifications.setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_ALL)
        }
        storeSavedPolicy(null)
    }

    private fun setMode(mode: Int): Boolean {
        return runCatching {
            audio.ringerMode = mode
            audio.ringerMode == mode
        }.getOrDefault(false)
    }

    private fun trySilent(): Boolean {
        setMode(AudioManager.RINGER_MODE_SILENT)
        if (!hasPolicyAccess()) {
            return audio.ringerMode == AudioManager.RINGER_MODE_SILENT
        }
        runCatching {
            val current = notifications.notificationPolicy
            val stash = loadSavedPolicy() ?: SavedDndPolicy.from(current).also {
                // Persist before touching the policy so a kill mid-Silent can still restore it.
                storeSavedPolicy(it)
            }
            // Total silence (FILTER_NONE) ducks STREAM_MUSIC on One UI.
            // Priority DND with media + alarms keeps Spotify, kills calls.
            notifications.notificationPolicy = silentMediaPolicy(stash)
            notifications.setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_PRIORITY)
        }
        return readSystem() == RingerVisual.Silent
    }

    fun hasPolicyAccess(): Boolean = notifications.isNotificationPolicyAccessGranted

    fun readSystem(): RingerVisual {
        val policy = runCatching { notifications.notificationPolicy }.getOrNull()
        return ringerVisual(
            interruptionFilter = notifications.currentInterruptionFilter,
            ringerMode = audio.ringerMode,
            ringVolume = ringVolume(),
            priorityCategories = policy?.priorityCategories ?: 0,
        )
    }

    companion object {
        private const val KEY_SAVED_POLICY = "saved_policy"

        internal fun silentMediaPolicy(user: SavedDndPolicy): NotificationManager.Policy {
            return NotificationManager.Policy(
                SavedDndPolicy.FOLIO_SILENT_CATEGORIES,
                user.callSenders,
                user.messageSenders,
                user.suppressedVisualEffects,
                NotificationManager.Policy.CONVERSATION_SENDERS_NONE,
            )
        }

        internal fun ringerVisual(
            interruptionFilter: Int,
            ringerMode: Int,
            ringVolume: Int,
            priorityCategories: Int,
        ): RingerVisual {
            when (interruptionFilter) {
                NotificationManager.INTERRUPTION_FILTER_NONE,
                NotificationManager.INTERRUPTION_FILTER_ALARMS,
                -> return RingerVisual.Silent
                NotificationManager.INTERRUPTION_FILTER_PRIORITY -> {
                    if (isRingerSilentPolicy(priorityCategories)) return RingerVisual.Silent
                }
            }
            return when (ringerMode) {
                AudioManager.RINGER_MODE_SILENT -> RingerVisual.Silent
                AudioManager.RINGER_MODE_VIBRATE -> RingerVisual.Vibrate
                else -> if (ringVolume == 0) RingerVisual.Vibrate else RingerVisual.Sound
            }
        }

        internal fun isRingerSilentPolicy(priorityCategories: Int): Boolean {
            val media = priorityCategories and NotificationManager.Policy.PRIORITY_CATEGORY_MEDIA != 0
            val calls = priorityCategories and NotificationManager.Policy.PRIORITY_CATEGORY_CALLS != 0
            val messages = priorityCategories and NotificationManager.Policy.PRIORITY_CATEGORY_MESSAGES != 0
            return media && !calls && !messages
        }

        fun isDefaultHome(context: Context): Boolean {
            val role = context.getSystemService(RoleManager::class.java)
            if (role.isRoleHeld(RoleManager.ROLE_HOME)) return true
            val home = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
            val resolved = context.packageManager.resolveActivity(home, 0)
            return resolved?.activityInfo?.packageName == context.packageName
        }

        fun roleIntent(context: Context): Intent {
            val role = context.getSystemService(RoleManager::class.java)
            return if (role.isRoleAvailable(RoleManager.ROLE_HOME)) {
                role.createRequestRoleIntent(RoleManager.ROLE_HOME)
            } else {
                Intent(android.provider.Settings.ACTION_HOME_SETTINGS)
            }
        }

        fun hasUsageAccess(context: Context): Boolean {
            return try {
                val appOps = context.getSystemService(AppOpsManager::class.java)
                val mode = appOps.checkOpNoThrow(
                    AppOpsManager.OPSTR_GET_USAGE_STATS,
                    android.os.Process.myUid(),
                    context.packageName,
                )
                mode == AppOpsManager.MODE_ALLOWED
            } catch (_: Exception) {
                false
            }
        }

        fun queryUsageTimestamps(
            context: Context,
            windowMs: Long = 30 * Ranking.DAY_MS,
        ): Map<String, List<Long>> {
            if (!hasUsageAccess(context)) return emptyMap()
            return runCatching {
                val usm = context.getSystemService(UsageStatsManager::class.java)
                val now = System.currentTimeMillis()
                val events = usm.queryEvents(now - windowMs, now)
                val event = android.app.usage.UsageEvents.Event()
                val map = HashMap<String, MutableList<Long>>()
                val self = context.packageName
                while (events.hasNextEvent()) {
                    events.getNextEvent(event)
                    val type = event.eventType
                    if (type != android.app.usage.UsageEvents.Event.ACTIVITY_RESUMED) continue
                    val pkg = event.packageName ?: continue
                    if (pkg == self) continue
                    map.getOrPut(pkg) { ArrayList() }.add(event.timeStamp)
                }
                map
            }.getOrDefault(emptyMap())
        }
    }
}
