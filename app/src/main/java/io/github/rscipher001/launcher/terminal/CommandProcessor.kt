package io.github.rscipher001.launcher.terminal

import android.content.Context
import android.app.role.RoleManager
import android.content.Intent
import android.content.pm.PackageManager
import android.os.PowerManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import io.github.rscipher001.launcher.apps.AppEntry
import io.github.rscipher001.launcher.apps.AppPrefs
import io.github.rscipher001.launcher.script.ScriptMeta
import io.github.rscipher001.launcher.script.ScriptLibrary
import io.github.rscipher001.launcher.script.Json
import io.github.rscipher001.launcher.script.Net
import io.github.rscipher001.launcher.script.ScriptFolder
import io.github.rscipher001.launcher.script.HostApi
import io.github.rscipher001.launcher.data.KvStore
import io.github.rscipher001.launcher.apps.AppRepository
import io.github.rscipher001.launcher.apps.ProfileKind
import io.github.rscipher001.launcher.data.Config
import io.github.rscipher001.launcher.data.ConfigKey
import io.github.rscipher001.launcher.data.Keys
import io.github.rscipher001.launcher.data.LogContext
import io.github.rscipher001.launcher.data.LogKind
import io.github.rscipher001.launcher.data.LogRepository
import io.github.rscipher001.launcher.data.Script
import io.github.rscipher001.launcher.data.ScriptLang
import io.github.rscipher001.launcher.data.ScriptRepository
import io.github.rscipher001.launcher.notify.Notifier
import io.github.rscipher001.launcher.script.ScriptScheduler
import io.github.rscipher001.launcher.script.ScriptTemplates
import io.github.rscipher001.launcher.unlock.UnlockCounter
import java.text.DateFormat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.util.Date

/** UI-only operations the processor cannot do from an application context. */
/** Full-screen GUI pages besides the terminal (the script editor is separate). */
enum class Screen { TERMINAL, SETTINGS, SCRIPTS, SETUP, FEEDS }

interface TerminalUi {
    fun requestContactsPermission()
    fun openScreen(screen: Screen)
    fun pickScriptFolder()
    fun openEditor(script: Script)
    fun requestNotificationPermission()
    fun clearScreen()
}

data class CommandSpec(val name: String, val usage: String, val help: String)

/**
 * Parses and runs commands typed at the prompt. Everything it prints goes into the
 * persistent log with a [LogContext] so lines stay actionable later (tap / long-press).
 */
class CommandProcessor(private val context: Context, private val ui: TerminalUi) {
    private val logs = LogRepository.get(context)
    private val apps = AppRepository.get(context)
    private val scripts = ScriptRepository.get(context)
    private val unlocks = UnlockCounter.get(context)
    private val conf = Config.get(context)
    private val prefs = AppPrefs.get(context)
    private val folder = ScriptFolder(context)
    private val backgroundScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val controls by lazy { io.github.rscipher001.launcher.control.ControlCommands(context) }

    private fun background(label: String, block: () -> Unit) {
        backgroundScope.launch {
            runCatching(block).onFailure { error("$label: ${it.message ?: it.javaClass.simpleName}") }
        }
    }

    /** [expand] = false runs [line] literally (tap-to-run lines must not be rewritten by aliases). */
    fun run(line: String, expand: Boolean = true) {
        val typed = line.trim()
        if (typed.isEmpty()) return
        logs.add(LogKind.COMMAND, typed, ctx = LogContext.of(LogContext.COMMAND to typed))
        val stages = Pipes.split(typed)
        if (stages.size > 1) {
            // `cmd | grep x | head 5`: capture what the command prints right away, then filter it.
            val (_, captured) = logs.capture { execute(stages[0], expand) }
            Pipes.apply(captured, stages.drop(1)).fold(
                onSuccess = { logs.addAll(it) },
                onFailure = { logs.addAll(captured); error(it.message ?: "bad pipe") },
            )
            return
        }
        execute(typed, expand)
    }

    private fun execute(typed: String, expand: Boolean) {
        val trimmed = if (expand) expandAliases(typed) else typed
        // "= 6*7" is a quick calculator (any Lua expression).
        if (trimmed.startsWith("=")) return calc(trimmed.drop(1).trim())
        val name = trimmed.substringBefore(' ').lowercase()
        val rest = trimmed.substringAfter(' ', "").trim()
        val args = tokenize(rest)
        runCatching {
            when (name) {
                "alias" -> alias(rest)
                "unalias" -> unalias(args)
                "hide" -> withApp(args) { setHidden(it, true) }
                "pin", "fav" -> if (args.isEmpty()) listPinned() else withApp(args) { app -> prefs.setPinned(app.key, true); info("pinned ${app.label} — it leads the suggestions on an empty prompt") }
                "unpin", "unfav" -> withApp(args) { app -> prefs.setPinned(app.key, false); info("unpinned ${app.label}") }
                "pins", "favs" -> listPinned()
                "unhide" -> withApp(args, includeHidden = true) { setHidden(it, false) }
                "rename" -> rename(args)
                "help", "?" -> help()
                "apps", "ls" -> listApps(args)
                "open", "launch" -> open(args)
                "info" -> withApp(args) { apps.openAppInfo(it); info("app info: ${it.label}") }
                "uninstall" -> withApp(args) { apps.uninstall(it) }
                "work" -> profileToggle(ProfileKind.WORK, args)
                "private" -> profileToggle(ProfileKind.PRIVATE, args)
                "unlocks" -> unlocks(args)
                "clear", "cls" -> clear(args)
                "restart", "reboot" -> restart(args)
                "history" -> history()
                "scripts" -> if (args.isEmpty()) ui.openScreen(Screen.SCRIPTS) else script(args)
                "script" -> script(args)
                "lua" -> eval(ScriptLang.LUA, rest)
                "js" -> eval(ScriptLang.JS, rest)
                "rss" -> rss(rest)
                "feeds" -> if (args.isEmpty()) ui.openScreen(Screen.FEEDS) else feeds(args)
                "feed" -> feeds(args)
                "notifs", "notifications-feed" -> notifs(args)
                "url", "web" -> openUrl(rest)
                "search", "g" -> search(rest)
                "yt", "youtube" -> search(rest, "youtube")
                "wiki" -> search(rest, "wikipedia")
                "maps", "map" -> if (rest.isBlank()) fallbackLaunch(trimmed) else openUri("geo:0,0?q=" + Uri.encode(rest), "maps: $rest")
                "play", "store" -> if (rest.isBlank()) fallbackLaunch(trimmed) else openUri("market://search?q=" + Uri.encode(rest), "play store: $rest")
                "usage", "screentime" -> usage(args)
                "shortcuts" -> withApp(args) { app -> listShortcuts(app) }
                "call", "dial" -> call(rest)
                "sms", "text" -> sms(args)
                "contacts", "contact" -> listContacts(rest)
                "notifications" -> notifications(args)
                "setup" -> if (args.firstOrNull() == "text") setupText() else ui.openScreen(Screen.SETUP)
                "status" -> statusCommand(args)
                "usage-access" -> startActivity(unlocks.usageAccessIntent())
                "battery" -> startSettings(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
                "settings", "prefs" -> ui.openScreen(Screen.SETTINGS)
                "system" -> startSettings(Settings.ACTION_SETTINGS)
                "home" -> startSettings(Settings.ACTION_HOME_SETTINGS)
                "lock" -> if (!io.github.rscipher001.launcher.gesture.LockService.lock()) {
                    logs.add(LogKind.ERROR, "locking needs the Kolahal screen lock accessibility service — tap to enable it", ctx = LogContext.of(LogContext.RUN to "accessibility"))
                }
                "accessibility" -> startActivity(io.github.rscipher001.launcher.gesture.LockService.settingsIntent())
                "shade" -> io.github.rscipher001.launcher.gesture.Gestures.expandShade(context)
                "date", "time" -> out(DateFormat.getDateTimeInstance().format(Date()))
                "echo" -> out(rest)
                "config", "set" -> if (name == "config" && args.isEmpty()) ui.openScreen(Screen.SETTINGS) else config(args, rest)
                "theme" -> config(listOf("set", "theme") + args)
                in io.github.rscipher001.launcher.control.ControlCommands.NAMES -> controls.run(name, args)
                else -> scriptCommand(name, args) ?: fallbackLaunch(trimmed)
            }
        }.onFailure { error("$name: ${it.message ?: it.javaClass.simpleName}") }
    }

    // ---- aliases --------------------------------------------------------------------------

    /** Expands the first word through user aliases (up to a few levels, no cycles). */
    private fun expandAliases(line: String): String {
        var current = line
        val seen = HashSet<String>()
        repeat(5) {
            val head = current.substringBefore(' ')
            if (!seen.add(head)) return current
            val expansion = prefs.alias(head) ?: return current
            current = (expansion + " " + current.substringAfter(' ', "")).trim()
        }
        return current
    }

    private fun alias(rest: String) {
        if (rest.isBlank()) {
            val all = prefs.aliases()
            if (all.isEmpty()) info("no aliases — e.g. alias m = open maps")
            all.forEach { (k, v) -> out("$k = $v", LogContext.of(LogContext.COMMAND to k)) }
            return
        }
        val name = rest.substringBefore('=').trim().substringBefore(' ')
        val expansion = rest.substringAfter('=', "").trim().removeSurrounding("\"").removeSurrounding("'")
        when {
            !rest.contains('=') -> prefs.alias(name)?.let { out("$name = $it") } ?: error("no alias '$name'")
            name.isEmpty() || expansion.isEmpty() -> error("usage: alias <name> = <command>")
            name in COMMANDS.map { it.name } -> error("'$name' is a built-in command and can't be an alias")
            else -> {
                prefs.setAlias(name, expansion)
                info("alias $name = $expansion")
            }
        }
    }

    private fun unalias(args: List<String>) {
        val name = args.firstOrNull() ?: return error("usage: unalias <name>")
        if (prefs.alias(name) == null) return error("no alias '$name'")
        prefs.setAlias(name, null)
        info("removed alias $name")
    }

    // ---- hide / rename --------------------------------------------------------------------

    private fun listPinned() {
        val byKey = apps.apps.value.associateBy { it.key }
        val pinned = prefs.pinned().mapNotNull { byKey[it] }
        if (pinned.isEmpty()) return info("no pinned apps — pin <app>, or long-press an app → Pin")
        appGrid(pinned)
        info("${pinned.size} pinned · unpin <app> to remove")
    }

    private fun setHidden(app: AppEntry, hidden: Boolean) {
        prefs.setHidden(app.key, hidden)
        apps.refresh()
        info("${if (hidden) "hidden" else "unhidden"} ${appLine(app)}", appCtx(app))
    }

    /** `rename <app> = <new label>`, or `rename <app> =` to restore the original. */
    private fun rename(args: List<String>) {
        val eq = args.indexOf("=")
        if (eq <= 0) return error("usage: rename <app> [-w] = <new name>")
        withApp(args.subList(0, eq), includeHidden = true) { app ->
            val label = args.drop(eq + 1).joinToString(" ").ifBlank { null }
            prefs.setLabel(app.key, label)
            apps.refresh()
            info("${app.originalLabel} → ${label ?: app.originalLabel}", appCtx(app))
        }
    }

    // ---- script commands ------------------------------------------------------------------

    /** Runs a `@command` script named [name]; returns null if there is none. */
    private fun scriptCommand(name: String, args: List<String>): Unit? {
        val script = scripts.byName(name)?.takeIf { ScriptMeta.isCommand(it.code) } ?: return null
        ScriptScheduler.runNow(context, script, args)
        return Unit
    }

    // ---- output helpers -------------------------------------------------------------------

    private fun out(text: String, ctx: LogContext = LogContext.EMPTY) { logs.add(LogKind.OUTPUT, text, ctx = ctx) }
    private fun info(text: String, ctx: LogContext = LogContext.EMPTY) { logs.add(LogKind.INFO, text, ctx = ctx) }
    private fun error(text: String) { logs.add(LogKind.ERROR, text) }

    private fun appCtx(app: AppEntry) = LogContext.of(
        LogContext.PKG to app.pkg,
        LogContext.CLS to app.component.className,
        LogContext.USER to app.profile.serial.toString(),
    )

    /** `[W] Maps`; personal apps are only tagged when the same app also exists in another profile. */
    private fun appLine(app: AppEntry): String {
        val twin = app.profile.kind == ProfileKind.PERSONAL &&
            apps.apps.value.any { it.pkg == app.pkg && it.profile.serial != app.profile.serial }
        return if (app.profile.kind != ProfileKind.PERSONAL || twin) "[${app.profile.kind.tag}] ${app.label}" else app.label
    }

    // ---- apps -----------------------------------------------------------------------------

    private fun profileFlag(args: List<String>): ProfileKind? = when {
        "-w" in args || "--work" in args -> ProfileKind.WORK
        "-p" in args || "--personal" in args -> ProfileKind.PERSONAL
        "-s" in args || "--private" in args -> ProfileKind.PRIVATE
        "-c" in args || "--clone" in args -> ProfileKind.CLONE
        else -> null
    }

    private fun stripFlags(args: List<String>) = args.filterNot { it.startsWith("-") }.joinToString(" ")

    private fun listApps(args: List<String>) {
        val kind = profileFlag(args) ?: when (args.firstOrNull()) {
            "work" -> ProfileKind.WORK
            "personal" -> ProfileKind.PERSONAL
            "private" -> ProfileKind.PRIVATE
            else -> null
        }
        val showHidden = "-a" in args || "hidden" in args
        val list = apps.apps.value.filter {
            (kind == null || it.profile.kind == kind) && (if ("hidden" in args) it.hidden else showHidden || !it.hidden)
        }
        if (list.isEmpty()) {
            info("no apps" + (kind?.let { " in ${it.label} profile" } ?: ""))
            return
        }
        if ("-l" in args) list.forEach { out(appLine(it), appCtx(it)) } else appGrid(list)
        val kinds = list.map { it.profile.kind }.distinct().sortedBy { it.ordinal }
        info("${list.size} apps · " + kinds.joinToString("  ") { "[${it.tag}] ${it.label}" })
    }

    private fun resolveApp(args: List<String>, includeHidden: Boolean = false): List<AppEntry> =
        apps.search(stripFlags(args), profileFlag(args), includeHidden)

    private inline fun withApp(args: List<String>, includeHidden: Boolean = false, action: (AppEntry) -> Unit) {
        val matches = resolveApp(args, includeHidden)
        when {
            matches.isEmpty() -> error("no app matches '${stripFlags(args)}'")
            else -> action(matches.first())
        }
    }

    private fun open(args: List<String>) {
        // `open maps:navigate` starts the shortcut whose label best matches "navigate".
        val joined = stripFlags(args)
        if (joined.contains(':')) {
            val app = apps.search(joined.substringBefore(':'), profileFlag(args)).firstOrNull()
                ?: return error("no app matches '${joined.substringBefore(':')}'")
            val wanted = joined.substringAfter(':').trim().lowercase()
            val shortcut = apps.shortcuts(app).minByOrNull { s ->
                val label = (s.shortLabel ?: s.longLabel ?: "").toString().lowercase()
                when {
                    label == wanted -> 0
                    label.startsWith(wanted) -> 1
                    label.contains(wanted) -> 2
                    else -> 9
                }
            }?.takeIf { s -> (s.shortLabel ?: "").toString().lowercase().contains(wanted) || (s.longLabel ?: "").toString().lowercase().contains(wanted) }
                ?: return error("${app.label} has no shortcut matching '$wanted' — 'shortcuts ${app.label}'")
            apps.startShortcut(app, shortcut.id)
            return info("${app.label} › ${shortcut.shortLabel}", appCtx(app))
        }
        val matches = resolveApp(args)
        if (matches.isEmpty()) {
            error("no app matches '${stripFlags(args)}'")
            return
        }
        val app = matches.first()
        if ("--resume" in args && app.profile.quiet) resumeAndLaunch(app) else launch(app)
    }

    fun launch(app: AppEntry) {
        if (app.profile.quiet) {
            val flag = if (app.profile.kind == ProfileKind.PRIVATE) "-s" else "-w"
            logs.add(
                LogKind.INFO,
                "${app.profile.kind.label} profile is paused — tap to resume it and open ${app.label}",
                ctx = LogContext.of(LogContext.RUN to "open ${app.label} $flag --resume"),
            )
            return
        }
        runCatching { apps.launch(app) }
            .onSuccess { info("launched ${appLine(app)}", appCtx(app)) }
            .onFailure { error("can't launch ${app.label}: ${it.message ?: it.javaClass.simpleName}") }
    }

    /** Turns the paused profile back on, waits until it is available, then opens [app]. */
    private fun resumeAndLaunch(app: AppEntry) {
        if (!apps.setQuietMode(app.profile, false)) {
            error("couldn't resume the ${app.profile.kind.label} profile (is Kolahal the default launcher? run 'home')")
            return
        }
        info("resuming ${app.profile.kind.label} profile…")
        background("resume") {
            val deadline = System.currentTimeMillis() + 20_000
            while (System.currentTimeMillis() < deadline) {
                apps.refresh()
                Thread.sleep(500)
                val profile = apps.profileBySerial(app.profile.serial)
                if (profile != null && !profile.quiet) {
                    val fresh = apps.find(app.pkg, app.component.className, app.profile.serial) ?: app
                    launch(fresh)
                    return@background
                }
            }
            error("${app.profile.kind.label} profile didn't come back in time — try again")
        }
    }

    /** Anything that isn't a command is treated as an app name. */
    private fun fallbackLaunch(text: String) {
        val ranked = apps.searchRanked(text)
        val matches = ranked.map { it.first }
        when {
            matches.isEmpty() -> notFound(text)
            matches[0].label.equals(text, ignoreCase = true) -> launch(matches[0])
            matches.size == 1 && conf[Keys.AUTO_LAUNCH] -> launch(matches[0])
            // The top suggestion chip is what Enter launches, as long as it starts with the input.
            conf[Keys.LAUNCH_TOP] && ranked[0].second <= 2 -> launch(matches[0])
            else -> {
                appGrid(matches.take(12))
                info("${matches.size} matches — tap one to launch")
            }
        }
    }

    /** Unknown input: suggest the closest command (typo in the first word). */
    private fun notFound(text: String) {
        val head = text.substringBefore(' ').lowercase()
        val rest = text.substringAfter(' ', "")
        val candidates = COMMANDS.map { it.name } + prefs.aliases().keys +
            scripts.all().filter { ScriptMeta.isCommand(it.code) }.map { it.name }
        val best = candidates.filter { it.length > 1 }
            .map { it to editDistance(head, it) }
            .filter { (c, d) -> d <= (if (c.length <= 4) 1 else 2) }
            .minByOrNull { it.second }?.first
        if (best == null) {
            error("command not found: $text  (try 'help')")
        } else {
            val corrected = (best + " " + rest).trim()
            logs.add(
                LogKind.ERROR,
                "command not found: $head — did you mean '$corrected'? (tap to put it in the prompt)",
                ctx = LogContext.of(LogContext.COMMAND to corrected),
            )
        }
    }

    /** One log line rendered as tappable app chips (long-press a chip for its actions). */
    private fun appGrid(list: List<AppEntry>) {
        val refs = list.map { mapOf("pkg" to it.pkg, "cls" to it.component.className, "user" to it.profile.serial) }
        out(
            list.joinToString(", ") { it.label },
            LogContext.of(LogContext.APPS to Json.stringify(refs), LogContext.COPY to list.joinToString("\n") { appLine(it) }),
        )
    }

    private fun profileToggle(kind: ProfileKind, args: List<String>) {
        val profile = apps.profiles.value.firstOrNull { it.kind == kind }
        if (profile == null) {
            info("no ${kind.label} profile on this device")
            return
        }
        when (args.firstOrNull()) {
            null, "status" -> info("${kind.label} profile: ${if (profile.quiet) "paused" else "on"}")
            "on", "off" -> {
                val quiet = args[0] == "off"
                if (Build.VERSION.SDK_INT < 29) {
                    error("toggling profiles needs Android 10+")
                } else if (apps.setQuietMode(profile, quiet)) {
                    info("${kind.label} profile turning ${args[0]}…")
                } else {
                    error("couldn't change ${kind.label} profile (is Kolahal the default launcher? run 'home')")
                }
            }
            else -> error("usage: ${kind.name.lowercase()} [on|off|status]")
        }
    }

    // ---- misc -----------------------------------------------------------------------------

    private fun unlocks(args: List<String>) {
        val days = args.firstOrNull()?.toIntOrNull()?.coerceIn(1, 30) ?: 1
        if (!unlocks.hasUsageAccess()) {
            info("counting via unlock receiver (misses unlocks while launcher isn't running)")
            info("tap here to grant Usage Access for accurate counts", LogContext.of(LogContext.RUN to "usage-access"))
        }
        unlocks.history(days).forEach { (day, count) -> out("$day  ${"█".repeat(minOf(count, 40))} $count") }
    }

    private fun history() {
        val cmds = logs.commandHistory(30).reversed()
        cmds.forEach { out(it, LogContext.of(LogContext.COMMAND to it)) }
        if (cmds.isEmpty()) info("history is empty")
    }

    private fun eval(lang: ScriptLang, code: String) {
        if (code.isBlank()) {
            error("usage: ${lang.ext} <code>")
            return
        }
        if (!ScriptScheduler.eval(context, lang, code)) error("snippet too long — use 'script new'")
    }

    private fun rss(url: String) {
        if (url.isBlank()) {
            error("usage: rss <feed url>")
            return
        }
        val quoted = url.replace("\\", "\\\\").replace("\"", "\\\"")
        ScriptScheduler.eval(
            context, ScriptLang.LUA,
            """
            local feed = rss.fetch("$quoted")
            for i, it in ipairs(feed.items) do
              if i > 15 then break end
              log(it.title, { url = it.link })
            end
            return (feed.title or "feed") .. ": " .. #feed.items .. " items"
            """.trimIndent(),
            name = "rss",
        )
    }

    private fun openUrl(raw: String) {
        if (raw.isBlank()) return error("usage: url <address>")
        val url = if (raw.contains("://")) raw else "https://$raw"
        startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        info("opened $url", LogContext.of(LogContext.URL to url))
    }

    private fun search(query: String, engine: String? = null) {
        if (query.isBlank()) return error("usage: search <query>")
        val template = when (engine ?: conf[Keys.SEARCH_ENGINE]) {
            "google" -> "https://www.google.com/search?q=%s"
            "bing" -> "https://www.bing.com/search?q=%s"
            "brave" -> "https://search.brave.com/search?q=%s"
            "startpage" -> "https://www.startpage.com/do/search?q=%s"
            "kagi" -> "https://kagi.com/search?q=%s"
            "youtube" -> "https://www.youtube.com/results?search_query=%s"
            "wikipedia" -> "https://en.wikipedia.org/w/index.php?search=%s"
            "custom" -> conf[Keys.SEARCH_URL].takeIf { it.contains("%s") } ?: "https://duckduckgo.com/?q=%s"
            else -> "https://duckduckgo.com/?q=%s"
        }
        val url = template.replace("%s", Uri.encode(query))
        startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        info("search: $query", LogContext.of(LogContext.URL to url))
    }

    /** Opens [uri] in whatever app handles it (maps, store, ...). */
    private fun openUri(uri: String, what: String) {
        startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(uri)))
        info(what, LogContext.of(LogContext.URL to uri))
    }

    // ---- calculator & conversions --------------------------------------------------------

    /** `= 2^10`, `= 5 km in mi`, `= 72 f to c`, `= 100 usd in inr` (live rates, cached 6h). */
    private fun calc(expr: String) {
        val conversion = Units.parse(expr)
        if (conversion != null) {
            val (amount, from, to) = conversion
            Units.convert(amount, from, to)?.let { return out(it, LogContext.of(LogContext.COPY to it.substringAfter("= "))) }
            if (Units.looksLikeCurrency(from) && Units.looksLikeCurrency(to)) {
                return background("convert") { currency(amount, from.uppercase(), to.uppercase()) }
            }
        }
        eval(ScriptLang.LUA, expr)
    }

    private fun currency(amount: Double, from: String, to: String) {
        val cache = KvStore(context, "fx")
        val cached = cache.get(from)?.let { runCatching { Json.parse(it) as? Map<*, *> }.getOrNull() }
        val fresh = (cached?.get("at") as? Number)?.toLong()?.let { System.currentTimeMillis() - it < 6 * 3_600_000L } == true
        val rates = if (fresh) {
            cached?.get("rates") as? Map<*, *>
        } else {
            val body = Json.parse(Net.get("https://open.er-api.com/v6/latest/$from")) as? Map<*, *>
                ?: throw IllegalStateException("bad response")
            if (body["result"] != "success") throw IllegalStateException("unknown currency $from")
            (body["rates"] as? Map<*, *>)?.also {
                cache.set(from, Json.stringify(mapOf("at" to System.currentTimeMillis(), "rates" to it)))
            }
        }
        val rate = (rates?.get(to) as? Number)?.toDouble() ?: throw IllegalStateException("unknown currency $to")
        val text = "${Units.format(amount)} $from = ${Units.format(amount * rate)} $to"
        out(text, LogContext.of(LogContext.COPY to Units.format(amount * rate)))
    }

    private fun listShortcuts(app: AppEntry) {
        val list = apps.shortcuts(app)
        if (list.isEmpty()) {
            return info(
                if (io.github.rscipher001.launcher.terminal.SetupChecks.isDefaultLauncher(context)) "${app.label} has no shortcuts"
                else "shortcuts need Kolahal as the default launcher — run 'home'"
            )
        }
        list.forEach { s ->
            out(
                "${app.label} › ${s.shortLabel}",
                appCtx(app) + mapOf(LogContext.SHORTCUT to s.id, LogContext.DESC to (s.longLabel ?: s.shortLabel ?: "").toString()),
            )
        }
    }

    // ---- feeds ------------------------------------------------------------------------------

    /** Built-in RSS/Atom subscriptions (no scripting needed). */
    private fun feeds(args: List<String>) {
        val repo = io.github.rscipher001.launcher.feeds.FeedRepository.get(context)
        val scheduler = io.github.rscipher001.launcher.feeds.FeedScheduler
        val id = args.getOrNull(1)?.toLongOrNull()
        when (args.firstOrNull()) {
            null, "ls", "list" -> {
                val all = repo.all()
                if (all.isEmpty()) return info("no feeds yet — feed add <url> (or 'feeds' for the manager and OPML import)")
                all.forEach { f ->
                    val flags = listOfNotNull(
                        "${f.unread}/${f.total} unread", "every ${formatInterval(f.intervalMin)}",
                        if (f.notify) "notify" else null, if (!f.enabled) "off" else null, f.lastStatus,
                    ).joinToString(" · ")
                    out(
                        "#${f.id} ${f.title ?: f.url}",
                        LogContext.of(LogContext.COMMAND to "feed read ${f.id}", LogContext.COPY to f.url, LogContext.DESC to flags),
                    )
                }
                info("tap a feed to read it · feed read = all unread · feed fetch = refresh now")
            }
            "add" -> {
                val url = args.getOrNull(1) ?: return error("usage: feed add <url>")
                val feed = runCatching { repo.add(url) }.getOrElse { return error(it.message ?: "bad url") }
                scheduler.sync(context)
                scheduler.fetchNow(context, feed.id)
                info("subscribed #${feed.id} ${feed.url} — fetching…", LogContext.of(LogContext.URL to feed.url))
            }
            "rm", "remove" -> {
                id ?: return error("usage: feed rm <id>")
                repo.remove(id)
                scheduler.sync(context)
                info("removed feed #$id")
            }
            "fetch", "refresh" -> {
                scheduler.fetchNow(context, id)
                info("fetching ${id?.let { "feed #$it" } ?: "all feeds"}…")
            }
            "read", "all" -> {
                val unreadOnly = args[0] == "read"
                val items = repo.items(feedId = id, unreadOnly = unreadOnly, limit = 40)
                if (items.isEmpty()) return info(if (unreadOnly) "nothing unread" else "no items")
                val names = repo.feeds().associate { it.id to it.shortName }
                items.forEach {
                    logs.add(LogKind.SCRIPT, it.title, names[it.feedId] ?: "feed", LogContext.of(LogContext.URL to it.link, LogContext.COPY to it.title))
                }
                if (unreadOnly) repo.markAllRead(id)
            }
            "open" -> {
                // Opens every unread item of a feed (or all feeds) in the browser, then marks them read.
                val items = repo.items(feedId = id, unreadOnly = true, limit = 10).filter { it.link != null }
                if (items.isEmpty()) return info("nothing unread")
                items.forEach { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(it.link))) }
                repo.markAllRead(id)
                info("opened ${items.size} items")
            }
            "notify" -> {
                id ?: return error("usage: feed notify <id> on|off")
                repo.setNotify(id, args.getOrNull(2) != "off")
                info("feed #$id notifications ${args.getOrNull(2) ?: "on"}")
            }
            "every" -> {
                val minutes = parseInterval(args.getOrNull(2))
                if (id == null || minutes == null || minutes <= 0) return error("usage: feed every <id> <30m|2h|…>")
                repo.setInterval(id, minutes)
                info("feed #$id every ${formatInterval(minutes.coerceAtLeast(15))}")
            }
            "on", "off" -> {
                id ?: return error("usage: feed ${args[0]} <id>")
                repo.setEnabled(id, args[0] == "on")
                scheduler.sync(context)
                info("feed #$id ${args[0]}")
            }
            else -> error("usage: feed [ls|add <url>|rm|fetch|read|all|open|notify|every|on|off] [id]")
        }
    }

    // ---- notifications ----------------------------------------------------------------------

    private fun notifs(args: List<String>) {
        val actions = io.github.rscipher001.launcher.notifications.NotificationActions
        val store = io.github.rscipher001.launcher.notifications.NotificationStore
        val prefs = io.github.rscipher001.launcher.notifications.NotifPrefs.get(context)
        if (!actions.isEnabled(context) && args.firstOrNull() != "enable") {
            logs.add(LogKind.ERROR, "notification access is off — tap to enable it", ctx = LogContext.of(LogContext.RUN to "notifs enable"))
            return
        }
        when (args.firstOrNull()) {
            null, "all" -> {
                val list = if (args.firstOrNull() == "all") store.items.value.take(40) else store.active().take(40)
                if (list.isEmpty()) return info("no notifications")
                list.forEach { n ->
                    logs.add(
                        LogKind.SCRIPT, n.line, n.app,
                        LogContext.of(
                            LogContext.PKG to n.pkg, LogContext.USER to n.userSerial.toString(), LogContext.COPY to n.line,
                            io.github.rscipher001.launcher.notifications.KolahalNotificationListener.CTX_KEY to n.key,
                        ),
                    )
                }
            }
            "enable" -> startActivity(actions.settingsIntent(context))
            "mute", "unmute" -> withApp(args.drop(1)) { app ->
                if (args[0] == "mute") prefs.mute(app.pkg) else prefs.unmute(app.pkg)
                info("${app.label}: notifications ${if (args[0] == "mute") "won't" else "will"} appear in the log")
            }
            "muted" -> {
                val muted = prefs.muted.value
                if (muted.isEmpty()) info("no muted apps")
                muted.forEach { pkg -> out(apps.apps.value.firstOrNull { it.pkg == pkg }?.label ?: pkg, LogContext.of(LogContext.COMMAND to "notifs unmute $pkg")) }
            }
            "log" -> {
                prefs.logToTerminal = args.getOrNull(1) != "off"
                info("notifications ${if (prefs.logToTerminal) "appear" else "no longer appear"} in the log")
            }
            "clear" -> {
                actions.dismissAll()
                store.clear()
                info("notifications cleared")
            }
            else -> error("usage: notifs [all|enable|mute <app>|unmute <app>|muted|log on|off|clear]")
        }
    }

    // ---- contacts ---------------------------------------------------------------------------

    private val contacts = io.github.rscipher001.launcher.contacts.ContactsRepository.get(context)

    /** Resolves a name to one contact; null (with a message) when permission is missing or ambiguous. */
    private fun resolveContact(query: String): io.github.rscipher001.launcher.contacts.Contact? {
        if (!contacts.hasPermission()) {
            logs.add(LogKind.ERROR, "contacts permission needed — tap to allow", ctx = LogContext.of(LogContext.RUN to "contacts --allow"))
            ui.requestContactsPermission()
            return null
        }
        val matches = contacts.search(query)
        if (matches.isEmpty()) { error("no contact matches '$query'"); return null }
        if (matches.size > 1 && !matches[0].name.equals(query, ignoreCase = true)) {
            matches.take(8).forEach(::contactLine)
            info("${matches.size} contacts match — tap one to call, long-press for more")
            return null
        }
        return matches[0]
    }

    private fun contactLine(c: io.github.rscipher001.launcher.contacts.Contact) = out(
        (if (c.starred) "★ " else "") + c.name,
        LogContext.of(LogContext.PHONE to c.number, LogContext.CONTACT to c.lookupUri.toString(), LogContext.DESC to "${c.number} · ${c.label}"),
    )

    private fun call(rest: String) {
        if (rest.isBlank()) return error("usage: call <name|number>")
        val number = if (io.github.rscipher001.launcher.contacts.ContactsRepository.looksLikeNumber(rest)) rest else resolveContact(rest)?.number ?: return
        startActivity(Intent(Intent.ACTION_DIAL, Uri.fromParts("tel", number, null)))
        info("dialing $number", LogContext.of(LogContext.PHONE to number))
    }

    private fun sms(args: List<String>) {
        val who = args.firstOrNull() ?: return error("usage: sms <name|number> [message]")
        val number = if (io.github.rscipher001.launcher.contacts.ContactsRepository.looksLikeNumber(who)) who else resolveContact(who)?.number ?: return
        val body = args.drop(1).joinToString(" ")
        startActivity(Intent(Intent.ACTION_SENDTO, Uri.fromParts("smsto", number, null)).putExtra("sms_body", body))
        info("message to $number", LogContext.of(LogContext.PHONE to number))
    }

    private fun listContacts(query: String) {
        if (query == "--allow" || !contacts.hasPermission()) {
            if (contacts.hasPermission()) return info("contacts are allowed")
            ui.requestContactsPermission()
            return
        }
        val list = if (query.isBlank()) contacts.contacts.value.filter { it.starred }.ifEmpty { contacts.contacts.value.take(20) } else contacts.search(query, 20)
        if (list.isEmpty()) return info(if (query.isBlank()) "no contacts" else "no contact matches '$query'")
        list.forEach(::contactLine)
    }

    // ---- screen time ------------------------------------------------------------------------

    /** `usage [days]`: foreground time per app from UsageStats (needs Usage Access). */
    private fun usage(args: List<String>) {
        if (!unlocks.hasUsageAccess()) {
            logs.add(LogKind.ERROR, "usage needs Usage Access — tap to grant it", ctx = LogContext.of(LogContext.RUN to "usage-access"))
            return
        }
        val days = args.firstOrNull()?.toIntOrNull()?.coerceIn(1, 30) ?: 1
        val zone = java.time.ZoneId.systemDefault()
        val start = java.time.LocalDate.now(zone).minusDays((days - 1).toLong()).atStartOfDay(zone).toInstant().toEpochMilli()
        val usm = context.getSystemService(android.app.usage.UsageStatsManager::class.java)
        val totals = usm.queryAndAggregateUsageStats(start, System.currentTimeMillis())
            .mapValues { it.value.totalTimeInForeground }
            .filter { it.value >= 60_000 && it.key != context.packageName }
            .entries.sortedByDescending { it.value }
        if (totals.isEmpty()) return info("no app usage recorded ${if (days == 1) "today" else "in $days days"}")
        val sum = totals.sumOf { it.value }
        info("screen time ${if (days == 1) "today" else "last $days days"}: ${duration(sum)}")
        val max = totals.first().value
        totals.take(12).forEach { (pkg, ms) ->
            val app = apps.apps.value.firstOrNull { it.pkg == pkg && it.profile.kind == ProfileKind.PERSONAL }
                ?: apps.apps.value.firstOrNull { it.pkg == pkg }
            val bar = "█".repeat((ms * 12 / max).toInt().coerceAtLeast(1))
            out(
                app?.label ?: pkg,
                (if (app != null) appCtx(app) else LogContext.EMPTY) + mapOf(LogContext.DESC to "${duration(ms)}  $bar"),
            )
        }
    }

    private fun duration(ms: Long): String {
        val minutes = ms / 60_000
        return if (minutes >= 60) "${minutes / 60}h ${minutes % 60}m" else "${minutes}m"
    }


    /** Asks for the runtime permission when it can; otherwise (pre-13, or turned off) opens settings. */
    private fun notifications(args: List<String>) {
        val permissionMissing = Build.VERSION.SDK_INT >= 33 && context.checkSelfPermission(
            android.Manifest.permission.POST_NOTIFICATIONS,
        ) != PackageManager.PERMISSION_GRANTED
        when {
            args.firstOrNull() == "settings" || (!permissionMissing && !Notifier.canPost(context)) ->
                startActivity(
                    Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                        .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                )
            permissionMissing -> ui.requestNotificationPermission()
            else -> info("notifications are allowed")
        }
    }

    /** `status` lists script status lines; `status clear <name|all>` removes them. */
    private fun statusCommand(args: List<String>) {
        val store = KvStore(context, HostApi.STATUS_SCOPE)
        val all = store.all()
        when (args.firstOrNull()) {
            null, "ls" -> {
                if (all.isEmpty()) info("no status lines — scripts set them with status(text)")
                all.forEach { (name, text) -> out("$name ▸ $text", LogContext.of(LogContext.SCRIPT to name)) }
            }
            "clear" -> {
                val target = args.getOrNull(1) ?: "all"
                (if (target == "all") all.keys else listOf(target)).forEach { store.set(it, null) }
                logs.onStatusChanged()
                info("cleared status: $target")
            }
            else -> error("usage: status [clear <name|all>]")
        }
    }

    /** `clear` screen · `clear history` ↑ recall · `clear all` everything (log + history). */
    private fun clear(args: List<String>) {
        when (args.firstOrNull()) {
            null, "screen" -> ui.clearScreen()
            "history" -> {
                logs.clearHistory()
                info("command history cleared")
            }
            "all", "log" -> logs.clearAll()
            else -> error("usage: clear [screen|history|all]")
        }
    }

    /** `restart` relaunches the launcher; `restart scripts` stops the :script process (stuck scripts). */
    private fun restart(args: List<String>) {
        when (args.firstOrNull()) {
            null, "launcher" -> {
                info("restarting…")
                android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                    context.startActivity(
                        Intent(context, io.github.rscipher001.launcher.MainActivity::class.java)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                    )
                    android.os.Process.killProcess(android.os.Process.myPid())
                }, 300)
            }
            "scripts", "script" -> {
                val am = context.getSystemService(android.app.ActivityManager::class.java)
                val scriptProcess = am.runningAppProcesses.orEmpty().firstOrNull { it.processName.endsWith(":script") }
                if (scriptProcess == null) {
                    info("script process isn't running")
                } else {
                    android.os.Process.killProcess(scriptProcess.pid)
                    info("script process stopped; scheduled scripts will start it again")
                }
            }
            else -> error("usage: restart [scripts]")
        }
    }

    /** First-run checklist; each unchecked line runs its fix when tapped. */
    /** Text form of the setup checklist (`setup text`); unchecked lines run their fix when tapped. */
    fun setupText() {
        info("setup — tap an unchecked item to fix it, run 'setup text' again to re-check")
        SetupChecks.run(context).forEach { c ->
            val mark = when {
                c.ok -> "[x]"
                c.fix == null -> "[-]"
                else -> "[ ]"
            }
            out("$mark ${c.title} — ${c.detail}", if (c.ok || c.fix == null) LogContext.EMPTY else LogContext.of(LogContext.RUN to c.fix))
        }
    }

    private fun startSettings(action: String) = startActivity(Intent(action))

    private fun startActivity(intent: Intent) {
        context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    private fun help() {
        val byName = COMMANDS.associateBy { it.name }
        val placed = HashSet<String>()
        for ((section, names) in HELP_SECTIONS) {
            info(section)
            names.mapNotNull(byName::get).forEach { spec ->
                placed += spec.name
                out(spec.usage, LogContext.of(LogContext.COMMAND to spec.name + " ", LogContext.DESC to spec.help))
            }
        }
        COMMANDS.filter { it.name !in placed }.forEach { spec ->
            out(spec.usage, LogContext.of(LogContext.COMMAND to spec.name + " ", LogContext.DESC to spec.help))
        }
        val commandScripts = scripts.all().filter { ScriptMeta.isCommand(it.code) }
        if (commandScripts.isNotEmpty()) info("your script commands")
        commandScripts.forEach { s ->
            val desc = ScriptMeta.description(s.code) ?: "${s.lang.ext} script"
            out(s.name, LogContext.of(LogContext.COMMAND to s.name + " ", LogContext.SCRIPT to s.name, LogContext.DESC to desc))
        }
        info("anything else is matched against app names · tap a line to act · long-press for more")
        info("pipes: help | grep feed · history | tail 5 · contacts | count  (grep [-v], head, tail, sort, uniq, count)")
    }

    // ---- config ---------------------------------------------------------------------------

    /** [raw] is the untokenized argument text, so free-text values (e.g. a key bar with quotes) survive. */
    private fun config(args: List<String>, raw: String = "") {
        val sub = args.firstOrNull()
        when {
            sub == null || sub == "ls" || sub == "list" -> {
                Keys.ALL.forEach { k ->
                    val marker = if (conf.isDefault(k)) "" else " *"
                    out(
                        "${k.key} = ${conf.display(k)}$marker",
                        LogContext.of(LogContext.COMMAND to "config set ${k.key} ", LogContext.DESC to k.help),
                    )
                }
                info("tap a line to edit it · * = changed from default · 'config reset <key|all>'")
            }
            sub == "get" -> withKey(args.getOrNull(1)) { out("${it.key} = ${conf.display(it)}") }
            sub == "reset" -> {
                if (args.getOrNull(1) == "all") {
                    Keys.ALL.forEach(conf::reset)
                    info("all settings reset")
                } else withKey(args.getOrNull(1)) {
                    conf.reset(it)
                    info("${it.key} = ${conf.display(it)} (default)")
                }
            }
            sub == "set" -> setKey(args.getOrNull(1), args.drop(2).joinToString(" "), raw)
            Keys.byName(sub) != null -> setKey(sub, args.drop(1).joinToString(" "), raw)
            else -> error("usage: config [ls|get|set|reset] <key> [value]")
        }
    }

    private inline fun withKey(name: String?, action: (ConfigKey<*>) -> Unit) {
        val key = name?.let(Keys::byName)
        if (key == null) error("unknown setting '${name.orEmpty()}' — run 'config'") else action(key)
    }

    private fun setKey(name: String?, parsed: String, raw: String) = withKey(name) { key ->
        val value = if (key is ConfigKey.Text && raw.contains(key.key)) raw.substringAfter(key.key).trim() else parsed
        if (value.isBlank()) return error("${key.key} = ${conf.display(key)}  (${hint(key)})")
        if (conf.set(key, value)) info("${key.key} = ${conf.display(key)}")
        else error("invalid value for ${key.key}: ${hint(key)}")
    }

    private fun hint(key: ConfigKey<*>) = when (key) {
        is ConfigKey.Bool -> "on|off"
        is ConfigKey.Num -> "${key.range.first}..${key.range.last}"
        is ConfigKey.Choice -> key.choices.joinToString("|")
        is ConfigKey.Text -> key.help
    }

    // ---- scripts --------------------------------------------------------------------------

    private fun script(args: List<String>) {
        val sub = args.firstOrNull() ?: "ls"
        val target = args.getOrNull(1)
        when (sub) {
            "ls", "list" -> listScripts()
            "triggers", "events" -> listTriggers()
            "dir", "folder" -> {
                folder.uri?.let { info("script folder: ${it.lastPathSegment}") }
                ui.pickScriptFolder()
            }
            // Folder sync can be slow on SAF providers; run it off the serial command queue.
            "lib", "library" -> {
                val lib = ScriptLibrary(context)
                val installed = scripts.all()
                info("script library — 'script install <name>', or browse it in 'scripts'")
                lib.entries.forEach { e ->
                    val state = when (lib.state(e, installed)) {
                        ScriptLibrary.State.NOT_INSTALLED -> ""
                        ScriptLibrary.State.INSTALLED -> " ✓"
                        ScriptLibrary.State.MODIFIED -> " (edited)"
                        ScriptLibrary.State.UPDATE_AVAILABLE -> " (update available)"
                    }
                    out("${e.name}.${e.lang.ext}$state", LogContext.of(LogContext.COMMAND to "script install ${e.name}", LogContext.DESC to e.description))
                }
            }
            "install" -> {
                val lib = ScriptLibrary(context)
                val entry = lib.entries.firstOrNull { it.name == target?.lowercase()?.substringBefore('.') }
                    ?: return error("no library script '${target.orEmpty()}' — 'script lib' lists them")
                val s = lib.install(entry)
                info("installed ${s.name}.${s.lang.ext}" + (if (entry.command) " — run it as '${s.name}'" else "") +
                    (if (s.intervalMin > 0) " · every ${formatInterval(s.intervalMin)}" else ""), scriptCtx(s))
            }
            "export" -> background("export") { info("exported ${folder.export()} scripts + kolahal.json") }
            "import" -> background("import") {
                val r = folder.import()
                info("imported: ${r.created} new, ${r.updated} updated" + if (r.skipped.isNotEmpty()) ", skipped ${r.skipped.joinToString()}" else "")
            }
            "new" -> {
                val (name, lang) = parseName(target ?: return error("usage: script new <name>[.lua|.js] [lua|js] [--cmd]"))
                if (!Regex("^[a-z0-9_-]{1,40}$").matches(name)) return error("script names: lowercase letters, digits, - and _ only")
                if (name in COMMANDS.map { it.name }) return error("'$name' is a built-in command, pick another name")
                val asCommand = "--cmd" in args || "cmd" in args
                val chosen = args.drop(2).firstNotNullOfOrNull(ScriptLang::of) ?: lang ?: ScriptLang.LUA
                if (scripts.byName(name) != null) return error("script '$name' exists — 'script edit $name'")
                val template = if (asCommand) ScriptTemplates.command(chosen, name) else ScriptTemplates.forLang(chosen, name)
                val script = scripts.create(name, chosen, template)
                info("created ${script.name}.${script.lang.ext}", scriptCtx(script))
                ui.openEditor(script)
            }
            "edit" -> withScript(target) { ui.openEditor(it) }
            "run" -> withScript(target) {
                ScriptScheduler.runNow(context, it)
                info("running ${it.name}…", scriptCtx(it))
            }
            "every" -> withScript(target) { s ->
                val minutes = parseInterval(args.getOrNull(2))
                    ?: return error("usage: script every <name> <15m|30m|2h|1d|off>")
                val effective = if (minutes > 0) minutes.coerceAtLeast(ScriptScheduler.MIN_INTERVAL_MIN) else 0
                scripts.setInterval(s.id, effective)
                if (effective > 0 && !s.enabled) scripts.setEnabled(s.id, true)
                scripts.byId(s.id)?.let { ScriptScheduler.sync(context, it) }
                info(
                    if (effective == 0) "${s.name}: timer off" else "${s.name}: runs every ${formatInterval(effective)}" +
                        if (minutes in 1 until ScriptScheduler.MIN_INTERVAL_MIN) " (Android minimum is 15m)" else "",
                    scriptCtx(s),
                )
            }
            "at" -> withScript(target) { s ->
                val spec = args.getOrNull(2) ?: return error("usage: script at <name> <HH:mm[,HH:mm…]|off>")
                val times = if (spec == "off") emptyList() else spec.split(',').map { parseClock(it) ?: return error("bad time '$it' — use HH:mm") }
                scripts.setAtTimes(s.id, times.distinct().sorted())
                if (times.isNotEmpty() && !s.enabled) scripts.setEnabled(s.id, true)
                scripts.byId(s.id)?.let { ScriptScheduler.sync(context, it) }
                info(if (times.isEmpty()) "${s.name}: no daily times" else "${s.name}: daily at ${times.distinct().sorted().joinToString(", ")}", scriptCtx(s))
            }
            "notify" -> withScript(target) { s ->
                val on = when (args.getOrNull(2)) {
                    "on" -> true
                    "off" -> false
                    else -> return error("usage: script notify <name> on|off")
                }
                scripts.setNotify(s.id, on)
                info("${s.name}: notifications ${if (on) "on" else "off"}", scriptCtx(s))
                if (on && !Notifier.canPost(context)) ui.requestNotificationPermission()
            }
            "on", "off", "enable", "disable" -> withScript(target) { s ->
                val enabled = sub == "on" || sub == "enable"
                scripts.setEnabled(s.id, enabled)
                scripts.byId(s.id)?.let { ScriptScheduler.sync(context, it) }
                info("${s.name}: ${if (enabled) "enabled" else "disabled"}", scriptCtx(s))
            }
            "rm", "delete" -> withScript(target) { s ->
                ScriptScheduler.cancel(context, s.id)
                scripts.delete(s.id)
                KvStore(context, HostApi.STATUS_SCOPE).set(s.name, null)
                logs.onStatusChanged()
                info("deleted ${s.name}")
            }
            "cat", "show" -> withScript(target) { s ->
                s.code.lines().forEachIndexed { i, l -> out("${(i + 1).toString().padStart(3)}  $l", LogContext.of(LogContext.COPY to s.code)) }
            }
            else -> error("usage: script [ls|new|edit|run|every|at|notify|on|off|triggers|rm|cat] <name>")
        }
    }

    private fun listScripts() {
        val all = scripts.all()
        if (all.isEmpty()) {
            info("no scripts yet — try 'script new hn' or 'script new feed.js'")
            return
        }
        val df = DateFormat.getTimeInstance(DateFormat.SHORT)
        all.forEach { s ->
            val timer = listOfNotNull(
                s.intervalMin.takeIf { it > 0 }?.let { "every ${formatInterval(it)}" },
                s.atTimes.takeIf { it.isNotEmpty() }?.let { "at " + it.joinToString(",") },
            ).joinToString(" ").ifEmpty { "manual" }
            val last = s.lastRun?.let { "${df.format(Date(it))} ${s.lastStatus.orEmpty()}" } ?: "never run"
            val parts = buildList {
                add(timer)
                if (ScriptMeta.isCommand(s.code)) add("command")
                ScriptMeta.events(s.code).takeIf { it.isNotEmpty() }?.let { add("on " + it.joinToString(",")) }
                if (s.notify) add("notify")
                if (!s.enabled) add("disabled")
                add(last)
            }
            out("${s.name}.${s.lang.ext}", scriptCtx(s) + mapOf(LogContext.DESC to parts.joinToString(" · ")))
        }
    }

    /** Scripts with `@on` lines and the events they run on. */
    private fun listTriggers() {
        val subscribed = scripts.all().mapNotNull { s ->
            val events = ScriptMeta.events(s.code)
            val unknown = ScriptMeta.unknownEvents(s.code)
            if (events.isEmpty() && unknown.isEmpty()) null else Triple(s, events, unknown)
        }
        if (subscribed.isEmpty()) {
            info("no script has an @on line — add '-- @on unlock' (Lua) or '// @on charger' (JS)")
        }
        subscribed.forEach { (s, events, unknown) ->
            val parts = buildList {
                add(if (events.isEmpty()) "no known events" else "on " + events.joinToString(", "))
                if (unknown.isNotEmpty()) add("unknown: " + unknown.joinToString(", "))
                if (!s.enabled) add("disabled")
            }
            out("${s.name}.${s.lang.ext}", scriptCtx(s) + mapOf(LogContext.DESC to parts.joinToString(" · ")))
        }
        info("events: ${ScriptMeta.EVENTS.joinToString(" ")}")
    }

    private inline fun withScript(name: String?, action: (Script) -> Unit) {
        if (name == null) return error("script name required")
        val script = scripts.byName(parseName(name).first)
        if (script == null) error("no script named '$name' — 'script ls'") else action(script)
    }

    private fun scriptCtx(s: Script) = LogContext.of(LogContext.SCRIPT to s.name)

    private fun parseName(raw: String): Pair<String, ScriptLang?> {
        val ext = raw.substringAfterLast('.', "")
        val lang = ScriptLang.of(ext)
        return (if (lang != null) raw.substringBeforeLast('.') else raw).lowercase() to lang
    }

    companion object {
        val COMMANDS = listOf(
            CommandSpec("help", "help", "show this list"),
            *io.github.rscipher001.launcher.control.ControlCommands.SPECS.toTypedArray(),
            CommandSpec("apps", "apps [work|personal|private|hidden] [-l]", "app grid (-l: one per line)"),
            CommandSpec("open", "open <app>[:shortcut] [-w|-p|-s]", "launch an app or one of its shortcuts"),
            CommandSpec("shortcuts", "shortcuts <app>", "an app's launcher shortcuts"),
            CommandSpec("info", "info <app> [-w]", "open app info"),
            CommandSpec("uninstall", "uninstall <app> [-w]", "uninstall an app"),
            CommandSpec("work", "work [on|off|status]", "pause / resume the work profile"),
            CommandSpec("private", "private [on|off|status]", "lock / unlock private space"),
            CommandSpec("unlocks", "unlocks [days]", "unlock counts"),
            CommandSpec("script", "script <sub> <name>", "ls lib install new edit run every at notify on off triggers rm cat dir export import"),
            CommandSpec("=", "= <expr>", "calculator · = 5 km in mi · = 72 f to c · = 100 usd in eur"),
            CommandSpec("lua", "lua <code>", "evaluate Lua 5.4"),
            CommandSpec("js", "js <code>", "evaluate JavaScript (QuickJS)"),
            CommandSpec("rss", "rss <url>", "fetch a feed into the log once"),
            CommandSpec("feed", "feed [add <url>|rm|fetch|read|open|notify|every] [id]", "RSS/Atom subscriptions with unread counts (no args: text list)"),
            CommandSpec("feeds", "feeds", "feed manager screen (add, intervals, notifications, OPML)"),
            CommandSpec("notifs", "notifs [all|enable|mute <app>|muted|clear]", "your notifications in the terminal"),
            CommandSpec("search", "search <query>", "web search (engine: config search.engine)"),
            CommandSpec("yt", "yt <query>", "search YouTube"),
            CommandSpec("wiki", "wiki <query>", "search Wikipedia"),
            CommandSpec("maps", "maps <place>", "find a place in your maps app"),
            CommandSpec("play", "play <query>", "search the Play Store"),
            CommandSpec("usage", "usage [days]", "screen time per app"),
            CommandSpec("call", "call <name|number>", "dial a contact or number"),
            CommandSpec("sms", "sms <name|number> [text]", "start a text message"),
            CommandSpec("contacts", "contacts [query]", "find contacts (favourites when empty)"),
            CommandSpec("url", "url <address>", "open a link"),
            CommandSpec("history", "history", "recent commands (tap to reuse)"),
            CommandSpec("clear", "clear [history|all]", "clear the screen, the ↑ history, or both"),
            CommandSpec("restart", "restart [scripts]", "restart the launcher, or stop running scripts"),
            CommandSpec("status", "status [clear <name|all>]", "header lines pinned by scripts"),
            CommandSpec("setup", "setup [text]", "setup checklist page (text: print it in the log)"),
            CommandSpec("notifications", "notifications", "allow script notifications"),
            CommandSpec("usage-access", "usage-access", "grant usage access (unlock count)"),
            CommandSpec("battery", "battery", "battery optimization settings"),
            CommandSpec("home", "home", "choose the default launcher"),
            CommandSpec("lock", "lock", "lock the screen (needs the accessibility service)"),
            CommandSpec("shade", "shade", "pull down the notification shade"),
            CommandSpec("accessibility", "accessibility", "enable the screen-lock service for double-tap"),
            CommandSpec("settings", "settings", "Kolahal settings screen (+ backup import/export)"),
            CommandSpec("scripts", "scripts", "script manager screen (import files, timers)"),
            CommandSpec("system", "system", "Android system settings"),
            CommandSpec("config", "config [ls|set|get|reset] <key> <v>", "settings (no args opens the settings page)"),
            CommandSpec("theme", "theme <name>", "green amber mono dracula solarized gruvbox gruvbox-material"),
            CommandSpec("date", "date", "current date and time"),
            CommandSpec("echo", "echo <text>", "print text"),
            CommandSpec("alias", "alias [name = command]", "list or define aliases"),
            CommandSpec("unalias", "unalias <name>", "remove an alias"),
            CommandSpec("pin", "pin <app>", "favourite: first in the empty-prompt strip (pins lists them)"),
            CommandSpec("unpin", "unpin <app>", "remove a favourite"),
            CommandSpec("hide", "hide <app> [-w]", "hide an app from lists and search"),
            CommandSpec("unhide", "unhide <app>", "show a hidden app again ('apps hidden')"),
            CommandSpec("rename", "rename <app> = <name>", "custom label (empty restores)"),
        )

        /** `help` groups; commands not listed here are shown after the groups. */
        val HELP_SECTIONS = linkedMapOf(
            "apps" to listOf("open", "shortcuts", "apps", "pin", "unpin", "info", "uninstall", "hide", "unhide", "rename", "usage"),
            "people" to listOf("call", "sms", "contacts"),
            "web" to listOf("search", "yt", "wiki", "maps", "play", "url"),
            "feeds" to listOf("feeds", "feed", "rss", "notifs"),
            "control" to io.github.rscipher001.launcher.control.ControlCommands.SPECS.map { it.name },
            "profiles" to listOf("work", "private"),
            "scripts" to listOf("scripts", "script", "lua", "js", "=", "status"),
            "terminal" to listOf("help", "history", "clear", "restart", "alias", "unalias", "config", "theme", "echo", "date"),
            "device" to listOf("settings", "setup", "unlocks", "notifications", "usage-access", "battery", "home", "lock", "shade", "accessibility", "system"),
        )

        val SCRIPT_SUBCOMMANDS = listOf("ls", "lib", "install", "new", "edit", "run", "every", "at", "notify", "on", "off", "triggers", "rm", "cat", "dir", "export", "import")

        fun tokenize(s: String): List<String> {
            val out = ArrayList<String>()
            val cur = StringBuilder()
            var quote: Char? = null
            for (ch in s) {
                when {
                    quote != null && ch == quote -> quote = null
                    quote != null -> cur.append(ch)
                    ch == '"' || ch == '\'' -> quote = ch
                    ch.isWhitespace() -> if (cur.isNotEmpty()) { out += cur.toString(); cur.clear() }
                    else -> cur.append(ch)
                }
            }
            if (cur.isNotEmpty()) out += cur.toString()
            return out
        }

        /** "30m", "2h", "1d", "45" (minutes), "off" -> minutes (0 = off). */
        fun parseInterval(s: String?): Int? {
            if (s == null) return null
            if (s == "off" || s == "0" || s == "manual") return 0
            val m = Regex("^(\\d+)([mhd]?)$").find(s.lowercase()) ?: return null
            val n = m.groupValues[1].toInt()
            return when (m.groupValues[2]) {
                "h" -> n * 60
                "d" -> n * 60 * 24
                else -> n
            }
        }

        /** "8:00", "08:00", "18:30" -> "HH:mm"; null if invalid. */
        fun parseClock(s: String): String? {
            val m = Regex("^([01]?\\d|2[0-3]):([0-5]\\d)$").find(s.trim()) ?: return null
            return String.format(java.util.Locale.ROOT, "%02d:%s", m.groupValues[1].toInt(), m.groupValues[2])
        }

        /** Levenshtein distance, for "did you mean". */
        fun editDistance(a: String, b: String): Int {
            var prev = IntArray(b.length + 1) { it }
            for (i in 1..a.length) {
                val cur = IntArray(b.length + 1)
                cur[0] = i
                for (j in 1..b.length) {
                    val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                    cur[j] = minOf(prev[j] + 1, cur[j - 1] + 1, prev[j - 1] + cost)
                }
                prev = cur
            }
            return prev[b.length]
        }

        fun formatInterval(min: Int): String = when {
            min % (60 * 24) == 0 -> "${min / (60 * 24)}d"
            min % 60 == 0 -> "${min / 60}h"
            else -> "${min}m"
        }
    }
}
