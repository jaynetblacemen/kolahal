package io.github.rscipher001.launcher.productivity

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset

class AgendaTest {
    private val zone = ZoneId.of("Asia/Kolkata")
    private val today = LocalDate.of(2026, 10, 17)
    private fun local(d: Int, h: Int, m: Int = 0) = LocalDateTime.of(2026, 10, d, h, m).atZone(zone).toInstant().toEpochMilli()
    private fun utcDay(d: Int) = LocalDate.of(2026, 10, d).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
    private val now = local(17, 12)

    @Test fun groupsByDayWithAllDayFirst() {
        val events = listOf(
            AgendaEvent(1, "Standup", local(17, 13), local(17, 13, 30), false),
            AgendaEvent(2, "Holiday", utcDay(17), utcDay(18), true),
            AgendaEvent(3, "Dinner", local(18, 20), local(18, 21), false),
            AgendaEvent(4, "Breakfast (over)", local(17, 8), local(17, 9), false),
            AgendaEvent(5, "Far away", local(25, 9), local(25, 10), false),
        )
        val g = Agenda.group(events, zone, today, 3, now)
        assertEquals(listOf(today, today.plusDays(1)), g.map { it.first })
        assertEquals(listOf("Holiday", "Standup"), g[0].second.map { it.title })
        assertEquals(listOf("Dinner"), g[1].second.map { it.title })
    }

    @Test fun multiDayAllDayRepeats() {
        val trip = AgendaEvent(1, "Trip", utcDay(16), utcDay(19), true)
        val g = Agenda.group(listOf(trip), zone, today, 5, now)
        assertEquals(listOf(today, today.plusDays(1)), g.map { it.first })
    }

    @Test fun ongoingEventShowsToday() {
        val e = AgendaEvent(1, "Night shift", local(16, 22), local(17, 14), false)
        assertEquals(listOf(today), Agenda.group(listOf(e), zone, today, 1, now).map { it.first })
    }
}
