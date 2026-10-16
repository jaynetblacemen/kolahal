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

    /** Visible side effects (log lines, status, notifications); a REPL run with none prints "nil". */
    var effects = 0
        private set

    /** A notify() call was dropped because notifications are blocked (reported once by the worker). */
    var notifyBlocked = false
        private set

    private val baseCtx: Map<String, String> =
        if (scriptId != null) mapOf(LogContext.SCRIPT to scriptName) else emptyMap()

    /** Saved scripts get a `── name · 22:40 ──` header before their first line of a run. */
    private var headerWritten = false

    fun log(text: String, ctx: Map<*, *>? = null) {
        effects++
        if (scriptId != null && !headerWritten) {
            headerWritten = true
            val time = java.text.DateFormat.getTimeInstance(java.text.DateFormat.SHORT).format(java.util.Date())
            logs.add(LogKind.INFO, "── $scriptName · $time ──", scriptName, LogContext(baseCtx + mapOf(LogContext.RUN_HEADER to "1")))
        }
        logs.add(LogKind.SCRIPT, text, scriptName, LogContext(baseCtx + Json.toStringMap(ctx)))
    }

    fun logError(text: String) {
        logs.add(LogKind.ERROR, text, scriptName, LogContext(baseCtx))
    }

    fun notify(title: String, body: String, ctx: Map<*, *>? = null): Boolean {
        effects++
        val values = Json.toStringMap(ctx)
        // With notifications off for this script, the script's own log lines already show the content.
        if (!notifyEnabled) return false
        val posted = Notifier.post(context, scriptName, title, body, values[LogContext.URL])
        if (!posted) notifyBlocked = true
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
        val m = method.uppercase()
        val reqBody = body?.toRequestBody((contentType ?: "text/plain; charset=utf-8").toMediaTypeOrNull())
            ?: if (m in METHODS_REQUIRING_BODY) ByteArray(0).toRequestBody() else null
        val request = Request.Builder()
            .url(url)
            .headers(h.toHeaders())
            .method(m, if (m != "GET" && m != "HEAD") reqBody else null)
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

    /** Seen-id updates from `rss.fresh`, applied by [commit] only if the script finishes successfully. */
    private val pendingSeen = LinkedHashMap<String, List<String>>()

    fun rssFresh(url: String): Map<String, Any?> {
        val feed = fetchFeed(url)
        val key = "rss.seen:$url"
        val seen = pendingSeen[key] ?: readSeen(key)
        val seenSet = seen.toHashSet()
        val fresh = feed.items.filter { it.id !in seenSet }
        pendingSeen[key] = (fresh.map { it.id } + seen).take(MAX_SEEN)
        return feed.copy(items = fresh).toMap()
    }

    private fun readSeen(key: String): List<String> {
        val raw = store.get(key) ?: return emptyList()
        return runCatching { (Json.parse(raw) as? List<*>)?.map { it.toString() } }.getOrNull()
            ?: raw.split('\n') // format used before ids were stored as JSON
    }

    /** Persists deferred state after a successful run (so a failing script re-sees its items). */
    fun commit() {
        pendingSeen.forEach { (key, ids) -> store.set(key, Json.stringify(ids)) }
        pendingSeen.clear()
    }

    private fun fetchFeed(url: String): RssParser.Feed {
        val resp = httpGet(url)
        if (resp["ok"] != true) throw IllegalStateException("rss: HTTP ${resp["status"]} for $url")
        return RssParser.parse(resp["body"] as String)
    }

    /** Pins a short line for this script into the launcher header; null or empty clears it. */
    fun status(text: String?) {
        effects++
        if (scriptId == null) {
            log("status() only works in saved scripts (it is keyed by script name)")
            return
        }
        val value = text?.lines()?.firstOrNull()?.take(STATUS_MAX)?.takeIf { it.isNotBlank() }
        KvStore(context, STATUS_SCOPE).set(scriptName, value)
        logs.onStatusChanged()
    }

    fun storeGet(key: String): String? = store.get(key)

    fun storeSet(key: String, value: String?) = store.set(key, value)

    companion object {
        private const val USER_AGENT = "Kolahal-Launcher/0.1 (+https://github.com/rscipher001)"
        private const val MAX_BODY = 4L * 1024 * 1024
        private const val MAX_SEEN = 500
        private const val STATUS_MAX = 80
        const val STATUS_SCOPE = "status"
        private val METHODS_REQUIRING_BODY = setOf("POST", "PUT", "PATCH", "PROPPATCH", "REPORT")

        private val client: OkHttpClient by lazy {
            OkHttpClient.Builder()
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(20, TimeUnit.SECONDS)
                .callTimeout(30, TimeUnit.SECONDS)
                .build()
        }
    }
}
