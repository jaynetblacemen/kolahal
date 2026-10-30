package com.mtwabpin.kolahal.script

import com.mtwabpin.kolahal.terminal.CommandParsing
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
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

    @Test fun commandsSentByScriptsMarkTheRunsTheyStart() {
        assertFalse(ScriptScheduler.runningSentCommand)
        assertEquals("ran", ScriptScheduler.sentByScript {
            assertTrue(ScriptScheduler.runningSentCommand)
            ScriptScheduler.sentByScript { assertTrue(ScriptScheduler.runningSentCommand) }
            assertTrue(ScriptScheduler.runningSentCommand)
            "ran"
        })
        assertFalse(ScriptScheduler.runningSentCommand)
        runCatching { ScriptScheduler.sentByScript { error("failed") } }
        assertFalse(ScriptScheduler.runningSentCommand)
        // Only on the thread that runs the command.
        var elsewhere = true
        ScriptScheduler.sentByScript { Thread { elsewhere = ScriptScheduler.runningSentCommand }.apply { start(); join() } }
        assertFalse(elsewhere)
    }

    @Test fun normalizeTime() {
        assertEquals("08:05", ScriptScheduler.normalizeTime("8:05"))
        assertNull(ScriptScheduler.normalizeTime("8"))
        assertNull(ScriptScheduler.normalizeTime("25:00"))
    }

    @Test fun parseClock() {
        assertEquals("08:00", CommandParsing.parseClock("8:00"))
        assertEquals("18:30", CommandParsing.parseClock("18:30"))
        assertNull(CommandParsing.parseClock("24:00"))
        assertNull(CommandParsing.parseClock("7pm"))
    }

    /** What follows `script at <name>`, split as the command splits it: commas, spaces or both between the times. */
    private fun typed(words: String) = CommandParsing.clockWords(CommandParsing.tokenize(words))

    @Test fun scriptAtTakesCommasSpacesOrBoth() {
        val both = listOf("22:30", "06:30")
        assertEquals(both, typed("22:30, 06:30"))
        assertEquals(both, typed("22:30,06:30"))
        assertEquals(both, typed("22:30 06:30"))
        assertEquals(both, typed("22:30 , 06:30"))
        assertEquals(both, typed("\"22:30, 06:30\""))
        assertEquals(listOf("22:30"), typed("22:30"))
        assertEquals(listOf("off"), typed("off"))
    }

    /** A comma alone says no times, for a usage line: it must not clear them as `off` does. */
    @Test fun scriptAtWithoutTimes() {
        assertEquals(emptyList<String>(), typed(""))
        assertEquals(emptyList<String>(), typed(","))
    }

    /** An `@at` line and `script at` read a list of times alike. */
    @Test fun scriptAtAgreesWithAnAtLine() {
        for (form in listOf("7:05", "22:30,06:30", "22:30, 06:30", "22:30 06:30", "22:30 , 06:30", "22:30,", ",22:30", "22:30,,06:30", "18:30, 7:05, 18:30")) {
            assertEquals(form, ScriptMeta.at("-- @at $form"), typed(form).mapNotNull(CommandParsing::parseClock).distinct().sorted())
        }
    }
}
