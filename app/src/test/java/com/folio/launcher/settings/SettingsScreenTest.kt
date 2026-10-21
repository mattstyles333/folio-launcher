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

    private fun show(state: HomeUiState = HomeUiState()) {
        compose.setContent {
            FolioTheme {
                SettingsScreen(
                    state = state,
                    onBack = { calls += "back" },
                    onPickPhoto = { calls += "pickPhoto" },
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

    @Test fun aPhotoThatCannotBeReadSaysSoUnderChoosePhoto() {
        show(HomeUiState(wallpaperCaption = "Couldn't open that photo"))
        compose.onNodeWithText("Couldn't open that photo").assertExists()
    }

    @Test fun aPrintsTitleStaysUnderTheClock() {
        show(HomeUiState(wallpaperCaption = "Lavender fields, Provence"))
        compose.onNodeWithText("Replace the print").assertExists()
        compose.onNodeWithText("Lavender fields, Provence").assertDoesNotExist()
    }

    @Test fun jevTakesTheKeyAndClearsItFromTheClipboard() {
        clipboard.setPrimaryClip(ClipData.newPlainText("OpenRouter", key))
        show()
        tapJev()
        assertEquals(listOf("key:$key"), calls)
        assertFalse(clipboard.hasPrimaryClip())
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
