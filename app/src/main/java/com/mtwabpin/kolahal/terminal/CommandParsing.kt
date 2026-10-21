package com.mtwabpin.kolahal.terminal

/** Pure helpers for reading and printing command arguments. */
object CommandParsing {
    fun tokenize(s: String): List<String> {
        val out = ArrayList<String>()
        val cur = StringBuilder()
        var quote: Char? = null
        for (ch in s) {
            when {
                quote != null && ch == quote -> quote = null
                quote != null -> cur.append(ch)
                ch == '"' || ch == '\'' -> quote = ch
                ch.isWhitespace() -> if (cur.isNotEmpty()) { out += cur.toString(); cur.clear() }
                else -> cur.append(ch)
            }
        }
        if (cur.isNotEmpty()) out += cur.toString()
        return out
    }

    /** Longest timer or feed interval: a week, like backup import allows. */
    const val MAX_INTERVAL_MIN = 7L * 24 * 60

    /** "30m", "2h", "1d", "45" (minutes), "off" -> minutes (0 = off). */
    fun parseInterval(s: String?): Int? {
        if (s == null) return null
        if (s == "off" || s == "0" || s == "manual") return 0
        val m = Regex("^(\\d+)([mhd]?)$").find(s.lowercase()) ?: return null
        // Long, then capped: `1500000d` must be rejected, not wrap to a negative "timer off".
        val n = m.groupValues[1].toLongOrNull() ?: return null
        val minutes = when (m.groupValues[2]) {
            "h" -> n * 60
            "d" -> n * 60 * 24
            else -> n
        }
        return minutes.takeIf { it <= MAX_INTERVAL_MIN }?.toInt()
    }

    /** "8:00", "08:00", "18:30" -> "HH:mm"; null if invalid. */
    fun parseClock(s: String): String? {
        val m = Regex("^([01]?\\d|2[0-3]):([0-5]\\d)$").find(s.trim()) ?: return null
        return String.format(java.util.Locale.ROOT, "%02d:%s", m.groupValues[1].toInt(), m.groupValues[2])
    }

    /** Levenshtein distance, for "did you mean". */
    fun editDistance(a: String, b: String): Int {
        var prev = IntArray(b.length + 1) { it }
        for (i in 1..a.length) {
            val cur = IntArray(b.length + 1)
            cur[0] = i
            for (j in 1..b.length) {
                val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                cur[j] = minOf(prev[j] + 1, cur[j - 1] + 1, prev[j - 1] + cost)
            }
            prev = cur
        }
        return prev[b.length]
    }

    fun formatInterval(min: Int): String = when {
        min % (60 * 24) == 0 -> "${min / (60 * 24)}d"
        min % 60 == 0 -> "${min / 60}h"
        else -> "${min}m"
    }
}
