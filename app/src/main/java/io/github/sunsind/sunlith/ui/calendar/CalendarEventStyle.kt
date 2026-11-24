package io.github.sunsind.sunlith.ui.calendar

import android.text.format.DateFormat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CheckBoxOutlineBlank
import androidx.compose.material.icons.outlined.Repeat
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.intl.LocaleList
import androidx.compose.ui.text.style.Hyphens
import androidx.compose.ui.text.style.LineBreak
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import io.github.sunsind.sunlith.R
import io.github.sunsind.sunlith.data.CalendarEvent
import io.github.sunsind.sunlith.data.SchoolAbsenceCoverage
import io.github.sunsind.sunlith.ui.Formats
import io.github.sunsind.sunlith.ui.LocalPalette
import io.github.sunsind.sunlith.ui.rememberPageSource
import io.github.sunsind.sunlith.ui.SunlithPalette
import io.github.sunsind.sunlith.ui.timeLabel
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import kotlin.math.sign
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

/** Fill and text colors for an event block. */
@Immutable
internal data class EventColors(val container: Color, val content: Color)

internal val EventTitleFade = 4.dp

/**
 * Course fills. Light themes use deep tones under white text, dark themes light tones under near-black text. Every
 * pair keeps at least 5:1 text contrast, and every fill keeps at least 3:1 against the app background.
 */
private val COURSE_LIGHT = longArrayOf(
    0xFF1A5FB4, 0xFF2E7D32, 0xFF7B1FA2, 0xFFB4450E, 0xFFC62828, 0xFF00695C, 0xFF3F51B5, 0xFFAD1457, 0xFF6D4C41, 0xFF5C6B00,
)
private val COURSE_DARK = longArrayOf(
    0xFF8EC5FF, 0xFF9BD7A0, 0xFFD7A6E8, 0xFFFFB870, 0xFFF4A3A3, 0xFF7FD3C8, 0xFFAEB8F5, 0xFFF7A6C8, 0xFFD2BBAE, 0xFFCFD97A,
)
private const val COURSE_SLOTS = 10
private val COURSE_INK_DARK = Color(0xFF121212)

internal fun courseColors(slot: Int, dark: Boolean): EventColors {
    val index = Math.floorMod(slot, COURSE_SLOTS)
    return if (dark) EventColors(Color(COURSE_DARK[index]), COURSE_INK_DARK) else EventColors(Color(COURSE_LIGHT[index]), Color.White)
}

private fun preferredCourseSlot(key: String): Int = Math.floorMod(key.hashCode(), COURSE_SLOTS)

/**
 * Keeps saved course slots and assigns new courses the next free slot while the palette has room.
 * New keys are sorted so event order cannot affect their initial assignment.
 */
internal fun assignCourseSlots(keys: Collection<String>, previous: Map<String, Int>): Map<String, Int> {
    val result = previous.filterValues { it in 0 until COURSE_SLOTS }.toMutableMap()
    val active = keys.filter { it.isNotBlank() }.toSet()
    val counts = IntArray(COURSE_SLOTS)
    active.mapNotNull { result[it] }.forEach { counts[it]++ }
    active.filter { it !in result }.sorted().forEach { key ->
        val preferred = preferredCourseSlot(key)
        val slot = (0 until COURSE_SLOTS).map { (preferred + it) % COURSE_SLOTS }.minBy { counts[it] }
        counts[slot]++
        result[key] = slot
    }
    return result
}

/** Course color slots for every school course currently loaded. Outside a provider, each course uses its hash slot. */
@Immutable
internal class CourseColors(private val slots: Map<String, Int>) {
    fun slot(key: String): Int = slots[key] ?: preferredCourseSlot(key)

    companion object {
        val Empty = CourseColors(emptyMap())
    }
}

internal val LocalCourseColors = staticCompositionLocalOf { CourseColors.Empty }

private fun CalendarEvent.courseKeyOrNull(): String? = school?.course?.takeIf { it.isNotBlank() }

/**
 * School classes take their saved course color; personal events use a stable title color. A canceled class keeps its
 * color under [drawCanceledBars], with explicit text so color is never the only signal.
 */
internal fun eventColors(event: CalendarEvent, palette: SunlithPalette, courses: CourseColors, dimmed: Boolean = false): EventColors {
    if (dimmed) return EventColors(palette.raised, palette.muted)
    return event.courseKeyOrNull()?.let { courseColors(courses.slot(it), palette.dark) }
        ?: courseColors(preferredCourseSlot("event:${event.title.trim().lowercase(Locale.ROOT)}"), palette.dark)
}

internal fun CalendarEvent.withTodoMark(title: String) = if (todo) "☐ $title" else title

@Composable
internal fun CalendarEvent.spokenDescription(f: Formats, time: String, includeLocation: Boolean = true): String =
    listOfNotNull(
        displayTitle().ifBlank { stringResource(R.string.untitled) },
        stringResource(R.string.todo_tag).takeIf { todo },
        absenceStatus(this, f), time,
        location.takeIf { includeLocation && it.isNotBlank() },
    ).joinToString(", ")

/** The one corner shape of event cards, all-day chips and hour tiles, so every view draws events alike. */
internal val EVENT_CORNER = 8.dp
internal val EventShape = RoundedCornerShape(EVENT_CORNER)

/** A college cancellation or a teacher absent for the whole class. */
internal val CalendarEvent.canceled: Boolean
    get() = school?.let { it.absenceCoverage == SchoolAbsenceCoverage.FULL || it.lift != null } == true

/**
 * Slanted bars over [topLeft] and [area], the mark of a canceled class or of the canceled part of one, drawn in the
 * card's [content] color so they read on any course color.
 */
internal fun DrawScope.drawCanceledBars(content: Color, topLeft: Offset = Offset.Zero, area: Size = size) {
    val gap = 8.dp.toPx()
    val color = content.copy(alpha = content.alpha * 0.28f)
    clipRect(topLeft.x, topLeft.y, topLeft.x + area.width, topLeft.y + area.height) {
        var x = topLeft.x - area.height
        while (x < topLeft.x + area.width) {
            drawLine(color, Offset(x, topLeft.y + area.height), Offset(x + area.height, topLeft.y), strokeWidth = 2.5.dp.toPx())
            x += gap
        }
    }
}

@Composable
@ReadOnlyComposable
internal fun eventColors(event: CalendarEvent): EventColors =
    eventColors(event, LocalPalette.current, LocalCourseColors.current)

/** A small marker color (dots, swatches) that matches the event's block color. */
@Composable
@ReadOnlyComposable
internal fun eventAccent(event: CalendarEvent): Color {
    val p = LocalPalette.current
    return eventColors(event, p, LocalCourseColors.current).container
}

/** Keep a verified college cancellation in even the smallest Month chip. Teacher absence has its own status line. */
internal fun CalendarEvent.displayTitle(): String =
    school?.takeIf { it.kind == "01" && it.lift == null && it.courseTitle.isNotBlank() }?.courseTitle ?: title

/** Absence ranges inside this event, clipped to it and merged. Empty for a full or all-day absence. */
internal fun CalendarEvent.partialAbsences(): List<Pair<Long, Long>> {
    val info = school ?: return emptyList()
    if (info.absenceCoverage != SchoolAbsenceCoverage.PARTIAL) return emptyList()
    val merged = mutableListOf<Pair<Long, Long>>()
    info.absences.filter { !it.allDay }
        .map { maxOf(it.startMillis, startMillis) to minOf(it.endMillis, endMillis) }
        .filter { it.second > it.first }
        .sortedBy { it.first }
        .forEach { range ->
            val last = merged.lastOrNull()
            if (last != null && range.first <= last.second) merged[merged.lastIndex] = last.first to maxOf(last.second, range.second)
            else merged += range
        }
    return merged
}

/** A ring that fades in while [active] is true. Used to point out the event a date opened on. */
@Composable
internal fun Modifier.focusRing(active: Boolean, color: Color, shape: Shape, motion: Boolean): Modifier {
    val alpha by animateFloatAsState(if (active) 1f else 0f, if (motion) tween(if (active) 200 else 600) else tween(0), label = "focusRing")
    return if (alpha <= 0f) this else border(2.dp, color.copy(alpha = alpha), shape)
}

/**
 * Short, localized time ranges for narrow event blocks: "8:30 - 10:30 p.m." instead of repeating the day period,
 * "8 - 9 a.m." for whole hours, "20:30 - 22:30" in 24-hour time. Uses the app's localized range separator, and
 * ordinary spaces so the text can wrap between the start and the end in a narrow column.
 */
@Stable
internal class CompactTimes(private val locale: Locale, is24Hour: Boolean, private val join: (String, String) -> String) {
    private val is24 = is24Hour
    private val hm = formatter(locale, if (is24Hour) "Hm" else "hm")
    private val h = if (is24Hour) hm else formatter(locale, "h")
    private val bareHm = bare(locale, if (is24Hour) null else "hm")
    private val bareH = bare(locale, if (is24Hour) null else "h")
    private val period = runCatching { DateTimeFormatter.ofPattern("a", locale) }.getOrNull()

    fun time(t: LocalTime): String = clean((if (!is24 && t.minute == 0) h else hm).format(t))

    /**
     * The rail label for [hour], 0 to 24. The 12-hour day period is set smaller and without its dots ("7 PM"), as other
     * calendars write it, so the column stays narrow.
     */
    fun hourLabel(hour: Int): AnnotatedString {
        val t = LocalTime.of(hour % 24, 0)
        val number = bareH?.takeIf { !is24 && period != null }?.let { clean(it.format(t)) } ?: return AnnotatedString(time(t))
        return buildAnnotatedString {
            append("$number ")
            withStyle(SpanStyle(fontSize = 0.8.em)) { append(period!!.format(t).replace(".", "").uppercase(locale)) }
        }
    }

    fun range(start: LocalTime, end: LocalTime): String {
        val samePeriod = !is24 && period != null && bareHm != null && bareH != null && period.format(start) == period.format(end)
        val first = if (samePeriod) clean((if (start.minute == 0) bareH!! else bareHm!!).format(start)) else time(start)
        return join(first, time(end))
    }

    private fun clean(s: String) = s.replace(' ', ' ').replace(' ', ' ')

    private companion object {
        fun formatter(locale: Locale, skeleton: String): DateTimeFormatter = runCatching {
            DateTimeFormatter.ofPattern(DateFormat.getBestDateTimePattern(locale, skeleton), locale)
        }.getOrElse { DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT).withLocale(locale) }

        /** The 12-hour pattern without its day period, only when the period is a single trailing field. */
        fun bare(locale: Locale, skeleton: String?): DateTimeFormatter? {
            if (skeleton == null) return null
            val pattern = runCatching { DateFormat.getBestDateTimePattern(locale, skeleton) }.getOrNull() ?: return null
            if ('\'' in pattern || 'B' in pattern || 'b' in pattern || pattern.count { it == 'a' } != 1) return null
            val trimmed = pattern.trimEnd()
            if (!trimmed.endsWith("a")) return null
            val rest = trimmed.dropLast(1).trimEnd(' ', ' ', ' ')
            return runCatching { DateTimeFormatter.ofPattern(rest, locale) }.getOrNull()
        }
    }
}

/**
 * Explicit date opens consume their request before moving, so recreation cannot replay a scroll in progress. Only a
 * date opened from a link [ring]s the event it lands on. Switching views or dates just scrolls there.
 */
@Stable
internal class TimelineFocus(val date: LocalDate, val request: Int, val ring: Boolean, private val onStart: () -> Unit) {
    fun start() = onStart()
}

/**
 * The first timed event on the chosen day. All-day-only dates use their first banner. When [nowMillis] falls on
 * [day], the day is today: the event in progress comes first, then the next one that has not ended, so a list never
 * points back at the morning's classes.
 */
internal fun focusTarget(events: List<CalendarEvent>, day: LocalDate, zone: ZoneId, nowMillis: Long? = null): CalendarEvent? {
    val timed = events.filter { !it.isTimelineBanner() }.sortedWith(compareBy<CalendarEvent> { it.startMillis }.thenBy { it.endMillis })
    if (nowMillis != null && Instant.ofEpochMilli(nowMillis).atZone(zone).toLocalDate() == day) {
        (activeTimedEvent(timed, nowMillis) ?: timed.firstOrNull { it.endMillis > nowMillis })?.let { return it }
    }
    if (timed.isEmpty()) return events.firstOrNull { it.isTimelineBanner() }
    return timed.firstOrNull { Instant.ofEpochMilli(it.startMillis).atZone(zone).toLocalDate() == day } ?: timed.first()
}

/**
 * The timed event in progress at [nowMillis] (start <= now < end), or null. Among overlapping events the earliest
 * start wins, then the earliest end, so the choice never depends on load order.
 */
internal fun activeTimedEvent(events: List<CalendarEvent>, nowMillis: Long): CalendarEvent? =
    events.filter { !it.isTimelineBanner() && it.startMillis <= nowMillis && nowMillis < it.endMillis }
        .minWithOrNull(compareBy<CalendarEvent> { it.startMillis }.thenBy { it.endMillis }.thenBy { it.id })

/**
 * The minute today's timeline opens at, both when it first appears and when Today is tapped: the start of the event
 * in progress, otherwise the usual hour before now. A later event is never a reason to jump ahead.
 */
internal fun timelineTodayMinute(events: List<CalendarEvent>, today: LocalDate, zone: ZoneId, nowMillis: Long, currentMinute: Int): Int =
    activeTimedEvent(events, nowMillis)?.let { minuteOn(it.startMillis, today, zone) } ?: previousHour(currentMinute)

/** The minute of [day] at [millis], clamped to the day: 0 for earlier days and 24:00 for later ones. */
internal fun minuteOn(millis: Long, day: LocalDate, zone: ZoneId): Int {
    val time = Instant.ofEpochMilli(millis).atZone(zone)
    return when (time.toLocalDate().compareTo(day).sign) {
        -1 -> 0
        1 -> 24 * 60
        else -> time.hour * 60 + time.minute
    }
}

@Composable
internal fun absenceStatus(event: CalendarEvent, f: Formats, compact: CompactTimes? = null): String? {
    val info = event.school ?: return null
    val cancellation = info.lift?.let { lift ->
        val until = lift.until
        if (until == null) stringResource(R.string.calendar_class_cancelled)
        else {
            val time = compact?.time(until) ?: f.time(until)
            stringResource(R.string.calendar_class_cancelled_until, time)
        }
    }
    val teacher = when (info.absenceCoverage) {
        SchoolAbsenceCoverage.NONE -> null
        SchoolAbsenceCoverage.FULL -> stringResource(if (compact != null) R.string.calendar_absent_short else R.string.calendar_teacher_absent)
        SchoolAbsenceCoverage.PARTIAL -> {
            val ranges = event.partialAbsences()
            if (ranges.isEmpty()) return cancellation
            val zone = ZoneId.systemDefault()
            fun local(millis: Long) = Instant.ofEpochMilli(millis).atZone(zone).toLocalTime()
            val single = ranges.singleOrNull()
            when {
                compact == null -> stringResource(R.string.calendar_teacher_absent_range,
                    ranges.joinToString(", ") { (start, end) -> f.timeRange(start, end) })
                // A card already shows the class start or range, so an absence that shares one edge names only the other.
                single != null && single.first == event.startMillis && single.second < event.endMillis ->
                    stringResource(R.string.calendar_absent_until, compact.time(local(single.second)))
                single != null && single.second == event.endMillis && single.first > event.startMillis ->
                    stringResource(R.string.calendar_absent_from, compact.time(local(single.first)))
                else -> stringResource(R.string.calendar_absent_short_range,
                    ranges.joinToString(", ") { (start, end) -> compact.range(local(start), local(end)) })
            }
        }
    }
    return listOfNotNull(cancellation, teacher).takeIf { it.isNotEmpty() }?.joinToString("\n")
}

/**
 * Combined classes show each period’s start time and room when the rooms differ.
 */
internal fun Typography.dayEventTitle(f: Formats): TextStyle =
    bodyMedium.copy(fontWeight = FontWeight.SemiBold, hyphens = Hyphens.Auto,
        lineBreak = LineBreak.Paragraph, localeList = LocaleList(f.locale.toLanguageTag()))

internal fun CalendarEvent.classroom(): String? =
    location.trim().takeIf { !allDay && school?.kind == "01" && it.isNotEmpty() }

@Composable
internal fun CalendarEventCard(
    event: CalendarEvent, day: LocalDate, f: Formats, onClick: () -> Unit, modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val zone = ZoneId.systemDefault()
    val time = context.timeLabel(event, day, zone, f)
    val title = event.displayTitle().ifBlank { stringResource(R.string.untitled) }
    val colors = eventColors(event)
    val status = absenceStatus(event, f)
    val radius = 10.dp
    val shape = RoundedCornerShape(radius)
    val morph = LocalAgendaMorph.current
    val endpoint = morph?.endpoint(event, day, inList = true, radius)
    val card = if (endpoint == null) modifier else morph.card(modifier, endpoint, day)
    val typography = MaterialTheme.typography
    val source = rememberPageSource()
    Row(
        card.fillMaxWidth().height(IntrinsicSize.Min).heightIn(min = 52.dp).clip(shape).background(colors.container)
            .then(source.modifier)
            .then(if (event.canceled) Modifier.drawBehind { drawCanceledBars(colors.content) } else Modifier)
            .calendarPress(colors.content)
            .clickable(role = Role.Button) { source.open(action = onClick) }
            .semantics { contentDescription = listOf(time, event.location).filter { it.isNotBlank() }.joinToString(", ") }
            .padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            val titleStyle = if (endpoint != null) typography.dayEventTitle(f) else typography.titleMedium
            AgendaCardText(title, colors.content, titleStyle, endpoint, "title")
            if (!event.allDay) {
                AgendaCardText(time, colors.content, typography.bodySmall, endpoint, "time",
                    modifier = Modifier.clearAndSetSemantics { })
            }
            event.classroom()?.let { room ->
                AgendaCardText(room, colors.content, typography.bodySmall, endpoint, "room",
                    modifier = Modifier.clearAndSetSemantics { })
            }
            if (status != null) AgendaCardText(status, colors.content, typography.bodyMedium, endpoint, "status")
        }
        if (event.todo) {
            Icon(Icons.Outlined.CheckBoxOutlineBlank, stringResource(R.string.todo_tag), tint = colors.content,
                modifier = Modifier.padding(start = 8.dp, end = 4.dp).size(16.dp))
        }
        if (!event.rrule.isNullOrBlank()) {
            Icon(Icons.Outlined.Repeat, stringResource(R.string.repeating), tint = colors.content, modifier = Modifier.padding(horizontal = 12.dp).size(16.dp))
        } else {
            Spacer(Modifier.width(12.dp))
        }
    }
}
