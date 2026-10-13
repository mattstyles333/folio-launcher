package com.folio.launcher.hermes

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

    @Test
    fun signedOutOrAbsent_needsSignIn() = runTest {
        assertEquals(LiveTokenProvider.SIGNED_OUT, expectSignIn { LiveTokenProvider(home, { null }).accessToken() })
        assertEquals(LiveTokenProvider.SIGNED_OUT, expectSignIn { LiveTokenProvider(home, { conn("t", signedIn = false) }).accessToken() })
        assertEquals(LiveTokenProvider.SIGNED_OUT, expectSignIn { LiveTokenProvider(home, { conn("") }).accessToken() })
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
        val provider = LiveTokenProvider(home, source = { conn("fake-old") }) {
            renewals++
            conn("fake-renewed")
        }
        assertEquals("fake-old", provider.accessToken())
        assertEquals(0, renewals)
        assertEquals("fake-renewed", provider.refreshAfterUnauthorized("fake-old"))
        assertEquals(1, renewals)
    }

    @Test
    fun connection_toStringNeverPrintsTheToken() {
        val text = conn("fake-secret-value").toString()
        assertFalse(text.contains("fake-secret-value"))
        assertTrue(text.contains("hermes.example.com"))
    }

    @Test
    fun connection_usableOnlyWithAnAllowedAddress() {
        assertTrue(conn("t").usable)
        assertFalse(conn("t", base = "http://hermes.example.com").usable)
        assertTrue(conn("t", base = "http://box.tail1234.ts.net:9119").usable)
        assertFalse(conn("t", base = "").usable)
    }
}
