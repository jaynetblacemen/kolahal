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
    /** Started by the user from the prompt (not a timer): may drive the launcher with launcher.run. */
    private val interactive: Boolean = false,
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

    // Script lines are batched: one transaction and one UI refresh per ~300 ms instead of per line.
    private val pending = ArrayList<LogRepository.Pending>()
    private var lastFlush = System.currentTimeMillis()

    private fun buffer(kind: LogKind, text: String, ctx: LogContext) {
        synchronized(pending) {
            pending += LogRepository.Pending(kind, text, scriptName, ctx, System.currentTimeMillis())
            if (pending.size >= 50 || System.currentTimeMillis() - lastFlush > 300) flushLocked()
        }
    }

    /** Writes buffered lines; the worker calls this when the run ends (success or failure). */
    fun flush() = synchronized(pending) { flushLocked() }

    private fun flushLocked() {
        logs.addAll(pending.toList())
        pending.clear()
        lastFlush = System.currentTimeMillis()
    }

    /** Saved scripts get a `── name · 22:40 ──` header before their first line of a run. */
    private var headerWritten = false

    fun log(text: String, ctx: Map<*, *>? = null) {
        effects++
        if (scriptId != null && !headerWritten) {
            headerWritten = true
            val time = java.text.DateFormat.getTimeInstance(java.text.DateFormat.SHORT).format(java.util.Date())
            buffer(LogKind.INFO, "── $scriptName · $time ──", LogContext(baseCtx + mapOf(LogContext.RUN_HEADER to "1")))
        }
        buffer(LogKind.SCRIPT, text, LogContext(baseCtx + safeCtx(ctx)))
    }

    /**
     * Context a script may attach to its lines. Launcher-internal keys (tap-to-run commands, script
     * identity, app grids, error lines) are dropped, so a script can't plant a one-tap `clear all`.
     */
    private fun safeCtx(ctx: Map<*, *>?): Map<String, String> =
        Json.toStringMap(ctx).filterKeys { it !in RESERVED_CTX }

    fun logError(text: String) {
        flush()
        logs.add(LogKind.ERROR, text, scriptName, LogContext(baseCtx))
    }

    fun notify(title: String, body: String, ctx: Map<*, *>? = null): Boolean {
        effects++
        flush() // earlier log lines appear before the notification
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
        flush() // show what was logged before a slow request
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
        flush()
        if (scriptId == null) {
            log("status() only works in saved scripts (it is keyed by script name)")
            return
        }
        val value = text?.lines()?.firstOrNull()?.take(STATUS_MAX)?.takeIf { it.isNotBlank() }
        KvStore(context, STATUS_SCOPE).set(scriptName, value)
        logs.onStatusChanged()
    }

    // ---- device, apps, launcher ----------------------------------------------------------

    fun clipboardSet(text: String) {
        val cm = context.getSystemService(android.content.ClipboardManager::class.java)
        android.os.Handler(android.os.Looper.getMainLooper()).post {
            cm.setPrimaryClip(android.content.ClipData.newPlainText(scriptName, text))
        }
    }

    /** Android only lets the focused app read the clipboard, so this is usually null in timers. */
    fun clipboardGet(): String? = runCatching {
        context.getSystemService(android.content.ClipboardManager::class.java)
            .primaryClip?.getItemAt(0)?.coerceToText(context)?.toString()
    }.getOrNull()

    fun battery(): Map<String, Any?> {
        val intent = context.registerReceiver(null, android.content.IntentFilter(android.content.Intent.ACTION_BATTERY_CHANGED))
        val level = intent?.getIntExtra(android.os.BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = intent?.getIntExtra(android.os.BatteryManager.EXTRA_SCALE, -1) ?: -1
        return mapOf(
            "level" to if (level >= 0 && scale > 0) level * 100 / scale else null,
            "charging" to ((intent?.getIntExtra(android.os.BatteryManager.EXTRA_PLUGGED, 0) ?: 0) != 0),
        )
    }

    fun network(): String {
        val cm = context.getSystemService(android.net.ConnectivityManager::class.java)
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return "offline"
        return when {
            caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_WIFI) -> "wifi"
            caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_CELLULAR) -> "cell"
            caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_ETHERNET) -> "eth"
            else -> "net"
        }
    }

    fun unlocksToday(): Int = io.github.rscipher001.launcher.unlock.UnlockCounter.get(context).countFor(java.time.LocalDate.now())

    /** Launchable apps across profiles: [{label, pkg, profile}]. */
    fun appsList(): List<Map<String, Any?>> {
        val la = context.getSystemService(android.content.pm.LauncherApps::class.java)
        val me = android.os.Process.myUserHandle()
        return la.profiles.flatMap { user ->
            runCatching { la.getActivityList(null, user) }.getOrDefault(emptyList()).map {
                mapOf("label" to it.label.toString(), "pkg" to it.componentName.packageName, "profile" to if (user == me) "personal" else "other")
            }
        }.sortedBy { (it["label"] as String).lowercase() }
    }

    /** Recent notifications (needs notification access): [{pkg, app, title, text, time}], newest first. */
    fun notificationsRecent(limit: Int): List<Any?> {
        val L = io.github.rscipher001.launcher.notifications.KolahalNotificationListener
        val raw = KvStore(context, L.MIRROR_SCOPE).get(L.MIRROR_KEY) ?: return emptyList()
        return (runCatching { Json.parse(raw) as? List<*> }.getOrNull() ?: emptyList<Any?>()).take(limit.coerceIn(0, 50))
    }

    /** Runs a terminal command in the launcher. Only for scripts started from the prompt. */
    fun runCommand(command: String): Boolean {
        if (!interactive) {
            logError("launcher.run is only available when the script is run from the prompt")
            return false
        }
        flush()
        context.sendBroadcast(
            android.content.Intent(ACTION_RUN_COMMAND).setPackage(context.packageName).putExtra(EXTRA_COMMAND, command)
        )
        return true
    }

    /** GET with a per-script cache: returns the stored response if it is younger than [maxAgeSeconds]. */
    fun httpCached(url: String, maxAgeSeconds: Int): Map<String, Any?> {
        val key = "http.cache:$url"
        val cached = store.get(key)?.let { runCatching { Json.parse(it) as? Map<*, *> }.getOrNull() }
        val at = (cached?.get("at") as? Number)?.toLong() ?: 0L
        if (cached != null && System.currentTimeMillis() - at < maxAgeSeconds * 1000L) {
            return mapOf("status" to 200, "ok" to true, "body" to cached["body"], "headers" to emptyMap<String, String>(), "cached" to true)
        }
        val resp = httpGet(url)
        if (resp["ok"] == true) store.set(key, Json.stringify(mapOf("at" to System.currentTimeMillis(), "body" to resp["body"])))
        return resp + ("cached" to false)
    }

    fun storeGet(key: String): String? = store.get(key)

    fun storeSet(key: String, value: String?) = store.set(key, value)

    companion object {
        private const val USER_AGENT = "Kolahal-Launcher/0.1 (+https://github.com/rscipher001)"
        private const val MAX_BODY = 4L * 1024 * 1024
        private const val MAX_SEEN = 500
        private const val STATUS_MAX = 80
        const val STATUS_SCOPE = "status"
        const val ACTION_RUN_COMMAND = "io.github.rscipher001.launcher.RUN_COMMAND"
        const val EXTRA_COMMAND = "command"
        private val RESERVED_CTX = setOf(
            LogContext.RUN, LogContext.COMMAND, LogContext.SCRIPT, LogContext.LINE, LogContext.APPS,
            LogContext.RUN_HEADER, LogContext.DESC, LogContext.PKG, LogContext.CLS, LogContext.USER,
        )
        private val METHODS_REQUIRING_BODY = setOf("POST", "PUT", "PATCH", "PROPPATCH", "REPORT")

        private val client get() = Net.client
    }
}
