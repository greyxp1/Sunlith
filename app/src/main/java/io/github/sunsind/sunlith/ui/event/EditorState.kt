package io.github.sunsind.sunlith.ui.event

import io.github.sunsind.sunlith.R
import io.github.sunsind.sunlith.data.CalendarDates
import io.github.sunsind.sunlith.data.DeviceCalendar
import io.github.sunsind.sunlith.data.EventDraft
import io.github.sunsind.sunlith.data.TodoMarker
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.temporal.ChronoUnit

data class EditorState(
    val id: Long?,
    val calendarId: Long?,
    val lockedCalendar: Boolean,
    val title: String = "",
    val location: String = "",
    val notes: String = "",
    val allDay: Boolean = false,
    val startDate: LocalDate,
    val startTime: LocalTime,
    val endDate: LocalDate,
    val endTime: LocalTime,
    val timeZone: String,
    val rrule: String?,
    val originalRrule: String?,
    val reminders: List<Int>,
    val originalDraft: EventDraft? = null,
    val todo: Boolean = false,
) {
    val recurring: Boolean get() = !rrule.isNullOrBlank()
    val editsSeries: Boolean get() = id != null && !originalRrule.isNullOrBlank()

    fun startMillis(zone: ZoneId): Long = if (allDay) CalendarDates.allDayMillis(startDate)
    else startDate.atTime(startTime).atZone(zone).toInstant().toEpochMilli()

    fun endMillis(zone: ZoneId): Long = if (allDay) CalendarDates.allDayMillis(endDate.plusDays(1))
    else endDate.atTime(endTime).atZone(zone).toInstant().toEpochMilli()

    fun endInvalid(): Boolean {
        if (allDay) return endDate.isBefore(startDate)
        val zone = ZoneId.systemDefault()
        val s = startMillis(zone)
        val e = endMillis(zone)
        return if (recurring) e <= s else e < s
    }

    fun problem(writable: List<DeviceCalendar>): Int? = when {
        title.isBlank() -> R.string.editor_need_title
        endInvalid() -> R.string.editor_end_before_start
        calendarId == null || writable.none { it.id == calendarId } -> R.string.editor_need_calendar
        else -> null
    }

    fun toDraft(zone: ZoneId): EventDraft {
        val original = originalDraft?.let { from(it, zone) }
        val timingUnchanged = original != null && allDay == original.allDay &&
            startDate == original.startDate && startTime == original.startTime &&
            endDate == original.endDate && endTime == original.endTime
        return EventDraft(
            id = id, calendarId = calendarId ?: error("No calendar"), title = title.trim(),
            description = if (original != null && notes == original.notes && todo == original.todo)
                originalDraft!!.description else TodoMarker.set(notes, todo),
            location = location.trim(),
            startMillis = if (timingUnchanged) originalDraft!!.startMillis else startMillis(zone),
            endMillis = if (timingUnchanged) originalDraft!!.endMillis else endMillis(zone), allDay = allDay,
            timeZone = if (allDay) "UTC" else timeZone, rrule = rrule?.takeIf(String::isNotBlank),
            reminderMinutes = reminders.distinct().sorted(),
        )
    }

    fun withStartDate(date: LocalDate): EditorState {
        val days = ChronoUnit.DAYS.between(startDate, date)
        return copy(startDate = date, endDate = endDate.plusDays(days))
    }

    fun withStartTime(time: LocalTime): EditorState {
        val oldStart = startDate.atTime(startTime)
        val duration = Duration.between(oldStart, endDate.atTime(endTime)).let { if (it.isNegative) Duration.ofHours(1) else it }
        val newEnd = startDate.atTime(time).plus(duration)
        return copy(startTime = time, endDate = newEnd.toLocalDate(), endTime = newEnd.toLocalTime())
    }

    companion object {
        fun from(d: EventDraft, zone: ZoneId): EditorState {
            val base = EditorState(
                id = d.id, calendarId = d.calendarId, lockedCalendar = d.id != null, title = d.title,
                location = d.location, notes = TodoMarker.strip(d.description), todo = TodoMarker.has(d.description), allDay = d.allDay,
                startDate = LocalDate.now(), startTime = LocalTime.of(9, 0), endDate = LocalDate.now(), endTime = LocalTime.of(10, 0),
                timeZone = if (d.allDay) java.util.TimeZone.getDefault().id else d.timeZone,
                rrule = d.rrule, originalRrule = d.rrule, reminders = d.reminderMinutes.distinct().sorted(),
                originalDraft = d,
            )
            if (d.allDay) {
                val s = CalendarDates.allDayDate(d.startMillis)
                val endEx = CalendarDates.allDayDate(d.endMillis)
                return base.copy(startDate = s, endDate = if (endEx.isAfter(s)) endEx.minusDays(1) else s)
            }
            val s = Instant.ofEpochMilli(d.startMillis).atZone(zone).toLocalDateTime()
            val e = Instant.ofEpochMilli(maxOf(d.endMillis, d.startMillis)).atZone(zone).toLocalDateTime()
            return base.copy(startDate = s.toLocalDate(), startTime = s.toLocalTime(), endDate = e.toLocalDate(), endTime = e.toLocalTime())
        }
    }
}
