package com.mtwabpin.kolahal.script

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import java.io.IOException
import java.net.InetAddress
import java.util.concurrent.TimeUnit

/** One HTTP client for the whole app (scripts, feeds, currency rates). */
object Net {
    const val USER_AGENT = "Kolahal-Launcher/0.1 (+https://github.com/mtwabpin)"

    val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .callTimeout(30, TimeUnit.SECONDS)
            .addNetworkInterceptor(reachGuard)
            .build()
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

    // The address a hop actually connected to: covers names, IP literals and redirects alike.
    private val reachGuard = Interceptor { chain ->
        val tag = chain.request().tag(ReachTag::class.java)
        val address = chain.connection()?.route()?.socketAddress?.address
        if (tag != null && tag.reach != Reach.ANY && address != null) {
            val local = isLocal(address)
            val first = tag.firstLocal ?: local.also { tag.firstLocal = it }
            if (local && (tag.reach == Reach.PUBLIC || !first)) {
                throw IOException("${chain.request().url.host} is this phone or the local network: not allowed here")
            }
        }
        chain.proceed(chain.request())
    }

    /**
     * Loopback, private (10/8, 172.16/12, 192.168/16, fc00::/7), link-local, multicast, and the
     * carrier-grade NAT range 100.64/10 that VPNs like Tailscale use.
     */
    fun isLocal(a: InetAddress): Boolean {
        if (a.isLoopbackAddress || a.isSiteLocalAddress || a.isLinkLocalAddress || a.isAnyLocalAddress || a.isMulticastAddress) return true
        val b = a.address
        return when (b.size) {
            4 -> (b[0].toInt() and 0xff) == 100 && (b[1].toInt() and 0xc0) == 64
            16 -> (b[0].toInt() and 0xfe) == 0xfc
            else -> false
        }
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
