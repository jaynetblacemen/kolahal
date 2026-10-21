package com.mtwabpin.kolahal.terminal

import android.content.Context
import com.mtwabpin.kolahal.apps.AppEntry
import com.mtwabpin.kolahal.apps.AppPrefs
import com.mtwabpin.kolahal.apps.AppRepository
import com.mtwabpin.kolahal.apps.ProfileKind
import com.mtwabpin.kolahal.data.Config
import com.mtwabpin.kolahal.data.ConfigKey
import com.mtwabpin.kolahal.data.Keys
import com.mtwabpin.kolahal.data.Script
import com.mtwabpin.kolahal.ui.TermFonts
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.map

/**
 * The suggestion strip for what is typed: commands, aliases and apps for the first word, then each
 * command's arguments, the options its usage line lists, and earlier commands that continue the line.
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
        /** Scripts with a status line in the header. */
        val statusNames: Set<String>
        val feeds: List<com.mtwabpin.kolahal.feeds.FeedInfo>
        val todos: List<com.mtwabpin.kolahal.productivity.Todo>
        val notes: List<com.mtwabpin.kolahal.productivity.Note>
        val reminders: List<com.mtwabpin.kolahal.productivity.Reminder>
    }

    private val config = Config.get(context)
    private val appsRepo = AppRepository.get(context)
    private val appPrefs = AppPrefs.get(context)

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
        val scriptList = data.scripts
        if (parts.size == 1) {
            val names = CommandProcessor.COMMAND_NAMES +
                data.commandNames +
                appPrefs.aliases().keys
            val cmds = names.distinct()
                .filter { it.startsWith(head) && it != head }
                .map { Suggestion.Completion(it, "$it ") }
            val ranked = appsRepo.searchRanked(head).take(12)
            // When Enter would launch an app (launch.top + a prefix match), that app is the first,
            // highlighted chip, so Enter, TAB and the highlight agree.
            val enterLaunches = config[Keys.LAUNCH_TOP] && ranked.firstOrNull()?.second?.let { it <= 2 } == true &&
                names.none { it == head }
            val apps = ranked.map { Suggestion.App(it.first) }
            // A whole command name already: its first options too, so `xkcd` offers `xkcd random`.
            val options = if (head in names) usageOptions(head, listOf(head, "")).map { it.copy(label = "$head ${it.label}") } else emptyList()
            return (if (enterLaunches) listOf(apps.first()) + cmds.take(3) + apps.drop(1) else cmds.take(3) + options + apps).distinctBy { it.key }
        }
        val specific = argSuggestions(head, parts, scriptList)
        // Commands without their own completer still get the options their usage line lists.
        val options = if (specific.isEmpty()) usageOptions(head, parts) else emptyList()
        val typed = text.trim()
        return (specific + options + historySuggestions(text))
            .filter { it !is Suggestion.Completion || it.replacement.trim() != typed }
            .distinctBy { it.key }
    }

    /** Earlier commands that continue what is typed: the completer for free text (search, note, …). */
    private fun historySuggestions(text: String): List<Suggestion> {
        val typed = text.trimStart()
        return data.history.asSequence()
            .filter { it.length > typed.length && it.startsWith(typed, ignoreCase = true) }
            .take(4)
            .map { Suggestion.Completion(it, it) }
            .toList()
    }

    /** Literal options at this argument position in the command's usage line (`apps [work|personal|…]`). */
    private fun usageOptions(head: String, parts: List<String>): List<Suggestion.Completion> {
        val usage = CommandProcessor.COMMAND_USAGES[head] ?: data.scriptUsages[head] ?: return emptyList()
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
        }.filter { it.startsWith(last) && it != last }
            .map { Suggestion.Completion(it, "$before $it ") }
    }

    private fun argSuggestions(head: String, parts: List<String>, scriptList: List<Script>): List<Suggestion> {
        val arg = parts.drop(1).joinToString(" ")
        val last = parts.last()
        return when (head) {
            "open", "launch" -> if (arg.contains(':')) shortcutSuggestions(head, arg)
                else appsRepo.search(arg.replace(FLAGS, "").trim()).take(12).map { Suggestion.App(it) }
            "info", "uninstall", "hide", "unhide", "rename", "shortcuts", "pin", "fav", "unpin", "unfav" ->
                if (head == "rename" && arg.contains('=')) emptyList() else appCompletions(head, arg, includeHidden = head == "unhide")
            "notifs" -> when {
                parts.size >= 3 && parts[1] in listOf("mute", "unmute") -> appCompletions("notifs ${parts[1]}", parts.drop(2).joinToString(" "))
                else -> emptyList()
            }
            "alias", "unalias" -> if (parts.size == 2) appPrefs.aliases().filterKeys { it.startsWith(last) }.map { (name, cmd) ->
                if (head == "unalias") Suggestion.Completion("$name = $cmd", "unalias $name")
                else Suggestion.Completion("$name = $cmd", "alias $name = $cmd")
            } else emptyList()
            "status" -> if (parts.size == 3 && parts[1] == "clear") (data.statusNames + "all")
                .filter { it.startsWith(last) && it != last }.map { Suggestion.Completion(it, "status clear $it") } else emptyList()
            "feed" -> if (parts.size == 3 && parts[1] in listOf("rm", "read", "open", "notify", "every", "fetch")) data.feeds
                .filter { last.isEmpty() || it.id.toString().startsWith(last) || (it.title ?: it.url).contains(last, ignoreCase = true) }
                .take(12).map { Suggestion.Completion("#${it.id} ${it.title ?: it.url}", "feed ${parts[1]} ${it.id}") } else emptyList()
            "usage", "unlocks" -> if (parts.size == 2) listOf("1", "7", "14", "30")
                .filter { it.startsWith(last) && it != last }.map { Suggestion.Completion(if (it == "1") "today" else "$it days", "$head $it") } else emptyList()
            "installs", "updates", "calls" -> if (parts.size == 2) listOf("20", "50", "200")
                .filter { it.startsWith(last) && it != last }.map { Suggestion.Completion("last $it", "$head $it") } else emptyList()
            "script" -> {
                if (parts.size == 2) {
                    ScriptCommands.SUBCOMMANDS.filter { it.startsWith(parts[1]) && it != parts[1] }
                        .map { Suggestion.Completion(it, "script $it ") }
                } else {
                    val sub = parts[1]
                    val prefix = parts.getOrNull(2).orEmpty()
                    if (parts.size == 3 && sub != "new") {
                        scriptList.filter { it.name.startsWith(prefix) && it.name != prefix }
                            .map { Suggestion.Completion(it.name, "script $sub ${it.name} ") }
                    } else emptyList()
                }
            }
            "config", "set" -> {
                val keyIndex = if (parts.getOrNull(1) in listOf("set", "get", "reset")) 2 else 1
                when {
                    parts.size == 2 && keyIndex == 2 -> emptyList()
                    parts.size == keyIndex + 1 -> {
                        val prefix = parts.take(keyIndex).joinToString(" ")
                        val subs = if (keyIndex == 1) listOf("ls", "set", "get", "reset").filter { it.startsWith(last) } else emptyList()
                        subs.map { Suggestion.Completion(it, "$prefix $it ") } +
                            Keys.ALL.filter { it.key.startsWith(last) && it.key != last }
                                .map { Suggestion.Completion(it.key, "$prefix ${it.key} ") }
                    }
                    parts.size == keyIndex + 2 -> {
                        val key = Keys.byName(parts[keyIndex]) ?: return emptyList()
                        val prefix = parts.take(keyIndex + 1).joinToString(" ")
                        val values = when (key) {
                            is ConfigKey.Bool -> listOf("on", "off")
                            is ConfigKey.Choice -> key.choices
                            else -> emptyList()
                        }
                        values.filter { it.startsWith(last) && it != last }.map { Suggestion.Completion(it, "$prefix $it") }
                    }
                    else -> emptyList()
                }
            }
            "theme" -> Keys.THEME.choices.filter { it.startsWith(arg) && it != arg }
                .map { Suggestion.Completion(it, "theme $it") }
            "font" -> if (parts.size == 3 && parts[1] == "sans") {
                Keys.FONT_SANS.choices.filter { it.startsWith(last) && it != last }.map { Suggestion.Completion(it, "font sans $it") }
            } else {
                (TermFonts.names + "import" + "sans").filter { it.startsWith(arg) && it != arg }
                    .map { Suggestion.Completion(it, if (it == "sans") "font sans " else "font $it") }
            }
            "call", "dial", "sms", "text", "contacts", "contact" -> contactSuggestions(head, parts)
            "work", "private" -> listOf("on", "off", "status")
                .filter { it.startsWith(arg) && it != arg }
                .map { Suggestion.Completion(it, "$head $it") }
            in CommandProcessor.PRODUCTIVITY -> productivitySuggestions(head, parts)
            else -> com.mtwabpin.kolahal.control.ControlCommands.suggest(head, arg)
                .map { (label, line) -> Suggestion.Completion(label, line) }
        }
    }

    /** `<head> <app>` completions; the profile flag is added when the app exists in several profiles. */
    private fun appCompletions(head: String, arg: String, includeHidden: Boolean = false): List<Suggestion> =
        appsRepo.search(arg.replace(FLAGS, "").trim(), includeHidden = includeHidden)
            .take(12).map { Suggestion.Completion("${it.label} [${it.profile.kind.tag}]", "$head ${it.label}" + flagFor(it), app = it) }

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
        return labels.filter { it.contains(wanted, ignoreCase = true) }.take(12)
            .map { Suggestion.Completion("${app.label} › $it", "$head ${app.label}:$it" + flagFor(app), app = app) }
    }

    /**
     * `call ` lists favourites first, then everyone; typing narrows by name or number. A name with
     * several numbers gets one row per number and completes to the number, so each row is distinct
     * and dials what it shows. `sms` always completes to the number: its first word is the recipient.
     */
    private fun contactSuggestions(head: String, parts: List<String>): List<Suggestion> {
        val repo = com.mtwabpin.kolahal.contacts.ContactsRepository.get(context)
        if (!repo.hasPermission()) return listOf(Suggestion.Completion("allow contacts access", "contacts --allow"))
        val sms = head == "sms" || head == "text"
        // `sms <number> hello…`: the recipient is done, the rest is the message.
        if (sms && parts.size > 2 && com.mtwabpin.kolahal.contacts.ContactsRepository.looksLikeNumber(parts[1])) return emptyList()
        val query = parts.drop(1).joinToString(" ")
        val all = repo.contacts.value
        // Allowed after start (e.g. from system settings): load now; [contacts] recomposes us when done.
        if (all.isEmpty() && !contactsLoadRequested) { contactsLoadRequested = true; repo.refresh() }
        val list =if (query.isBlank()) all.take(12) else repo.search(query, 12)
        val numbersPerName = numbersPerName(all)
        return list.map { c ->
            val shared = (numbersPerName[c.name.lowercase()] ?: 1) > 1
            val label = (if (c.starred) "★ " else "") + c.name + if (shared) " · ${c.label} ${c.number}" else ""
            val target = when {
                head == "contacts" || head == "contact" -> c.name
                sms || shared -> c.number
                else -> c.name
            }
            Suggestion.Completion(label, "$head $target" + if (sms) " " else "")
        }
    }

    private fun flagFor(app: AppEntry) = when (app.profile.kind) {
        ProfileKind.WORK -> " -w"
        ProfileKind.PRIVATE -> " -s"
        ProfileKind.CLONE -> " -c"
        ProfileKind.PERSONAL -> if (appsRepo.apps.value.any { it.pkg == app.pkg && it.profile.kind != ProfileKind.PERSONAL }) " -p" else ""
    }

    private fun productivitySuggestions(head: String, parts: List<String>): List<Suggestion> {
        val sub = parts.getOrNull(1).orEmpty()
        fun subs(list: List<String>) = if (parts.size == 2) {
            list.filter { it.startsWith(sub) && it != sub }.map { Suggestion.Completion(it, "$head $it ") }
        } else emptyList()
        // `todo done ` → the open items, by their list number (what `todo` shows).
        fun items(labels: List<Pair<Int, String>>) = if (parts.size == 3) {
            labels.filter { (n, text) -> parts[2].isEmpty() || n.toString().startsWith(parts[2]) || text.contains(parts[2], ignoreCase = true) }
                .take(12).map { (n, text) -> Suggestion.Completion("$n. $text", "$head $sub $n") }
        } else emptyList()
        val numberedTodos = data.todos.mapIndexed { i, t -> i + 1 to t }
        return when (head) {
            "todo" -> when (sub) {
                "done", "toggle" -> items(numberedTodos.filter { !it.second.done }.map { it.first to it.second.text })
                "undo", "undone" -> items(numberedTodos.filter { it.second.done }.map { it.first to it.second.text })
                "rm", "edit" -> items(numberedTodos.map { it.first to it.second.text })
                else -> subs(ProductivityCommands.TODO_SUBCOMMANDS)
            }
            "notes" -> if (sub == "rm") items(data.notes.mapIndexed { i, n -> i + 1 to n.text }) else subs(ProductivityCommands.NOTES_SUBCOMMANDS)
            "remind" -> when {
                sub == "rm" -> items(data.reminders.mapIndexed { i, r -> i + 1 to r.text })
                parts.size == 2 -> (listOf("rm") + listOf("10m", "30m", "1h", "tomorrow 9:00"))
                    .filter { it.startsWith(sub) && it != sub }.map { Suggestion.Completion(it, "remind $it ") }
                else -> emptyList()
            }
            "timer" -> if (parts.size == 2) listOf("5m", "10m", "15m", "25m", "1h")
                .filter { it.startsWith(sub) && it != sub }.map { Suggestion.Completion(it, "timer $it ") } else emptyList()
            "agenda" -> if (parts.size == 2) listOf("1", "7", "14")
                .filter { it.startsWith(sub) && it != sub }.map { Suggestion.Completion("$it days", "agenda $it") } else emptyList()
            else -> emptyList()
        }
    }

    companion object {
        /** Profile flags typed after an app name (`-w`, `-p`), dropped before searching. */
        private val FLAGS = Regex("\\s-\\w+")
    }
}
