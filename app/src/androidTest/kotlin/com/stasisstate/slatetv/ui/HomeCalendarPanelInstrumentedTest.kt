package com.stasisstate.slatetv.ui

import android.content.Context
import android.text.format.DateUtils
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.stasisstate.slatetv.R
import com.stasisstate.slatetv.calendar.CalendarEvent
import java.time.Instant
import java.util.Formatter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class HomeCalendarPanelInstrumentedTest {
    @Test
    fun panelStaysOutOfDpadFocusAndClearsRowsWhenEventsAreRemoved() = withPanel { container, panel ->
        assertEquals(View.GONE, container.visibility)
        assertFalse(container.hasFocusable())

        val now = System.currentTimeMillis()
        val ongoing = CalendarEvent("Ongoing", now - HOUR, now + HOUR, false)
        val upcoming = CalendarEvent("Upcoming", now + HOUR, now + 2 * HOUR, false)
        panel.render(listOf(ongoing, upcoming))

        assertEquals(View.VISIBLE, container.visibility)
        assertFalse(container.hasFocusable())
        assertEquals(
            container.context.getString(R.string.home_calendar_in_progress, formatStart(container.context, ongoing)),
            eventText(container, 0, R.id.homeCalendarEventWhen),
        )
        assertEquals(formatStart(container.context, upcoming), eventText(container, 1, R.id.homeCalendarEventWhen))

        panel.render(listOf(upcoming))
        assertEquals(1, rows(container).childCount)
        assertEquals(upcoming.title, eventText(container, 0, R.id.homeCalendarEventTitle))

        panel.render(emptyList())
        assertEquals(View.GONE, container.visibility)
        assertEquals(0, rows(container).childCount)
    }

    @Test
    fun allDayEventsUseUtcDatesWithAnExclusiveEndAndFallbackTitle() = withPanel { container, panel ->
        val singleDay = CalendarEvent(" ", millis("2026-11-20T00:00:00Z"), millis("2026-11-21T00:00:00Z"), true)
        val multiDay = CalendarEvent("Trip", millis("2026-11-22T00:00:00Z"), millis("2026-11-25T00:00:00Z"), true)
        panel.render(listOf(singleDay, multiDay))

        val context = container.context
        val singleDate = DateUtils.formatDateTime(context, singleDay.startMillis, DATE_FLAGS or DateUtils.FORMAT_UTC)
        val dateRange = Formatter().use {
            DateUtils.formatDateRange(context, it, multiDay.startMillis, millis("2026-11-24T12:00:00Z"), DATE_FLAGS, "UTC")
                .toString()
        }
        assertEquals(context.getString(R.string.home_calendar_untitled), eventText(container, 0, R.id.homeCalendarEventTitle))
        assertEquals(context.getString(R.string.home_calendar_all_day, singleDate), eventText(container, 0, R.id.homeCalendarEventWhen))
        assertEquals(context.getString(R.string.home_calendar_all_day, dateRange), eventText(container, 1, R.id.homeCalendarEventWhen))
    }

    private fun withPanel(test: (ViewGroup, HomeCalendarPanel) -> Unit) {
        ActivityScenario.launch(HomeActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                val container = activity.layoutInflater.inflate(R.layout.home_calendar_panel, null) as ViewGroup
                test(container, HomeCalendarPanel(container))
            }
        }
    }

    private fun rows(container: ViewGroup) = container.findViewById<ViewGroup>(R.id.homeCalendarEvents)

    private fun eventText(container: ViewGroup, index: Int, id: Int) =
        rows(container).getChildAt(index).findViewById<TextView>(id).text.toString()

    private fun formatStart(context: Context, event: CalendarEvent) =
        DateUtils.formatDateTime(context, event.startMillis, DATE_FLAGS or DateUtils.FORMAT_SHOW_TIME)

    private fun millis(value: String) = Instant.parse(value).toEpochMilli()

    private companion object {
        const val DATE_FLAGS = DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_ABBREV_MONTH
        const val HOUR = 3_600_000L
    }
}
