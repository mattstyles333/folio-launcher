package com.folio.launcher.hermes

import com.folio.hermes.ApprovalChoice
import com.folio.launcher.hermes.HermesAskReducer.REACH
import com.folio.launcher.hermes.LiveTokenProvider.Companion.PLAIN_HTTP
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

    /**
     * Each off connection and the line it shows: signed in without a usable token is out of reach, not
     * signed out. Plain http, even the Tailscale kind HermesEndpoint allows, is never used, token or not.
     */
    private val offConnections = listOf(
        null to SIGNED_OUT,
        HermesConnection(signedIn = false, baseUrl = "https://hermes.example.com", accessToken = "", expiresAtMs = 0) to SIGNED_OUT,
        HermesConnection(signedIn = true, baseUrl = "https://hermes.example.com", accessToken = "", expiresAtMs = 0) to REACH,
        HermesConnection(signedIn = true, baseUrl = "http://hermes.example.com", accessToken = "fake", expiresAtMs = 0) to REACH,
        HermesConnection(signedIn = true, baseUrl = "http://box.tail1234.ts.net:9119", accessToken = "fake", expiresAtMs = 0) to PLAIN_HTTP,
        HermesConnection(signedIn = true, baseUrl = "http://100.101.102.103:9119", accessToken = "", expiresAtMs = 0) to PLAIN_HTTP,
        HermesConnection(signedIn = false, baseUrl = "http://box.tail1234.ts.net:9119", accessToken = "", expiresAtMs = 0) to SIGNED_OUT,
    )

    @Test
    fun attachAskApprove_neverBuildAGatewayWhileOff() = runTest {
        for ((connection, line) in offConnections) {
            val hermes = ask(CoroutineScope(StandardTestDispatcher(testScheduler)), connection)
            hermes.attach()
            advanceUntilIdle()
            assertEquals("attach $connection", line, hermes.state.value.error)
            hermes.ask("What's the weather?")
            advanceUntilIdle()
            val exchange = hermes.state.value.exchanges.single()
            assertEquals(ExchangeStatus.Failed, exchange.status)
            assertEquals(line, exchange.note)
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
