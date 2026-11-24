package io.github.sunsind.sunlith.ui.calendar

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.DecayAnimationSpec
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.calculateTargetValue
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.rememberSplineBasedDecay
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.gestures.FlingBehavior
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.ScrollScope
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.scrollable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyListItemInfo
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.layout.LazyLayoutCacheWindow
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.GenericShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import io.github.sunsind.sunlith.R
import io.github.sunsind.sunlith.data.CalendarEvent
import io.github.sunsind.sunlith.data.key
import io.github.sunsind.sunlith.ui.LocalPalette
import io.github.sunsind.sunlith.ui.Screen
import io.github.sunsind.sunlith.ui.ViewMode
import io.github.sunsind.sunlith.ui.weekStart
import java.time.LocalDate
import java.time.ZoneId
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sign
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

private const val TIMELINE_CENTER = 1_000_000
private const val TIMELINE_DAYS = 2_000_001
/** How far the hours fade in below the dates and the all-day band. */
private val TIMELINE_SEAM = 14.dp

internal fun DrawScope.drawTimelineSeam(top: Float, color: Color, opacity: Float = 1f) {
    val height = TIMELINE_SEAM.toPx()
    drawRect(Brush.verticalGradient(0f to color.copy(alpha = 0.45f * opacity), 1f to color.copy(alpha = 0f),
        startY = top, endY = top + height), topLeft = Offset(0f, top), size = Size(size.width, height))
}

private data class WeekPinch(val index: Int, val offsetFraction: Float, val initialDays: Float)

private class TimelinePanAnchor(var index: Int, var fraction: Float = 0f) {
    fun record(index: Int, offset: Int, width: Int) {
        if (width <= 0) return
        this.index = index
        fraction = (offset.toFloat() / width).coerceIn(0f, 1f)
    }

    fun offset(width: Int): Int = (fraction * width).roundToInt()

    companion object {
        val Saver = listSaver<TimelinePanAnchor, Number>(
            save = { listOf(it.index, it.fraction) },
            restore = { TimelinePanAnchor(it[0].toInt(), it[1].toFloat()) },
        )
    }
}

/** One lazy day strip owns horizontal motion. The pinned bands project its item positions during layout. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun ContinuousTimeline(
    c: CalendarContext, modifier: Modifier, focused: Boolean = false, allDayModifier: Modifier = Modifier,
) {
    val vm = c.vm
    val f = c.f
    val today = c.today
    val motion = c.motion
    val density = LocalDensity.current
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    val p = LocalPalette.current
    // Day shows one day of the strip under its own week picker, with Day's larger titles and roomier cards.
    // The view this timeline belongs to, which stays put while it slides out for another one.
    val mode = LocalShownMode.current ?: vm.viewMode
    val active = mode == vm.viewMode && vm.continuousTimeline()
    val events = vm.eventsByDayFor(mode)
    val scroll = c.timelineScrolls.getValue(mode)
    val focus = if (focused) c.readyFocus else null
    val owner = remember(c.viewport, c.viewportScope) { Any() }
    DisposableEffect(c.viewport, owner) { onDispose { c.viewport.release(owner) } }
    val onDirectionChanged: (TodayDirection?) -> Unit = remember(c.viewport, c.viewportScope, owner, active) {
        { if (active) c.viewport.report(owner, it, c.viewportScope) }
    }
    val onOpen: (CalendarEvent) -> Unit = remember(vm) { { vm.open(Screen.Details(it)) } }
    val days = if (mode == ViewMode.Day) 1 else vm.visibleWeekDays
    var liveDays by remember { mutableStateOf<Float?>(null) }
    val spreadScope = rememberCoroutineScope()
    // A time zone change reloads the events, so the zone is read again with them. A new ZoneId on every composition
    // made each DayColumn recompose and pack its events again.
    val zone = remember(events) { ZoneId.systemDefault() }
    val origin = rememberSaveable { vm.selectedDateFor(mode).toEpochDay() }
    fun date(index: Int) = LocalDate.ofEpochDay(origin + index - TIMELINE_CENTER)
    fun index(day: LocalDate) = (TIMELINE_CENTER + day.toEpochDay() - origin).coerceIn(0L, TIMELINE_DAYS - 1L).toInt()
    // The start is read only on creation. The viewport it reads changes while the strip scrolls. A screen of days on
    // each side stays composed and measured, built while the strip rests, so a swipe within it builds no day mid-frame.
    val start = remember { index(vm.timelineStartFor(mode) ?: vm.selectedDateFor(mode)) }
    val row = rememberLazyListState(LazyLayoutCacheWindow(aheadFraction = 1f, behindFraction = 1f), start)
    val todaySlide = rememberTodaySlide()
    val decay = rememberSplineBasedDecay<Float>()
    // The all-day band's shown height, written as it lays out, so vertical snaps land just below it.
    val bandHeight = remember { mutableIntStateOf(0) }
    val agendaMorph = LocalAgendaMorph.current
    DisposableEffect(agendaMorph, bandHeight) {
        agendaMorph?.timelineBandHeight = bandHeight
        onDispose {
            if (agendaMorph?.timelineBandHeight === bandHeight) agendaMorph.timelineBandHeight = null
        }
    }
    val bandExtent = remember { mutableIntStateOf(0) }
    val snapScope = rememberCoroutineScope()
    val weekFling = with(density) { 350.dp.toPx() }
    val fling = remember(row, decay, mode, weekFling) {
        DaySnap(row, decay, snapScope, oneDay = mode == ViewMode.Day,
            weekStart = if (mode == ViewMode.Day) null else { i -> index(weekStart(date(i))) }, weekFling = weekFling)
    }
    val titleStyle = if (days == 1) MaterialTheme.typography.bodyMedium else MaterialTheme.typography.labelMedium
    val railStyle = MaterialTheme.typography.labelSmall
    val text = rememberTimelineText(f, titleStyle, railStyle)
    val headerMeasurer = rememberTextMeasurer(cacheSize = 64)
    val titleSize = text.titleSize
    val zoom = remember { TimelineZoom() }
    val liveZoom = zoom.live
    val hour = if (liveZoom != null) timelineHourHeight(liveZoom) else timelineHourHeight(vm.weekTimelineZoom)
    val gridColors = rememberTimelineGridColors()
    val hourPx = with(density) { hour.toPx() }
    // Packing follows the saved zoom, frozen while a pinch or its glide runs, so cards stay put until the scale has
    // settled.
    val minMinutes = text.minMinutes(zoom.frozen ?: vm.weekTimelineZoom)
    val edgePx = text.edgePx
    val edge = with(density) { edgePx.toDp() }
    val topPx = edgePx.toFloat()
    val minBlockWidth = timelineMinLaneWidth(titleSize)
    val compact = text.compact
    val labels = text.hourLabels
    val loadingLabel = stringResource(R.string.timeline_loading_day)
    var overflowEvents by remember { mutableStateOf(emptyList<CalendarEvent>()) }
    // Columns get the same list instances on every composition, so their packing stays remembered.
    val timed = remember(events) { events.mapValues { (_, list) -> list.filter { !it.isTimelineBanner() } } }
    val banners = remember(events) { events.mapValues { (_, list) -> list.filter { it.isTimelineBanner() } } }
    // Read only by the bands and effects, never here: this composition would otherwise rerun at every column.
    val visibleIndices = remember(row) { derivedStateOf {
        row.layoutInfo.visibleItemsInfo.filter { it.offset + it.size > 0 && it.offset < row.layoutInfo.viewportEndOffset }
            .map { it.index }.let { if (it.isEmpty()) row.firstVisibleItemIndex..row.firstVisibleItemIndex else it.min()..it.max() }
    } }
    // The band opens and sizes for days at least half on screen, so a sliver of a day at an edge does not open it.
    val bandIndices = remember(row, visibleIndices) { derivedStateOf {
        val info = row.layoutInfo
        info.visibleItemsInfo.filter { minOf(it.offset + it.size, info.viewportEndOffset) - maxOf(it.offset, 0) >= it.size / 2 }
            .map { it.index }.let { if (it.isEmpty()) visibleIndices.value else it.min()..it.max() }
    } }
    val axis = remember(hourPx) { TimelineAxis(hourPx) }
    val zoomAxis by rememberUpdatedState { percent: Float ->
        TimelineAxis(with(density) { timelineHourHeight(percent).toPx() })
    }
    val pinchScope = rememberCoroutineScope()
    val latestAxis by rememberUpdatedState(axis)
    // Vertical scrolling always settles on an hour line or an event start on the days in view, placed at the top of the
    // day below the all-day band, so its hour label stays whole.
    val snapPoints by rememberUpdatedState {
        val minutes = sortedSetOf<Int>()
        for (h in 0..24) minutes += h * 60
        visibleIndices.value.forEach { i ->
            val day = date(i)
            timed[day].orEmpty().forEach { minutes += minuteOn(it.startMillis, day, zone) }
        }
        minutes.map { timelineSnapScroll(latestAxis, it, bandHeight.intValue) }
    }
    val hourSnap = remember(decay, scroll, density) {
        HourSnap(decay, { snapPoints() }, { scroll.value }, { scroll.maxValue })
    }
    val reportDirection by rememberUpdatedState(onDirectionChanged)
    // One clock for the line, the Today direction and where Today opens. It is read only where today is used, and
    // never during composition, so a minute tick redraws today's column instead of recomposing the strip.
    val clock = LocalCurrentMinute.current
    // Today opens at the start of a class in progress, otherwise near the current time.
    val todayMinute by rememberUpdatedState {
        val minute = clock.value
        if (vm.loadError != null && !vm.loadingEvents) previousHour(minute)
        else timelineTodayMinute(vm.eventsByDay[today].orEmpty().filter { !it.isTimelineBanner() }, today, zone, System.currentTimeMillis(), minute)
    }
    LaunchedEffect(axis, today, clock, topPx, onDirectionChanged) {
        snapshotFlow {
            val visible = visibleIndices.value
            if (today in date(visible.first)..date(visible.last)) timelineTodayDirection(axis, clock.value, scroll.value, scroll.viewportSize, topPx) else null
        }.collect { reportDirection(it) }
    }
    var requestedMinute by remember { mutableStateOf<Int?>(null) }
    val remap = rememberTimelineRemap(scroll, axis, topPx, zoom, liveZoom != null)
    val readableHeaderPx = with(density) { 32.dp.toPx() }
    LaunchedEffect(row, vm, readableHeaderPx, days, active) {
        if (!active) return@LaunchedEffect
        snapshotFlow {
            if (!todaySlide.showingDestination) return@snapshotFlow null
            fun shown(item: LazyListItemInfo) = minOf(item.offset + item.size, row.layoutInfo.viewportEndOffset) - maxOf(item.offset, 0)
            val items = if (days == 1) listOfNotNull(row.layoutInfo.visibleItemsInfo.maxByOrNull(::shown))
                else row.layoutInfo.visibleItemsInfo.filter { shown(it) >= minOf(readableHeaderPx, it.size / 2f) }
            val settled = !row.isScrollInProgress && (days != 1 || row.firstVisibleItemScrollOffset == 0)
            if (items.isEmpty()) null else Triple(items.minOf { it.index }, items.maxOf { it.index }, settled && !vm.loadingEvents)
        }.distinctUntilChanged().collect { range ->
            if (vm.viewMode == mode) range?.let { vm.showTimelineRange(date(it.first), date(it.second), load = it.third) }
        }
    }
    var firstPosition by rememberSaveable { mutableStateOf(true) }
    // An explicit date (picker, Today) moves the strip in the next measure, so no frame shows the dates in between
    // or the old week. Two fingers that take over a pending Today jump keep the strip where it is.
    val requestedRow = remember { intArrayOf(Int.MIN_VALUE) }
    SideEffect {
        val request = vm.timelineNavigationRequest
        if (active && request != vm.handledTimelineNavigation && requestedRow[0] != request && !zoom.pinching) {
            requestedRow[0] = request
            val target = index(vm.selectedDate)
            val explicitToday = vm.isTimelineToday(mode, request)
            val inWeek = explicitToday && mode == ViewMode.Week && target in bandIndices.value
            if ((!motion || !explicitToday) && !inWeek &&
                (target != row.firstVisibleItemIndex || row.firstVisibleItemScrollOffset != 0)
            ) row.requestScrollToItem(target)
        }
    }
    LaunchedEffect(active, vm.timelineNavigationRequest, zoom.pinching, motion) {
        if (!active) return@LaunchedEffect
        val request = vm.timelineNavigationRequest
        var completed = false
        try {
            val pending = request != vm.handledTimelineNavigation
            val todayRequest = pending && vm.isTimelineToday(mode, request)
            val glide = motion && todayRequest
            if (pending) fling.stop()
            if (firstPosition && scroll.isScrollInProgress) {
                firstPosition = false
                return@LaunchedEffect
            }
            if (zoom.pinching && vm.selectedDate == today) {
                firstPosition = false
                vm.consumeTimelineNavigation(request)
                return@LaunchedEffect
            }
            val target = index(vm.selectedDate)
            val keepWeek = todayRequest && mode == ViewMode.Week &&
                target in bandIndices.value
            val toToday = vm.selectedDate == today &&
                ((firstPosition && scroll.value == 0) || todayRequest)
            if (toToday) {
                snapshotFlow {
                    val ready = if (mode == ViewMode.Day) c.todayGridReady else vm.coversRange(today, today)
                    !zoom.pinching && zoom.live == null && (ready || (!vm.loadingEvents && vm.loadError != null))
                }.first { it }
                requestedMinute = todayMinute()
            }
            suspend fun placeHours(animateHours: Boolean) {
                if (toToday) snapshotFlow { target in bandIndices.value }.first { it }
                if (toToday || (firstPosition && scroll.value == 0)) {
                    withFrameNanos { }
                    val y = if (toToday) timelineSnapScroll(latestAxis, requestedMinute ?: todayMinute(), bandExtent.intValue)
                        else latestAxis.minuteToY(7 * 60)
                    val destination = y.roundToInt().coerceAtMost(scroll.maxValue)
                    if (animateHours) scroll.animateScrollTo(destination, tween(640, easing = FastOutSlowInEasing))
                    else scroll.scrollTo(destination)
                    remap.pendingAnchor = null
                    remap.previousAxis = latestAxis
                }
            }
            val distance = target - row.firstVisibleItemIndex
            if (glide && !keepWeek && abs(distance) > 7) {
                kotlinx.coroutines.coroutineScope {
                    todaySlide.showTarget(distance) {
                        row.scrollToItem(target)
                        launch { placeHours(animateHours = true) }
                    }
                }
            } else if (glide) {
                kotlinx.coroutines.coroutineScope {
                    if (!keepWeek) launch {
                        val first = row.layoutInfo.visibleItemsInfo.firstOrNull()
                        if (first == null) row.scrollToItem(target)
                        else {
                            val remaining = target - first.index
                            val duration = 380 + (120f * abs(remaining) / days).roundToInt().coerceAtMost(660)
                            row.animateScrollBy(remaining * first.size.toFloat() + first.offset,
                                tween(duration, easing = FastOutSlowInEasing))
                        }
                    }
                    placeHours(animateHours = true)
                }
            } else {
                if (pending && !keepWeek && (target != row.firstVisibleItemIndex || row.firstVisibleItemScrollOffset != 0)) row.scrollToItem(target)
                placeHours(animateHours = false)
            }
            firstPosition = false
            withFrameNanos { }
            completed = true
        } finally {
            if (completed || currentCoroutineContext().isActive) {
                firstPosition = false
                vm.consumeTimelineNavigation(request)
            }
            requestedMinute = null
        }
    }
    // A date opened on an event (a search result, a notification) scrolls to it and rings it briefly, once the strip
    // has reached the date and its events have loaded.
    var highlighted by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(highlighted) { if (highlighted != null) { delay(1800); highlighted = null } }
    val reportFocus by rememberUpdatedState(c.onFocusHandled)
    LaunchedEffect(focus) {
        val request = focus ?: return@LaunchedEffect
        request.start()
        try {
            val day = request.date
            snapshotFlow { !firstPosition && vm.timelineNavigationRequest == vm.handledTimelineNavigation && vm.coversRange(day, day, mode) }.first { it }
            if (row.firstVisibleItemIndex != index(day) || row.firstVisibleItemScrollOffset != 0) row.scrollToItem(index(day))
            val target = focusTarget(vm.eventsByDay[day].orEmpty(), day, zone)
            val minute = if (target != null && !target.isTimelineBanner()) (minuteOn(target.startMillis, day, zone) - 30).coerceAtLeast(0) else 0
            withFrameNanos { }
            scroll.scrollTo((latestAxis.minuteToY(minute) - bandExtent.intValue).roundToInt().coerceAtLeast(0))
            // The focus owns this position, including a concurrent axis change.
            remap.pendingAnchor = null
            remap.previousAxis = latestAxis
            if (request.ring) highlighted = target?.key()
        } finally { if (currentCoroutineContext().isActive) reportFocus(request) }
    }
    // The vertical positions the navigation effect below would reach a frame or two later, applied while the grid is
    // placed so it is never drawn at an old or clamped offset. A pinch and its glide own the scroll themselves.
    val placementTarget: () -> Float? = {
        val pending = vm.timelineNavigationRequest != vm.handledTimelineNavigation
        val todayRequest = pending && vm.isTimelineToday(mode, vm.timelineNavigationRequest)
        val turnToToday = todayRequest
        val previewDay = if (!active && mode == ViewMode.Day && vm.viewMode != ViewMode.Day) date(start) else null
        val landingDay = previewDay ?: vm.selectedDate
        when {
            (!active && previewDay == null) || zoom.live != null || zoom.pinching || (todayRequest && motion) -> null
            (firstPosition || turnToToday) && landingDay == today &&
                (vm.loadingEvents || (vm.loadError == null && !vm.coversRange(today, today)) ||
                    index(today) !in bandIndices.value) -> null
            firstPosition && scroll.isScrollInProgress -> {
                firstPosition = false
                vm.consumeTimelineNavigation(vm.timelineNavigationRequest)
                null
            }
            (firstPosition && scroll.value == 0) || (turnToToday && landingDay == today) ->
                if (landingDay == today) timelineSnapScroll(axis, todayMinute(), bandExtent.intValue)
                else axis.minuteToY(7 * 60)
            active && remap.pendingAnchor != null -> (requestedMinute?.let { timelineSnapScroll(axis, it, bandExtent.intValue) }
                ?: remap.pendingAnchor!!.scrollOn(axis, topPx))
                .also { remap.pendingAnchor = null }
            else -> null
        }
    }
    val latestPlacement = rememberUpdatedState(placementTarget)
    val positioned = remember(scroll) { Modifier.timelinePosition(scroll) { latestPlacement.value() } }
    BoxWithConstraints(todaySlide.drawOn(modifier.fillMaxSize())) {
        val gutter = rememberTimeGutter(labels, railStyle, 44.dp, maxWidth * 0.30f)
        // At normal text sizes the chosen number of days fits the screen. Large text keeps readable columns and scrolls.
        val normalText = density.fontScale <= 1.15f
        fun columnWidth(count: Float): Dp {
            val narrowest = when {
                !normalText -> titleSize * 8f + 24.dp
                count <= 5f -> maxOf(40.dp, titleSize * 3.2f)
                else -> maxOf(30.dp, titleSize * 2.4f)
            }
            return maxOf(narrowest, (maxWidth - gutter) / count)
        }
        val column = columnWidth(liveDays ?: days.toFloat())
        val columnPx = with(density) { column.toPx() }
        var oldColumnPx by rememberSaveable { mutableFloatStateOf(columnPx) }
        val pan = rememberSaveable(saver = TimelinePanAnchor.Saver) { TimelinePanAnchor(row.firstVisibleItemIndex) }
        var pinch by remember { mutableStateOf<WeekPinch?>(null) }
        var settling by remember { mutableStateOf<Job?>(null) }
        val onSpread by rememberUpdatedState { ratio: Float, velocity: Float? ->
            val continuing = pinch.takeUnless { settling?.isActive == true }
            settling?.cancel()
            val anchor = continuing ?: run {
                val width = row.layoutInfo.visibleItemsInfo.firstOrNull()?.size?.coerceAtLeast(1) ?: columnPx.roundToInt().coerceAtLeast(1)
                WeekPinch(row.firstVisibleItemIndex, row.firstVisibleItemScrollOffset.toFloat() / width, liveDays ?: days.toFloat())
            }
            val preview = (anchor.initialDays / ratio.pow(1.65f)).coerceIn(3f, 7f)
            pinch = anchor
            if (velocity == null) {
                liveDays = preview
            } else {
                val projectedRatio = ratio * exp((velocity * 0.13f).coerceIn(-0.22f, 0.22f))
                val projectedDays = anchor.initialDays / projectedRatio.pow(1.65f)
                val target = when {
                    projectedDays < 3.65f -> 3
                    projectedDays < 6.4f -> 5
                    else -> 7
                }
                val from = liveDays ?: preview
                settling = spreadScope.launch {
                    if (motion) animate(0f, 1f, animationSpec = spring(stiffness = Spring.StiffnessMedium)) { value, _ ->
                        liveDays = from + (target - from) * value
                        pinch = anchor.copy(offsetFraction = anchor.offsetFraction * (1f - value))
                    }
                    liveDays = target.toFloat()
                    pinch = anchor.copy(offsetFraction = 0f)
                    vm.visibleWeekDays = target
                    // Let the final width and offset reach the strip before handing scrolling back to it.
                    withFrameNanos { }
                    withFrameNanos { }
                    pinch = null
                    liveDays = null
                }
            }
        }
        val spread: ((Float, Float?) -> Unit)? = remember(mode) {
            if (mode == ViewMode.Week) { ratio, velocity -> onSpread(ratio, velocity) } else null
        }
        SideEffect {
            pinch?.let { anchor ->
                // Keep the same leading date. A partially visible day stays partial until it settles to its edge.
                val width = columnPx.roundToInt().coerceAtLeast(1)
                val offset = (anchor.offsetFraction * width).roundToInt().coerceIn(0, width - 1)
                if (anchor.index != row.firstVisibleItemIndex || offset != row.firstVisibleItemScrollOffset) {
                    row.requestScrollToItem(anchor.index, offset)
                }
                oldColumnPx = columnPx
            }
        }
        LaunchedEffect(row, columnPx) {
            snapshotFlow {
                val item = row.layoutInfo.visibleItemsInfo.firstOrNull { it.index == row.firstVisibleItemIndex }
                if (oldColumnPx == columnPx && item?.size == columnPx.roundToInt())
                    row.firstVisibleItemIndex to row.firstVisibleItemScrollOffset else null
            }.collect { position -> position?.let { pan.record(it.first, it.second, columnPx.roundToInt()) } }
        }
        LaunchedEffect(columnPx) {
            if (pinch == null && oldColumnPx != columnPx) {
                try {
                    if (vm.timelineNavigationRequest != vm.handledTimelineNavigation) {
                        // Explicit navigation owns the strip until it completes or the user interrupts it.
                        snapshotFlow { vm.timelineNavigationRequest == vm.handledTimelineNavigation }.first { it }
                        withFrameNanos { }
                        pan.record(row.firstVisibleItemIndex, row.firstVisibleItemScrollOffset, columnPx.roundToInt())
                    } else row.scrollToItem(pan.index, pan.offset(columnPx.roundToInt()))
                    oldColumnPx = columnPx
                } catch (cancelled: CancellationException) {
                    if (currentCoroutineContext().isActive) oldColumnPx = columnPx
                    throw cancelled
                }
            }
        }
        // While pinching, the strip keeps its largest height so the focal scroll is never clamped early.
        val height = with(density) {
            (if (liveZoom == null) axis.totalHeight else maxOf(axis.totalHeight, zoomAxis(TIMELINE_ZOOM_MAX * 1.25f).totalHeight)).toDp()
        }
        val headerHeight = maxOf(40.dp, with(density) { MaterialTheme.typography.bodyLarge.fontSize.toDp() } * 2.25f) +
            with(density) { railStyle.lineHeight.toDp() } + 12.dp
        // All-day titles wrap to three lines, and the band fits the tallest day in view, as in Google Calendar.
        val chipLine = with(density) { MaterialTheme.typography.labelLarge.lineHeight.toDp() }
        val bandLimit = maxHeight / 4
        val drag = Modifier.scrollable(row, Orientation.Horizontal, reverseDirection = !rtl, flingBehavior = fling)
        val hoursDrag = Modifier.scrollable(scroll, Orientation.Vertical, reverseDirection = true, flingBehavior = hourSnap)
        Column(Modifier.fillMaxSize().pointerInput(fling) {
            // Any finger stops a snap in progress, and one lifting without dragging the days lets it finish.
            awaitEachGesture {
                awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                fling.stop()
                while (awaitPointerEvent(PointerEventPass.Final).changes.any { it.pressed }) Unit
                if (!zoom.pinching && liveDays == null) fling.settle()
            }
        }) {
            if (mode == ViewMode.Week) Row(Modifier.fillMaxWidth().height(headerHeight).then(hoursDrag)) {
                Spacer(Modifier.width(gutter))
                ProjectedDays(row, visibleIndices, column, Modifier.weight(1f).fillMaxHeight().then(drag)) { i ->
                    val day = date(i)
                    val semantics = if (i in visibleIndices.value) Modifier else Modifier.clearAndSetSemantics { }
                    TimelineDayHeader(day, day == today,
                        if (vm.coversRange(day, day, mode)) events[day].orEmpty().size else 0, f, headerMeasurer, Modifier.fillMaxSize().then(semantics))
                }
            }
            // The hours fade softly into the dates, and into the all-day band once it opens, rather than meeting them at a
            // hard edge, hour labels included. Snaps keep a label clear of it, and the fade takes no touches.
            val seamColor = p.background
            Box(Modifier.weight(1f).fillMaxWidth()) {
                // The grid ends [edge] above the bottom of the scrolling content, plus the gesture bar the screen runs under
                // and the toolbar floating over it.
                val barBottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
                val barPx = with(density) { (barBottom + TOOLBAR_CLEARANCE).toPx() }
                Row(
                    Modifier.fillMaxSize().drawWithContent {
                        drawContent()
                        if (agendaMorph?.drawingOverlay != true) drawTimelineSeam(bandHeight.intValue.toFloat(), seamColor)
                    }.timelinePinch(
                        zoom, scroll, pinchScope, motion,
                        saved = { vm.weekTimelineZoom }, axisAt = { zoomAxis(it) }, topPx = topPx,
                        contentHeight = { it.totalHeight + topPx * 2 + barPx },
                        onCommit = { vm.weekTimelineZoom = it }, onSpread = spread,
                    ).then(positioned).verticalScroll(scroll, flingBehavior = hourSnap).padding(top = edge, bottom = edge + barBottom + TOOLBAR_CLEARANCE),
                ) {
                    // Gap labels share the same mapping as the columns, so no event can drift away from its hour.
                    Box(Modifier.width(gutter).height(height)) {
                        HourRail(labels, railStyle, Modifier.fillMaxSize(), axis, overhang = edgePx)
                    }
                    LazyRow(state = row, modifier = Modifier.weight(1f).height(height), flingBehavior = fling) {
                        items(TIMELINE_DAYS, key = { origin + it - TIMELINE_CENTER }, contentType = { "timeline_day" }) { i ->
                            val day = date(i)
                            val isToday = day == today
                            val tileColor = if (isToday && days > 1) gridColors.today else gridColors.tile
                            Box(Modifier.width(column).fillMaxHeight().drawBehind {
                                // Day shows one date, so today needs no tint to stand out from its neighbours.
                                drawTimelineGrid(axis, tileColor)
                                if (isToday) {
                                    // A class in progress marks the time inside its card instead, so the line never pokes out beside it.
                                    val now = day.atStartOfDay(zone).toInstant().toEpochMilli() + clock.value * 60_000L
                                    if (timed[day].orEmpty().none { now in it.startMillis until it.endMillis }) drawNowLine(axis, clock.value, gridColors)
                                }
                            }) {
                                if (vm.coversRange(day, day, mode)) CompositionLocalProvider(LocalTimelineHeld provides (liveZoom != null || liveDays != null)) {
                                    DayColumn(day, timed[day].orEmpty(), zone, minMinutes, minBlockWidth,
                                        f, compact, titleStyle, highlighted, motion, Modifier.fillMaxSize(), onOpen, vm::newEvent,
                                        { overflowEvents = it }, axis, readableLabels = days == 1,
                                        minLabelHeight = if (days == 1) text.minBlock else 0.dp, clock = clock.takeIf { isToday })
                                } else Text(loadingLabel, Modifier.padding(8.dp), color = p.muted, style = railStyle)
                            }
                        }
                    }
                }
                // All-day events sit over the start of the hours without moving the scroll.
                Column(Modifier.fillMaxWidth().then(hoursDrag)) {
                    TimelineBand(gutter, motion, bandHeight, bandExtent) {
                        ProjectedDays(row, bandIndices, column, Modifier.weight(1f).then(drag), fit = bandLimit) { i ->
                            val day = date(i)
                            val list = if (vm.coversRange(day, day, mode)) banners[day].orEmpty() else emptyList()
                            if (list.isNotEmpty()) {
                                Column(Modifier.fillMaxWidth().then(allDayModifier).verticalScroll(rememberScrollState(), overscrollEffect = null)
                                    .padding(horizontal = 1.dp, vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                    list.forEach { event ->
                                        AllDayChip(event, day, f, highlighted == event.key(), motion, chipLine * 3 + 12.dp) { onOpen(event) }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
    val chooser = rememberChooserHandoff(vm) { overflowEvents = emptyList() }
    if (overflowEvents.isNotEmpty()) EventChooser(overflowEvents, f, onOpen = chooser.open, onClose = chooser.close)
}

/**
 * Snaps the strip to whole days the way Android and iOS lists snap: a fling picks the day edge nearest where its
 * momentum would stop and decelerates onto it, starting at the finger's speed. The glide moves the strip without
 * holding its scroll lock, so a finger landing during it stops it ([stop]) instead of catching the strip, and a scroll
 * down started then stays a scroll down. When that finger lifts without dragging the days, [settle] finishes the snap.
 * [oneDay] keeps each swipe to the neighbouring day, for Day's strip. With [weekStart], a swipe at least [weekFling] pixels a
 * second turns a whole week, and a slower one moves only as far as it was dragged.
 */
private class DaySnap(
    private val state: LazyListState, private val decay: DecayAnimationSpec<Float>, private val scope: CoroutineScope,
    private val oneDay: Boolean, private val weekStart: ((Int) -> Int)?, private val weekFling: Float,
) : FlingBehavior {
    private var glide: Job? = null
    /** The day the strip last rested on or headed for, so [oneDay] measures a swipe from where it began. */
    private var rest = -1

    override suspend fun ScrollScope.performFling(initialVelocity: Float): Float {
        glideTo(initialVelocity, decay.calculateTargetValue(0f, initialVelocity), swipe = true)
        return 0f
    }

    fun stop() {
        glide?.cancel()
        val first = state.layoutInfo.visibleItemsInfo.firstOrNull() ?: return
        if (first.size > 0) rest = first.index + (-first.offset.toFloat() / first.size).roundToInt()
    }

    fun settle() { if (glide?.isActive != true && !state.isScrollInProgress) glideTo(0f, 0f, swipe = false) }

    private fun glideTo(velocity: Float, momentum: Float, swipe: Boolean) {
        glide?.cancel()
        val info = state.layoutInfo
        val first = info.visibleItemsInfo.firstOrNull() ?: return
        val stride = (first.size + info.mainAxisItemSpacing).toFloat()
        if (stride <= 0f) return
        // Measured from the first day in view: the strip holds a million days, too many pixels for a Float.
        val day = if (oneDay && swipe) {
            // Like a pager, a quarter of a day past where the swipe began, or a fling that way, moves to the next day.
            val from = rest.takeIf { abs(it - first.index) <= 1 } ?: (first.index + if (-first.offset > stride / 2) 1 else 0)
            val moved = (first.index - from) * stride - first.offset + momentum
            from + if (moved > stride / 4) 1 else if (moved < -stride / 4) -1 else 0
        } else if (swipe && weekStart != null && abs(velocity) >= weekFling) {
            // Any quick swipe turns the week: it lands on the next week's first day, or going back on the first day of
            // this week, or of the previous one when already there.
            val from = rest.takeIf { abs(it - first.index) <= 14 } ?: first.index
            val start = weekStart(from)
            if (momentum > 0) start + 7 else if (start < from) start else start - 7
        } else if (weekStart != null) {
            // A slow, controlled drag moves by days: the strip settles on the day edge nearest where it was let go.
            first.index + (-first.offset / stride).roundToInt()
        } else first.index + ((momentum - first.offset) / stride).roundToInt()
        rest = day.coerceIn(0, info.totalItemsCount - 1)
        val distance = (rest - first.index) * stride + first.offset
        if (abs(distance) < 0.5f) return
        // A quadratic ease-out starts at twice its average speed, so this duration keeps the finger's speed, within a
        // quick snap however far the day edge is.
        val millis = if (velocity != 0f && sign(velocity) == sign(distance)) (2000 * distance / velocity).roundToInt() else 180
        glide = scope.launch {
            var moved = 0f
            animate(0f, distance, animationSpec = tween(millis.coerceIn(100, 300), easing = { 1 - (1 - it) * (1 - it) })) { value, _ ->
                // A drag or a jump to a date takes the lock, and the strip is theirs from then on.
                if (state.isScrollInProgress) glide?.cancel() else moved += state.dispatchRawDelta(value - moved)
            }
        }
    }
}

/**
 * Reads pixel offsets in placement, keeping scroll updates out of composition. With [fit], the band takes the height
 * of the tallest day in view, up to [fit], instead of the height it is given.
 */
@Composable
private fun ProjectedDays(
    state: LazyListState, visible: State<IntRange>, column: Dp, modifier: Modifier, fit: Dp? = null, content: @Composable (Int) -> Unit,
) {
    val indices = visible.value
    val composed = (indices.first - 3).coerceAtLeast(0)..(indices.last + 3).coerceAtMost(TIMELINE_DAYS - 1)
    Layout(content = { composed.forEach { i -> key(i) { ProjectedDay(i, content) } } }, modifier = modifier.clipToBounds()) { measurables, constraints ->
        val width = column.roundToPx()
        // Fitted cells keep their natural height up to the cap, and the band follows the tallest visible day.
        val cells = measurables.map { it.measure(if (fit == null) Constraints.fixed(width, constraints.maxHeight) else Constraints(width, width, 0, fit.roundToPx())) }
        val height = if (fit == null) constraints.maxHeight else visible.value.maxOf { i -> cells.getOrNull(i - composed.first)?.height ?: 0 }
        layout(constraints.maxWidth, height) {
            val anchor = state.layoutInfo.visibleItemsInfo.firstOrNull()
            if (anchor != null) cells.forEachIndexed { n, cell ->
                cell.placeRelativeWithLayer(anchor.offset + (composed.first + n - anchor.index) * width, 0)
            }
        }
    }
}

/** A restartable cell, so a new visible range composes only the cells that enter it. */
@Composable
private fun ProjectedDay(index: Int, content: @Composable (Int) -> Unit) {
    Box { content(index) }
}

/**
 * The all-day band, kept composed and collapsed to nothing while no day in view has an all-day event, so it opens
 * and closes without building its days again. Its height follows the tallest day with a spring, and its edge
 * reveals the chips where they sit.
 */
@Composable
private fun TimelineBand(
    gutter: Dp, motion: Boolean, shownHeight: MutableIntState, extent: MutableIntState,
    content: @Composable RowScope.() -> Unit,
) {
    val background = LocalPalette.current.background
    val height = remember { Animatable(0f) }
    // Written by the layout and read only by the effect, so a new height never measures the band twice.
    val target = remember { mutableIntStateOf(-1) }
    LaunchedEffect(height, motion) {
        var first = true
        snapshotFlow { target.intValue }.collectLatest {
            if (it < 0) return@collectLatest
            // The band opens with the screen at its height, and only later changes glide.
            if (first || !motion) height.snapTo(it.toFloat()) else height.animateTo(it.toFloat(), spring(stiffness = Spring.StiffnessMediumLow))
            first = false
        }
    }
    Row(Modifier.fillMaxWidth().layout { measurable, constraints ->
        val placeable = measurable.measure(constraints.copy(minHeight = 0, maxHeight = Constraints.Infinity))
        // Jumps clear the destination band's full height, including while it is still opening.
        extent.intValue = placeable.height
        if (target.intValue != placeable.height) target.intValue = placeable.height
        val visible = height.value.roundToInt().coerceAtLeast(0)
        shownHeight.intValue = visible
        layout(placeable.width, visible) { placeable.place(0, 0) }
    }.drawBehind { drawRect(background, size = Size(size.width, height.value.coerceAtLeast(0f))) }.graphicsLayer {
        // The band's layer clips everything in it, nested scrolling days included, at the height its background has
        // reached, so no chip shows past the background while it opens.
        val shownHeight = height.value.coerceAtLeast(0f)
        clip = true
        val corner = 12.dp.toPx()
        shape = GenericShape { size, _ ->
            addRoundRect(RoundRect(0f, 0f, size.width, shownHeight,
                bottomLeftCornerRadius = CornerRadius(corner), bottomRightCornerRadius = CornerRadius(corner)))
        }
    }) {
        Spacer(Modifier.width(gutter))
        content()
    }
}

/** Pixels per second above which a release counts as a flick. */
private const val FLICK_VELOCITY = 400f

/**
 * Settles a vertical flick on the snap point from [points] nearest where its momentum would stop, decelerating onto it
 * from the finger's speed. A slow release settles on the point nearest where it was let go.
 */
private class HourSnap(
    private val decay: DecayAnimationSpec<Float>, private val points: () -> List<Float>,
    private val position: () -> Int, private val max: () -> Int,
) : FlingBehavior {
    override suspend fun ScrollScope.performFling(initialVelocity: Float): Float {
        val start = position().toFloat()
        val natural = start + decay.calculateTargetValue(0f, initialVelocity)
        val all = points().map { it.coerceIn(0f, max().toFloat()) }
        // A flick always moves on to a point in its direction, so even a light one lands somewhere new. A release without
        // one settles on the nearest point.
        val ahead = if (abs(initialVelocity) < FLICK_VELOCITY) emptyList()
            else all.filter { (it - start) * sign(initialVelocity) > 1f }
        val target = ahead.minByOrNull { abs(it - natural) } ?: all.minByOrNull { abs(it - natural) } ?: return 0f
        val distance = target - start
        if (abs(distance) < 0.5f) return 0f
        // A quadratic ease-out starts at twice its average speed, so this duration keeps the finger's speed.
        val millis = if (sign(initialVelocity) == sign(distance)) (2000 * distance / initialVelocity).roundToInt() else 250
        var moved = 0f
        animate(0f, distance, animationSpec = tween(millis.coerceIn(150, 600), easing = { 1 - (1 - it) * (1 - it) })) { value, _ ->
            moved += scrollBy(value - moved)
        }
        return 0f
    }
}
