package io.github.greg7gkb.deputy.calendar

import android.Manifest
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.provider.CalendarContract.Attendees
import android.provider.CalendarContract.Calendars
import android.provider.CalendarContract.Events
import android.provider.CalendarContract.Instances
import deputy.broker.CalendarAdapter
import deputy.broker.Interval
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.time.Instant

/**
 * Tier 1: the on-device calendar through CalendarContract, which Google Calendar syncs into.
 *
 * Minimisation by construction: [readBusy] projects only times and status columns. Titles,
 * descriptions, locations and attendees are never read, so they cannot leak however the
 * broker or a future planner misbehaves (docs/decisions.md D9).
 */
class AndroidCalendarAdapter(private val context: Context) : CalendarAdapter {
    override val source = "Calendar on this phone"

    override suspend fun readBusy(window: Interval): List<Interval> = withContext(Dispatchers.IO) {
        requirePermission(Manifest.permission.READ_CALENDAR)
        val uri = Instances.CONTENT_URI.buildUpon().also {
            ContentUris.appendId(it, window.start.toEpochMilliseconds())
            ContentUris.appendId(it, window.end.toEpochMilliseconds())
        }.build()
        val projection = arrayOf(Instances.BEGIN, Instances.END, Instances.AVAILABILITY, Instances.STATUS, Instances.SELF_ATTENDEE_STATUS)
        val busy = mutableListOf<Interval>()
        context.contentResolver.query(uri, projection, "${Instances.VISIBLE} = 1", null, "${Instances.BEGIN} ASC")?.use { c ->
            while (c.moveToNext()) {
                val begin = c.getLong(0)
                val end = c.getLong(1)
                val availability = c.getInt(2)
                val status = if (c.isNull(3)) Events.STATUS_CONFIRMED else c.getInt(3)
                val self = c.getInt(4)
                if (end <= begin) continue
                if (availability == Events.AVAILABILITY_FREE) continue
                if (status == Events.STATUS_CANCELED) continue
                if (self == Attendees.ATTENDEE_STATUS_DECLINED) continue
                busy += Interval(Instant.fromEpochMilliseconds(begin), Instant.fromEpochMilliseconds(end))
            }
        }
        busy
    }

    override suspend fun placeHold(slot: Interval, title: String): String = withContext(Dispatchers.IO) {
        insertEvent(slot, title, Events.AVAILABILITY_TENTATIVE, emptyList())
    }

    override suspend fun releaseHold(adapterRef: String) = withContext(Dispatchers.IO) {
        requirePermission(Manifest.permission.WRITE_CALENDAR)
        // Belt and braces: only ever delete an event carrying Deputy's marker. The provider refuses
        // a selection on a by-id delete, so check the marker with a query first.
        val uri = ContentUris.withAppendedId(Events.CONTENT_URI, adapterRef.toLong())
        val ours = context.contentResolver.query(uri, arrayOf(Events.DESCRIPTION), null, null, null)?.use { c ->
            c.moveToFirst() && c.getString(0) == MARKER
        } ?: false
        check(ours) { "the hold was not found in the calendar" }
        val deleted = context.contentResolver.delete(uri, null, null)
        check(deleted == 1) { "the calendar did not delete the hold" }
    }

    override suspend fun createEvent(slot: Interval, title: String, attendees: List<String>): String = withContext(Dispatchers.IO) {
        insertEvent(slot, title, Events.AVAILABILITY_BUSY, attendees)
    }

    private fun insertEvent(slot: Interval, title: String, availability: Int, attendees: List<String>): String {
        requirePermission(Manifest.permission.WRITE_CALENDAR)
        val calendarId = writableCalendarId() ?: error("no writable calendar on this phone")
        val values = ContentValues().apply {
            put(Events.CALENDAR_ID, calendarId)
            put(Events.DTSTART, slot.start.toEpochMilliseconds())
            put(Events.DTEND, slot.end.toEpochMilliseconds())
            put(Events.TITLE, title)
            put(Events.DESCRIPTION, MARKER)
            put(Events.AVAILABILITY, availability)
            put(Events.EVENT_TIMEZONE, java.util.TimeZone.getDefault().id)
            if (attendees.isNotEmpty()) put(Events.HAS_ATTENDEE_DATA, 1)
        }
        val uri = context.contentResolver.insert(Events.CONTENT_URI, values) ?: error("the calendar refused the event")
        val eventId = ContentUris.parseId(uri)
        attendees.forEach { email ->
            context.contentResolver.insert(Attendees.CONTENT_URI, ContentValues().apply {
                put(Attendees.EVENT_ID, eventId)
                put(Attendees.ATTENDEE_EMAIL, email)
                put(Attendees.ATTENDEE_RELATIONSHIP, Attendees.RELATIONSHIP_ATTENDEE)
                put(Attendees.ATTENDEE_TYPE, Attendees.TYPE_REQUIRED)
                put(Attendees.ATTENDEE_STATUS, Attendees.ATTENDEE_STATUS_INVITED)
            })
        }
        return eventId.toString()
    }

    /** The primary calendar the user can write to, else the first writable visible one. */
    private fun writableCalendarId(): Long? {
        val projection = arrayOf(Calendars._ID, Calendars.IS_PRIMARY)
        val selection = "${Calendars.VISIBLE} = 1 AND ${Calendars.CALENDAR_ACCESS_LEVEL} >= ${Calendars.CAL_ACCESS_CONTRIBUTOR}"
        context.contentResolver.query(Calendars.CONTENT_URI, projection, selection, null, null)?.use { c ->
            var first: Long? = null
            while (c.moveToNext()) {
                if (first == null) first = c.getLong(0)
                if (!c.isNull(1) && c.getInt(1) == 1) return c.getLong(0)
            }
            return first
        }
        return null
    }

    private fun requirePermission(permission: String) {
        if (context.checkSelfPermission(permission) != PackageManager.PERMISSION_GRANTED) {
            throw SecurityException("Deputy doesn't have calendar permission yet")
        }
    }

    companion object {
        /** Written into every event Deputy creates, so it can only ever delete its own. */
        const val MARKER = "Placed by Deputy. Undo it from Deputy's Activity."
    }
}
