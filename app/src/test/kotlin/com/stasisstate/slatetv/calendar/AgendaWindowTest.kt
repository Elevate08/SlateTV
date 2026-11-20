package com.stasisstate.slatetv.calendar

import java.time.Instant
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AgendaWindowTest {
    @Test
    fun allDayEventStaysOnItsLocalDateAfterItsUtcEnd() {
        val window = AgendaWindow(Instant.parse("2026-11-21T04:00:00Z"), ZoneId.of("America/New_York"))
        val event = event("2026-11-20T00:00:00Z", "2026-11-21T00:00:00Z", isAllDay = true)

        assertTrue(window.queryStartMillis < event.endMillis)
        assertEquals(millis("2026-11-20T05:00:00Z"), window.eligibleStartMillis(event))
    }

    @Test
    fun allDayEventAppearsAfterLocalMidnightBeforeItsUtcStart() {
        val window = AgendaWindow(Instant.parse("2026-11-19T17:00:00Z"), ZoneId.of("Asia/Tokyo"))
        val event = event("2026-11-20T00:00:00Z", "2026-11-21T00:00:00Z", isAllDay = true)

        assertEquals(millis("2026-11-19T15:00:00Z"), window.eligibleStartMillis(event))
    }

    @Test
    fun queryReachesAllDayStartWhenTheHorizonEndsBeforeUtcMidnight() {
        val window = AgendaWindow(Instant.parse("2026-11-19T17:00:00Z"), ZoneId.of("Asia/Tokyo"))
        val event = event("2026-11-27T00:00:00Z", "2026-11-28T00:00:00Z", isAllDay = true)

        assertTrue(window.queryEndMillis > event.startMillis)
        assertEquals(millis("2026-11-26T15:00:00Z"), window.eligibleStartMillis(event))
    }

    @Test
    fun queryReachesTimedHorizonAfterUtcMidnightInWesternTimeZones() {
        val window = AgendaWindow(Instant.parse("2026-11-20T09:00:00Z"), ZoneId.of("Pacific/Honolulu"))

        assertTrue(window.queryEndMillis >= millis("2026-11-27T09:00:00Z"))
    }

    @Test
    fun threeEarliestEventsUseLocalAllDayDatesAndKeepProviderOrderForTies() {
        val window = AgendaWindow(Instant.parse("2026-11-19T17:00:00Z"), ZoneId.of("Asia/Tokyo"))
        val first = event("2026-11-19T18:00:00Z", "2026-11-19T19:00:00Z").copy(title = "First")
        val second = event("2026-11-19T20:00:00Z", "2026-11-19T21:00:00Z").copy(title = "Second")
        val tied = second.copy(title = "Later in provider order")
        val allDay = event("2026-11-20T00:00:00Z", "2026-11-21T00:00:00Z", isAllDay = true)

        assertEquals(listOf(allDay, first, second), window.selectUpcoming(sequenceOf(first, second, tied, allDay)))
    }

    @Test
    fun ongoingAndInstantaneousTimedEventsAreKeptWhileFinishedEventsAreDropped() {
        val window = AgendaWindow(Instant.parse("2026-11-20T12:00:00Z"), ZoneId.of("UTC"))
        val ongoing = event("2026-11-20T11:00:00Z", "2026-11-20T13:00:00Z")
        val finished = event("2026-11-20T11:00:00Z", "2026-11-20T12:00:00Z")
        val instantaneous = event("2026-11-20T14:00:00Z", "2026-11-20T14:00:00Z")

        assertEquals(ongoing.startMillis, window.eligibleStartMillis(ongoing))
        assertNull(window.eligibleStartMillis(finished))
        assertEquals(instantaneous.startMillis, window.eligibleStartMillis(instantaneous))
    }

    @Test
    fun sevenDayHorizonFollowsLocalTimeAcrossDaylightSaving() {
        val window = AgendaWindow(Instant.parse("2026-03-06T17:00:00Z"), ZoneId.of("America/New_York"))
        val beforeLocalNoon = event("2026-03-13T15:59:00Z", "2026-03-13T16:30:00Z")
        val atLocalNoon = event("2026-03-13T16:00:00Z", "2026-03-13T16:30:00Z")

        assertEquals(beforeLocalNoon.startMillis, window.eligibleStartMillis(beforeLocalNoon))
        assertNull(window.eligibleStartMillis(atLocalNoon))
    }

    private fun event(start: String, end: String, isAllDay: Boolean = false): CalendarEvent =
        CalendarEvent("Event", millis(start), millis(end), isAllDay)

    private fun millis(value: String): Long = Instant.parse(value).toEpochMilli()
}
