package com.mtwabpin.kolahal.terminal

import com.mtwabpin.kolahal.data.LogContext
import com.mtwabpin.kolahal.data.LogEntry
import com.mtwabpin.kolahal.data.LogKind
import org.junit.Assert.assertEquals
import org.junit.Test

class LogRowsTest {
    private val t0 = System.currentTimeMillis()

    private fun line(id: Long, at: Long, kind: LogKind = LogKind.INFO, ctx: LogContext = LogContext.EMPTY, source: String? = null) =
        LogEntry(id, t0 + at, kind, source, "line $id", ctx)

    private fun starts(vararg entries: LogEntry): List<Boolean> =
        buildRows(entries.toList()).filterIsInstance<LogRow.Entry>().map { it.blockStart }

    @Test
    fun commandAndItsOutputAreOneGroup() {
        assertEquals(
            listOf(true, false, false),
            starts(line(1, 0, LogKind.COMMAND), line(2, 100, LogKind.OUTPUT), line(3, 900, LogKind.OUTPUT)),
        )
    }

    @Test
    fun lineArrivingLaterOnItsOwnStartsAGroup() {
        // e.g. an app launched by tapping, or an install event, well after the last command.
        assertEquals(
            listOf(true, false, true, true),
            starts(
                line(1, 0, LogKind.COMMAND),
                line(2, 200, LogKind.OUTPUT),
                line(3, 60_000),
                line(4, 120_000),
            ),
        )
    }

    @Test
    fun linesArrivingTogetherStayTogether() {
        assertEquals(listOf(false, false), starts(line(1, 0), line(2, GROUP_GAP_MS)))
    }

    @Test
    fun timestampsGoingBackNeverRepeatADayDivider() {
        // id order with days running D-2, D-1, D-2, D-1 (clock set back, or a buffered script line
        // written just after midnight): each day divider must appear once, or the list key repeats.
        val day = 86_400_000L
        val rows = buildRows(
            listOf(line(1, -2 * day), line(2, -day), line(3, -2 * day + 1), line(4, -day + 1)),
        )
        val keys = rows.filterIsInstance<LogRow.Day>().map { it.key }
        assertEquals(keys.distinct(), keys)
        assertEquals(rows.map { it.key }.distinct().size, rows.size)
    }

    /** A script's or a feed's lines are a group of their own, even right after other lines. */
    @Test
    fun aRunStartsAGroupEvenRightAfterOtherLines() {
        assertEquals(
            listOf(true, true, false, true, false),
            starts(
                line(1, 0, LogKind.COMMAND),
                line(2, 10, LogKind.SCRIPT, source = "exchange"),
                line(3, 20, LogKind.SCRIPT, source = "exchange"),
                line(4, 30, LogKind.SCRIPT, source = "hn"),
                line(5, 40, LogKind.SCRIPT, source = "hn"),
            ),
        )
    }
}
