package io.github.rscipher001.launcher.script

/**
 * Directives read from a script's comments:
 *
 *   -- @command            (or `// @command` in JS) — the script can be run from the prompt by name
 *   -- @desc <text>        — one-line description shown in `help` and suggestions
 */
object ScriptMeta {
    private val command = Regex("""^\s*(--|//)\s*@command\b""", RegexOption.MULTILINE)
    private val desc = Regex("""^\s*(--|//)\s*@desc\s+(.+)$""", RegexOption.MULTILINE)

    fun isCommand(code: String) = command.containsMatchIn(code)

    fun description(code: String): String? = desc.find(code)?.groupValues?.get(2)?.trim()
}
