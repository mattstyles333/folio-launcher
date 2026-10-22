package com.folio.launcher.data

import java.lang.ref.WeakReference
import java.text.Collator
import java.text.Normalizer
import java.time.ZoneId

object Ranking {
    const val DAY_MS = 86_400_000L

    /** How far back an open counts: for ranking, and for how long UsageStore keeps it. */
    const val USAGE_WINDOW_MS = 30 * DAY_MS

    fun startOfDay(now: Long = System.currentTimeMillis()): Long {
        val zone = ZoneId.systemDefault()
        // LocalDate.ofInstant is API 34; minSdk is 31.
        return java.time.Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
            .atStartOfDay(zone)
            .toInstant()
            .toEpochMilli()
    }

    fun score(timestamps: List<Long>, now: Long = System.currentTimeMillis()): Int =
        score(timestamps, now, startOfDay(now))

    /** [score] with the start of [now]'s day worked out once for a whole ranking, not once per app. */
    internal fun score(timestamps: List<Long>, now: Long, today: Long): Int {
        val d7 = now - 7 * DAY_MS
        val d30 = now - USAGE_WINDOW_MS
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
        return opens7 * 4 + opens30 + if (usedToday) 2 else 0
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

    /** Best first. [Typo] is the last resort: a match with a slip never ranks above one without. */
    enum class MatchKind { Prefix, WordPrefix, Acronym, Fuzzy, Typo }

    /** The shortest query a slip is forgiven in: in three letters, one wrong changes too much. */
    private const val TYPO_MIN = 4

    fun match(query: String, label: String): MatchKind? = match(fold(query.trim()), Folded(label))

    /** [query] already folded. */
    private fun match(query: String, label: Folded): MatchKind? {
        if (query.isEmpty()) return null
        if (label.text.startsWith(query)) return MatchKind.Prefix
        if (label.words.any { it.startsWith(query) }) return MatchKind.WordPrefix
        if (label.acronym.startsWith(query)) return MatchKind.Acronym
        if (fuzzy(query, label.text)) return MatchKind.Fuzzy
        if (query.length >= TYPO_MIN && label.oneSlipFrom(query)) return MatchKind.Typo
        return null
    }

    /** [query] (as typed) is an app's name, or the start of one of its words, for one of [apps]: what never needs Jev. */
    internal fun namesAnApp(query: String, apps: List<LaunchableApp>): Boolean {
        val q = fold(query.trim())
        return q.isNotEmpty() && folded(apps).any { label ->
            label.text.startsWith(q) || label.words.any { it.startsWith(q) }
        }
    }

    private fun fuzzy(query: String, label: String): Boolean {
        var i = 0
        for (c in label) {
            if (i < query.length && c == query[i]) i++
        }
        return i == query.length
    }

    /**
     * [query] is one slip from the start of [text]: a letter wrong, missing or extra, or two
     * letters swapped ("spitify", "sptoify", "spotiffy" all start "spotify"). Looked for where
     * the two first differ: a slip further on can always be moved back to there.
     */
    private fun slipFromStart(query: String, text: String): Boolean {
        val n = query.length
        val m = text.length
        var i = 0
        while (i < n && i < m && query[i] == text[i]) i++
        if (i == n) return true
        if (m >= n) {
            // A wrong letter: the rest lines up.
            if (text.regionMatches(i + 1, query, i + 1, n - i - 1)) return true
            // Two letters swapped.
            if (i + 1 < n && query[i] == text[i + 1] && query[i + 1] == text[i] &&
                text.regionMatches(i + 2, query, i + 2, n - i - 2)
            ) {
                return true
            }
        }
        // A letter too many in the query, or one missing from it.
        if (m >= n - 1 && text.regionMatches(i, query, i + 1, n - i - 1)) return true
        return m >= n + 1 && text.regionMatches(i + 1, query, i, n - i)
    }

    private val MARKS = Regex("\\p{Mn}+")

    private val WORD_SPLIT = Regex("[\\s._\\-+/]+")

    /** A label folded ([fold]) and split into words once: what each keystroke's query is compared with. */
    private class Folded(label: String) {
        val text = fold(label)
        val words = text.split(WORD_SPLIT).filter { it.isNotEmpty() }
        val acronym = words.map { it.first() }.joinToString("")

        /** One slip from the start of the label, or of one of its words. */
        fun oneSlipFrom(query: String): Boolean =
            slipFromStart(query, text) || words.any { it != text && slipFromStart(query, it) }
    }

    /** An app list's labels, folded. The list is held weakly: an old one (and its icons) isn't kept alive for this. */
    private class FoldedApps(apps: List<LaunchableApp>) {
        val list = WeakReference(apps)
        val labels: List<Folded> = apps.map { Folded(it.label) }
    }

    @Volatile
    private var lastFolded: FoldedApps? = null

    /** [apps]' labels folded once per app list (a published list never changes), not on every keystroke. */
    private fun folded(apps: List<LaunchableApp>): List<Folded> {
        lastFolded?.let { if (it.list.get() === apps && it.labels.size == apps.size) return it.labels }
        return FoldedApps(apps).also { lastFolded = it }.labels
    }

    /** Folds [apps]' labels now, off the main thread, so search's first keystroke doesn't have to. */
    fun prepareSearch(apps: List<LaunchableApp>) {
        folded(apps)
    }

    /** Lowercase without accents, so "pokemon" finds "Pokémon GO"; NFC keeps Hangul syllables whole. */
    private fun fold(s: String): String =
        Normalizer.normalize(
            Normalizer.normalize(s, Normalizer.Form.NFD).replace(MARKS, ""),
            Normalizer.Form.NFC,
        ).lowercase()

    /**
     * [item]'s opens summed up once for a sort, not on every comparison: its [score], and its
     * latest open inside the 30-day window ([last]; an older one doesn't lift an app out of the A–Z tail).
     */
    private class Used<T>(val item: T, val label: String, val score: Int, val last: Long)

    private fun <T> used(item: T, label: String, timestamps: List<Long>, now: Long, today: Long): Used<T> {
        val d30 = now - USAGE_WINDOW_MS
        var last = 0L
        for (t in timestamps) if (t >= d30 && t > last) last = t
        return Used(item, label, score(timestamps, now, today), last)
    }

    /** Most-used first (habit score, then the latest open), then A–Z in the phone's language. */
    private fun <T> mostUsedFirst(items: List<Used<T>>): List<T> {
        val byLabel = labelOrder()
        return items.sortedWith(
            Comparator { a, b ->
                when {
                    a.score != b.score -> b.score.compareTo(a.score)
                    a.last != b.last -> b.last.compareTo(a.last)
                    else -> byLabel.compare(a.label, b.label)
                }
            },
        ).map { it.item }
    }

    /**
     * A–Z in the phone's language: "Ärzte" before "Bahn", not after "Zoom". A new collator for
     * each sort, since one isn't thread-safe and the language can change.
     */
    fun labelOrder(): Comparator<String> {
        val collator = Collator.getInstance().apply { strength = Collator.SECONDARY }
        return Comparator<String> { a, b -> collator.compare(a, b) }
    }

    fun suggest(
        apps: List<LaunchableApp>,
        rail: List<LaunchableApp>,
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
        val today = startOfDay(now)
        mostUsedFirst(apps.map { used(it, it.label, launches[it.packageName].orEmpty(), now, today) })
            .forEach(::take)
        return out
    }

    fun search(
        query: String,
        apps: List<LaunchableApp>,
        launches: Map<String, List<Long>>,
        now: Long = System.currentTimeMillis(),
    ): List<LaunchableApp> {
        data class Hit(val app: LaunchableApp, val kind: MatchKind, val usage: Int)
        val q = fold(query.trim())
        if (q.isEmpty()) return emptyList()
        val labels = folded(apps)
        val today = startOfDay(now)
        val hits = apps.indices.mapNotNull { i ->
            val app = apps[i]
            val kind = match(q, labels[i]) ?: return@mapNotNull null
            Hit(app, kind, score(launches[app.packageName].orEmpty(), now, today))
        }
        return hits.sortedWith(
            compareBy<Hit> { it.kind.ordinal }
                .thenByDescending { it.usage }
                .thenBy(labelOrder()) { it.app.label },
        ).map { it.app }
    }

    fun rankForRail(
        apps: List<LaunchableApp>,
        launches: Map<String, List<Long>>,
        firstSeen: Map<String, Long>,
        defaults: List<LaunchableApp>,
        hidden: Set<String> = emptySet(),
        now: Long = System.currentTimeMillis(),
    ): List<LaunchableApp> {
        val eligible = apps.filter {
            !it.isHome &&
                it.packageName !in hidden &&
                isEligible(it.packageName, firstSeen, launches, now)
        }
        val today = startOfDay(now)
        val scored = eligible.map { it to score(launches[it.packageName].orEmpty(), now, today) }
        val anyUsage = scored.any { it.second > 0 }
        return if (anyUsage) {
            scored.sortedWith(
                compareByDescending<Pair<LaunchableApp, Int>> { it.second }
                    .thenBy(labelOrder()) { it.first.label },
            ).map { it.first }
        } else {
            // Defaults are picked from every app: a hidden one or another home screen stays off.
            val shown = defaults.filter { !it.isHome && it.packageName !in hidden }
            val defaultKeys = shown.map { it.key }.toSet()
            shown + eligible.filter { it.key !in defaultKeys }
                .sortedWith(compareBy(labelOrder()) { it.label })
        }
    }

    fun drawer(
        apps: List<LaunchableApp>,
        railPackages: Set<String>,
        launches: Map<String, List<Long>>,
        hidden: Set<String> = emptySet(),
        now: Long = System.currentTimeMillis(),
    ): List<LaunchableApp> {
        val visible = apps.filter { !it.isHome && it.packageName !in hidden }
        val order = orderDrawer(
            labeled = visible.map { it.packageName to it.label },
            rail = railPackages,
            launches = launches,
            now = now,
            hide = hidden,
        )
        val byPkg = visible.groupBy { it.packageName }
        return order.flatMap { pkg -> byPkg[pkg].orEmpty() }
    }

    fun orderDrawer(
        labeled: List<Pair<String, String>>,
        rail: Set<String>,
        launches: Map<String, List<Long>>,
        now: Long = System.currentTimeMillis(),
        hide: Set<String> = emptySet(),
    ): List<String> {
        val today = startOfDay(now)
        return mostUsedFirst(
            labeled
                .filter { it.first !in rail && it.first !in hide }
                .distinctBy { it.first }
                .map { (pkg, label) -> used(pkg, label, launches[pkg].orEmpty(), now, today) },
        )
    }

    /**
     * Opens per package. With usage access the system's opens are the record: read at [readAt]
     * (wall clock), they already hold every launch from Folio before then, so adding Folio's own
     * stamps counts those twice. Folio's stamps still fill in where the system has nothing:
     * packages it never logged, the days before its oldest event (Android keeps only a week or
     * so), and launches since [readAt] (the system is re-read at most once a minute).
     */
    fun combinedLaunches(
        local: Map<String, List<Long>>,
        extra: Map<String, List<Long>>,
        readAt: Long,
    ): Map<String, List<Long>> {
        if (extra.isEmpty()) return local
        val since = extra.values.minOf { it.minOrNull() ?: Long.MAX_VALUE }
        val keys = local.keys + extra.keys
        return keys.associateWith { pkg ->
            val a = local[pkg].orEmpty()
            val b = extra[pkg].orEmpty()
            if (b.isEmpty()) a else (a.filter { it < since || it > readAt } + b).sorted()
        }
    }

    const val RAIL_SLOTS = 4

    /** Pads or trims saved slots to exactly [RAIL_SLOTS]. */
    fun railSlots(saved: List<SlotPref>): List<SlotPref> =
        (saved + List(RAIL_SLOTS) { SlotPref() }).take(RAIL_SLOTS)

    /**
     * Pins that are still installed stay in their slot; every other slot takes the next
     * best-ranked app not already on the rail. [resolveKey] maps a slot to an installed
     * app's key, or null if it's gone.
     */
    fun fillRail(
        current: List<SlotPref>,
        ranked: List<SlotPref>,
        resolveKey: (SlotPref) -> String?,
    ): List<SlotPref> {
        val slots = railSlots(current)
        val used = HashSet<String>()
        val next = MutableList(RAIL_SLOTS) { SlotPref() }
        for (i in slots.indices) {
            val slot = slots[i]
            if (!slot.pinned || slot.packageName == null) continue
            val key = resolveKey(slot) ?: continue
            next[i] = slot
            used += key
        }
        var idx = 0
        for (i in next.indices) {
            if (next[i].pinned) continue
            while (idx < ranked.size && slotKey(ranked[idx]) in used) idx++
            val pick = ranked.getOrNull(idx) ?: continue
            next[i] = pick.copy(pinned = false)
            used += slotKey(pick)
            idx++
        }
        return next
    }

    private fun slotKey(slot: SlotPref) = "${slot.packageName}/${slot.activityName}"
}
