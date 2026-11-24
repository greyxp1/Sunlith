package io.github.sunsind.sunlith.data

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit

data class DaySpan(val first: LocalDate, val last: LocalDate) {
    val multiDay: Boolean get() = last.isAfter(first)
    val length: Long get() = ChronoUnit.DAYS.between(first, last) + 1
}

fun CalendarEvent.span(zone: ZoneId): DaySpan {
    if (allDay) {
        val start = CalendarDates.allDayDate(startMillis)
        val endExclusive = CalendarDates.allDayDate(endMillis)
        return DaySpan(start, if (endExclusive.isAfter(start)) endExclusive.minusDays(1) else start)
    }
    val start = Instant.ofEpochMilli(startMillis).atZone(zone).toLocalDate()
    val last = if (endMillis > startMillis) Instant.ofEpochMilli(endMillis - 1).atZone(zone).toLocalDate() else start
    return DaySpan(start, if (last.isBefore(start)) start else last)
}

fun CalendarEvent.key(): String = "$id-$startMillis"

fun indexByDay(events: List<CalendarEvent>, zone: ZoneId, from: LocalDate, to: LocalDate): Map<LocalDate, List<CalendarEvent>> {
    val map = HashMap<LocalDate, MutableList<CalendarEvent>>()
    val order = compareByDescending<Pair<CalendarEvent, DaySpan>> { it.first.allDay || it.second.multiDay }
        .thenBy { it.first.startMillis }.thenBy { it.first.endMillis }.thenBy { it.first.title.lowercase() }
    for ((event, span) in events.map { it to it.span(zone) }.sortedWith(order)) {
        var day = if (span.first.isBefore(from)) from else span.first
        val end = if (span.last.isAfter(to)) to else span.last
        while (!day.isAfter(end)) {
            map.getOrPut(day) { mutableListOf() }.add(event)
            day = day.plusDays(1)
        }
    }
    return map
}
