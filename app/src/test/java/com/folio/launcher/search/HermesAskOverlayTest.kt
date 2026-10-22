package com.folio.launcher.search

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.ScrollAxisRange
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipe
import androidx.compose.ui.text.TextLayoutResult
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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
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

    /** What the overlay shows; a test sets it again to stream more of a reply. */
    private val hermesState = mutableStateOf<HermesAskState?>(null)

    private fun show(hermes: HermesAskState?, aiLabel: String = "Hermes", startQuery: String = "") {
        hermesState.value = hermes
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
                    hermes = hermesState.value,
                    hermesActions = actions,
                )
            }
        }
        compose.waitForIdle()
    }

    private fun approval(command: String) = ApprovalRequest(
        id = "srq-1", sessionId = "rt", requestId = "a1", command = command,
        description = "Run the script", choices = ApprovalChoice.entries, toolName = "terminal", params = JsonObject(emptyMap()),
    )

    /** The text as laid out on screen (the semantics text is always whole, even when clipped). */
    private fun SemanticsNodeInteraction.textLayout(): TextLayoutResult {
        val layouts = mutableListOf<TextLayoutResult>()
        fetchSemanticsNode().config[SemanticsActions.GetTextLayoutResult].action!!.invoke(layouts)
        return layouts.single()
    }

    /** The transcript's scroll position and its end. */
    private fun transcript(): ScrollAxisRange =
        compose.onNode(SemanticsMatcher.keyIsDefined(SemanticsProperties.VerticalScrollAxisRange))
            .fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange]

    private val longReply = (1..60).joinToString("\n") { "Line $it of the week." }

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

    @Test
    fun approval_showsEveryLineOfTheCommandBeforeApproveOnce() {
        val command = (1..20).joinToString("\n") { "echo step $it" } + "\nrm -rf ~/notes"
        show(HermesAskState(exchanges = listOf(HermesExchange(1, "Tidy up")), approval = approval(command)))
        val layout = compose.onNodeWithText(command).textLayout()
        assertFalse(layout.didOverflowHeight)
        assertTrue(layout.lineCount >= 21)
        compose.onNodeWithText("Approve once").performScrollTo().performClick()
        compose.waitForIdle()
        assertEquals(listOf("approve:Once"), calls)
    }

    @Test
    fun approvalBeforeAnyQuestion_aLongCommandStillScrollsToItsButtons() {
        val command = (1..80).joinToString("\n") { "echo step $it" }
        show(HermesAskState(approval = approval(command)))
        assertFalse(compose.onNodeWithText(command).textLayout().didOverflowHeight)
        compose.onNodeWithText("Deny").performScrollTo().performClick()
        compose.waitForIdle()
        assertEquals(listOf("approve:Deny"), calls)
    }

    @Test
    fun clarify_freeTextTakesTheKeyboard_soSendAnswersIt() {
        val request = ClarifyRequest(
            "srq-3", "rt",
            listOf(ClarifyQuestion("q1", "Which day?", emptyList(), multiSelect = false)),
            JsonObject(emptyMap()),
        )
        show(HermesAskState(exchanges = listOf(HermesExchange(1, "Book the usual table")), clarify = request))
        // The cursor is in the answer, so typing and Send answer Hermes instead of interrupting it.
        compose.onNode(isFocused()).performTextInput("Saturday")
        compose.onNode(isFocused()).performImeAction()
        compose.waitForIdle()
        assertEquals(listOf("clarify:{q1=Saturday}"), calls)
    }

    @Test
    fun streamingReply_scrolledUpToReread_staysPut() {
        show(HermesAskState(exchanges = listOf(HermesExchange(1, "Summarise the week", reply = longReply))))
        assertEquals(transcript().maxValue(), transcript().value(), 0.5f)
        compose.onNodeWithText("Summarise the week").performScrollTo()
        val readAt = transcript().value()
        assertTrue(readAt < transcript().maxValue())
        hermesState.value = HermesAskState(exchanges = listOf(HermesExchange(1, "Summarise the week", reply = "$longReply\nMore.")))
        compose.waitForIdle()
        assertEquals(readAt, transcript().value(), 0.5f)
        compose.onNodeWithText("Summarise the week").assertIsDisplayed()
    }

    @Test
    fun streamingReply_atTheEnd_isFollowed() {
        show(HermesAskState(exchanges = listOf(HermesExchange(1, "Summarise the week", reply = longReply))))
        val before = transcript().maxValue()
        assertEquals(before, transcript().value(), 0.5f)
        hermesState.value = HermesAskState(exchanges = listOf(HermesExchange(1, "Summarise the week", reply = "$longReply\nMore.\nMore.")))
        compose.waitForIdle()
        assertTrue(transcript().maxValue() > before)
        assertEquals(transcript().maxValue(), transcript().value(), 0.5f)
    }

    @Test
    fun talkBack_hearsApprovalsAndToolSteps_andChipsAreButtons() {
        show(
            HermesAskState(
                exchanges = listOf(HermesExchange(1, "Ship it", reply = "Pushing now.")),
                working = "Hermes is working: terminal",
                approval = approval("git push"),
            ),
        )
        val polite = SemanticsMatcher.expectValue(SemanticsProperties.LiveRegion, LiveRegionMode.Polite)
        val button = SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Button)
        compose.onNodeWithText("Hermes wants to go ahead").assert(polite)
        compose.onNodeWithText("Hermes is working: terminal").assert(polite)
        // Never the streaming reply: it would be read out token by token.
        compose.onNodeWithText("Pushing now.").assert(SemanticsMatcher.keyNotDefined(SemanticsProperties.LiveRegion))
        compose.onNodeWithText("Approve once").assert(button)
        compose.onNodeWithText("Stop").assert(button)
        compose.onNodeWithText("Cancel").assert(button)
    }

    @Test
    fun hermesBeforeAnyQuestion_aSwipeDownOnTheChipsDismisses() {
        show(HermesAskState())
        compose.onNodeWithText("Catch me up").performTouchInput { swipe(center, center + Offset(0f, 300f)) }
        compose.waitForIdle()
        assertEquals(listOf("dismiss"), calls)
    }

    @Test
    fun smallDrags_doNotAddUpToADismiss() {
        show(hermes = null, aiLabel = "Grok")
        repeat(2) {
            compose.onRoot().performTouchInput { swipe(center, center + Offset(0f, 100f)) }
            compose.waitForIdle()
        }
        assertEquals(emptyList<String>(), calls)
        compose.onRoot().performTouchInput { swipe(center, center + Offset(0f, 300f)) }
        compose.waitForIdle()
        assertEquals(listOf("dismiss"), calls)
    }
}
