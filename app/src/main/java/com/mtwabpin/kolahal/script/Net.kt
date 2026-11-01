package com.mtwabpin.kolahal.script

import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketAddress
import java.util.concurrent.TimeUnit
import javax.net.SocketFactory
import kotlin.math.pow
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Interceptor
import okhttp3.OkHttpClient

/** One HTTP client for the whole app (scripts, feeds, pictures in the log, the script library, currency rates). */
object Net {
    const val USER_AGENT = "Kolahal-Launcher/0.1 (+https://github.com/mtwabpin)"

    /** The longest a whole request may take, redirects included (a script's own run may set less). */
    const val CALL_TIMEOUT_MS = 30_000L

    /**
     * Public addresses only: it refuses this phone and the local network before connecting, redirects
     * included. The [reachGuard] only sees a hop once it is connected, which would still let a script
     * learn which local ports are open.
     */
    val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .callTimeout(CALL_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            // Straight to the site: through a proxy, the socket's address would be the proxy's, and the
            // site behind it never checked.
            .proxy(java.net.Proxy.NO_PROXY)
            .socketFactory(GuardedSocketFactory())
            .addNetworkInterceptor(reachGuard)
            .addNetworkInterceptor(hopGuard)
            .build()
    }

    /** The one local address that's reached: a test's own server on this phone. Only tests set it. */
    @Volatile internal var testServer: InetSocketAddress? = null

    private fun refused(to: InetSocketAddress) = to.address?.let { isLocal(it) } == true && to != testServer

    /**
     * The networks this phone is on (its addresses and each link's prefix length): on an IPv6 home
     * network, or one on public IPv4, the router and the other devices have public addresses that
     * are just as local. So are a VPN's own addresses and, unless they carry the internet
     * ([narrowRoutes]), its routes; the networks under it stay local. Kept by [watchLinks], in each
     * process.
     */
    @Volatile internal var links: List<Pair<ByteArray, Int>> = emptyList()

    /**
     * [routes] but for an address family a VPN carries the internet over: half of IPv4 or more, or
     * half of IPv6's public range (2000::/3). That's often in parts (`0.0.0.0/1`, or all but the
     * private ranges), and none of it is local; a narrower VPN's routes are a network of its own.
     */
    internal fun narrowRoutes(routes: List<Pair<ByteArray, Int>>): List<Pair<ByteArray, Int>> =
        routes.groupBy { it.first.size }.filter { (size, family) -> family.sumOf { 0.5.pow(it.second) } < if (size == 4) 0.5 else 1.0 / 16 }.values.flatten()

    /** Follows the links of every network ([links]), from now on. */
    fun watchLinks(context: android.content.Context) {
        val cm = context.getSystemService(android.net.ConnectivityManager::class.java) ?: return
        // Callbacks come one at a time, both watches' too.
        val byNetwork = HashMap<android.net.Network, List<Pair<ByteArray, Int>>>()
        fun watch(request: android.net.NetworkRequest, linksOf: (android.net.LinkProperties, List<Pair<ByteArray, Int>>) -> List<Pair<ByteArray, Int>>) = runCatching {
            cm.registerNetworkCallback(request, object : android.net.ConnectivityManager.NetworkCallback() {
                override fun onLinkPropertiesChanged(network: android.net.Network, lp: android.net.LinkProperties) {
                    // Routes it reaches without a gateway (directly connected).
                    byNetwork[network] = linksOf(lp, lp.routes.filter { it.gateway?.isAnyLocalAddress != false }.map { it.destination.address.address to it.destination.prefixLength })
                    links = byNetwork.values.flatten()
                }

                override fun onLost(network: android.net.Network) {
                    byNetwork.remove(network)
                    links = byNetwork.values.flatten()
                }
            })
        }
        watch(
            android.net.NetworkRequest.Builder()
                .addCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .addCapability(android.net.NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
                .build(),
        ) { lp, routes -> lp.linkAddresses.map { it.address.address to it.prefixLength } + routes.filter { it.second > 0 } }
        // A VPN that applies to this app: only the phone's own address on it, not its neighbours by prefix.
        watch(
            android.net.NetworkRequest.Builder()
                .addTransportType(android.net.NetworkCapabilities.TRANSPORT_VPN)
                .removeCapability(android.net.NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
                .build(),
        ) { lp, routes -> lp.linkAddresses.map { it.address.address.let { a -> a to a.size * 8 } } + narrowRoutes(routes) }
    }

    /** Whether [address] is in the network [prefix]/[length]: the same family, and the first [length] bits alike. */
    fun inPrefix(address: ByteArray, prefix: ByteArray, length: Int): Boolean {
        if (address.size != prefix.size || length <= 0 || length > address.size * 8) return false
        val whole = length / 8
        for (i in 0 until whole) if (address[i] != prefix[i]) return false
        val rest = length % 8
        if (rest == 0) return true
        val mask = (0xff shl (8 - rest)) and 0xff
        return (address[whole].toInt() and mask) == (prefix[whole].toInt() and mask)
    }

    // No connection to this phone or the local network at all. Android 17 would block most of them,
    // and let them hang until the connect timeout; this says why straight away.
    private class GuardedSocket : Socket() {
        override fun connect(endpoint: SocketAddress?, timeout: Int) {
            val target = endpoint as? InetSocketAddress
            if (target != null && refused(target)) throw IOException("${target.hostString} points to this phone or the local network: not allowed here")
            super.connect(endpoint, timeout)
        }
    }

    private class GuardedSocketFactory : SocketFactory() {
        private fun socket() = GuardedSocket()
        override fun createSocket(): Socket = socket()
        override fun createSocket(host: String, port: Int): Socket = socket().apply { connect(InetSocketAddress(host, port)) }
        override fun createSocket(host: String, port: Int, localHost: InetAddress, localPort: Int): Socket =
            socket().apply { bind(InetSocketAddress(localHost, localPort)); connect(InetSocketAddress(host, port)) }
        override fun createSocket(host: InetAddress, port: Int): Socket = socket().apply { connect(InetSocketAddress(host, port)) }
        override fun createSocket(address: InetAddress, port: Int, localAddress: InetAddress, localPort: Int): Socket =
            socket().apply { bind(InetSocketAddress(localAddress, localPort)); connect(InetSocketAddress(address, port)) }
    }

    /** A check every hop of a request must pass before it is sent, redirects included ([hopGuard]). */
    fun interface HopCheck {
        /** Throws (an IOException) to stop [hop]. */
        fun check(hop: okhttp3.Request)
    }

    private val hopGuard = Interceptor { chain ->
        chain.request().tag(HopCheck::class.java)?.check(chain.request())
        chain.proceed(chain.request())
    }

    // The address a hop actually connected to: covers names, IP literals, redirects and a connection
    // kept from before the phone changed networks alike.
    private val reachGuard = Interceptor { chain ->
        val address = chain.connection()?.route()?.socketAddress
        if (address != null && refused(address)) throw IOException("${chain.request().url.host} points to this phone or the local network: not allowed here")
        chain.proceed(chain.request())
    }

    /**
     * Loopback, private (10/8, 172.16/12, 192.168/16, fc00::/7), link-local, multicast, the
     * carrier-grade NAT range 100.64/10 that VPNs like Tailscale use, and the networks this phone
     * is on ([links]), whatever their addresses.
     */
    fun isLocal(a: InetAddress): Boolean {
        if (a.isLoopbackAddress || a.isSiteLocalAddress || a.isLinkLocalAddress || a.isAnyLocalAddress || a.isMulticastAddress) return true
        val b = a.address
        val private = when (b.size) {
            4 -> (b[0].toInt() and 0xff) == 100 && (b[1].toInt() and 0xc0) == 64
            16 -> (b[0].toInt() and 0xfe) == 0xfc
            else -> false
        }
        return private || links.any { (prefix, length) -> inPrefix(b, prefix, length) }
    }

    /** [link] if it is an http(s) link, normalised; null for any other scheme (tel:, upi:, intent:, file: …). */
    fun webUrl(link: String?): String? = link?.trim()?.toHttpUrlOrNull()?.toString()

    /** Simple blocking GET returning the body, or throwing with the HTTP status. */
    fun get(url: String): String {
        val request = okhttp3.Request.Builder().url(url).header("User-Agent", USER_AGENT).build()
        client.newCall(request).execute().use { resp ->
            if (!resp.isSuccessful) throw IllegalStateException("HTTP ${resp.code}")
            return resp.body.string()
        }
    }
}
