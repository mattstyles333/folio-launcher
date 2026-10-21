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
import android.app.usage.UsageEvents
import android.app.usage.UsageEventsQuery
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

    /** Where the system stayed when it ignored [overrideVisual]: the look is held only while it's still there. */
    private var overrideFrom: RingerVisual? = null

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context?, intent: Intent?) {
            refresh()
        }
    }

    fun start() {
        val filter = IntentFilter().apply {
            addAction(AudioManager.RINGER_MODE_CHANGED_ACTION)
            addAction(NotificationManager.ACTION_INTERRUPTION_FILTER_CHANGED)
            // The policy alone can change the look: exceptions edited, a mode starting or ending.
            addAction(NotificationManager.ACTION_NOTIFICATION_POLICY_CHANGED)
            if (Build.VERSION.SDK_INT >= 35) {
                addAction(NotificationManager.ACTION_CONSOLIDATED_NOTIFICATION_POLICY_CHANGED)
            }
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
        val sys = readSystem()
        forgetSpentRingVolume(sys)
        follow(sys)
    }

    /** Show [sys], unless the system ignored a request (Silent without DND access) and hasn't moved since. */
    private fun follow(sys: RingerVisual) {
        val next = nextVisual(overrideVisual, overrideFrom, sys)
        if (next == sys) {
            overrideVisual = null
            overrideFrom = null
        }
        _visual.value = next
    }

    fun consumeDndRequest() {
        _needsDndAccess.value = false
    }

    fun onDndAccessReturned() {
        val wanted = overrideVisual
        if (wanted != null && hasPolicyAccess()) {
            overrideVisual = null
            overrideFrom = null
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
        if (sys == visual) {
            overrideVisual = null
            overrideFrom = null
        } else {
            overrideFrom = sys
        }
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
                // Used once Sound takes. A Sound the system refused (DND it can't end) keeps it for the next.
                forgetSpentRingVolume(readSystem())
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

    /** Vibrate zeroes the ring, so its level is kept on disk: Sound puts it back after a kill or an update too. */
    private fun rememberRingVolume() {
        val volume = ringVolume()
        if (volume > 0) storeRingVolume(volume)
    }

    private fun restoreRingVolume() {
        val remembered = saved.getInt(KEY_RING_VOLUME, 0).takeIf { it > 0 }
        ringVolumeToRestore(ringVolume(), remembered, maxRingVolume())?.let(::setRingVolume)
    }

    /**
     * The phone rings again ([sys] is Sound), by Folio's Sound or another way (volume keys, Quick
     * Settings): the level Vibrate zeroed is spent. Left behind, a later Sound would raise the ring
     * over a level the user set since.
     */
    private fun forgetSpentRingVolume(sys: RingerVisual) {
        if (sys == RingerVisual.Sound && saved.contains(KEY_RING_VOLUME)) storeRingVolume(null)
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

    // commit() too: the level must be on disk before Vibrate zeroes the ring.
    @SuppressLint("ApplySharedPref")
    private fun storeRingVolume(volume: Int?) {
        saved.edit().apply {
            if (volume == null) remove(KEY_RING_VOLUME) else putInt(KEY_RING_VOLUME, volume)
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
        // The policy in force, every active mode merged. On Android 15+ notificationPolicy is only
        // Folio's own rule; the stash and restore above still read and write that one.
        val policy = runCatching { notifications.consolidatedNotificationPolicy }.getOrNull()
        return ringerVisual(
            interruptionFilter = notifications.currentInterruptionFilter,
            ringerMode = audio.ringerMode,
            ringVolume = ringVolume(),
            priorityCategories = policy?.priorityCategories ?: 0,
        )
    }

    companion object {
        private const val KEY_SAVED_POLICY = "saved_policy"
        private const val KEY_RING_VOLUME = "ring_volume"

        /**
         * The look: [override], a request the system ignored (null when none), while the system is
         * still where it stayed then ([overrideFrom]); otherwise the system's own [sys]. Volume
         * keys, Quick Settings or a mode moving the ringer win over a request that never took.
         */
        internal fun nextVisual(
            override: RingerVisual?,
            overrideFrom: RingerVisual?,
            sys: RingerVisual,
        ): RingerVisual = if (override != null && sys == overrideFrom) override else sys

        /**
         * The ring level Sound sets, or null to leave the ring alone. A ring below the level Vibrate
         * zeroed ([remembered]) goes back up to it: stock Android unmutes a zeroed ring at one step.
         * With nothing remembered, a silent ring gets 40% of [max].
         */
        internal fun ringVolumeToRestore(current: Int, remembered: Int?, max: Int): Int? = when {
            remembered != null && remembered > 0 -> if (current < remembered) remembered else null
            current == 0 -> (max * 0.4f).toInt().coerceAtLeast(1)
            else -> null
        }

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

        /** Nothing that rings gets through. Media doesn't matter: alarms-only is as silent as Folio's own. */
        internal fun isRingerSilentPolicy(priorityCategories: Int): Boolean {
            val rings = NotificationManager.Policy.PRIORITY_CATEGORY_CALLS or
                NotificationManager.Policy.PRIORITY_CATEGORY_REPEAT_CALLERS or
                NotificationManager.Policy.PRIORITY_CATEGORY_MESSAGES or
                NotificationManager.Policy.PRIORITY_CATEGORY_CONVERSATIONS
            return priorityCategories and rings == 0
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

        /** Opens per app ([foldOpens]) from [now] - [windowMs] up to [now] (wall clock). */
        fun queryUsageTimestamps(
            context: Context,
            windowMs: Long = Ranking.USAGE_WINDOW_MS,
            now: Long = System.currentTimeMillis(),
        ): Map<String, List<Long>> {
            if (!hasUsageAccess(context)) return emptyMap()
            return runCatching {
                val usm = context.getSystemService(UsageStatsManager::class.java)
                val events = resumeEvents(usm, now - windowMs, now) ?: return emptyMap()
                val event = UsageEvents.Event()
                val resumes = ArrayList<Pair<String, Long>>()
                while (events.hasNextEvent()) {
                    events.getNextEvent(event)
                    if (event.eventType != UsageEvents.Event.ACTIVITY_RESUMED) continue
                    val pkg = event.packageName ?: continue
                    resumes += pkg to event.timeStamp
                }
                foldOpens(resumes, context.packageName)
            }.getOrDefault(emptyMap())
        }

        /** Activity resumes. On Android 15+ the system leaves out every other event type before they reach Folio. */
        private fun resumeEvents(usm: UsageStatsManager, from: Long, to: Long): UsageEvents? {
            if (Build.VERSION.SDK_INT >= 35) {
                val query = UsageEventsQuery.Builder(from, to)
                    .setEventTypes(UsageEvents.Event.ACTIVITY_RESUMED)
                    .build()
                return usm.queryEvents(query)
            }
            return usm.queryEvents(from, to)
        }

        /**
         * Opens from activity resumes in time order: a resume counts only when the app resumed
         * before it was another one, so moving between one app's own screens is a single open.
         * Folio's resumes are never counted but still part of the order, so Home → app always counts.
         */
        internal fun foldOpens(resumes: List<Pair<String, Long>>, self: String): Map<String, List<Long>> {
            val opens = HashMap<String, MutableList<Long>>()
            var last: String? = null
            for ((pkg, at) in resumes) {
                if (pkg != last && pkg != self) opens.getOrPut(pkg) { ArrayList() }.add(at)
                last = pkg
            }
            return opens
        }
    }
}
