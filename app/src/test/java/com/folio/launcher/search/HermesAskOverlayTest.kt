package com.folio.launcher.search

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.folio.hermes.ApprovalChoice
import com.folio.hermes.ApprovalRequest
import com.folio.hermes.ClarifyQuestion
import com.folio.hermes.ClarifyRequest
import com.folio.launcher.data.AskProviders
import com.folio.launcher.hermes.ExchangeStatus
import com.folio.launcher.hermes.HermesAskState
import com.folio.launcher.hermes.HermesExchange
import com.folio.launcher.ui.FolioTheme
import com.folio.launcher.ui.PrintInk
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** The Ask overlay when Hermes answers: what shows and what each control sends. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-xxhdpi")
class HermesAskOverlayTest {
    @get:Rule
    val compose = createComposeRule()

    private val calls = mutableListOf<String>()

    private val actions = HermesAskActions(
        ask = { calls += "ask:$it" },
        stop = { calls += "stop" },
        newSession = { calls += "new" },
        approve = { calls += "approve:$it" },
        clarify = { calls += "clarify:$it" },
        call = { calls += "call:$it" },
    )

    private fun show(hermes: HermesAskState?, aiLabel: String = "Hermes", startQuery: String = "") {
        compose.setContent {
            var query by remember { mutableStateOf(startQuery) }
            FolioTheme {
                AskOverlay(
                    visible = true,
                    aiLabel = aiLabel,
                    query = query,
                    onQueryChange = { query = it },
                    suggestions = if (hermes != null) AskProviders.hermesSuggestions("Stillness is the move") else listOf("Open Grok" to ""),
                    accent = PrintInk,
                    onAsk = { calls += "onAsk:$it" },
                    onDismiss = { calls += "dismiss" },
                    hermes = hermes,
                    hermesActions = actions,
                )
            }
        }
        compose.waitForIdle()
    }

    @Test
    fun typingAndSend_asksHermesAndClearsTheField() {
        show(HermesAskState())
        compose.onNode(hasSetTextAction()).performTextInput("Is the boiler booked?")
        compose.onNode(hasSetTextAction()).performImeAction()
        compose.waitForIdle()
        assertEquals(listOf("ask:Is the boiler booked?"), calls)
        compose.onNodeWithText("Ask Hermes").assertExists()
    }

    @Test
    fun mic_opensHermesLiveWithTheTypedText() {
        show(HermesAskState())
        compose.onNode(hasSetTextAction()).performTextInput("call the garage")
        compose.onNodeWithContentDescription("Talk to Hermes").performClick()
        compose.waitForIdle()
        assertEquals(listOf("call:call the garage"), calls)
    }

    @Test
    fun chips_talkCallsAndOthersAsk() {
        show(HermesAskState())
        compose.onNodeWithText("Talk to Hermes").performClick()
        compose.onNodeWithText("Catch me up").performClick()
        compose.waitForIdle()
        assertEquals("call:", calls[0])
        assertEquals(true, calls[1].startsWith("ask:Catch me up"))
    }

    @Test
    fun runningTurn_showsReplyToolLineAndStop() {
        show(
            HermesAskState(
                exchanges = listOf(HermesExchange(1, "Weather?", reply = "Dry until four.")),
                working = "Hermes is working: web search",
                hasSession = true,
            ),
        )
        compose.onNodeWithText("Dry until four.").assertIsDisplayed()
        compose.onNodeWithText("Hermes is working: web search").assertIsDisplayed()
        compose.onNodeWithText("Stop").performClick()
        compose.onNodeWithText("New").performClick()
        compose.waitForIdle()
        assertEquals(listOf("stop", "new"), calls)
    }

    @Test
    fun finishedTurn_hasNoStop() {
        show(HermesAskState(exchanges = listOf(HermesExchange(1, "Hi", "Hello.", ExchangeStatus.Done))))
        compose.onNodeWithText("Stop").assertDoesNotExist()
        compose.onNodeWithText("Talk to Hermes").assertDoesNotExist()
    }

    @Test
    fun approval_isAnsweredOnlyByTheUser() {
        val request = ApprovalRequest(
            id = "srq-1", sessionId = "rt", requestId = "a1", command = "git push",
            description = "Push the branch", choices = ApprovalChoice.entries, toolName = "terminal", params = JsonObject(emptyMap()),
        )
        show(HermesAskState(exchanges = listOf(HermesExchange(1, "Ship it")), approval = request))
        compose.onNodeWithText("git push").assertIsDisplayed()
        assertEquals(emptyList<String>(), calls)
        compose.onNodeWithText("Approve once").performClick()
        compose.onNodeWithText("Deny").performClick()
        compose.waitForIdle()
        assertEquals(listOf("approve:Once", "approve:Deny"), calls)
    }

    @Test
    fun clarify_choiceAnswersTheQuestion() {
        val request = ClarifyRequest(
            "srq-2", "rt",
            listOf(ClarifyQuestion("q1", "Which day?", listOf("Friday", "Saturday"), multiSelect = false)),
            JsonObject(emptyMap()),
        )
        show(HermesAskState(exchanges = listOf(HermesExchange(1, "Book it")), clarify = request))
        compose.onNodeWithText("Which day?").assertIsDisplayed()
        compose.onNodeWithText("Saturday").performClick()
        compose.waitForIdle()
        assertEquals(listOf("clarify:{q1=Saturday}"), calls)
    }

    @Test
    fun withoutHermes_theOverlayIsTheAppOne() {
        show(hermes = null, aiLabel = "Grok")
        compose.onNodeWithText("Ask Grok").assertExists()
        compose.onNodeWithContentDescription("Talk to Hermes").assertDoesNotExist()
        compose.onNodeWithText("Open Grok").performClick()
        compose.waitForIdle()
        assertEquals(listOf("onAsk:"), calls)
    }
}
