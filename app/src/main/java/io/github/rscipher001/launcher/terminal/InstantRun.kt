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

    /** Whether a whole command line (e.g. tapped in the history) may run without Enter. */
    fun canRun(
        line: String,
        commands: Map<String, String>,
        aliases: Map<String, String>,
        scriptCommands: Collection<String>,
    ): Boolean {
        val parts = line.trim().split(Regex("\\s+"))
        val head = parts.firstOrNull()?.lowercase() ?: return false
        if (head.isEmpty() || head in SENSITIVE) return false
        // An alias is as safe as what it expands to (one level; aliases don't chain).
        aliases[head]?.let { return canRun(it + parts.drop(1).joinToString(" ", prefix = " "), commands, emptyMap(), scriptCommands) }
        if (head in scriptCommands) return false
        val usage = commands[head] ?: return true // not a command: an app name
        // A command that needs an argument would only print its usage.
        return parts.size > 1 || "<" !in usage
    }
}
