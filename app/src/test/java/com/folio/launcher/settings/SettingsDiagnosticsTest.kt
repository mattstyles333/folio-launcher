package com.folio.launcher.settings

import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.folio.launcher.data.HomeUiState
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-xxhdpi")
class SettingsDiagnosticsTest {
    @get:Rule
    val compose = createComposeRule()

    private val switch = isToggleable() and hasAnyAncestor(hasTestTag("diagnostics"))
    private val changes = mutableListOf<Boolean>()

    private fun show(state: HomeUiState) = compose.setContent {
        SettingsScreen(
            state = state, onBack = {}, onPickPhoto = {}, onNextBing = {}, onPreviousPrint = {},
            onShowClock = {}, onResetPins = {}, onSetDefault = {}, onUnhideApp = {}, onCycleAi = {},
            onSetOpenRouterKey = { false }, onClearOpenRouterKey = {},
            onDiagnostics = { changes += it },
        )
    }

    @Test fun releaseBuildSwitchIsOnAndTurnsOff() {
        show(HomeUiState(diagnostics = true, diagnosticsBuiltIn = true))
        compose.onNodeWithText(DIAGNOSTICS_PRIVACY).performScrollTo()
        compose.onNode(switch).assertIsEnabled().performClick()
        assertEquals(listOf(false), changes)
    }

    @Test fun buildWithoutEndpointSaysSoAndCannotTurnOn() {
        show(HomeUiState(diagnostics = true, diagnosticsBuiltIn = false))
        compose.onNodeWithText("This build has no diagnostics endpoint; nothing is sent.").performScrollTo()
        compose.onNode(switch).assertIsNotEnabled()
    }
}
