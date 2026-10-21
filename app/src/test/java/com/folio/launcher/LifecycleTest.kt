package com.folio.launcher

import android.content.Intent
import android.media.AudioManager
import android.os.Looper
import androidx.compose.material3.Text
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.folio.launcher.data.RingerVisual
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLauncherApps
import org.robolectric.util.ReflectionHelpers

/** Home stays Home: Back on the print, Home over Settings, and listeners that outlive an activity. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class LifecycleTest {
    @get:Rule
    val compose = createComposeRule()

    private val app: FolioApp = ApplicationProvider.getApplicationContext()

    @Test
    fun backOnTheIdlePrint_keepsFolioOpen() {
        val activity = Robolectric.buildActivity(MainActivity::class.java).create().get()
        activity.onBackPressedDispatcher.onBackPressed()
        assertFalse(activity.isFinishing)
    }

    @Test
    fun clearedHomeViewModel_leavesTheAppListAndRingerListening() {
        // A second MainActivity (opened from a drawer or the installer) going away.
        val store = ViewModelStore()
        homeViewModel(store)
        store.clear()

        assertTrue("app list callback", launcherAppsCallbacks().isNotEmpty())
        assertNotEquals(RingerVisual.Silent, app.ringer.visual.value)
        app.getSystemService(AudioManager::class.java).ringerMode = AudioManager.RINGER_MODE_SILENT
        app.sendBroadcast(Intent(AudioManager.RINGER_MODE_CHANGED_ACTION))
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(RingerVisual.Silent, app.ringer.visual.value)
    }

    @Test
    fun firstState_carriesTheRingerAlreadyKnown() {
        app.getSystemService(AudioManager::class.java).ringerMode = AudioManager.RINGER_MODE_VIBRATE
        app.ringer.refresh()
        val store = ViewModelStore()
        try {
            // What the first frame draws, before prefs are read and the real state is built.
            assertEquals(RingerVisual.Vibrate, homeViewModel(store).state.value.mode)
        } finally {
            store.clear()
        }
    }

    @Test
    fun popToHome_leavesSettings_andNeverPopsHomeItself() {
        lateinit var nav: NavHostController
        compose.setContent {
            nav = rememberNavController()
            NavHost(nav, startDestination = "home") {
                composable("home") { Text("Print") }
                composable("settings") { Text("Settings") }
            }
        }
        compose.runOnIdle { nav.navigate("settings") }
        compose.onNodeWithText("Settings").assertIsDisplayed()

        // Home pressed over Settings, then Settings' Back tapped again inside its exit fade.
        compose.runOnIdle {
            assertTrue(nav.popToHome())
            assertFalse(nav.popToHome())
        }
        compose.onNodeWithText("Print").assertIsDisplayed()
        compose.runOnIdle { assertEquals("home", nav.currentDestination?.route) }
    }

    @Test
    fun usageQuery_firstAtOnce_thenAtMostOncePerMinute_unlessJustGranted() {
        assertTrue(HomeViewModel.usageQueryDue(now = 5_000, queriedAt = null, justGranted = false))
        assertFalse(HomeViewModel.usageQueryDue(now = 64_999, queriedAt = 5_000, justGranted = false))
        assertTrue(HomeViewModel.usageQueryDue(now = 65_000, queriedAt = 5_000, justGranted = false))
        assertTrue(HomeViewModel.usageQueryDue(now = 5_100, queriedAt = 5_000, justGranted = true))
    }

    @Test
    fun roleRequest_fallsBackOnlyWhenRefusedWithoutAsking() {
        assertTrue(roleRefusedWithoutAsking(isDefaultHome = false, elapsedMs = 150))
        // The dialog was shown: the user said no, or chose Folio.
        assertFalse(roleRefusedWithoutAsking(isDefaultHome = false, elapsedMs = 4_000))
        assertFalse(roleRefusedWithoutAsking(isDefaultHome = true, elapsedMs = 150))
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

    /** Robolectric's list of registered LauncherApps callbacks; AppRepository's is the only one. */
    private fun launcherAppsCallbacks(): List<*> =
        ReflectionHelpers.getStaticField(ShadowLauncherApps::class.java, "callbacks")
}
