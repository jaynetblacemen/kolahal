package com.mtwabpin.kolahal.script

import com.mtwabpin.kolahal.terminal.CommandProcessor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime

class ScheduleTest {
    private val now = ZonedDateTime.of(2026, 10, 16, 21, 30, 0, 0, ZoneId.of("UTC"))

    @Test fun millisUntilLaterToday() =
        assertEquals(30 * 60_000L, ScriptScheduler.millisUntil("22:00", now))

    @Test fun millisUntilTomorrowWhenPassed() =
        assertEquals((10 * 60 + 30) * 60_000L, ScriptScheduler.millisUntil("08:00", now))

    @Test fun millisUntilSameMinuteMeansTomorrow() =
        assertEquals(24 * 60 * 60_000L, ScriptScheduler.millisUntil("21:30", now))

    @Test fun dstFallBackAddsAnHour() {
        // Clocks go back at 03:00 on 2026-10-25 in Berlin, so 22:00 -> 08:00 is 11 real hours.
        val berlin = ZonedDateTime.of(2026, 10, 24, 22, 0, 0, 0, ZoneId.of("Europe/Berlin"))
        assertEquals(11 * 60 * 60_000L, ScriptScheduler.millisUntil("08:00", berlin))
    }

    @Test fun normalizeTime() {
        assertEquals("08:05", ScriptScheduler.normalizeTime("8:05"))
        assertNull(ScriptScheduler.normalizeTime("8"))
        assertNull(ScriptScheduler.normalizeTime("25:00"))
    }

    @Test fun parseClock() {
        assertEquals("08:00", CommandProcessor.parseClock("8:00"))
        assertEquals("18:30", CommandProcessor.parseClock("18:30"))
        assertNull(CommandProcessor.parseClock("24:00"))
        assertNull(CommandProcessor.parseClock("7pm"))
    }
}
