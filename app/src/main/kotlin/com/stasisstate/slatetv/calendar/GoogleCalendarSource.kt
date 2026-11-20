package com.stasisstate.slatetv.calendar

import android.Manifest
import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.database.sqlite.SQLiteException
import android.os.CancellationSignal
import android.os.OperationCanceledException
import android.provider.CalendarContract
import android.provider.CalendarContract.Attendees
import android.provider.CalendarContract.Calendars
import android.provider.CalendarContract.Events
import android.provider.CalendarContract.Instances
import androidx.core.content.ContextCompat
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import kotlin.coroutines.resume
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext

internal sealed interface CalendarAgenda {
    data object Unavailable : CalendarAgenda
    data object PermissionRequired : CalendarAgenda
    data object NoSyncedCalendars : CalendarAgenda
    data object MultipleAccounts : CalendarAgenda
    data class Ready(val events: List<CalendarEvent>) : CalendarAgenda
}

internal data class CalendarEvent(
    val title: String,
    val startMillis: Long,
    val endMillis: Long,
    val isAllDay: Boolean,
)

internal class GoogleCalendarSource(context: Context) {
    private val appContext = context.applicationContext
    private val resolver = appContext.contentResolver

    fun hasReadPermission(): Boolean = ContextCompat.checkSelfPermission(
        appContext, Manifest.permission.READ_CALENDAR,
    ) == PackageManager.PERMISSION_GRANTED

    fun isProviderAvailable(): Boolean =
        appContext.packageManager.resolveContentProvider(CalendarContract.AUTHORITY, 0) != null

    suspend fun readUpcoming(): CalendarAgenda = withContext(Dispatchers.IO) {
        suspendCancellableCoroutine { continuation ->
            val signal = CancellationSignal()
            continuation.invokeOnCancellation { signal.cancel() }
            val agenda = try {
                readAgenda(signal)
            } catch (_: SecurityException) {
                if (hasReadPermission()) CalendarAgenda.Unavailable else CalendarAgenda.PermissionRequired
            } catch (_: IllegalArgumentException) {
                CalendarAgenda.Unavailable
            } catch (_: SQLiteException) {
                CalendarAgenda.Unavailable
            } catch (_: OperationCanceledException) {
                CalendarAgenda.Unavailable
            }
            continuation.resume(agenda)
        }
    }

    private fun readAgenda(signal: CancellationSignal): CalendarAgenda {
        if (!isProviderAvailable()) return CalendarAgenda.Unavailable
        if (!hasReadPermission()) return CalendarAgenda.PermissionRequired

        // The provider cannot identify the active Google TV profile, so count all Google accounts
        // represented in it before selecting the visible, synced calendars to display.
        val calendars = resolver.query(
            Calendars.CONTENT_URI,
            arrayOf(
                Calendars._ID, Calendars.ACCOUNT_NAME, Calendars.VISIBLE,
                Calendars.SYNC_EVENTS, Calendars.CALENDAR_ACCESS_LEVEL,
            ),
            "${Calendars.ACCOUNT_TYPE} = ? AND ${Calendars.DELETED} = 0",
            arrayOf(GOOGLE_ACCOUNT_TYPE),
            null,
            signal,
        ) ?: return CalendarAgenda.Unavailable

        val selection = calendars.use { cursor ->
            selectGoogleCalendars(sequence {
                while (cursor.moveToNext()) {
                    signal.throwIfCanceled()
                    yield(
                        GoogleCalendar(
                            id = cursor.getLong(0),
                            accountName = cursor.getString(1),
                            isVisible = cursor.getInt(2) == 1,
                            syncsEvents = cursor.getInt(3) == 1,
                            accessLevel = cursor.getInt(4),
                        ),
                    )
                }
            })
        }
        when (selection) {
            GoogleCalendarSelection.NoEligibleCalendars -> return CalendarAgenda.NoSyncedCalendars
            GoogleCalendarSelection.MultipleAccounts -> return CalendarAgenda.MultipleAccounts
            is GoogleCalendarSelection.Eligible -> Unit
        }

        val window = AgendaWindow(Instant.now(), ZoneId.systemDefault())
        val uri = Instances.CONTENT_URI.buildUpon().also { builder ->
            ContentUris.appendId(builder, window.queryStartMillis)
            ContentUris.appendId(builder, window.queryEndMillis)
        }.build()
        val instances = resolver.query(
            uri,
            arrayOf(Events.CALENDAR_ID, Events.TITLE, Instances.BEGIN, Instances.END, Events.ALL_DAY),
            EVENT_SELECTION,
            arrayOf(
                GOOGLE_ACCOUNT_TYPE,
                selection.accountName,
                Calendars.CAL_ACCESS_READ.toString(),
                Events.STATUS_CANCELED.toString(),
                Attendees.ATTENDEE_STATUS_DECLINED.toString(),
                Events.ACCESS_DEFAULT.toString(),
                Events.ACCESS_PUBLIC.toString(),
            ),
            "${Instances.BEGIN} ASC, ${Instances.EVENT_ID} ASC",
            signal,
        ) ?: return CalendarAgenda.Unavailable

        val upcoming = instances.use { cursor ->
            window.selectUpcoming(sequence {
                while (cursor.moveToNext()) {
                    signal.throwIfCanceled()
                    // Instances exposes the event's deleted flag. The calendar IDs exclude
                    // calendars awaiting deletion and calendars with free/busy access only.
                    if (cursor.getLong(0) !in selection.calendarIds || cursor.isNull(2) || cursor.isNull(3)) continue
                    yield(
                        CalendarEvent(
                            title = cursor.getString(1)?.trim().orEmpty(),
                            startMillis = cursor.getLong(2),
                            endMillis = cursor.getLong(3),
                            isAllDay = cursor.getInt(4) == 1,
                        ),
                    )
                }
            })
        }
        return if (hasReadPermission()) CalendarAgenda.Ready(upcoming)
        else CalendarAgenda.PermissionRequired
    }

    private companion object {
        const val GOOGLE_ACCOUNT_TYPE = "com.google"
        val EVENT_SELECTION = """
            ${Calendars.ACCOUNT_TYPE} = ? AND ${Calendars.ACCOUNT_NAME} = ?
            AND ${Calendars.VISIBLE} = 1 AND ${Calendars.SYNC_EVENTS} = 1
            AND ${Calendars.CALENDAR_ACCESS_LEVEL} >= ? AND ${Events.DELETED} = 0
            AND (${Events.STATUS} IS NULL OR ${Events.STATUS} != ?)
            AND (${Events.SELF_ATTENDEE_STATUS} IS NULL OR ${Events.SELF_ATTENDEE_STATUS} != ?)
            AND ${Events.ACCESS_LEVEL} IN (?, ?)
        """.trimIndent()
    }
}

internal data class GoogleCalendar(
    val id: Long,
    val accountName: String?,
    val isVisible: Boolean,
    val syncsEvents: Boolean,
    val accessLevel: Int,
)

internal sealed interface GoogleCalendarSelection {
    data object NoEligibleCalendars : GoogleCalendarSelection
    data object MultipleAccounts : GoogleCalendarSelection
    data class Eligible(val accountName: String, val calendarIds: Set<Long>) : GoogleCalendarSelection
}

internal fun selectGoogleCalendars(calendars: Sequence<GoogleCalendar>): GoogleCalendarSelection {
    var accountName: String? = null
    val calendarIds = mutableSetOf<Long>()
    for (calendar in calendars) {
        val name = calendar.accountName?.takeIf(String::isNotBlank) ?: continue
        if (accountName != null && name != accountName) return GoogleCalendarSelection.MultipleAccounts
        accountName = name
        if (calendar.isVisible && calendar.syncsEvents && calendar.accessLevel >= Calendars.CAL_ACCESS_READ) {
            calendarIds.add(calendar.id)
        }
    }
    return if (accountName != null && calendarIds.isNotEmpty()) {
        GoogleCalendarSelection.Eligible(accountName, calendarIds)
    } else {
        GoogleCalendarSelection.NoEligibleCalendars
    }
}

internal class AgendaWindow(now: Instant, private val zone: ZoneId) {
    private val startMillis = now.toEpochMilli()
    private val localStart = now.atZone(zone)
    private val localEnd = localStart.plusDays(7)
    private val endMillis = localEnd.toInstant().toEpochMilli()

    // The provider represents all-day event dates as UTC midnight, even when the TV is in another zone.
    val queryStartMillis: Long = minOf(startMillis, utcMidnightMillis(localStart.toLocalDate()))
    // Recurrence expansion uses an exclusive end, including for all-day events near the horizon.
    val queryEndMillis: Long = maxOf(endMillis, utcMidnightMillis(localEnd.toLocalDate().plusDays(1)))

    fun selectUpcoming(events: Sequence<CalendarEvent>): List<CalendarEvent> {
        val upcoming = ArrayList<Pair<Long, CalendarEvent>>(MAX_EVENTS + 1)
        for (event in events) {
            val start = eligibleStartMillis(event) ?: continue
            if (upcoming.size == MAX_EVENTS && start >= upcoming.last().first) continue
            upcoming.add(start to event)
            upcoming.sortBy { it.first }
            if (upcoming.size > MAX_EVENTS) upcoming.removeAt(upcoming.lastIndex)
        }
        return upcoming.map { it.second }
    }

    fun eligibleStartMillis(event: CalendarEvent): Long? {
        val start = localMillis(event.startMillis, event.isAllDay)
        val end = localMillis(event.endMillis, event.isAllDay)
        return start.takeIf { end >= start && (start >= startMillis || end > startMillis) && start < endMillis }
    }

    private fun localMillis(millis: Long, isAllDay: Boolean): Long = if (isAllDay) {
        Instant.ofEpochMilli(millis).atOffset(ZoneOffset.UTC).toLocalDate().atStartOfDay(zone)
            .toInstant().toEpochMilli()
    } else millis

    private fun utcMidnightMillis(date: LocalDate): Long =
        date.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()

    private companion object {
        const val MAX_EVENTS = 3
    }
}
