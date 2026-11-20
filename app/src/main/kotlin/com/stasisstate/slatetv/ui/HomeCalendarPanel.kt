package com.stasisstate.slatetv.ui

import android.text.format.DateUtils
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.core.view.ViewCompat
import com.stasisstate.slatetv.R
import com.stasisstate.slatetv.calendar.CalendarEvent
import java.util.Formatter

internal class HomeCalendarPanel(private val container: ViewGroup) {
    private val context = container.context
    private val rows = container.findViewById<ViewGroup>(R.id.homeCalendarEvents)
    private val inflater = LayoutInflater.from(context)

    init {
        ViewCompat.setAccessibilityHeading(container.findViewById(R.id.homeCalendarHeading), true)
    }

    fun render(events: List<CalendarEvent>) {
        val nowMillis = System.currentTimeMillis()
        events.forEachIndexed { index, event ->
            val row = rows.getChildAt(index) ?: inflater.inflate(R.layout.item_home_calendar_event, rows, false)
                .also(rows::addView)
            val title = event.title.ifBlank { context.getString(R.string.home_calendar_untitled) }
            val whenText = eventTime(event, nowMillis)
            row.findViewById<TextView>(R.id.homeCalendarEventTitle).also {
                if (it.text != title) it.text = title
            }
            row.findViewById<TextView>(R.id.homeCalendarEventWhen).also {
                if (it.text != whenText) it.text = whenText
            }
        }
        if (rows.childCount > events.size) {
            rows.removeViews(events.size, rows.childCount - events.size)
        }
        container.visibility = if (events.isEmpty()) View.GONE else View.VISIBLE
    }

    private fun eventTime(event: CalendarEvent, nowMillis: Long): String {
        val dateFlags = DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_ABBREV_MONTH
        if (event.isAllDay) {
            // CalendarContract stores all-day boundaries at UTC midnight with an exclusive end.
            val lastDay = (event.endMillis - 1L).coerceAtLeast(event.startMillis)
            val dates = Formatter().use { formatter ->
                DateUtils.formatDateRange(context, formatter, event.startMillis, lastDay, dateFlags, "UTC")
                    .toString()
            }
            return context.getString(R.string.home_calendar_all_day, dates)
        }

        val start = DateUtils.formatDateTime(context, event.startMillis, dateFlags or DateUtils.FORMAT_SHOW_TIME)
        return if (event.startMillis <= nowMillis && nowMillis < event.endMillis) {
            context.getString(R.string.home_calendar_in_progress, start)
        } else {
            start
        }
    }
}
