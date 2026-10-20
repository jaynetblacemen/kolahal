package com.mtwabpin.kolahal.terminal

/**
 * `launch.instant`: run what the user is typing as soon as it can only mean one thing, without
 * Enter. `apps` limits this to app names; `all` also covers commands, aliases, and whole lines
 * whose arguments leave nothing to choose (`torch on`). Sensitive commands (calls, messages,
 * uninstall, profile switches, clearing, …) never run this way.
 */
object InstantRun {
    const val OFF = "off"
    const val APPS = "apps"
    const val ALL = "all"

    /** Never run without Enter: they cost money, change state, or are hard to undo. */
    val SENSITIVE = setOf(
        "call", "sms", "uninstall", "work", "private", "clear", "restart", "lock", "script", "alias", "unalias",
        "config", "hide", "unhide", "rename", "feed", "feeds", "notifs", "lua", "js", "=", "url", "echo",
        "todo", "note", "notes", "remind", "timer", "alarm", "widget",
        // `scripts rm|off|import …` reaches the same handler as `script`; `status clear` wipes lines.
        "scripts", "status",
    ) + com.mtwabpin.kolahal.control.ControlCommands.SENSITIVE

    /** Other names the dispatcher accepts for a command; checks always use the canonical name. */
    val SYNONYMS = mapOf(
        "fav" to "pin", "unfav" to "unpin", "favs" to "pins", "?" to "help", "ls" to "apps", "launch" to "open",
        "cls" to "clear", "reboot" to "restart", "notifications-feed" to "notifs", "web" to "url", "g" to "search",
        "youtube" to "yt", "map" to "maps", "store" to "play", "screentime" to "usage", "dial" to "call",
        "text" to "sms", "contact" to "contacts", "prefs" to "settings", "time" to "date", "set" to "config",
        "vol" to "volume", "bluetooth" to "bt",
    )

    fun canonical(head: String): String = head.lowercase().let { SYNONYMS[it] ?: it }

    /** True when the first word of [line], after synonyms, is a sensitive command. */
    fun isSensitive(line: String): Boolean = canonical(line.trim().substringBefore(' ')) in SENSITIVE

    /**
     * Like [isSensitive], but follows [aliases] the way the dispatcher expands them (up to 5
     * levels), so an alias such as `c = clear` can't carry a sensitive command past the check.
     */
    fun isSensitive(line: String, aliases: Map<String, String>): Boolean {
        var current = line.trim()
        repeat(6) {
            if (isSensitive(current)) return true
            val head = current.substringBefore(' ')
            val body = aliases[head] ?: aliases[head.lowercase()] ?: return false
            current = (body + " " + current.substringAfter(' ', "")).trim()
        }
        return true // deeper than the dispatcher expands: refuse rather than guess
    }

    sealed interface Target<out A> {
        data class App<A>(val app: A) : Target<A>
        data class Command(val line: String) : Target<Nothing>
    }

    /**
     * What [text] uniquely refers to, or null. [rankedApps] is the app search (tier 0 exact,
     * 1 prefix, 2 word prefix, 3+ looser); only prefix tiers count, so "grov" picks Grove even
     * though looser matches exist. A command and an app with the same name resolve to the command
     * in `all` mode (`system` opens Android settings).
     */
    fun <A> target(
        text: String,
        mode: String,
        minLength: Int,
        rankedApps: (String) -> List<Pair<A, Int>>,
        appLabel: (A) -> String,
        commands: Map<String, String>,
        aliases: Map<String, String>,
        scriptCommands: Collection<String>,
    ): Target<A>? {
        if (mode == OFF) return null
        val q = text.trimStart().lowercase()
        // With arguments: the whole line runs once there is nothing left to choose (`torch on`).
        if (q.any { it.isWhitespace() }) return if (mode == ALL && wholeLine(q, commands, aliases)) Target.Command(q.trim()) else null
        if (q.length < minLength) return null
        val names = commands.keys + aliases.keys + scriptCommands
        val nameMatches = names.filter { it.startsWith(q) }.distinct()
        // The same app in several profiles counts once: the copy ranked first (frecency) is what
        // Enter would launch too.
        val apps = rankedApps(q).filter { it.second <= 2 }.map { it.first }.distinctBy { appLabel(it).lowercase() }
        if (mode == APPS) {
            // Typing a command must never launch an app that happens to share its prefix.
            if (nameMatches.isNotEmpty()) return null
            return apps.singleOrNull()?.let { Target.App(it) }
        }
        val otherApps = apps.filter { appLabel(it).lowercase() !in nameMatches }
        return when {
            nameMatches.isEmpty() -> otherApps.singleOrNull()?.let { Target.App(it) }
            nameMatches.size == 1 && otherApps.isEmpty() -> {
                val name = nameMatches[0]
                if (canRun(name, commands, aliases, scriptCommands)) Target.Command(name) else null
            }
            else -> null
        }
    }

    /**
     * Device and media state (`torch`, `dnd`, `media`, …) is sensitive: never run by a prefix, from
     * a script or from the history. A typed line with nothing left to choose (`torch on`) does run.
     */
    private val WHOLE_LINE_OK = com.mtwabpin.kolahal.control.ControlCommands.SENSITIVE

    /** A typed [line] with arguments that fills its command's usage line completely ([UsageLine.complete]). */
    private fun wholeLine(line: String, commands: Map<String, String>, aliases: Map<String, String>): Boolean {
        val word = line.trim().substringBefore(' ')
        // An alias may stand for anything: it waits for Enter.
        if (word in aliases) return false
        val head = canonical(word)
        if (head in SENSITIVE && head !in WHOLE_LINE_OK) return false
        return commands[head]?.let { UsageLine.complete(line, it) } == true
    }

    /**
     * Whether a whole command line (e.g. tapped in the history) may run without Enter. Aliases
     * are expanded the way the dispatcher does (chained, cycle-safe); a word that isn't a command
     * runs only if [isApp] says it names an app.
     */
    fun canRun(
        line: String,
        commands: Map<String, String>,
        aliases: Map<String, String>,
        scriptCommands: Collection<String>,
        isApp: (String) -> Boolean = { false },
    ): Boolean {
        var current = line.trim()
        val seen = HashSet<String>()
        while (true) {
            val head = current.substringBefore(' ').lowercase()
            val expansion = aliases[head] ?: break
            if (!seen.add(head) || seen.size > 5) return false
            current = (expansion + " " + current.substringAfter(' ', "")).trim()
        }
        val parts = current.split(Regex("\\s+"))
        val head = canonical(parts.firstOrNull() ?: return false)
        if (head.isEmpty() || head in SENSITIVE) return false
        // Script commands are in [commands] only when their usage is known (ScriptMeta.usage).
        val usage = commands[head] ?: return head !in scriptCommands && isApp(current)
        // Only commands that take no arguments at all: `contacts` must still accept `contacts bob`.
        return parts.size > 1 || usage.trim() == head
    }
}
