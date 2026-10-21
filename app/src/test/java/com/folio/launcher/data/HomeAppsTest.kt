package com.folio.launcher.data

import android.graphics.Bitmap
import android.os.Process
import androidx.compose.ui.graphics.asImageBitmap
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** Other home screens and hidden apps stay out of the grid and the rail; search still finds them. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class HomeAppsTest {
    private val noon = Ranking.startOfDay(1_700_000_000_000L) + 12 * 3_600_000L

    private fun app(label: String, pkg: String, isHome: Boolean = false) = LaunchableApp(
        packageName = pkg,
        activityName = "$pkg.Main",
        user = Process.myUserHandle(),
        label = label,
        icon = Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888).asImageBitmap(),
        isHome = isHome,
    )

    private val chat = app("Chat", "com.chat")
    private val chrome = app("Chrome", "com.android.chrome")
    private val maps = app("Maps", "com.maps")
    private val oneUi = app("One UI Home", "com.sec.android.app.launcher", isHome = true)
    private val phone = app("Phone", "com.phone")
    private val apps = listOf(chat, chrome, maps, oneUi, phone)

    /** [n] opens in the last few minutes. */
    private fun opens(n: Int) = List(n) { noon - it * 60_000L }

    @Test
    fun homeSet_leavesOutSettingsFallbackHomeAndFolio() {
        val found = listOf(
            "com.android.settings" to -1000,
            "com.sec.android.app.launcher" to 0,
            "com.folio.launcher" to 0,
        )
        assertEquals(
            setOf("com.sec.android.app.launcher"),
            HomeApps.homeSet(found, settingsPackage = null, ownPackage = "com.folio.launcher"),
        )
        // The Settings app is never a home screen, whatever priority its filter has.
        assertEquals(
            setOf("com.sec.android.app.launcher"),
            HomeApps.homeSet(
                listOf("com.android.settings" to 0, "com.sec.android.app.launcher" to 0),
                settingsPackage = "com.android.settings",
                ownPackage = "com.folio.launcher",
            ),
        )
    }

    @Test
    fun drawer_dropsOtherHomesAndHiddenAppsEvenWhenMostUsed() {
        val launches = mapOf(
            oneUi.packageName to opens(9),
            chrome.packageName to opens(5),
            chat.packageName to opens(1),
        )
        val drawer = Ranking.drawer(
            apps,
            railPackages = emptySet(),
            launches,
            hidden = setOf(chrome.packageName),
            now = noon,
        )
        assertEquals(listOf(chat, maps, phone), drawer)
    }

    @Test
    fun rankForRail_dropsOtherHomesAndHiddenApps() {
        val launches = mapOf(
            oneUi.packageName to opens(9),
            chrome.packageName to opens(5),
            maps.packageName to opens(1),
        )
        val ranked = Ranking.rankForRail(
            apps,
            launches,
            firstSeen = emptyMap(),
            defaults = emptyList(),
            hidden = setOf(chrome.packageName),
            now = noon,
        )
        assertEquals(listOf(maps, chat, phone), ranked)
    }

    @Test
    fun rankForRail_withoutUsage_skipsHiddenDefaults() {
        // Fresh install, nothing opened yet: Chrome was swiped off the default rail.
        val ranked = Ranking.rankForRail(
            apps,
            launches = emptyMap(),
            firstSeen = emptyMap(),
            defaults = listOf(phone, chat, chrome, oneUi),
            hidden = setOf(chrome.packageName),
            now = noon,
        )
        assertEquals(listOf(phone, chat, maps), ranked)
    }

    @Test
    fun search_stillFindsOtherHomesAndHiddenApps() {
        assertEquals(listOf(oneUi), Ranking.search("one ui", apps, emptyMap(), noon))
        assertEquals(listOf(chrome), Ranking.search("chrome", apps, emptyMap(), noon))
    }

    @Test
    fun jevOptions_leaveOutOtherHomes() {
        val options = SearchBrain.candidates(apps, emptyMap(), noon)
        assertEquals(listOf(chat, chrome, maps, phone), options.values.toList())
    }
}
