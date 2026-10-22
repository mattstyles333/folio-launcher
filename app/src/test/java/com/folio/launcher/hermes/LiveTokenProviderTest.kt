package com.folio.launcher.hermes

import com.folio.hermes.GatewayUnavailableException
import com.folio.hermes.HermesAuthException
import com.folio.hermes.HermesEndpoint
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class LiveTokenProviderTest {
    private val home = HermesEndpoint.of("https://hermes.example.com")

    private fun conn(token: String, signedIn: Boolean = true, base: String = "https://hermes.example.com") =
        HermesConnection(signedIn, base, token, expiresAtMs = 1_000L)

    private suspend fun expectSignIn(block: suspend () -> Unit): String {
        try {
            block()
        } catch (e: HermesAuthException.SignInRequired) {
            return e.message.orEmpty()
        }
        fail("expected SignInRequired")
        return ""
    }

    @Test
    fun handsOutTheLiveToken() = runTest {
        val provider = LiveTokenProvider(home, { conn("fake-token-1") })
        assertEquals("fake-token-1", provider.accessToken())
    }

    private suspend fun expectUnavailable(block: suspend () -> Unit): String {
        try {
            block()
        } catch (e: GatewayUnavailableException) {
            return e.message.orEmpty()
        }
        fail("expected GatewayUnavailableException")
        return ""
    }

    @Test
    fun signedOut_needsSignIn() = runTest {
        assertEquals(LiveTokenProvider.SIGNED_OUT, expectSignIn { LiveTokenProvider(home, { conn("t", signedIn = false) }).accessToken() })
    }

    @Test
    fun noAnswerOrNoTokenYet_isOutOfReachNotSignedOut() = runTest {
        // Hermes Live not answering, or signed in but unable to renew (offline, server down): the
        // gateway backs off and tries again instead of stopping on "signed out".
        expectUnavailable { LiveTokenProvider(home, { null }).accessToken() }
        expectUnavailable { LiveTokenProvider(home, { conn("") }).accessToken() }
        expectUnavailable { LiveTokenProvider(home, { conn("fake-old") }, { conn("") }).refreshAfterUnauthorized("fake-old") }
    }

    @Test
    fun neverSendsAnotherServersToken() = runTest {
        val provider = LiveTokenProvider(home, { conn("fake-other", base = "https://elsewhere.example.com") })
        assertEquals(LiveTokenProvider.MOVED, expectSignIn { provider.accessToken() })
    }

    @Test
    fun after401_requeriesAndStopsOnTheSameToken() = runTest {
        var token = "fake-old"
        val provider = LiveTokenProvider(home, { conn(token) })
        assertEquals(LiveTokenProvider.REFUSED, expectSignIn { provider.refreshAfterUnauthorized("fake-old") })
        token = "fake-new"
        assertEquals("fake-new", provider.refreshAfterUnauthorized("fake-old"))
    }

    @Test
    fun after401_asksHermesLiveToRenew() = runTest {
        var renewals = 0
        val provider = LiveTokenProvider(
            home,
            source = { conn("fake-old") },
            renewed = {
                renewals++
                conn("fake-renewed")
            },
        )
        assertEquals("fake-old", provider.accessToken())
        assertEquals(0, renewals)
        assertEquals("fake-renewed", provider.refreshAfterUnauthorized("fake-old"))
        assertEquals(1, renewals)
    }

    @Test
    fun plainHttp_noTokenNoRenewal_andHermesLiveIsNeverAsked() = runTest {
        val box = "http://box.tail1234.ts.net:9119"
        var asked = 0
        var renewals = 0
        val provider = LiveTokenProvider(
            HermesEndpoint.of(box),
            source = { asked++; conn("fake-token", base = box) },
            renewed = { renewals++; conn("fake-renewed", base = box) },
        )
        // It stops rather than retrying, and Hermes Live is never asked, so it never renews (its
        // refresh token, then its password) towards whatever answers that name.
        assertEquals(LiveTokenProvider.PLAIN_HTTP, expectSignIn { provider.accessToken() })
        assertEquals(LiveTokenProvider.PLAIN_HTTP, expectSignIn { provider.refreshAfterUnauthorized("fake-token") })
        assertEquals(0, asked)
        assertEquals(0, renewals)
    }

    @Test
    fun hermesLiveNowOnPlainHttp_getsNoToken_andIsToldWhatToChange() = runTest {
        val plain: suspend () -> HermesConnection? = { conn("fake-token", base = "http://box.tail1234.ts.net:9119") }
        assertEquals(LiveTokenProvider.PLAIN_HTTP, expectSignIn { LiveTokenProvider(home, plain).accessToken() })
        assertEquals(LiveTokenProvider.PLAIN_HTTP, expectSignIn { LiveTokenProvider(home, { conn("fake-old") }, plain).refreshAfterUnauthorized("fake-old") })
        // With no token right now as well: it stops instead of waiting for one.
        val tailnetIp: suspend () -> HermesConnection? = { conn("", base = "http://100.101.102.103:9119") }
        assertEquals(LiveTokenProvider.PLAIN_HTTP, expectSignIn { LiveTokenProvider(home, tailnetIp).accessToken() })
    }

    @Test
    fun connection_toStringNeverPrintsTheToken() {
        val text = conn("fake-secret-value").toString()
        assertFalse(text.contains("fake-secret-value"))
        assertTrue(text.contains("hermes.example.com"))
    }

    @Test
    fun connection_usableOnlyOverHttps() {
        assertTrue(conn("t").usable)
        assertFalse(conn("t").plainHttp)
        assertFalse(conn("t", base = "http://hermes.example.com").usable)
        // The plain http HermesEndpoint allows (a Tailscale name or address) is never usable in Folio.
        for (base in listOf("http://box.tail1234.ts.net:9119", "http://100.101.102.103:9119")) {
            assertTrue(base, conn("t", base = base).plainHttp)
            assertFalse(base, conn("t", base = base).usable)
        }
        assertFalse(conn("t", base = "").usable)
    }
}
