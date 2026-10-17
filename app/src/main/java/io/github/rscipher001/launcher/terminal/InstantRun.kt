package io.github.rscipher001.launcher.terminal

/**
 * `launch.instant`: run what the user is typing as soon as it can only mean one thing, without
 * Enter. `apps` limits this to app names; `all` also covers commands and aliases. Sensitive
 * commands (calls, messages, uninstall, profile switches, clearing, …) never run this way.
 */
object InstantRun {
    const val OFF = "off"
    const val APPS = "apps"
    const val ALL = "all"

    /** Never run without Enter: they cost money, change state, or are hard to undo. */
    val SENSITIVE = setOf(
        "call", "sms", "uninstall", "work", "private", "clear", "restart", "lock", "script", "alias", "unalias",
        "config", "hide", "unhide", "rename", "feed", "feeds", "notifs", "lua", "js", "=", "url", "echo",
        "todo", "note", "notes", "remind", "timer", "alarm",
    ) + io.github.rscipher001.launcher.control.ControlCommands.SENSITIVE

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
        if (q.length < minLength || q.any { it.isWhitespace() }) return null
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
        if (head in scriptCommands) return false
        val usage = commands[head] ?: return isApp(current)
        // Only commands that take no arguments at all: `contacts` must still accept `contacts bob`.
        return parts.size > 1 || usage.trim() == head
    }
}
