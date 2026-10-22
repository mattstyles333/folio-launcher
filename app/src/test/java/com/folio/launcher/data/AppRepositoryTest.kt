package com.folio.launcher.data

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ActivityInfo
import android.content.pm.ApplicationInfo
import android.content.pm.LauncherActivityInfo
import android.content.pm.LauncherApps
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Looper
import android.os.PersistableBundle
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
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers
import org.robolectric.util.ReflectionHelpers.ClassParameter

/**
 * Apps paused by an app timer, Focus mode or an admin: Folio knows, and a tap on one isn't an open.
 * An icon that can't be drawn, or a load that fails, takes no app away.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class AppRepositoryTest {
    private val app: FolioApp = ApplicationProvider.getApplicationContext()

    /** An installed app with a launcher activity. */
    private fun install(pkg: String): LaunchableApp {
        val main = ComponentName(pkg, "$pkg.Main")
        shadowOf(app.packageManager).apply {
            addActivityIfNotPresent(main)
            addIntentFilterForActivity(main, IntentFilter(Intent.ACTION_MAIN).apply { addCategory(Intent.CATEGORY_LAUNCHER) })
        }
        return LaunchableApp(
            packageName = pkg,
            activityName = main.className,
            user = Process.myUserHandle(),
            label = pkg,
            icon = Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888).asImageBitmap(),
        )
    }

    /** What Digital Wellbeing does when an app timer runs out (a system API, so by reflection). */
    private fun pause(pkg: String, paused: Boolean = true) {
        val notPaused: Array<String> = ReflectionHelpers.callInstanceMethod(
            app.packageManager,
            "setPackagesSuspended",
            ClassParameter.from(Array<String>::class.java, arrayOf(pkg)),
            ClassParameter.from(Boolean::class.javaPrimitiveType!!, paused),
            ClassParameter.from(PersistableBundle::class.java, null),
            ClassParameter.from(PersistableBundle::class.java, null),
            ClassParameter.from(String::class.java, null),
        )
        assertEquals(emptyList<String>(), notPaused.toList())
    }

    @Test
    fun isPaused_followsTheSystem() {
        val pkg = "com.example.timer.check"
        install(pkg)
        val repo = AppRepository(app)
        assertFalse(repo.isPaused(pkg))
        pause(pkg)
        assertTrue(repo.isPaused(pkg))
        pause(pkg, paused = false)
        assertFalse(repo.isPaused(pkg))
        // Not installed (or not visible to Folio): not paused, as before.
        assertFalse(repo.isPaused("com.example.not.installed"))
    }

    @Test
    fun aTapOnAPausedApp_isNotAnOpen() {
        val paused = install("com.example.timer.paused")
        val open = install("com.example.timer.open")
        pause(paused.packageName)
        val store = ViewModelStore()
        try {
            val vm = homeViewModel(store)
            // The system shows "App paused" instead: counting it would rank up the app the user limited.
            assertTrue(vm.launch(paused))
            assertTrue(vm.launch(open))
            waitFor("the open of ${open.packageName}") { open.packageName in launches() }
            assertFalse(paused.packageName in launches())
        } finally {
            store.clear()
        }
    }

    @Test
    fun anIconThatCantBeDrawn_keepsItsApp_andIsTriedAgain() {
        val pm = shadowOf(app.packageManager)
        pm.setUnbadgedApplicationIcon("com.example.drawn", ColorDrawable(Color.BLUE))
        pm.setUnbadgedApplicationIcon(
            "com.example.broken",
            object : ColorDrawable(Color.RED) {
                override fun draw(canvas: Canvas) {
                    throw IllegalStateException("can't draw")
                }
            },
        )
        listInLauncher("com.example.drawn")
        listInLauncher("com.example.broken")
        val repo = AppRepository(app)
        repo.reload()
        repo.awaitLoad()
        // Still listed, so still in the drawer, search and a rail pin, with a stand-in icon.
        val first = repo.apps.value.associateBy { it.packageName }
        assertEquals(setOf("com.example.drawn", "com.example.broken"), first.keys)

        // The stand-in isn't kept: the next load draws the real icon once it can.
        pm.setUnbadgedApplicationIcon("com.example.broken", ColorDrawable(Color.RED))
        repo.reload()
        repo.awaitLoad()
        val second = repo.apps.value.associateBy { it.packageName }
        assertNotSame(first.getValue("com.example.broken").icon, second.getValue("com.example.broken").icon)
        // A drawn icon is kept, not drawn again.
        assertSame(first.getValue("com.example.drawn").icon, second.getValue("com.example.drawn").icon)
    }

    @Test
    fun aLoadThatFails_keepsTheListItHad() {
        listInLauncher("com.example.kept")
        val repo = AppRepository(app)
        repo.reload()
        repo.awaitLoad()
        val before = repo.apps.value
        assertEquals(listOf("com.example.kept"), before.map { it.packageName })

        // One entry that can't be read fails the whole load.
        listInLauncher("com.example.unreadable", appInfo = null)
        repo.reload()
        repo.awaitLoad()
        // The last list stays up, not an empty home screen until the next package event.
        assertSame(before, repo.apps.value)
    }

    @Test
    @Config(qualifiers = "xxhdpi")
    fun icons_areDrawnAtTheDrawersSize() {
        shadowOf(app.packageManager).setUnbadgedApplicationIcon("com.example.sized", ColorDrawable(Color.BLUE))
        listInLauncher("com.example.sized")
        val repo = AppRepository(app)
        repo.reload()
        repo.awaitLoad()
        // 48 dp at xxhdpi: 1:1 in the drawer, shrunk for search; the rail's 52 dp stretches it a little.
        val icon = repo.apps.value.single().icon
        assertEquals(144, icon.width)
        assertEquals(144, icon.height)
    }

    /**
     * Lists [pkg]'s main activity in LauncherApps, as the system hands it to a launcher (Robolectric
     * builds one only by reflection). With no [appInfo] reading it throws, as a list the system
     * couldn't hand over would.
     */
    private fun listInLauncher(pkg: String, appInfo: ApplicationInfo? = ApplicationInfo().apply { packageName = pkg }) {
        val activity = ActivityInfo().apply {
            packageName = pkg
            name = "$pkg.Main"
            nonLocalizedLabel = pkg
            applicationInfo = appInfo
        }
        val internal = Class.forName("android.content.pm.LauncherActivityInfoInternal")
        val info: LauncherActivityInfo = ReflectionHelpers.callConstructor(
            LauncherActivityInfo::class.java,
            ClassParameter.from(Context::class.java, app),
            ClassParameter.from(
                internal,
                ReflectionHelpers.callConstructor(
                    internal,
                    ClassParameter.from(ActivityInfo::class.java, activity),
                    ClassParameter.from(Class.forName("android.content.pm.IncrementalStatesInfo"), null),
                    ClassParameter.from(UserHandle::class.java, Process.myUserHandle()),
                ),
            ),
        )
        shadowOf(app.getSystemService(LauncherApps::class.java)).addActivity(Process.myUserHandle(), info)
    }

    /** Waits for the load the last reload started, even one that publishes nothing (its job is private). */
    private fun AppRepository.awaitLoad() = runBlocking {
        ReflectionHelpers.getField<Job>(this@awaitLoad, "reloadJob").join()
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
}
