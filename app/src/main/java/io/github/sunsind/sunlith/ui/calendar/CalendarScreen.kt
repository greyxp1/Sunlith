package io.github.sunsind.sunlith.ui.calendar

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.database.ContentObserver
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.spring
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.State
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.BlurEffect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import io.github.sunsind.sunlith.R
import io.github.sunsind.sunlith.SettingsActivity
import io.github.sunsind.sunlith.data.CalendarEvent
import io.github.sunsind.sunlith.ui.Formats
import io.github.sunsind.sunlith.ui.InfoCard
import io.github.sunsind.sunlith.ui.LocalPalette
import io.github.sunsind.sunlith.ui.Screen
import io.github.sunsind.sunlith.ui.SunlithViewModel
import io.github.sunsind.sunlith.ui.ViewMode
import io.github.sunsind.sunlith.ui.event.DatePick
import io.github.sunsind.sunlith.ui.gridStart
import io.github.sunsind.sunlith.ui.rememberFormats
import io.github.sunsind.sunlith.ui.weekStart
import java.time.LocalDate
import java.time.LocalTime
import java.time.YearMonth
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** Animations run unless Android's "Remove animations" is on. */
@Composable
fun rememberMotion(): Boolean {
    val context = LocalContext.current
    val resolver = context.contentResolver
    var systemOff by remember(resolver) {
        mutableStateOf(Settings.Global.getFloat(resolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f)
    }
    DisposableEffect(resolver) {
        fun refresh() { systemOff = Settings.Global.getFloat(resolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f }
        val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) = refresh()
        }
        resolver.registerContentObserver(Settings.Global.getUriFor(Settings.Global.ANIMATOR_DURATION_SCALE), false, observer)
        refresh()
        onDispose { resolver.unregisterContentObserver(observer) }
    }
    return !systemOff
}

internal enum class TodayDirection { Up, Down, Previous, Next }

/** Where today lies outside [first]..[last]: above or below, or before or after when the dates run [horizontal]ly. */
internal fun todayDirection(today: LocalDate, first: LocalDate, last: LocalDate, horizontal: Boolean = false): TodayDirection? = when {
    today < first -> if (horizontal) TodayDirection.Previous else TodayDirection.Up
    today > last -> if (horizontal) TodayDirection.Next else TodayDirection.Down
    else -> null
}

/** Scrolling reports visible dates without changing the selected date. Only the active page owns the report. */
@Stable
internal class AgendaViewport(private var awaitingToday: Boolean = false) {
    var direction by mutableStateOf<TodayDirection?>(null)
        private set
    var reporting by mutableStateOf(false)
        private set
    private var owner: Any? = null
    private var suppressed: Pair<Any, TodayDirection>? = null
    private var scope by mutableStateOf<Any?>(null)

    fun enterScope(value: Any) {
        if (scope === value) return
        scope = value
        owner = null
        direction = null
        reporting = false
        suppressed = null
    }

    fun reportingIn(value: Any): Boolean = scope === value && reporting

    fun report(source: Any, value: TodayDirection?, scope: Any?) {
        if (this.scope !== scope) return
        if (awaitingToday && value != null) {
            suppressed = source to value
            return
        }
        suppressed = null
        awaitingToday = false
        owner = source
        direction = value
        reporting = true
    }

    fun userInteraction() {
        awaitingToday = false
        suppressed?.let { (source, value) -> report(source, value, scope) }
    }

    fun release(source: Any) {
        if (suppressed?.first === source) suppressed = null
        if (owner !== source) return
        owner = null
        direction = null
        reporting = false
    }
}

/** Everything the calendar sections share for one frame. */
internal class CalendarContext(
    val vm: SunlithViewModel,
    val f: Formats,
    val dayMeasurer: TextMeasurer,
    val today: LocalDate,
    val motion: Boolean,
    private val swipes: Map<ViewMode, PeriodSwipeState>,
    val viewport: AgendaViewport,
    val viewportScope: Any,
    /** Each view's own hour scroll, so a timeline sliding out for another view keeps its place. */
    val timelineScrolls: Map<ViewMode, ScrollState>,
    val timelineFocus: TimelineFocus?,
    val onFocusHandled: (TimelineFocus) -> Unit,
    /** The view these periods count in, when it is not the open one, as for a view sliding out. */
    private val mode: ViewMode? = null,
) {
    private val shown: ViewMode get() = mode ?: vm.viewMode

    val swipe: PeriodSwipeState get() = swipes.getValue(shown)

    val visibleDay: LocalDate
        get() = if (vm.continuousTimeline()) vm.timelineViewportStart ?: vm.selectedDate
            else swipe.visiblePeriod?.let(LocalDate::ofEpochDay) ?: vm.selectedDate

    val todayGridReady: Boolean
        get() {
            val first = gridStart(YearMonth.from(today))
            return vm.coversRange(first, first.plusDays(41))
        }

    fun forMode(mode: ViewMode) = CalendarContext(vm, f, dayMeasurer, today, motion, swipes, viewport, viewportScope,
        timelineScrolls, timelineFocus, onFocusHandled, mode)

    // A fresh request waits for provider data; an animation already started can finish during a refresh.
    val readyFocus: TimelineFocus?
        get() = timelineFocus?.takeIf { !vm.pendingDayFocus || (!vm.loadingEvents && vm.loadError == null) }

    val period: Long get() = periodOf(vm.selectedDateFor(shown))

    fun periodOf(date: LocalDate): Long = when (shown) {
        ViewMode.Month -> date.year * 12L + date.monthValue - 1
        ViewMode.Week -> Math.floorDiv(weekStart(date).toEpochDay(), 7L)
        ViewMode.Day -> date.toEpochDay()
    }
}

@Composable
fun CalendarScreen(vm: SunlithViewModel, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    var clockChange by remember { mutableIntStateOf(0) }
    // One clock for every timeline page. It moves together with today's date, so a new day never shows yesterday's
    // last minute. Only today's column and the Today direction read it.
    val currentMinute = remember { mutableIntStateOf(minuteOfDay(LocalTime.now())) }
    fun syncClock(): java.time.ZonedDateTime {
        val now = java.time.ZonedDateTime.now()
        vm.refreshCurrentDate(now.toLocalDate())
        currentMinute.intValue = minuteOfDay(now.toLocalTime())
        return now
    }
    DisposableEffect(vm, context) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                syncClock()
                if (intent?.action == Intent.ACTION_TIMEZONE_CHANGED) vm.reload()
                clockChange++
            }
        }
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_DATE_CHANGED)
            addAction(Intent.ACTION_TIME_CHANGED)
            addAction(Intent.ACTION_TIMEZONE_CHANGED)
        }
        ContextCompat.registerReceiver(context, receiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
        onDispose { context.unregisterReceiver(receiver) }
    }
    val lifecycleOwner = LocalLifecycleOwner.current
    LaunchedEffect(vm, clockChange, lifecycleOwner) {
        lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (true) delay(millisToNextMinute(syncClock().toLocalTime()) + 50)
        }
    }
    val p = LocalPalette.current
    val f = rememberFormats()
    val motion = rememberMotion()
    var showPicker by rememberSaveable { mutableStateOf(false) }

    val today = vm.today
    val mode = vm.viewMode
    val viewSwipe = remember(vm) { CalendarViewSwipe(mode, vm::changeViewMode) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(mode, motion) { viewSwipe.sync(mode, motion, scope) }
    val visibleViews by remember(viewSwipe) {
        derivedStateOf { floor(viewSwipe.position).toInt()..ceil(viewSwipe.position).toInt() }
    }
    val transitioning by remember(viewSwipe, mode) {
        derivedStateOf { !viewSwipe.isShowing(mode) }
    }
    var viewport by remember(vm, mode) { mutableStateOf(AgendaViewport()) }
    // Horizontal scrolling changes the visible dates without starting a new timeline navigation.
    val todayRequest = vm.todayRequestFor(mode)
    val viewportSelection: Any = if (vm.continuousTimeline()) vm.timelineNavigationRequest else vm.selectedDate
    val viewportScope = remember(mode, viewportSelection, vm.calendarExpanded,
        vm.visibleWeekDays, today,
        vm.dayFocusRequest.takeIf { mode == ViewMode.Day }, todayRequest) { Any() }
    // Hide old measurements during composition; accept only producers of this context after it is applied.
    SideEffect { viewport.enterScope(viewportScope) }
    val swipes = ViewMode.entries.associateWith { owner ->
        rememberPeriodSwipe(motion) { dir ->
            if (vm.viewMode == owner) {
                viewport.userInteraction()
                vm.step(dir)
            }
        }
    }
    val swipe = swipes.getValue(mode)
    LaunchedEffect(mode, vm.calendarExpanded) { swipes.values.forEach(PeriodSwipeState::cancel) }
    val weekScroll = rememberSaveable(saver = ScrollState.Saver) { ScrollState(0) }
    val dayScroll = rememberSaveable(saver = ScrollState.Saver) { ScrollState(0) }
    val timelineScrolls = remember(weekScroll, dayScroll) { mapOf(ViewMode.Week to weekScroll, ViewMode.Day to dayScroll) }
    var timelineFocus by remember(mode, vm.dayFocusRequest, todayRequest) {
        val request = vm.dayFocusRequest
        mutableStateOf(if (vm.pendingDayFocus) TimelineFocus(vm.selectedDate, request, vm.dayFocusRing) { vm.consumeDayFocus(request) } else null)
    }
    LaunchedEffect(mode, vm.selectedDate) {
        if (mode != ViewMode.Day || timelineFocus?.date?.let { it != vm.selectedDate } == true) {
            timelineFocus = null
            vm.consumeDayFocus(vm.dayFocusRequest)
        }
    }
    val onFocusHandled: (TimelineFocus) -> Unit = remember(vm, mode, vm.dayFocusRequest) {
        { if (timelineFocus === it) { vm.consumeDayFocus(it.request); timelineFocus = null } }
    }
    val dayMeasurer = rememberTextMeasurer(cacheSize = 128)
    val c = remember(vm, f, dayMeasurer, today, motion, swipes, viewport, viewportScope, timelineScrolls, timelineFocus, onFocusHandled) {
        CalendarContext(vm, f, dayMeasurer, today, motion, swipes, viewport, viewportScope, timelineScrolls, timelineFocus, onFocusHandled)
    }

    val weekPositioning = mode == ViewMode.Week && vm.timelineNavigationRequest != vm.handledTimelineNavigation
    val dateDirection by remember(c, mode, today, weekPositioning) {
        derivedStateOf {
            val activeDate = when (mode) {
                ViewMode.Month -> vm.selectedDate
                ViewMode.Week -> if (weekPositioning) vm.selectedDate else vm.timelineActionDate()
                ViewMode.Day -> c.visibleDay
            }
            todayDirection(today, activeDate, activeDate, horizontal = true)
        }
    }
    val measuredDirection = viewport.direction.takeIf { !weekPositioning && viewport.reportingIn(viewportScope) }
    val direction = dateDirection ?: measuredDirection

    val openPicker = { showPicker = true }
    val calendarState = rememberSaveableStateHolder()
    val dayReturn = rememberTodaySlide()
    var dayReturnJob by remember { mutableStateOf<Job?>(null) }
    LaunchedEffect(mode, vm.selectedDate) {
        if (mode != ViewMode.Day || vm.selectedDate != vm.today) dayReturnJob?.cancel()
    }
    val goToday: () -> Unit = goToday@{
        if (dayReturnJob?.isActive == true) return@goToday
        viewport = AgendaViewport(awaitingToday = true)
        val steps = (c.periodOf(today) - c.period).toInt()
        if (vm.continuousTimeline()) {
            swipe.cancel()
            vm.goToday()
        } else if (mode == ViewMode.Day && motion && steps != 0 && !c.todayGridReady) {
            // The destination can fall outside both loaded ranges. Keep the outgoing picker and agenda
            // together while the existing loader replaces their data, then reveal the complete Day.
            swipe.cancel()
            dayReturnJob = scope.launch {
                dayReturn.showTarget(steps) {
                    vm.goToday()
                    snapshotFlow { c.todayGridReady || (!vm.loadingEvents && vm.loadError != null) }.first { it }
                    withFrameNanos { }
                }
            }
        } else swipe.animateBy(steps) {
            if (vm.viewMode == mode) vm.goToday()
        }
    }
    val searchMorph = vm.searchMorph
    val searching = Screen.Search in vm.stack
    LaunchedEffect(searching, motion) {
        val target = if (searching) 1f else 0f
        searchMorph.settle(target, motion)
    }
    CompositionLocalProvider(LocalCurrentMinute provides currentMinute) {
    Scaffold(
        modifier = modifier,
        containerColor = p.background,
        topBar = {
            Column(Modifier.background(p.background).statusBarsPadding()) {
            }
        },
    ) { pad ->
        // The calendar runs under the gesture bar like other apps. Scrolling views add the bar's height at their end,
        // and the floating controls keep above it.
        val layoutDirection = LocalLayoutDirection.current
        Box(Modifier.fillMaxSize().padding(start = pad.calculateStartPadding(layoutDirection), top = pad.calculateTopPadding(),
            end = pad.calculateEndPadding(layoutDirection))
            .then(if (vm.continuousTimeline()) Modifier else Modifier.ignoreMultiplePointers()).pointerInput(viewport) {
            awaitPointerEventScope {
                while (true) {
                    val event = awaitPointerEvent(PointerEventPass.Initial)
                    if (event.changes.any { it.pressed && !it.previousPressed }) viewport.userInteraction()
                }
            }
        }) {
            Column(Modifier.fillMaxSize().graphicsLayer {
                val fraction = searchMorph.progress
                renderEffect = if (fraction > 0f && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    val radius = 28.dp.toPx() * fraction
                    BlurEffect(radius, radius)
                } else null
            }.drawWithContent {
                drawContent()
                drawRect(p.background.copy(alpha = 0.55f * searchMorph.progress))
            }) {
                val slideDirection = if (layoutDirection == LayoutDirection.Ltr) 1f else -1f
                Box(Modifier.fillMaxSize().clipToBounds()) {
                    visibleViews.forEach { index ->
                        val shown = viewModeAt(index)
                        key(shown) {
                            val active = shown == mode
                            CompositionLocalProvider(LocalShownMode provides shown) {
                                Column(Modifier.fillMaxSize().graphicsLayer {
                                    translationX = (index - viewSwipe.position) * size.width * slideDirection
                                }.background(p.background).then(if (active) Modifier else Modifier.clearAndSetSemantics {})) {
                                    StatusCards(vm, Modifier.padding(horizontal = 12.dp))
                                    when (shown) {
                                        ViewMode.Month -> calendarState.SaveableStateProvider(ViewMode.Month.name) {
                                            MonthBody(c, Modifier.weight(1f))
                                        }
                                        ViewMode.Week -> calendarState.SaveableStateProvider(ViewMode.Week.name) {
                                            ContinuousTimeline(c, Modifier.weight(1f))
                                        }
                                        ViewMode.Day -> calendarState.SaveableStateProvider(ViewMode.Day.name) {
                                            DayBody(c.forMode(shown), Modifier.weight(1f), dayReturn)
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
            TodayPill(direction.takeUnless { searching }, motion, goToday,
                Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(bottom = 68.dp))
            // While the toolbar is on its way to or from search, the calendar under it takes no touches.
            val morphing by remember { derivedStateOf { searchMorph.progress > 0f } }
            if (morphing || transitioning) Box(Modifier.fillMaxSize().pointerInput(Unit) {
                awaitPointerEventScope { while (true) awaitPointerEvent(PointerEventPass.Initial).changes.forEach { it.consume() } }
            })
            BottomBar(vm, f, openPicker,
                onStep = { if (vm.continuousTimeline()) vm.step(it) else swipe.animateStep(it) },
                onStepView = { delta, speed -> viewSwipe.release(delta, speed, motion, scope) },
                viewTarget = viewSwipe::peek,
                onDragView = viewSwipe::drag,
                pillDrag = { viewSwipe.pill },
                shift = { swipe.ahead },
                modifier = Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(bottom = 16.dp))
        }
    }
    }

    if (showPicker) DatePick(if (vm.continuousTimeline()) vm.timelineActionDate() else vm.selectedDate, { showPicker = false }) { vm.selectAndFocus(it) }
}

// Keep positions adjacent across the wrap so Month and Day slide in the same direction as any other pair.
private fun viewModeAt(index: Int): ViewMode = ViewMode.entries[index.mod(ViewMode.entries.size)]

private class CalendarViewSwipe(mode: ViewMode, private val select: (ViewMode) -> Unit) {
    var position by mutableFloatStateOf(mode.ordinal.toFloat())
        private set
    var pill by mutableFloatStateOf(0f)
        private set

    private var committed = mode
    private var destination by mutableIntStateOf(mode.ordinal)
    private var settle: Job? = null
    private var origin = position
    private var pillOrigin = 0f
    private var dragging = false

    fun peek(delta: Int): ViewMode = viewModeAt(destination + delta)

    fun isShowing(mode: ViewMode): Boolean = position == destination.toFloat() && peek(0) == mode

    fun drag(pull: Float, pagePull: Float) {
        if (!dragging) {
            settle?.cancel()
            dragging = true
            origin = position
            pillOrigin = pill
        }
        val next = origin + pagePull * 0.85f
        position = next.coerceIn(minOf(origin, destination - 1f), maxOf(origin, destination + 1f))
        pill = (pillOrigin + pull / (0.14f + abs(pull))).coerceIn(-1f, 1f)
    }

    fun release(delta: Int, speed: Float, motion: Boolean, scope: CoroutineScope) {
        dragging = false
        slide(destination + delta, motion, scope, speed)
    }

    fun sync(mode: ViewMode, motion: Boolean, scope: CoroutineScope) {
        if (mode != committed) {
            committed = mode
            dragging = false
            slide(destination + mode.ordinal - peek(0).ordinal, motion, scope)
        } else if (!motion && !dragging && (position != destination.toFloat() || pill != 0f)) {
            slide(destination, false, scope)
        }
    }

    private fun slide(target: Int, motion: Boolean, scope: CoroutineScope, speed: Float = 0f) {
        settle?.cancel()
        val start = position
        destination = target
        val startPill = pill
        val end = target.toFloat()
        val mode = viewModeAt(target)
        if (!motion || (start == end && startPill == 0f)) {
            position = end
            pill = 0f
            committed = mode
            select(mode)
            return
        }
        // Commit the view only once it owns the screen. A second swipe can retarget the visible slide without
        // activating and repositioning a calendar page that the user is still swiping past.
        settle = scope.launch {
            val remaining = end - start
            val momentum = if (speed * remaining > 0f) (speed * 0.55f / remaining).coerceAtMost(5f) else 0f
            animate(0f, 1f, initialVelocity = momentum, animationSpec = spring(stiffness = 650f, visibilityThreshold = 0.003f)) { value, _ ->
                val fraction = value.coerceIn(0f, 1f)
                position = start + remaining * fraction
                pill = startPill * (1f - fraction)
            }
            position = end
            pill = 0f
            committed = mode
            select(mode)
        }
    }
}

/** Events per day for [from]..[to]. Days that are not loaded yet give no events, never stale ones from another range. */
@Composable
internal fun rememberDayEvents(vm: SunlithViewModel, mode: ViewMode, from: LocalDate, to: LocalDate): (LocalDate) -> List<CalendarEvent> {
    val index = vm.eventsByDayFor(mode)
    val coverage = vm.loadedRangesFor(mode)
    val visible = remember(index, coverage, from, to) {
        index.filter { (day, _) -> day in from..to && coverage?.covers(day..day) == true }
    }
    return remember(visible) { { d: LocalDate -> visible[d].orEmpty() } }
}

@Composable
internal fun StatusCards(vm: SunlithViewModel, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    Column(modifier) {
        vm.loadError?.let { InfoCard(it, stringResource(R.string.action_retry)) { vm.reload() } }
        if (vm.loaded && vm.loadError == null) {
            when {
                vm.calendars.isEmpty() -> InfoCard(stringResource(R.string.no_calendars_on_phone), stringResource(R.string.action_create_local)) { vm.createLocalCalendar() }
                vm.activeIds.isEmpty() -> InfoCard(stringResource(R.string.no_calendars_selected), stringResource(R.string.action_choose_calendars)) { SettingsActivity.open(context, SettingsActivity.Page.Calendars) }
            }
        }
    }
}


/** The view a body is drawn for, which differs from the open one while it slides out. */
internal val LocalShownMode = compositionLocalOf<ViewMode?> { null }

internal fun minuteOfDay(time: LocalTime): Int = time.hour * 60 + time.minute

/** Milliseconds from [time] until the next whole minute, between 1 and 60 000. */
internal fun millisToNextMinute(time: LocalTime): Long = 60_000L - (time.second * 1_000L + time.nano / 1_000_000)

/** Minutes since local midnight. [CalendarScreen] provides one clock that it ticks together with today's date. */
internal val LocalCurrentMinute = staticCompositionLocalOf<State<Int>> { error("No calendar clock") }
