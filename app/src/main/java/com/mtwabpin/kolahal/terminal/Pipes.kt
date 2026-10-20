package com.mtwabpin.kolahal.terminal

import com.mtwabpin.kolahal.data.LogContext
import com.mtwabpin.kolahal.data.LogKind
import com.mtwabpin.kolahal.data.LogRepository.Pending

/**
 * Shell-style filters over a command's output: `help | grep app`, `history | tail 5`,
 * `feed read | grep -v sponsored | head 3`, `contacts | count`.
 *
 * Only a `|` followed by a known filter starts a stage, so `lua a or b`, `js x || y` and `=` maths
 * are never split. Error lines always pass through; output a command prints later (network
 * replies, scripts) isn't captured.
 */
object Pipes {
    val FILTERS = listOf("grep", "head", "tail", "count", "wc", "sort", "uniq")

    private val SPLIT = Regex("""\s\|\s*(?=(?:${FILTERS.joinToString("|")})\b)""")

    fun split(line: String): List<String> = line.split(SPLIT).map { it.trim() }.filter { it.isNotEmpty() }

    fun apply(lines: List<Pending>, stages: List<String>): Result<List<Pending>> = runCatching {
        var out = lines
        for (stage in stages) out = applyStage(out, stage)
        out
    }

    private fun applyStage(lines: List<Pending>, stage: String): List<Pending> {
        val parts = stage.split(Regex("\\s+"))
        val name = parts[0].lowercase()
        val args = parts.drop(1)
        // Errors and the command echo are not data: they pass every stage untouched.
        val (keep, data) = lines.partition { it.kind == LogKind.ERROR || it.kind == LogKind.COMMAND }
        val result = when (name) {
            "grep" -> {
                val invert = "-v" in args
                val pattern = args.filterNot { it == "-v" || it == "-i" }.joinToString(" ")
                require(pattern.isNotEmpty()) { "usage: … | grep [-v] <pattern>" }
                val regex = runCatching { Regex(pattern, RegexOption.IGNORE_CASE) }
                    .getOrElse { Regex(Regex.escape(pattern), RegexOption.IGNORE_CASE) }
                // A pattern that backtracks without end (`(.*.*)*x`) stops after a second
                // instead of holding up every command after it.
                val until = System.nanoTime() + GREP_BUDGET_MS * 1_000_000
                data.filter { regex.containsMatchIn(Budget(searchable(it), until)) != invert }
            }
            "head" -> data.take(count(args))
            "tail" -> data.takeLast(count(args))
            "sort" -> if ("-r" in args) data.sortedByDescending { it.text.lowercase() } else data.sortedBy { it.text.lowercase() }
            "uniq" -> data.distinctBy { it.text }
            "count", "wc" -> listOf(data.firstOrNull()?.copy(kind = LogKind.OUTPUT, text = "${data.size}", ctx = LogContext.EMPTY)
                ?: Pending(LogKind.OUTPUT, "0", null, LogContext.EMPTY, System.currentTimeMillis()))
            else -> throw IllegalArgumentException("Unknown filter '$name' (${FILTERS.joinToString()})")
        }
        return keep + result
    }

    /** Text plus the right-hand description of two-column rows (help, config, script ls). */
    private fun searchable(p: Pending): String = p.ctx.values[LogContext.DESC]?.let { "${p.text} $it" } ?: p.text

    /** `head 5`, `head -5`, `head -n5` and `head -n 5`. */
    private fun count(args: List<String>): Int {
        val raw = if (args.firstOrNull() == "-n") args.getOrNull(1) else args.firstOrNull()?.removePrefix("-")?.removePrefix("n")
        return raw?.toIntOrNull()?.coerceIn(0, 10_000) ?: 10
    }
}

private const val GREP_BUDGET_MS = 1_000L

/** Text that gives up being read after [until] (System.nanoTime), so a regex over it can't run for ever. */
private class Budget(private val s: CharSequence, private val until: Long) : CharSequence {
    override val length: Int get() = s.length
    override fun get(index: Int): Char {
        if (System.nanoTime() > until) throw IllegalArgumentException("grep: pattern too slow, try a simpler one")
        return s[index]
    }
    override fun subSequence(startIndex: Int, endIndex: Int): CharSequence = Budget(s.subSequence(startIndex, endIndex), until)
    override fun toString(): String = s.toString()
}
