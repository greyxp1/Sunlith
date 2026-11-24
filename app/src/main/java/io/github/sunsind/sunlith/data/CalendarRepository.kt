package io.github.sunsind.sunlith.data

import android.Manifest
import android.content.ContentProviderOperation
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.os.Looper
import android.provider.CalendarContract
import android.provider.CalendarContract.Attendees
import android.provider.CalendarContract.Calendars
import android.provider.CalendarContract.Events
import android.provider.CalendarContract.Instances
import android.provider.CalendarContract.Reminders
import io.github.sunsind.sunlith.R
import io.github.sunsind.sunlith.school.sync.SchoolCalendarPresentation
import io.github.sunsind.sunlith.school.sync.SchoolOwnership
import io.github.sunsind.sunlith.system.AppRefresh
import io.github.sunsind.sunlith.system.hasPermission
import java.time.ZoneId
import java.util.TimeZone
import java.util.UUID

class CalendarRepository(context: Context) {
    private val context = context.applicationContext
    private val resolver get() = context.contentResolver

    fun calendars(): List<DeviceCalendar> {
        checkRead()
        return resolver.query(
            Calendars.CONTENT_URI,
            arrayOf(Calendars._ID, Calendars.CALENDAR_DISPLAY_NAME, Calendars.ACCOUNT_NAME, Calendars.CALENDAR_COLOR,
                Calendars.CALENDAR_ACCESS_LEVEL, Calendars.VISIBLE, Calendars.ACCOUNT_TYPE, Calendars.NAME),
            "${Calendars.SYNC_EVENTS} IS NOT 0 OR ${Calendars.ACCOUNT_TYPE} = ?",
            arrayOf(CalendarContract.ACCOUNT_TYPE_LOCAL), "${Calendars.CALENDAR_DISPLAY_NAME} COLLATE NOCASE ASC",
        )?.use { cursor ->
            buildList {
                while (cursor.moveToNext()) {
                    val account = cursor.string(Calendars.ACCOUNT_NAME)
                    val local = cursor.string(Calendars.ACCOUNT_TYPE) == CalendarContract.ACCOUNT_TYPE_LOCAL
                    val name = if (local && account == LOCAL_ACCOUNT && cursor.string(Calendars.NAME) == LOCAL_NAME) LOCAL_ACCOUNT
                        else cursor.string(Calendars.CALENDAR_DISPLAY_NAME).ifBlank { "Calendar" }
                    add(DeviceCalendar(
                        cursor.long(Calendars._ID), name, account, cursor.int(Calendars.CALENDAR_COLOR),
                        cursor.int(Calendars.CALENDAR_ACCESS_LEVEL) >= Calendars.CAL_ACCESS_CONTRIBUTOR,
                        cursor.int(Calendars.VISIBLE) != 0, local,
                    ))
                }
            }
        } ?: emptyList()
    }

    /**
     * Event occurrences in the range. With [reminders], the alert reminders of every row that has any are read in a
     * few batched queries and attached to the snapshot, so Details can show them without its own read.
     */
    fun events(startMillis: Long, endMillis: Long, calendarIds: Set<Long>, school: Boolean = false, reminders: Boolean = false): List<CalendarEvent> {
        checkRead()
        require(endMillis > startMillis) { "Choose a valid date range." }
        require(endMillis - startMillis <= 366L * 24 * 60 * 60 * 1000 * 3) { "Load at most three years at a time." }
        if (calendarIds.isEmpty()) return emptyList()
        val ids = calendarIds.toList()
        val withAlarms = mutableSetOf<Long>()
        val rows = instances(startMillis, endMillis, ids, "", (ids.map(Long::toString) + Events.STATUS_CANCELED.toString()).toTypedArray(),
            "${Instances.BEGIN} ASC, ${Instances.END} ASC, ${Instances.TITLE} COLLATE NOCASE ASC", withAlarms)
        val events = if (!reminders) rows else {
            val minutes = reminderMinutes(withAlarms).mapValues { (_, list) -> list.distinct().sorted() }
            rows.map { it.copy(reminderMinutes = minutes[it.id].orEmpty()) }
        }
        return if (school) SchoolCalendarPresentation.annotate(context, events) else events
    }

    /** Uncancelled occurrences in [ids]; [extra] adds selection after the calendar and status filters, whose arguments lead [args]. Rows with alerts are added to [withAlarms]. */
    private fun instances(startMillis: Long, endMillis: Long, ids: List<Long>, extra: String, args: Array<String>, order: String, withAlarms: MutableSet<Long>? = null): List<CalendarEvent> {
        val uri = Instances.CONTENT_URI.buildUpon().also {
            ContentUris.appendId(it, startMillis)
            ContentUris.appendId(it, endMillis)
        }.build()
        return resolver.query(uri,
            arrayOf(Instances.EVENT_ID, Instances.CALENDAR_ID, Instances.TITLE, Instances.DESCRIPTION,
                Instances.EVENT_LOCATION, Instances.BEGIN, Instances.END, Instances.ALL_DAY,
                Instances.EVENT_TIMEZONE, Instances.RRULE, Instances.DISPLAY_COLOR, Instances.CALENDAR_DISPLAY_NAME, Instances.HAS_ALARM),
            "${Instances.CALENDAR_ID} IN (${ids.joinToString(",") { "?" }}) AND (${Instances.STATUS} IS NULL OR ${Instances.STATUS} != ?)$extra",
            args, order,
        )?.use { cursor ->
            buildList {
                while (cursor.moveToNext()) {
                    val id = cursor.long(Instances.EVENT_ID)
                    if (withAlarms != null && cursor.int(Instances.HAS_ALARM) != 0) withAlarms += id
                    add(CalendarEvent(
                        id, cursor.long(Instances.CALENDAR_ID),
                        cursor.string(Instances.TITLE), cursor.string(Instances.DESCRIPTION),
                        cursor.string(Instances.EVENT_LOCATION), cursor.long(Instances.BEGIN), cursor.long(Instances.END),
                        cursor.int(Instances.ALL_DAY) != 0, cursor.string(Instances.EVENT_TIMEZONE).ifBlank { TimeZone.getDefault().id },
                        cursor.optional(Instances.RRULE), cursor.int(Instances.DISPLAY_COLOR), cursor.string(Instances.CALENDAR_DISPLAY_NAME),
                    ))
                }
            }
        } ?: emptyList()
    }

    fun getEvent(id: Long): EventDraft? {
        checkRead()
        return readStored(id)?.draft
    }

    /** Tags only the description; timing, recurrence, attendees and reminders are never rewritten. */
    fun setTodo(id: Long, enabled: Boolean) {
        checkWrite()
        val expected = revision(id) ?: error("This event no longer exists.")
        require(editBlockReason(id) == null) { "This event cannot be edited here. Open it in your existing calendar app." }
        val description = expected[Events.DESCRIPTION].orEmpty()
        val updated = TodoMarker.set(description, enabled)
        if (description == updated) return
        require(updated.length <= 100_000) { "The event details are too long." }
        resolver.applyBatch(CalendarContract.AUTHORITY, arrayListOf(
            assertRow(Events.CONTENT_URI, id, expected),
            ContentProviderOperation.newUpdate(ContentUris.withAppendedId(Events.CONTENT_URI, id))
                .withValue(Events.DESCRIPTION, updated).withExpectedCount(1).build(),
        ))
        AppRefresh.refresh(context)
    }

    /**
     * Single events have no history cutoff. Repeating occurrences cover the last 366 days and the
     * next 366 days, keeping expansion bounded. Future items let the UI schedule its next refresh.
     */
    fun todoEvents(calendarIds: Set<Long>): List<TodoItem> {
        val now = System.currentTimeMillis()
        val zone = ZoneId.systemDefault()
        checkRead()
        if (calendarIds.isEmpty()) return emptyList()
        val ids = calendarIds.toList()
        val selection = "${Events.CALENDAR_ID} IN (${ids.joinToString(",") { "?" }}) AND ${Events.DELETED}=0 AND " +
            "(${Events.STATUS} IS NULL OR ${Events.STATUS} != ?) AND ${Events.DESCRIPTION} LIKE ?"
        val args = (ids.map(Long::toString) + Events.STATUS_CANCELED.toString() + "%${TodoMarker.TOKEN}%").toTypedArray()
        val tagged = resolver.query(Events.CONTENT_URI, STORED_PROJECTION, selection, args, "${Events.DTSTART} ASC")?.use { cursor ->
            buildList {
                while (cursor.moveToNext()) {
                    val stored = readStored(cursor, false)
                    if (TodoMarker.has(stored.draft.description)) add(stored)
                }
            }
        }.orEmpty()
        val calendars = calendars().associateBy { it.id }
        fun StoredEvent.isRecurring(): Boolean = !draft.rrule.isNullOrBlank() || !rdate.isNullOrBlank() ||
            !exrule.isNullOrBlank() || originalId != null || originalSyncId != null || originalInstanceTime != null
        val singles = tagged.filterNot { it.isRecurring() }.map { stored ->
            val draft = stored.draft
            val calendar = calendars[draft.calendarId]
            val event = CalendarEvent(requireNotNull(draft.id), draft.calendarId, draft.title, draft.description, draft.location,
                draft.startMillis, draft.endMillis, draft.allDay, draft.timeZone, draft.rrule, calendar?.color ?: 0, calendar?.name.orEmpty())
            TodoItem(event, TodoIdentity.key(event), TodoIdentity.availableAt(event, zone))
        }
        if (tagged.none { it.isRecurring() }) return singles.sortedBy { it.availableAt }
        val span = 366L * 24 * 60 * 60 * 1000
        val occurrences = instances(now - span, now + span, ids, " AND ${Instances.DESCRIPTION} LIKE ?", args, "${Instances.BEGIN} ASC")
            .filter { TodoMarker.has(it.description) }
        val recurringIds = tagged.filter { it.isRecurring() }.mapNotNull { it.draft.id }.toSet()
        val taggedById = tagged.associateBy { it.draft.id }
        val repeating = occurrences.groupBy { it.calendarId }.flatMap { (calendarId, events) ->
            val links = seriesLinks(calendarId, events.map { it.id })
            val identities = links.values.map { it.masterId }.distinct().associateWith { masterId ->
                val master = taggedById[masterId] ?: readStored(masterId)
                master?.let { TodoRecurrenceIdentity.from(it.draft, !it.rdate.isNullOrBlank() || !it.exrule.isNullOrBlank()) }
            }
            events.mapNotNull { event ->
                val link = links[event.id] ?: return@mapNotNull null
                if (event.id !in recurringIds && link.originalInstanceTime == null && link.rrule.isNullOrBlank()) return@mapNotNull null
                TodoItem(event, TodoIdentity.key(event, link.masterId, true, link.originalInstanceTime,
                    if (link.originalInstanceTime == null) event.allDay else link.originalAllDay, identities[link.masterId]),
                    TodoIdentity.availableAt(event, zone))
            }
        }
        return (singles + repeating).distinctBy { it.key }.sortedBy { it.availableAt }
    }

    fun editBlockReason(id: Long): EditBlockReason? {
        checkRead()
        return blockReason(id, readStored(id, includeReminders = false), checkData = true)
    }

    private fun blockReason(id: Long, stored: StoredEvent?, checkData: Boolean): EditBlockReason? {
        if (stored == null) return EditBlockReason.MISSING
        val current = revision(id) ?: return EditBlockReason.MISSING
        if (SchoolOwnership.isManaged(context, id, current)) return EditBlockReason.SCHOOL_MANAGED
        if (calendars().none { it.id == stored.draft.calendarId && it.writable }) return EditBlockReason.READ_ONLY
        if (checkData && stored.invalidReason != null) return EditBlockReason.INVALID_PROVIDER_DATA
        if (hasAttendees(id)) return EditBlockReason.GUESTS
        if (stored.isOccurrence() || !stored.exdate.isNullOrBlank() ||
            !stored.rdate.isNullOrBlank() || !stored.exrule.isNullOrBlank() || hasExceptions(id)) return EditBlockReason.RECURRENCE_EXCEPTION
        return null
    }

    /** Throws the save or delete message for [editBlockReason]. Deleting does not need readable provider timing. */
    private fun requireChangeable(id: Long, stored: StoredEvent, delete: Boolean) {
        when (blockReason(id, stored, checkData = !delete)) {
            null -> Unit
            EditBlockReason.MISSING -> error("This event no longer exists.")
            EditBlockReason.SCHOOL_MANAGED -> throw IllegalArgumentException(context.getString(R.string.school_sync_managed_event))
            EditBlockReason.READ_ONLY -> writableCalendar(stored.draft.calendarId)
            EditBlockReason.INVALID_PROVIDER_DATA -> throw IllegalArgumentException(stored.invalidReason.orEmpty())
            EditBlockReason.GUESTS -> throw IllegalArgumentException(if (delete) "Delete meetings with guests in your existing calendar app so you can review cancellation messages."
                else "Edit meetings with guests in your existing calendar app so you can review invitation updates.")
            EditBlockReason.RECURRENCE_EXCEPTION -> throw IllegalArgumentException(when {
                stored.isOccurrence() && delete -> "Delete this occurrence in your existing calendar app to preserve the repeating series."
                stored.isOccurrence() -> "Editing a single occurrence is not supported yet. Open the series in your existing calendar app."
                delete -> "This series has exceptions. Delete it in your existing calendar app to preserve them."
                else -> "This series has exceptions. Edit it in your existing calendar app to preserve them."
            })
        }
    }

    private fun StoredEvent.isOccurrence() = originalId != null || originalSyncId != null || originalInstanceTime != null

    fun saveEvent(draft: EventDraft): Long = save(draft, null)

    internal fun saveImported(draft: EventDraft, uid: String): Long = save(draft, uid)

    /** Updates only if the provider row still matches [expected] (see [revision]); the check and update share one provider batch. */
    internal fun updateIfUnchanged(draft: EventDraft, expected: Map<String, String?>): Long {
        requireNotNull(draft.id) { "This event no longer exists." }
        return save(draft, null, expected)
    }

    private fun save(draft: EventDraft, importedUid: String?, expected: Map<String, String?>? = null): Long {
        checkWrite()
        CalendarDates.validate(draft)
        writableCalendar(draft.calendarId)
        val original = draft.id?.let { readStored(it) ?: error("This event no longer exists.") }
        val currentRevision = draft.id?.let { revision(it) ?: error("This event no longer exists.") }
        if (original != null) {
            requireChangeable(draft.id!!, original, delete = false)
            require(original.draft.calendarId == draft.calendarId) { "Moving an existing event between calendars is not supported yet." }
        }
        val minutes = draft.reminderMinutes.distinct().sorted()
        val remindersChanged = original == null || original.draft.reminderMinutes.distinct().sorted() != minutes
        val preservedReminders = if (remindersChanged) draft.id?.let(::preservedReminderCount) ?: 0 else 0
        val policy = if (remindersChanged) reminderPolicy(draft.calendarId) else null
        if (policy != null) {
            val problem = policy.countProblem(minutes, preservedReminders) ?: policy.alertProblem(minutes) ?: policy.timeProblem(minutes)
            if (problem != null) throw IllegalArgumentException(problem)
        }
        val rule = draft.rrule?.takeIf(String::isNotBlank)
        val timingUnchanged = original != null && CalendarDates.timingUnchanged(original.draft, draft)
        val recurrenceTypeUnchanged = (original?.draft?.rrule.isNullOrBlank() == rule.isNullOrBlank())
        val values = ContentValues().apply {
            put(Events.CALENDAR_ID, draft.calendarId)
            put(Events.TITLE, draft.title.trim())
            put(Events.DESCRIPTION, draft.description)
            put(Events.EVENT_LOCATION, draft.location)
            if (remindersChanged) put(Events.HAS_ALARM, if (minutes.isNotEmpty() || preservedReminders > 0) 1 else 0)
            if (!timingUnchanged || !recurrenceTypeUnchanged) {
                put(Events.DTSTART, draft.startMillis)
                put(Events.ALL_DAY, if (draft.allDay) 1 else 0)
                put(Events.EVENT_TIMEZONE, if (draft.allDay) "UTC" else draft.timeZone)
                if (rule == null) {
                    put(Events.DTEND, draft.endMillis)
                    putNull(Events.DURATION)
                } else {
                    putNull(Events.DTEND)
                    put(Events.DURATION, if (timingUnchanged) original?.originalDuration
                        ?: CalendarDates.duration(draft.startMillis, draft.endMillis, draft.allDay)
                    else CalendarDates.duration(draft.startMillis, draft.endMillis, draft.allDay))
                }
            }
            if (rule == null) putNull(Events.RRULE) else put(Events.RRULE, rule)
            if (original == null) {
                put(Events.STATUS, Events.STATUS_CONFIRMED)
                put(Events.UID_2445, importedUid?.takeIf(String::isNotBlank) ?: "${UUID.randomUUID()}@sunlith")
            }
        }
        val operations = arrayListOf<ContentProviderOperation>()
        if (expected != null && draft.id != null) operations += assertRow(Events.CONTENT_URI, draft.id, expected)
        if (currentRevision != null && draft.id != null) operations += assertRow(Events.CONTENT_URI, draft.id, currentRevision)
        if (draft.id == null) {
            operations += ContentProviderOperation.newInsert(Events.CONTENT_URI).withValues(values).build()
        } else {
            operations += ContentProviderOperation.newUpdate(ContentUris.withAppendedId(Events.CONTENT_URI, draft.id))
                .withValues(values).withExpectedCount(1).build()
            if (remindersChanged) operations += ContentProviderOperation.newDelete(Reminders.CONTENT_URI)
                    .withSelection("${Reminders.EVENT_ID}=? AND ${Reminders.MINUTES}>=0 AND (${Reminders.METHOD}=? OR ${Reminders.METHOD}=?)",
                        arrayOf(draft.id.toString(), Reminders.METHOD_ALERT.toString(), Reminders.METHOD_DEFAULT.toString())).build()
        }
        if (remindersChanged) minutes.forEach { minute ->
            val builder = ContentProviderOperation.newInsert(Reminders.CONTENT_URI)
                .withValue(Reminders.MINUTES, minute).withValue(Reminders.METHOD, policy?.writeMethod ?: Reminders.METHOD_ALERT)
            if (draft.id == null) builder.withValueBackReference(Reminders.EVENT_ID, 0)
            else builder.withValue(Reminders.EVENT_ID, draft.id)
            operations += builder.build()
        }
        val results = resolver.applyBatch(CalendarContract.AUTHORITY, operations)
        return draft.id ?: results.first().uri?.let(ContentUris::parseId) ?: error("The calendar did not save the event.")
    }

    fun deleteEvent(id: Long) = delete(id, null)

    internal fun deleteIfUnchanged(id: Long, expected: Map<String, String?>) = delete(id, expected)

    private fun delete(id: Long, expected: Map<String, String?>?) {
        checkWrite()
        val stored = readStored(id) ?: return
        val currentRevision = revision(id) ?: return
        requireChangeable(id, stored, delete = true)
        val uri = ContentUris.withAppendedId(Events.CONTENT_URI, id)
        val operations = arrayListOf<ContentProviderOperation>()
        if (expected != null) operations += assertRow(Events.CONTENT_URI, id, expected)
        operations += assertRow(Events.CONTENT_URI, id, currentRevision)
        operations += ContentProviderOperation.newDelete(uri).withExpectedCount(1).build()
        resolver.applyBatch(CalendarContract.AUTHORITY, operations)
    }

    fun createLocalCalendar(): Long {
        checkWrite()
        calendars().firstOrNull { it.local && it.account == LOCAL_ACCOUNT && it.writable }?.let { return it.id }
        val uri = Calendars.CONTENT_URI.buildUpon()
            .appendQueryParameter(CalendarContract.CALLER_IS_SYNCADAPTER, "true")
            .appendQueryParameter(Calendars.ACCOUNT_NAME, LOCAL_ACCOUNT)
            .appendQueryParameter(Calendars.ACCOUNT_TYPE, CalendarContract.ACCOUNT_TYPE_LOCAL).build()
        val values = ContentValues().apply {
            put(Calendars.ACCOUNT_NAME, LOCAL_ACCOUNT)
            put(Calendars.ACCOUNT_TYPE, CalendarContract.ACCOUNT_TYPE_LOCAL)
            put(Calendars.NAME, LOCAL_NAME)
            put(Calendars.CALENDAR_DISPLAY_NAME, LOCAL_ACCOUNT)
            put(Calendars.CALENDAR_COLOR, 0xFF737373.toInt())
            put(Calendars.CALENDAR_ACCESS_LEVEL, Calendars.CAL_ACCESS_OWNER)
            put(Calendars.OWNER_ACCOUNT, LOCAL_ACCOUNT)
            put(Calendars.VISIBLE, 1)
            put(Calendars.SYNC_EVENTS, 1)
            put(Calendars.CALENDAR_TIME_ZONE, TimeZone.getDefault().id)
            put(Calendars.ALLOWED_REMINDERS, Reminders.METHOD_ALERT.toString())
            put(Calendars.MAX_REMINDERS, 5)
        }
        return resolver.insert(uri, values)?.let(ContentUris::parseId) ?: error("Could not create an offline calendar.")
    }

    internal fun writableCalendar(id: Long): DeviceCalendar {
        val calendar = calendars().firstOrNull { it.id == id } ?: error("This calendar is no longer available.")
        require(calendar.writable) { "This calendar is read-only." }
        return calendar
    }

    internal fun storedEvents(calendarIds: Set<Long>): List<StoredEvent> {
        checkRead()
        if (calendarIds.isEmpty()) return emptyList()
        val records = resolver.query(Events.CONTENT_URI, STORED_PROJECTION,
            "${Events.CALENDAR_ID} IN (${calendarIds.joinToString(",") { "?" }}) AND ${Events.DELETED}=0",
            calendarIds.map(Long::toString).toTypedArray(), "${Events.DTSTART} ASC")?.use { cursor ->
            buildList {
                while (cursor.moveToNext()) {
                    require(size < 50_000) { "This calendar is too large for this operation. Export it from your calendar service." }
                    add(readStored(cursor, false))
                }
            }
        } ?: emptyList()
        val reminderMap = reminderMinutes(records.mapNotNull { it.draft.id })
        return records.map { it.copy(draft = it.draft.copy(reminderMinutes = reminderMap[it.draft.id].orEmpty())) }
    }

    /** On-device alert minutes per event; other reminder methods belong to the calendar service. */
    internal fun reminderMinutes(eventIds: Collection<Long>): Map<Long, List<Int>> {
        val reminders = mutableMapOf<Long, MutableList<Int>>()
        eventIds.distinct().chunked(300).forEach { ids ->
            resolver.query(Reminders.CONTENT_URI, arrayOf(Reminders.EVENT_ID, Reminders.MINUTES),
                "${Reminders.EVENT_ID} IN (${ids.joinToString(",") { "?" }}) AND ${Reminders.MINUTES}>=0 AND (${Reminders.METHOD}=? OR ${Reminders.METHOD}=?)",
                (ids.map(Long::toString) + Reminders.METHOD_ALERT.toString() + Reminders.METHOD_DEFAULT.toString()).toTypedArray(), null)?.use { cursor ->
                while (cursor.moveToNext()) reminders.getOrPut(cursor.long(Reminders.EVENT_ID)) { mutableListOf() }.add(cursor.int(Reminders.MINUTES))
            }
        }
        return reminders
    }

    internal fun storedEvent(id: Long): StoredEvent? {
        checkRead()
        return readStored(id)
    }

    /** Raw provider columns used for revision tags and compare-and-set writes. */
    internal fun revision(id: Long): Map<String, String?>? {
        checkRead()
        return resolver.query(ContentUris.withAppendedId(Events.CONTENT_URI, id), REVISION_PROJECTION, "${Events.DELETED}=0", null, null)?.use { cursor ->
            if (!cursor.moveToFirst()) null
            else cursor.values(REVISION_PROJECTION)
        }
    }

    /** Events in [calendarId] whose UID starts with [prefix], including rows a sync adapter still keeps as deleted. */
    internal fun eventsWithUidPrefix(calendarId: Long, prefix: String): List<UidMatch> {
        checkRead()
        require(prefix.isNotEmpty() && prefix.none { it == '%' || it == '_' || it == '\\' }) { "Invalid UID prefix." }
        return resolver.query(Events.CONTENT_URI, arrayOf(Events._ID, Events.UID_2445, Events.DELETED),
            "${Events.CALENDAR_ID}=? AND ${Events.UID_2445} LIKE ?", arrayOf(calendarId.toString(), "$prefix%"), null,
        )?.use { cursor ->
            buildList {
                while (cursor.moveToNext()) {
                    val uid = cursor.string(Events.UID_2445)
                    if (uid.startsWith(prefix)) add(UidMatch(cursor.long(Events._ID), uid, cursor.int(Events.DELETED) != 0))
                }
            }
        } ?: emptyList()
    }

    /** Maps each event ID in [calendarId] to its series master; exceptions resolve through ORIGINAL_ID or ORIGINAL_SYNC_ID. */
    internal fun seriesLinks(calendarId: Long, ids: Collection<Long>): Map<Long, SeriesLink> {
        checkRead()
        data class Row(val id: Long, val rrule: String?, val originalId: Long?, val originalSyncId: String?, val originalTime: Long?, val originalAllDay: Boolean)
        fun placeholders(count: Int) = List(count) { "?" }.joinToString(",")
        val rows = ids.distinct().chunked(300).flatMap { chunk ->
            resolver.query(Events.CONTENT_URI, arrayOf(Events._ID, Events.RRULE, Events.ORIGINAL_ID, Events.ORIGINAL_SYNC_ID,
                Events.ORIGINAL_INSTANCE_TIME, Events.ORIGINAL_ALL_DAY),
                "${Events._ID} IN (${placeholders(chunk.size)}) AND ${Events.CALENDAR_ID}=?",
                (chunk.map(Long::toString) + calendarId.toString()).toTypedArray(), null)?.use { cursor ->
                buildList {
                    while (cursor.moveToNext()) add(Row(cursor.long(Events._ID), cursor.optional(Events.RRULE), cursor.optionalLong(Events.ORIGINAL_ID),
                        cursor.optional(Events.ORIGINAL_SYNC_ID), cursor.optionalLong(Events.ORIGINAL_INSTANCE_TIME), cursor.int(Events.ORIGINAL_ALL_DAY) != 0))
                }
            } ?: emptyList()
        }
        val byId = mutableMapOf<Long, Pair<Long, String?>>()
        val bySyncId = mutableMapOf<String, Pair<Long, String?>>()
        rows.mapNotNull { it.originalId }.distinct().chunked(300).forEach { chunk ->
            resolver.query(Events.CONTENT_URI, arrayOf(Events._ID, Events.RRULE),
                "${Events._ID} IN (${placeholders(chunk.size)}) AND ${Events.CALENDAR_ID}=? AND ${Events.DELETED}=0",
                (chunk.map(Long::toString) + calendarId.toString()).toTypedArray(), null)?.use { cursor ->
                while (cursor.moveToNext()) byId[cursor.long(Events._ID)] = cursor.long(Events._ID) to cursor.optional(Events.RRULE)
            }
        }
        rows.filter { it.originalId == null }.mapNotNull { it.originalSyncId }.distinct().chunked(300).forEach { chunk ->
            resolver.query(Events.CONTENT_URI, arrayOf(Events._ID, Events._SYNC_ID, Events.RRULE),
                "${Events._SYNC_ID} IN (${placeholders(chunk.size)}) AND ${Events.CALENDAR_ID}=? AND ${Events.DELETED}=0",
                (chunk + calendarId.toString()).toTypedArray(), null)?.use { cursor ->
                while (cursor.moveToNext()) bySyncId[cursor.string(Events._SYNC_ID)] = cursor.long(Events._ID) to cursor.optional(Events.RRULE)
            }
        }
        return rows.associate { row ->
            val master = row.originalId?.let(byId::get) ?: row.originalSyncId?.let(bySyncId::get)
            row.id to when {
                row.originalId == null && row.originalSyncId == null -> SeriesLink(row.id, row.rrule, null, false)
                master != null -> SeriesLink(master.first, master.second, row.originalTime, row.originalAllDay)
                else -> SeriesLink(row.id, null, row.originalTime, row.originalAllDay)
            }
        }
    }

    private fun readStored(id: Long, includeReminders: Boolean = true): StoredEvent? = resolver.query(
        ContentUris.withAppendedId(Events.CONTENT_URI, id), STORED_PROJECTION, "${Events.DELETED}=0", null, null,
    )?.use { if (it.moveToFirst()) readStored(it, includeReminders) else null }

    private fun readStored(cursor: Cursor, includeReminders: Boolean = true): StoredEvent {
        val id = cursor.long(Events._ID)
        val start = cursor.long(Events.DTSTART)
        val allDay = cursor.int(Events.ALL_DAY) != 0
        val timezone = if (allDay) "UTC" else cursor.string(Events.EVENT_TIMEZONE).ifBlank { TimeZone.getDefault().id }
        val duration = cursor.optional(Events.DURATION)
        var invalidReason: String? = null
        val durationEnd = duration?.let {
            try { CalendarDates.providerDurationEndMillis(start, it) }
            catch (_: Exception) {
                invalidReason = "Event $id has an invalid calendar duration. Open it in your existing calendar app."
                null
            }
        }
        val end = cursor.optionalLong(Events.DTEND) ?: durationEnd ?: (start + if (allDay) 86_400_000 else 0)
        return StoredEvent(
            EventDraft(id, cursor.long(Events.CALENDAR_ID), cursor.string(Events.TITLE), cursor.string(Events.DESCRIPTION),
                cursor.string(Events.EVENT_LOCATION), start, end, allDay,
                timezone,
                cursor.optional(Events.RRULE), if (includeReminders) reminderMinutes(listOf(id))[id].orEmpty() else emptyList()),
            cursor.optional(Events.UID_2445) ?: "event-$id@sunlith.local",
            cursor.optional(Events.EXDATE), cursor.optional(Events.RDATE), cursor.optional(Events.EXRULE),
            cursor.optionalLong(Events.ORIGINAL_ID), cursor.optionalLong(Events.ORIGINAL_INSTANCE_TIME),
            cursor.int(Events.ORIGINAL_ALL_DAY) != 0, cursor.int(Events.STATUS) == Events.STATUS_CANCELED,
            cursor.optional(Events._SYNC_ID), cursor.optional(Events.ORIGINAL_SYNC_ID),
            duration, invalidReason,
        )
    }

    internal fun reminderPolicy(calendarId: Long): ReminderPolicy = resolver.query(
        ContentUris.withAppendedId(Calendars.CONTENT_URI, calendarId), arrayOf(Calendars.MAX_REMINDERS, Calendars.ALLOWED_REMINDERS, Calendars.ACCOUNT_TYPE),
        null, null, null,
    )?.use {
        if (!it.moveToFirst()) return@use ReminderPolicy(0, false, 0)
        val max = it.int(Calendars.MAX_REMINDERS).let { count -> if (count < 0) 20 else count }
        val allowed = it.string(Calendars.ALLOWED_REMINDERS).split(',').map(String::trim)
        val google = it.string(Calendars.ACCOUNT_TYPE) == "com.google"
        ReminderPolicy(max, allowed.isEmpty() || allowed == listOf("") || Reminders.METHOD_ALERT.toString() in allowed || Reminders.METHOD_DEFAULT.toString() in allowed,
            if (google) 40_320 else 525_600,
            if (Reminders.METHOD_ALERT.toString() in allowed || allowed == listOf("")) Reminders.METHOD_ALERT else Reminders.METHOD_DEFAULT)
    } ?: ReminderPolicy(0, false, 0)

    private fun preservedReminderCount(id: Long): Int = resolver.query(Reminders.CONTENT_URI,
        arrayOf(Reminders._ID), "${Reminders.EVENT_ID}=? AND (${Reminders.MINUTES}<0 OR (${Reminders.METHOD}!=? AND ${Reminders.METHOD}!=?))",
        arrayOf(id.toString(), Reminders.METHOD_ALERT.toString(), Reminders.METHOD_DEFAULT.toString()), null,
    )?.use { it.count } ?: 0

    private fun hasAttendees(id: Long): Boolean = resolver.query(Attendees.CONTENT_URI, arrayOf(Attendees._ID),
        "${Attendees.EVENT_ID}=?", arrayOf(id.toString()), null)?.use { it.moveToFirst() } ?: false

    private fun hasExceptions(id: Long): Boolean {
        val syncId = resolver.query(ContentUris.withAppendedId(Events.CONTENT_URI, id), arrayOf(Events._SYNC_ID),
            null, null, null)?.use { if (it.moveToFirst()) it.optional(Events._SYNC_ID) else null }
        val selection = if (syncId == null) "${Events.ORIGINAL_ID}=? AND ${Events.DELETED}=0"
        else "(${Events.ORIGINAL_ID}=? OR ${Events.ORIGINAL_SYNC_ID}=?) AND ${Events.DELETED}=0"
        val args = if (syncId == null) arrayOf(id.toString()) else arrayOf(id.toString(), syncId)
        return resolver.query(Events.CONTENT_URI, arrayOf(Events._ID), selection, args, null)?.use { it.moveToFirst() } ?: false
    }

    private fun checkRead() {
        check(Looper.myLooper() != Looper.getMainLooper()) { "Calendar access must run off the main thread." }
        check(context.hasPermission(Manifest.permission.READ_CALENDAR)) {
            "Allow calendar access to show your events."
        }
    }

    private fun checkWrite() {
        checkRead()
        check(context.hasPermission(Manifest.permission.WRITE_CALENDAR)) {
            "Allow calendar access to save events."
        }
    }

    companion object {
        private const val LOCAL_ACCOUNT = "Sunlith"
        private const val LOCAL_NAME = "sunlith-local"
        private val STORED_PROJECTION = arrayOf(Events._ID, Events.CALENDAR_ID, Events.TITLE, Events.DESCRIPTION,
            Events.EVENT_LOCATION, Events.DTSTART, Events.DTEND, Events.DURATION, Events.ALL_DAY,
            Events.EVENT_TIMEZONE, Events.RRULE, Events.UID_2445, Events.EXDATE, Events.RDATE, Events.EXRULE,
            Events.ORIGINAL_ID, Events.ORIGINAL_INSTANCE_TIME, Events.ORIGINAL_ALL_DAY, Events.STATUS, Events._SYNC_ID, Events.ORIGINAL_SYNC_ID)
        private val REVISION_PROJECTION = arrayOf(Events.CALENDAR_ID, Events.TITLE, Events.DESCRIPTION, Events.EVENT_LOCATION,
            Events.DTSTART, Events.DTEND, Events.DURATION, Events.ALL_DAY, Events.EVENT_TIMEZONE, Events.EVENT_END_TIMEZONE,
            Events.RRULE, Events.RDATE, Events.EXRULE, Events.EXDATE, Events.ORIGINAL_ID, Events.ORIGINAL_SYNC_ID,
            Events.ORIGINAL_INSTANCE_TIME, Events.STATUS, Events.UID_2445, Events.DELETED, Events.CUSTOM_APP_PACKAGE, Events.CUSTOM_APP_URI, Events.HAS_ALARM)
    }
}

internal data class UidMatch(val id: Long, val uid: String, val deleted: Boolean)

internal data class SeriesLink(val masterId: Long, val rrule: String?, val originalInstanceTime: Long?, val originalAllDay: Boolean)

private fun Cursor.string(name: String): String = getColumnIndex(name).let { if (it < 0 || isNull(it)) "" else getString(it) }
private fun Cursor.optional(name: String): String? = string(name).takeIf(String::isNotBlank)
private fun Cursor.long(name: String): Long = getColumnIndex(name).let { if (it < 0 || isNull(it)) 0L else getLong(it) }
private fun Cursor.optionalLong(name: String): Long? = getColumnIndex(name).let { if (it < 0 || isNull(it)) null else getLong(it) }
private fun Cursor.int(name: String): Int = getColumnIndex(name).let { if (it < 0 || isNull(it)) 0 else getInt(it) }

/** The current row's [columns] as provider strings. A missing or NULL column is null. */
internal fun Cursor.values(columns: Array<String>): Map<String, String?> =
    columns.associateWith { name -> getColumnIndex(name).let { if (it < 0 || isNull(it)) null else getString(it) } }

internal fun contentValues(values: Map<String, String?>) = ContentValues().apply { values.forEach { (k, v) -> if (v == null) putNull(k) else put(k, v) } }

/** Fails the whole batch unless row [id] of [uri] still holds [values]. */
internal fun assertRow(uri: Uri, id: Long, values: Map<String, String?>): ContentProviderOperation =
    ContentProviderOperation.newAssertQuery(ContentUris.withAppendedId(uri, id)).withValues(contentValues(values)).withExpectedCount(1).build()
