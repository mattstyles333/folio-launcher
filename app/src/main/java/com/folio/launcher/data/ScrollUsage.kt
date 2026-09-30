package com.folio.launcher.data

import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context

/** Apps built to be scrolled forever. The user can add or remove any; Jev sorts the rest. */
object EndlessApps {
    val KNOWN = setOf(
        "com.twitter.android",
        "com.google.android.youtube",
        "com.instagram.android",
        "com.instagram.barcelona",
        "com.zhiliaoapp.musically",
        "com.ss.android.ugc.trill",
        "com.reddit.frontpage",
        "com.facebook.katana",
        "com.snapchat.android",
        "com.pinterest",
        "tv.twitch.android.app",
        "com.tumblr",
        "xyz.blueskyweb.app",
    )

    /** Installed endless apps: known or Jev-flagged ([picked]), minus what the user took off. */
    fun effective(installed: Set<String>, picked: Collection<String>, off: Collection<String>): Set<String> =
        (KNOWN + picked).filterTo(LinkedHashSet()) { it in installed && it !in off }
}

data class UsageEvt(val pkg: String, val resumed: Boolean, val at: Long)

/** One stretch of an app in the foreground. */
data class AppSession(val pkg: String, val start: Long, val end: Long) {
    val minutes: Int get() = ((end - start) / 60_000L).toInt()
}

/** Today's scrolling, counted in code; [PetMind] turns it into words for Jev. */
data class ScrollDay(
    val minutesByApp: Map<String, Int> = emptyMap(),
    val opensLastHour: Int = 0,
    /** A session that ended in the last few minutes — the owner just came back from it. */
    val justLeft: AppSession? = null,
) {
    val totalMinutes: Int get() = minutesByApp.values.sum()
}

object ScrollUsage {
    private const val JOIN_GAP_MS = 20_000L
    private const val JUST_LEFT_MS = 3 * 60_000L
    private const val HOUR_MS = 3_600_000L

    /**
     * Foreground sessions of [packages]. A session ends when its app pauses or another app
     * comes forward; one still open at [now] runs to now. Gaps under 20 s (activity switches
     * inside the app) are joined.
     */
    fun sessions(events: List<UsageEvt>, packages: Set<String>, now: Long): List<AppSession> {
        val out = ArrayList<AppSession>()
        var openPkg: String? = null
        var openAt = 0L
        fun close(at: Long) {
            val pkg = openPkg ?: return
            val last = out.lastOrNull()
            if (last != null && last.pkg == pkg && openAt - last.end < JOIN_GAP_MS) {
                out[out.lastIndex] = last.copy(end = at)
            } else if (at > openAt) {
                out += AppSession(pkg, openAt, at)
            }
            openPkg = null
        }
        for (e in events.sortedBy { it.at }) {
            if (e.resumed) {
                if (openPkg == e.pkg) continue
                close(e.at)
                if (e.pkg in packages) {
                    openPkg = e.pkg
                    openAt = e.at
                }
            } else if (e.pkg == openPkg) {
                close(e.at)
            }
        }
        close(now)
        return out
    }

    /** Minutes of [sessions] inside [from, to). */
    fun minutesBetween(sessions: List<AppSession>, from: Long, to: Long): Map<String, Int> {
        val ms = HashMap<String, Long>()
        for (s in sessions) {
            val a = maxOf(s.start, from)
            val b = minOf(s.end, to)
            if (b > a) ms[s.pkg] = (ms[s.pkg] ?: 0L) + (b - a)
        }
        return ms.mapValues { (it.value / 60_000L).toInt() }.filterValues { it > 0 }
    }

    fun day(sessions: List<AppSession>, dayStart: Long, now: Long): ScrollDay = ScrollDay(
        minutesByApp = minutesBetween(sessions, dayStart, now),
        opensLastHour = sessions.count { it.start >= now - HOUR_MS },
        justLeft = sessions.lastOrNull()?.takeIf { now - it.end in 0..JUST_LEFT_MS && it.end < now },
    )

    /** Average endless minutes a day over the [days] full days before [todayStart]; 0 without history. */
    fun usualMinutes(sessions: List<AppSession>, todayStart: Long, days: Int = 7): Int {
        val totals = (1..days).map { k ->
            val from = todayStart - k * Ranking.DAY_MS
            minutesBetween(sessions, from, from + Ranking.DAY_MS).values.sum()
        }
        val known = totals.dropLastWhile { it == 0 }
        return if (known.isEmpty()) 0 else known.sum() / known.size
    }

    /** Resume/pause events since [from], from Android's usage stats. Empty without usage access. */
    fun read(context: Context, from: Long, now: Long): List<UsageEvt> = runCatching {
        val usm = context.getSystemService(UsageStatsManager::class.java)
        val events = usm.queryEvents(from, now)
        val event = UsageEvents.Event()
        val out = ArrayList<UsageEvt>()
        while (events.hasNextEvent()) {
            events.getNextEvent(event)
            val pkg = event.packageName ?: continue
            when (event.eventType) {
                UsageEvents.Event.ACTIVITY_RESUMED -> out += UsageEvt(pkg, true, event.timeStamp)
                UsageEvents.Event.ACTIVITY_PAUSED,
                UsageEvents.Event.ACTIVITY_STOPPED -> out += UsageEvt(pkg, false, event.timeStamp)
            }
        }
        out
    }.getOrDefault(emptyList())

    /** "X 47m · YouTube 12m" for the dog's collar, biggest first. */
    fun tag(minutesByApp: Map<String, Int>, label: (String) -> String): String =
        minutesByApp.entries
            .sortedByDescending { it.value }
            .joinToString(" · ") { (pkg, m) -> "${label(pkg)} ${short(m)}" }

    internal fun short(minutes: Int): String =
        if (minutes < 60) "${minutes}m" else "${minutes / 60}h${(minutes % 60).toString().padStart(2, '0')}"
}
