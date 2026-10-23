package com.mtwabpin.kolahal.terminal

import java.time.LocalDate
import java.time.Month
import java.time.YearMonth
import java.time.format.TextStyle
import java.time.temporal.WeekFields
import java.util.Locale

/** `cal`: a month as the Unix `cal` prints it, today marked, weeks starting on the locale's day. */
object MonthCalendar {
    private val YEAR_MONTH = Regex("^(\\d{4})-(\\d{1,2})$")

    /**
     * The month [text] names: nothing for this month; a month by name or number (`dec`, `12`),
     * the coming one unless a year follows (`dec 2027`); or `2027-12`. Null when it names none.
     */
    fun parse(text: String, today: LocalDate, locale: Locale = Locale.getDefault()): YearMonth? {
        val words = text.trim().lowercase(locale).split(Regex("\\s+")).filter { it.isNotEmpty() }
        if (words.isEmpty()) return YearMonth.from(today)
        if (words.size > 2) return null
        YEAR_MONTH.matchEntire(words[0])?.let { m ->
            val month = m.groupValues[2].toInt()
            return if (words.size == 1 && month in 1..12) YearMonth.of(m.groupValues[1].toInt(), month) else null
        }
        val month = monthOf(words[0], locale) ?: return null
        val year = words.getOrNull(1)?.let { it.toIntOrNull()?.takeIf { y -> y in 1900..2200 } ?: return null }
        if (year != null) return YearMonth.of(year, month)
        val thisYear = YearMonth.of(today.year, month)
        return if (thisYear.isBefore(YearMonth.from(today))) thisYear.plusYears(1) else thisYear
    }

    /** A month's number from `12`, `dec` or `december`, in the locale's language or English. */
    private fun monthOf(word: String, locale: Locale): Int? {
        word.toIntOrNull()?.let { return it.takeIf { n -> n in 1..12 } }
        if (word.length < 3) return null
        return Month.entries.firstOrNull { m ->
            m.getDisplayName(TextStyle.FULL, locale).lowercase(locale).startsWith(word) || m.name.lowercase().startsWith(word)
        }?.value
    }

    /** Month names starting with [prefix] (any case), for completions. */
    fun suggest(prefix: String, locale: Locale = Locale.getDefault()): List<String> {
        val p = prefix.lowercase(locale)
        return Month.entries.map { it.getDisplayName(TextStyle.FULL, locale).lowercase(locale) }.filter { it.startsWith(p) && it != p }
    }

    /**
     * The lines of [month]: its name and year over the middle, the day names, then a line per
     * week with the days outside the month blank; [today]'s number goes through [mark] when it is
     * in the month. Every cell is two characters wide, so the lines hold in the terminal font.
     */
    fun lines(month: YearMonth, today: LocalDate, locale: Locale = Locale.getDefault(), mark: (String) -> String = { it }): List<String> {
        val first = WeekFields.of(locale).firstDayOfWeek
        val days = (0 until 7).map { first.plus(it.toLong()) }
        val title = month.month.getDisplayName(TextStyle.FULL, locale) + " " + month.year
        val out = ArrayList<String>()
        out += title.padStart((20 + title.length) / 2)
        out += days.joinToString(" ") { it.getDisplayName(TextStyle.SHORT, locale).take(2).padEnd(2) }
        val cells = ArrayList<String>()
        repeat(((month.atDay(1).dayOfWeek.value - first.value) + 7) % 7) { cells += "  " }
        var day = month.atDay(1)
        while (day.month == month.month) {
            val n = day.dayOfMonth.toString().padStart(2)
            cells += if (day == today) mark(n) else n
            day = day.plusDays(1)
        }
        while (cells.size % 7 != 0) cells += "  "
        cells.chunked(7).forEach { out += it.joinToString(" ").trimEnd() }
        return out
    }
}
