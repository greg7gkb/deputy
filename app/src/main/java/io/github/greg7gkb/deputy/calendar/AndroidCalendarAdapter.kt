package io.github.greg7gkb.deputy.calendar

import android.Manifest
import android.accounts.Account
import android.content.ContentResolver
import android.os.Bundle
import android.util.Log
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.provider.CalendarContract
import android.provider.CalendarContract.Attendees
import android.provider.CalendarContract.Calendars
import android.provider.CalendarContract.Events
import android.provider.CalendarContract.Instances
import deputy.broker.Artifacts
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
class AndroidCalendarAdapter(
    private val context: Context,
    /** Writes go only to a calendar with this display name (D22). Never falls back to another. */
    private val writeCalendarName: String,
) : CalendarAdapter {
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

    override suspend fun placeHold(slot: Interval, title: String, note: String): String = withContext(Dispatchers.IO) {
        insertEvent(slot, title, Events.AVAILABILITY_TENTATIVE, emptyList(), note)
    }

    override suspend fun releaseHold(adapterRef: String) = withContext(Dispatchers.IO) {
        requirePermission(Manifest.permission.WRITE_CALENDAR)
        // Belt and braces: only ever delete an event carrying Deputy's marker. The provider refuses
        // a selection on a by-id delete, so check the marker with a query first.
        val uri = ContentUris.withAppendedId(Events.CONTENT_URI, adapterRef.toLong())
        val ours = context.contentResolver.query(uri, arrayOf(Events.DESCRIPTION), null, null, null)?.use { c ->
            c.moveToFirst() && c.getString(0)?.startsWith(Artifacts.NOTE_PREFIX) == true
        } ?: false
        check(ours) { "the hold was not found in the calendar" }
        val deleted = context.contentResolver.delete(uri, null, null)
        check(deleted == 1) { "the calendar did not delete the hold" }
        requestUpload()
    }

    override suspend fun createEvent(slot: Interval, title: String, attendees: List<String>): String = withContext(Dispatchers.IO) {
        insertEvent(slot, title, Events.AVAILABILITY_BUSY, attendees, note = null)
    }

    private fun insertEvent(slot: Interval, title: String, availability: Int, attendees: List<String>, note: String?): String {
        requirePermission(Manifest.permission.WRITE_CALENDAR)
        val calendarId = writableCalendarId()?.first
            ?: error("There's no calendar named \"$writeCalendarName\" on this phone. Create it in Google Calendar, let it sync, and try again.")
        val values = ContentValues().apply {
            put(Events.CALENDAR_ID, calendarId)
            put(Events.DTSTART, slot.start.toEpochMilliseconds())
            put(Events.DTEND, slot.end.toEpochMilliseconds())
            put(Events.TITLE, title)
            if (note != null) put(Events.DESCRIPTION, note)
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
        requestUpload()
        return eventId.toString()
    }

    /**
     * Writes through CalendarContract stay on the phone until Android's next calendar sync, which
     * can be many minutes away; found when an invite sat unsynced (dirty) on the Pixel. Ask for an
     * immediate upload for the target calendar's account. Best effort: if Android refuses, the
     * periodic sync still carries it.
     */
    private fun requestUpload() {
        val account = writableCalendar()?.account ?: return
        runCatching {
            ContentResolver.requestSync(account, CalendarContract.AUTHORITY, Bundle().apply {
                putBoolean(ContentResolver.SYNC_EXTRAS_UPLOAD, true)
                putBoolean(ContentResolver.SYNC_EXTRAS_EXPEDITED, true)
                putBoolean(ContentResolver.SYNC_EXTRAS_MANUAL, true)
            })
        }.onFailure { Log.w("DeputyCalendar", "couldn't request a calendar sync: ${it.message}") }
    }

    /** The calendar Deputy writes to, and the account that syncs it (null for device-only calendars). */
    data class Target(val id: Long, val description: String, val account: Account?)

    /**
     * The calendar Deputy may write to: the writable, visible one named [writeCalendarName],
     * preferring an account calendar over a device-only one.
     */
    fun writableCalendar(): Target? {
        if (context.checkSelfPermission(Manifest.permission.READ_CALENDAR) != PackageManager.PERMISSION_GRANTED) return null
        val projection = arrayOf(Calendars._ID, Calendars.ACCOUNT_NAME, Calendars.ACCOUNT_TYPE)
        val selection = "${Calendars.VISIBLE} = 1 AND ${Calendars.CALENDAR_ACCESS_LEVEL} >= ${Calendars.CAL_ACCESS_CONTRIBUTOR} AND ${Calendars.CALENDAR_DISPLAY_NAME} = ?"
        val found = mutableListOf<Triple<Long, String, String>>()
        context.contentResolver.query(Calendars.CONTENT_URI, projection, selection, arrayOf(writeCalendarName), null)?.use { c ->
            while (c.moveToNext()) found += Triple(c.getLong(0), c.getString(1).orEmpty(), c.getString(2).orEmpty())
        }
        val (id, name, type) = found.sortedBy { it.third == CalendarContract.ACCOUNT_TYPE_LOCAL }.firstOrNull() ?: return null
        val local = type == CalendarContract.ACCOUNT_TYPE_LOCAL
        return Target(id, "$writeCalendarName (" + (if (local) "on this phone only" else name) + ")", if (local) null else Account(name, type))
    }

    /** Kept for callers that only need the id and a description. */
    fun writableCalendarId(): Pair<Long, String>? = writableCalendar()?.let { it.id to it.description }

    private fun requirePermission(permission: String) {
        if (context.checkSelfPermission(permission) != PackageManager.PERMISSION_GRANTED) {
            throw SecurityException("Deputy doesn't have calendar permission yet")
        }
    }
}
