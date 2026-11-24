package io.github.sunsind.sunlith.ui.calendar

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.platform.ViewConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.intl.LocaleList
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.Hyphens
import androidx.compose.ui.text.style.LineBreak
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.constrainHeight
import androidx.compose.ui.unit.constrainWidth
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.sunsind.sunlith.R
import io.github.sunsind.sunlith.data.CalendarEvent
import io.github.sunsind.sunlith.data.key
import io.github.sunsind.sunlith.ui.Formats
import io.github.sunsind.sunlith.ui.timeLabel
import java.time.LocalDate
import java.time.ZoneId
import kotlin.math.max
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first

private const val DAY_MINUTES = 24 * 60
internal val TIMELINE_LABEL_PADDING = 3.dp

/** The folded corner of an exam, larger than the card corner so it still reads after the card clips it. */
private val EXAM_FLAG = 12.dp

internal fun CalendarEvent.isTimelineBanner(): Boolean = allDay || endMillis - startMillis >= 86_400_000L

/**
 * One timed event on one day, in minutes from local midnight, with its column inside its overlap group. [span] counts
 * its own column plus the following columns that no block overlapping it uses, so the card can widen into free room.
 */
private class TimelineBlock(val event: CalendarEvent, val start: Int, val end: Int, val realEnd: Int = end) {
    var col = 0
    var span = 1

    /**
     * Day only: every event that shares one compact card with this one, in time order and including this one, or
     * null when the event has its own card. All members share one lane, one painted [end] and one [span].
     */
    var group: List<TimelineBlock>? = null
}

/** The event whose title a compact group shows: the longest real event, the earliest one when they tie. */
private fun timelineGroupHead(group: List<TimelineBlock>): TimelineBlock = group.maxWith(compareBy<TimelineBlock> { it.realEnd - it.start })

/**
 * A run of events that overlap each other on screen, directly or through a chain. They share the day width in
 * [cols] columns. [start] and [end] are the minutes the run covers on screen.
 */
private class TimelineCluster(val blocks: List<TimelineBlock>, val cols: Int) {
    val start: Int = blocks.minOf { it.start }
    val end: Int = blocks.maxOf { it.end }
}

private val timelineOrder = compareBy<TimelineBlock> { it.start }.thenByDescending { it.end }.thenBy { it.event.title.lowercase() }

/**
 * Lane assignment uses real overlaps. Short events in a lane share a card if the title line still occupies it.
 * [readableLabels] lets that line extend past midnight when the strip shows one day.
 */
private fun packTimeline(events: List<CalendarEvent>, day: LocalDate, zone: ZoneId, minMinutes: Int, readableLabels: Boolean): List<TimelineCluster> {
    val blocks = events.map { e ->
        val start = minuteOn(e.startMillis, day, zone)
        val end = minuteOn(max(e.endMillis, e.startMillis), day, zone)
        TimelineBlock(e, start, max(end, start + 1).coerceAtMost(DAY_MINUTES))
    }.sortedWith(timelineOrder)
    return readableClusters(blocks, minMinutes.coerceAtLeast(1), if (readableLabels) Int.MAX_VALUE else DAY_MINUTES)
}

/**
 * Each event goes in a lane that is free at its real start. A lane whose card still shows its title line is used
 * only when no lane is free on screen, and then the event joins that card instead of opening a lane the real times
 * do not need. A new lane opens only when every lane holds an event that really overlaps. Cards end at [limit].
 */
private fun readableClusters(blocks: List<TimelineBlock>, row: Int, limit: Int): List<TimelineCluster> {
    class Card(val col: Int, first: TimelineBlock) {
        val members = mutableListOf(first)
        var realEnd = first.end
        val start get() = members.first().start
        val end get() = minOf(max(start + row, realEnd), maxOf(limit, realEnd))
        var span = 1
    }
    val clusters = mutableListOf<TimelineCluster>()
    val cards = mutableListOf<Card>()
    val lanes = mutableListOf<Card>()
    var clusterEnd = -1
    fun close() {
        if (cards.isEmpty()) return
        val cols = lanes.size
        cards.forEach { card ->
            while (card.col + card.span < cols &&
                cards.none { it.col == card.col + card.span && it.start < card.end && card.start < it.end }) card.span++
        }
        val shown = cards.flatMap { card ->
            val members = card.members.map { b ->
                TimelineBlock(b.event, b.start, card.end, b.end).also { it.col = card.col; it.span = card.span }
            }
            if (members.size > 1) members.forEach { it.group = members }
            members
        }.sortedWith(timelineOrder)
        clusters += TimelineCluster(shown, cols)
        cards.clear()
        lanes.clear()
    }
    for (b in blocks) {
        if (cards.isNotEmpty() && b.start >= clusterEnd) close()
        val free = lanes.indices.filter { lanes[it].realEnd <= b.start }
        val open = free.firstOrNull { lanes[it].end <= b.start }
        val card = when {
            open != null -> Card(open, b).also { lanes[open] = it; cards += it }
            free.isNotEmpty() -> lanes[free.minBy { lanes[it].end }].also { it.members += b; it.realEnd = max(it.realEnd, b.end) }
            else -> Card(lanes.size, b).also { lanes += it; cards += it }
        }
        clusterEnd = max(clusterEnd, card.end)
    }
    close()
    return clusters
}

/**
 * How one overlap group shares a day column: [shown] event lanes of width [lane], then [chooser] width for "+N".
 * [stacked] means not even two lanes fit, so the whole group is one card that lists its events.
 */
private class OverflowLanes(val shown: Int, val lane: Dp, val chooser: Dp) {
    val stacked: Boolean get() = shown == 0
}

/**
 * Lanes that fit at [minLane] each. When the group needs more, the last lane becomes the chooser. When a column fits
 * only one lane, side-by-side slivers would show a letter or two, so the group becomes one full-width stacked card.
 */
private fun overflowLanes(width: Dp, minLane: Dp, cols: Int): OverflowLanes {
    val fit = (width / minLane).toInt().coerceAtLeast(1)
    if (cols <= fit) return OverflowLanes(cols, width / cols, 0.dp)
    if (fit >= 2) return OverflowLanes(fit - 1, width / fit, width / fit)
    return OverflowLanes(0, 0.dp, width)
}

/**
 * True for a narrow card that shares its day column with other cards. Its title and time are centered, so a title
 * that wraps to two short lines stays balanced. Cards that use the whole column keep start aligned text.
 */
private fun timelineCentersLabel(card: Dp, column: Dp): Boolean = card < column - 1.dp && card < 96.dp

@Composable
internal fun AllDayChip(
    event: CalendarEvent, day: LocalDate, f: Formats, highlight: Boolean, motion: Boolean, viewportHeight: Dp, onClick: () -> Unit,
) {
    val context = LocalContext.current
    val title = event.withTodoMark(event.displayTitle().ifBlank { stringResource(R.string.untitled) })
    val description = event.spokenDescription(f, context.timeLabel(event, day, ZoneId.systemDefault(), f), includeLocation = false)
    val colors = eventColors(event)
    val shape = EventShape
    val morph = LocalAgendaMorph.current
    val typography = MaterialTheme.typography
    val style = if (morph != null) typography.dayEventTitle(f) else typography.labelLarge.copy(
        fontWeight = FontWeight.SemiBold, hyphens = Hyphens.Auto, lineBreak = LineBreak.Paragraph,
        localeList = LocaleList(f.locale.toLanguageTag()))
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    val lineHeight = remember(style, density) { measurer.measure("Ag", style, maxLines = 1).size.height.coerceAtLeast(1) }
    val maxLines = with(density) { ((viewportHeight - 12.dp).toPx() / lineHeight).toInt().coerceIn(1, 4) }
    val endpoint = morph?.endpoint(event, day, inList = false, EVENT_CORNER)
    val bounds = Modifier.fillMaxWidth().heightIn(min = 48.dp)
    val card = if (endpoint == null) bounds else morph.card(bounds, endpoint, day)
    Box(
        card.clip(shape).background(colors.container)
            .then(if (event.exam() == null) Modifier else Modifier.drawBehind { drawExamFlag(colors.content, EXAM_FLAG.toPx()) })
            .focusRing(highlight, colors.content, shape, motion)
            .calendarTap(colors.content, onClick = onClick)
            .clearAndSetSemantics { contentDescription = description; role = Role.Button; onClick { onClick(); true } }
            .padding(horizontal = 6.dp, vertical = 4.dp),
        contentAlignment = Alignment.CenterStart,
    ) {
        AgendaCardText(title, colors.content, style, endpoint, "title", maxLines = maxLines)
    }
}

@Composable
internal fun DayColumn(
    day: LocalDate, events: List<CalendarEvent>, zone: ZoneId, minMinutes: Int, minBlockWidth: Dp,
    f: Formats, compact: CompactTimes, style: TextStyle, highlighted: String?, motion: Boolean, modifier: Modifier,
    onOpen: (CalendarEvent) -> Unit, onNew: (LocalDate, Int?) -> Unit, onOverflow: (List<CalendarEvent>) -> Unit,
    axis: TimelineAxis,
    readableLabels: Boolean,
    minLabelHeight: Dp,
    /** Today's minute, so a class in progress can mark the current time inside its card. */
    clock: State<Int>? = null,
) {
    val addLabel = stringResource(R.string.action_add_event)
    val clusters = remember(events, day, zone, minMinutes, readableLabels) { packTimeline(events, day, zone, minMinutes, readableLabels) }
    val density = LocalDensity.current
    fun y(minute: Int) = axis.minuteToY(minute) +
        if (readableLabels) (minute - DAY_MINUTES).coerceAtLeast(0) / 60f * axis.hourPx else 0f
    val interaction = remember { MutableInteractionSource() }
    val keyboardFocused by interaction.collectIsFocusedAsState()
    val focusColor = MaterialTheme.colorScheme.onSurface
    val create by rememberUpdatedState(onNew)
    var tappedHour by remember(day) { mutableStateOf<Int?>(null) }
    LaunchedEffect(tappedHour) {
        if (tappedHour != null) {
            delay(120)
            tappedHour = null
        }
    }
    BoxWithConstraints(
        modifier.then(if (keyboardFocused) Modifier.border(2.dp, focusColor, RoundedCornerShape(6.dp)) else Modifier),
    ) {
        // Event cards take taps above this layer. Keep the clickable action for keyboard and accessibility.
        Box(Modifier.matchParentSize()
            .drawBehind {
                tappedHour?.let { drawTimelineTile(axis, it, focusColor.copy(alpha = 0.22f)) }
            }
            .clickable(interactionSource = interaction, indication = null, onClickLabel = addLabel, role = Role.Button) { create(day, null) }
            .pointerInput(day, axis) {
                detectTapGestures { position ->
                    val hour = axis.hourAt(position.y)
                    tappedHour = hour
                    create(day, hour)
                }
            })
        val context = LocalContext.current
        fun height(start: Int, end: Int) =
            maxOf(minLabelHeight, with(density) { (y(end) - y(start)).toDp() })
        clusters.forEach { cluster ->
            val lanes = overflowLanes(maxWidth, minBlockWidth, cluster.cols)
            if (lanes.stacked) {
                // Packing sorts by start, then longest first, so the card starts with the event that opens the group.
                val first = cluster.blocks.first()
                val start = cluster.start
                val end = cluster.end
                EventBlock(
                    first.event, day, zone, f, compact, style, cluster.blocks.any { highlighted == it.event.key() }, motion, end - start,
                    Modifier.offset(y = with(density) { y(start).toDp() }).width(maxWidth).height(height(start, end)),
                    more = cluster.blocks.drop(1).map { it.event }, keepCount = readableLabels, clock = clock,
                ) { onOverflow(cluster.blocks.map { it.event }) }
                return@forEach
            }
            val width = lanes.lane
            val hidden = mutableListOf<TimelineBlock>()
            cluster.blocks.forEach { b ->
                val group = b.group
                if (b.col >= lanes.shown) {
                    hidden += b
                } else if (group == null || b === group.first()) {
                    // A compact group is one card at its first real start that names its longest event and counts the rest.
                    val head = group?.let(::timelineGroupHead) ?: b
                    val start = b.start
                    val cardWidth = width * b.span.coerceAtMost(lanes.shown - b.col)
                    EventBlock(
                        head.event, day, zone, f, compact, style, (group ?: listOf(b)).any { highlighted == it.event.key() }, motion, b.end - start,
                        Modifier.offset(x = width * b.col, y = with(density) { y(start).toDp() })
                            .width(cardWidth).height(height(start, b.end)),
                        more = group.orEmpty().filter { it !== head }.map { it.event },
                        centered = timelineCentersLabel(cardWidth, maxWidth),
                        originMillis = b.event.startMillis, keepCount = group != null, clock = clock,
                    ) { if (group == null) onOpen(b.event) else onOverflow(group.map { it.event }) }
                }
            }
            if (hidden.isNotEmpty()) {
                val start = hidden.minOf { it.start }
                val end = hidden.maxOf { it.end }
                val only = hidden.singleOrNull()?.event
                OverflowBlock(
                    hidden.size,
                    only?.let { it.spokenDescription(f, context.timeLabel(it, day, zone, f)) },
                    Modifier.offset(x = maxWidth - lanes.chooser, y = with(density) { y(start).toDp() })
                        .width(lanes.chooser).height(height(start, end)),
                    singleTodo = only?.todo == true,
                ) { if (hidden.size == 1) onOpen(hidden.single().event) else onOverflow(hidden.map { it.event }) }
            }
        }
    }
}

/**
 * Grouped events share a card whose side rail marks their real times relative to [originMillis].
 * [keepCount] lets the count move onto the title line when the card cannot fit a separate count.
 */
@Composable
private fun EventBlock(
    event: CalendarEvent, day: LocalDate, zone: ZoneId, f: Formats, compact: CompactTimes, style: TextStyle,
    highlight: Boolean, motion: Boolean, shownMinutes: Int, modifier: Modifier,
    more: List<CalendarEvent> = emptyList(), centered: Boolean = false, originMillis: Long = event.startMillis,
    keepCount: Boolean = false, clock: State<Int>? = null, onClick: () -> Unit,
) {
    val context = LocalContext.current
    val typography = MaterialTheme.typography
    val title = event.withTodoMark(event.displayTitle().ifBlank { stringResource(R.string.untitled) })
    val ownExam = event.exam() != null
    // A stack is flagged when any of its events is an exam. Only its first event names it, since that is the one it shows.
    val exam = ownExam || more.any { it.exam() != null }
    val status = listOfNotNull(stringResource(R.string.exam_tag).takeIf { ownExam }, absenceStatus(event, f, compact))
        .takeIf { it.isNotEmpty() }?.joinToString("\n")
    val ownDescription = event.spokenDescription(f, context.timeLabel(event, day, zone, f))
    val description = if (more.isEmpty()) ownDescription else {
        val count = more.size + 1
        val descriptions = listOf(ownDescription) + more.map { it.spokenDescription(f, context.timeLabel(it, day, zone, f)) }
        context.resources.getQuantityString(R.plurals.event_count, count, count) + ": " + descriptions.joinToString("; ")
    }
    val colors = eventColors(event)
    // A stack's rail shows every event of the group, the first one included, since the card itself spans the group.
    val railEvents = if (more.isEmpty()) emptyList() else listOf(event) + more
    val markColors = railEvents.map { eventAccent(it) }
    val cardBackground = MaterialTheme.colorScheme.background
    val shape = EventShape
    val origin = maxOf(originMillis, day.atStartOfDay(zone).toInstant().toEpochMilli())
    fun fraction(millis: Long) = ((millis - origin) / (shownMinutes.coerceAtLeast(1) * 60_000f)).coerceIn(0f, 1f)
    val missed = remember(event, origin, shownMinutes) {
        event.partialAbsences().map { (start, end) -> fraction(start) to fraction(end) }.filter { it.second > it.first }
    }
    // Each event of a stack sits on a rail at the end edge, at its real time inside the card.
    val marks = remember(railEvents, origin, shownMinutes) {
        railEvents.map { fraction(it.startMillis) to fraction(maxOf(it.endMillis, it.startMillis)) }
    }
    // How far down the card the current time is, while the class runs.
    val now = clock?.let { (day.atStartOfDay(zone).toInstant().toEpochMilli() + it.value * 60_000L - origin) / (shownMinutes.coerceAtLeast(1) * 60_000f) }
        ?.takeIf { it > 0f && it < 1f }
    val nowColor = remember(colors) { nowMark(colors) }
    val canceled = event.canceled
    val rail = 3.dp
    val held = LocalTimelineHeld.current
    val heldSize = remember { IntArray(2) }
    val measurer = rememberTextMeasurer()
    val configuration = LocalViewConfiguration.current
    val cardConfiguration = remember(configuration) {
        object : ViewConfiguration by configuration {
            // Expanding a narrow card's touch area over its neighbours would hide their accessibility nodes.
            override val minimumTouchTargetSize = DpSize.Zero
        }
    }
    val inset = modifier.padding(TIMELINE_BLOCK_INSET)
    val morph = LocalAgendaMorph.current
    val endpoint = if (more.isEmpty()) morph?.endpoint(event, day, inList = false, EVENT_CORNER) else null
    if (endpoint != null) SideEffect { endpoint.nowLine = now?.let { AgendaNowLine(it, nowColor) } }
    val card = if (morph != null && endpoint != null) morph.card(inset, endpoint, day) else inset
    CompositionLocalProvider(LocalViewConfiguration provides cardConfiguration) {
        BoxWithConstraints(
            card.clip(shape).background(colors.container)
                .then(if (now == null) Modifier else Modifier.drawBehind {
                    drawNowBar(nowColor, now * size.height)
                })
                .then(if (!canceled && missed.isEmpty() && marks.isEmpty() && !exam) Modifier else Modifier.drawBehind {
                    val rtl = layoutDirection == LayoutDirection.Rtl
                    // A canceled class is barred across, and a partly canceled one only over the time it loses.
                    if (canceled) drawCanceledBars(colors.content)
                    else missed.forEach { (from, to) ->
                        drawCanceledBars(colors.content, Offset(0f, from * size.height), Size(size.width, (to - from) * size.height))
                    }
                    if (exam) drawExamFlag(colors.content, EXAM_FLAG.toPx(),
                        end = size.width - if (marks.isEmpty()) 0f else rail.toPx() + 1.dp.toPx())
                    if (marks.isNotEmpty()) {
                        val railWidth = rail.toPx()
                        val gap = 1.dp.toPx()
                        drawRect(cardBackground, Offset(if (rtl) 0f else size.width - railWidth - gap, 0f), Size(railWidth + gap, size.height))
                        val minHeight = 2.dp.toPx()
                        marks.forEachIndexed { i, (from, to) ->
                            val top = (from * size.height).coerceAtMost(size.height - minHeight)
                            drawRect(markColors[i], Offset(if (rtl) 0f else size.width - railWidth, top),
                                Size(railWidth, maxOf(minHeight, (to - from) * size.height)))
                        }
                    }
                })
                .focusRing(highlight, colors.content, shape, motion)
                .calendarTap(colors.content, onClick = onClick)
                .clearAndSetSemantics { contentDescription = description; role = Role.Button; onClick { onClick(); true } }
                .holdLayout(held, heldSize),
        ) {
            val density = LocalDensity.current
            val wide = maxWidth >= 96.dp
            val narrow = maxWidth < 48.dp
            val padV = if (maxHeight < 28.dp) 1.dp else TIMELINE_LABEL_PADDING
            val innerHeight = with(density) { (maxHeight.roundToPx() - padV.roundToPx() * 2).toFloat() }
            // Narrow week cards drop the label letter spacing, which is about one extra letter per line at the same size.
            val baseStyle = when {
                endpoint != null -> typography.dayEventTitle(f)
                narrow -> style.copy(letterSpacing = 0.sp)
                else -> style
            }
            val preferredStyle = if (wide && endpoint == null) typography.bodyMedium else baseStyle
            val preferredHeight = remember(preferredStyle) {
                measurer.measure("Ag", preferredStyle.copy(fontWeight = FontWeight.SemiBold), maxLines = 1, softWrap = false).size.height
            }
            val titleStyle = (if (innerHeight >= preferredHeight) preferredStyle else baseStyle)
                .copy(fontWeight = FontWeight.SemiBold, hyphens = Hyphens.Auto, lineBreak = LineBreak.Paragraph,
                    localeList = LocaleList(f.locale.toLanguageTag()))
            val detailStyle = if (wide) typography.bodySmall else typography.labelSmall
            val statusStyle = detailStyle.copy(fontWeight = FontWeight.Bold)
            val padH = when {
                narrow -> 3.dp
                maxWidth < 72.dp -> 4.dp
                maxWidth < 160.dp -> 8.dp
                else -> 12.dp
            }
            val padStart = padH
            val padEnd = if (marks.isEmpty()) padH else rail + 1.dp + padH.coerceAtMost(4.dp)
            // Match Modifier.padding, which rounds each inset before subtracting it from the pixel constraints.
            val innerWidth = with(density) { (maxWidth.roundToPx() - padStart.roundToPx() - padEnd.roundToPx()).coerceAtLeast(1) }
            fun measureText(text: String, textStyle: TextStyle) = measurer.measure(text, textStyle, constraints = Constraints(maxWidth = innerWidth))
            val moreText = "+${more.size}"
            val countGap = with(density) { 4.dp.roundToPx() }
            val countSize = remember(more.size, statusStyle) {
                if (more.isEmpty()) IntSize.Zero else measurer.measure(moreText, statusStyle, maxLines = 1, softWrap = false).size
            }
            val oneTitleLine = remember(titleStyle) { measurer.measure("Ag", titleStyle, maxLines = 1, softWrap = false).size.height }
            val minTitle = with(density) { timelineMinLaneWidth(titleStyle.fontSize.toDp()).roundToPx() }
            // A Day group ([keepCount]) puts its count on the first title line when the title keeps a readable start
            // beside it, and also when a line of its own would not fit, so it never loses its count. Week groups keep
            // the count on its own line below the title.
            val countInline = keepCount && more.isNotEmpty() &&
                (innerWidth - countSize.width - countGap >= minTitle || innerHeight < oneTitleLine + countSize.height)
            val titleWidth = if (countInline) (innerWidth - countSize.width - countGap).coerceAtLeast(1) else innerWidth
            val titleLayout = remember(title, titleStyle, titleWidth) {
                measurer.measure(title, titleStyle, constraints = Constraints(maxWidth = titleWidth))
            }
            fun wholeField(text: String, textStyle: TextStyle) = measureText(text, textStyle).takeUnless { it.hasVisualOverflow }
            val moreLayout = remember(more.size, statusStyle, innerWidth, countInline) {
                if (more.isEmpty() || countInline) null else wholeField(moreText, statusStyle)
            }
            // The count of a Week group reads as a badge beside the title's hierarchy, never as a line of the time.
            val badgePad = with(density) { 5.dp.roundToPx() }
            val countBadge = moreLayout != null && moreLayout.size.width + badgePad * 2 <= innerWidth
            val statusLayout = remember(status, statusStyle, innerWidth) { status?.let { wholeField(it, statusStyle) } }
            var left = innerHeight.coerceAtLeast(0f)
            fun take(lines: Int, height: Float): Int {
                val line = height / lines.coerceAtLeast(1)
                val n = minOf(lines, (left / line).toInt()).coerceAtLeast(0)
                left -= n * line
                return n
            }
            fun takeWhole(height: Int): Boolean {
                if (height > left) return false
                left -= height
                return true
            }
            val titleLine = titleLayout.size.height.toFloat() / titleLayout.lineCount.coerceAtLeast(1)
            val firstTitle = take(1, titleLine)
            val showMore = firstTitle > 0 && moreLayout?.let { takeWhole(it.size.height) } == true
            val showStatus = firstTitle > 0 && statusLayout?.let { takeWhole(it.size.height) } == true
            val priorityTitle = if (wide) 0 else minOf(2, titleLayout.lineCount - firstTitle)
            var titleLines = firstTitle + take(priorityTitle, titleLine * priorityTitle)
            titleLines += take(titleLayout.lineCount - titleLines, titleLine * (titleLayout.lineCount - titleLines))
            // A narrow lane beside other cards centers its lines, so a wrapped title reads as one balanced label.
            val align = if (centered) TextAlign.Center else TextAlign.Start
            Column(Modifier.fillMaxSize().padding(start = padStart, end = padEnd, top = padV, bottom = padV),
                verticalArrangement = Arrangement.Center) {
                if (countInline) {
                    Row(Modifier.fillMaxWidth()) {
                        if (titleLines > 0) AgendaCardText(title, colors.content, titleStyle, null, "title", Modifier.weight(1f).alignByBaseline(),
                            textAlign = align, maxLines = titleLines)
                        else Spacer(Modifier.weight(1f))
                        Spacer(Modifier.width(4.dp))
                        Text(moreText, Modifier.alignByBaseline(), color = colors.content, style = statusStyle, maxLines = 1, softWrap = false)
                    }
                } else if (titleLines > 0) AgendaCardText(title, colors.content, titleStyle, endpoint, "title", Modifier.fillMaxWidth(),
                    textAlign = align, maxLines = titleLines)
                if (showMore) Box(Modifier.fillMaxWidth(), contentAlignment = if (centered) Alignment.Center else Alignment.CenterStart) {
                    Text(moreText, if (!countBadge) Modifier else Modifier
                        .background(colors.content.copy(alpha = 0.16f), CircleShape).padding(horizontal = 5.dp),
                        color = colors.content, style = statusStyle, maxLines = 1, softWrap = false, overflow = TextOverflow.Clip)
                }
                if (showStatus && status != null) AgendaCardText(status, colors.content, statusStyle, endpoint, "status", Modifier.fillMaxWidth(),
                    textAlign = align, maxLines = statusLayout!!.lineCount)

            }
        }
    }
}

@Composable
private fun OverflowBlock(count: Int, single: String?, modifier: Modifier, singleTodo: Boolean = false, onClick: () -> Unit) {
    val p = MaterialTheme.colorScheme
    val label = single ?: LocalContext.current.resources.getQuantityString(R.plurals.event_count, count, count)
    val style = MaterialTheme.typography.labelMedium
    val measurer = rememberTextMeasurer()
    BoxWithConstraints(
        modifier.padding(TIMELINE_BLOCK_INSET).clip(EventShape)
            .border(1.dp, p.outlineVariant, EventShape).background(p.surfaceContainerLow)
            .calendarTap(p.onSurface, onClick = onClick)
            .clearAndSetSemantics { contentDescription = label; role = Role.Button; onClick { onClick(); true } },
        contentAlignment = Alignment.Center,
    ) {
        val full = if (singleTodo) "☐ +$count" else "+$count"
        val room = with(LocalDensity.current) { (maxWidth - 2.dp).roundToPx() }
        val fits = remember(full, style, room) { measurer.measure(full, style, maxLines = 1, softWrap = false).size.width <= room }
        Text(if (fits) full else if (singleTodo) "☐" else "+", color = p.onSurface, style = style, maxLines = 1, softWrap = false)
    }
}

/**
 * A deeper, more saturated shade of the card's own hue, toward its text's lightness. It stands out on the card in any
 * colour or theme without matching the neutral text.
 */
private fun nowMark(colors: EventColors): Color {
    val hsv = FloatArray(3)
    android.graphics.Color.colorToHSV(colors.container.toArgb(), hsv)
    hsv[1] = (hsv[1] * 2f).coerceIn(0.55f, 1f)
    hsv[2] = if (colors.content.luminance() < colors.container.luminance()) hsv[2] * 0.62f else (hsv[2] * 1.6f).coerceAtMost(1f)
    return Color(android.graphics.Color.HSVToColor(hsv))
}

/** Whether a pinch is resizing the timeline, so its cards reveal their text rather than reshuffle it. */
internal val LocalTimelineHeld = compositionLocalOf { false }

/** While pinching, keep the previous height as a minimum so shrinking clips the existing lines. */
private fun Modifier.holdLayout(held: Boolean, last: IntArray): Modifier = layout { measurable, constraints ->
    if (!held || last[0] == 0) {
        val placeable = measurable.measure(constraints)
        last[0] = placeable.width
        last[1] = placeable.height
        layout(placeable.width, placeable.height) { placeable.place(0, 0) }
    } else {
        val width = constraints.maxWidth
        val placeable = measurable.measure(Constraints.fixed(width, maxOf(last[1], constraints.maxHeight)))
        val height = constraints.constrainHeight(placeable.height)
        // The label is centred in the card, so the taller layout is centred on it too and trims evenly from both ends.
        layout(constraints.constrainWidth(placeable.width), height) { placeable.place(0, (height - placeable.height) / 2) }
    }
}
