package com.mtwabpin.kolahal.terminal

import android.content.Context
import com.mtwabpin.kolahal.data.KvStore
import com.mtwabpin.kolahal.data.LogContext
import com.mtwabpin.kolahal.data.LogKind
import com.mtwabpin.kolahal.data.LogRepository
import com.mtwabpin.kolahal.data.Script
import com.mtwabpin.kolahal.data.ScriptLang
import com.mtwabpin.kolahal.data.ScriptRepository
import com.mtwabpin.kolahal.notify.Notifier
import com.mtwabpin.kolahal.script.HostApi
import com.mtwabpin.kolahal.script.ScriptAccess
import com.mtwabpin.kolahal.script.ScriptCatalog
import com.mtwabpin.kolahal.script.ScriptFolder
import com.mtwabpin.kolahal.script.ScriptLibrary
import com.mtwabpin.kolahal.script.ScriptMeta
import com.mtwabpin.kolahal.script.ScriptNames
import com.mtwabpin.kolahal.script.ScriptScheduler
import com.mtwabpin.kolahal.script.ScriptTemplates
import java.text.DateFormat
import java.util.Date

/**
 * `script …`, `status`, and `@command` scripts run by name. Kept out of [CommandProcessor] so
 * the dispatcher only needs one line per command.
 */
class ScriptCommands(
    private val context: Context,
    private val ui: TerminalUi,
    /** Runs slow work (folder sync) off the serial command queue; failures are logged. */
    private val background: (label: String, block: () -> Unit) -> Unit,
) {
    private val logs = LogRepository.get(context)
    private val scripts = ScriptRepository.get(context)
    private val folder = ScriptFolder(context)

    private fun out(text: String, ctx: LogContext = LogContext.EMPTY) { logs.add(LogKind.OUTPUT, text, ctx = ctx) }
    private fun info(text: String, ctx: LogContext = LogContext.EMPTY) { logs.add(LogKind.INFO, text, ctx = ctx) }
    private fun error(text: String) { logs.add(LogKind.ERROR, text) }

    /** Runs a `@command` script named [name]; returns null if there is none. */
    fun runCommand(name: String, args: List<String>): Unit? {
        val script = scripts.byName(name)?.takeIf { ScriptMeta.isCommand(it.code) } ?: return null
        // A switched-off script (an import not reviewed yet) doesn't run by its name.
        if (!script.enabled) {
            logs.add(LogKind.ERROR, "${script.name} is switched off — tap to turn it on", ctx = LogContext.of(LogContext.RUN to "script on ${script.name}"))
            return Unit
        }
        ScriptScheduler.runNow(context, script, args)
        return Unit
    }

    /** `status` lists script status lines; `status clear <name|all>` removes them. */
    fun status(args: List<String>) {
        val store = KvStore(context, HostApi.STATUS_SCOPE)
        val all = store.all()
        when (args.firstOrNull()) {
            null, "ls" -> {
                if (all.isEmpty()) info("No status lines — scripts set them with status(text)")
                all.forEach { (name, text) -> out("$name ▸ $text", LogContext.of(LogContext.SCRIPT to name)) }
            }
            "clear" -> {
                val target = args.getOrNull(1) ?: "all"
                (if (target == "all") all.keys else listOf(target)).forEach { store.set(it, null) }
                logs.onStatusChanged()
                info("Cleared status: $target")
            }
            else -> error("Usage: status [clear <name|all>]")
        }
    }

    fun script(args: List<String>) {
        val sub = args.firstOrNull() ?: "ls"
        val target = args.getOrNull(1)
        when (sub) {
            "ls", "list" -> listScripts()
            "triggers", "events" -> listTriggers()
            "dir", "folder" -> {
                folder.uri?.let { info("Script folder: ${it.lastPathSegment}") }
                ui.pickScriptFolder()
            }
            // Folder sync can be slow on SAF providers; run it off the serial command queue.
            "lib", "library" -> {
                val lib = ScriptLibrary(context)
                val installed = scripts.all()
                info("Script library — 'script install <name>', or browse it in 'scripts'")
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
                    ?: return error("No library script '${target.orEmpty()}' — 'script lib' lists them")
                // Updating an unedited copy is safe; a copy with your changes (or your own script of
                // that name) is only replaced when asked.
                if ("--replace" !in args && lib.state(entry, scripts.all()) == ScriptLibrary.State.MODIFIED) {
                    return error(
                        "Your ${entry.name}.${entry.lang.ext} differs from the library's: " +
                            "'script install ${entry.name} --replace' replaces it (export it first to keep a copy)"
                    )
                }
                val s = lib.install(entry)
                ScriptAccess.askForNew(context, s)
                info("Installed ${s.name}.${s.lang.ext}" + (if (entry.command) " — run it as '${s.name}'" else "") +
                    (if (s.intervalMin > 0) " · every ${CommandParsing.formatInterval(s.intervalMin)}" else ""), scriptCtx(s))
            }
            "export" -> background("export") { info("Exported ${folder.export()} scripts + kolahal.json") }
            "import" -> background("import") {
                val r = folder.import()
                info("Imported: ${r.created} new, ${r.updated} updated" + if (r.skipped.isNotEmpty()) ", skipped ${r.skipped.joinToString()}" else "")
            }
            "new" -> {
                val (name, lang) = parseName(target ?: return error("Usage: script new <name>[.lua|.js] [lua|js] [--cmd]"))
                if (!Regex("^[a-z0-9_-]{1,40}$").matches(name)) return error("Script names: lowercase letters, digits, - and _ only")
                if (name in CommandProcessor.COMMAND_NAMES) return error("'$name' is a built-in command, pick another name")
                val asCommand = "--cmd" in args || "cmd" in args
                val chosen = args.drop(2).firstNotNullOfOrNull(ScriptLang::of) ?: lang ?: ScriptLang.LUA
                if (scripts.byName(name) != null) return error("Script '$name' exists — 'script edit $name'")
                val template = if (asCommand) ScriptTemplates.command(chosen, name) else ScriptTemplates.forLang(chosen, name)
                val script = scripts.create(name, chosen, template)
                info("Created ${script.name}.${script.lang.ext}", scriptCtx(script))
                ui.openEditor(script)
            }
            "edit" -> withScript(target) { ui.openEditor(it) }
            "run" -> withScript(target) {
                ScriptScheduler.runNow(context, it)
                info("Running ${it.name}…", scriptCtx(it))
            }
            "every" -> withScript(target) { s ->
                val minutes = CommandParsing.parseInterval(args.getOrNull(2))
                    ?: return error("Usage: script every <name> <15m|30m|2h|1d|off>")
                val effective = if (minutes > 0) minutes.coerceAtLeast(ScriptScheduler.MIN_INTERVAL_MIN) else 0
                scripts.setInterval(s.id, effective)
                if (effective > 0 && !s.enabled) scripts.setEnabled(s.id, true)
                scripts.byId(s.id)?.let { ScriptScheduler.sync(context, it) }
                info(
                    if (effective == 0) "${s.name}: timer off" else "${s.name}: runs every ${CommandParsing.formatInterval(effective)}" +
                        if (minutes in 1 until ScriptScheduler.MIN_INTERVAL_MIN) " (Android minimum is 15m)" else "",
                    scriptCtx(s),
                )
            }
            "at" -> withScript(target) { s ->
                val spec = args.getOrNull(2) ?: return error("Usage: script at <name> <HH:mm[,HH:mm…]|off>")
                val times = if (spec == "off") emptyList() else spec.split(',').map { CommandParsing.parseClock(it) ?: return error("Bad time '$it' — use HH:mm") }
                scripts.setAtTimes(s.id, times.distinct().sorted())
                if (times.isNotEmpty() && !s.enabled) scripts.setEnabled(s.id, true)
                scripts.byId(s.id)?.let { ScriptScheduler.sync(context, it) }
                info(if (times.isEmpty()) "${s.name}: no daily times" else "${s.name}: daily at ${times.distinct().sorted().joinToString(", ")}", scriptCtx(s))
            }
            "notify" -> withScript(target) { s ->
                val on = when (args.getOrNull(2)) {
                    "on" -> true
                    "off" -> false
                    else -> return error("Usage: script notify <name> on|off")
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
                // Switching a script on allows what it declares and nobody decided yet: say what it may use.
                val grants = scripts.byId(s.id)?.grants
                val uses = grants?.declared.orEmpty().filter { grants?.allows(it) == true }
                if (enabled && uses.isNotEmpty()) {
                    info("${s.name} can use: ${uses.joinToString()} — 'script deny ${s.name} <what>' switches one off", scriptCtx(s))
                }
            }
            // Network: off the serial command queue.
            "get" -> background("script get") { getShared(args.drop(1)) }
            "access" -> withScript(target) { listAccess(it) }
            "allow", "deny" -> withScript(target) { s ->
                val items = args.drop(2).map { it.lowercase() }
                if (items.isEmpty()) return error("Usage: script $sub <name> <what…> — 'script access ${s.name}' lists them")
                setAccess(s, items, sub == "allow")
            }
            "rm", "delete" -> withScript(target) { s ->
                ScriptScheduler.cancel(context, s.id)
                scripts.delete(s.id)
                KvStore(context, HostApi.STATUS_SCOPE).set(s.name, null)
                logs.onStatusChanged()
                info("Deleted ${s.name}")
            }
            "cat", "show" -> withScript(target) { s ->
                s.code.lines().forEachIndexed { i, l -> out("${(i + 1).toString().padStart(3)}  $l", LogContext.of(LogContext.COPY to s.code) + LogContext.ALIGNED) }
            }
            else -> error("Usage: script [ls|new|edit|run|every|at|notify|on|off|get|access|allow|deny|triggers|rm|cat] <name>")
        }
    }

    /**
     * `script get [<name>] [--refresh] [--replace]`: the scripts shared in kolahal-scripts, or one
     * installed (switched on, allowed what it declares, as `script on` does) or updated.
     */
    private fun getShared(args: List<String>) {
        val catalog = ScriptCatalog(context)
        val refresh = "--refresh" in args
        val replace = "--replace" in args
        val name = args.firstOrNull { !it.startsWith("--") }?.lowercase()?.substringBefore('.')
        val entries = catalog.entries(refresh)
        if (name == null) {
            val installed = scripts.all()
            info("Shared scripts from ${ScriptCatalog.SOURCE} — 'script get <name>' installs one")
            if (entries.isEmpty()) info("None yet")
            entries.forEach { e ->
                val state = when (catalog.state(e, installed)) {
                    ScriptCatalog.State.NOT_INSTALLED -> ""
                    ScriptCatalog.State.INSTALLED -> " ✓"
                    ScriptCatalog.State.UPDATE_AVAILABLE -> " (update available)"
                    ScriptCatalog.State.MODIFIED -> " (edited)"
                    ScriptCatalog.State.NAME_TAKEN -> " (name taken)"
                    ScriptCatalog.State.NEEDS_NEWER -> " (needs Kolahal ${e.requires})"
                }
                val desc = listOfNotNull(
                    e.title, e.version.ifBlank { null }?.let { "v$it" }, e.author.ifBlank { null }?.let { "by $it" }, e.desc.ifBlank { null },
                )
                out("${e.name}.${e.lang.ext}$state", LogContext.of(LogContext.COMMAND to "script get ${e.name}", LogContext.DESC to desc.joinToString(" · ")))
            }
            return
        }
        val entry = entries.firstOrNull { it.name == name }
            ?: (if (refresh) null else catalog.entries(refresh = true).firstOrNull { it.name == name })
            ?: return error("No shared script '$name' — 'script get' lists them")
        when (catalog.state(entry, scripts.all())) {
            ScriptCatalog.State.NEEDS_NEWER -> return error("${entry.name} needs Kolahal ${entry.requires} or newer")
            ScriptCatalog.State.NAME_TAKEN -> return error(ScriptNames.clash(context, entry.name) ?: "You have your own script named ${entry.name}")
            ScriptCatalog.State.INSTALLED -> return info("${entry.name} is installed and up to date")
            ScriptCatalog.State.MODIFIED -> if (!replace) {
                return error("You edited ${entry.name}: 'script get ${entry.name} --replace' replaces it (export it first to keep a copy)")
            }
            else -> Unit
        }
        val update = scripts.byName(entry.name) != null
        val s = catalog.install(entry, catalog.download(entry), replace = replace)
        if (update) {
            info("Updated ${s.name}.${s.lang.ext} to ${entry.version}", scriptCtx(s))
            return
        }
        val timer = listOfNotNull(
            s.intervalMin.takeIf { it > 0 }?.let { "every ${CommandParsing.formatInterval(it)}" },
            s.atTimes.takeIf { it.isNotEmpty() }?.let { "daily at ${it.joinToString(", ")}" },
        )
        info("Installed ${s.name}.${s.lang.ext} from ${ScriptCatalog.SOURCE}" + timer.joinToString("") { " · $it" }, scriptCtx(s))
        val grants = s.grants
        val uses = grants.declared.filter(grants::allows)
        if (uses.isNotEmpty()) info("${s.name} can use: ${uses.joinToString()} — 'script deny ${s.name} <what>' switches one off", scriptCtx(s))
    }

    /** `script access <name>`: each switch, on or off, and where it comes from; tap one to change it. */
    private fun listAccess(s: Script) {
        val grants = s.grants
        info("${s.name}.${s.lang.ext} — 'script allow|deny ${s.name} <what>'", scriptCtx(s))
        ScriptAccess.items(s.code).forEach { item ->
            val from = when (item) {
                in ScriptMeta.USES -> " · @uses $item"
                in ScriptMeta.EVENTS -> " · @on $item"
                else -> ""
            }
            val on = grants.allows(item)
            val state = if (on) "on" else if (item in grants.undecided) "off, not allowed yet" else "off"
            out(
                "${item.padEnd(14)}$state",
                LogContext.of(
                    LogContext.DESC to ScriptAccess.label(item) + from,
                    LogContext.COMMAND to "script ${if (on) "deny" else "allow"} ${s.name} $item",
                ) + LogContext.ALIGNED,
            )
        }
        out(
            "${"notify".padEnd(14)}${if (s.notify) "on" else "off"}",
            LogContext.of(LogContext.DESC to "Posting notifications", LogContext.COMMAND to "script notify ${s.name} ${if (s.notify) "off" else "on"}") +
                LogContext.ALIGNED,
        )
    }

    /** `script allow|deny <name> <what…>`; `notify` is the notifications switch. */
    private fun setAccess(s: Script, items: List<String>, on: Boolean) {
        val all = ScriptAccess.items(s.code)
        items.firstOrNull { it != "notify" && it !in all }?.let { item ->
            return error(
                if (item in ScriptMeta.USES) "${s.name} doesn't declare @uses $item"
                else "No '$item' for ${s.name} — it has: ${(all + "notify").joinToString(" ")}"
            )
        }
        for (item in items) {
            if (item == "notify") scripts.setNotify(s.id, on) else scripts.setAccess(s.id, item, on)
        }
        // Switched off, its line leaves the header now rather than at its next run.
        if (!on && ScriptAccess.HEADER in items) {
            KvStore(context, HostApi.STATUS_SCOPE).set(s.name, null)
            logs.onStatusChanged()
        }
        info("${s.name}: " + items.joinToString(", ") { "$it ${if (on) "on" else "off"}" }, scriptCtx(s))
        if (on && "notify" in items && !Notifier.canPost(context)) ui.requestNotificationPermission()
    }

    private fun listScripts() {
        val all = scripts.all()
        if (all.isEmpty()) {
            info("No scripts yet — try 'script new hn' or 'script new feed.js'")
            return
        }
        val df = DateFormat.getTimeInstance(DateFormat.SHORT)
        all.forEach { s ->
            val timer = listOfNotNull(
                s.intervalMin.takeIf { it > 0 }?.let { "every ${CommandParsing.formatInterval(it)}" },
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
            info("No script has an @on line — add '-- @on unlock' (Lua) or '// @on charger' (JS)")
        }
        subscribed.forEach { (s, events, unknown) ->
            val parts = buildList {
                add(if (events.isEmpty()) "no known events" else "on " + events.joinToString(", "))
                if (unknown.isNotEmpty()) add("unknown: " + unknown.joinToString(", "))
                if (!s.enabled) add("disabled")
            }
            out("${s.name}.${s.lang.ext}", scriptCtx(s) + mapOf(LogContext.DESC to parts.joinToString(" · ")))
        }
        info("Events: ${ScriptMeta.EVENTS.joinToString(" ")}")
    }

    private inline fun withScript(name: String?, action: (Script) -> Unit) {
        if (name == null) return error("Script name required")
        val script = scripts.byName(parseName(name).first)
        if (script == null) error("No script named '$name' — 'script ls'") else action(script)
    }

    private fun scriptCtx(s: Script) = LogContext.of(LogContext.SCRIPT to s.name)

    private fun parseName(raw: String): Pair<String, ScriptLang?> {
        val ext = raw.substringAfterLast('.', "")
        val lang = ScriptLang.of(ext)
        return (if (lang != null) raw.substringBeforeLast('.') else raw).lowercase() to lang
    }

    companion object {
        /** What follows `script`, for completion. */
        val SUBCOMMANDS = listOf("ls", "lib", "install", "new", "edit", "run", "every", "at", "notify", "on", "off", "get", "access", "allow", "deny", "triggers", "rm", "cat", "dir", "export", "import")
    }
}
