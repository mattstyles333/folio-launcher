package com.folio.launcher.hermes

import com.folio.hermes.ApprovalChoice
import com.folio.launcher.hermes.LiveTokenProvider.Companion.SIGNED_OUT
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Off state: without a usable Hermes Live connection nothing is built, so nothing dials. */
class HermesAskTest {
    private var built = 0
    private var queries = 0

    private fun ask(scope: CoroutineScope, connection: HermesConnection?) = HermesAsk(
        parent = scope,
        connection = { queries++; connection },
        loadSessionId = { "" },
        saveSessionId = {},
        gatewayFactory = { _, _, _ -> built++; error("must not build a gateway while Hermes is off") },
    )

    private val offConnections = listOf(
        null,
        HermesConnection(signedIn = false, baseUrl = "https://hermes.example.com", accessToken = "", expiresAtMs = 0),
        HermesConnection(signedIn = true, baseUrl = "https://hermes.example.com", accessToken = "", expiresAtMs = 0),
        HermesConnection(signedIn = true, baseUrl = "http://hermes.example.com", accessToken = "fake", expiresAtMs = 0),
    )

    @Test
    fun attachAskApprove_neverBuildAGatewayWhileOff() = runTest {
        for (connection in offConnections) {
            val hermes = ask(CoroutineScope(StandardTestDispatcher(testScheduler)), connection)
            hermes.attach()
            advanceUntilIdle()
            assertEquals("attach $connection", SIGNED_OUT, hermes.state.value.error)
            hermes.ask("What's the weather?")
            advanceUntilIdle()
            val exchange = hermes.state.value.exchanges.single()
            assertEquals(ExchangeStatus.Failed, exchange.status)
            assertEquals(SIGNED_OUT, exchange.note)
            hermes.approve(ApprovalChoice.Once)
            hermes.stop()
            hermes.detach()
            advanceUntilIdle()
        }
        assertEquals(0, built)
        assertEquals(offConnections.size * 2, queries)
    }

    @Test
    fun nothingHappensUntilAttachedOrAsked() = runTest {
        val hermes = ask(CoroutineScope(StandardTestDispatcher(testScheduler)), null)
        advanceUntilIdle()
        hermes.detach()
        advanceUntilIdle()
        assertEquals(0, queries)
        assertEquals(0, built)
        assertNull(hermes.state.value.error)
    }

    @Test
    fun blankQuestion_isIgnored() = runTest {
        val hermes = ask(CoroutineScope(StandardTestDispatcher(testScheduler)), null)
        hermes.ask("   ")
        advanceUntilIdle()
        assertEquals(0, queries)
        assertEquals(emptyList<HermesExchange>(), hermes.state.value.exchanges)
    }
}
