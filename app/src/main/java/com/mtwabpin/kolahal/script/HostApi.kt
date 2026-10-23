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
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

/**
 * The launcher-side API exposed to scripts. Engine-agnostic: the Lua and JS bindings both
 * translate their arguments into these calls, so scripts behave the same in either language.
 *
 *   log(title, body, ctx)       -> append a line to the terminal: a bold title, a muted body (ctx drives tap / long-press actions)
 *   logs(titles)                -> a title line per item
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
    /** Started by another script's launcher.run: it may not send commands itself, so scripts can't start each other without end. */
    private val byScript: Boolean = false,
    /** What the script declares and the user allows ([ScriptAccess]); null for code typed at the prompt, which may use anything. */
    private val grants: ScriptAccess.Grants? = null,
    /** The script's `@secret` names and the values you set for them ([SecretStore]); null for code typed at the prompt. */
    private val secrets: Secrets? = null,
) {
    class Secrets(val declared: List<ScriptMeta.Secret>, val values: Map<String, String>)

    /** `secrets.get(name)`: the value you set for a declared `@secret` name, or nil until you set one. */
    fun secretGet(name: String): String? {
        val s = secrets ?: throw IllegalStateException("secrets.get only works in saved scripts")
        if (s.declared.none { it.name == name }) {
            throw IllegalStateException("secrets.get(\"$name\") needs `@secret $name <site> <description>` at the top of the script")
        }
        return s.values[name]
    }

    /**
     * This script's secret values in every form a request or a line may carry them (as typed,
     * URL-encoded, JSON-escaped), longest first. Shorter values than [SecretStore.MIN_LENGTH] can't be set.
     */
    private val secretForms: List<String> =
        secrets?.values?.values.orEmpty().filter { it.length >= SecretStore.MIN_LENGTH }.flatMap(::forms).distinct().sortedByDescending { it.length }

    /** [text] with each of this script's secret values masked: what a run shows never shows a key. */
    fun redact(text: String): String = secretForms.fold(text) { t, v -> t.replace(v, "••••") }

    /**
     * Private data and local-network reach are only for scripts that declare them: undeclared is a
     * mistake in the script. Declared but switched off by the user is false: the caller answers empty.
     */
    private fun may(what: String, call: String): Boolean {
        val g = grants ?: return true
        if (what !in g.declared) throw IllegalStateException("$call needs `@uses $what` at the top of the script")
        if (!g.allows(what) && what in g.undecided) undecided += what
        return g.allows(what)
    }

    /** `allowed(what)` for scripts: whether the user lets this script use [what]. */
    fun allowed(what: String): Boolean = grants?.allows(what.trim().lowercase()) ?: true

    /** The access item that stopped this run, switched off by the user (the worker says how to allow it). */
    var denied: String? = null
        private set

    /** What [denied] stopped the run with, to tell it from an error the script caught and went on after. */
    var deniedMessage: String? = null
        private set

    /** Declared, not decided yet, and asked for this run: the worker offers to allow them. */
    val undecided = LinkedHashSet<String>()

    /** Stops the run: [item] is switched off; the worker says so, with a tap to allow it. */
    private fun deny(item: String, message: String): Nothing {
        denied = item
        deniedMessage = message
        throw java.io.IOException(message)
    }

    /** May reach this device or the local network over http. */
    val allowsLan: Boolean get() = grants == null || grants.allows("lan")
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

    /**
     * Lines this run has logged, and their characters as stored; past [MAX_LINES_PER_RUN] or
     * [MAX_CHARS_PER_RUN] the rest are dropped with one note. Error lines count too.
     */
    private var lines = 0
    private var chars = 0
    private var full = false

    private fun buffer(kind: LogKind, text: String, ctx: LogContext) {
        synchronized(pending) {
            if (full) return
            lines++
            chars += minOf(text.length, LogRepository.MAX_FIELD) + minOf(ctx.values.entries.sumOf { (k, v) -> k.length + v.length }, LogRepository.MAX_FIELD)
            if (lines > MAX_LINES_PER_RUN || chars > MAX_CHARS_PER_RUN) {
                full = true
                val what = if (lines > MAX_LINES_PER_RUN) "$MAX_LINES_PER_RUN lines" else "${MAX_CHARS_PER_RUN / 1024}K characters"
                pending += LogRepository.Pending(LogKind.INFO, "More than $what in one run: the rest aren't logged", scriptName, LogContext(baseCtx), System.currentTimeMillis())
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

    /** `log(title, body?, ctx?)`: a title line, bold, with [body] (any number of lines) muted under it. */
    fun log(raw: String, body: String? = null, ctx: Map<*, *>? = null) {
        effects++
        val text = redact(raw)
        val under = body?.let(::redact)?.takeIf { it.isNotEmpty() }
        if (scriptId != null && !headerWritten) {
            headerWritten = true
            val time = com.mtwabpin.kolahal.data.SharedSettings.shortTime(context)
            buffer(LogKind.INFO, "── $scriptName · $time ──", LogContext(baseCtx + mapOf(LogContext.RUN_HEADER to "1")))
        }
        val title = mapOf(LogContext.TITLE to "1") + (under?.let { mapOf(LogContext.BODY to it) } ?: emptyMap())
        buffer(LogKind.SCRIPT, text, LogContext(baseCtx + title + safeCtx(ctx, listOfNotNull(text, under).joinToString("\n")).mapValues { redact(it.value) }))
    }

    /** `logs(titles)`: one title line each, no bodies. */
    fun logs(titles: List<String>) = titles.forEach { log(it) }

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

    /** `console.error`, and calls the launcher refuses: counted with the run's other lines. */
    fun logError(text: String) = buffer(LogKind.ERROR, redact(text), LogContext(baseCtx))

    /** The watchdog's last line before it stops the process: written at once, whatever the run logged. */
    fun logStopped(text: String) {
        flush()
        logs.add(LogKind.ERROR, redact(text), scriptName, LogContext(baseCtx))
    }

    fun notify(title: String, body: String, ctx: Map<*, *>? = null): Boolean {
        effects++
        flush() // earlier log lines appear before the notification
        val values = Json.toStringMap(ctx)
        // With notifications off for this script, the script's own log lines already show the content.
        if (!notifyEnabled) return false
        // Opening the link sends what it holds to its site: the same rule as a request.
        val url = values[LogContext.URL]?.let(Net::webUrl)?.also { u -> u.toHttpUrlOrNull()?.let { checkSecrets(it.host, listOf(u)) } }
        val posted = Notifier.post(context, scriptName, redact(title), redact(body), url)
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
        // No made-up empty response: a script would take it for real data.
        if (grants != null && !grants.allows(ScriptAccess.INTERNET)) {
            deny(ScriptAccess.INTERNET, "internet is switched off for this script")
        }
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
            // Redirects too: every hop is checked before it is sent, with what it carries on.
            .tag(Net.HopCheck::class.java, Net.HopCheck { hop ->
                checkSecrets(hop.url.host, listOf(hop.url.toString()) + hop.headers.map { it.second } + if (hop.body != null) listOf(body.orEmpty()) else emptyList())
            })
            .build()
        checkSecrets(request.url.host, listOf(request.url.toString(), body.orEmpty()) + h.values)
        try {
            (if (allowsLan) client else Net.publicClient).newCall(request).execute().use { resp ->
                return mapOf(
                    "status" to resp.code,
                    "ok" to resp.isSuccessful,
                    "body" to resp.peekBody(MAX_BODY).string(),
                    "headers" to resp.headers.associate { (k, v) -> k.lowercase() to v },
                )
            }
        } catch (e: Net.LocalNetworkBlocked) {
            // Without `@uses lan` the address isn't allowed at all; switched off, the user said no;
            // otherwise Android's permission is what's missing.
            if (grants != null && "lan" in grants.declared && !grants.allows("lan")) {
                deny("lan", "${e.host} is on your local network, switched off for this script")
            }
            if (!allowsLan) throw java.io.IOException("${e.host} is this phone or the local network: not allowed here")
            lanBlocked = true
            throw e
        }
    }

    /**
     * A secret goes only to the sites its `@secret` line names: a request carrying it ([carried]: the
     * URL, body and headers) anywhere else stops the run. A script could still disguise a value, so
     * this guards against mistakes more than against a script written to leak.
     */
    private fun checkSecrets(host: String, carried: List<String>) {
        val s = secrets ?: return
        for (secret in s.declared) {
            val value = s.values[secret.name]?.takeIf { it.length >= SecretStore.MIN_LENGTH } ?: continue
            val forms = forms(value)
            if (carried.any { text -> forms.any { it in text } } && !secret.allows(host)) {
                throw java.io.IOException("${secret.name} may only be sent to ${secret.hosts.joinToString()}, not $host")
            }
        }
    }

    /** How a value can appear in a URL, a body or a line: as typed, URL-encoded, and JSON-escaped (Lua's json.encode writes `\/`). */
    private fun forms(value: String): Set<String> =
        setOf(value, java.net.URLEncoder.encode(value, "UTF-8"), android.net.Uri.encode(value), value.replace("/", "\\/"))

    /**
     * A store key for [url] ([prefix] + the URL): one carrying a secret is hashed, so the key never
     * sits in the script's store, and so in a backup.
     */
    private fun urlKey(prefix: String, url: String): String =
        if (redact(url) == url) prefix + url
        else prefix + "#" + java.security.MessageDigest.getInstance("SHA-256").digest(url.toByteArray()).joinToString("") { "%02x".format(it) }

    fun httpGet(url: String, headers: Map<*, *>? = null) = httpRequest(url, headers = headers)

    fun rssParse(xml: String): Map<String, Any?> = RssParser.parse(xml).toMap()

    fun rssFetch(url: String): Map<String, Any?> = fetchFeed(url).toMap()

    /** Seen-id updates from `rss.fresh`, applied by [commit] only if the script finishes successfully. */
    private val pendingSeen = LinkedHashMap<String, List<String>>()

    fun rssFresh(url: String): Map<String, Any?> {
        val feed = fetchFeed(url)
        val key = urlKey("rss.seen:", url)
        // JavaScript runs host calls side by side: two rss.fresh finishing together must not lose ids.
        val fresh = synchronized(pendingSeen) {
            val seen = pendingSeen[key] ?: readSeen(key)
            val seenSet = seen.toHashSet()
            feed.items.filter { it.id !in seenSet }.also { fresh ->
                // Never forget an id the feed still lists: a feed longer than MAX_SEEN would otherwise
                // push its own old items out and report them as new on the next run.
                pendingSeen[key] = (fresh.map { it.id } + seen).take(maxOf(MAX_SEEN, feed.items.size + 100))
            }
        }
        return feed.copy(items = fresh).toMap()
    }

    private fun readSeen(key: String): List<String> {
        val raw = store.get(key) ?: return emptyList()
        return runCatching { (Json.parse(raw) as? List<*>)?.map { it.toString() } }.getOrNull()
            ?: raw.split('\n') // format used before ids were stored as JSON
    }

    /** Persists deferred state after a successful run (so a failing script re-sees its items). */
    fun commit() = synchronized(pendingSeen) {
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
        // Header line switched off: the script runs on, its line just doesn't show.
        if (grants?.allows(ScriptAccess.HEADER) == false) return
        val value = text?.let(::redact)?.lines()?.firstOrNull()?.take(STATUS_MAX)?.takeIf { it.isNotBlank() }
        KvStore(context, STATUS_SCOPE).set(scriptName, value)
        logs.onStatusChanged()
    }

    // ---- device, apps, launcher ----------------------------------------------------------

    fun clipboardSet(text: String) {
        if (!may("clipboard", "clipboard.set")) return
        val cm = context.getSystemService(android.content.ClipboardManager::class.java)
        android.os.Handler(android.os.Looper.getMainLooper()).post {
            cm.setPrimaryClip(android.content.ClipData.newPlainText(scriptName, text))
        }
    }

    /** Android only lets the focused app read the clipboard, so this is usually null in timers. */
    fun clipboardGet(): String? {
        if (!may("clipboard", "clipboard.get")) return null
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
        if (!may("apps", "apps.list")) return emptyList()
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
        if (!may("notifications", "notifications.recent")) return emptyList()
        val L = com.mtwabpin.kolahal.notifications.KolahalNotificationListener
        // Access revoked while the listener wasn't bound leaves the copy behind: drop it, share nothing.
        if (!com.mtwabpin.kolahal.notifications.NotificationActions.isEnabled(context)) {
            runCatching { L.clearMirror(context) }
            return emptyList()
        }
        val raw = KvStore(context, L.MIRROR_SCOPE).get(L.MIRROR_KEY) ?: return emptyList()
        return (runCatching { Json.parse(raw) as? List<*> }.getOrNull() ?: emptyList<Any?>()).take(limit.coerceIn(0, 50))
    }

    /** Commands this run has sent with launcher.run. */
    private var commands = 0

    /** Runs a terminal command in the launcher. Only for scripts started from the prompt. */
    fun runCommand(sent: String): Boolean {
        // Split and checked as home will run it ([com.mtwabpin.kolahal.terminal.CommandProcessor.run]).
        val command = com.mtwabpin.kolahal.terminal.CommandParsing.spaces(sent)
        // Commands, and through them other scripts, only for scripts that declare it and are allowed it.
        if (!may("commands", "launcher.run")) return false
        if (!interactive) {
            logError("launcher.run is only available when the script is run from the prompt")
            return false
        }
        if (byScript) {
            logError("launcher.run isn't available to a script another script started")
            return false
        }
        if (++commands > MAX_COMMANDS_PER_RUN) {
            if (commands == MAX_COMMANDS_PER_RUN + 1) logError("launcher.run: more than $MAX_COMMANDS_PER_RUN commands in one run; the rest don't run")
            return false
        }
        // A command goes to the terminal, its log and its history, and on to apps and sites: never a secret.
        if (redact(command) != command) {
            logError("launcher.run refused: the command holds one of this script's secrets")
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
        val key = urlKey("http.cache:", url)
        val cached = store.get(key)?.let { runCatching { Json.parse(it) as? Map<*, *> }.getOrNull() }
        val at = (cached?.get("at") as? Number)?.toLong() ?: 0L
        if (cached != null && System.currentTimeMillis() - at < maxAgeSeconds * 1000L) {
            return mapOf("status" to 200, "ok" to true, "body" to cached["body"], "headers" to emptyMap<String, String>(), "cached" to true)
        }
        val resp = httpGet(url)
        // A body near the store's value cap isn't cached; it would be refused anyway.
        val body = resp["body"] as? String
        if (resp["ok"] == true && (body?.length ?: 0) < KvStore.MAX_VALUE / 2) {
            val entry = Json.stringify(mapOf("at" to System.currentTimeMillis(), "body" to body))
            // Cached responses count toward the store's limits. Full, the other ones make room: they
            // are only a cache, and a store left full would refuse the script's own values.
            runCatching { store.set(key, entry) }.recoverCatching {
                store.deleteStartingWith("http.cache:", except = key)
                store.set(key, entry)
            }
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
            // Set by log() itself, from its arguments.
            LogContext.TITLE, LogContext.BODY,
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
        /** Characters one run may log, its lines' links and other context included. */
        const val MAX_CHARS_PER_RUN = 256 * 1024
        /** Commands one run may send with launcher.run; each one's output is logged by home. */
        const val MAX_COMMANDS_PER_RUN = 10
    }
}
