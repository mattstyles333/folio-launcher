package com.folio.launcher.data

import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RankingTest {
    private val noon = Ranking.startOfDay(1_700_000_000_000L) + 12 * 3_600_000L

    @Test
    fun score_weightsRecentOpens() {
        val today = noon
        val sixDays = noon - 6 * Ranking.DAY_MS
        val twentyDays = noon - 20 * Ranking.DAY_MS
        val fortyDays = noon - 40 * Ranking.DAY_MS
        val score = Ranking.score(listOf(today, sixDays, twentyDays, fortyDays), noon)
        // today + 6d count as 7d and 30d; 20d is 30d only; 40d ignored
        // opens7=2 *4 =8, opens30=3, usedToday=2 → 13
        assertEquals(13, score)
    }

    @Test
    fun match_prefix_word_acronym_fuzzy() {
        assertEquals(Ranking.MatchKind.Prefix, Ranking.match("gma", "Gmail"))
        assertEquals(Ranking.MatchKind.Prefix, Ranking.match("gm", "Gmail"))
        assertEquals(Ranking.MatchKind.WordPrefix, Ranking.match("map", "Google Maps"))
        assertEquals(Ranking.MatchKind.Acronym, Ranking.match("gm", "Google Maps"))
        assertEquals(Ranking.MatchKind.Fuzzy, Ranking.match("gml", "Gmail"))
        assertNull(Ranking.match("zzz", "Gmail"))
        assertNull(Ranking.match("", "Gmail"))
    }

    @Test
    fun match_ignoresAccents() {
        assertEquals(Ranking.MatchKind.Prefix, Ranking.match("poke", "Pokémon GO"))
        assertEquals(Ranking.MatchKind.Prefix, Ranking.match("pokemon go", "Pokémon GO"))
        assertEquals(Ranking.MatchKind.Prefix, Ranking.match("telephone", "Téléphone"))
        assertEquals(Ranking.MatchKind.Prefix, Ranking.match("Pokémon", "Pokemon TCG Pocket"))
        // An app's name, so it never goes to Jev.
        assertFalse(SearchBrain.worthAsking("pokemon", listOf("Pokémon GO")))
        // Hangul stays whole syllables: 칵 is not hidden in 카카오톡's letters.
        assertEquals(Ranking.MatchKind.Prefix, Ranking.match("카카", "카카오톡"))
        assertNull(Ranking.match("칵", "카카오톡"))
    }

    @Test
    fun eligible_after_first_launch_or_24h() {
        val now = noon
        val launches = mapOf("com.used" to listOf(now))
        val firstSeen = mapOf(
            "com.new" to now,
            "com.old" to now - Ranking.DAY_MS,
            "com.preexisting" to 0L,
        )
        assertTrue(Ranking.isEligible("com.used", firstSeen, launches, now))
        assertFalse(Ranking.isEligible("com.new", firstSeen, launches, now))
        assertTrue(Ranking.isEligible("com.old", firstSeen, launches, now))
        assertTrue(Ranking.isEligible("com.preexisting", firstSeen, launches, now))
        assertTrue(Ranking.isEligible("com.unknown", firstSeen, launches, now))
    }

    @Test
    fun drawer_recentAndMostUsedFirst() {
        val now = noon
        val launches = mapOf(
            "com.chat" to listOf(now - 1_000),
            "com.maps" to listOf(now - 50_000),
        )
        val labeled = listOf(
            "com.alpha" to "Alpha",
            "com.chat" to "Chat",
            "com.maps" to "Maps",
            "com.rail" to "Phone",
            "com.zeta" to "Zeta",
            "com.beta" to "Beta",
        )
        val order = Ranking.orderDrawer(labeled, rail = setOf("com.rail"), launches, now)
        assertEquals(listOf("com.chat", "com.maps", "com.alpha", "com.beta", "com.zeta"), order)
    }

    @Test
    fun drawer_habitBeatsOneOffToday() {
        val now = noon
        val week = List(5) { i -> now - (i + 1) * Ranking.DAY_MS }
        val launches = mapOf(
            "com.maps" to week,
            "com.bank" to listOf(now),
        )
        val labeled = listOf(
            "com.bank" to "Bank",
            "com.maps" to "Maps",
        )
        val order = Ranking.orderDrawer(labeled, rail = emptySet(), launches, now)
        assertEquals(listOf("com.maps", "com.bank"), order)
        assertTrue(
            Ranking.score(week, now) > Ranking.score(listOf(now), now),
        )
    }

    @Test
    fun drawer_oldOpensStayInTheAToZTail() {
        val now = noon
        val launches = mapOf("com.zulu" to listOf(now - 40 * Ranking.DAY_MS))
        val labeled = listOf(
            "com.zulu" to "Zulu",
            "com.alpha" to "Alpha",
            "com.beta" to "Beta",
        )
        val order = Ranking.orderDrawer(labeled, rail = emptySet(), launches, now)
        assertEquals(listOf("com.alpha", "com.beta", "com.zulu"), order)
    }

    @Test
    fun drawer_aToZFollowsThePhonesLanguage() {
        val before = Locale.getDefault()
        Locale.setDefault(Locale.GERMAN)
        try {
            val labeled = listOf(
                "com.zoom" to "Zoom",
                "com.aerzte" to "Ärzte",
                "com.bahn" to "Bahn",
            )
            val order = Ranking.orderDrawer(labeled, rail = emptySet(), launches = emptyMap(), now = noon)
            assertEquals(listOf("com.aerzte", "com.bahn", "com.zoom"), order)
        } finally {
            Locale.setDefault(before)
        }
    }

    @Test
    fun homeApps_detectsOneUiAndNova() {
        assertTrue(HomeApps.packageLooksLikeLauncher("com.sec.android.app.launcher"))
        assertTrue(HomeApps.packageLooksLikeLauncher("com.teslacoilsw.launcher"))
        assertTrue(HomeApps.packageLooksLikeLauncher("bitpit.launcher"))
        assertFalse(HomeApps.packageLooksLikeLauncher("com.spotify.music"))
        assertFalse(HomeApps.packageLooksLikeLauncher("com.android.chrome"))
        assertTrue(HomeApps.isHome("com.sec.android.app.launcher", emptySet()))
        assertTrue(HomeApps.isHome("com.other", setOf("com.other")))
        assertFalse(HomeApps.isHome("com.spotify.music", emptySet()))
    }

    @Test
    fun drawer_hidesOtherHomes() {
        val now = noon
        val launches = mapOf("com.sec.android.app.launcher" to listOf(now))
        val labeled = listOf(
            "com.chat" to "Chat",
            "com.sec.android.app.launcher" to "One UI Home",
        )
        val order = Ranking.orderDrawer(
            labeled,
            rail = emptySet(),
            launches = launches,
            now = now,
            hide = setOf("com.sec.android.app.launcher"),
        )
        assertEquals(listOf("com.chat"), order)
    }

    @Test
    fun drawer_hidesHiddenEvenWhenMostUsed() {
        val now = noon
        val apps = listOf(
            "com.chat" to "Chat",
            "com.hidden" to "Hidden",
            "com.maps" to "Maps",
        )
        val order = Ranking.orderDrawer(
            labeled = apps,
            rail = emptySet(),
            launches = mapOf("com.hidden" to listOf(now), "com.chat" to listOf(now - 1)),
            now = now,
            hide = setOf("com.hidden"),
        )
        assertEquals(listOf("com.chat", "com.maps"), order)
    }

    @Test
    fun combinedLaunches_systemOpensAreTheRecord() {
        val day = Ranking.DAY_MS
        val local = mapOf(
            // Launched from Folio 20 days ago and yesterday.
            "a" to listOf(noon - 20 * day, noon - day),
            "c" to listOf(noon - 2 * day),
        )
        val extra = mapOf(
            // The system logged yesterday's launch as the app resumed; it keeps nine days.
            "a" to listOf(noon - day + 300),
            "b" to listOf(noon - 9 * day),
        )
        val merged = Ranking.combinedLaunches(local, extra, readAt = noon)
        // Yesterday counts once; the 20-day launch is older than anything the system kept.
        assertEquals(listOf(noon - 20 * day, noon - day + 300), merged["a"])
        assertEquals(listOf(noon - 9 * day), merged["b"])
        // Nothing from the system for c: Folio's own launches still count.
        assertEquals(listOf(noon - 2 * day), merged["c"])
        // No usage access: only launches from Folio.
        assertEquals(local, Ranking.combinedLaunches(local, emptyMap(), readAt = 0L))
    }

    @Test
    fun combinedLaunches_aLaunchSinceTheLastReadCountsOnce() {
        val day = Ranking.DAY_MS
        val hour = 3_600_000L
        // Read at noon: the system had an open nine days ago and Folio's launch an hour ago.
        val extra = mapOf("w" to listOf(noon - 9 * day, noon - hour + 300))
        // Folio launched it an hour ago, and again 20 s after the read; the next read is a minute away.
        val local = mapOf("w" to listOf(noon - hour, noon + 20_000))
        assertEquals(
            listOf(noon - 9 * day, noon - hour + 300, noon + 20_000),
            Ranking.combinedLaunches(local, extra, readAt = noon)["w"],
        )
        // The next read has that launch too (the app resumed 300 ms later): still once.
        val reread = mapOf("w" to listOf(noon - 9 * day, noon - hour + 300, noon + 20_300))
        assertEquals(reread["w"], Ranking.combinedLaunches(local, reread, readAt = noon + 61_000)["w"])
    }

    @Test
    fun defaultApps_preferThePhonesDefault() {
        val known = listOf("com.google.android.apps.messaging", "com.samsung.android.messaging")
        val installed = known.toSet()
        // Samsung Messages is the default SMS app: it, not the first known one.
        assertEquals(
            "com.samsung.android.messaging",
            DefaultApps.choose(installed, "com.samsung.android.messaging", known),
        )
        // No default (the system chooser, "android") or nothing resolved: the first known one.
        assertEquals("com.google.android.apps.messaging", DefaultApps.choose(installed, "android", known))
        assertEquals("com.google.android.apps.messaging", DefaultApps.choose(installed, null, known))
        assertNull(DefaultApps.choose(emptySet(), "com.samsung.android.messaging", known))
    }

    private fun slot(pkg: String, pinned: Boolean = false) = SlotPref(pkg, "$pkg.Main", pinned)

    @Test
    fun fillRail_keepsPinsInPlaceAndFillsTheRestByRank() {
        val installed = setOf("a", "b", "c", "d", "e", "pin")
        val next = Ranking.fillRail(
            current = listOf(SlotPref(), slot("pin", pinned = true), slot("old"), SlotPref()),
            ranked = listOf("pin", "a", "b", "c").map { slot(it) },
            resolveKey = { s -> s.packageName?.takeIf { it in installed }?.let { "$it/$it.Main" } },
        )
        assertEquals(listOf("a", "pin", "b", "c"), next.map { it.packageName })
        assertEquals(listOf(false, true, false, false), next.map { it.pinned })
    }

    @Test
    fun fillRail_dropsUninstalledPins() {
        val next = Ranking.fillRail(
            current = listOf(slot("gone", pinned = true)),
            ranked = listOf(slot("a")),
            resolveKey = { s -> s.packageName?.takeIf { it != "gone" }?.let { "$it/$it.Main" } },
        )
        assertEquals(listOf("a", null, null, null), next.map { it.packageName })
        assertFalse(next.any { it.pinned })
    }

    @Test
    fun railSlots_alwaysFour() {
        assertEquals(4, Ranking.railSlots(emptyList()).size)
        assertEquals(4, Ranking.railSlots(List(6) { slot("x$it") }).size)
    }
}
