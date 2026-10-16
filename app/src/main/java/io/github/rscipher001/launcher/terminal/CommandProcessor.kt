package io.github.rscipher001.launcher.terminal

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import io.github.rscipher001.launcher.apps.AppEntry
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
import java.util.Date

/** UI-only operations the processor cannot do from an application context. */
interface TerminalUi {
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

    fun run(line: String) {
        val trimmed = line.trim()
        if (trimmed.isEmpty()) return
        logs.add(LogKind.COMMAND, trimmed, ctx = LogContext.of(LogContext.COMMAND to trimmed))
        val name = trimmed.substringBefore(' ').lowercase()
        val rest = trimmed.substringAfter(' ', "").trim()
        val args = tokenize(rest)
        runCatching {
            when (name) {
                "help", "?" -> help()
                "apps", "ls" -> listApps(args)
                "open", "launch" -> open(args)
                "info" -> withApp(args) { apps.openAppInfo(it); info("app info: ${it.label}") }
                "uninstall" -> withApp(args) { apps.uninstall(it) }
                "work" -> profileToggle(ProfileKind.WORK, args)
                "private" -> profileToggle(ProfileKind.PRIVATE, args)
                "unlocks" -> unlocks(args)
                "clear", "cls" -> ui.clearScreen()
                "history" -> history()
                "script", "scripts" -> script(args)
                "lua" -> eval(ScriptLang.LUA, rest)
                "js" -> eval(ScriptLang.JS, rest)
                "rss" -> rss(rest)
                "url", "web" -> openUrl(rest)
                "search", "g" -> search(rest)
                "notifications" -> notifications()
                "settings" -> startSettings(Settings.ACTION_SETTINGS)
                "home" -> startSettings(Settings.ACTION_HOME_SETTINGS)
                "date", "time" -> out(DateFormat.getDateTimeInstance().format(Date()))
                "echo" -> out(rest)
                "config", "set" -> config(args)
                "theme" -> config(listOf("set", "theme") + args)
                else -> fallbackLaunch(trimmed)
            }
        }.onFailure { error("$name: ${it.message ?: it.javaClass.simpleName}") }
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

    private fun appLine(app: AppEntry) = "[${app.profile.kind.tag}] ${app.label}"

    // ---- apps -----------------------------------------------------------------------------

    private fun profileFlag(args: List<String>): ProfileKind? = when {
        "-w" in args || "--work" in args -> ProfileKind.WORK
        "-p" in args || "--personal" in args -> ProfileKind.PERSONAL
        "-s" in args || "--private" in args -> ProfileKind.PRIVATE
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
        val list = apps.apps.value.filter { kind == null || it.profile.kind == kind }
        if (list.isEmpty()) {
            info("no apps" + (kind?.let { " in ${it.label} profile" } ?: ""))
            return
        }
        list.forEach { out(appLine(it), appCtx(it)) }
        info("${list.size} apps  ·  [P] personal  [W] work  [S] private")
    }

    private fun resolveApp(args: List<String>): List<AppEntry> = apps.search(stripFlags(args), profileFlag(args))

    private inline fun withApp(args: List<String>, action: (AppEntry) -> Unit) {
        val matches = resolveApp(args)
        when {
            matches.isEmpty() -> error("no app matches '${stripFlags(args)}'")
            else -> action(matches.first())
        }
    }

    private fun open(args: List<String>) {
        val matches = resolveApp(args)
        if (matches.isEmpty()) {
            error("no app matches '${stripFlags(args)}'")
            return
        }
        launch(matches.first())
    }

    fun launch(app: AppEntry) {
        if (app.profile.quiet) {
            val cmd = if (app.profile.kind == ProfileKind.PRIVATE) "private on" else "work on"
            error("${app.profile.kind.label} profile is paused — run '$cmd' first")
            return
        }
        apps.launch(app)
        info("launched ${appLine(app)}", appCtx(app))
    }

    /** Anything that isn't a command is treated as an app name. */
    private fun fallbackLaunch(text: String) {
        val matches = apps.search(text)
        when {
            matches.isEmpty() -> error("command not found: $text  (try 'help')")
            matches[0].label.equals(text, ignoreCase = true) -> launch(matches[0])
            matches.size == 1 && conf[Keys.AUTO_LAUNCH] -> launch(matches[0])
            else -> {
                matches.take(8).forEach { out(appLine(it), appCtx(it)) }
                info("${matches.size} matches — tap one to launch")
            }
        }
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
            info("tap here to grant Usage Access for accurate counts", LogContext.of(ACTION_KEY to ACTION_USAGE_ACCESS))
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
        )
    }

    private fun openUrl(raw: String) {
        if (raw.isBlank()) return error("usage: url <address>")
        val url = if (raw.contains("://")) raw else "https://$raw"
        startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        info("opened $url", LogContext.of(LogContext.URL to url))
    }

    private fun search(query: String) {
        if (query.isBlank()) return error("usage: search <query>")
        val url = "https://duckduckgo.com/?q=" + Uri.encode(query)
        startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        info("search: $query", LogContext.of(LogContext.URL to url))
    }

    private fun notifications() {
        if (Notifier.canPost(context)) info("notifications are allowed")
        else ui.requestNotificationPermission()
    }

    private fun startSettings(action: String) = startActivity(Intent(action))

    private fun startActivity(intent: Intent) {
        context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    private fun help() {
        COMMANDS.forEach { out("${it.usage.padEnd(30)} ${it.help}") }
        info("anything else is matched against app names · tap a line to act · long-press for more")
    }

    // ---- config ---------------------------------------------------------------------------

    private fun config(args: List<String>) {
        val sub = args.firstOrNull()
        when {
            sub == null || sub == "ls" || sub == "list" -> {
                Keys.ALL.forEach { k ->
                    val marker = if (conf.isDefault(k)) "" else " *"
                    out(
                        "${k.key.padEnd(17)} ${conf.display(k).padEnd(8)} ${k.help}$marker",
                        LogContext.of(LogContext.COMMAND to "config set ${k.key} "),
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
            sub == "set" -> setKey(args.getOrNull(1), args.drop(2).joinToString(" "))
            Keys.byName(sub) != null -> setKey(sub, args.drop(1).joinToString(" "))
            else -> error("usage: config [ls|get|set|reset] <key> [value]")
        }
    }

    private inline fun withKey(name: String?, action: (ConfigKey<*>) -> Unit) {
        val key = name?.let(Keys::byName)
        if (key == null) error("unknown setting '${name.orEmpty()}' — run 'config'") else action(key)
    }

    private fun setKey(name: String?, value: String) = withKey(name) { key ->
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
            "new" -> {
                val (name, lang) = parseName(target ?: return error("usage: script new <name>[.lua|.js] [lua|js]"))
                val chosen = args.getOrNull(2)?.let(ScriptLang::of) ?: lang ?: ScriptLang.LUA
                if (scripts.byName(name) != null) return error("script '$name' exists — 'script edit $name'")
                val script = scripts.create(name, chosen, ScriptTemplates.forLang(chosen, name))
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
                info("deleted ${s.name}")
            }
            "cat", "show" -> withScript(target) { s ->
                s.code.lines().forEachIndexed { i, l -> out("${(i + 1).toString().padStart(3)}  $l", LogContext.of(LogContext.COPY to s.code)) }
            }
            else -> error("usage: script [ls|new|edit|run|every|notify|on|off|rm|cat] <name>")
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
            val timer = if (s.intervalMin > 0) "every ${formatInterval(s.intervalMin)}" else "manual"
            val flags = buildList {
                if (!s.enabled) add("disabled")
                if (s.notify) add("notify")
            }.joinToString(",").ifEmpty { "-" }
            val last = s.lastRun?.let { "${df.format(Date(it))} ${s.lastStatus.orEmpty()}" } ?: "never run"
            out("${(s.name + "." + s.lang.ext).padEnd(18)} ${timer.padEnd(12)} ${flags.padEnd(16)} $last", scriptCtx(s))
        }
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
        return (if (lang != null) raw.substringBeforeLast('.') else raw) to lang
    }

    companion object {
        /** Extra context key for launcher-internal actions on a log line. */
        const val ACTION_KEY = "action"
        const val ACTION_USAGE_ACCESS = "usage_access"

        val COMMANDS = listOf(
            CommandSpec("help", "help", "show this list"),
            CommandSpec("apps", "apps [work|personal|private]", "list apps with profile tags"),
            CommandSpec("open", "open <app> [-w|-p|-s]", "launch an app (or just type its name)"),
            CommandSpec("info", "info <app> [-w]", "open app info"),
            CommandSpec("uninstall", "uninstall <app> [-w]", "uninstall an app"),
            CommandSpec("work", "work [on|off|status]", "pause / resume the work profile"),
            CommandSpec("private", "private [on|off|status]", "lock / unlock private space"),
            CommandSpec("unlocks", "unlocks [days]", "unlock counts"),
            CommandSpec("script", "script <sub> <name>", "ls new edit run every notify on off rm cat"),
            CommandSpec("lua", "lua <code>", "evaluate Lua 5.4"),
            CommandSpec("js", "js <code>", "evaluate JavaScript (QuickJS)"),
            CommandSpec("rss", "rss <url>", "fetch a feed into the log"),
            CommandSpec("search", "search <query>", "web search"),
            CommandSpec("url", "url <address>", "open a link"),
            CommandSpec("history", "history", "recent commands (tap to reuse)"),
            CommandSpec("clear", "clear", "clear the screen"),
            CommandSpec("notifications", "notifications", "allow script notifications"),
            CommandSpec("home", "home", "choose the default launcher"),
            CommandSpec("settings", "settings", "system settings"),
            CommandSpec("config", "config [set|get|reset] <key> <v>", "show or change settings"),
            CommandSpec("theme", "theme <name>", "green amber mono dracula solarized"),
            CommandSpec("date", "date", "current date and time"),
            CommandSpec("echo", "echo <text>", "print text"),
        )

        val SCRIPT_SUBCOMMANDS = listOf("ls", "new", "edit", "run", "every", "notify", "on", "off", "rm", "cat")

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

        fun formatInterval(min: Int): String = when {
            min % (60 * 24) == 0 -> "${min / (60 * 24)}d"
            min % 60 == 0 -> "${min / 60}h"
            else -> "${min}m"
        }
    }
}
