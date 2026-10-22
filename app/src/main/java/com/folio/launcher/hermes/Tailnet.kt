package com.folio.launcher.hermes

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.RouteInfo
import android.os.Build
import java.io.IOException
import java.net.InetAddress
import java.net.Proxy
import java.net.Socket
import java.net.UnknownHostException
import javax.net.SocketFactory
import okhttp3.OkHttpClient

/**
 * Tailscale, for a Hermes on plain http. Hermes Live may point at `http://<box>.<tailnet>.ts.net`,
 * which only Tailscale keeps private: off the tunnel, a Wi-Fi resolver can answer that name with
 * any address. So for such a server no token goes out unless [isUp], and [bind] looks the name up
 * through Tailscale's VPN and dials there, only tailnet addresses that VPN carries, never by proxy.
 */
interface Tailnet {
    /** True while Folio's traffic goes through a VPN that routes the tailnet. */
    fun isUp(): Boolean

    /** [base], looking up and dialling only through the tailnet. */
    fun bind(base: OkHttpClient): OkHttpClient

    /**
     * One route of Folio's network: [prefix]/[length]; [unicast] unless it keeps traffic out of the
     * tunnel (an excluded or unreachable range).
     */
    data class Route(val prefix: InetAddress, val length: Int, val unicast: Boolean = true)

    companion object {
        /** Nothing to ask (the default): a Hermes on plain http is never reached. */
        val Off: Tailnet = object : Tailnet {
            override fun isUp() = false
            override fun bind(base: OkHttpClient): OkHttpClient =
                base.newBuilder().dns { throw UnknownHostException(LiveTokenProvider.TAILNET) }.build()
        }

        /** Tailscale as Android sees Folio's network (ACCESS_NETWORK_STATE). */
        fun of(context: Context): Tailnet = SystemTailnet(context.applicationContext)

        /** Tailscale's ranges, 100.64.0.0/10 and fd7a:115c:a1e0::/48 (literals: nothing is looked up). */
        private val ranges = listOf(
            Route(InetAddress.getByName("100.64.0.0"), 10),
            Route(InetAddress.getByName("fd7a:115c:a1e0::"), 48),
        )

        /** [address] is a Tailscale address. */
        fun contains(address: InetAddress): Boolean = ranges.any { it.covers(address) }

        /** Some of [routes] sends traffic into the tailnet: a peer's /32, Tailscale's /48, or the whole /10. */
        fun routedBy(routes: List<Route>): Boolean =
            routes.any { route -> route.unicast && ranges.any { it.length <= route.length && it.covers(route.prefix) } }

        /**
         * The [resolved] addresses a Hermes on plain http may be dialled at: tailnet addresses whose
         * most specific route in [routes] goes into the tunnel. A VPN hands anything it doesn't route to
         * Wi-Fi or mobile data, so a tailnet-looking address alone isn't enough. Throws when none is left.
         */
        fun addresses(resolved: List<InetAddress>, routes: List<Route>): List<InetAddress> =
            resolved.filter { address ->
                contains(address) && routes.filter { it.covers(address) }.maxByOrNull { it.length }?.unicast == true
            }.ifEmpty { throw UnknownHostException("No tailnet address for Hermes.") }

        /** The first [Route.length] bits of [address] are this route's (same family only). */
        private fun Route.covers(address: InetAddress): Boolean {
            val a = address.address
            val p = prefix.address
            if (a.size != p.size || length !in 0..a.size * 8) return false
            for (bit in 0 until length) {
                val mask = 0x80 ushr (bit % 8)
                if ((a[bit / 8].toInt() and mask) != (p[bit / 8].toInt() and mask)) return false
            }
            return true
        }
    }
}

/** [Tailnet] from ConnectivityManager: Folio's network, while it is Tailscale's VPN. */
private class SystemTailnet(private val context: Context) : Tailnet {
    private val connectivity: ConnectivityManager? by lazy { context.getSystemService(ConnectivityManager::class.java) }

    /**
     * Folio's network and its routes, while it is a VPN that routes the tailnet. A VPN that applies
     * to Folio is always its default network, so the active one is the only one to check.
     */
    private fun vpn(): Pair<Network, List<Tailnet.Route>>? {
        val cm = connectivity ?: return null
        return try {
            val network = cm.activeNetwork ?: return null
            if (cm.getNetworkCapabilities(network)?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) != true) return null
            val routes = cm.getLinkProperties(network)?.routes.orEmpty().map(::route)
            if (Tailnet.routedBy(routes)) network to routes else null
        } catch (e: SecurityException) {
            // No telling without ACCESS_NETWORK_STATE: as good as off. Never thrown on OkHttp's
            // threads, where it would take Folio down.
            null
        }
    }

    override fun isUp(): Boolean = vpn() != null

    override fun bind(base: OkHttpClient): OkHttpClient = base.newBuilder()
        // A proxy would carry the request out of the tunnel.
        .proxy(Proxy.NO_PROXY)
        .dns { host ->
            val (network, routes) = vpn() ?: throw UnknownHostException(LiveTokenProvider.TAILNET)
            Tailnet.addresses(network.getAllByName(host).toList(), routes)
        }
        .socketFactory(Sockets())
        .build()

    /** Each socket bound to Tailscale's VPN as it is made, so the dial goes into the tunnel; none while it's off. */
    private inner class Sockets : SocketFactory() {
        private fun sockets(): SocketFactory = vpn()?.first?.socketFactory ?: throw IOException(LiveTokenProvider.TAILNET)

        override fun createSocket(): Socket = sockets().createSocket()

        override fun createSocket(host: String?, port: Int): Socket = sockets().createSocket(host, port)

        override fun createSocket(host: String?, port: Int, localHost: InetAddress?, localPort: Int): Socket =
            sockets().createSocket(host, port, localHost, localPort)

        override fun createSocket(host: InetAddress?, port: Int): Socket = sockets().createSocket(host, port)

        override fun createSocket(address: InetAddress?, port: Int, localAddress: InetAddress?, localPort: Int): Socket =
            sockets().createSocket(address, port, localAddress, localPort)
    }

    private fun route(info: RouteInfo) = Tailnet.Route(
        prefix = info.destination.address,
        length = info.destination.prefixLength,
        // Ranges excluded from a VPN (throw routes) came with Android 13, as did getType(); before
        // it, no VPN route lets traffic out of the tunnel.
        unicast = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU || info.type == RouteInfo.RTN_UNICAST,
    )
}
