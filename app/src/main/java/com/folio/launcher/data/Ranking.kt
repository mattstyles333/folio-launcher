package com.folio.launcher.data

import java.time.LocalDate
import java.time.ZoneId

object Ranking {
    const val DAY_MS = 86_400_000L

    fun startOfDay(now: Long = System.currentTimeMillis()): Long {
        val zone = ZoneId.systemDefault()
        return LocalDate.ofInstant(java.time.Instant.ofEpochMilli(now), zone)
            .atStartOfDay(zone)
            .toInstant()
            .toEpochMilli()
    }

    fun score(timestamps: List<Long>, now: Long = System.currentTimeMillis()): Int {
        val d7 = now - 7 * DAY_MS
        val d30 = now - 30 * DAY_MS
        val today = startOfDay(now)
        var opens7 = 0
        var opens30 = 0
        var usedToday = false
        for (t in timestamps) {
            if (t >= d30) {
                opens30++
                if (t >= d7) opens7++
                if (t >= today) usedToday = true
            }
        }
        return opens7 * 4 + opens30 + if (usedToday) 8 else 0
    }

    fun isEligible(
        packageName: String,
        firstSeen: Map<String, Long>,
        launches: Map<String, List<Long>>,
        now: Long = System.currentTimeMillis(),
    ): Boolean {
        if (launches[packageName].orEmpty().isNotEmpty()) return true
        val seen = firstSeen[packageName] ?: return true
        if (seen == 0L) return true
        return now - seen >= DAY_MS
    }

    enum class MatchKind { Prefix, WordPrefix, Acronym, Fuzzy }

    fun match(query: String, label: String): MatchKind? {
        val q = query.trim().lowercase()
        if (q.isEmpty()) return null
        val l = label.lowercase()
        if (l.startsWith(q)) return MatchKind.Prefix
        val words = l.split(Regex("[\\s._\\-+/]+")).filter { it.isNotEmpty() }
        if (words.any { it.startsWith(q) }) return MatchKind.WordPrefix
        val acronym = words.map { it.first() }.joinToString("")
        if (acronym.startsWith(q)) return MatchKind.Acronym
        if (fuzzy(q, l)) return MatchKind.Fuzzy
        return null
    }

    private fun fuzzy(query: String, label: String): Boolean {
        var i = 0
        for (c in label) {
            if (i < query.length && c == query[i]) i++
        }
        return i == query.length
    }

    fun suggest(
        apps: List<LaunchableApp>,
        rail: List<LaunchableApp>,
        recents: List<LaunchableApp>,
        launches: Map<String, List<Long>>,
        limit: Int = 16,
        now: Long = System.currentTimeMillis(),
    ): List<LaunchableApp> {
        val seen = LinkedHashSet<String>()
        val out = ArrayList<LaunchableApp>(limit)
        fun take(app: LaunchableApp) {
            if (out.size >= limit) return
            if (seen.add(app.key)) out += app
        }
        rail.forEach(::take)
        recents.forEach(::take)
        apps.sortedWith(
            compareByDescending<LaunchableApp> { score(launches[it.packageName].orEmpty(), now) }
                .thenBy { it.label.lowercase() },
        ).forEach(::take)
        return out
    }

    fun search(
        query: String,
        apps: List<LaunchableApp>,
        launches: Map<String, List<Long>>,
        now: Long = System.currentTimeMillis(),
    ): List<LaunchableApp> {
        data class Hit(val app: LaunchableApp, val kind: MatchKind, val usage: Int)
        val hits = apps.mapNotNull { app ->
            val kind = match(query, app.label) ?: return@mapNotNull null
            Hit(app, kind, score(launches[app.packageName].orEmpty(), now))
        }
        return hits.sortedWith(
            compareBy<Hit> { it.kind.ordinal }
                .thenByDescending { it.usage }
                .thenBy { it.app.label.lowercase() },
        ).map { it.app }
    }

    fun rankForRail(
        apps: List<LaunchableApp>,
        launches: Map<String, List<Long>>,
        firstSeen: Map<String, Long>,
        defaults: List<LaunchableApp>,
        now: Long = System.currentTimeMillis(),
    ): List<LaunchableApp> {
        val eligible = apps.filter { isEligible(it.packageName, firstSeen, launches, now) }
        val scored = eligible.map { it to score(launches[it.packageName].orEmpty(), now) }
        val anyUsage = scored.any { it.second > 0 }
        return if (anyUsage) {
            scored.sortedWith(
                compareByDescending<Pair<LaunchableApp, Int>> { it.second }
                    .thenBy { it.first.label.lowercase() },
            ).map { it.first }
        } else {
            val defaultKeys = defaults.map { it.key }.toSet()
            defaults + eligible.filter { it.key !in defaultKeys }
                .sortedBy { it.label.lowercase() }
        }
    }

    fun drawer(
        apps: List<LaunchableApp>,
        railPackages: Set<String>,
        launches: Map<String, List<Long>>,
        now: Long = System.currentTimeMillis(),
    ): List<LaunchableApp> {
        val order = orderDrawer(
            labeled = apps.map { it.packageName to it.label },
            rail = railPackages,
            launches = launches,
            now = now,
        )
        val byPkg = apps.groupBy { it.packageName }
        return order.flatMap { pkg -> byPkg[pkg].orEmpty() }
    }

    fun orderDrawer(
        labeled: List<Pair<String, String>>,
        rail: Set<String>,
        launches: Map<String, List<Long>>,
        now: Long = System.currentTimeMillis(),
    ): List<String> {
        return labeled
            .filter { it.first !in rail }
            .distinctBy { it.first }
            .sortedWith(
                compareByDescending<Pair<String, String>> {
                    launches[it.first].orEmpty().maxOrNull() ?: 0L
                }.thenByDescending {
                    score(launches[it.first].orEmpty(), now)
                }.thenBy { it.second.lowercase() },
            )
            .map { it.first }
    }

    fun combinedLaunches(
        local: Map<String, List<Long>>,
        extra: Map<String, List<Long>>,
    ): Map<String, List<Long>> {
        if (extra.isEmpty()) return local
        val keys = local.keys + extra.keys
        return keys.associateWith { pkg ->
            val a = local[pkg].orEmpty()
            val b = extra[pkg].orEmpty()
            if (b.isEmpty()) a else (a + b).distinct().sorted()
        }
    }

    fun relativeTime(then: Long, now: Long = System.currentTimeMillis()): String {
        val d = (now - then).coerceAtLeast(0)
        val m = d / 60_000
        val h = d / 3_600_000
        val days = d / DAY_MS
        return when {
            m < 1 -> "now"
            m < 60 -> "${m}m"
            h < 24 -> "${h}h"
            days < 7 -> "${days}d"
            else -> "${days / 7}w"
        }
    }
}
