package com.folio.launcher.data

import android.content.ComponentName
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.os.Looper
import android.os.PersistableBundle
import android.os.Process
import androidx.compose.ui.graphics.asImageBitmap
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.folio.launcher.FolioApp
import com.folio.launcher.HomeViewModel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers
import org.robolectric.util.ReflectionHelpers.ClassParameter

/** Apps paused by an app timer, Focus mode or an admin: Folio knows, and a tap on one isn't an open. */
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

    /** A HomeViewModel as MainActivity's `by viewModels()` builds it, in a store the test clears. */
    private fun homeViewModel(store: ViewModelStore): HomeViewModel =
        ViewModelProvider(
            store,
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T =
                    HomeViewModel(app, SavedStateHandle()) as T
            },
        )[HomeViewModel::class.java]
}
