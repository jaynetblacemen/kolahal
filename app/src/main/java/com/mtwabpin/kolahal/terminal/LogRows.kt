package com.mtwabpin.kolahal.terminal

import androidx.compose.runtime.Immutable
import com.mtwabpin.kolahal.data.LogContext
import com.mtwabpin.kolahal.data.LogEntry
import com.mtwabpin.kolahal.data.LogKind
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Which coloured gutter a line gets, so command blocks and timer output read as groups. */
enum class Gutter { COMMAND, SCRIPT, ERROR }

/** What the log list renders: entries plus synthetic day / unread separators. */
@Immutable
sealed interface LogRow {
    val key: Any

    @Immutable
    data class Entry(
        val entry: LogEntry,
        val dimmed: Boolean,
        val gutter: Gutter,
        /** Script output that arrived since the user last left home. */
        val unread: Boolean = false,
        /** Starts a block (command or script run header): gets the block gap above it. */
        val blockStart: Boolean = false,
        /**
         * Ends with its source's name (a notification: its app's icon): the first line of a run or a
         * group, unless the command that ran it is right above, naming it already.
         */
        val showSource: Boolean = true,
        /** Shows its time under `log.time groups`: a group's first line, the first of a day, or a new minute. */
        val showTime: Boolean = true,
    ) : LogRow {
        override val key: Any get() = entry.id
    }

    @Immutable
    data class Day(val day: LocalDate) : LogRow {
        override val key: Any get() = "day:$day"
    }

    @Immutable
    data class Unread(val count: Int) : LogRow {
        override val key: Any get() = "unread"
    }
}

/** Lines further apart than this belong to different groups (unless a command or header joins them). */
const val GROUP_GAP_MS = 3_000L

/**
 * Output older than the most recent command is dimmed, so the latest result stands out;
 * timer-script lines that arrived after it stay bright. A separator marks each new day, and
 * script output newer than [seenUpTo] is marked unread with a divider above the first one.
 */
fun buildRows(
    entries: List<LogEntry>,
    seenUpTo: Long = Long.MAX_VALUE,
    unreadUntil: Long = Long.MAX_VALUE,
    zone: ZoneId = ZoneId.systemDefault(),
    /** Leave out a name or a time the lines above already give; off for lines out of context (search results). */
    trim: Boolean = true,
): List<LogRow> {
    val latestCommand = entries.lastOrNull { it.kind == LogKind.COMMAND }?.id ?: Long.MIN_VALUE
    val today = LocalDate.now(zone)
    val isScriptLine = { e: LogEntry -> e.kind == LogKind.SCRIPT }
    val isUnread = { e: LogEntry -> e.id > seenUpTo && e.id <= unreadUntil }
    val unreadCount = entries.count { isUnread(it) && it.kind == LogKind.SCRIPT }
    val rows = ArrayList<LogRow>(entries.size + 4)
    var previousDay: LocalDate? = null
    var unreadShown = false
    var previous: LogEntry? = null
    for (e in entries) {
        val day = Instant.ofEpochMilli(e.ts).atZone(zone).toLocalDate()
        // Only forward: lines are in id order, and a clock set back (or a script line buffered across
        // midnight) would otherwise emit the same day twice, which is a duplicate list key.
        val last = previousDay
        val newDay = if (last == null) day != today else day > last
        if (newDay) rows += LogRow.Day(day)
        if (last == null || day > last) previousDay = day
        val unread = isUnread(e) && isScriptLine(e)
        if (unread && !unreadShown && unreadCount > 0) {
            rows += LogRow.Unread(unreadCount)
            unreadShown = true
        }
        // A background run's first line: a script or a feed starts logging after other lines.
        val runStart = isScriptLine(e) && (previous == null || !isScriptLine(previous) || previous.source != e.source)
        // Right under the command that ran it (or under that command's other lines): its output,
        // in its group, and `$ countdown` names it already.
        val from = e.ctx.values[LogContext.FROM]?.toLongOrNull()
        val underItsCommand = from != null && previous != null &&
            (previous.id == from || previous.ctx.values[LogContext.FROM]?.toLongOrNull() == from)
        // A group is one action's lines: a typed command and its output, a background run
        // from its first line, or lines that arrived together. A line that comes on its own later
        // (a tap launch, an app event, a notification) starts a group of its own.
        val blockStart = e.kind == LogKind.COMMAND || (runStart && !underItsCommand) ||
            (previous != null && e.ts - previous.ts > GROUP_GAP_MS)
        rows += LogRow.Entry(
            entry = e,
            dimmed = e.id < latestCommand,
            gutter = when {
                e.kind == LogKind.ERROR -> Gutter.ERROR
                isScriptLine(e) -> Gutter.SCRIPT
                else -> Gutter.COMMAND
            },
            unread = unread && e.kind == LogKind.SCRIPT,
            blockStart = blockStart,
            showSource = !trim || ((runStart || blockStart) && !underItsCommand),
            showTime = !trim || blockStart || newDay || previous == null || e.ts / 60_000 != previous.ts / 60_000,
        )
        previous = e
    }
    return rows
}

/**
 * Where a line came from and when, to the second, for its long-press sheet (`countdown · today
 * 6:55:12`): what the log may leave off the line itself.
 */
fun lineDetail(
    e: LogEntry,
    clock24: Boolean,
    now: Long = System.currentTimeMillis(),
    zone: ZoneId = ZoneId.systemDefault(),
    locale: Locale = Locale.getDefault(),
): String {
    val at = Instant.ofEpochMilli(e.ts).atZone(zone)
    val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
    val day = when (at.toLocalDate()) {
        today -> "today"
        today.minusDays(1) -> "yesterday"
        else -> at.format(DateTimeFormatter.ofPattern("EEE d MMM", locale))
    }
    val time = at.format(DateTimeFormatter.ofPattern(if (clock24) "HH:mm:ss" else "h:mm:ss a", locale))
    return listOfNotNull(e.source?.takeIf { e.kind == LogKind.SCRIPT }, "$day $time").joinToString(" · ")
}
