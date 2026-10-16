package io.github.rscipher001.launcher.terminal

import androidx.compose.runtime.Immutable
import io.github.rscipher001.launcher.data.LogContext
import io.github.rscipher001.launcher.data.LogEntry
import io.github.rscipher001.launcher.data.LogKind
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

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

/**
 * Output older than the most recent command is dimmed, so the latest result stands out;
 * timer-script lines that arrived after it stay bright. A separator marks each new day, and
 * script output newer than [seenUpTo] is marked unread with a divider above the first one.
 */
fun buildRows(
    entries: List<LogEntry>,
    seenUpTo: Long = Long.MAX_VALUE,
    zone: ZoneId = ZoneId.systemDefault(),
): List<LogRow> {
    val latestCommand = entries.lastOrNull { it.kind == LogKind.COMMAND }?.id ?: Long.MIN_VALUE
    val today = LocalDate.now(zone)
    val isScriptLine = { e: LogEntry -> e.kind == LogKind.SCRIPT || e.ctx.values.containsKey(LogContext.RUN_HEADER) }
    val unreadCount = entries.count { it.id > seenUpTo && it.kind == LogKind.SCRIPT }
    val rows = ArrayList<LogRow>(entries.size + 4)
    var previousDay: LocalDate? = null
    var unreadShown = false
    for (e in entries) {
        val day = Instant.ofEpochMilli(e.ts).atZone(zone).toLocalDate()
        if (day != previousDay && (previousDay != null || day != today)) rows += LogRow.Day(day)
        previousDay = day
        val unread = e.id > seenUpTo && isScriptLine(e)
        if (unread && !unreadShown && unreadCount > 0) {
            rows += LogRow.Unread(unreadCount)
            unreadShown = true
        }
        val header = e.ctx.values.containsKey(LogContext.RUN_HEADER)
        rows += LogRow.Entry(
            entry = e,
            dimmed = e.id < latestCommand,
            gutter = when {
                e.kind == LogKind.ERROR -> Gutter.ERROR
                isScriptLine(e) -> Gutter.SCRIPT
                else -> Gutter.COMMAND
            },
            unread = unread && e.kind == LogKind.SCRIPT,
            blockStart = e.kind == LogKind.COMMAND || header,
        )
    }
    return rows
}
