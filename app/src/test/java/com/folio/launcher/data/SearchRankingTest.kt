package com.folio.launcher.data

import android.graphics.Bitmap
import android.os.Process
import androidx.compose.ui.graphics.asImageBitmap
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** Search over app lists: what a slip finds and where it ranks, labels read once per list, and Jev's options. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class SearchRankingTest {
    private val noon = Ranking.startOfDay(1_700_000_000_000L) + 12 * 3_600_000L

    private fun app(label: String, pkg: String) = LaunchableApp(
        packageName = pkg,
        activityName = "$pkg.Main",
        user = Process.myUserHandle(),
        label = label,
        icon = Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888).asImageBitmap(),
    )

    @Test
    fun search_aSlipRanksBelowEveryOtherMatch_howeverOftenItsOpened() {
        val mars = app("Mars Explorer", "com.mars")
        val maple = app("Maple Planets", "com.maple")
        val maps = app("Google Maps", "com.google.android.apps.maps")
        val launches = mapOf(mars.packageName to List(20) { noon - it * 60_000L })
        // A word's start, then letters in order, then one letter wrong.
        assertEquals(listOf(maps, maple, mars), Ranking.search("maps", listOf(mars, maple, maps), launches, noon))
    }

    @Test
    fun search_findsTheAppATypoMeant() {
        val spotify = app("Spotify", "com.spotify.music")
        val whatsapp = app("WhatsApp", "com.whatsapp")
        val instagram = app("Instagram", "com.instagram.android")
        val apps = listOf(instagram, spotify, whatsapp)
        assertEquals(listOf(spotify), Ranking.search("spotfy", apps, emptyMap(), noon))
        assertEquals(listOf(spotify), Ranking.search("sptoify", apps, emptyMap(), noon))
        assertEquals(listOf(whatsapp), Ranking.search("whatsapo", apps, emptyMap(), noon))
        assertEquals(listOf(instagram), Ranking.search("instagarm", apps, emptyMap(), noon))
    }

    @Test
    fun search_readsEachNewAppListAfresh() {
        // Labels are folded once per list; another list (an app renamed or replaced) isn't read from the last one's.
        val before = listOf(app("Spotify", "com.spotify.music"))
        assertEquals(before, Ranking.search("spitify", before, emptyMap(), noon))
        val after = listOf(app("Deezer", "deezer.android.app"))
        assertEquals(emptyList<LaunchableApp>(), Ranking.search("spitify", after, emptyMap(), noon))
        assertEquals(after, Ranking.search("deez", after, emptyMap(), noon))
    }

    @Test
    fun jevsGate_overTheAppList_isTheGateOverItsLabels() {
        val apps = listOf(
            app("Spotify", "com.spotify.music"),
            app("Google Maps", "com.google.android.apps.maps"),
            app("Trainline", "com.thetrainline"),
            app("Pokémon GO", "com.nianticlabs.pokemongo"),
        )
        val labels = apps.map { it.label }
        for (q in listOf("tr", "spot", "maps", "trains", "weather tomorrow", "google m", "spitify", "pokemon", " Train ")) {
            assertEquals(q, SearchBrain.worthAsking(q, labels), SearchBrain.worthAsking(q, apps))
        }
    }

    @Test
    fun jevsOptions_stayMostUsedFirst_thenAToZ() {
        val zoom = app("Zoom", "us.zoom.videomeetings")
        val bank = app("Bank", "com.bank")
        val alpha = app("Alpha", "com.alpha")
        val beta = app("Beta", "com.beta")
        val launches = mapOf(
            zoom.packageName to List(5) { noon - it * Ranking.DAY_MS },
            bank.packageName to listOf(noon),
        )
        val options = SearchBrain.candidates(listOf(beta, bank, alpha, zoom), launches, noon)
        assertEquals(listOf(zoom, bank, alpha, beta), options.values.toList())
    }
}
