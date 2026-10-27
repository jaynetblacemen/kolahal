package com.mtwabpin.kolahal.terminal

import android.content.Context
import com.mtwabpin.kolahal.R
import com.mtwabpin.kolahal.apps.AppEntry
import com.mtwabpin.kolahal.apps.AppPrefs
import com.mtwabpin.kolahal.apps.AppRepository
import com.mtwabpin.kolahal.apps.ProfileKind
import com.mtwabpin.kolahal.data.Config
import com.mtwabpin.kolahal.data.ConfigKey
import com.mtwabpin.kolahal.data.Keys
import com.mtwabpin.kolahal.data.Script
import com.mtwabpin.kolahal.notifications.NotifPrefs
import com.mtwabpin.kolahal.script.ScriptAccess
import com.mtwabpin.kolahal.script.ScriptMeta
import com.mtwabpin.kolahal.ui.TermFonts

/**
 * The suggestion strip for what is typed: commands, aliases and apps for the first word, then each
 * command's arguments, the options its usage line lists, and earlier commands that continue the line.
 * A synonym (`ls`, `cls`, `launch`) completes like the command it stands for, and keeps its spelling.
 */
class Completer(private val context: Context, private val data: Data) {
    /** What suggestions read from the view model, fresh on each keystroke. */
    interface Data {
        /** Earlier commands, newest first. */
        val history: List<String>
        val scripts: List<Script>
        /** Names of enabled `@command` scripts. */
        val commandNames: List<String>
        /** Usage lines of the command scripts that declare one. */
        val scriptUsages: Map<String, String>
        /** `@desc` of the command scripts that have one, shown beside their name. */
        val scriptDescriptions: Map<String, String> get() = emptyMap()
        /** Scripts with a status line in the header. */
        val statusNames: Set<String>
        val feeds: List<com.mtwabpin.kolahal.feeds.FeedInfo>
        val todos: List<com.mtwabpin.kolahal.productivity.Todo>
        val notes: List<com.mtwabpin.kolahal.productivity.Note>
        val reminders: List<com.mtwabpin.kolahal.productivity.Reminder>
        /** Where the log's lines came from, newest first ([LogFilter]). */
        val logSources: List<String> get() = emptyList()
        /** The bundled library's script names (`script install`). */
        val libraryNames: List<String> get() = emptyList()
        /** The shared scripts' names, as far as they are known (`script get`). */
        val sharedNames: List<String> get() = emptyList()
    }

    private val config = Config.get(context)
    private val appsRepo = AppRepository.get(context)
    private val appPrefs = AppPrefs.get(context)

    /** How many of one kind at most: what the strip shows (`suggestions.max`), and never fewer than a dozen. */
    private val cap: Int get() = maxOf(12, config[Keys.SUGGESTIONS])

    /** How many numbers each contact name has, worked out once per contact list, not per keystroke. */
    private var countedContacts: List<Any>? = null
    private var namesCounted: Map<String, Int> = emptyMap()

    private fun numbersPerName(all: List<com.mtwabpin.kolahal.contacts.Contact>): Map<String, Int> {
        if (countedContacts !== all) {
            namesCounted = all.groupingBy { it.name.lowercase() }.eachCount()
            countedContacts = all
        }
        return namesCounted
    }

    fun suggestions(text: String): List<Suggestion> {
        if (text.isBlank()) {
            return if (config[Keys.SUGGEST_IDLE]) appsRepo.top(config[Keys.SUGGESTIONS]).map { Suggestion.App(it) } else emptyList()
        }
        val parts = text.trimStart().split(' ')
        val head = parts.first().lowercase()
        // The command a synonym stands for: what decides the completions; [head] is what is typed back.
        val cmd = InstantRun.canonical(head)
        val scriptList = data.scripts
        if (parts.size == 1) {
            val names = CommandProcessor.COMMAND_NAMES - CommandProcessor.UNAVAILABLE +
                data.commandNames +
                appPrefs.aliases().keys
            // Commands used lately come first, then the rest in their usual order.
            val recent = data.history.asSequence().map { InstantRun.canonical(it.substringBefore(' ')) }.distinct()
                .withIndex().associate { (i, name) -> name to i }
            val cmds = names.distinct()
                .filter { it.startsWith(head) && it != head }
                .sortedBy { recent[InstantRun.canonical(it)] ?: Int.MAX_VALUE }
                .map { Suggestion.Completion(it, "$it ", icon = if (it in data.commandNames) R.drawable.ic_code else null, help = helpFor(it)) }
            val ranked = appsRepo.searchRanked(head).take(cap)
            // When Enter would launch an app (as the prompt's fallback does: its whole name, the only
            // match, or launch.top and a prefix match), that app is the first, highlighted row, so
            // Enter, TAB and the highlight agree.
            val top = ranked.firstOrNull()
            val enterLaunches = top != null && names.none { it == head } && head !in InstantRun.SYNONYMS &&
                (top.first.label.equals(head, ignoreCase = true) || (ranked.size == 1 && config[Keys.AUTO_LAUNCH]) ||
                    (config[Keys.LAUNCH_TOP] && top.second <= 2))
            val apps = ranked.map { Suggestion.App(it.first) }
            // A whole command name already: Enter runs it, so it is the first row, then its options,
            // then longer commands (`feed` offers `feed add` before `feeds`). TAB adds the space.
            val whole = if (cmd in names) {
                listOf(Suggestion.Completion(head, "$head ", icon = if (head in data.commandNames) R.drawable.ic_code else null,
                    help = helpFor(head) ?: CommandProcessor.COMMANDS.firstOrNull { it.name == cmd }?.help, enter = true))
            } else emptyList()
            val options = if (cmd in names) usageOptions(cmd, listOf(head, "")).map { it.copy(label = "$head ${it.label}") } else emptyList()
            return (if (enterLaunches) listOf(apps.first().copy(enter = true)) + cmds.take(3) + apps.drop(1) else whole + options + cmds.take(3) + apps).distinctBy { it.key }
        }
        // The Play build has no call log and no screen lock: nothing to offer.
        if (cmd in CommandProcessor.UNAVAILABLE) return emptyList()
        val specific = argSuggestions(cmd, head, parts, scriptList)
        // Commands without their own completer still get the options their usage line lists.
        val options = if (specific.isEmpty()) usageOptions(cmd, parts) else emptyList()
        val typed = text.trim()
        // An earlier line that a specific row already stands for (`open maps` beside the Maps app) is left out.
        val covered = specific.mapTo(HashSet()) { s ->
            when (s) {
                is Suggestion.Completion -> s.replacement
                is Suggestion.App -> "$head ${s.app.label}"
            }.trim().lowercase()
        }
        val rows = (specific + options + historySuggestions(text).filterNot { it.replacement.trim().lowercase() in covered })
            .filter { it !is Suggestion.Completion || it.replacement.trim() != typed }
            .distinctBy { it.key }
        // Otherwise Enter runs the line as typed, which no row is (`torch ` toggles, it isn't `torch on`);
        // but `open ma`, `info ma`, … act on the best match for the name, the first row when it is that app.
        val arg = parts.drop(1).joinToString(" ").trim()
        val best = if (cmd in PICK_BEST_APP && arg.isNotEmpty() && FLAGS.find(" $arg") == null && !(cmd == "open" && ':' in arg)) {
            appsRepo.search(arg).firstOrNull()
        } else null
        val first = rows.firstOrNull() ?: return rows
        val picksBest = best != null && when (first) {
            is Suggestion.App -> first.app == best
            is Suggestion.Completion -> first.app == best
        }
        if (!picksBest) return rows
        return listOf(if (first is Suggestion.App) first.copy(enter = true) else (first as Suggestion.Completion).copy(enter = true)) + rows.drop(1)
    }

    /** What a command's row says beside its name: a command script's `@desc`, or what an alias stands for. */
    private fun helpFor(name: String): String? = data.scriptDescriptions[name] ?: appPrefs.aliases()[name]?.let { "= $it" }

    /**
     * Earlier commands that continue what is typed: the completer for free text (search, note, …).
     * A line that named an item by its number (`todo done 3`) is left out: the numbers have moved on,
     * and the live rows list today's.
     */
    private fun historySuggestions(text: String): List<Suggestion.Completion> {
        val typed = text.trimStart()
        return data.history.asSequence()
            .filter { it.length > typed.length && it.startsWith(typed, ignoreCase = true) && !STALE_NUMBERED.matches(it) }
            .take(4)
            .map { Suggestion.Completion(it, it) }
            .toList()
    }

    /** Literal options at this argument position in the command's usage line (`apps [work|personal|…]`). */
    private fun usageOptions(cmd: String, parts: List<String>): List<Suggestion.Completion> {
        val usage = CommandProcessor.COMMAND_USAGES[cmd] ?: data.scriptUsages[cmd] ?: return emptyList()
        val token = UsageLine.tokens(usage).drop(1).getOrNull(parts.size - 2) ?: return emptyList()
        if (token.startsWith("<")) return emptyList() // a placeholder: <app>, <query>, …
        val inner = token.removeSurrounding("[", "]")
        val last = parts.last()
        val before = parts.dropLast(1).joinToString(" ")
        return UsageLine.split(inner, '|').mapNotNull { alt ->
            val word = alt.trim().substringBefore(' ')
            val rest = alt.trim().substringAfter(' ', "")
            // `add <url>` and `mute <app>` take a value after them; `name = command` is not an option.
            word.takeIf { it.matches(UsageLine.OPTION) && (rest.isEmpty() || rest.startsWith("<") || rest.startsWith("[")) }
        }.filter { it.starts(last) }
            .map { Suggestion.Completion(it, "$before $it ") }
    }

    /** [prefix] starts this word, any case, and isn't the whole of it. */
    private fun String.starts(prefix: String) = startsWith(prefix, ignoreCase = true) && !equals(prefix, ignoreCase = true)

    private fun argSuggestions(cmd: String, head: String, parts: List<String>, scriptList: List<Script>): List<Suggestion> {
        val arg = parts.drop(1).joinToString(" ")
        val last = parts.last()
        fun words(options: List<String>, icon: Int? = null) = options.filter { it.starts(last) }
            .map { Suggestion.Completion(it, (parts.dropLast(1) + it).joinToString(" "), icon = icon) }
        return when (cmd) {
            "open" -> if (arg.contains(':')) shortcutSuggestions(head, arg)
                else appsRepo.search(arg.replace(FLAGS, "").trim()).take(cap).map { Suggestion.App(it) }
            // `unhide` and `unpin` offer what they can undo, not every app.
            "info", "uninstall", "hide", "unhide", "rename", "shortcuts", "pin", "unpin" -> when {
                cmd == "rename" && arg.contains('=') -> emptyList()
                cmd == "unhide" -> appCompletions(head, arg, includeHidden = true) { appPrefs.isHidden(it.key) }
                cmd == "unpin" -> appPrefs.pinned().toSet().let { pinned -> appCompletions(head, arg) { it.key in pinned } }
                else -> appCompletions(head, arg)
            }
            "notifs" -> when {
                parts.size >= 3 && parts[1] == "mute" -> appCompletions("$head mute", parts.drop(2).joinToString(" "))
                parts.size >= 3 && parts[1] == "unmute" -> NotifPrefs.get(context).let { prefs -> appCompletions("$head unmute", parts.drop(2).joinToString(" ")) { prefs.isMuted(it.pkg) } }
                parts.size == 3 && parts[1] == "log" -> words(listOf("on", "off"))
                else -> emptyList()
            }
            "alias", "unalias" -> if (parts.size == 2) appPrefs.aliases().filterKeys { it.startsWith(last, ignoreCase = true) }.map { (name, command) ->
                if (cmd == "unalias") Suggestion.Completion("$name = $command", "$head $name")
                else Suggestion.Completion("$name = $command", "$head $name = $command")
            } else emptyList()
            "status" -> if (parts.size == 3 && parts[1] == "clear") words(data.statusNames.toList() + "all", R.drawable.ic_code) else emptyList()
            "feed" -> when {
                parts.size == 3 && parts[1] in FEED_ID_SUBCOMMANDS -> data.feeds
                    .filter { last.isEmpty() || it.id.toString().startsWith(last) || (it.title ?: it.url).contains(last, ignoreCase = true) }
                    .take(cap).map { Suggestion.Completion("#${it.id} ${it.title ?: it.url}", "$head ${parts[1]} ${it.id}", icon = R.drawable.ic_rss) }
                parts.size == 4 && parts[1] == "notify" -> words(listOf("on", "off"))
                parts.size == 4 && parts[1] == "every" -> words(INTERVALS)
                else -> emptyList()
            }
            "usage", "unlocks" -> if (parts.size == 2) listOf("1", "7", "14", "30")
                .filter { it.starts(last) }.map { Suggestion.Completion(if (it == "1") "today" else "$it days", "$head $it") } else emptyList()
            "installs", "calls" -> if (parts.size == 2) listOf("20", "50", "200")
                .filter { it.starts(last) }.map { Suggestion.Completion("last $it", "$head $it") } else emptyList()
            "script" -> {
                if (parts.size == 2) {
                    ScriptCommands.SUBCOMMANDS.filter { it.starts(parts[1]) }
                        .map { Suggestion.Completion(it, "$head $it ") }
                } else {
                    val sub = parts[1]
                    val prefix = parts.getOrNull(2).orEmpty()
                    if (parts.size == 3) {
                        // What the subcommand takes: a library or shared script to install, a script
                        // to switch on or off, any script otherwise; some take no name at all.
                        val names = when (sub) {
                            "install" -> data.libraryNames
                            // Only a library script has a library version to compare with.
                            "diff" -> scriptList.map { it.name }.filter { it in data.libraryNames }
                            "get" -> data.sharedNames
                            "on" -> scriptList.filter { !it.enabled }.map { it.name }
                            "off" -> scriptList.filter { it.enabled }.map { it.name }
                            in SCRIPT_NO_NAME -> emptyList()
                            else -> scriptList.map { it.name }
                        }
                        names.filter { it.starts(prefix) }.map { Suggestion.Completion(it, "$head $sub $it ", icon = R.drawable.ic_code) }
                    } else if (parts.size == 4 && sub == "every") {
                        words(INTERVALS)
                    } else if (parts.size == 4 && sub == "notify") {
                        words(listOf("on", "off"))
                    } else if (parts.size == 4 && (sub == "secret" || sub == "secrets")) {
                        // Its @secret names.
                        val s = scriptList.firstOrNull { it.name == parts[2] }
                        words((s?.let { ScriptMeta.secrets(it.code).map { d -> d.name } }).orEmpty())
                    } else if (parts.size >= 4 && (sub == "allow" || sub == "deny")) {
                        // What can be switched: its access items and `notify` when it posts, each once.
                        val s = scriptList.firstOrNull { it.name == parts[2] }
                        val given = parts.subList(3, parts.size - 1)
                        (s?.let { ScriptAccess.items(it.code) + listOfNotNull("notify".takeIf { _ -> ScriptMeta.postsNotifications(it.code) }) }).orEmpty()
                            .filter { it.starts(last) && it !in given }
                            .map { Suggestion.Completion(it, (parts.dropLast(1) + it).joinToString(" ") + " ") }
                    } else emptyList()
                }
            }
            "config" -> {
                val keyIndex = if (parts.getOrNull(1) in listOf("set", "get", "reset")) 2 else 1
                when {
                    parts.size == 2 && keyIndex == 2 -> emptyList()
                    parts.size == keyIndex + 1 -> {
                        val prefix = parts.take(keyIndex).joinToString(" ")
                        val subs = if (keyIndex == 1) listOf("ls", "set", "get", "reset").filter { it.startsWith(last, ignoreCase = true) } else emptyList()
                        subs.map { Suggestion.Completion(it, "$prefix $it ") } +
                            Keys.ALL.filter { it.key.starts(last) }
                                .map { Suggestion.Completion(it.key, "$prefix ${it.key} ", icon = R.drawable.ic_tune) }
                    }
                    parts.size == keyIndex + 2 -> {
                        val key = Keys.byName(parts[keyIndex]) ?: return emptyList()
                        val prefix = parts.take(keyIndex + 1).joinToString(" ")
                        val values = when {
                            key is ConfigKey.Bool -> listOf("on", "off")
                            key is ConfigKey.Choice -> key.choices
                            // A gesture's action: one of the words, or any command.
                            key.key.startsWith("gesture.") -> listOf("lock", "shade", "quick", "none")
                            else -> emptyList()
                        }
                        values.filter { it.starts(last) }.map { Suggestion.Completion(it, "$prefix $it") }
                    }
                    else -> emptyList()
                }
            }
            "theme" -> Keys.THEME.choices.filter { it.starts(arg) }
                .map { Suggestion.Completion(it, "$head $it", icon = R.drawable.ic_palette) }
            // `cal dec`: the month names.
            "cal" -> if (parts.size == 2) MonthCalendar.suggest(last).map { Suggestion.Completion(it, "$head $it") } else emptyList()
            // The last words typed may be the start of one place (`new y` → New York) after others.
            // `time` is a synonym, so [cmd] is `date` for both; the line keeps what was typed.
            "date" -> (minOf(3, parts.size - 1) downTo 1).firstNotNullOfOrNull { n ->
                WorldClock.suggest(parts.takeLast(n).joinToString(" ")).takeIf { it.isNotEmpty() }
                    ?.map { Suggestion.Completion(it.name, (parts.dropLast(n) + it.name).joinToString(" ")) }
            }.orEmpty()
            "font" -> if (parts.size == 3 && parts[1] == "sans") {
                Keys.FONT_SANS.choices.filter { it.starts(last) }.map { Suggestion.Completion(it, "$head sans $it") }
            } else {
                (TermFonts.names + "import" + "sans").filter { it.starts(arg) }
                    .map { Suggestion.Completion(it, if (it == "sans") "$head sans " else "$head $it") }
            }
            "call", "sms", "contacts" -> contactSuggestions(cmd, head, parts)
            in CommandProcessor.PRODUCTIVITY -> productivitySuggestions(cmd, head, parts)
            // `log only …`: the kinds, then where the log's lines came from.
            "log" -> if (parts.size == 3 && parts[1] == "only") {
                LogFilter.suggestions(parts[2], data.logSources).take(cap)
                    .map { Suggestion.Completion(it.label, "$head only ${it.word}", icon = R.drawable.ic_visibility) }
            } else emptyList()
            else -> com.mtwabpin.kolahal.control.ControlCommands.suggest(cmd, arg)
                .map { (label, _) -> Suggestion.Completion(label, "$head $label") }
        }
    }

    /**
     * `<head> <app>` completions, [only] those that pass when given; the profile flag is added when
     * the app exists in several profiles, and the tag shown for apps outside the personal profile.
     */
    private fun appCompletions(head: String, arg: String, includeHidden: Boolean = false, only: ((AppEntry) -> Boolean)? = null): List<Suggestion> {
        val found = appsRepo.search(arg.replace(FLAGS, "").trim(), includeHidden = includeHidden)
            .filter { only == null || only(it) }
            .take(cap)
        // Two apps of one name in one profile would complete to the same line, and the command could
        // only ever reach the first: each is named by its package (or package/activity) instead.
        val twins = found.groupBy { it.label.lowercase() to it.profile.kind }.values.filter { it.size > 1 }
        val ids = twins.flatMap { group ->
            val packages = group.groupingBy { it.pkg }.eachCount()
            group.map { it to if (packages.getValue(it.pkg) > 1) it.component.flattenToShortString() else it.pkg }
        }.toMap()
        return found.map { app ->
            val tag = if (app.profile.kind == ProfileKind.PERSONAL) "" else " [${app.profile.kind.tag}]"
            val id = ids[app]
            Suggestion.Completion(app.label + tag + (id?.let { " · $it" } ?: ""), "$head ${id ?: app.label}" + flagFor(app), app = app)
        }
    }

    /** Last app's shortcut labels, so typing after `open maps:` doesn't ask the system on every key. */
    private var shortcutCache: Pair<String, List<String>>? = null

    private var contactsLoadRequested = false

    /** `open maps:` → that app's launcher shortcuts. */
    private fun shortcutSuggestions(head: String, arg: String): List<Suggestion> {
        val app = appsRepo.search(arg.substringBefore(':').trim()).firstOrNull() ?: return emptyList()
        val labels = shortcutCache?.takeIf { it.first == app.key }?.second
            ?: appsRepo.shortcuts(app).mapNotNull { (it.shortLabel ?: it.longLabel)?.toString() }.distinct()
                .also { shortcutCache = app.key to it }
        val wanted = arg.substringAfter(':').trim()
        return labels.filter { it.contains(wanted, ignoreCase = true) }.take(cap)
            .map { Suggestion.Completion("${app.label} › $it", "$head ${app.label}:$it" + flagFor(app), app = app) }
    }

    /**
     * `call ` lists favourites first, then everyone; typing narrows by name or number. A name with
     * several numbers gets one row per number and completes to the number, so each row is distinct
     * and dials what it shows. `sms` always completes to the number: its first word is the recipient.
     */
    private fun contactSuggestions(cmd: String, head: String, parts: List<String>): List<Suggestion> {
        val repo = com.mtwabpin.kolahal.contacts.ContactsRepository.get(context)
        if (!repo.picker && !repo.hasPermission()) return listOf(Suggestion.Completion("Allow contacts access", "contacts --allow", icon = R.drawable.ic_person))
        // `contacts --forget <name>`: the names picked before.
        if (repo.picker && cmd == "contacts" && parts.getOrNull(1) == "--forget") {
            val q = parts.drop(2).joinToString(" ")
            return (if (q.isBlank()) repo.contacts.value else repo.search(q, cap)).map { it.name }.distinct().take(cap)
                .map { Suggestion.Completion(it, "$head --forget $it", icon = R.drawable.ic_person) }
        }
        val sms = cmd == "sms"
        // `sms <number> hello…`: the recipient is done, the rest is the message.
        if (sms && parts.size > 2 && com.mtwabpin.kolahal.contacts.ContactsRepository.looksLikeNumber(parts[1])) return emptyList()
        val query = parts.drop(1).joinToString(" ")
        val all = repo.contacts.value
        // Allowed after start (e.g. from system settings): load now; [contacts] recomposes us when done.
        if (all.isEmpty() && !contactsLoadRequested) { contactsLoadRequested = true; repo.refresh() }
        val list = if (query.isBlank()) all.take(cap) else repo.search(query, cap)
        val numbersPerName = numbersPerName(all)
        // The Play build knows only the contacts picked before: the picker is a line away.
        val pick = if (repo.picker) Suggestion.Completion("Pick a contact", cmd, icon = R.drawable.ic_person, help = "Enter opens Android's contact picker") else null
        return list.map { c ->
            val shared = (numbersPerName[c.name.lowercase()] ?: 1) > 1
            val label = (if (c.starred) "★ " else "") + c.name + if (shared) " · ${c.label} ${c.number}" else ""
            val target = when {
                cmd == "contacts" -> c.name
                sms -> c.number.filterNot { it.isWhitespace() }
                shared -> c.number
                else -> c.name
            }
            Suggestion.Completion(label, "$head $target" + if (sms) " " else "", icon = R.drawable.ic_person)
        } + listOfNotNull(pick)
    }

    private fun flagFor(app: AppEntry) = when (app.profile.kind) {
        ProfileKind.WORK -> " -w"
        ProfileKind.PRIVATE -> " -s"
        ProfileKind.CLONE -> " -c"
        ProfileKind.PERSONAL -> if (appsRepo.apps.value.any { it.pkg == app.pkg && it.profile.kind != ProfileKind.PERSONAL }) " -p" else ""
    }

    private fun productivitySuggestions(cmd: String, head: String, parts: List<String>): List<Suggestion> {
        val sub = parts.getOrNull(1).orEmpty()
        fun subs(list: List<String>) = if (parts.size == 2) {
            list.filter { it.starts(sub) }.map { Suggestion.Completion(it, "$head $it ") }
        } else emptyList()
        // `todo done ` → the open items, by their list number (what `todo` shows).
        fun items(labels: List<Pair<Int, String>>, icon: Int) = if (parts.size == 3) {
            labels.filter { (n, text) -> parts[2].isEmpty() || n.toString().startsWith(parts[2]) || text.contains(parts[2], ignoreCase = true) }
                .take(cap).map { (n, text) -> Suggestion.Completion("$n. $text", "$head $sub $n", icon = icon) }
        } else emptyList()
        val numberedTodos = data.todos.mapIndexed { i, t -> i + 1 to t }
        return when (cmd) {
            "todo" -> when (sub) {
                "done", "toggle" -> items(numberedTodos.filter { !it.second.done }.map { it.first to it.second.text }, R.drawable.ic_check)
                "undo", "undone" -> items(numberedTodos.filter { it.second.done }.map { it.first to it.second.text }, R.drawable.ic_check)
                "rm", "edit" -> items(numberedTodos.map { it.first to it.second.text }, R.drawable.ic_check)
                else -> subs(ProductivityCommands.TODO_SUBCOMMANDS)
            }
            "notes" -> if (sub == "rm") items(data.notes.mapIndexed { i, n -> i + 1 to n.text }, R.drawable.ic_edit) else subs(ProductivityCommands.NOTES_SUBCOMMANDS)
            "remind" -> when {
                sub == "rm" -> items(data.reminders.mapIndexed { i, r -> i + 1 to r.text }, R.drawable.ic_alarm)
                parts.size == 2 -> (listOf("rm", "exact") + listOf("10m", "30m", "1h", "tomorrow 9:00"))
                    .filter { it.starts(sub) }.map { Suggestion.Completion(it, "$head $it ") }
                else -> emptyList()
            }
            "timer" -> if (parts.size == 2) listOf("5m", "10m", "15m", "25m", "1h")
                .filter { it.starts(sub) }.map { Suggestion.Completion(it, "$head $it ") } else emptyList()
            "agenda" -> if (parts.size == 2) listOf("1", "3", "7", "14", "31")
                .filter { it.starts(sub) }.map { Suggestion.Completion(if (it == "1") "today" else "$it days", "$head $it") } else emptyList()
            else -> emptyList()
        }
    }

    companion object {
        /** Profile flags typed after an app name (`-w`, `-p`), dropped before searching. */
        private val FLAGS = Regex("\\s-\\w+")

        /** Commands that act on the best match for the app name typed after them, as their first row shows it. */
        private val PICK_BEST_APP = setOf("open", "info", "uninstall", "hide", "shortcuts", "pin")

        /** `feed <sub> <id>`: the subcommands that take a feed. */
        private val FEED_ID_SUBCOMMANDS = setOf("rm", "remove", "read", "all", "open", "notify", "every", "fetch", "refresh", "on", "off")

        /** `script <sub>`: the subcommands that take no script name. */
        private val SCRIPT_NO_NAME = setOf("ls", "lib", "triggers", "dir", "export", "import", "new")

        /** Timer intervals offered for `feed every` and `script every`. */
        private val INTERVALS = listOf("15m", "30m", "1h", "2h", "6h", "12h", "1d", "off")

        /** An earlier line that named an item by its list number: not offered again, the numbers have moved. */
        private val STALE_NUMBERED = Regex("^(todo (done|toggle|undo|undone|rm|edit)|notes rm|remind rm|feed (rm|remove|read|all|open|notify|every|fetch|refresh|on|off)) #?\\d+.*", RegexOption.IGNORE_CASE)
    }
}
