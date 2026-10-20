package com.mtwabpin.kolahal.script

import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

/** One HTTP client for the whole app (scripts, feeds, currency rates). */
object Net {
    const val USER_AGENT = "Kolahal-Launcher/0.1 (+https://github.com/mtwabpin)"

    val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .callTimeout(30, TimeUnit.SECONDS)
            .build()
    }

    /** Simple blocking GET returning the body, or throwing with the HTTP status. */
    fun get(url: String): String {
        val request = okhttp3.Request.Builder().url(url).header("User-Agent", USER_AGENT).build()
        client.newCall(request).execute().use { resp ->
            if (!resp.isSuccessful) throw IllegalStateException("HTTP ${resp.code}")
            return resp.body.string()
        }
    }
}
