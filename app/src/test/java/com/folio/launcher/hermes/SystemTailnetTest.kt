package com.folio.launcher.hermes

import android.app.Application
import android.net.ConnectivityManager
import android.net.IpPrefix
import android.net.LinkProperties
import android.net.NetworkCapabilities
import android.net.RouteInfo
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.net.InetAddress
import java.net.UnknownHostException
import okhttp3.OkHttpClient
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowNetworkCapabilities
import org.robolectric.util.ReflectionHelpers
import org.robolectric.util.ReflectionHelpers.ClassParameter

/**
 * The tailnet as production asks Android for it (`Tailnet.of`): only a VPN that routes into the
 * tailnet counts. Carrier or hotel networks are often addressed from 100.64.0.0/10 too, and their
 * own link route looks just like a route into the tailnet.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class SystemTailnetTest {
    private val app: Application = ApplicationProvider.getApplicationContext()
    private val cm = app.getSystemService(ConnectivityManager::class.java)

    /** Folio's network: carried over [transport], with [routes] (prefix, length, RouteInfo type). */
    private fun network(transport: Int, vararg routes: Triple<String, Int, Int>) {
        val network = checkNotNull(cm.activeNetwork)
        val caps = ShadowNetworkCapabilities.newInstance()
        shadowOf(caps).addTransportType(transport)
        shadowOf(cm).setNetworkCapabilities(network, caps)
        val links = LinkProperties()
        routes.forEach { (prefix, length, type) -> links.addRoute(route(prefix, length, type)) }
        shadowOf(cm).setLinkProperties(network, links)
    }

    /** RouteInfo's constructors are system API. */
    private fun route(prefix: String, length: Int, type: Int): RouteInfo = ReflectionHelpers.callConstructor(
        RouteInfo::class.java,
        ClassParameter.from(IpPrefix::class.java, IpPrefix(InetAddress.getByName(prefix), length)),
        ClassParameter.from(InetAddress::class.java, null),
        ClassParameter.from(String::class.java, null),
        ClassParameter.from(Int::class.javaPrimitiveType!!, type),
    )

    @Test
    fun aNetworkInTheTailnetsRange_thatIsntAVpn_isNotTheTailnet() {
        // CGNAT Wi-Fi: its own link route sits inside 100.64.0.0/10.
        network(NetworkCapabilities.TRANSPORT_WIFI, Triple("100.64.4.0", 22, RouteInfo.RTN_UNICAST))
        val tailnet = Tailnet.of(app)
        assertFalse(tailnet.isUp())
        // And nothing is looked up through it: a café resolver's answer never gets a token.
        try {
            tailnet.bind(OkHttpClient()).dns.lookup("box.tail1234.ts.net")
            fail("expected UnknownHostException")
        } catch (_: UnknownHostException) {
        }
    }

    @Test
    fun aVpnRoutingAPeer_isTheTailnet() {
        network(NetworkCapabilities.TRANSPORT_VPN, Triple("100.101.102.103", 32, RouteInfo.RTN_UNICAST))
        assertTrue(Tailnet.of(app).isUp())
    }

    @Test
    fun aVpnThatOnlyKeepsTheTailnetOut_isNotTheTailnet() {
        // A range excluded from the VPN (a throw route) goes past the tunnel.
        network(NetworkCapabilities.TRANSPORT_VPN, Triple("100.64.0.0", 10, RouteInfo.RTN_THROW))
        assertFalse(Tailnet.of(app).isUp())
    }
}
