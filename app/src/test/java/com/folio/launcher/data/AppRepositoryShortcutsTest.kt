package com.folio.launcher.data

import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Intent
import android.content.pm.LauncherApps
import android.content.pm.ShortcutInfo
import android.graphics.Bitmap
import android.graphics.Rect
import android.graphics.drawable.Icon
import android.os.Bundle
import android.os.Looper
import android.os.Process
import android.os.UserHandle
import androidx.compose.ui.graphics.asImageBitmap
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.folio.launcher.FolioApp
import com.folio.launcher.HomeViewModel
import java.io.File
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.shadows.ShadowLauncherApps
import org.robolectric.util.ReflectionHelpers
import org.robolectric.util.ReflectionHelpers.ClassParameter

/**
 * What a long-press menu reads from the system, and what a tap on a shortcut does. Only Home may read
 * shortcuts; a role lost mid-call, or a shortcut the system won't start, costs a row, never a crash.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], shadows = [AppRepositoryShortcutsTest.HomeLauncherApps::class])
class AppRepositoryShortcutsTest {
    private val app: FolioApp = ApplicationProvider.getApplicationContext()

    private val maps = LaunchableApp(
        packageName = "com.example.maps",
        activityName = "com.example.maps.Main",
        user = Process.myUserHandle(),
        label = "Maps",
        icon = Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888).asImageBitmap(),
    )

    @Before
    fun forgetShortcuts() = HomeLauncherApps.clear()

    /** Whether Folio holds the Home role, as LauncherApps answers it. */
    private fun home(isHome: Boolean) =
        shadowOf(app.getSystemService(LauncherApps::class.java)).setHasShortcutHostPermission(isHome)

    /** One of Maps' shortcuts as the system hands it to Home; [flags] say manifest, dynamic or disabled. */
    private fun published(id: String, rank: Int, flags: Int, activity: String = maps.activityName): ShortcutInfo =
        ShortcutInfo.Builder(app, id)
            .setShortLabel(id)
            .setLongLabel("Go $id")
            .setActivity(ComponentName(maps.packageName, activity))
            .setRank(rank)
            .setIcon(Icon.createWithBitmap(Bitmap.createBitmap(96, 96, Bitmap.Config.ARGB_8888)))
            .setIntent(Intent(Intent.ACTION_VIEW))
            .build()
            // The system sets these as it publishes; ShortcutInfo.addFlags is hidden.
            .also {
                ReflectionHelpers.callInstanceMethod<Any?>(
                    it,
                    "addFlags",
                    ClassParameter.from(Int::class.javaPrimitiveType!!, flags),
                )
            }

    @Test
    fun notHome_noShortcuts_andTheSystemIsNeverAsked() {
        HomeLauncherApps.published[maps.packageName] = listOf(published("home", rank = 0, flags = FLAG_MANIFEST))
        home(false)
        assertEquals(emptyList<AppShortcut>(), AppRepository(app).shortcuts(maps))
        assertEquals(0, HomeLauncherApps.asked)
    }

    @Test
    fun home_theIconsOwnShortcuts_manifestFirstByRank_fourAtMost() {
        HomeLauncherApps.published[maps.packageName] = listOf(
            published("recent", rank = 0, flags = FLAG_DYNAMIC),
            published("work", rank = 1, flags = FLAG_MANIFEST),
            published("off", rank = 0, flags = FLAG_MANIFEST or FLAG_DISABLED),
            published("home", rank = 0, flags = FLAG_MANIFEST),
            published("saved", rank = 1, flags = FLAG_DYNAMIC),
            published("later", rank = 2, flags = FLAG_DYNAMIC),
            // Another of Maps' launcher icons: its shortcuts are its own.
            published("transit", rank = 0, flags = FLAG_MANIFEST, activity = "com.example.maps.Transit"),
        )
        home(true)
        val menu = AppRepository(app).shortcuts(maps)
        assertEquals(listOf("home", "work", "recent", "saved"), menu.map { it.id })
        // Asked once, for what the app declares and what it publishes; nothing pinned or cached.
        assertEquals(1, HomeLauncherApps.asked)
        assertEquals(
            LauncherApps.ShortcutQuery.FLAG_MATCH_MANIFEST or LauncherApps.ShortcutQuery.FLAG_MATCH_DYNAMIC,
            HomeLauncherApps.flags,
        )
        val first = menu.first()
        assertEquals("home", first.label)
        assertEquals("Go home", first.longLabel)
        assertEquals(maps.packageName, first.packageName)
        assertEquals(maps.user, first.user)
        // Drawn for the menu's row, not at the app's 96 px.
        val px = (AppShortcuts.ICON_DP * app.resources.displayMetrics.density).toInt()
        assertEquals(px, first.icon?.width)
        assertEquals(px, first.icon?.height)
    }

    @Test
    fun aQueryTheSystemRefuses_givesNone() {
        HomeLauncherApps.published[maps.packageName] = listOf(published("home", rank = 0, flags = FLAG_MANIFEST))
        home(true)
        // Folio stopped being Home between the check and the call, or the user is locked.
        HomeLauncherApps.refuse = SecurityException("not the default launcher")
        assertEquals(emptyList<AppShortcut>(), AppRepository(app).shortcuts(maps))
        HomeLauncherApps.refuse = IllegalStateException("user locked")
        assertEquals(emptyList<AppShortcut>(), AppRepository(app).shortcuts(maps))
    }

    @Test
    fun aStartedShortcut_isAnOpenOfItsApp_oneTheSystemWontStartIsNot() {
        val refused = AppShortcut("com.example.refused", "gone", "Gone", Process.myUserHandle())
        val started = AppShortcut("com.example.started", "work", "Work", Process.myUserHandle())
        HomeLauncherApps.wontStart[refused.id] = ActivityNotFoundException("shortcut removed")
        val store = ViewModelStore()
        try {
            val vm = homeViewModel(store)
            assertFalse(vm.startShortcut(refused))
            assertTrue(vm.startShortcut(started))
            assertEquals(listOf("com.example.started/work"), HomeLauncherApps.started)
            // Counted for ranking like a tap on its icon; the one that never started isn't.
            waitFor("the open of ${started.packageName}") { started.packageName in launches() }
            assertFalse(refused.packageName in launches())
        } finally {
            store.clear()
        }
    }

    private fun launches(): Map<String, List<Long>> = runBlocking { app.usageStore.data.first() }.launches

    /** Lets main-thread work run (a usage write resumes there) until [done], for up to five seconds. */
    private fun waitFor(what: String, done: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 5_000
        while (!done()) {
            check(System.currentTimeMillis() < deadline) { "timed out waiting for $what" }
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(10)
        }
    }

    /**
     * A HomeViewModel as MainActivity's `by viewModels()` builds it, in a store the test clears. A
     * print is already on disk (prefs say it isn't a Bing one), so it never fetches one from Bing.
     */
    private fun homeViewModel(store: ViewModelStore): HomeViewModel {
        File(app.filesDir, "wallpaper.jpg").writeBytes(byteArrayOf(1))
        return ViewModelProvider(
            store,
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T =
                    HomeViewModel(app, SavedStateHandle()) as T
            },
        )[HomeViewModel::class.java]
    }

    /**
     * LauncherApps as Folio sees it while it's Home. Robolectric's own shadow refuses a manifest and
     * dynamic query and can't start a shortcut: this one answers both, or refuses as the system does.
     */
    @Implements(LauncherApps::class)
    class HomeLauncherApps : ShadowLauncherApps() {
        @Implementation
        override fun getShortcuts(query: LauncherApps.ShortcutQuery, user: UserHandle): List<ShortcutInfo>? {
            asked++
            flags = ReflectionHelpers.getField(query, "mQueryFlags")
            refuse?.let { throw it }
            val pkg: String? = ReflectionHelpers.getField(query, "mPackage")
            val activity: ComponentName? = ReflectionHelpers.getField(query, "mActivity")
            return pkg?.let { published[it] }.orEmpty().filter { activity == null || it.activity == activity }
        }

        @Implementation
        override fun startShortcut(
            packageName: String,
            shortcutId: String,
            sourceBounds: Rect?,
            startActivityOptions: Bundle?,
            user: UserHandle,
        ) {
            wontStart[shortcutId]?.let { throw it }
            started += "$packageName/$shortcutId"
        }

        companion object {
            /** What each package has published, as the system hands it to Home. */
            val published = mutableMapOf<String, List<ShortcutInfo>>()
            val started = mutableListOf<String>()
            val wontStart = mutableMapOf<String, RuntimeException>()
            var asked = 0
            var flags = 0
            var refuse: RuntimeException? = null

            fun clear() {
                published.clear()
                started.clear()
                wontStart.clear()
                asked = 0
                flags = 0
                refuse = null
            }
        }
    }

    private companion object {
        // ShortcutInfo's hidden flags, as the system sets them.
        const val FLAG_DYNAMIC = 1
        const val FLAG_MANIFEST = 32
        const val FLAG_DISABLED = 64
    }
}
