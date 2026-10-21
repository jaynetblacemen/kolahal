package com.mtwabpin.kolahal.terminal

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import com.mtwabpin.kolahal.apps.AppEntry
import com.mtwabpin.kolahal.apps.AppPrefs
import com.mtwabpin.kolahal.script.ScriptMeta
import com.mtwabpin.kolahal.script.Json
import com.mtwabpin.kolahal.script.Net
import com.mtwabpin.kolahal.data.KvStore
import com.mtwabpin.kolahal.apps.AppRepository
import com.mtwabpin.kolahal.apps.ProfileKind
import com.mtwabpin.kolahal.data.Config
import com.mtwabpin.kolahal.data.Keys
import com.mtwabpin.kolahal.data.LogContext
import com.mtwabpin.kolahal.data.LogKind
import com.mtwabpin.kolahal.data.LogRepository
import com.mtwabpin.kolahal.data.ScriptLang
import com.mtwabpin.kolahal.data.ScriptRepository
import com.mtwabpin.kolahal.notify.Notifier
import com.mtwabpin.kolahal.script.ScriptScheduler
import com.mtwabpin.kolahal.unlock.UnlockCounter
import com.mtwabpin.kolahal.widgets.listing
import java.text.DateFormat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.util.Date

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
    private val backgroundScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val controls by lazy { com.mtwabpin.kolahal.control.ControlCommands(context) }
    private val productivity = ProductivityCommands(context, ui)
    private val scriptCommands = ScriptCommands(context, ui, ::background)
    private val configCommands = ConfigCommands(context, ui)
    private val people = PeopleCommands(context, ui)

    private fun background(label: String, block: () -> Unit) {
        backgroundScope.launch {
            runCatching(block).onFailure { error("$label: ${it.message ?: it.javaClass.simpleName}") }
        }
    }

    /** [expand] = false runs [line] literally (tap-to-run lines must not be rewritten by aliases). */
    fun run(line: String, expand: Boolean = true) {
        val typed = line.trim()
        if (typed.isEmpty()) return
        // The same command again with nothing printed in between (settings, settings…) is one line.
        // `fresh` isn't logged at all: it would put a line straight back on the clean screen.
        if (typed.lowercase() != "fresh" && logs.last() != (LogKind.COMMAND to typed)) logs.add(LogKind.COMMAND, typed, ctx = LogContext.of(LogContext.COMMAND to typed))
        // Aliases first, so `alias hf = help | grep feed` pipes too.
        val stages = Pipes.split(if (expand) expandAliases(typed) else typed)
        if (stages.size > 1) {
            // `cmd | grep x | head 5`: capture what the command prints right away, then filter it.
            val (_, captured) = logs.capture { execute(stages[0], expand = false) }
            Pipes.apply(captured, stages.drop(1)).fold(
                onSuccess = { logs.addAll(it) },
                onFailure = { logs.addAll(captured); error(it.message ?: "Bad pipe") },
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
        val args = CommandParsing.tokenize(rest)
        runCatching {
            when (name) {
                "alias" -> alias(rest)
                "unalias" -> unalias(args)
                "hide" -> withApp(args) { setHidden(it, true) }
                "pin", "fav" -> if (args.isEmpty()) listPinned() else withApp(args) { app -> prefs.setPinned(app.key, true); info("Pinned ${app.label} — it leads the suggestions on an empty prompt") }
                "unpin", "unfav" -> withApp(args) { app -> prefs.setPinned(app.key, false); info("Unpinned ${app.label}") }
                "pins", "favs" -> listPinned()
                "unhide" -> withApp(args, includeHidden = true) { setHidden(it, false) }
                "rename" -> rename(args)
                "help", "?" -> help()
                "apps", "ls" -> listApps(args)
                "open", "launch" -> open(args)
                "info" -> withApp(args) { apps.openAppInfo(it); info("App info: ${it.label}") }
                "uninstall" -> withApp(args) { apps.uninstall(it) }
                "work" -> profileToggle(ProfileKind.WORK, args)
                "private" -> profileToggle(ProfileKind.PRIVATE, args)
                "unlocks" -> unlocks(args)
                "clear", "cls" -> clear(args)
                "fresh" -> ui.freshScreen()
                "restart", "reboot" -> restart(args)
                "history" -> history()
                "scripts" -> if (args.isEmpty()) ui.openScreen(Screen.SCRIPTS) else scriptCommands.script(args)
                "script" -> scriptCommands.script(args)
                "lua" -> eval(ScriptLang.LUA, rest)
                "js" -> eval(ScriptLang.JS, rest)
                "rss" -> rss(rest)
                "feeds" -> if (args.isEmpty()) ui.openScreen(Screen.FEEDS) else feeds(args)
                // A feed added, removed, switched or given a new interval changes the schedule.
                "feed" -> feeds(args).also { runCatching { com.mtwabpin.kolahal.feeds.FeedScheduler.sync(context) } }
                "widgets" -> if (args.isEmpty()) ui.openScreen(Screen.WIDGETS) else widgets(args)
                "widget" -> widgets(args)
                "notifs", "notifications-feed" -> notifs(args)
                "url", "web" -> openUrl(rest)
                "search", "g" -> search(rest)
                "yt", "youtube" -> search(rest, "youtube")
                "wiki" -> search(rest, "wikipedia")
                "maps", "map" -> if (rest.isBlank()) fallbackLaunch(trimmed) else openUri("geo:0,0?q=" + Uri.encode(rest), "maps: $rest")
                "play", "store" -> if (rest.isBlank()) fallbackLaunch(trimmed) else openUri("market://search?q=" + Uri.encode(rest), "play store: $rest")
                "usage", "screentime" -> usage(args)
                "installs", "updates" -> installs(args)
                "calls" -> people.calls(args)
                "shortcuts" -> withApp(args) { app -> listShortcuts(app) }
                "call", "dial" -> people.call(rest)
                "sms", "text" -> people.sms(args)
                "contacts", "contact" -> people.listContacts(rest)
                "notifications" -> notifications(args)
                "setup" -> if (args.firstOrNull() == "text") setupText() else ui.openScreen(Screen.SETUP)
                "status" -> scriptCommands.status(args)
                "usage-access" -> startActivity(unlocks.usageAccessIntent())
                "battery" -> startSettings(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
                "settings", "prefs" -> ui.openScreen(Screen.SETTINGS)
                "system" -> startSettings(Settings.ACTION_SETTINGS)
                "home" -> startSettings(Settings.ACTION_HOME_SETTINGS)
                "lock", "accessibility" -> if (!com.mtwabpin.kolahal.BuildConfig.LOCK_SERVICE) {
                    error("The lite build has no accessibility service, so it can't lock the screen")
                } else if (name == "accessibility") {
                    startActivity(com.mtwabpin.kolahal.gesture.LockService.settingsIntent())
                } else if (!com.mtwabpin.kolahal.gesture.LockService.lock()) {
                    logs.add(LogKind.ERROR, "Locking needs the Kolahal screen lock accessibility service — tap to enable it", ctx = LogContext.of(LogContext.RUN to "accessibility"))
                }
                "shade" -> com.mtwabpin.kolahal.gesture.Gestures.expandShade(context)
                "date", "time" -> out(DateFormat.getDateTimeInstance().format(Date()))
                "echo" -> out(rest)
                "config", "set" -> if (name == "config" && args.isEmpty()) ui.openScreen(Screen.SETTINGS) else configCommands.config(args, rest)
                "theme" -> configCommands.config(listOf("set", "theme") + args)
                "font" -> configCommands.font(args)
                in com.mtwabpin.kolahal.control.ControlCommands.NAMES -> controls.run(name, args)
                in PRODUCTIVITY -> productivity.run(name, rest)
                else -> scriptCommands.runCommand(name, args) ?: fallbackLaunch(trimmed)
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
            // A built-in always wins, even over an alias that slipped in (an old backup).
            if (head.lowercase() in COMMAND_NAMES) return current
            if (!seen.add(head)) return current
            val expansion = prefs.alias(head) ?: return current
            current = (expansion + " " + current.substringAfter(' ', "")).trim()
        }
        return current
    }

    private fun alias(rest: String) {
        if (rest.isBlank()) {
            val all = prefs.aliases()
            if (all.isEmpty()) info("No aliases — e.g. alias m = open maps")
            all.forEach { (k, v) -> out("$k = $v", LogContext.of(LogContext.COMMAND to k)) }
            return
        }
        val name = rest.substringBefore('=').trim().substringBefore(' ')
        val expansion = rest.substringAfter('=', "").trim().removeSurrounding("\"").removeSurrounding("'")
        when {
            !rest.contains('=') -> prefs.alias(name)?.let { out("$name = $it") } ?: error("No alias '$name'")
            name.isEmpty() || expansion.isEmpty() -> error("Usage: alias <name> = <command>")
            name in COMMAND_NAMES -> error("'$name' is a built-in command and can't be an alias")
            else -> {
                prefs.setAlias(name, expansion)
                info("Alias $name = $expansion")
            }
        }
    }

    private fun unalias(args: List<String>) {
        val name = args.firstOrNull() ?: return error("Usage: unalias <name>")
        if (prefs.alias(name) == null) return error("No alias '$name'")
        prefs.setAlias(name, null)
        info("Removed alias $name")
    }

    // ---- hide / rename --------------------------------------------------------------------

    private fun installs(args: List<String>) {
        val n = args.firstOrNull()?.toIntOrNull()?.coerceIn(1, 200) ?: 20
        val list = com.mtwabpin.kolahal.apps.PackageEvents.get(context).history().take(n)
        if (list.isEmpty()) return info("No app installs, updates or uninstalls recorded yet")
        val fmt = java.text.SimpleDateFormat("EEE d MMM HH:mm", java.util.Locale.getDefault())
        list.forEach { (t, text) -> out(text, LogContext.of(LogContext.DESC to fmt.format(Date(t)))) }
    }

    private fun listPinned() {
        val byKey = apps.apps.value.associateBy { it.key }
        val pinned = prefs.pinned().mapNotNull { byKey[it] }
        if (pinned.isEmpty()) return info("No pinned apps — pin <app>, or long-press an app → Pin")
        appGrid(pinned)
        info("${pinned.size} pinned · unpin <app> to remove")
    }

    private fun setHidden(app: AppEntry, hidden: Boolean) {
        prefs.setHidden(app.key, hidden)
        apps.refresh()
        info("${if (hidden) "Hidden" else "Unhidden"} ${appLine(app)}", appCtx(app))
    }

    /** `rename <app> = <new label>`, or `rename <app> =` to restore the original. */
    private fun rename(args: List<String>) {
        val eq = args.indexOf("=")
        if (eq <= 0) return error("Usage: rename <app> [-w] = <new name>")
        withApp(args.subList(0, eq), includeHidden = true) { app ->
            val label = args.drop(eq + 1).joinToString(" ").ifBlank { null }
            prefs.setLabel(app.key, label)
            apps.refresh()
            info("${app.originalLabel} → ${label ?: app.originalLabel}", appCtx(app))
        }
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

    /** `Maps [W]`; personal apps are only tagged when the same app also exists in another profile. */
    private fun appLine(app: AppEntry): String {
        val twin = app.profile.kind == ProfileKind.PERSONAL &&
            apps.apps.value.any { it.pkg == app.pkg && it.profile.serial != app.profile.serial }
        return if (app.profile.kind != ProfileKind.PERSONAL || twin) "${app.label} [${app.profile.kind.tag}]" else app.label
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
            info("No apps" + (kind?.let { " in ${it.label} profile" } ?: ""))
            return
        }
        val kinds = list.map { it.profile.kind }.distinct().sortedBy { it.ordinal }
        when {
            "-l" in args -> list.forEach { out(appLine(it), appCtx(it)) }
            // One grid per profile instead of interleaving every app with its work twin.
            kinds.size > 1 -> kinds.forEach { k ->
                info("── ${k.label} ──")
                appGrid(list.filter { it.profile.kind == k })
            }
            else -> appGrid(list)
        }
        info("${list.size} apps · " + kinds.joinToString("  ") { "${it.label} [${it.tag}]" })
    }

    private fun resolveApp(args: List<String>, includeHidden: Boolean = false): List<AppEntry> =
        apps.search(stripFlags(args), profileFlag(args), includeHidden)

    private inline fun withApp(args: List<String>, includeHidden: Boolean = false, action: (AppEntry) -> Unit) {
        val matches = resolveApp(args, includeHidden)
        when {
            matches.isEmpty() -> error("No app matches '${stripFlags(args)}'")
            else -> action(matches.first())
        }
    }

    private fun open(args: List<String>) {
        // `open maps:navigate` starts the shortcut whose label best matches "navigate".
        val joined = stripFlags(args)
        if (joined.contains(':')) {
            val app = apps.search(joined.substringBefore(':'), profileFlag(args)).firstOrNull()
                ?: return error("No app matches '${joined.substringBefore(':')}'")
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
            error("No app matches '${stripFlags(args)}'")
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
            .onSuccess { logs.addReplacing(LogKind.INFO, "Launched ${appLine(app)}", appCtx(app)) }
            .onFailure { error("Can't launch ${app.label}: ${it.message ?: it.javaClass.simpleName}") }
    }

    /** Turns the paused profile back on, waits until it is available, then opens [app]. */
    private fun resumeAndLaunch(app: AppEntry) {
        if (!apps.setQuietMode(app.profile, false)) {
            error("Couldn't resume the ${app.profile.kind.label} profile (is Kolahal the default launcher? run 'home')")
            return
        }
        info("Resuming ${app.profile.kind.label} profile…")
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
        val candidates = COMMAND_NAMES + prefs.aliases().keys +
            scripts.all().filter { ScriptMeta.isCommand(it.code) }.map { it.name }
        val best = candidates.filter { it.length > 1 }
            .map { it to CommandParsing.editDistance(head, it) }
            .filter { (c, d) -> d <= (if (c.length <= 4) 1 else 2) }
            .minByOrNull { it.second }?.first
        if (best == null) {
            error("Command not found: $text  (try 'help')")
        } else {
            val corrected = (best + " " + rest).trim()
            logs.add(
                LogKind.ERROR,
                "Command not found: $head — did you mean '$corrected'? (tap to put it in the prompt)",
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
            info("No ${kind.label} profile on this device")
            return
        }
        when (args.firstOrNull()) {
            null, "status" -> info("${kind.label} profile: ${if (profile.quiet) "paused" else "on"}")
            "on", "off" -> {
                val quiet = args[0] == "off"
                if (Build.VERSION.SDK_INT < 29) {
                    error("Toggling profiles needs Android 10+")
                } else if (apps.setQuietMode(profile, quiet)) {
                    info("${kind.label} profile turning ${args[0]}…")
                } else {
                    error("Couldn't change ${kind.label} profile (is Kolahal the default launcher? run 'home')")
                }
            }
            else -> error("Usage: ${kind.name.lowercase()} [on|off|status]")
        }
    }

    // ---- misc -----------------------------------------------------------------------------

    private fun unlocks(args: List<String>) {
        val days = args.firstOrNull()?.toIntOrNull()?.coerceIn(1, 30) ?: 1
        if (!unlocks.hasUsageAccess()) {
            info("Counting via unlock receiver (misses unlocks while launcher isn't running)")
            info("Tap here to grant Usage Access for accurate counts", LogContext.of(LogContext.RUN to "usage-access"))
        }
        val today = java.time.LocalDate.now()
        val fmt = java.time.format.DateTimeFormatter.ofPattern("EEE d MMM", java.util.Locale.getDefault())
        val rows = unlocks.history(days).map { (day, count) ->
            val label = when (day) {
                today -> "Today"
                today.minusDays(1) -> "Yesterday"
                else -> day.format(fmt)
            }
            label to count
        }
        // Padded so the counts line up (the log is monospaced).
        val labelWidth = rows.maxOf { it.first.length }
        val countWidth = rows.maxOf { it.second.toString().length }
        rows.forEach { (label, count) ->
            out("${label.padEnd(labelWidth)}  ${count.toString().padStart(countWidth)} ${if (count == 1) "unlock" else "unlocks"}")
        }
    }

    private fun history() {
        val cmds = logs.commandHistory(30).reversed()
        cmds.forEach { out(it, LogContext.of(LogContext.COMMAND to it)) }
        if (cmds.isEmpty()) info("History is empty")
    }

    private fun eval(lang: ScriptLang, code: String) {
        if (code.isBlank()) {
            error("Usage: ${lang.ext} <code>")
            return
        }
        if (!ScriptScheduler.eval(context, lang, code)) error("Snippet too long — use 'script new'")
    }

    private fun rss(url: String) {
        if (url.isBlank()) {
            error("Usage: rss <feed url>")
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
        if (raw.isBlank()) return error("Usage: url <address>")
        val url = if (raw.contains("://")) raw else "https://$raw"
        startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        info("Opened $url", LogContext.of(LogContext.URL to url))
    }

    private fun search(query: String, engine: String? = null) {
        if (query.isBlank()) return error("Usage: search <query>")
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
        info("Search: $query", LogContext.of(LogContext.URL to url))
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
                if (com.mtwabpin.kolahal.terminal.SetupChecks.isDefaultLauncher(context)) "${app.label} has no shortcuts"
                else "Shortcuts need Kolahal as the default launcher — run 'home'"
            )
        }
        list.forEach { s ->
            out(
                "${app.label} › ${s.shortLabel}",
                appCtx(app) + mapOf(LogContext.SHORTCUT to s.id, LogContext.DESC to (s.longLabel ?: s.shortLabel ?: "").toString()),
            )
        }
    }

    // ---- widgets ----------------------------------------------------------------------------

    /** `widgets ls` prints the placed widgets (tap opens the page); `widget add [home]` opens the picker. */
    private fun widgets(args: List<String>) {
        val host = com.mtwabpin.kolahal.widgets.WidgetHost.get(context)
        when (args.firstOrNull()) {
            null -> ui.openScreen(Screen.WIDGETS)
            "ls", "list" -> {
                val lines = host.listing(context)
                if (lines.isEmpty()) return info("No widgets yet — widget add (or widget add home) opens the picker", LogContext.of(LogContext.RUN to "widget add"))
                lines.forEach { (text, desc) -> out(text, LogContext.of(LogContext.RUN to "widgets", LogContext.DESC to desc)) }
            }
            "add" -> {
                host.addLocation = com.mtwabpin.kolahal.widgets.WidgetLocation.of(args.getOrNull(1))
                ui.openScreen(Screen.WIDGET_PICKER)
            }
            else -> error("Usage: widgets [ls] · widget add [home]")
        }
    }

    // ---- feeds ------------------------------------------------------------------------------

    /** Built-in RSS/Atom subscriptions (no scripting needed). */
    private fun feeds(args: List<String>) {
        val repo = com.mtwabpin.kolahal.feeds.FeedRepository.get(context)
        val scheduler = com.mtwabpin.kolahal.feeds.FeedScheduler
        val id = args.getOrNull(1)?.toLongOrNull()
        when (args.firstOrNull()) {
            null, "ls", "list" -> {
                val all = repo.all()
                if (all.isEmpty()) return info("No feeds yet — feed add <url> (or 'feeds' for the manager and OPML import)")
                all.forEach { f ->
                    val flags = listOfNotNull(
                        "${f.unread}/${f.total} unread", "every ${CommandParsing.formatInterval(f.intervalMin)}",
                        if (f.notify) "notify" else null, if (!f.enabled) "off" else null, f.lastStatus,
                    ).joinToString(" · ")
                    out(
                        "#${f.id} ${f.title ?: f.url}",
                        LogContext.of(LogContext.COMMAND to "feed read ${f.id}", LogContext.COPY to f.url, LogContext.DESC to flags),
                    )
                }
                info("Tap a feed to read it · feed read = all unread · feed fetch = refresh now")
            }
            "add" -> {
                val url = args.getOrNull(1) ?: return error("Usage: feed add <url>")
                val feed = runCatching { repo.add(url) }.getOrElse { return error(it.message ?: "Bad URL") }
                scheduler.sync(context)
                scheduler.fetchNow(context, feed.id)
                info("Subscribed #${feed.id} ${feed.url} — fetching…", LogContext.of(LogContext.URL to feed.url))
            }
            "rm", "remove" -> {
                id ?: return error("Usage: feed rm <id>")
                repo.remove(id)
                scheduler.sync(context)
                info("Removed feed #$id")
            }
            "fetch", "refresh" -> {
                scheduler.fetchNow(context, id)
                info("Fetching ${id?.let { "feed #$it" } ?: "all feeds"}…")
            }
            "read", "all" -> {
                val unreadOnly = args[0] == "read"
                val items = repo.items(feedId = id, unreadOnly = unreadOnly, limit = 40)
                if (items.isEmpty()) return info(if (unreadOnly) "Nothing unread" else "No items")
                val names = repo.feeds().associate { it.id to it.shortName }
                items.forEach {
                    logs.add(LogKind.SCRIPT, it.title, names[it.feedId] ?: "feed", LogContext.of(LogContext.URL to it.link, LogContext.COPY to it.title, com.mtwabpin.kolahal.feeds.FeedWorker.CTX_ITEM to it.id.toString()))
                }
                // Only what was shown: older unread items beyond the limit stay unread.
                if (unreadOnly) repo.markRead(items.map { it.id })
                val left = if (unreadOnly) repo.unreadCount(id) else 0
                if (left > 0) info("$left more unread — feed read${id?.let { " $it" }.orEmpty()} again")
            }
            "open" -> {
                // Opens the newest unread items (with links) in the browser; only those are marked read.
                val items = repo.items(feedId = id, unreadOnly = true, limit = 40)
                    .filter { it.link != null }.take(MAX_FEED_OPEN)
                if (items.isEmpty()) return info("Nothing unread")
                val opened = items.filter { item ->
                    runCatching { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(item.link))) }.isSuccess
                }
                repo.markRead(opened.map { it.id })
                val left = repo.unreadCount(id)
                val failed = items.size - opened.size
                info(
                    "Opened ${opened.size} item${if (opened.size == 1) "" else "s"}" +
                        (if (failed > 0) " ($failed couldn't be opened)" else "") +
                        (if (left > 0) " · $left still unread" else ""),
                )
            }
            "notify" -> {
                id ?: return error("Usage: feed notify <id> on|off")
                repo.setNotify(id, args.getOrNull(2) != "off")
                info("Feed #$id notifications ${args.getOrNull(2) ?: "on"}")
            }
            "every" -> {
                val minutes = CommandParsing.parseInterval(args.getOrNull(2))
                if (id == null || minutes == null || minutes <= 0) return error("Usage: feed every <id> <30m|2h|…>")
                repo.setInterval(id, minutes)
                info("Feed #$id every ${CommandParsing.formatInterval(minutes.coerceAtLeast(15))}")
            }
            "on", "off" -> {
                id ?: return error("Usage: feed ${args[0]} <id>")
                repo.setEnabled(id, args[0] == "on")
                scheduler.sync(context)
                info("Feed #$id ${args[0]}")
            }
            else -> error("Usage: feed [ls|add <url>|rm|fetch|read|all|open|notify|every|on|off] [id]")
        }
    }

    // ---- notifications ----------------------------------------------------------------------

    private fun notifs(args: List<String>) {
        val actions = com.mtwabpin.kolahal.notifications.NotificationActions
        val store = com.mtwabpin.kolahal.notifications.NotificationStore
        val prefs = com.mtwabpin.kolahal.notifications.NotifPrefs.get(context)
        if (!com.mtwabpin.kolahal.BuildConfig.NOTIFICATION_ACCESS) return error("The lite build has no notification access, so notifs isn't available")
        if (!actions.isEnabled(context) && args.firstOrNull() != "enable") {
            logs.add(LogKind.ERROR, "Notification access is off — tap to enable it", ctx = LogContext.of(LogContext.RUN to "notifs enable"))
            return
        }
        when (args.firstOrNull()) {
            null, "all" -> {
                val list = if (args.firstOrNull() == "all") store.items.value.take(40) else store.active().take(40)
                if (list.isEmpty()) return info("No notifications")
                list.forEach { n ->
                    logs.add(
                        LogKind.SCRIPT, n.line, n.app,
                        LogContext.of(
                            LogContext.PKG to n.pkg, LogContext.USER to n.userSerial.toString(), LogContext.COPY to n.line,
                            com.mtwabpin.kolahal.notifications.KolahalNotificationListener.CTX_KEY to n.key,
                            LogContext.ICON to n.icon,
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
                if (muted.isEmpty()) info("No muted apps")
                muted.forEach { pkg -> out(apps.apps.value.firstOrNull { it.pkg == pkg }?.label ?: pkg, LogContext.of(LogContext.COMMAND to "notifs unmute $pkg")) }
            }
            "log" -> {
                prefs.logToTerminal = args.getOrNull(1) != "off"
                info("Notifications ${if (prefs.logToTerminal) "appear" else "no longer appear"} in the log")
            }
            "clear" -> {
                actions.dismissAll()
                store.clear()
                // Also drop the snapshot scripts read, so cleared notifications don't linger on disk.
                com.mtwabpin.kolahal.notifications.KolahalNotificationListener.clearMirror(context)
                info("Notifications cleared")
            }
            else -> error("Usage: notifs [all|enable|mute <app>|unmute <app>|muted|log on|off|clear]")
        }
    }

    // ---- screen time ------------------------------------------------------------------------

    /** `usage [days]`: foreground time per app from UsageStats (needs Usage Access). */
    private fun usage(args: List<String>) {
        if (!unlocks.hasUsageAccess()) {
            logs.add(LogKind.ERROR, "Usage needs Usage Access — tap to grant it", ctx = LogContext.of(LogContext.RUN to "usage-access"))
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
        if (totals.isEmpty()) return info("No app usage recorded ${if (days == 1) "today" else "in $days days"}")
        val sum = totals.sumOf { it.value }
        info("Screen time ${if (days == 1) "today" else "last $days days"}: ${duration(sum)}")
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
            else -> info("Notifications are allowed")
        }
    }

    /** `clear` screen · `clear history` ↑ recall · `clear all` everything (log + history). */
    private fun clear(args: List<String>) {
        when (args.firstOrNull()) {
            null, "screen" -> ui.clearScreen()
            "history" -> {
                logs.clearHistory()
                info("Command history cleared")
            }
            "all", "log" -> logs.clearAll()
            else -> error("Usage: clear [screen|history|all]")
        }
    }

    /** `restart` relaunches the launcher; `restart scripts` stops the :script process (stuck scripts). */
    private fun restart(args: List<String>) {
        when (args.firstOrNull()) {
            null, "launcher" -> {
                info("Restarting…")
                android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                    context.startActivity(
                        Intent(context, com.mtwabpin.kolahal.MainActivity::class.java)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                    )
                    android.os.Process.killProcess(android.os.Process.myPid())
                }, 300)
            }
            "scripts", "script" -> {
                val am = context.getSystemService(android.app.ActivityManager::class.java)
                val scriptProcess = am.runningAppProcesses.orEmpty().firstOrNull { it.processName.endsWith(":script") }
                if (scriptProcess == null) {
                    info("Script process isn't running")
                } else {
                    android.os.Process.killProcess(scriptProcess.pid)
                    info("Script process stopped; scheduled scripts will start it again")
                }
            }
            else -> error("Usage: restart [scripts]")
        }
    }

    /** First-run checklist; each unchecked line runs its fix when tapped. */
    /** Text form of the setup checklist (`setup text`); unchecked lines run their fix when tapped. */
    fun setupText() {
        info("Setup — tap an unchecked item to fix it, run 'setup text' again to re-check")
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
        if (commandScripts.isNotEmpty()) info("Your script commands")
        commandScripts.forEach { s ->
            val desc = ScriptMeta.description(s.code) ?: "${s.lang.ext} script"
            out(ScriptMeta.usage(s.code, s.name) ?: s.name, LogContext.of(LogContext.COMMAND to s.name + " ", LogContext.SCRIPT to s.name, LogContext.DESC to desc))
        }
        info("Anything else is matched against app names · tap a line to act · long-press for more")
        info("Pipes: help | grep feed · history | tail 5 · contacts | count  (grep [-v], head, tail, sort, uniq, count)")
    }

    companion object {
        /** Most browser tabs one `feed open` launches; the rest stay unread. */
        const val MAX_FEED_OPEN = 5

        val COMMANDS = listOf(
            CommandSpec("help", "help", "Show this list"),
            *com.mtwabpin.kolahal.control.ControlCommands.SPECS.toTypedArray(),
            CommandSpec("apps", "apps [work|personal|private|hidden] [-l]", "App grid (-l: one per line)"),
            CommandSpec("open", "open <app>[:shortcut] [-w|-p|-s]", "Launch an app or one of its shortcuts"),
            CommandSpec("shortcuts", "shortcuts <app>", "An app's launcher shortcuts"),
            CommandSpec("info", "info <app> [-w]", "Open app info"),
            CommandSpec("uninstall", "uninstall <app> [-w]", "Uninstall an app"),
            CommandSpec("work", "work [on|off|status]", "Pause / resume the work profile"),
            CommandSpec("private", "private [on|off|status]", "Lock / unlock private space"),
            CommandSpec("unlocks", "unlocks [days]", "Unlock counts"),
            CommandSpec("script", "script <sub> <name>", "Subcommands: ls lib install new edit run every at notify on off triggers rm cat dir export import"),
            CommandSpec("=", "= <expr>", "Calculator · = 5 km in mi · = 72 f to c · = 100 usd in eur"),
            CommandSpec("lua", "lua <code>", "Evaluate Lua 5.4"),
            CommandSpec("js", "js <code>", "Evaluate JavaScript (QuickJS)"),
            CommandSpec("rss", "rss <url>", "Fetch a feed into the log once"),
            CommandSpec("feed", "feed [add <url>|rm|fetch|read|open|notify|every] [id]", "RSS/Atom subscriptions with unread counts (no args: text list)"),
            CommandSpec("feeds", "feeds", "Feed manager screen (add, intervals, notifications, OPML)"),
            CommandSpec("widgets", "widgets [ls]", "Home-screen widgets page (ls: text list)"),
            CommandSpec("widget", "widget add [home]", "Pick a widget to add (home: show it under the header)"),
            CommandSpec("notifs", "notifs [all|enable|mute <app>|muted|clear]", "Your notifications in the terminal"),
            CommandSpec("search", "search <query>", "Web search (engine: config search.engine)"),
            CommandSpec("yt", "yt <query>", "Search YouTube"),
            CommandSpec("wiki", "wiki <query>", "Search Wikipedia"),
            CommandSpec("maps", "maps <place>", "Find a place in your maps app"),
            CommandSpec("play", "play <query>", "Search the Play Store"),
            CommandSpec("usage", "usage [days]", "Screen time per app"),
            CommandSpec("installs", "installs [n]", "Recent app installs, updates and uninstalls"),
            CommandSpec("call", "call <name|number>", "Dial a contact or number"),
            CommandSpec("sms", "sms <name|number> [text]", "Start a text message"),
            CommandSpec("contacts", "contacts [query]", "Find contacts (favourites when empty)"),
            CommandSpec("calls", "calls [n]", "Recent calls: incoming, outgoing and missed"),
            CommandSpec("url", "url <address>", "Open a link"),
            CommandSpec("history", "history", "Recent commands (tap to reuse)"),
            CommandSpec("clear", "clear [history|all]", "Clear the screen, the ↑ history, or both"),
            CommandSpec("fresh", "fresh", "Start a clean screen now, like log.fresh; earlier lines are one tap away (not logged)"),
            CommandSpec("restart", "restart [scripts]", "Restart the launcher, or stop running scripts"),
            CommandSpec("status", "status [clear <name|all>]", "Header lines pinned by scripts"),
            CommandSpec("setup", "setup [text]", "Setup checklist page (text: print it in the log)"),
            CommandSpec("notifications", "notifications", "Allow script notifications"),
            CommandSpec("usage-access", "usage-access", "Grant usage access (unlock count)"),
            CommandSpec("battery", "battery", "Battery optimization settings"),
            CommandSpec("home", "home", "Choose the default launcher"),
            CommandSpec("lock", "lock", "Lock the screen (needs the accessibility service)"),
            CommandSpec("shade", "shade", "Pull down the notification shade"),
            CommandSpec("accessibility", "accessibility", "Enable the screen-lock service for double-tap"),
            CommandSpec("settings", "settings", "Kolahal settings screen (+ backup import/export)"),
            CommandSpec("scripts", "scripts", "Script manager screen (import files, timers)"),
            CommandSpec("system", "system", "Android system settings"),
            CommandSpec("config", "config [ls|set|get|reset] <key> <v>", "Settings (no args opens the settings page)"),
            CommandSpec("theme", "theme <name>", "Themes: green amber mono dracula solarized gruvbox gruvbox-material"),
            CommandSpec("font", "font [<name>|import]", "Terminal font: system jetbrains-mono fira-code ibm-plex-mono source-code-pro custom (no args: list)"),
            CommandSpec("date", "date", "Current date and time"),
            CommandSpec("echo", "echo <text>", "Print text"),
            CommandSpec("alias", "alias [name = command]", "List or define aliases"),
            CommandSpec("unalias", "unalias <name>", "Remove an alias"),
            CommandSpec("pin", "pin <app>", "Favourite: first in the empty-prompt strip (pins lists them)"),
            CommandSpec("unpin", "unpin <app>", "Remove a favourite"),
            CommandSpec("hide", "hide <app> [-w]", "Hide an app from lists and search"),
            CommandSpec("unhide", "unhide <app>", "Show a hidden app again ('apps hidden')"),
            CommandSpec("rename", "rename <app> = <name>", "Custom label (empty restores)"),
            CommandSpec("todo", "todo [<text>|add|done|undo|edit|rm <n>|all|clear]", "Todo list (tap an item to tick it)"),
            CommandSpec("note", "note <text>", "Save a quick note"),
            CommandSpec("notes", "notes [grep <text>|rm <n>]", "Your notes, newest first (tap copies)"),
            CommandSpec("remind", "remind <when> <text>", "Notify later · 10m · 1h30m · 18:30 · tomorrow 9:00 · fri 8am · rm <n>"),
            CommandSpec("reminders", "reminders", "Pending reminders"),
            CommandSpec("timer", "timer <5m|1h30m> [label]", "Start a timer in the clock app (no args: show timers)"),
            CommandSpec("alarm", "alarm <HH:MM> [label]", "Set an alarm in the clock app"),
            CommandSpec("alarms", "alarms", "Show the clock app's alarms"),
            CommandSpec("stopwatch", "stopwatch", "Open the clock app"),
            CommandSpec("agenda", "agenda [days]", "Upcoming calendar events (tap opens one)"),
        )

        /** Every built-in command name, for alias, script-name and typo checks. */
        val COMMAND_NAMES: Set<String> = COMMANDS.map { it.name }.toSet()

        /** Commands handled by [ProductivityCommands]. */
        val PRODUCTIVITY = setOf("todo", "note", "notes", "remind", "reminders", "timer", "alarm", "alarms", "stopwatch", "agenda")

        /** `help` groups; commands not listed here are shown after the groups. */
        val HELP_SECTIONS = linkedMapOf(
            "apps" to listOf("open", "shortcuts", "apps", "pin", "unpin", "info", "uninstall", "hide", "unhide", "rename", "usage", "installs"),
            "people" to listOf("call", "sms", "contacts", "calls"),
            "web" to listOf("search", "yt", "wiki", "maps", "play", "url"),
            "feeds" to listOf("feeds", "feed", "rss", "notifs"),
            "control" to com.mtwabpin.kolahal.control.ControlCommands.SPECS.map { it.name },
            "profiles" to listOf("work", "private"),
            "productivity" to listOf("todo", "note", "notes", "remind", "reminders", "timer", "alarm", "alarms", "stopwatch", "agenda"),
            "scripts" to listOf("scripts", "script", "lua", "js", "=", "status"),
            "terminal" to listOf("help", "history", "clear", "fresh", "restart", "alias", "unalias", "config", "theme", "font", "echo", "date"),
            "widgets" to listOf("widgets", "widget"),
            "device" to listOf("settings", "setup", "unlocks", "notifications", "usage-access", "battery", "home", "lock", "shade", "accessibility", "system"),
        )
    }
}
