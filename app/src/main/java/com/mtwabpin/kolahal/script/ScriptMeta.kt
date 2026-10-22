package com.mtwabpin.kolahal.script

/**
 * Directives read from a script's comments:
 *
 *   -- @command            (or `// @command` in JS) — the script can be run from the prompt by name
 *   -- @desc <text>        — one-line description shown in `help` and suggestions
 *   -- @usage [args]       — the arguments, written like a built-in usage line (`[random|<number>]`);
 *                            empty when there are none. Drives suggestions and `launch.instant`
 *   -- @on <event>[, ...]  — run when a device event happens (see [EVENTS]); the payload is `event`
 *   -- @uses <what>[, ...] — private data or reach it needs (see [USES]); without it those calls fail
 *   -- @secret <NAME> <site> <description> — a key you set, read with secrets.get, sent only to <site> ([Secret])
 *
 * And what describes it, so a script file is complete on its own (the library reads these too):
 *
 *   -- @title <text>       — its name for people: Hacker News
 *   -- @author <name>, @version <x.y>, @tags <word>[, ...]
 *   -- @every <15m|1h|1d>, @at <HH:mm>[, ...], @notify — the timers and notifications it suggests,
 *                            set when it is added from a file or the library
 *   -- @requires <x.y.z>   — the oldest Kolahal it works with; older ones don't import it
 */
object ScriptMeta {
    private val command = Regex("""^\s*(--|//)\s*@command\b""", RegexOption.MULTILINE)
    private val desc = Regex("""^\s*(--|//)\s*@desc\s+(.+)$""", RegexOption.MULTILINE)
    private val usageLine = Regex("""^\s*(--|//)\s*@usage\b[ \t]*(.*)$""", RegexOption.MULTILINE)
    private val argRef = Regex("""\b(args|argline)\b""")
    private val on = Regex("""^\s*(--|//)\s*@on\b(.*)$""", RegexOption.MULTILINE)
    private val usesLine = Regex("""^\s*(--|//)\s*@uses\b(.*)$""", RegexOption.MULTILINE)

    /**
     * What a script may ask for with `@uses`: `notifications` (notifications.recent and the
     * `@on notification` trigger), `clipboard`, `apps` (apps.list), `lan` (http to the local
     * network or this device) and `commands` (launcher.run: terminal commands and other scripts).
     * The user allows each one ([ScriptAccess]).
     */
    val USES = listOf("notifications", "clipboard", "apps", "lan", "commands")

    /** Known names from every `@uses` line; unknown ones are ignored. */
    fun uses(code: String): Set<String> {
        val named = usesLine.findAll(code).flatMap { m -> m.groupValues[2].split(',', ' ', '\t') }
            .map { it.trim().lowercase() }.toSet()
        return USES.filterTo(LinkedHashSet()) { it in named }
    }

    /** Events a script can subscribe to with `@on`. */
    val EVENTS = listOf("unlock", "charger", "unplug", "wifi", "offline", "notification", "boot", "headset")

    fun isCommand(code: String) = command.containsMatchIn(code)

    fun description(code: String): String? = desc.find(code)?.groupValues?.get(2)?.trim()

    /** The text after a `@<name>` directive, or null when there is none (or it is empty). */
    private fun value(code: String, name: String): String? =
        Regex("""^\s*(--|//)\s*@$name\b[ \t]*(.*)$""", RegexOption.MULTILINE).find(code)
            ?.groupValues?.get(2)?.trim()?.takeIf { it.isNotEmpty() }

    /**
     * `@secret OWM_KEY api.openweathermap.org Your OpenWeather API key`: a value you set (in the
     * script's sheet or with `script secret`), read with `secrets.get("OWM_KEY")`, and sent only to
     * [hosts] (or below them; `*` for any).
     */
    data class Secret(val name: String, val hosts: List<String>, val desc: String) {
        fun allows(host: String): Boolean = hosts.any { it == "*" || host.equals(it, ignoreCase = true) || host.endsWith(".$it", ignoreCase = true) }
    }

    private val secretLine = Regex("""^\s*(--|//)\s*@secret\b[ \t]*(.*)$""", RegexOption.MULTILINE)
    private val secretName = Regex("^[A-Za-z][A-Za-z0-9_]{0,39}$")

    /** Every well-formed `@secret` line (a name, then its hosts); the first one of a name wins. */
    fun secrets(code: String): List<Secret> = secretLine.findAll(code).mapNotNull { m ->
        val parts = m.groupValues[2].trim().split(Regex("\\s+"), limit = 3)
        val name = parts.getOrNull(0)?.takeIf(secretName::matches) ?: return@mapNotNull null
        val hosts = parts.getOrNull(1)?.split(',')?.map { it.trim().lowercase() }?.filter { it.isNotEmpty() }?.takeIf { it.isNotEmpty() }
            ?: return@mapNotNull null
        Secret(name, hosts, parts.getOrNull(2).orEmpty())
    }.distinctBy { it.name }.toList()

    fun title(code: String): String? = value(code, "title")

    fun author(code: String): String? = value(code, "author")

    fun version(code: String): String? = value(code, "version")

    fun tags(code: String): List<String> =
        value(code, "tags")?.split(',')?.map { it.trim().lowercase() }?.filter { it.isNotEmpty() }?.distinct().orEmpty()

    /** `@every 1h`: the interval it suggests, in minutes; null without one (or `off`). */
    fun every(code: String): Int? =
        value(code, "every")?.let { com.mtwabpin.kolahal.terminal.CommandParsing.parseInterval(it) }?.takeIf { it > 0 }

    /** `@at 07:00, 18:30`: the daily times it suggests, as "HH:mm". */
    fun at(code: String): List<String> =
        value(code, "at")?.split(',', ' ')?.filter { it.isNotBlank() }?.mapNotNull(ScriptScheduler::normalizeTime)
            ?.distinct()?.sorted().orEmpty()

    /** `@notify`: it suggests letting it post notifications. */
    fun notifies(code: String): Boolean = Regex("""^\s*(--|//)\s*@notify\b""", RegexOption.MULTILINE).containsMatchIn(code)

    fun requires(code: String): String? = value(code, "requires")

    /** True when [code]'s `@requires` names a newer Kolahal than [current] (a version name such as 0.4.0). */
    fun needsNewer(code: String, current: String): Boolean = requires(code)?.let { isNewer(it, current) } == true

    /** True when version [wanted] is newer than [current]; false when either isn't a version. */
    fun isNewer(wanted: String, current: String): Boolean {
        val want = versionParts(wanted) ?: return false
        val have = versionParts(current) ?: return false
        for (i in 0 until maxOf(want.size, have.size)) {
            val w = want.getOrElse(i) { 0 }
            val h = have.getOrElse(i) { 0 }
            if (w != h) return w > h
        }
        return false
    }

    /** "0.4.0" or "0.4.0-lite" to [0, 4, 0]; null when it isn't a version. */
    private fun versionParts(v: String): List<Int>? =
        v.trim().removePrefix("v").substringBefore('-').split('.').map { it.toIntOrNull() ?: return null }

    /**
     * The command's usage line, `name args…`, like a built-in one: from `@usage` (the name in front
     * is optional there), or just [name] when the code never reads `args` or `argline`, so it can't
     * take any. Null when unknown: nothing is suggested after the name, and it never runs without Enter.
     */
    fun usage(code: String, name: String): String? {
        val m = usageLine.find(code) ?: return if (argRef.containsMatchIn(code)) null else name
        val rest = m.groupValues[2].trim()
        val args = if (rest == name || rest.startsWith("$name ")) rest.removePrefix(name).trim() else rest
        return if (args.isEmpty()) name else "$name $args"
    }

    /** Known events from every `@on` line, in [EVENTS] order; unknown names are ignored. */
    fun events(code: String): List<String> {
        val named = words(code).toSet()
        return EVENTS.filter { it in named }
    }

    /** Names in `@on` lines that aren't events (for warnings). */
    fun unknownEvents(code: String): List<String> = words(code).filter { it !in EVENTS }.distinct()

    private fun words(code: String): List<String> =
        on.findAll(code).flatMap { m -> m.groupValues[2].split(',', ' ', '\t') }
            .map { it.trim().lowercase() }
            .filter { it.isNotEmpty() }
            .toList()
}
