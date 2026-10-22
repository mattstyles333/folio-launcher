package com.folio.launcher.settings

import android.content.ClipData
import android.content.ClipboardManager
import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.content.res.AssetFileDescriptor
import android.database.Cursor
import android.net.Uri
import android.os.Bundle
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTouchInput
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.folio.launcher.data.BrainStatus
import com.folio.launcher.data.HomeUiState
import com.folio.launcher.hermes.HermesAvailability
import com.folio.launcher.hermes.LiveTokenProvider
import com.folio.launcher.ui.FolioTheme
import java.io.FileNotFoundException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.annotation.Config

/** What a tap in Settings reaches, and what Jev takes from the clipboard. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-xxhdpi")
class SettingsScreenTest {
    @get:Rule
    val compose = createComposeRule()

    private val calls = mutableListOf<String>()
    private val clipboard = ApplicationProvider.getApplicationContext<Context>()
        .getSystemService(ClipboardManager::class.java)
    private val key = "sk-or-v1-" + "a".repeat(64)

    /** What the view model hands Settings; a test moves it on as a key check would. */
    private var screen by mutableStateOf(HomeUiState())

    private fun show(state: HomeUiState = HomeUiState()) {
        screen = state
        compose.setContent {
            FolioTheme {
                SettingsScreen(
                    state = screen,
                    onBack = { calls += "back" },
                    onPickPhoto = { calls += "pickPhoto" },
                    onPickPhotos = { calls += "pickPhotos" },
                    onNextBing = { calls += "nextBing" },
                    onPreviousPrint = { calls += "previousPrint" },
                    onShowClock = { calls += "showClock:$it" },
                    onDiagnostics = { calls += "diagnostics:$it" },
                    onResetPins = { calls += "resetPins" },
                    onSetDefault = { calls += "setDefault" },
                    onUnhideApp = { calls += "unhide:${it.packageName}" },
                    onCycleAi = { calls += "cycleAi" },
                    onSetOpenRouterKey = { text ->
                        calls += "key:$text"
                        text.startsWith("sk-or-")
                    },
                    onClearOpenRouterKey = { calls += "clearKey" },
                    onRetryOpenRouterKey = { calls += "retry" },
                    onOpenHermesLive = { calls += "hermesLive" },
                )
            }
        }
        compose.waitForIdle()
    }

    private fun tapJev() {
        compose.onNodeWithText("Jev").performScrollTo().performClick()
        compose.waitForIdle()
    }

    @Test fun tapOnTheEmptyHiddenPickerReachesNoRowUnderneath() {
        show()
        compose.onNodeWithText("Hidden apps").performScrollTo().performClick()
        compose.onNodeWithText("Nothing hidden. Swipe an app right on the home screen.").assertExists()
        // Mid-screen, where someone taps to put the keyboard away: blank picker over Reset pins and Ask.
        compose.onRoot().performTouchInput { click() }
        compose.waitForIdle()
        assertEquals(emptyList<String>(), calls)
    }

    @Test fun theEmptyHiddenPicker_hidesTheRowsUnderneathFromAccessibility() {
        show()
        compose.onNodeWithText("Hidden apps").performScrollTo().performClick()
        compose.onNodeWithText("Nothing hidden. Swipe an app right on the home screen.").assertExists()
        // TalkBack, Switch Access and Voice Access can't reach them behind the scrim either.
        compose.onNodeWithText("Reset pins").assertDoesNotExist()
        compose.onNodeWithText("Show clock").assertDoesNotExist()
    }

    @Test fun showClockOff_saysWhereSettingsWent() {
        show()
        compose.onNodeWithText("Time and date").assertExists()
        // Off, the clock's invisible slot is the way back in; a long-press on the print is the ringer.
        screen = screen.copy(showClock = false)
        compose.waitForIdle()
        compose.onNodeWithText("Long-press where the clock was for Settings").assertExists()
    }

    @Test fun aPhotoThatCannotBeReadSaysSoUnderChoosePhoto() {
        show(HomeUiState(wallpaperCaption = "Couldn't open that photo"))
        compose.onNodeWithText("Couldn't open that photo").assertExists()
    }

    @Test fun aPrintsTitleStaysUnderTheClock() {
        show(HomeUiState(wallpaperCaption = "Lavender fields, Provence"))
        compose.onNodeWithText("Replace the print").assertExists()
        compose.onNodeWithText("Lavender fields, Provence").assertDoesNotExist()
    }

    @Test fun yourPhotosOpensThePickerForSeveral() {
        show()
        compose.onNodeWithText("Up to 30, one a day").assertExists()
        compose.onNodeWithText("Your photos").performScrollTo().performClick()
        compose.waitForIdle()
        assertEquals(listOf("pickPhotos"), calls)
    }

    @Test fun yourPhotosSaysHowTheImportWent() {
        // The picker comes back to Settings: the count, and what couldn't be read, show there.
        show(HomeUiState(rollNote = "Adding 3 of 12…"))
        compose.onNodeWithText("Adding 3 of 12…").assertExists()
        screen = screen.copy(rollNote = "Added 11 photos, 1 couldn't be read")
        compose.waitForIdle()
        compose.onNodeWithText("Added 11 photos, 1 couldn't be read").assertExists()
    }

    @Test fun onYourPhotos_theBingRowLeavesThem() {
        show(HomeUiState(rollSize = 12))
        compose.onNodeWithText("12 photos, one a day. Double-tap for the next.").assertExists()
        // Double-tap is the roll's now: Bing print is the way back to Bing's.
        compose.onNodeWithText("Back to Bing's prints").assertExists()
        compose.onNodeWithText("Double-tap the print for the next one").assertDoesNotExist()
        compose.onNodeWithText("Bing print").performScrollTo().performClick()
        compose.waitForIdle()
        assertEquals(listOf("nextBing"), calls)
    }

    @Test fun hermesLiveOnPlainHttp_saysToSetTheHttpsAddress_andTheTapOpensIt() {
        show(HomeUiState(hermes = HermesAvailability.PlainHttp))
        compose.onNodeWithText(LiveTokenProvider.PLAIN_HTTP).performScrollTo().performClick()
        compose.waitForIdle()
        assertEquals(listOf("hermesLive"), calls)
    }

    /** The view model's word on the pasted key, as Settings sees it arrive. */
    private fun keyChecked(brain: BrainStatus) {
        screen = screen.copy(brain = brain)
        compose.waitForIdle()
    }

    @Test fun jevTakesTheKey_andClearsItFromTheClipboardOnceSaved() {
        clipboard.setPrimaryClip(ClipData.newPlainText("OpenRouter", key))
        show()
        tapJev()
        assertEquals(listOf("key:$key"), calls)
        // Not while it's checked: OpenRouter shows a key only once, and the check may not save it.
        keyChecked(BrainStatus.Checking)
        assertTrue(clipboard.hasPrimaryClip())
        keyChecked(BrainStatus.Ready)
        assertFalse(clipboard.hasPrimaryClip())
    }

    @Test fun jevLeavesARefusedKeyOnTheClipboard() {
        clipboard.setPrimaryClip(ClipData.newPlainText("OpenRouter", key))
        show()
        tapJev()
        keyChecked(BrainStatus.Checking)
        keyChecked(BrainStatus.Rejected)
        assertTrue(clipboard.hasPrimaryClip())
    }

    @Test fun jevTappedAgainWhileChecking_isNoMissingKey() {
        clipboard.setPrimaryClip(ClipData.newPlainText("OpenRouter", key))
        show()
        tapJev()
        keyChecked(BrainStatus.Checking)
        // An impatient second tap, with the clipboard emptied meanwhile.
        clipboard.clearPrimaryClip()
        tapJev()
        compose.onNodeWithText("No OpenRouter key on the clipboard").assertDoesNotExist()
        compose.onNodeWithText("Checking the key…").assertExists()
    }

    @Test fun jevLeavesAClipThatIsNotAKey() {
        clipboard.setPrimaryClip(ClipData.newPlainText("note", "milk, eggs"))
        show()
        tapJev()
        assertEquals(listOf("key:milk, eggs"), calls)
        assertTrue(clipboard.hasPrimaryClip())
        compose.onNodeWithText("No OpenRouter key on the clipboard").assertExists()
    }

    @Test fun jevOutOfCredit_aTapWithNoNewKeyTriesTheSavedOneAgain() {
        // OpenRouter shows a key only once: after a top-up there may be nothing to copy again.
        clipboard.setPrimaryClip(ClipData.newPlainText("note", "milk, eggs"))
        show(HomeUiState(brain = BrainStatus.NoCredit))
        tapJev()
        assertEquals(listOf("key:milk, eggs", "retry"), calls)
        assertTrue(clipboard.hasPrimaryClip())
        compose.onNodeWithText("No OpenRouter key on the clipboard").assertDoesNotExist()
    }

    @Test fun jevNeverOpensAUriClip() {
        KeyFileProvider.opened = false
        Robolectric.setupContentProvider(KeyFileProvider::class.java, KeyFileProvider.AUTHORITY)
        clipboard.setPrimaryClip(ClipData.newRawUri("key.txt", Uri.parse("content://${KeyFileProvider.AUTHORITY}/key.txt")))
        show()
        tapJev()
        assertFalse(KeyFileProvider.opened)
        assertEquals(listOf("key:"), calls)
    }

    /** A text file a copied URI could point at. Reading one in a tap handler blocks the main thread. */
    class KeyFileProvider : ContentProvider() {
        override fun onCreate() = true
        override fun getType(uri: Uri) = "text/plain"
        override fun openTypedAssetFile(uri: Uri, mimeTypeFilter: String, opts: Bundle?): AssetFileDescriptor? {
            opened = true
            throw FileNotFoundException(uri.toString())
        }
        override fun query(
            uri: Uri,
            projection: Array<String>?,
            selection: String?,
            selectionArgs: Array<String>?,
            sortOrder: String?,
        ): Cursor? = null
        override fun insert(uri: Uri, values: ContentValues?): Uri? = null
        override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<String>?) = 0
        override fun delete(uri: Uri, selection: String?, selectionArgs: Array<String>?) = 0

        companion object {
            const val AUTHORITY = "com.folio.launcher.test.keyfile"
            var opened = false
        }
    }
}
