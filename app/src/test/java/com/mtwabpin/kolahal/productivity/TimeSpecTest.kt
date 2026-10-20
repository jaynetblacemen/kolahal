package com.mtwabpin.kolahal.productivity

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDateTime
import java.time.LocalTime

class TimeSpecTest {
    // Saturday 17 Oct 2026, 14:20
    private val now = LocalDateTime.of(2026, 10, 17, 14, 20, 0)
    private fun parse(s: String) = TimeSpec.parseWhen(s.split(" "), now)

    @Test fun minutes() = assertEquals(TimeSpec.Parsed(now.plusMinutes(10), 1), parse("10m tea"))
    @Test fun hours() = assertEquals(now.plusHours(2), parse("2h call")?.at)
    @Test fun combined() = assertEquals(TimeSpec.Parsed(now.plusMinutes(90), 1), parse("1h30m stretch"))
    @Test fun inPrefix() = assertEquals(TimeSpec.Parsed(now.plusMinutes(5), 2), parse("in 5m x"))
    @Test fun clockLaterToday() = assertEquals(TimeSpec.Parsed(LocalDateTime.of(2026, 10, 17, 18, 30), 1), parse("18:30 gym"))
    @Test fun clockPassedMeansTomorrow() = assertEquals(LocalDateTime.of(2026, 10, 18, 9, 0), parse("9:00 x")?.at)
    @Test fun ampm() = assertEquals(LocalDateTime.of(2026, 10, 17, 18, 0), parse("6pm x")?.at)
    @Test fun atBareHour() = assertEquals(TimeSpec.Parsed(LocalDateTime.of(2026, 10, 17, 17, 0), 2), parse("at 17 x"))
    @Test fun tomorrowWithTime() = assertEquals(TimeSpec.Parsed(LocalDateTime.of(2026, 10, 18, 9, 0), 2), parse("tomorrow 9:00 call mom"))
    @Test fun tomorrowAtTime() = assertEquals(TimeSpec.Parsed(LocalDateTime.of(2026, 10, 18, 7, 15), 3), parse("tomorrow at 7:15 run"))
    @Test fun tomorrowDefaultsToNine() = assertEquals(TimeSpec.Parsed(LocalDateTime.of(2026, 10, 18, 9, 0), 1), parse("tomorrow call mom"))
    @Test fun weekday() = assertEquals(TimeSpec.Parsed(LocalDateTime.of(2026, 10, 23, 8, 0), 2), parse("fri 8am standup"))
    @Test fun sameWeekdayIsNextWeek() = assertEquals(LocalDateTime.of(2026, 10, 24, 9, 0), parse("saturday x")?.at)
    @Test fun todayPassedIsRejected() = assertNull(parse("today 9:00 x"))
    @Test fun bareNumberIsNotATime() = assertNull(parse("2 tickets"))
    @Test fun wordsAreNotATime() = assertNull(parse("buy milk"))
    @Test fun empty() = assertNull(TimeSpec.parseWhen(emptyList(), now))

    @Test fun durations() {
        assertEquals(600L, TimeSpec.parseDuration("10m"))
        assertEquals(5400L, TimeSpec.parseDuration("1h30m"))
        assertEquals(90L, TimeSpec.parseDuration("90s"))
        assertEquals(86_400L, TimeSpec.parseDuration("1d"))
        assertEquals(1500L, TimeSpec.parseDuration("25", bareMinutes = true))
        assertNull(TimeSpec.parseDuration("25"))
        assertNull(TimeSpec.parseDuration("0m"))
        assertNull(TimeSpec.parseDuration("abc"))
    }

    @Test fun clocks() {
        assertEquals(LocalTime.of(0, 5), TimeSpec.parseClock("12:05am"))
        assertEquals(LocalTime.of(12, 0), TimeSpec.parseClock("12pm"))
        assertEquals(LocalTime.of(7, 0), TimeSpec.parseClock("7", allowBareHour = true))
        assertNull(TimeSpec.parseClock("7"))
        assertNull(TimeSpec.parseClock("24:00"))
        assertNull(TimeSpec.parseClock("13pm"))
        assertNull(TimeSpec.parseClock("9:60"))
    }

    @Test fun formatting() {
        assertEquals("2h 10m", TimeSpec.formatDuration(7800))
        assertEquals("45s", TimeSpec.formatDuration(45))
        assertEquals("1d 1h", TimeSpec.formatDuration(90_000))
        assertEquals("5m", TimeSpec.formatDuration(300))
    }

    @Test fun refs() {
        val list = listOf(10L, 20L, 30L)
        assertEquals(20L, pickRef("2", list) { it })
        assertEquals(30L, pickRef("#30", list) { it })
        assertNull(pickRef("#2", list) { it })
        assertNull(pickRef("4", list) { it })
        assertNull(pickRef("x", list) { it })
    }

    @Test fun relativeSecondsOnlyForDurations() {
        assertEquals(7200L, TimeSpec.relativeSeconds(listOf("2h", "call")))
        assertEquals(600L, TimeSpec.relativeSeconds(listOf("in", "10m", "x")))
        assertNull(TimeSpec.relativeSeconds(listOf("18:30", "x")))
        assertNull(TimeSpec.relativeSeconds(listOf("at", "6pm")))
    }
}
