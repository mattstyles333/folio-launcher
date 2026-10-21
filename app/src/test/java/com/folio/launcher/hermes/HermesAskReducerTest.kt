package com.folio.launcher.hermes

import com.folio.hermes.ApprovalChoice
import com.folio.hermes.ApprovalRequest
import com.folio.hermes.ClarifyQuestion
import com.folio.hermes.ClarifyRequest
import com.folio.hermes.GatewayConnection
import com.folio.hermes.GatewayUnavailableException
import com.folio.hermes.HermesAuthException
import com.folio.hermes.HermesRpcException
import com.folio.hermes.SubmitStatus
import com.folio.hermes.TurnStatus
import com.folio.hermes.TurnUpdate
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HermesAskReducerTest {
    private val r = HermesAskReducer

    private fun asked(q: String = "What's on?") = r.asked(HermesAskState(), 1, q)

    private fun HermesAskState.apply(vararg updates: TurnUpdate) = updates.fold(this) { s, u -> r.reduce(s, 1, u) }

    private val approval = ApprovalRequest(
        id = "srq-1", sessionId = "rt", requestId = "a1", command = "rm -rf /tmp/x",
        description = "Delete a folder", choices = ApprovalChoice.entries, toolName = "terminal", params = JsonObject(emptyMap()),
    )

    @Test
    fun streamsTextThenCompletes() {
        val s = asked().apply(
            TurnUpdate.Accepted("rt", SubmitStatus.Streaming),
            TurnUpdate.Started,
            TurnUpdate.Text("Hel", "Hel"),
            TurnUpdate.Text("lo", "Hello"),
        )
        assertTrue(s.running)
        assertEquals("Hello", s.exchanges.single().reply)
        val done = s.apply(TurnUpdate.Completed("Hello there.", TurnStatus.Complete, null))
        assertFalse(done.running)
        assertEquals("Hello there.", done.exchanges.single().reply)
        assertEquals(ExchangeStatus.Done, done.exchanges.single().status)
    }

    @Test
    fun toolProgressIsAQuietLine() {
        val s = asked().apply(TurnUpdate.Started, TurnUpdate.ToolStarted("t1", "web_search", "web search"))
        assertEquals("Hermes is working: web search", s.working)
        assertNull(s.apply(TurnUpdate.ToolCompleted("t1", "web_search", null)).working)
    }

    @Test
    fun interruptedAndErrorEndInPlainWords() {
        val stopped = asked().apply(TurnUpdate.Started, TurnUpdate.Completed("", TurnStatus.Interrupted, null))
        assertEquals(ExchangeStatus.Stopped, stopped.exchanges.single().status)
        assertEquals("Stopped.", stopped.exchanges.single().note)
        val failed = asked().apply(TurnUpdate.Completed("", TurnStatus.Error, "model overloaded"))
        assertEquals(ExchangeStatus.Failed, failed.exchanges.single().status)
        assertEquals("Hermes hit a problem: model overloaded", failed.exchanges.single().note)
    }

    @Test
    fun failedTurnUsesTheCauseInPlainWords() {
        val s = asked().apply(TurnUpdate.Failed("prompt.submit failed (4009): session busy", HermesRpcException("prompt.submit", 4009, "session busy")))
        assertEquals("Hermes is still busy with the last question.", s.exchanges.single().note)
        val lost = asked().apply(TurnUpdate.Failed("Lost the connection to Hermes."))
        assertEquals("Lost the connection to Hermes.", lost.exchanges.single().note)
    }

    @Test
    fun approvalShowsAndClearsOnWithdrawOrCompletion() {
        val s = asked().apply(TurnUpdate.Started, TurnUpdate.ApprovalNeeded(approval))
        assertEquals(approval, s.approval)
        assertNull(s.apply(TurnUpdate.RequestWithdrawn("srq-1", "timeout")).approval)
        assertEquals(approval, s.apply(TurnUpdate.RequestWithdrawn("srq-other", "x")).approval)
        assertNull(s.apply(TurnUpdate.Completed("ok", TurnStatus.Complete, null)).approval)
    }

    @Test
    fun clarifyShowsUntilWithdrawn() {
        val req = ClarifyRequest("srq-2", "rt", listOf(ClarifyQuestion("q1", "Which one?", listOf("A", "B"), false)), JsonObject(emptyMap()))
        val s = asked().apply(TurnUpdate.ClarifyNeeded(req))
        assertEquals(req, s.clarify)
        assertNull(s.apply(TurnUpdate.RequestWithdrawn("srq-2", "answered")).clarify)
    }

    @Test
    fun newQuestionSettlesTheOldOneAndKeepsAShortHistory() {
        var s = asked("one")
        s = r.asked(s, 2, "two")
        assertEquals(ExchangeStatus.Stopped, s.exchanges.first().status)
        assertTrue(s.running)
        repeat(30) { s = r.asked(s, 10L + it, "q$it") }
        assertEquals(HermesAskReducer.MAX_EXCHANGES, s.exchanges.size)
    }

    @Test
    fun closingMidTurnSaysWhereTheReplyWent() {
        val s = r.closed(asked().apply(TurnUpdate.Started))
        assertFalse(s.running)
        assertTrue(s.exchanges.single().note!!.contains("Hermes session"))
    }

    @Test
    fun reopenedWhileThatTurnRuns_theWholeReplyReplacesTheNote() {
        val closed = r.closed(asked().apply(TurnUpdate.Started, TurnUpdate.Text("Two", "Two meetings")))
        val back = r.resumed(closed, 1)
        assertTrue(back.running)
        assertNull(back.exchanges.single().note)
        assertEquals("Two meetings", back.exchanges.single().reply)
        assertEquals("Hermes is still answering…", back.working)
        val done = back.apply(TurnUpdate.Completed("Two meetings and a parcel.", TurnStatus.Complete, null))
        assertEquals(ExchangeStatus.Done, done.exchanges.single().status)
        assertEquals("Two meetings and a parcel.", done.exchanges.single().reply)
        assertNull(done.exchanges.single().note)
        assertNull(done.working)
    }

    @Test
    fun aStopThatFailsLate_leavesTheNextQuestionRunning() {
        val two = r.asked(asked("one"), 2, "two")
        assertEquals(two, r.stopped(two, 1))
        val stopped = r.stopped(two, 2)
        assertEquals(ExchangeStatus.Stopped, stopped.exchanges.last().status)
        assertEquals("Stopped.", stopped.exchanges.last().note)
        assertEquals(two, r.stopped(two, null))
    }

    @Test
    fun connectionLines() {
        assertNull(r.connectionLine(GatewayConnection.Ready(1, "e")))
        assertNull(r.connectionLine(GatewayConnection.Waiting(1, 1000, "x")))
        assertEquals("Can’t reach Hermes. Trying again…", r.connectionLine(GatewayConnection.Waiting(2, 1000, "x")))
        assertEquals(LiveTokenProvider.REFUSED, r.connectionLine(GatewayConnection.Stopped(LiveTokenProvider.REFUSED, true)))
    }

    @Test
    fun plainWords() {
        assertEquals(LiveTokenProvider.SIGNED_OUT, r.plain(HermesAuthException.SignInRequired(LiveTokenProvider.SIGNED_OUT)))
        assertEquals(HermesAskReducer.REACH, r.plain(GatewayUnavailableException("socket closed")))
        assertEquals(HermesAskReducer.REACH, r.plain(java.net.UnknownHostException("hermes.example.com")))
        assertEquals("Hermes couldn’t take that (code 4100).", r.plain(HermesRpcException("x", 4100, "nope")))
    }
}
