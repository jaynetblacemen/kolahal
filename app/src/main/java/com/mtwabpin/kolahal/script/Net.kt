package com.mtwabpin.kolahal.script

import android.os.Build
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketAddress
import java.util.concurrent.TimeUnit
import javax.net.SocketFactory
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Interceptor
import okhttp3.OkHttpClient

/** One HTTP client for the whole app (scripts, feeds, currency rates). */
object Net {
    const val USER_AGENT = "Kolahal-Launcher/0.1 (+https://github.com/mtwabpin)"

    /** The longest a whole request may take, redirects included (a script's own run may set less). */
    const val CALL_TIMEOUT_MS = 30_000L

    val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .callTimeout(CALL_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            .socketFactory(GuardedSocketFactory(publicOnly = false))
            .addNetworkInterceptor(reachGuard)
            .addNetworkInterceptor(hopGuard)
            .build()
    }

    /**
     * For [Reach.PUBLIC] requests (scripts without `@uses lan`, pictures in the log): refuses this
     * phone and the local network before connecting, redirects included. The [reachGuard] only sees
     * a hop once it is connected, which would still let a script learn which local ports are open.
     */
    val publicClient: OkHttpClient by lazy {
        // Straight to the site: through a proxy, the socket's address would be the proxy's, and the
        // site behind it never checked.
        client.newBuilder().socketFactory(GuardedSocketFactory(publicOnly = true)).proxy(java.net.Proxy.NO_PROXY).build()
    }

    /**
     * The networks this phone is on (its addresses and each link's prefix length): on an IPv6 home
     * network, or one on public IPv4, the router and the other devices have public addresses that
     * are just as local. A VPN isn't one of them: its routes are what it carries, often the internet
     * itself in parts (`0.0.0.0/1`), and the networks under it stay local. Kept by [watchLinks], in
     * each process.
     */
    @Volatile internal var links: List<Pair<ByteArray, Int>> = emptyList()

    /** Follows the links of every network but a VPN ([links]), from now on. */
    fun watchLinks(context: android.content.Context) {
        val cm = context.getSystemService(android.net.ConnectivityManager::class.java) ?: return
        val request = android.net.NetworkRequest.Builder()
            .addCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .addCapability(android.net.NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
            .build()
        // Callbacks come one at a time.
        val byNetwork = HashMap<android.net.Network, List<Pair<ByteArray, Int>>>()
        runCatching {
            cm.registerNetworkCallback(request, object : android.net.ConnectivityManager.NetworkCallback() {
                override fun onLinkPropertiesChanged(network: android.net.Network, lp: android.net.LinkProperties) {
                    // Its own addresses' prefixes, and routes it reaches without a gateway (directly connected).
                    byNetwork[network] = lp.linkAddresses.map { it.address.address to it.prefixLength } +
                        lp.routes.filter { it.gateway?.isAnyLocalAddress != false && it.destination.prefixLength > 0 }.map { it.destination.address.address to it.destination.prefixLength }
                    links = byNetwork.values.flatten()
                }

                override fun onLost(network: android.net.Network) {
                    byNetwork.remove(network)
                    links = byNetwork.values.flatten()
                }
            })
        }
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

    /**
     * Whether Kolahal may reach the local network: Android 17's runtime permission, set at start-up
     * in each process. Before Android 17 there is nothing to ask for.
     */
    @Volatile var localNetworkAllowed: () -> Boolean = { true }

    /** A connection Android would block (local network, no permission), refused at once instead of timing out. */
    class LocalNetworkBlocked(val host: String) :
        IOException("$host is on your local network, which Kolahal isn't allowed to reach yet (run 'local-network')")

    /** Addresses Android 17 keeps behind the local network permission; this phone itself is not one of them. */
    private fun needsPermission(a: InetAddress): Boolean {
        if (Build.VERSION.SDK_INT < 37 || a.isLoopbackAddress) return false
        if (a.isSiteLocalAddress || a.isLinkLocalAddress || a.isMulticastAddress) return true
        return a.address.size == 16 && (a.address[0].toInt() and 0xfe) == 0xfc
    }

    // Android lets a blocked connection hang until the connect timeout; this says why straight away.
    // [publicOnly]: no connection to this phone or the local network at all.
    private class GuardedSocket(private val publicOnly: Boolean) : Socket() {
        override fun connect(endpoint: SocketAddress?, timeout: Int) {
            val target = endpoint as? InetSocketAddress
            val address = target?.address
            if (publicOnly && address != null && isLocal(address)) throw IOException("${target.hostString} points to this phone or the local network: not allowed here")
            if (address != null && needsPermission(address) && !localNetworkAllowed()) throw LocalNetworkBlocked(target.hostString)
            super.connect(endpoint, timeout)
        }
    }

    private class GuardedSocketFactory(private val publicOnly: Boolean) : SocketFactory() {
        private fun socket() = GuardedSocket(publicOnly)
        override fun createSocket(): Socket = socket()
        override fun createSocket(host: String, port: Int): Socket = socket().apply { connect(InetSocketAddress(host, port)) }
        override fun createSocket(host: String, port: Int, localHost: InetAddress, localPort: Int): Socket =
            socket().apply { bind(InetSocketAddress(localHost, localPort)); connect(InetSocketAddress(host, port)) }
        override fun createSocket(host: InetAddress, port: Int): Socket = socket().apply { connect(InetSocketAddress(host, port)) }
        override fun createSocket(address: InetAddress, port: Int, localAddress: InetAddress, localPort: Int): Socket =
            socket().apply { bind(InetSocketAddress(localAddress, localPort)); connect(InetSocketAddress(address, port)) }
    }

    /** How far a request may reach. Checked on every hop once connected, redirects included. */
    enum class Reach {
        /** Anywhere, this phone and the local network included. */
        ANY,
        /** Public addresses only (scripts without `@uses lan`, pictures in the log). */
        PUBLIC,
        /** The local network only if the first hop was local: a feed on a home server may stay home, a public one can't redirect into it. */
        SAME_SIDE,
    }

    /** Request tag for [Reach]; remembers whether the first hop was local. */
    class ReachTag(val reach: Reach) {
        @Volatile internal var firstLocal: Boolean? = null
    }

    /** Tags [builder] with [reach]. */
    fun reach(builder: okhttp3.Request.Builder, reach: Reach): okhttp3.Request.Builder =
        builder.tag(ReachTag::class.java, ReachTag(reach))

    /** A check every hop of a request must pass before it is sent, redirects included ([hopGuard]). */
    fun interface HopCheck {
        /** Throws (an IOException) to stop [hop]. */
        fun check(hop: okhttp3.Request)
    }

    private val hopGuard = Interceptor { chain ->
        chain.request().tag(HopCheck::class.java)?.check(chain.request())
        chain.proceed(chain.request())
    }

    // The address a hop actually connected to: covers names, IP literals and redirects alike.
    private val reachGuard = Interceptor { chain ->
        val tag = chain.request().tag(ReachTag::class.java)
        val address = chain.connection()?.route()?.socketAddress?.address
        if (tag != null && tag.reach != Reach.ANY && address != null) {
            val local = isLocal(address)
            val first = tag.firstLocal ?: local.also { tag.firstLocal = it }
            if (local && (tag.reach == Reach.PUBLIC || !first)) {
                throw IOException("${chain.request().url.host} points to this phone or the local network: not allowed here")
            }
        }
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
