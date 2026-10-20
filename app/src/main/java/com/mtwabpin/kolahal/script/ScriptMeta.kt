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
     * `@on notification` trigger), `clipboard`, `apps` (apps.list) and `lan` (http to the local
     * network or this device). Imported scripts arrive switched off, so turning one on is the
     * user's consent to what it declares.
     */
    val USES = listOf("notifications", "clipboard", "apps", "lan")

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
