package io.github.rscipher001.launcher.terminal

import io.github.rscipher001.launcher.data.LogContext
import io.github.rscipher001.launcher.data.LogEntry
import io.github.rscipher001.launcher.data.LogKind
import org.junit.Assert.assertEquals
import org.junit.Test

class LogRowsTest {
    private val t0 = System.currentTimeMillis()

    private fun line(id: Long, at: Long, kind: LogKind = LogKind.INFO, ctx: LogContext = LogContext.EMPTY) =
        LogEntry(id, t0 + at, kind, null, "line $id", ctx)

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
    fun runHeaderStartsAGroupEvenRightAfterOtherLines() {
        val header = LogContext.of(LogContext.RUN_HEADER to "1")
        assertEquals(
            listOf(false, true, false),
            starts(line(1, 0), line(2, 10, ctx = header), line(3, 20, LogKind.SCRIPT)),
        )
    }
}
