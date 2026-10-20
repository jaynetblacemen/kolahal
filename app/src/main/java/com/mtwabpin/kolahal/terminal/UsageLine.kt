package com.mtwabpin.kolahal.terminal

/**
 * A command's usage line, the one `help` shows (`feed [add <url>|rm] [id]`), read for option
 * completion and for instant runs of whole lines (`torch on`).
 */
object UsageLine {
    /** A completable literal in a usage line: `work`, `ls`, `-l`, `--vs`; not `<app>`, `0-100` or `30s`. */
    val OPTION = Regex("-{0,2}[a-z][a-z-]*")

    /** One way to fill an argument place: a literal [word] (null for a value like `<app>` or `0-100`), maybe with a value after it (`add <url>`). */
    data class Choice(val word: String?, val takesValue: Boolean)

    /** `feed [add <url>|rm] [id]` → `feed`, `[add <url>|rm]`, `[id]`: splits on spaces outside brackets. */
    fun tokens(usage: String): List<String> = split(usage, ' ').filter { it.isNotBlank() }

    /** The ways to fill the argument place [token]: `[add <url>|rm|<id>]` → `add` and a value, `rm`, a value. */
    fun choices(token: String): List<Choice> =
        split(token.removeSurrounding("[", "]"), '|').map { alt ->
            val word = alt.trim().substringBefore(' ')
            if (word.matches(OPTION)) Choice(word, alt.trim().contains(' ')) else Choice(null, true)
        }

    /**
     * Whether [line] fills every argument place of [usage] with nothing left to choose: each word is
     * an option of its place that takes no value, no other option there starts with it, no value
     * could go there instead, and the usage line ends with it. `torch on` for `torch [on|off]`; not
     * `torch o`, `apps work` (`-l` may follow) or `volume up` (a number could go there).
     */
    fun complete(line: String, usage: String): Boolean {
        val words = line.trim().lowercase().split(Regex("\\s+"))
        val places = tokens(usage).drop(1)
        if (words.size < 2 || words.size - 1 != places.size) return false
        return words.drop(1).zip(places).all { (word, place) ->
            val choices = choices(place)
            choices.all { it.word != null } &&
                choices.any { it.word == word && !it.takesValue } &&
                choices.none { it.word != word && it.word!!.startsWith(word) }
        }
    }

    /** Splits on [sep] where it is not inside `[]` or `<>`. */
    fun split(s: String, sep: Char): List<String> {
        val out = ArrayList<String>()
        var depth = 0
        val cur = StringBuilder()
        for (ch in s) {
            when {
                ch == '[' || ch == '<' -> { depth++; cur.append(ch) }
                ch == ']' || ch == '>' -> { depth--; cur.append(ch) }
                ch == sep && depth == 0 -> { out += cur.toString(); cur.clear() }
                else -> cur.append(ch)
            }
        }
        out += cur.toString()
        return out
    }
}
