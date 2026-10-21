package com.mtwabpin.kolahal.script

import com.mtwabpin.kolahal.data.shortTime
import android.content.Context
import com.mtwabpin.kolahal.data.KvStore
import com.mtwabpin.kolahal.data.LogContext
import com.mtwabpin.kolahal.data.LogKind
import com.mtwabpin.kolahal.data.LogRepository
import com.mtwabpin.kolahal.data.ScriptRepository
import com.mtwabpin.kolahal.notify.Notifier
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
    /** What the script declared with `@uses` ([ScriptMeta.uses]); null for code typed at the prompt, which may use anything. */
    private val uses: Set<String>? = null,
) {
    /** Private data and local-network reach are only for scripts that declare them. */
    private fun need(what: String, call: String) {
        if (uses != null && what !in uses) throw IllegalStateException("$call needs `@uses $what` at the top of the script")
    }

    /** May reach this device or the local network over http. */
    val allowsLan: Boolean get() = uses == null || "lan" in uses
    private val logs = LogRepository.get(context)
    private val store = KvStore(context, scriptId?.let(ScriptRepository::scopeOf) ?: "repl")

    /** Visible side effects (log lines, status, notifications); a REPL run with none prints "nil". */
    var effects = 0
        private set

    /** A notify() call was dropped because notifications are blocked (reported once by the worker). */
    var notifyBlocked = false
        private set

    /** A request was refused because Kolahal may not reach the local network yet (reported once by the worker). */
    var lanBlocked = false
        private set

    private val baseCtx: Map<String, String> =
        if (scriptId != null) mapOf(LogContext.SCRIPT to scriptName) else emptyMap()

    // Script lines are batched: one transaction and one UI refresh per ~300 ms instead of per line.
    private val pending = ArrayList<LogRepository.Pending>()
    private var lastFlush = System.currentTimeMillis()

    /** Lines this run has logged; past [MAX_LINES_PER_RUN] the rest are dropped with one note. */
    private var lines = 0

    private fun buffer(kind: LogKind, text: String, ctx: LogContext) {
        synchronized(pending) {
            if (++lines > MAX_LINES_PER_RUN) {
                if (lines == MAX_LINES_PER_RUN + 1) {
                    pending += LogRepository.Pending(LogKind.INFO, "More than $MAX_LINES_PER_RUN lines in one run: the rest aren't logged", scriptName, LogContext(baseCtx), System.currentTimeMillis())
                }
                return
            }
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
            val time = com.mtwabpin.kolahal.data.Config.get(context).shortTime()
            buffer(LogKind.INFO, "── $scriptName · $time ──", LogContext(baseCtx + mapOf(LogContext.RUN_HEADER to "1")))
        }
        buffer(LogKind.SCRIPT, text, LogContext(baseCtx + safeCtx(ctx, text)))
    }

    /**
     * Context a script may attach to its lines. Launcher-internal keys (tap-to-run commands, script
     * identity, app grids, error lines) are dropped, so a script can't plant a one-tap `clear all`.
     */
    private fun safeCtx(ctx: Map<*, *>?, text: String): Map<String, String> {
        val m = Json.toStringMap(ctx).filterKeys { it !in RESERVED_CTX }.toMutableMap()
        // A tap opens the link in another app at once: web links only, not upi:, tel:, intent: …
        for (key in listOf(LogContext.URL, LogContext.IMAGE)) {
            val link = m[key] ?: continue
            Net.webUrl(link)?.let { m[key] = it } ?: m.remove(key)
        }
        m[LogContext.COPY]?.let { if (!honestCopy(it, text, m[LogContext.URL])) m.remove(LogContext.COPY) }
        return m
    }

    /**
     * "Copy text" must copy what the line shows: all or part of it, the whole text of a line cut
     * short with "…", or its link. Otherwise a line could show one address and copy another.
     */
    private fun honestCopy(copy: String, text: String, url: String?): Boolean {
        if (copy == url) return true
        fun bare(s: String) = s.filterNot { it.isWhitespace() || it == ',' }
        val c = bare(copy)
        if (c.isEmpty()) return false
        if (bare(text).contains(c)) return true
        return text.trimEnd().endsWith("…") && c.startsWith(bare(text.trimEnd().removeSuffix("…")))
    }

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
        val posted = Notifier.post(context, scriptName, title, body, values[LogContext.URL]?.let(Net::webUrl))
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
        // This phone and the local network only with `@uses lan` (or code typed at the prompt).
        val request = Net.reach(Request.Builder(), if (allowsLan) Net.Reach.ANY else Net.Reach.PUBLIC)
            .url(url)
            .headers(h.toHeaders())
            .method(m, if (m != "GET" && m != "HEAD") reqBody else null)
            .build()
        try {
            client.newCall(request).execute().use { resp ->
                return mapOf(
                    "status" to resp.code,
                    "ok" to resp.isSuccessful,
                    "body" to resp.peekBody(MAX_BODY).string(),
                    "headers" to resp.headers.associate { (k, v) -> k.lowercase() to v },
                )
            }
        } catch (e: Net.LocalNetworkBlocked) {
            // Without `@uses lan` the address isn't allowed at all; with it, Android's permission is what's missing.
            if (!allowsLan) throw java.io.IOException("${e.host} is this phone or the local network: not allowed here")
            lanBlocked = true
            throw e
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
        // Never forget an id the feed still lists: a feed longer than MAX_SEEN would otherwise
        // push its own old items out and report them as new on the next run.
        pendingSeen[key] = (fresh.map { it.id } + seen).take(maxOf(MAX_SEEN, feed.items.size + 100))
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
        need("clipboard", "clipboard.set")
        val cm = context.getSystemService(android.content.ClipboardManager::class.java)
        android.os.Handler(android.os.Looper.getMainLooper()).post {
            cm.setPrimaryClip(android.content.ClipData.newPlainText(scriptName, text))
        }
    }

    /** Android only lets the focused app read the clipboard, so this is usually null in timers. */
    fun clipboardGet(): String? {
        need("clipboard", "clipboard.get")
        return runCatching {
            context.getSystemService(android.content.ClipboardManager::class.java)
                .primaryClip?.getItemAt(0)?.coerceToText(context)?.toString()
        }.getOrNull()
    }

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

    fun unlocksToday(): Int = com.mtwabpin.kolahal.unlock.UnlockCounter.get(context).countFor(java.time.LocalDate.now())

    /** Launchable apps across profiles: [{label, pkg, profile}]. */
    fun appsList(): List<Map<String, Any?>> {
        need("apps", "apps.list")
        val la = context.getSystemService(android.content.pm.LauncherApps::class.java)
        val me = android.os.Process.myUserHandle()
        val um = context.getSystemService(android.os.UserManager::class.java)
        // Private space (and any paused profile) stays invisible to scripts.
        return la.profiles.filter { user ->
            !runCatching { um.isQuietModeEnabled(user) }.getOrDefault(false) &&
                (android.os.Build.VERSION.SDK_INT < 35 || runCatching { la.getLauncherUserInfo(user)?.userType }.getOrNull() != android.os.UserManager.USER_TYPE_PROFILE_PRIVATE)
        }.flatMap { user ->
            runCatching { la.getActivityList(null, user) }.getOrDefault(emptyList()).map {
                mapOf("label" to it.label.toString(), "pkg" to it.componentName.packageName, "profile" to if (user == me) "personal" else "other")
            }
        }.sortedBy { (it["label"] as String).lowercase() }
    }

    /** Recent notifications (needs notification access): [{pkg, app, title, text, time}], newest first. */
    fun notificationsRecent(limit: Int): List<Any?> {
        need("notifications", "notifications.recent")
        val L = com.mtwabpin.kolahal.notifications.KolahalNotificationListener
        // Access revoked while the listener wasn't bound leaves the copy behind: drop it, share nothing.
        if (!com.mtwabpin.kolahal.notifications.NotificationActions.isEnabled(context)) {
            runCatching { L.clearMirror(context) }
            return emptyList()
        }
        val raw = KvStore(context, L.MIRROR_SCOPE).get(L.MIRROR_KEY) ?: return emptyList()
        return (runCatching { Json.parse(raw) as? List<*> }.getOrNull() ?: emptyList<Any?>()).take(limit.coerceIn(0, 50))
    }

    /** Runs a terminal command in the launcher. Only for scripts started from the prompt. */
    fun runCommand(command: String): Boolean {
        if (!interactive) {
            logError("launcher.run is only available when the script is run from the prompt")
            return false
        }
        // State-changing commands (clear, uninstall, notifs clear, config, …) stay with the user.
        val aliases = runCatching { com.mtwabpin.kolahal.apps.AppPrefs.get(context).aliases() }.getOrDefault(emptyMap())
        if (com.mtwabpin.kolahal.terminal.InstantRun.isSensitive(command, aliases)) {
            logError("launcher.run refused '${command.substringBefore(' ')}': sensitive commands must be typed")
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
        // A body near the store's value cap isn't cached; it would be refused anyway.
        val body = resp["body"] as? String
        if (resp["ok"] == true && (body?.length ?: 0) < KvStore.MAX_VALUE / 2) {
            runCatching { store.set(key, Json.stringify(mapOf("at" to System.currentTimeMillis(), "body" to body))) }
        }
        return resp + ("cached" to false)
    }

    fun storeGet(key: String): String? = store.get(key)

    fun storeSet(key: String, value: String?) = store.set(key, value)

    companion object {
        private const val USER_AGENT = "Kolahal-Launcher/0.1 (+https://github.com/mtwabpin)"
        private const val MAX_BODY = 4L * 1024 * 1024
        private const val MAX_SEEN = 500
        private const val STATUS_MAX = 80
        const val STATUS_SCOPE = "status"
        const val ACTION_RUN_COMMAND = "com.mtwabpin.kolahal.RUN_COMMAND"
        const val EXTRA_COMMAND = "command"
        private val RESERVED_CTX = setOf(
            LogContext.ICON, LogContext.RUN, LogContext.COMMAND, LogContext.SCRIPT, LogContext.LINE, LogContext.APPS,
            LogContext.RUN_HEADER, LogContext.DESC, LogContext.PKG, LogContext.CLS, LogContext.USER,
            // Tap / long-press actions: a script must not be able to forge a dial, a live
            // notification's PendingIntent, a shortcut or delete buttons for the user's data.
            LogContext.PHONE, LogContext.CONTACT, LogContext.SHORTCUT, LogContext.TODO, LogContext.TODO_DONE,
            LogContext.NOTE, LogContext.REMINDER, LogContext.EVENT,
            com.mtwabpin.kolahal.notifications.KolahalNotificationListener.CTX_KEY,
            com.mtwabpin.kolahal.feeds.FeedWorker.CTX_FEED,
            com.mtwabpin.kolahal.feeds.FeedWorker.CTX_ITEM,
        )
        private val METHODS_REQUIRING_BODY = setOf("POST", "PUT", "PATCH", "PROPPATCH", "REPORT")

        private val client get() = Net.client
        /** Lines one run may log; a runaway loop stops filling the log here. */
        const val MAX_LINES_PER_RUN = 500
    }
}
