package io.github.sunsind.sunlith.ui.calendar

import androidx.compose.animation.core.animate
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import io.github.sunsind.sunlith.R
import io.github.sunsind.sunlith.data.CalendarEvent
import io.github.sunsind.sunlith.data.key
import io.github.sunsind.sunlith.data.span
import io.github.sunsind.sunlith.ui.Formats
import io.github.sunsind.sunlith.ui.LocalPalette
import io.github.sunsind.sunlith.ui.Screen
import io.github.sunsind.sunlith.ui.SunlithViewModel
import io.github.sunsind.sunlith.ui.ViewMode
import io.github.sunsind.sunlith.ui.gridStart
import io.github.sunsind.sunlith.ui.weekStart
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import kotlin.math.roundToInt
import kotlinx.coroutines.delay

private const val GRID_ROWS = 6

@Composable
internal fun DayBody(c: CalendarContext, modifier: Modifier, dayReturn: TodaySlide) {
    val vm = c.vm
    val background = LocalPalette.current.background
    var shown by remember { mutableStateOf(vm.timelineStartFor(ViewMode.Day)?.takeUnless { vm.calendarExpanded } ?: vm.selectedDateFor(ViewMode.Day)) }
    val expanded = vm.calendarExpanded
    if (vm.viewMode == ViewMode.Day) {
        val timelineToday = vm.timelineNavigationRequest != vm.handledTimelineNavigation &&
            vm.isTimelineToday(ViewMode.Day, vm.timelineNavigationRequest)
        shown = when {
            if (expanded) c.swipe.goingToday || (dayReturn.isRunning && vm.selectedDate == c.today)
            else timelineToday && c.todayGridReady -> c.today
            else -> c.visibleDay
        }
    }
    val rowPx = remember { floatArrayOf(1f) }
    var progress by remember { mutableFloatStateOf(if (expanded) 1f else 0f) }
    var dragging by remember { mutableStateOf(false) }
    var browseMonths by remember { mutableStateOf(expanded) }
    var releaseSpeed by remember { mutableFloatStateOf(0f) }
    val opening by remember { derivedStateOf { progress > 0f } }
    val monthVisible = expanded || opening
    val timelineVisible by remember { derivedStateOf { progress < 1f } }
    LaunchedEffect(expanded, dragging) {
        if (dragging) return@LaunchedEffect
        val target = if (expanded) 1f else 0f
        val speed = releaseSpeed
        releaseSpeed = 0f
        val remaining = target - progress
        val limit = 12f * kotlin.math.abs(remaining)
        val carried = if (speed * remaining > 0f) speed.coerceIn(-limit, limit) else 0f
        if (c.motion && remaining != 0f) animate(progress, target,
            initialVelocity = carried,
            animationSpec = spring(stiffness = 300f, visibilityThreshold = 0.001f),
        ) { value, _ -> progress = value.coerceIn(0f, 1f) }
        progress = target
        browseMonths = expanded
    }
    val drag = rememberDraggableState { delta ->
        val travel = rowPx[0] * (GRID_ROWS - 1)
        progress = (progress + delta / travel).coerceIn(0f, 1f)
    }
    val settlePull: (Float) -> Unit = { velocity ->
        vm.changeDayCalendarExpansion(when {
            velocity > 600f -> true
            velocity < -600f -> false
            else -> progress > 0.5f
        })
        releaseSpeed = velocity / (rowPx[0] * (GRID_ROWS - 1))
        dragging = false
    }
    val pull = Modifier.draggable(drag, Orientation.Vertical,
        onDragStarted = { dragging = true },
        onDragStopped = { settlePull(it) })
    val latestSettle by rememberUpdatedState(settlePull)
    val fromAllDay = remember(drag) {
        object : NestedScrollConnection {
            private var pulling = false

            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                if (!pulling || source != NestedScrollSource.UserInput) return Offset.Zero
                drag.dispatchRawDelta(available.y)
                return Offset(0f, available.y)
            }

            override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
                // A tall band can scroll normally until it reaches the top and hands the downward drag to the picker.
                if (source != NestedScrollSource.UserInput || available.y <= 0f) return Offset.Zero
                pulling = true
                dragging = true
                drag.dispatchRawDelta(available.y)
                return Offset(0f, available.y)
            }

            override suspend fun onPreFling(available: Velocity): Velocity {
                if (!pulling) return Velocity.Zero
                pulling = false
                latestSettle(available.y)
                return Velocity(0f, available.y)
            }
        }
    }
    val selectDate = if (expanded) vm::selectAndFocus else vm::select
    val pickerLabel = stringResource(R.string.calendar_picker)
    val toggleLabel = stringResource(if (expanded) R.string.calendar_show_week else R.string.calendar_show_month)
    val pickerState = stringResource(if (expanded) R.string.calendar_expanded else R.string.calendar_collapsed)
    val morph = remember { AgendaMorph { progress } }
    Column(dayReturn.drawOn(modifier)) {
        Column(pull.fillMaxWidth().semantics {
            contentDescription = pickerLabel
            stateDescription = pickerState
            customActions = listOf(CustomAccessibilityAction(toggleLabel) {
                vm.changeDayCalendarExpansion(!expanded)
                true
            })
        }) {
            Box(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(bottom = 4.dp)) {
                    MonthDatePicker(c, shown, browseMonths, monthVisible, dragging || browseMonths != expanded,
                        animatePeriodChanges = !dayReturn.isRunning, { progress }, rowPx, selectDate)
                }
                Box(Modifier.align(Alignment.BottomCenter).padding(bottom = 1.dp).width(22.dp).height(2.dp)
                    .background(LocalPalette.current.faint.copy(alpha = 0.7f), CircleShape))
            }
        }
        CompositionLocalProvider(LocalAgendaMorph provides morph) {
            Box(morph.container(Modifier.weight(1f).fillMaxWidth().graphicsLayer {
                val growth = rowPx[0] * (GRID_ROWS - 1) * progress
                translationY = growth - growth.roundToInt()
            }.clipToBounds().background(background), seamColor = background)) {
                if (!expanded || timelineVisible || dragging) {
                    ContinuousTimeline(c, Modifier.fillMaxSize().dayViewport(rowPx, inList = false) { progress }.agendaLayer(!expanded) { 1f - progress },
                        focused = !expanded, allDayModifier = Modifier.nestedScroll(fromAllDay))
                }
                if (monthVisible || dragging) {
                    val date = if (expanded && vm.viewMode == ViewMode.Day) vm.selectedDate else shown
                    PagedPeriod(c.swipe, date.toEpochDay(),
                        Modifier.fillMaxSize().dayViewport(rowPx, inList = true) { progress }
                            .agendaLayer(expanded) { progress },
                        userScrollEnabled = expanded && vm.viewMode == ViewMode.Day,
                        commitWhenSettled = true,
                    ) { epoch ->
                        val day = LocalDate.ofEpochDay(epoch)
                        DayCards(c, day, expanded && day == vm.selectedDate && vm.viewMode == ViewMode.Day)
                    }
                }
            }
        }
    }
}

private fun Modifier.foldCalendarRow(progress: () -> Float, foldIndex: Int): Modifier =
    clipToBounds().layout { measurable, constraints ->
        val row = measurable.measure(constraints)
        val grown = row.height * progress()
        // Round the cumulative height so the events below advance one pixel at a time, rather than five.
        val height = (grown * (foldIndex + 1)).roundToInt() - (grown * foldIndex).roundToInt()
        layout(row.width, height) {
            row.placeWithLayer(0, 0) { alpha = progress() }
        }
    }

@Composable
private fun MonthDatePicker(
    c: CalendarContext, shown: LocalDate, browseMonths: Boolean, monthVisible: Boolean, folding: Boolean,
    animatePeriodChanges: Boolean, progress: () -> Float, rowPx: FloatArray, onSelect: (LocalDate) -> Unit,
) {
    val month = YearMonth.from(shown)
    val period = if (browseMonths) month.year * 12L + month.monthValue - 1 else Math.floorDiv(weekStart(shown).toEpochDay(), 7L)
    fun dateAt(absolute: Long): LocalDate = if (!browseMonths) shown.plusWeeks(absolute - period)
        else when (val m = YearMonth.of(Math.floorDiv(absolute, 12L).toInt(), Math.floorMod(absolute, 12L).toInt() + 1)) {
            month -> shown
            YearMonth.from(c.today) -> c.today
            else -> m.atDay(1)
        }
    val swipe = rememberPeriodSwipe(c.motion) { delta -> onSelect(dateAt(period + delta)) }
    WeekdayLabels(c.f)
    key(browseMonths) {
        PagedPeriod(swipe, period, wrap = true, userScrollEnabled = !folding, animatePeriodChanges = animatePeriodChanges) { absolute ->
            // Keep the outgoing page's selection as it slides away from the new date.
            val active = absolute == period
            var date by remember(absolute) { mutableStateOf(dateAt(absolute)) }
            if (active) date = shown
            MonthRows(c, YearMonth.from(date), date, browseMonths && !folding, monthVisible, progress, rowPx, onSelect)
        }
    }
}

/** Always six rows, so the open month keeps one height. Rows other than [selected]'s fold away as [progress] nears 0. */
@Composable
private fun MonthRows(
    c: CalendarContext, month: YearMonth, selected: LocalDate, expanded: Boolean,
    monthVisible: Boolean, progress: () -> Float, rowPx: FloatArray, onSelect: (LocalDate) -> Unit,
) {
    val start = remember(month) { gridStart(month) }
    val dates = remember(start) { List(GRID_ROWS * 7) { start.plusDays(it.toLong()) } }
    val eventsOn = rememberDayEvents(c.vm, ViewMode.Day, start, dates.last())
    val selectedRow = (selected.toEpochDay() - start.toEpochDay()).toInt().coerceIn(0, dates.lastIndex) / 7
    Column(Modifier.fillMaxWidth().padding(horizontal = 4.dp)) {
        repeat(GRID_ROWS) { r ->
            Row(Modifier.fillMaxWidth().then(
                if (r == selectedRow) {
                    Modifier.onSizeChanged { rowPx[0] = it.height.toFloat().coerceAtLeast(1f) }
                } else Modifier.then(if (expanded) Modifier else Modifier.clearAndSetSemantics {})
                    .foldCalendarRow(progress, foldIndex = if (r < selectedRow) r else r - 1),
            )) {
                repeat(7) { col ->
                    val day = dates[r * 7 + col]
                    key(day) {
                        val addEvent = expanded && day == selected
                        DayCell(
                            day, day == selected, day == c.today, !monthVisible || YearMonth.from(day) == month, eventsOn(day), c.f,
                            c.dayMeasurer, Modifier.weight(1f), { if (addEvent) c.vm.newEvent(it) else onSelect(it) },
                            addEvent = addEvent,
                            reveal = progress.takeIf { r != selectedRow },
                        )
                    }
                }
            }
        }
    }
}


// Measure each endpoint at its resting height. The outer viewport alone changes during the fold,
// so the scroll range and the cards used by the morph stay in place.
private fun Modifier.dayViewport(rowPx: FloatArray, inList: Boolean, progress: () -> Float): Modifier =
    layout { measurable, constraints ->
        val height = constraints.maxHeight
        val extraRows = GRID_ROWS - 1
        val growth = (rowPx[0] * extraRows * progress()).roundToInt()
        val fullGrowth = rowPx[0].roundToInt() * extraRows
        val endpoint = (height + growth - if (inList) fullGrowth else 0).coerceAtLeast(0)
        val page = measurable.measure(constraints.copy(minHeight = endpoint, maxHeight = endpoint))
        layout(page.width, height) { page.place(0, 0) }
    }

private fun Modifier.agendaLayer(active: Boolean, opacity: () -> Float): Modifier =
    zIndex(if (active) 1f else 0f)
        .graphicsLayer { alpha = opacity() }
        .then(if (active) Modifier else Modifier.clearAndSetSemantics {})

@Composable
private fun DayCards(c: CalendarContext, day: LocalDate, active: Boolean) {
    val vm = c.vm
    if (!vm.coversRange(day, day, ViewMode.Day)) {
        if (active && vm.loadError == null) Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator(Modifier.size(24.dp), color = LocalPalette.current.muted, strokeWidth = 2.dp)
        }
        return
    }
    val eventsOn = rememberDayEvents(vm, ViewMode.Day, day, day)
    key(day) { DayView(vm, day, eventsOn(day), c.f,
        focus = if (active) c.readyFocus else null,
        onFocusHandled = c.onFocusHandled, motion = c.motion) }
}

@Composable
private fun DayView(vm: SunlithViewModel, day: LocalDate, events: List<CalendarEvent>, f: Formats,
    focus: TimelineFocus?, onFocusHandled: (TimelineFocus) -> Unit, motion: Boolean,
) {
    val ordered = remember(events) { agendaOrder(events) }
    val zone = ZoneId.systemDefault()
    val focusEvent = remember(focus, events, day, zone) { focus?.let { focusTarget(events, day, zone) } }
    val firstRow = ordered.indexOfFirst { it.key() == focusEvent?.key() }
        .takeIf { it >= 0 && focusEvent?.isTimelineBanner() == false }
        ?: 0
    val listState = rememberLazyListState(initialFirstVisibleItemIndex = firstRow)
    val positionedCardFocus = remember { arrayOf<TimelineFocus?>(null) }
    if (focus != null && focus.date == day && focus !== positionedCardFocus[0]) SideEffect {
        positionedCardFocus[0] = focus
        listState.requestScrollToItem(firstRow)
    }
    var focusedEvent by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(focusedEvent) { if (focusedEvent != null) { delay(1800); focusedEvent = null } }
    LaunchedEffect(focus) {
        val request = focus ?: return@LaunchedEffect
        if (request.date != day) return@LaunchedEffect
        request.start()
        try {
            val target = focusTarget(events, day, zone)
            val index = ordered.indexOfFirst { it.key() == target?.key() }
            if (request.ring && index >= 0 && target?.isTimelineBanner() == false) {
                focusedEvent = target?.key()
            }
        } finally { onFocusHandled(request) }
    }
    AgendaEventList(ordered, day, f, onOpen = { vm.open(Screen.Details(it)) }, Modifier.fillMaxSize(), state = listState,
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp,
            bottom = TOOLBAR_CLEARANCE + WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()),
        focusedEvent = focusedEvent, motion = motion)
}

internal fun agendaOrder(events: List<CalendarEvent>): List<CalendarEvent> {
    val zone = ZoneId.systemDefault()
    return events.sortedBy { !it.allDay && !it.span(zone).multiDay }
}

@Composable
internal fun AgendaEventList(
    events: List<CalendarEvent>, day: LocalDate, f: Formats, onOpen: (CalendarEvent) -> Unit,
    modifier: Modifier = Modifier, state: LazyListState = rememberLazyListState(),
    contentPadding: PaddingValues = PaddingValues(horizontal = 16.dp, vertical = 4.dp),
    emptyLabel: String? = null, focusedEvent: String? = null, motion: Boolean = false,
) {
    val palette = LocalPalette.current
    LazyColumn(modifier, state = state, contentPadding = contentPadding, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (events.isEmpty()) item(key = "empty") {
            Text(emptyLabel ?: stringResource(R.string.no_events),
                Modifier.fillMaxWidth().padding(vertical = 20.dp), color = palette.muted,
                style = MaterialTheme.typography.bodyLarge, textAlign = TextAlign.Center)
        }
        items(events, key = { it.key() }) { event ->
            CalendarEventCard(event, day, f, onClick = { onOpen(event) },
                modifier = Modifier.focusRing(event.key() == focusedEvent, eventColors(event).content, EventShape, motion))
        }
    }
}
