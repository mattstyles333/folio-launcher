package com.folio.launcher.hermes

import java.net.InetAddress
import java.net.UnknownHostException
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** The address rules for a Hermes on plain http: only tailnet addresses Tailscale's VPN carries. */
class TailnetTest {
    /** Literals only: nothing is looked up. */
    private fun ip(literal: String): InetAddress = InetAddress.getByName(literal)

    private fun route(prefix: String, length: Int, unicast: Boolean = true) = Tailnet.Route(ip(prefix), length, unicast)

    /** Tailscale's VPN on Android: a /32 per peer, Quad100 and the IPv6 /48. */
    private val tailscale = listOf(
        route("100.101.102.103", 32),
        route("100.100.100.100", 32),
        route("fd7a:115c:a1e0::", 48),
    )

    private fun expectNoAddress(block: () -> Unit) {
        try {
            block()
        } catch (e: UnknownHostException) {
            return
        }
        fail("expected UnknownHostException")
    }

    @Test
    fun tailnetAddresses_areTailscalesRangesOnly() {
        assertTrue(Tailnet.contains(ip("100.64.0.1")))
        assertTrue(Tailnet.contains(ip("100.101.102.103")))
        assertTrue(Tailnet.contains(ip("100.127.255.254")))
        assertTrue(Tailnet.contains(ip("fd7a:115c:a1e0:ab12:4843:cd96:6258:b240")))
        assertFalse(Tailnet.contains(ip("100.63.255.255")))
        assertFalse(Tailnet.contains(ip("100.128.0.1")))
        assertFalse(Tailnet.contains(ip("192.168.1.20")))
        assertFalse(Tailnet.contains(ip("127.0.0.1")))
        assertFalse(Tailnet.contains(ip("fd7a:115c:a1e1::1")))
        assertFalse(Tailnet.contains(ip("2001:db8::1")))
    }

    @Test
    fun aVpnIsTheTailnet_onlyWithARouteIntoIt() {
        assertTrue(Tailnet.routedBy(tailscale))
        assertTrue(Tailnet.routedBy(listOf(route("100.64.0.0", 10))))
        // With an exit node Tailscale also routes everything; its peer routes still say it's Tailscale.
        assertTrue(Tailnet.routedBy(listOf(route("0.0.0.0", 0), route("100.101.102.103", 32))))
        // Another VPN that sends everything (Secure Wi-Fi, say) is not the tailnet.
        assertFalse(Tailnet.routedBy(listOf(route("0.0.0.0", 0), route("::", 0))))
        assertFalse(Tailnet.routedBy(listOf(route("10.8.0.0", 24))))
        assertFalse(Tailnet.routedBy(listOf(route("100.101.102.103", 32, unicast = false))))
        assertFalse(Tailnet.routedBy(emptyList()))
    }

    @Test
    fun aRogueResolversAnswer_isNeverDialled() {
        // A café resolver answers the ts.net name with its own LAN address: dropped; the tailnet one stays.
        assertEquals(listOf(ip("100.101.102.103")), Tailnet.addresses(listOf(ip("192.168.1.20"), ip("100.101.102.103")), tailscale))
        assertEquals(listOf(ip("fd7a:115c:a1e0::9")), Tailnet.addresses(listOf(ip("fd7a:115c:a1e0::9")), tailscale))
        expectNoAddress { Tailnet.addresses(listOf(ip("192.168.1.20")), tailscale) }
        expectNoAddress { Tailnet.addresses(emptyList(), tailscale) }
    }

    @Test
    fun aTailnetAddressTheVpnDoesNotRoute_isNeverDialled() {
        // Not a peer: the VPN would hand it to Wi-Fi, in the clear.
        expectNoAddress { Tailnet.addresses(listOf(ip("100.90.1.1")), tailscale) }
        // A LAN range carved out of the tunnel (a throw route) wins over a wider route into it.
        val excluded = listOf(route("100.64.0.0", 10), route("100.90.0.0", 16, unicast = false))
        expectNoAddress { Tailnet.addresses(listOf(ip("100.90.1.1")), excluded) }
        assertEquals(listOf(ip("100.101.1.1")), Tailnet.addresses(listOf(ip("100.101.1.1")), excluded))
    }

    @Test
    fun off_neverReachesAHermesOnPlainHttp() {
        assertFalse(Tailnet.Off.isUp())
        val client = Tailnet.Off.bind(OkHttpClient())
        expectNoAddress { client.dns.lookup("box.tail1234.ts.net") }
    }
}
