package io.github.rscipher001.launcher.script

import android.content.Context
import io.github.rscipher001.launcher.data.KvStore
import io.github.rscipher001.launcher.data.LogContext
import io.github.rscipher001.launcher.data.LogKind
import io.github.rscipher001.launcher.data.LogRepository
import io.github.rscipher001.launcher.data.ScriptRepository
import io.github.rscipher001.launcher.notify.Notifier
import okhttp3.Headers.Companion.toHeaders
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

/**
 * The launcher-side API exposed to scripts. Engine-agnostic: the Lua and JS bindings both
 * translate their arguments into these calls, so scripts behave the same in either language.
 *
 *   log(text, ctx)              -> append a line to the terminal (ctx drives tap / long-press actions)
 *   notify(title, body, ctx)    -> post a notification (if enabled for the script)
 *   http.get(url, headers)      -> { status, ok, body, headers }
 *   http.request(opts)          -> same, opts = { url, method, body, headers, contentType }
 *   rss.fetch(url) / rss.parse(xml) -> { title, link, items = [{ title, link, id, date, summary }] }
 *   rss.fresh(url)              -> like fetch, but only items not returned by a previous call
 *   store.get(key) / store.set(key, value) -> per-script persistent strings
 *   json.encode(v) / json.decode(s)
 */
class HostApi(
    private val context: Context,
    val scriptName: String,
    private val scriptId: Long?,
    private val notifyEnabled: Boolean,
) {
    private val logs = LogRepository.get(context)
    private val store = KvStore(context, scriptId?.let(ScriptRepository::scopeOf) ?: "repl")

    var linesLogged = 0
        private set

    private val baseCtx: Map<String, String> =
        if (scriptId != null) mapOf(LogContext.SCRIPT to scriptName) else emptyMap()

    fun log(text: String, ctx: Map<*, *>? = null) {
        linesLogged++
        logs.add(LogKind.SCRIPT, text, scriptName, LogContext(baseCtx + Json.toStringMap(ctx)))
    }

    fun logError(text: String) {
        logs.add(LogKind.ERROR, text, scriptName, LogContext(baseCtx))
    }

    fun notify(title: String, body: String, ctx: Map<*, *>? = null): Boolean {
        val values = Json.toStringMap(ctx)
        if (!notifyEnabled) {
            log("[notify off] $title: $body", ctx)
            return false
        }
        val posted = Notifier.post(context, scriptName, title, body, values[LogContext.URL])
        if (!posted) log("[notify blocked: allow notifications] $title: $body", ctx)
        return posted
    }

    fun httpRequest(
        url: String,
        method: String = "GET",
        body: String? = null,
        contentType: String? = null,
        headers: Map<*, *>? = null,
    ): Map<String, Any?> {
        val h = Json.toStringMap(headers).toMutableMap()
        if (h.keys.none { it.equals("User-Agent", ignoreCase = true) }) h["User-Agent"] = USER_AGENT
        val reqBody = body?.toRequestBody((contentType ?: "text/plain; charset=utf-8").toMediaTypeOrNull())
        val request = Request.Builder()
            .url(url)
            .headers(h.toHeaders())
            .method(method.uppercase(), reqBody)
            .build()
        client.newCall(request).execute().use { resp ->
            return mapOf(
                "status" to resp.code,
                "ok" to resp.isSuccessful,
                "body" to resp.peekBody(MAX_BODY).string(),
                "headers" to resp.headers.associate { (k, v) -> k.lowercase() to v },
            )
        }
    }

    fun httpGet(url: String, headers: Map<*, *>? = null) = httpRequest(url, headers = headers)

    fun rssParse(xml: String): Map<String, Any?> = RssParser.parse(xml).toMap()

    fun rssFetch(url: String): Map<String, Any?> = fetchFeed(url).toMap()

    fun rssFresh(url: String): Map<String, Any?> {
        val feed = fetchFeed(url)
        val key = "rss.seen:$url"
        val seen = store.get(key)?.split('\n')?.toMutableList() ?: mutableListOf()
        val seenSet = seen.toHashSet()
        val fresh = feed.items.filter { it.id !in seenSet }
        seen.addAll(0, fresh.map { it.id.replace('\n', ' ') })
        store.set(key, seen.take(MAX_SEEN).joinToString("\n"))
        return feed.copy(items = fresh).toMap()
    }

    private fun fetchFeed(url: String): RssParser.Feed {
        val resp = httpGet(url)
        if (resp["ok"] != true) throw IllegalStateException("rss: HTTP ${resp["status"]} for $url")
        return RssParser.parse(resp["body"] as String)
    }

    fun storeGet(key: String): String? = store.get(key)

    fun storeSet(key: String, value: String?) = store.set(key, value)

    companion object {
        private const val USER_AGENT = "Kolahal-Launcher/0.1 (+https://github.com/rscipher001)"
        private const val MAX_BODY = 4L * 1024 * 1024
        private const val MAX_SEEN = 500

        private val client: OkHttpClient by lazy {
            OkHttpClient.Builder()
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(20, TimeUnit.SECONDS)
                .callTimeout(30, TimeUnit.SECONDS)
                .build()
        }
    }
}
