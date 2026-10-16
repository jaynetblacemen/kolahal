package io.github.rscipher001.launcher.script

import io.github.rscipher001.launcher.terminal.CommandProcessor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDateTime

class ScheduleTest {
    private val now = LocalDateTime.of(2026, 10, 16, 21, 30)

    @Test fun millisUntilLaterToday() =
        assertEquals(30 * 60_000L, ScriptScheduler.millisUntil("22:00", now))

    @Test fun millisUntilTomorrowWhenPassed() =
        assertEquals((10 * 60 + 30) * 60_000L, ScriptScheduler.millisUntil("08:00", now))

    @Test fun millisUntilSameMinuteMeansTomorrow() =
        assertEquals(24 * 60 * 60_000L, ScriptScheduler.millisUntil("21:30", now))

    @Test fun parseClock() {
        assertEquals("08:00", CommandProcessor.parseClock("8:00"))
        assertEquals("18:30", CommandProcessor.parseClock("18:30"))
        assertNull(CommandProcessor.parseClock("24:00"))
        assertNull(CommandProcessor.parseClock("7pm"))
    }
}
