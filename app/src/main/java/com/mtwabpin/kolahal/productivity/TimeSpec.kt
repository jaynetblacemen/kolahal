package com.mtwabpin.kolahal.productivity

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime

/** Pure parsing of the times and durations typed after `remind`, `timer` and `alarm`. */
object TimeSpec {
    private val DURATION = Regex("^(?:(\\d+)d)?(?:(\\d+)h)?(?:(\\d+)m(?:in)?)?(?:(\\d+)s)?$")
    private val CLOCK = Regex("^(\\d{1,2})(?:[:.](\\d{2}))?\\s*(am|pm|a|p)?$")

    /**
     * "10m", "2h", "1h30m", "90s", "1d" -> seconds. A bare number counts as minutes only when
     * [bareMinutes] is true (`timer 5`); `remind 2 tickets` must not mean two minutes.
     */
    fun parseDuration(raw: String, bareMinutes: Boolean = false): Long? {
        val s = raw.trim().lowercase()
        if (s.isEmpty()) return null
        if (s.all { it.isDigit() }) return if (bareMinutes) s.toLongOrNull()?.takeIf { it > 0 }?.times(60) else null
        val m = DURATION.find(s) ?: return null
        val (d, h, min, sec) = m.destructured
        if (d.isEmpty() && h.isEmpty() && min.isEmpty() && sec.isEmpty()) return null
        val total = (d.toLongOrNull() ?: 0) * 86_400 + (h.toLongOrNull() ?: 0) * 3_600 +
            (min.toLongOrNull() ?: 0) * 60 + (sec.toLongOrNull() ?: 0)
        return total.takeIf { it > 0 }
    }

    /** "18:30", "9:05", "6pm", "6:30pm"; a bare hour ("9") only when [allowBareHour]. */
    fun parseClock(raw: String, allowBareHour: Boolean = false): LocalTime? {
        val s = raw.trim().lowercase()
        val m = CLOCK.find(s) ?: return null
        val (hRaw, minRaw, ampm) = m.destructured
        if (minRaw.isEmpty() && ampm.isEmpty() && !allowBareHour) return null
        var hour = hRaw.toInt()
        val minute = minRaw.toIntOrNull() ?: 0
        if (minute > 59) return null
        if (ampm.isNotEmpty()) {
            if (hour !in 1..12) return null
            if (ampm.startsWith("p") && hour != 12) hour += 12
            if (ampm.startsWith("a") && hour == 12) hour = 0
        }
        if (hour > 23) return null
        return LocalTime.of(hour, minute)
    }

    /** A point in time and how many leading tokens described it (the rest is the reminder text). */
    data class Parsed(val at: LocalDateTime, val consumed: Int)

    /**
     * The duration in seconds when [tokens] start with one (`2h`, `in 10m`), else null. Callers add
     * it to the real clock: across a DST change, wall-clock arithmetic on [Parsed.at] is an hour off.
     */
    fun relativeSeconds(tokens: List<String>): Long? {
        val first = tokens.getOrNull(0)?.lowercase() ?: return null
        if (first == "at") return null
        return parseDuration((if (first == "in") tokens.getOrNull(1) else first)?.lowercase() ?: return null)
    }

    /** "fri", "friday" (three letters at least). */
    private fun weekday(token: String): DayOfWeek? {
        val t = token.lowercase()
        if (t.length < 3) return null
        return DayOfWeek.entries.firstOrNull { it.name.lowercase().startsWith(t) }
    }

    /** Default time for a day given without one ("tomorrow", "fri"). */
    val DEFAULT_TIME: LocalTime = LocalTime.of(9, 0)

    /**
     * Parses the start of [tokens]: `10m`, `in 2h`, `1h30m`, `18:30`, `at 6pm`, `tomorrow 9:00`,
     * `today 17:00`, `fri 8am`. A clock time that has already passed today means tomorrow.
     * Returns null when the tokens don't start with a time.
     */
    fun parseWhen(tokens: List<String>, now: LocalDateTime): Parsed? {
        var i = 0
        val first = tokens.getOrNull(0)?.lowercase() ?: return null
        if (first == "in" || first == "at") i = 1
        val head = tokens.getOrNull(i)?.lowercase() ?: return null

        if (first != "at") parseDuration(head)?.let { return Parsed(now.plusSeconds(it).withNano(0), i + 1) }
        if (first == "in") return null

        val day: LocalDate? = when {
            head == "today" -> now.toLocalDate()
            head == "tomorrow" || head == "tmrw" || head == "tmr" -> now.toLocalDate().plusDays(1)
            else -> weekday(head)?.let { dow ->
                var d = now.toLocalDate().plusDays(1)
                while (d.dayOfWeek != dow) d = d.plusDays(1)
                d
            }
        }
        if (day != null) {
            var j = i + 1
            if (tokens.getOrNull(j)?.lowercase() == "at") j++
            val time = tokens.getOrNull(j)?.let { parseClock(it, allowBareHour = true) }
            val at = day.atTime(time ?: DEFAULT_TIME)
            // "today 9:00" at 10:00 is in the past: refuse rather than fire at once.
            if (!at.isAfter(now)) return null
            return Parsed(at, if (time != null) j + 1 else i + 1)
        }

        val time = parseClock(head, allowBareHour = first == "at") ?: return null
        var at = now.toLocalDate().atTime(time)
        if (!at.isAfter(now)) at = at.plusDays(1)
        return Parsed(at, i + 1)
    }

    /** 7800 -> "2h 10m"; 45 -> "45s"; 90000 -> "1d 1h". */
    fun formatDuration(seconds: Long): String {
        val s = seconds.coerceAtLeast(0)
        val d = s / 86_400
        val h = s % 86_400 / 3_600
        val m = s % 3_600 / 60
        val sec = s % 60
        return when {
            d > 0 -> "${d}d" + if (h > 0) " ${h}h" else ""
            h > 0 -> "${h}h" + if (m > 0) " ${m}m" else ""
            m > 0 -> "${m}m" + if (sec > 0 && m < 10) " ${sec}s" else ""
            else -> "${sec}s"
        }
    }
}
