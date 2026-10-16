package io.github.rscipher001.launcher.script

/**
 * Directives read from a script's comments:
 *
 *   -- @command            (or `// @command` in JS) — the script can be run from the prompt by name
 *   -- @desc <text>        — one-line description shown in `help` and suggestions
 *   -- @on <event>[, ...]  — run when a device event happens (see [EVENTS]); the payload is `event`
 */
object ScriptMeta {
    private val command = Regex("""^\s*(--|//)\s*@command\b""", RegexOption.MULTILINE)
    private val desc = Regex("""^\s*(--|//)\s*@desc\s+(.+)$""", RegexOption.MULTILINE)
    private val on = Regex("""^\s*(--|//)\s*@on\b(.*)$""", RegexOption.MULTILINE)

    /** Events a script can subscribe to with `@on`. */
    val EVENTS = listOf("unlock", "charger", "unplug", "wifi", "offline", "notification", "boot", "headset")

    fun isCommand(code: String) = command.containsMatchIn(code)

    fun description(code: String): String? = desc.find(code)?.groupValues?.get(2)?.trim()

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
