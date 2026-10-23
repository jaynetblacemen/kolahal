package com.mtwabpin.kolahal.script

import com.mtwabpin.kolahal.data.StyledText
import com.mtwabpin.kolahal.data.StyledText.Style

/**
 * The Markdown `md.log` reads, as [StyledText]: `**bold**` (or `__bold__`), `*italic*` (or
 * `_italic_`, not inside a word), `~~strike~~`, `++underline++` and `` `code` ``; in a body, list
 * items too: `- `, `* `, `+ ` or `1. `, two spaces in for the second level. A backslash before a
 * punctuation mark shows the mark as it is ([escape] puts them in).
 */
object Markdown {
    private val DOUBLE = listOf("**" to Style.BOLD, "__" to Style.BOLD, "~~" to Style.STRIKE, "++" to Style.UNDERLINE)
    private val ITEM = Regex("^( *)([-*+]|\\d{1,4}[.)]) +(.*)$")
    private const val PUNCTUATION = "!\"#$%&'()*+,-./:;<=>?@[\\]^_`{|}~"

    /** A title: styles within each line, no list items. */
    fun inline(text: String): String = text.split('\n').joinToString("\n") { span(it) }

    /** A body: styles, and lines that are list items. */
    fun block(text: String): String = text.split('\n').joinToString("\n") { line ->
        val m = ITEM.find(line) ?: return@joinToString span(line)
        val level = if (m.groupValues[1].length >= 2) 2 else 1
        val marker = m.groupValues[2]
        val number = if (marker[0].isDigit()) "${marker.dropLast(1)}. " else ""
        StyledText.item(level) + number + span(m.groupValues[3])
    }

    /** [text] with what Markdown would read as styles or list items escaped, so it shows as written. */
    fun escape(text: String): String = text.split('\n').joinToString("\n") { line ->
        val escaped = buildString { line.forEach { c -> if (c in "\\*_~+`") append('\\'); append(c) } }
        // A line that would read as a list item: "- milk", "1. milk".
        Regex("^( *)(-|\\d{1,4})([.)]?)( +)").find(escaped)?.let { m ->
            val (spaces, mark, dot, gap) = m.destructured
            when {
                mark == "-" && dot.isEmpty() -> "$spaces\\-$gap" + escaped.substring(m.range.last + 1)
                mark != "-" && dot.isNotEmpty() -> "$spaces$mark\\$dot$gap" + escaped.substring(m.range.last + 1)
                else -> null
            }
        } ?: escaped
    }

    private fun span(s: String): String {
        val out = StringBuilder()
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c == '\\' && i + 1 < s.length && s[i + 1] in PUNCTUATION) {
                out.append(s[i + 1])
                i += 2
                continue
            }
            if (c == '`') {
                val end = s.indexOf('`', i + 1)
                if (end > i + 1) {
                    out.append(StyledText.wrap(Style.CODE, s.substring(i + 1, end)))
                    i = end + 1
                    continue
                }
            }
            val double = DOUBLE.firstOrNull { (d, _) -> s.startsWith(d, i) }
            if (double != null) {
                val (d, style) = double
                val end = closing(s, d, i + 2)
                if (end > i + 2 && !s[i + 2].isWhitespace() && !s[end - 1].isWhitespace()) {
                    out.append(StyledText.wrap(style, span(s.substring(i + 2, end))))
                    i = end + 2
                    continue
                }
            }
            if (c == '*' || c == '_') {
                val end = closing(s, c.toString(), i + 1)
                // `_` only around words, not inside one (snake_case stays as it is).
                val opens = i + 1 < s.length && !s[i + 1].isWhitespace() && (c == '*' || i == 0 || !s[i - 1].isLetterOrDigit())
                val closes = end > i + 1 && !s[end - 1].isWhitespace() && (c == '*' || end + 1 >= s.length || !s[end + 1].isLetterOrDigit())
                if (opens && closes) {
                    out.append(StyledText.wrap(Style.ITALIC, span(s.substring(i + 1, end))))
                    i = end + 1
                    continue
                }
            }
            out.append(c)
            i++
        }
        return out.toString()
    }

    /** Where [d] next closes in [s] after [from]: not escaped, not in `code`; or -1. */
    private fun closing(s: String, d: String, from: Int): Int {
        var i = from
        while (i < s.length) {
            when {
                s[i] == '\\' -> i += 2
                s[i] == '`' -> s.indexOf('`', i + 1).let { e -> i = if (e < 0) i + 1 else e + 1 }
                // A single * or _ isn't closed by half of a double one (`*a **b** c*`).
                d.length == 1 && s.startsWith(d + d, i) -> i += 2
                s.startsWith(d, i) -> return i
                else -> i++
            }
        }
        return -1
    }
}
