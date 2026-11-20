package com.stasisstate.slatetv.calendar

import android.provider.CalendarContract.Calendars
import com.stasisstate.slatetv.settings.LauncherSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class GoogleCalendarSelectionTest {
    @Test
    fun calendarDisplayIsOffByDefault() {
        assertFalse(LauncherSettings().showCalendarEvents)
    }

    @Test
    fun selectsOnlyVisibleSyncedReadableCalendarsFromTheSingleAccount() {
        val calendars = sequenceOf(
            calendar(1),
            calendar(2, isVisible = false),
            calendar(3, syncsEvents = false),
            calendar(4, accessLevel = Calendars.CAL_ACCESS_FREEBUSY),
            calendar(5, accessLevel = Calendars.CAL_ACCESS_OWNER),
            calendar(6, accountName = " "),
        )

        assertEquals(GoogleCalendarSelection.Eligible(ACCOUNT, setOf(1L, 5L)), selectGoogleCalendars(calendars))
    }

    @Test
    fun suppressesEventsWhenASecondAccountHasHiddenUnsyncedOrFreeBusyCalendars() {
        val secondAccount = calendar(2, accountName = "other@example.invalid")
        for (second in listOf(
            secondAccount.copy(isVisible = false),
            secondAccount.copy(syncsEvents = false),
            secondAccount.copy(accessLevel = Calendars.CAL_ACCESS_FREEBUSY),
        )) {
            assertEquals(GoogleCalendarSelection.MultipleAccounts, selectGoogleCalendars(sequenceOf(calendar(1), second)))
        }
    }

    @Test
    fun reportsNoEligibleCalendarsWhenOnlyBlankAccountsOrFreeBusyCalendarsExist() {
        val calendars = sequenceOf(
            calendar(1, accountName = null),
            calendar(2, accountName = "  "),
            calendar(3, accessLevel = Calendars.CAL_ACCESS_FREEBUSY),
        )

        assertEquals(GoogleCalendarSelection.NoEligibleCalendars, selectGoogleCalendars(calendars))
    }

    private fun calendar(
        id: Long,
        accountName: String? = ACCOUNT,
        isVisible: Boolean = true,
        syncsEvents: Boolean = true,
        accessLevel: Int = Calendars.CAL_ACCESS_READ,
    ) = GoogleCalendar(id, accountName, isVisible, syncsEvents, accessLevel)

    private companion object {
        const val ACCOUNT = "calendar@example.invalid"
    }
}
