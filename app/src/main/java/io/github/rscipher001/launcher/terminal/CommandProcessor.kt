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
import io.github.rscipher001.launcher.script.Json
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
interface TerminalUi {
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
        val trimmed = if (expand) expandAliases(typed) else typed
        // "= 6*7" is a quick calculator (any Lua expression).
        if (trimmed.startsWith("=")) return eval(ScriptLang.LUA, trimmed.drop(1).trim())
        val name = trimmed.substringBefore(' ').lowercase()
        val rest = trimmed.substringAfter(' ', "").trim()
        val args = tokenize(rest)
        runCatching {
            when (name) {
                "alias" -> alias(rest)
                "unalias" -> unalias(args)
                "hide" -> withApp(args) { setHidden(it, true) }
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
                "clear", "cls" -> ui.clearScreen()
                "history" -> history()
                "script", "scripts" -> script(args)
                "lua" -> eval(ScriptLang.LUA, rest)
                "js" -> eval(ScriptLang.JS, rest)
                "rss" -> rss(rest)
                "url", "web" -> openUrl(rest)
                "search", "g" -> search(rest)
                "notifications" -> notifications(args)
                "setup" -> setup()
                "status" -> statusCommand(args)
                "usage-access" -> startActivity(unlocks.usageAccessIntent())
                "battery" -> startSettings(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
                "settings" -> startSettings(Settings.ACTION_SETTINGS)
                "home" -> startSettings(Settings.ACTION_HOME_SETTINGS)
                "date", "time" -> out(DateFormat.getDateTimeInstance().format(Date()))
                "echo" -> out(rest)
                "config", "set" -> config(args, rest)
                "theme" -> config(listOf("set", "theme") + args)
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

    private fun appLine(app: AppEntry) = "[${app.profile.kind.tag}] ${app.label}"

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
            logs.add(
                LogKind.ERROR,
                "${app.profile.kind.label} profile is paused — tap to run '$cmd'",
                ctx = LogContext.of(LogContext.COMMAND to cmd),
            )
            return
        }
        runCatching { apps.launch(app) }
            .onSuccess { info("launched ${appLine(app)}", appCtx(app)) }
            .onFailure { error("can't launch ${app.label}: ${it.message ?: it.javaClass.simpleName}") }
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
                "command not found: $head — did you mean '$corrected'? (tap to run)",
                ctx = LogContext.of(LogContext.RUN to corrected),
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

    private fun search(query: String) {
        if (query.isBlank()) return error("usage: search <query>")
        val url = "https://duckduckgo.com/?q=" + Uri.encode(query)
        startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        info("search: $query", LogContext.of(LogContext.URL to url))
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

    /** First-run checklist; each unchecked line runs its fix when tapped. */
    fun setup() {
        val power = context.getSystemService(PowerManager::class.java)
        val checks = listOf(
            Triple(isDefaultLauncher(), "Kolahal is the default home app", "home"),
            Triple(unlocks.hasUsageAccess(), "usage access (accurate unlock count)", "usage-access"),
            Triple(Notifier.canPost(context), "notifications (script alerts)", "notifications"),
            Triple(power.isIgnoringBatteryOptimizations(context.packageName), "battery unrestricted (reliable timers)", "battery"),
        )
        info("setup — tap an unchecked item to fix it, run 'setup' again to re-check")
        checks.forEach { (ok, label, cmd) ->
            out((if (ok) "[x] " else "[ ] ") + label, if (ok) LogContext.EMPTY else LogContext.of(LogContext.RUN to cmd))
        }
        val others = apps.profiles.value.filter { it.kind != ProfileKind.PERSONAL }
        out(
            if (others.isEmpty()) "[-] no work or private profile found"
            else "[x] profiles: " + others.joinToString { "${it.kind.label} (${if (it.quiet) "paused" else "on"})" }
        )
    }

    private fun isDefaultLauncher(): Boolean = if (Build.VERSION.SDK_INT >= 29) {
        context.getSystemService(RoleManager::class.java).isRoleHeld(RoleManager.ROLE_HOME)
    } else {
        context.packageManager.resolveActivity(
            Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME), PackageManager.MATCH_DEFAULT_ONLY,
        )?.activityInfo?.packageName == context.packageName
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
            "dir", "folder" -> {
                folder.uri?.let { info("script folder: ${it.lastPathSegment}") }
                ui.pickScriptFolder()
            }
            // Folder sync can be slow on SAF providers; run it off the serial command queue.
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
            else -> error("usage: script [ls|new|edit|run|every|at|notify|on|off|rm|cat] <name>")
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
                if (s.notify) add("notify")
                if (!s.enabled) add("disabled")
                add(last)
            }
            out("${s.name}.${s.lang.ext}", scriptCtx(s) + mapOf(LogContext.DESC to parts.joinToString(" · ")))
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
        return (if (lang != null) raw.substringBeforeLast('.') else raw).lowercase() to lang
    }

    companion object {
        val COMMANDS = listOf(
            CommandSpec("help", "help", "show this list"),
            CommandSpec("apps", "apps [work|personal|private|hidden] [-l]", "app grid (-l: one per line)"),
            CommandSpec("open", "open <app> [-w|-p|-s]", "launch an app (or just type its name)"),
            CommandSpec("info", "info <app> [-w]", "open app info"),
            CommandSpec("uninstall", "uninstall <app> [-w]", "uninstall an app"),
            CommandSpec("work", "work [on|off|status]", "pause / resume the work profile"),
            CommandSpec("private", "private [on|off|status]", "lock / unlock private space"),
            CommandSpec("unlocks", "unlocks [days]", "unlock counts"),
            CommandSpec("script", "script <sub> <name>", "ls new edit run every at notify on off rm cat dir export import"),
            CommandSpec("=", "= <expression>", "calculator, e.g. = 2^10 / 3"),
            CommandSpec("lua", "lua <code>", "evaluate Lua 5.4"),
            CommandSpec("js", "js <code>", "evaluate JavaScript (QuickJS)"),
            CommandSpec("rss", "rss <url>", "fetch a feed into the log"),
            CommandSpec("search", "search <query>", "web search"),
            CommandSpec("url", "url <address>", "open a link"),
            CommandSpec("history", "history", "recent commands (tap to reuse)"),
            CommandSpec("clear", "clear", "clear the screen"),
            CommandSpec("status", "status [clear <name|all>]", "header lines pinned by scripts"),
            CommandSpec("setup", "setup", "checklist: default home, permissions, battery"),
            CommandSpec("notifications", "notifications", "allow script notifications"),
            CommandSpec("usage-access", "usage-access", "grant usage access (unlock count)"),
            CommandSpec("battery", "battery", "battery optimization settings"),
            CommandSpec("home", "home", "choose the default launcher"),
            CommandSpec("settings", "settings", "system settings"),
            CommandSpec("config", "config [set|get|reset] <key> <v>", "show or change settings"),
            CommandSpec("theme", "theme <name>", "green amber mono dracula solarized"),
            CommandSpec("date", "date", "current date and time"),
            CommandSpec("echo", "echo <text>", "print text"),
            CommandSpec("alias", "alias [name = command]", "list or define aliases"),
            CommandSpec("unalias", "unalias <name>", "remove an alias"),
            CommandSpec("hide", "hide <app> [-w]", "hide an app from lists and search"),
            CommandSpec("unhide", "unhide <app>", "show a hidden app again ('apps hidden')"),
            CommandSpec("rename", "rename <app> = <name>", "custom label (empty restores)"),
        )

        /** `help` groups; commands not listed here are shown after the groups. */
        val HELP_SECTIONS = linkedMapOf(
            "apps" to listOf("open", "apps", "info", "uninstall", "hide", "unhide", "rename", "search", "url"),
            "profiles" to listOf("work", "private"),
            "scripts" to listOf("script", "lua", "js", "=", "rss", "status"),
            "terminal" to listOf("help", "history", "clear", "alias", "unalias", "config", "theme", "echo", "date"),
            "device" to listOf("setup", "unlocks", "notifications", "usage-access", "battery", "home", "settings"),
        )

        val SCRIPT_SUBCOMMANDS = listOf("ls", "new", "edit", "run", "every", "at", "notify", "on", "off", "rm", "cat", "dir", "export", "import")

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
