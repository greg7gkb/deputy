package io.github.greg7gkb.deputy.calendar

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.provider.CalendarContract
import android.provider.CalendarContract.Calendars
import android.provider.CalendarContract.Events
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

/**
 * A local, on-device test calendar for the emulator, which has no Google account. Created
 * through the sync-adapter URI, which Android allows for ACCOUNT_TYPE_LOCAL calendars.
 * The events have titles on purpose: the point is to show Deputy never reads them.
 */
object TestCalendar {
    private const val ACCOUNT = "deputy-test"
    private const val NAME = "Deputy test calendar"

    suspend fun seed(context: Context): Int = withContext(Dispatchers.IO) {
        val resolver = context.contentResolver
        val calendarId = findCalendar(context) ?: run {
            val values = ContentValues().apply {
                put(Calendars.ACCOUNT_NAME, ACCOUNT)
                put(Calendars.ACCOUNT_TYPE, CalendarContract.ACCOUNT_TYPE_LOCAL)
                put(Calendars.NAME, NAME)
                put(Calendars.CALENDAR_DISPLAY_NAME, NAME)
                put(Calendars.CALENDAR_COLOR, 0xFF2B5A44.toInt())
                put(Calendars.CALENDAR_ACCESS_LEVEL, Calendars.CAL_ACCESS_OWNER)
                put(Calendars.OWNER_ACCOUNT, ACCOUNT)
                put(Calendars.VISIBLE, 1)
                put(Calendars.SYNC_EVENTS, 1)
                put(Calendars.IS_PRIMARY, 1)
                put(Calendars.CALENDAR_TIME_ZONE, ZoneId.systemDefault().id)
            }
            ContentUris.parseId(resolver.insert(asSyncAdapter(Calendars.CONTENT_URI), values)!!)
        }
        // Start clean: remove previously seeded events (but never Deputy's own holds).
        resolver.delete(Events.CONTENT_URI, "${Events.CALENDAR_ID} = ? AND (${Events.DESCRIPTION} IS NULL OR ${Events.DESCRIPTION} != ?)",
            arrayOf(calendarId.toString(), AndroidCalendarAdapter.MARKER))

        val zone = ZoneId.systemDefault()
        var count = 0
        fun add(day: LocalDate, start: LocalTime, end: LocalTime, title: String, free: Boolean = false) {
            resolver.insert(Events.CONTENT_URI, ContentValues().apply {
                put(Events.CALENDAR_ID, calendarId)
                put(Events.TITLE, title)
                put(Events.DTSTART, day.atTime(start).atZone(zone).toInstant().toEpochMilli())
                put(Events.DTEND, day.atTime(end).atZone(zone).toInstant().toEpochMilli())
                put(Events.EVENT_TIMEZONE, zone.id)
                put(Events.AVAILABILITY, if (free) Events.AVAILABILITY_FREE else Events.AVAILABILITY_BUSY)
            })
            count++
        }

        val today = LocalDate.now(zone)
        for (offset in 0..20L) {
            val day = today.plusDays(offset)
            when (day.dayOfWeek) {
                DayOfWeek.SATURDAY, DayOfWeek.SUNDAY -> {
                    if (day.dayOfWeek == DayOfWeek.SATURDAY) add(day, LocalTime.of(10, 0), LocalTime.of(12, 0), "Kids' soccer")
                    continue
                }
                else -> add(day, LocalTime.of(9, 30), LocalTime.of(10, 0), "Standup")
            }
            when (day.dayOfWeek) {
                DayOfWeek.MONDAY -> { add(day, LocalTime.of(13, 0), LocalTime.of(15, 0), "Planning with Priya"); add(day, LocalTime.of(16, 0), LocalTime.of(17, 0), "1:1") }
                DayOfWeek.TUESDAY -> { add(day, LocalTime.of(11, 0), LocalTime.of(12, 30), "Design review"); add(day, LocalTime.of(19, 0), LocalTime.of(20, 0), "Climbing") }
                DayOfWeek.WEDNESDAY -> { add(day, LocalTime.of(12, 0), LocalTime.of(13, 0), "Lunch with Ana"); add(day, LocalTime.of(14, 0), LocalTime.of(16, 0), "Deep work") }
                DayOfWeek.THURSDAY -> { add(day, LocalTime.of(11, 0), LocalTime.of(13, 30), "Dentist (private)"); add(day, LocalTime.of(18, 30), LocalTime.of(21, 0), "Book club") }
                DayOfWeek.FRIDAY -> { add(day, LocalTime.of(9, 0), LocalTime.of(12, 0), "Offsite"); add(day, LocalTime.of(15, 0), LocalTime.of(16, 0), "Focus block", free = true) }
                else -> Unit
            }
        }
        count
    }

    private fun findCalendar(context: Context): Long? =
        context.contentResolver.query(Calendars.CONTENT_URI, arrayOf(Calendars._ID),
            "${Calendars.ACCOUNT_NAME} = ? AND ${Calendars.ACCOUNT_TYPE} = ?",
            arrayOf(ACCOUNT, CalendarContract.ACCOUNT_TYPE_LOCAL), null)?.use { c -> if (c.moveToFirst()) c.getLong(0) else null }

    private fun asSyncAdapter(uri: Uri): Uri = uri.buildUpon()
        .appendQueryParameter(CalendarContract.CALLER_IS_SYNCADAPTER, "true")
        .appendQueryParameter(Calendars.ACCOUNT_NAME, ACCOUNT)
        .appendQueryParameter(Calendars.ACCOUNT_TYPE, CalendarContract.ACCOUNT_TYPE_LOCAL)
        .build()
}
