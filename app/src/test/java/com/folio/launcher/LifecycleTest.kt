package com.folio.launcher

import android.content.Intent
import android.media.AudioManager
import android.os.Looper
import androidx.compose.material3.Text
import androidx.compose.runtime.LaunchedEffect
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
import com.folio.launcher.data.BrainStatus
import com.folio.launcher.data.RingerVisual
import java.io.File
import kotlinx.coroutines.flow.MutableSharedFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
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
    fun home_overSettings_returnsToThePrint() {
        lateinit var nav: NavHostController
        val idleTick = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
        var idles = 0
        compose.setContent {
            nav = rememberNavController()
            // As MainActivity wires it: Home ticks idle (onNewIntent), and home's own state resets.
            LaunchedEffect(Unit) { nav.followIdle(idleTick) { idles++ } }
            NavHost(nav, startDestination = "home") {
                composable("home") { Text("Print") }
                composable("settings") { Text("Settings") }
            }
        }
        compose.runOnIdle { nav.navigate("settings") }
        compose.onNodeWithText("Settings").assertIsDisplayed()

        compose.runOnIdle { assertTrue(idleTick.tryEmit(Unit)) }
        compose.onNodeWithText("Print").assertIsDisplayed()
        compose.runOnIdle {
            assertEquals("home", nav.currentDestination?.route)
            assertEquals(1, idles)
        }
        // Home again on the print: it stays, and still resets home.
        compose.runOnIdle { assertTrue(idleTick.tryEmit(Unit)) }
        compose.runOnIdle {
            assertEquals("home", nav.currentDestination?.route)
            assertEquals(2, idles)
        }
    }

    @Test
    fun usageQuery_firstAtOnce_thenAtMostOncePerMinute_unlessJustGranted() {
        assertTrue(HomeViewModel.usageQueryDue(now = 5_000, queriedAt = null, justGranted = false))
        assertFalse(HomeViewModel.usageQueryDue(now = 64_999, queriedAt = 5_000, justGranted = false))
        assertTrue(HomeViewModel.usageQueryDue(now = 65_000, queriedAt = 5_000, justGranted = false))
        assertTrue(HomeViewModel.usageQueryDue(now = 5_100, queriedAt = 5_000, justGranted = true))
    }

    @Test
    fun jevRow_aRefusedKeySaysSo_thoughNoneIsSaved() {
        // A refused key is never saved (a restart would read it as Ready), so the row can't wait for one.
        val refused = HomeViewModel.pasteChecked(check = false, brain = null)
        assertFalse(refused.save)
        assertEquals(BrainStatus.Rejected, HomeViewModel.brainShown(savedKey = "", checked = refused.brain, pasted = refused.pasted))
        assertEquals(BrainStatus.Checking, HomeViewModel.brainShown(savedKey = "", checked = null, pasted = BrainStatus.Checking))
        assertEquals(BrainStatus.Off, HomeViewModel.brainShown(savedKey = "", checked = null, pasted = null))
        // A saved key is trusted after a restart, until a call says otherwise.
        val key = "sk-or-v1-" + "a".repeat(64)
        assertEquals(BrainStatus.Ready, HomeViewModel.brainShown(savedKey = key, checked = null, pasted = null))
        assertEquals(BrainStatus.NoCredit, HomeViewModel.brainShown(savedKey = key, checked = BrainStatus.NoCredit, pasted = null))
    }

    @Test
    fun jev_aRefusedPaste_leavesTheSavedKeyAsking() {
        val saved = "sk-or-v1-" + "a".repeat(64)
        // OpenRouter refuses the pasted key: it isn't saved, and what was known of the saved key stays.
        val refused = HomeViewModel.pasteChecked(check = false, brain = null)
        assertFalse(refused.save)
        assertNull(refused.brain)
        assertEquals(BrainStatus.Rejected, refused.pasted)
        assertEquals(BrainStatus.NoCredit, HomeViewModel.pasteChecked(check = false, brain = BrainStatus.NoCredit).brain)
        // The row says the pasted key was refused; that verdict isn't the saved key's, so Jev goes on with it.
        assertEquals(BrainStatus.Rejected, HomeViewModel.brainShown(savedKey = saved, checked = refused.brain, pasted = refused.pasted))
        assertTrue(HomeViewModel.jevMayAsk(savedKey = saved, checked = refused.brain))
        // Accepted, or OpenRouter out of reach: saved, and Jev asks with it whatever the old key's fate.
        for ((check, status) in listOf(true to BrainStatus.Ready, null to BrainStatus.Unverified)) {
            val taken = HomeViewModel.pasteChecked(check, brain = BrainStatus.Rejected)
            assertTrue(taken.save)
            assertEquals(status, taken.brain)
            assertNull(taken.pasted)
            assertTrue(HomeViewModel.jevMayAsk(savedKey = saved, checked = taken.brain))
        }
        // A call that finds the saved key refused, or out of credit, stops Jev until Settings.
        assertFalse(HomeViewModel.jevMayAsk(savedKey = saved, checked = BrainStatus.Rejected))
        assertFalse(HomeViewModel.jevMayAsk(savedKey = saved, checked = BrainStatus.NoCredit))
        assertFalse(HomeViewModel.jevMayAsk(savedKey = "", checked = null))
    }

    @Test
    fun roleRequest_fallsBackOnlyWhenRefusedWithoutAsking() {
        assertTrue(roleRefusedWithoutAsking(isDefaultHome = false, elapsedMs = 150))
        // The dialog was shown: the user said no, or chose Folio.
        assertFalse(roleRefusedWithoutAsking(isDefaultHome = false, elapsedMs = 4_000))
        assertFalse(roleRefusedWithoutAsking(isDefaultHome = true, elapsedMs = 150))
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

    /** Robolectric's list of registered LauncherApps callbacks; AppRepository's is the only one. */
    private fun launcherAppsCallbacks(): List<*> =
        ReflectionHelpers.getStaticField(ShadowLauncherApps::class.java, "callbacks")
}
