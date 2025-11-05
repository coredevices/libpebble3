package io.rebble.libpebblecommon.calendar

import android.Manifest
import android.content.ContentResolver
import android.content.ContentUris
import android.content.pm.PackageManager
import android.database.ContentObserver
import android.database.Cursor
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.CalendarContract
import co.touchlab.kermit.Logger
import io.rebble.libpebblecommon.connection.AppContext
import io.rebble.libpebblecommon.database.entity.CalendarEntity
import io.rebble.libpebblecommon.di.LibPebbleCoroutineScope
import io.rebble.libpebblecommon.util.PrivateLogger
import io.rebble.libpebblecommon.util.obfuscate
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch
import kotlin.time.Instant


private val calendarUri: Uri = CalendarContract.Calendars.CONTENT_URI
private val calendarProjection = arrayOf(
    CalendarContract.Calendars._ID,
    CalendarContract.Calendars.ACCOUNT_NAME,
    CalendarContract.Calendars.CALENDAR_DISPLAY_NAME,
    CalendarContract.Calendars.OWNER_ACCOUNT,
    CalendarContract.Calendars.CALENDAR_COLOR
)

private val instanceUri: Uri = CalendarContract.Instances.CONTENT_URI
private val instanceProjection = arrayOf(
    CalendarContract.Instances._ID,
    CalendarContract.Instances.EVENT_ID,
    CalendarContract.Instances.CALENDAR_ID,
    CalendarContract.Instances.TITLE,
    CalendarContract.Instances.DESCRIPTION,
    CalendarContract.Instances.BEGIN,
    CalendarContract.Instances.END,
    CalendarContract.Instances.ALL_DAY,
    CalendarContract.Instances.EVENT_LOCATION,
    CalendarContract.Instances.AVAILABILITY,
    CalendarContract.Instances.STATUS,
    CalendarContract.Instances.RRULE,
    CalendarContract.Instances.RDATE
)

private val eventUri: Uri = CalendarContract.Events.CONTENT_URI
private val eventProjection = arrayOf(
    CalendarContract.Events._ID,
    CalendarContract.Events.DTSTART,
    CalendarContract.Events.DTEND,
    CalendarContract.Events.CALENDAR_ID,
    CalendarContract.Events.TITLE,
    CalendarContract.Events.DESCRIPTION,
    CalendarContract.Events.ALL_DAY,
    CalendarContract.Events.EVENT_LOCATION,
    CalendarContract.Events.AVAILABILITY,
    CalendarContract.Events.STATUS,
    CalendarContract.Events.RRULE,
    CalendarContract.Events.RDATE
)

private val attendeeUri: Uri = CalendarContract.Attendees.CONTENT_URI
private val attendeeProjection = arrayOf(
    CalendarContract.Attendees._ID,
    CalendarContract.Attendees.EVENT_ID,
    CalendarContract.Attendees.ATTENDEE_NAME,
    CalendarContract.Attendees.ATTENDEE_EMAIL,
    CalendarContract.Attendees.ATTENDEE_TYPE,
    CalendarContract.Attendees.ATTENDEE_STATUS,
    CalendarContract.Attendees.ATTENDEE_RELATIONSHIP,
)

private val reminderUri: Uri = CalendarContract.Reminders.CONTENT_URI
private val reminderProjection = arrayOf(
    CalendarContract.Reminders._ID,
    CalendarContract.Reminders.EVENT_ID,
    CalendarContract.Reminders.MINUTES,
    CalendarContract.Reminders.METHOD,
)

private fun Cursor.getNullableColumnIndex(columnName: String): Int? {
    val index = getColumnIndex(columnName)
    return if (index == -1) {
        null
    } else {
        index
    }
}

private fun CharSequence?.notEmpty(): Boolean = this?.isNotEmpty() ?: false

private fun resolveCalendarInstance(
    contentResolver: ContentResolver,
    cursor: Cursor,
    ownerEmail: String
): CalendarEvent? {
    val id = cursor.getNullableColumnIndex(CalendarContract.Instances._ID)
        ?.let { cursor.getLong(it) } ?: run {
            logger.w("Calendar instance has no ID")
            return null
    }
    val eventId = cursor.getNullableColumnIndex(CalendarContract.Instances.EVENT_ID)
        ?.let { cursor.getLong(it) } ?: run {
            logger.w("Calendar instance has no event ID")
            return null
    }
    val calendarId = cursor.getNullableColumnIndex(CalendarContract.Instances.CALENDAR_ID)
        ?.let { cursor.getLong(it) } ?: run {
            logger.w("Calendar instance has no calendar ID")
            return null
    }
    val title = cursor.getNullableColumnIndex(CalendarContract.Instances.TITLE)
        ?.let { cursor.getString(it) } ?: "Untitled event"
    val description = cursor.getNullableColumnIndex(CalendarContract.Instances.DESCRIPTION)
        ?.let { cursor.getString(it) } ?: ""
    val start = cursor.getNullableColumnIndex(CalendarContract.Instances.BEGIN)
        ?.let { cursor.getLong(it) } ?: run {
        logger.w("Calendar instance has no BEGIN")
        return null
    }
    val end = cursor.getNullableColumnIndex(CalendarContract.Instances.END)
        ?.let { cursor.getLong(it) } ?: run {
        logger.w("Calendar instance has no END")
        return null
    }
    val allDay = cursor.getNullableColumnIndex(CalendarContract.Instances.ALL_DAY)
        ?.let { cursor.getInt(it) } ?: false
    val location = cursor.getNullableColumnIndex(CalendarContract.Instances.EVENT_LOCATION)
        ?.let { cursor.getString(it) }
    val availability = cursor.getNullableColumnIndex(CalendarContract.Instances.AVAILABILITY)
        ?.let { cursor.getInt(it) } ?: run {
        logger.w("Calendar instance has no AVAILABILITY")
        return null
    }
    val status = cursor.getNullableColumnIndex(CalendarContract.Instances.STATUS)
        ?.let { cursor.getInt(it) } ?: run {
        logger.w("Calendar instance has no STATUS")
        return null
    }
    val recurrenceRule = cursor.getNullableColumnIndex(CalendarContract.Instances.RRULE)
        ?.let { cursor.getString(it) }
    val recurrenceDate = cursor.getNullableColumnIndex(CalendarContract.Instances.RDATE)
        ?.let { cursor.getString(it) }
    val recurrenceOriginalId = cursor.getNullableColumnIndex(CalendarContract.Instances.ORIGINAL_ID)
        ?.let { cursor.getString(it) }
    val recurrenceOriginalSyncId =
        cursor.getNullableColumnIndex(CalendarContract.Instances.ORIGINAL_SYNC_ID)
            ?.let { cursor.getString(it) }

    // TODO add real recurrence parsing
    // https://android.googlesource.com/platform/packages/providers/CalendarProvider/+/8d2ed3bf1ef3525c3a6eb17b57f07b0af35ef4d0/src/com/android/providers/calendar/CalendarProvider2.java#1478
    val recurs =
        recurrenceRule.notEmpty() || recurrenceDate.notEmpty() || recurrenceOriginalId.notEmpty() || recurrenceOriginalSyncId.notEmpty()
    return CalendarEvent(
        id = id.toString(),
        calendarId = calendarId.toString(),
        title = title,
        description = description,
        location = location,
        startTime = Instant.fromEpochMilliseconds(start),
        endTime = Instant.fromEpochMilliseconds(end),
        allDay = allDay != 0,
        attendees = resolveAttendees(eventId, ownerEmail, contentResolver),
        recurs = recurs,
        reminders = resolveReminders(eventId, contentResolver),
        availability = when (availability) {
            CalendarContract.Instances.AVAILABILITY_BUSY -> CalendarEvent.Availability.Busy
            CalendarContract.Instances.AVAILABILITY_FREE -> CalendarEvent.Availability.Free
            CalendarContract.Instances.AVAILABILITY_TENTATIVE -> CalendarEvent.Availability.Tentative
            else -> CalendarEvent.Availability.Unavailable
        },
        status = when (status) {
            CalendarContract.Instances.STATUS_CONFIRMED -> CalendarEvent.Status.Confirmed
            CalendarContract.Instances.STATUS_CANCELED -> CalendarEvent.Status.Cancelled
            CalendarContract.Instances.STATUS_TENTATIVE -> CalendarEvent.Status.Tentative
            else -> CalendarEvent.Status.None
        },
        baseEventId = eventId.toString()
    )
}

//private fun resolveCalendarEvent(contentResolver: ContentResolver, cursor: Cursor, ownerEmail: String): CalendarEvent? {
//    val id = cursor.getNullableColumnIndex(CalendarContract.Events._ID)
//        ?.let { cursor.getLong(it) } ?: return null
//    val eventId = id
//    val calendarId = cursor.getNullableColumnIndex(CalendarContract.Events.CALENDAR_ID)
//        ?.let { cursor.getLong(it) } ?: return null
//    val title = cursor.getNullableColumnIndex(CalendarContract.Events.TITLE)
//        ?.let { cursor.getString(it) } ?: "Untitled event"
//    val description = cursor.getNullableColumnIndex(CalendarContract.Events.DESCRIPTION)
//        ?.let { cursor.getString(it) } ?: ""
//    val allDay = cursor.getNullableColumnIndex(CalendarContract.Events.ALL_DAY)
//        ?.let { cursor.getInt(it) } ?: false
//    val location = cursor.getNullableColumnIndex(CalendarContract.Events.EVENT_LOCATION)
//        ?.let { cursor.getString(it) }
//    val availability = cursor.getNullableColumnIndex(CalendarContract.Events.AVAILABILITY)
//        ?.let { cursor.getInt(it) } ?: return null
//    val status = cursor.getNullableColumnIndex(CalendarContract.Events.STATUS)
//        ?.let { cursor.getInt(it) } ?: return null
//    val recurrenceRule = cursor.getNullableColumnIndex(CalendarContract.Events.RRULE)
//        ?.let { cursor.getString(it) }
//    val start = cursor.getNullableColumnIndex(CalendarContract.Events.DTSTART)
//        ?.let { cursor.getLong(it) } ?: return null
//    val end = cursor.getNullableColumnIndex(CalendarContract.Events.DTEND)
//        ?.let { cursor.getLong(it) } ?: return null
//
//    return CalendarEvent(
//        id = id,
//        calendarId = calendarId,
//        title = title,
//        description = description,
//        location = location,
//        startTime = Instant.fromEpochMilliseconds(start),
//        endTime = Instant.fromEpochMilliseconds(end),
//        allDay = allDay != 0,
//        attendees = resolveAttendees(eventId, ownerEmail, contentResolver),
//        recurrenceRule = null, // TODO //recurrenceRule?.let { resolveRecurrenceRule(it, Instant.fromEpochMilliseconds(start)) },
//        reminders = resolveReminders(eventId, contentResolver),
//        availability = when (availability) {
//            CalendarContract.Instances.AVAILABILITY_BUSY -> CalendarEvent.Availability.Busy
//            CalendarContract.Instances.AVAILABILITY_FREE -> CalendarEvent.Availability.Free
//            CalendarContract.Instances.AVAILABILITY_TENTATIVE -> CalendarEvent.Availability.Tentative
//            else -> CalendarEvent.Availability.Unavailable
//        },
//        status = when (status) {
//            CalendarContract.Instances.STATUS_CONFIRMED -> CalendarEvent.Status.Confirmed
//            CalendarContract.Instances.STATUS_CANCELED -> CalendarEvent.Status.Cancelled
//            CalendarContract.Instances.STATUS_TENTATIVE -> CalendarEvent.Status.Tentative
//            else -> CalendarEvent.Status.None
//        },
//        baseEventId = eventId
//    )
//}

private fun resolveAttendees(
    eventId: Long,
    ownerEmail: String,
    contentResolver: ContentResolver
): List<EventAttendee> {
    return contentResolver.query(
        attendeeUri,
        attendeeProjection,
        "${CalendarContract.Attendees.EVENT_ID} = ?",
        arrayOf(eventId.toString()),
        null
    )?.use { cursor ->
        return@use generateSequence {
            if (cursor.moveToNext()) {
                val id = cursor.getNullableColumnIndex(CalendarContract.Attendees._ID)
                    ?.let { cursor.getLong(it) } ?: return@generateSequence null
                val eventId = cursor.getNullableColumnIndex(CalendarContract.Attendees.EVENT_ID)
                    ?.let { cursor.getLong(it) } ?: return@generateSequence null
                val name = cursor.getNullableColumnIndex(CalendarContract.Attendees.ATTENDEE_NAME)
                    ?.let { cursor.getString(it) } ?: return@generateSequence null
                val email = cursor.getNullableColumnIndex(CalendarContract.Attendees.ATTENDEE_EMAIL)
                    ?.let { cursor.getString(it) } ?: return@generateSequence null
                val type = cursor.getNullableColumnIndex(CalendarContract.Attendees.ATTENDEE_TYPE)
                    ?.let { cursor.getInt(it) } ?: return@generateSequence null
                val status =
                    cursor.getNullableColumnIndex(CalendarContract.Attendees.ATTENDEE_STATUS)
                        ?.let { cursor.getInt(it) } ?: return@generateSequence null
                val relationship =
                    cursor.getNullableColumnIndex(CalendarContract.Attendees.ATTENDEE_RELATIONSHIP)
                        ?.let { cursor.getInt(it) } ?: return@generateSequence null

                EventAttendee(
                    name = name,
                    email = email,
                    role = when (type) {
                        CalendarContract.Attendees.TYPE_REQUIRED -> EventAttendee.Role.Required
                        CalendarContract.Attendees.TYPE_OPTIONAL -> EventAttendee.Role.Optional
                        CalendarContract.Attendees.TYPE_RESOURCE -> EventAttendee.Role.Resource
                        else -> EventAttendee.Role.None
                    },
                    isOrganizer = relationship == CalendarContract.Attendees.RELATIONSHIP_ORGANIZER,
                    isCurrentUser = email == ownerEmail,
                    attendanceStatus = when (status) {
                        CalendarContract.Attendees.ATTENDEE_STATUS_ACCEPTED -> EventAttendee.AttendanceStatus.Accepted
                        CalendarContract.Attendees.ATTENDEE_STATUS_DECLINED -> EventAttendee.AttendanceStatus.Declined
                        CalendarContract.Attendees.ATTENDEE_STATUS_INVITED -> EventAttendee.AttendanceStatus.Invited
                        CalendarContract.Attendees.ATTENDEE_STATUS_TENTATIVE -> EventAttendee.AttendanceStatus.Tentative
                        else -> EventAttendee.AttendanceStatus.None
                    }
                )
            } else {
                null
            }
        }.toList()
    } ?: listOf()
}

private fun resolveReminders(eventId: Long, contentResolver: ContentResolver): List<EventReminder> {
    return contentResolver.query(
        reminderUri,
        reminderProjection,
        "${CalendarContract.Reminders.EVENT_ID} = ? AND ${CalendarContract.Reminders.METHOD} IN (${CalendarContract.Reminders.METHOD_ALERT}, ${CalendarContract.Reminders.METHOD_DEFAULT})",
        arrayOf(eventId.toString()),
        null
    )?.use { cursor ->
        return@use generateSequence {
            if (cursor.moveToNext()) {
                val id = cursor.getNullableColumnIndex(CalendarContract.Reminders._ID)
                    ?.let { cursor.getLong(it) } ?: return@generateSequence null
                val eventId = cursor.getNullableColumnIndex(CalendarContract.Reminders.EVENT_ID)
                    ?.let { cursor.getLong(it) } ?: return@generateSequence null
                val minutes = cursor.getNullableColumnIndex(CalendarContract.Reminders.MINUTES)
                    ?.let { cursor.getInt(it) } ?: return@generateSequence null
                val method = cursor.getNullableColumnIndex(CalendarContract.Reminders.METHOD)
                    ?.let { cursor.getInt(it) } ?: return@generateSequence null
                EventReminder(
                    minutesBefore = minutes,
                )
            } else {
                null
            }
        }.toList()
    } ?: listOf()
}

private val logger = Logger.withTag("AndroidSystemCalendar")

class AndroidSystemCalendar(
    private val contentResolver: ContentResolver,
    private val libPebbleCoroutineScope: LibPebbleCoroutineScope,
    private val appContext: AppContext,
    private val privateLogger: PrivateLogger,
) : SystemCalendar {
    override suspend fun getCalendars(): List<CalendarEntity> {
        return try {
            return contentResolver.query(calendarUri, calendarProjection, null, null, null)
                ?.use { cursor ->
                    return@use generateSequence {
                        if (cursor.moveToNext()) {
                            val id = cursor.getNullableColumnIndex(CalendarContract.Calendars._ID)
                                ?.let { cursor.getLong(it) }
                            if (id == null) {
                                logger.w("Calendar has no ID")
                                return@generateSequence null
                            }
                            val accountName =
                                cursor.getNullableColumnIndex(CalendarContract.Calendars.ACCOUNT_NAME)
                                    ?.let { cursor.getString(it) }
                            if (accountName == null) {
                                logger.w("Calendar has no accountName")
                                return@generateSequence null
                            }
                            val displayName =
                                cursor.getNullableColumnIndex(CalendarContract.Calendars.CALENDAR_DISPLAY_NAME)
                                    ?.let { cursor.getString(it) }
                            if (displayName == null) {
                                logger.w("Calendar has no displayName")
                                return@generateSequence null
                            }
                            val ownerAccount =
                                cursor.getNullableColumnIndex(CalendarContract.Calendars.OWNER_ACCOUNT)
                                    ?.let { cursor.getString(it) }
                            if (ownerAccount == null) {
                                logger.w("Calendar has no ownerAccount")
                            }
                            val color =
                                cursor.getNullableColumnIndex(CalendarContract.Calendars.CALENDAR_COLOR)
                                    ?.let { cursor.getInt(it) }
                            if (color == null) {
                                logger.w("Calendar has no color")
                                return@generateSequence null
                            }

                            CalendarEntity(
                                platformId = id.toString(),
                                name = displayName,
                                ownerName = accountName,
                                ownerId = ownerAccount ?: "unknown",
                                color = color,
                                enabled = true,
                            )
                        } else {
                            null
                        }
                    }.toList()
                } ?: emptyList()
        } catch (e: SecurityException) {
            logger.e(e) { "Error syncing calendars"}
            emptyList()
        }
    }

    override suspend fun getCalendarEvents(
        calendar: CalendarEntity,
        startDate: Instant,
        endDate: Instant,
    ): List<CalendarEvent> {
        val uriBuilder = instanceUri.buildUpon()
        ContentUris.appendId(uriBuilder, startDate.toEpochMilliseconds())
        ContentUris.appendId(uriBuilder, endDate.toEpochMilliseconds())
        val builtUri = uriBuilder.build()

        val result = contentResolver.query(
            builtUri, instanceProjection,
            "${CalendarContract.Instances.CALENDAR_ID} = ?"
                    + " AND IFNULL(" + CalendarContract.Instances.STATUS + ", " + CalendarContract.Instances.STATUS_TENTATIVE + ") != " + CalendarContract.Instances.STATUS_CANCELED
                    + " AND IFNULL(" + CalendarContract.Instances.SELF_ATTENDEE_STATUS + ", " + CalendarContract.Attendees.ATTENDEE_STATUS_NONE + ") != " + CalendarContract.Attendees.ATTENDEE_STATUS_DECLINED,
            arrayOf(calendar.platformId), "BEGIN ASC"
        )?.use { cursor ->
            logger.d("Found ${cursor.count} events for calendar ${calendar.name.obfuscate(privateLogger)}")
            val list = mutableListOf<CalendarEvent>()
            while (cursor.moveToNext()) {
                val event = resolveCalendarInstance(contentResolver, cursor, calendar.ownerId)
                if (event != null) {
                    list.add(event)
                }
            }
            list
        } ?: listOf()
        return result
    }

    private val observerHandler = Handler(Looper.getMainLooper())

    override fun registerForCalendarChanges(): Flow<Unit> {
        val flow = MutableSharedFlow<Unit>()
        val observer = object : ContentObserver(observerHandler) {
            override fun onChange(selfChange: Boolean) {
                logger.d("Calendar observer changed")
                libPebbleCoroutineScope.launch {
                    flow.emit(Unit)
                }
            }
        }
        try {
            contentResolver.registerContentObserver(
                CalendarContract.Instances.CONTENT_URI,
                false,
                observer
            );
            contentResolver.registerContentObserver(
                CalendarContract.Calendars.CONTENT_URI,
                false,
                observer
            );
        } catch (e: SecurityException) {
            logger.e(e) { "Error registering for calendar changes" }
        }
        return flow
    }

    override fun hasPermission(): Boolean {
        return appContext.context.checkSelfPermission(Manifest.permission.READ_CALENDAR) == PackageManager.PERMISSION_GRANTED &&
                    appContext.context.checkSelfPermission(Manifest.permission.WRITE_CALENDAR) == PackageManager.PERMISSION_GRANTED
    }
}