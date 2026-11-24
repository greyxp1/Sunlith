package io.github.sunsind.sunlith.ui

import android.app.Application
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import io.github.sunsind.sunlith.R
import io.github.sunsind.sunlith.SettingsActivity.Page as SettingsPage
import io.github.sunsind.sunlith.data.CalendarEvent
import io.github.sunsind.sunlith.data.CalendarRepository
import io.github.sunsind.sunlith.data.DeviceCalendar
import io.github.sunsind.sunlith.data.EditBlockReason
import io.github.sunsind.sunlith.data.EventDraft
import io.github.sunsind.sunlith.data.IcsTransfer
import io.github.sunsind.sunlith.data.ImportPreview
import io.github.sunsind.sunlith.data.ImportReport
import io.github.sunsind.sunlith.data.TodoItem
import io.github.sunsind.sunlith.data.TodoMarker
import io.github.sunsind.sunlith.data.indexByDay
import io.github.sunsind.sunlith.data.key
import io.github.sunsind.sunlith.data.projectSchoolEvents
import io.github.sunsind.sunlith.data.span
import io.github.sunsind.sunlith.system.AppRefresh
import io.github.sunsind.sunlith.system.LauncherIcons
import io.github.sunsind.sunlith.system.SettingsStore
import io.github.sunsind.sunlith.system.TodoStore
import io.github.sunsind.sunlith.system.visibleCalendarIds
import io.github.sunsind.sunlith.ui.calendar.SearchMorph
import io.github.sunsind.sunlith.ui.calendar.assignCourseSlots
import io.github.sunsind.sunlith.ui.event.EditorState
import io.github.sunsind.sunlith.widget.AgendaWidgetReceiver
import java.io.File
import java.io.FileNotFoundException
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.YearMonth
import java.time.ZoneId
import java.util.TimeZone
import kotlin.properties.ReadWriteProperty
import kotlin.reflect.KMutableProperty0
import kotlin.reflect.KProperty
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import io.github.sunsind.sunlith.system.hasCalendarAccess
import io.github.sunsind.sunlith.system.needsNotificationPermission

enum class ViewMode { Month, Week, Day }

private data class ViewPosition(
    val selected: LocalDate,
    val first: LocalDate? = null,
    val last: LocalDate? = null,
)

private data class CalendarPosition(
    val viewMode: ViewMode,
    val positions: Map<ViewMode, ViewPosition>,
    val dayParent: ViewMode?,
) {
    fun toBundle(today: LocalDate): Bundle = Bundle().apply {
        putString("view", viewMode.name)
        putString("parent_view", dayParent?.name)
        positions.forEach { (mode, position) ->
            putBundle(mode.name, Bundle().apply {
                putLong("selected", position.selected.toEpochDay())
                putBoolean("today", position.selected == today)
                position.first?.let { putLong("viewport", it.toEpochDay()) }
                position.last?.let { putLong("viewport_end", it.toEpochDay()) }
            })
        }
    }

    companion object {
        fun from(bundle: Bundle?, today: LocalDate): CalendarPosition? {
            if (bundle == null) return null
            fun Bundle.date(key: String) = if (containsKey(key)) {
                runCatching { LocalDate.ofEpochDay(getLong(key)).takeIf { it.year in 1900..2100 } }.getOrNull()
            } else null
            fun Bundle.position(): ViewPosition? {
                val selected = date("selected") ?: return null
                if (getBoolean("today") && selected != today) return ViewPosition(today)
                return ViewPosition(selected, date("viewport"), date("viewport_end"))
            }
            fun mode(key: String) = ViewMode.entries.firstOrNull { it.name == bundle.getString(key) }
            val view = mode("view") ?: return null
            val parent = mode("parent_view")
            val positions = ViewMode.entries.mapNotNull { mode ->
                bundle.getBundle(mode.name)?.position()?.let { mode to it }
            }.toMap().toMutableMap()
            // Older saved state kept only the active date and the view from which Day was opened.
            if (positions.isEmpty()) {
                bundle.position()?.let { positions[view] = it }
                if (parent != null && parent != view) bundle.date("parent_date")?.let {
                    positions[parent] = ViewPosition(it, bundle.date("parent_viewport"))
                }
            }
            return CalendarPosition(view, positions, parent)
        }
    }
}

sealed interface Screen {
    data object Search : Screen
    data object Todo : Screen
    data object Editor : Screen
    data class Details(val event: CalendarEvent) : Screen
}

class UiMessage(val text: String, val actionLabel: String?, val action: (() -> Unit)?)

data class ImportUi(
    val uri: Uri,
    val busy: Boolean = true,
    val preview: ImportPreview? = null,
    val calendarId: Long? = null,
    val report: ImportReport? = null,
    val error: String? = null,
)

internal data class EventRanges(val agenda: ClosedRange<LocalDate>, val grid: ClosedRange<LocalDate>) {
    fun covers(range: ClosedRange<LocalDate>) = agenda.covers(range) || grid.covers(range)
}

private fun ClosedRange<LocalDate>.covers(other: ClosedRange<LocalDate>) =
    other.start >= start && other.endInclusive <= endInclusive

class SunlithViewModel(app: Application, private val savedState: SavedStateHandle) : AndroidViewModel(app) {
    private val context: Context get() = getApplication()
    private val restored = CalendarPosition.from(savedState.get<Bundle>(KEY_POSITION), LocalDate.now())
    private val repo = CalendarRepository(app)
    private val transfer = IcsTransfer(repo)
    private val settings = SettingsStore(app)
    private val todoStore = TodoStore(app)
    private var todoJob: Job? = null
    private var todoGeneration = 0
    var todoItems by mutableStateOf(emptyList<TodoItem>()); private set
    var todoCompleted by mutableStateOf(emptySet<String>()); private set
    var todoLoading by mutableStateOf(false); private set
    var todoError by mutableStateOf<String?>(null); private set
    var todoRevision by mutableIntStateOf(0); private set
    var todoSavingIds by mutableStateOf(emptySet<Long>()); private set

    fun openTodos() {
        open(Screen.Todo)
        reloadTodos()
    }

    fun reloadTodos() {
        val generation = ++todoGeneration
        todoJob?.cancel()
        if (!hasPermission) {
            todoItems = emptyList(); todoCompleted = emptySet(); todoLoading = false
            return
        }
        todoLoading = true
        todoJob = viewModelScope.launch {
            try {
                val ids = activeIds
                val items = withContext(Dispatchers.IO) { repo.todoEvents(ids) }
                todoItems = items
                todoCompleted = items.filter { todoStore.isCompleted(it.key) }.mapTo(mutableSetOf()) { it.key }
                todoError = null
            } catch (c: CancellationException) { throw c }
            catch (e: Exception) { todoError = e.userMessage() }
            finally { if (generation == todoGeneration) todoLoading = false }
        }
    }

    fun completeTodo(item: TodoItem, completed: Boolean) {
        todoStore.setCompleted(item.key, completed)
        todoCompleted = if (completed) todoCompleted + item.key else todoCompleted - item.key
    }

    fun setEventTodo(event: CalendarEvent, enabled: Boolean) {
        if (event.id in todoSavingIds) return
        todoSavingIds = todoSavingIds + event.id
        launchAction({ todoSavingIds = todoSavingIds - event.id }) {
            withContext(Dispatchers.IO) { repo.setTodo(event.id, enabled) }
            todoRevision++
            reload()
        }
    }
    var launcherIcon by mutableStateOf(LauncherIcons.saved(context)); private set
    var launcherIconBusy by mutableStateOf(false); private set

    fun changeLauncherIcon(style: LauncherIcons.Style) {
        if (launcherIconBusy || style == launcherIcon) return
        launcherIconBusy = true
        viewModelScope.launch {
            try {
                val applied = withContext(Dispatchers.IO) { LauncherIcons.selectAndRequestReopen(context, style) }
                launcherIcon = LauncherIcons.saved(context)
                if (!applied) message(context.getString(R.string.launcher_icon_failed))
            } finally { launcherIconBusy = false }
        }
    }

    var hasPermission by mutableStateOf(context.hasCalendarAccess()); private set
    var notificationsGranted by mutableStateOf(!context.needsNotificationPermission()); private set
    var notificationStepDone by mutableStateOf(settings.notificationStepDone); private set
    var aiIntroDone by mutableStateOf(settings.aiIntroDone); private set

    var calendars by mutableStateOf(emptyList<DeviceCalendar>()); private set
    var activeIds by mutableStateOf(emptySet<Long>()); private set
    private val eventWindows = mutableStateMapOf<ViewMode, LoadedEvents>()
    val events: List<CalendarEvent> get() = eventWindows[viewMode]?.snapshot?.events.orEmpty()
    val eventsByDay: Map<LocalDate, List<CalendarEvent>> get() = eventsByDayFor(viewMode)

    private fun eventWindowFor(mode: ViewMode) = eventWindows[mode] ?: eventWindows[viewMode]
    internal fun eventsByDayFor(mode: ViewMode) = eventWindowFor(mode)?.snapshot?.byDay.orEmpty()
    internal fun loadedRangesFor(mode: ViewMode) = eventWindowFor(mode)?.ranges
    var loaded by mutableStateOf(false); private set
    var loadingEvents by mutableStateOf(false); private set
    var loadError by mutableStateOf<String?>(null); private set

    private val settingStates = mutableListOf<Setting<*>>()

    /** Mirrors a [SettingsStore] property as Compose state. Assigning saves it, rereads the stored value, then runs [onChange]. */
    private inner class Setting<T>(private val pref: KMutableProperty0<T>, private val onChange: () -> Unit) : ReadWriteProperty<Any?, T> {
        var state by mutableStateOf(pref.get())
        init { settingStates += this }
        fun reload() { state = pref.get() }
        override fun getValue(thisRef: Any?, property: KProperty<*>): T = state
        override fun setValue(thisRef: Any?, property: KProperty<*>, value: T) { pref.set(value); reload(); onChange() }
    }

    private fun <T> setting(pref: KMutableProperty0<T>, onChange: () -> Unit = {}) = Setting(pref, onChange)

    var themeMode by setting(settings::themeMode)
    var remindersEnabled by setting(settings::remindersEnabled) { refreshApp() }
    var widgetDays by setting(settings::widgetDays) { refreshWidgets() }
    var widgetShowCalendarName by setting(settings::widgetShowCalendarName) { refreshWidgets() }
    var widgetShowEmptyDays by setting(settings::widgetShowEmptyDays) { refreshWidgets() }
    val timelineViewportStart: LocalDate? get() = timelineStartFor(viewMode)
    private val timelineViewportEnd: LocalDate? get() = positionFor(viewMode).last
    var timelineNavigationRequest by mutableStateOf(0); private set
    var handledTimelineNavigation by mutableStateOf(0); private set
    var weekTimelineZoom by setting(settings::weekTimelineZoom)
    var visibleWeekDays by setting(settings::visibleWeekDays)
    var calendarExpanded by setting(settings::calendarExpanded); private set
    var courseColorSlots by setting(settings::courseColorSlots); private set

    var today by mutableStateOf(LocalDate.now()); private set
    private val positions = mutableStateMapOf<ViewMode, ViewPosition>().apply { putAll(restored?.positions.orEmpty()) }
    private fun positionFor(mode: ViewMode) = positions[mode] ?: ViewPosition(today)
    internal fun selectedDateFor(mode: ViewMode) = positionFor(mode).selected
    internal fun timelineStartFor(mode: ViewMode) = positionFor(mode).first
    var selectedDate: LocalDate
        get() = selectedDateFor(viewMode)
        private set(value) { positions[viewMode] = positionFor(viewMode).copy(selected = value) }
    private var dayParent by mutableStateOf(restored?.dayParent)
    val hasDayParent: Boolean get() = dayParent != null
    val searchMorph = SearchMorph()
    var dayFocusRequest by mutableStateOf(0); private set
    /** Whether the pending day focus came from a link to a date, so it rings the event it lands on. */
    var dayFocusRing by mutableStateOf(false); private set
    private var handledDayFocus = 0
    val pendingDayFocus: Boolean get() = viewMode == ViewMode.Day && handledDayFocus != dayFocusRequest

    fun consumeDayFocus(request: Int) {
        if (request == dayFocusRequest) handledDayFocus = request
    }
    val month: YearMonth get() = YearMonth.from(selectedDate)
    var viewMode by mutableStateOf(restored?.viewMode ?: launchViewMode()); private set

    /** Bumped by [goToday] so the calendar can re-center and confirm the action even when today is already selected. */
    var todayRequest by mutableIntStateOf(0); private set
    private var todayMode by mutableStateOf<ViewMode?>(null)
    private var todayTimelineNavigation = -1

    fun todayRequestFor(mode: ViewMode): Int = if (todayMode == mode) todayRequest else 0

    fun isTimelineToday(mode: ViewMode, request: Int): Boolean =
        todayMode == mode && todayTimelineNavigation == request

    val stack = mutableStateListOf<Screen>()
    var editor by mutableStateOf<EditorState?>(null); private set
    private var editorInitial: EditorState? = null
    var saving by mutableStateOf(false); private set

    var searchQuery by mutableStateOf(""); private set
    var searchResults by mutableStateOf(emptyList<CalendarEvent>()); private set
    var searchLoading by mutableStateOf(false); private set
    private var searchPool: List<CalendarEvent>? = null

    var importUi by mutableStateOf<ImportUi?>(null); private set
    var exporting by mutableStateOf(false); private set

    private val messageChannel = Channel<UiMessage>(Channel.BUFFERED)
    val messages = messageChannel.receiveAsFlow()

    private var loadJob: Job? = null
    private var loadingRanges: EventRanges? = null
    private var targetWindow: ClosedRange<LocalDate>? = null
    private var loadGeneration = 0L
    private var changeJob: Job? = null
    private var searchJob: Job? = null
    private var searchGeneration = 0L
    private var pendingLink: (() -> Unit)? = null

    init {
        // Read when Android saves the activity, so a recreated process returns to the same view and dates.
        savedState.setSavedStateProvider(KEY_POSITION) {
            CalendarPosition(viewMode, positions.toMap(), dayParent).toBundle(today)
        }
    }

    // region permissions and lifecycle

    val needsNotificationStep: Boolean
        get() = Build.VERSION.SDK_INT >= 33 && !notificationsGranted && remindersEnabled && !notificationStepDone

    private var settingsWindow = false

    fun onResume(settingsWindow: Boolean = false) {
        this.settingsWindow = settingsWindow
        settingStates.forEach { it.reload() }
        refreshCurrentDate()
        notificationsGranted = !context.needsNotificationPermission()
        val granted = context.hasCalendarAccess()
        val changed = granted != hasPermission
        hasPermission = granted
        if (!granted) {
            todoJob?.cancel(); todoItems = emptyList(); todoCompleted = emptySet(); todoLoading = false
            loadJob?.cancel()
            loadGeneration++
            loadingEvents = false
            calendars = emptyList(); loaded = false
            eventWindows.clear()
            targetWindow = null
            clearSearch()
            stack.clear(); editor = null
            return
        }
        if (changed) refreshApp()
        val calendarsChanged = settings.visibleCalendarIds(calendars) != activeIds
        if (calendarsChanged) invalidateSearch()
        // Keep an in-flight refresh only if it still uses the calendars chosen in Settings.
        if (changed || calendarsChanged || loadJob?.isActive != true) reload()
        pendingLink?.let { link -> pendingLink = null; link() }
    }

    fun onCalendarPermissionResult() = onResume()

    fun finishAiIntro() {
        settings.aiIntroDone = true
        aiIntroDone = true
    }

    fun startComputerSetup() {
        settings.computerSetupRequested = true
        finishAiIntro()
    }

    internal var settingsPage by mutableStateOf<SettingsPage?>(null)
        private set

    internal fun takeSettingsPage(): SettingsPage? = settingsPage.also { settingsPage = null }

    fun openRequestedComputerSetup() {
        if (!hasPermission || needsNotificationStep || stack.isNotEmpty() || !settings.computerSetupRequested) return
        settings.computerSetupRequested = false
        settingsPage = SettingsPage.Computer
    }

    fun onNotificationPermissionResult(granted: Boolean, fromOnboarding: Boolean) {
        notificationsGranted = !context.needsNotificationPermission()
        if (fromOnboarding) finishNotificationStep(enable = granted)
        else remindersEnabled = granted
    }

    fun finishNotificationStep(enable: Boolean) {
        settings.notificationStepDone = true
        notificationStepDone = true
        if (!enable) remindersEnabled = false
    }

    fun onProviderChanged() {
        if (!hasPermission) return
        changeJob?.cancel()
        changeJob = viewModelScope.launch {
            delay(450)
            todoRevision++
            reload()
            invalidateSearch()
        }
    }

    private fun invalidateSearch() {
        if (Screen.Search in stack && hasPermission) loadSearchPool(force = true) else clearSearch()
    }

    // endregion

    // region loading

    private val gridMonth: YearMonth get() = if (continuousTimeline()) YearMonth.from(timelineViewportStart ?: selectedDate) else month

    /** Week's continuous layout and Day's timeline share one sideways strip of days. */
    fun continuousTimeline(mode: ViewMode = viewMode): Boolean =
        mode == ViewMode.Week || (mode == ViewMode.Day && !calendarExpanded)

    fun timelineActionDate(): LocalDate {
        val first = timelineViewportStart ?: return selectedDate
        val last = timelineViewportEnd ?: first
        return if (selectedDate in first..last) selectedDate else first
    }

    private fun gridRange(month: YearMonth = gridMonth): ClosedRange<LocalDate> =
        gridStart(month).let { it..it.plusDays(41) }

    private fun bufferedGridRange(months: Long): ClosedRange<LocalDate> =
        gridRange(gridMonth.minusMonths(months)).start..gridRange(gridMonth.plusMonths(months)).endInclusive

    private fun initialWindow(): ClosedRange<LocalDate> = if (continuousTimeline()) {
        val anchor = timelineLoadAnchor()
        anchor.minusDays(60)..anchor.plusDays(120)
    } else gridRange()

    private fun wantedRange() = if (continuousTimeline()) targetWindow ?: initialWindow() else gridRange()

    internal fun coversRange(from: LocalDate, to: LocalDate, mode: ViewMode = viewMode) =
        loadedRangesFor(mode)?.covers(from..to) == true

    private fun ensureLoaded() {
        if (continuousTimeline() && targetWindow?.contains(timelineLoadAnchor()) != true) targetWindow = initialWindow()
        val wanted = wantedRange()
        val buffer = bufferedGridRange(1)
        if (loadJob?.isActive == true) {
            val loading = loadingRanges
            if (loading == null || !loading.covers(wanted) || !loading.grid.covers(buffer)) loadEvents()
            return
        }
        val loaded = loadedRangesFor(viewMode)
        if (loaded == null || !loaded.covers(wanted) || !loaded.covers(buffer)) loadEvents()
    }

    private fun timelineLoadAnchor(): LocalDate =
        if (continuousTimeline() && timelineNavigationRequest == handledTimelineNavigation) timelineViewportStart ?: selectedDate
        else selectedDate

    private fun eventsBetween(dates: ClosedRange<LocalDate>, ids: Set<Long>): List<CalendarEvent> {
        val zone = ZoneId.systemDefault()
        val start = dates.start.minusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
        val end = dates.endInclusive.plusDays(2).atStartOfDay(zone).toInstant().toEpochMilli()
        return repo.events(start, end, ids, school = true, reminders = true)
    }

    fun reload() {
        // Provider and permission changes can invalidate data cached by the other views.
        ViewMode.entries.forEach { if (it != viewMode) eventWindows.remove(it) }
        loadEvents()
    }

    private fun loadEvents() {
        if (!hasPermission) return
        val ranges = EventRanges(wantedRange(), bufferedGridRange(2))
        targetWindow = ranges.agenda
        loadingRanges = ranges
        loadJob?.cancel()
        val generation = ++loadGeneration
        loadingEvents = true
        loadJob = viewModelScope.launch {
            try {
                val cals = withContext(Dispatchers.IO) { repo.calendars() }
                val ids = settings.visibleCalendarIds(cals)
                Snapshot.withMutableSnapshot {
                    calendars = cals
                    activeIds = ids
                }
                if (settingsWindow) {
                    loadError = null
                    loaded = true
                    fillMissingCalendar()
                    return@launch
                }
                if (Screen.Todo in stack) reloadTodos()
                val result = withContext(Dispatchers.IO) {
                    val agenda = eventsBetween(ranges.agenda, ids)
                    val grid = if (ranges.agenda.covers(ranges.grid)) agenda else eventsBetween(ranges.grid, ids)
                    prepareEvents(agenda, grid, minOf(ranges.agenda.start, ranges.grid.start), maxOf(ranges.agenda.endInclusive, ranges.grid.endInclusive))
                }
                if (generation != loadGeneration || !ranges.covers(wantedRange()) || !ranges.grid.covers(gridRange())) return@launch
                Snapshot.withMutableSnapshot {
                    rememberCourseSlots(result.events)
                    eventWindows[viewMode] = LoadedEvents(ranges, result)
                    loadError = null
                    loaded = true
                }
                fillMissingCalendar()
            } catch (c: CancellationException) {
                throw c
            } catch (e: Exception) {
                if (generation == loadGeneration) {
                    loadError = e.userMessage()
                    loaded = true
                }
            } finally {
                if (generation == loadGeneration) loadingEvents = false
            }
        }
    }

    private class EventSnapshot(
        val events: List<CalendarEvent>,
        val byDay: Map<LocalDate, List<CalendarEvent>>,
    )

    private fun prepareEvents(agenda: List<CalendarEvent>, grid: List<CalendarEvent>, from: LocalDate, to: LocalDate): EventSnapshot {
        val merged = projectSchoolEvents((agenda + grid).distinctBy { it.key() }, ZoneId.systemDefault())
            .sortedWith(compareBy({ it.startMillis }, { it.id }))
        return EventSnapshot(merged, indexByDay(merged, ZoneId.systemDefault(), from, to))
    }

    private class LoadedEvents(val ranges: EventRanges, val snapshot: EventSnapshot)

    private fun rememberCourseSlots(events: List<CalendarEvent>) {
        val next = assignCourseSlots(events.mapNotNull { it.school?.course }, courseColorSlots)
        if (next != courseColorSlots) courseColorSlots = next
    }

    private fun fillMissingCalendar() {
        val current = editor ?: return
        if (current.id != null || current.calendarId != null) return
        val id = defaultCalendarId() ?: return
        editor = current.copy(calendarId = id)
        if (editorInitial?.calendarId == null) editorInitial = editorInitial?.copy(calendarId = id)
    }

    /** The stored draft and why editing is blocked, read together in one background hop. */
    suspend fun detailsMeta(id: Long): Pair<EventDraft?, EditBlockReason?> = withContext(Dispatchers.IO) {
        runCatching { repo.getEvent(id) }.getOrNull() to
            runCatching { repo.editBlockReason(id) }.getOrDefault(EditBlockReason.INVALID_PROVIDER_DATA)
    }

    // endregion

    // region date navigation

    internal fun refreshCurrentDate(current: LocalDate = LocalDate.now()) {
        if (today == current) return
        Snapshot.withMutableSnapshot {
            positions.toMap().forEach { (mode, position) ->
                if (position.selected == today) positions[mode] = position.copy(selected = current)
            }
            today = current
        }
        ensureLoaded()
    }

    fun select(date: LocalDate) {
        if (continuousTimeline()) timelineNavigationRequest++
        selectedDate = date
        ensureLoaded()
    }

    fun consumeTimelineNavigation(request: Int) {
        if (request == timelineNavigationRequest) handledTimelineNavigation = request
    }

    fun showTimelineRange(first: LocalDate, last: LocalDate, load: Boolean) {
        if (!continuousTimeline()) return
        // Day follows its own strip once it rests.
        val date = if (load && viewMode == ViewMode.Day && timelineNavigationRequest == handledTimelineNavigation) first else selectedDate
        positions[viewMode] = ViewPosition(date, first, last)
        if (!load || coversRange(first.minusDays(14), last.plusDays(14))) return
        targetWindow = first.minusDays(60)..last.plusDays(120)
        if (loadingEvents || loadError != null) return
        loadEvents()
    }

    fun goToday() {
        refreshCurrentDate()
        Snapshot.withMutableSnapshot {
            consumeDayFocus(dayFocusRequest)
            if (continuousTimeline()) timelineNavigationRequest++
            selectedDate = today
            todayMode = viewMode
            todayTimelineNavigation = if (continuousTimeline()) timelineNavigationRequest else -1
            todayRequest++
        }
        ensureLoaded()
    }

    fun step(delta: Int) {
        refreshCurrentDate()
        when (viewMode) {
            ViewMode.Month -> {
                val next = month.plusMonths(delta.toLong())
                select(if (next == YearMonth.from(today)) today else next.atDay(1))
            }
            ViewMode.Week -> select((if (continuousTimeline()) timelineViewportStart ?: selectedDate else selectedDate).plusWeeks(delta.toLong()))
            ViewMode.Day -> select((if (continuousTimeline()) timelineViewportStart ?: selectedDate else selectedDate).plusDays(delta.toLong()))
        }
    }

    fun changeViewMode(mode: ViewMode) {
        if (mode == viewMode) return
        refreshCurrentDate()
        Snapshot.withMutableSnapshot {
            dayParent = null
            switchView(mode)
        }
        ensureLoaded()
        settings.lastView = mode.name
    }

    private fun switchView(mode: ViewMode) {
        if (mode == viewMode) return
        if (timelineNavigationRequest != handledTimelineNavigation && isTimelineToday(viewMode, timelineNavigationRequest)) {
            selectedDate = timelineViewportStart ?: selectedDate
        }
        handledTimelineNavigation = timelineNavigationRequest
        val outgoing = eventWindows[viewMode]
        val destination = timelineStartFor(mode)?.takeIf { continuousTimeline(mode) } ?: selectedDateFor(mode)
        val existing = eventWindows[mode]
        if (existing?.ranges?.covers(destination..destination) != true &&
            outgoing?.ranges?.covers(destination..destination) == true
        ) eventWindows[mode] = outgoing
        viewMode = mode
        targetWindow = eventWindows[mode]?.ranges?.agenda
    }

    private fun launchViewMode(): ViewMode = settings.lastView.let { name -> ViewMode.entries.firstOrNull { it.name == name } } ?: ViewMode.Day

    // endregion

    // region navigation

    internal fun openDay(date: LocalDate, ring: Boolean = false) {
        Snapshot.withMutableSnapshot {
            if (viewMode != ViewMode.Day) dayParent = viewMode
            switchView(ViewMode.Day)
            positions[ViewMode.Day] = ViewPosition(date, date)
            if (continuousTimeline()) timelineNavigationRequest++
            targetWindow = initialWindow()
            dayFocusRing = ring
            dayFocusRequest++
        }
        ensureLoaded()
    }

    internal fun changeDayCalendarExpansion(expanded: Boolean) {
        if (calendarExpanded == expanded) return
        Snapshot.withMutableSnapshot {
            if (expanded) {
                selectedDate = timelineActionDate()
                handledTimelineNavigation = timelineNavigationRequest
            } else if (timelineViewportStart != selectedDate) {
                positions[ViewMode.Day] = ViewPosition(selectedDate, selectedDate)
                timelineNavigationRequest++
            }
            calendarExpanded = expanded
            targetWindow = initialWindow()
        }
        ensureLoaded()
    }

    fun selectAndFocus(date: LocalDate) {
        if (viewMode == ViewMode.Day) { dayFocusRing = false; dayFocusRequest++ }
        select(date)
    }

    fun openCalendar() {
        stack.clear()
        editor = null
        editorInitial = null
    }

    fun open(screen: Screen) {
        if (stack.lastOrNull() != screen) stack.add(screen)
    }

    fun back() {
        if (stack.isEmpty()) {
            val parent = dayParent ?: return
            Snapshot.withMutableSnapshot {
                dayParent = null
                switchView(parent)
            }
            ensureLoaded()
            return
        }
        val top = stack.removeAt(stack.lastIndex)
        if (top == Screen.Editor) { editor = null; editorInitial = null }
    }

    fun openSearch() {
        // Each search starts empty rather than showing the last one.
        updateSearch("")
        open(Screen.Search)
        loadSearchPool(force = true)
    }

    /** Whether app links can open now: setup is complete and the calendar has loaded. */
    val linksReady: Boolean get() = aiIntroDone && hasPermission && !needsNotificationStep && loaded

    /** Queues [retry] until links are ready, or refuses while an edited event is open. */
    private fun linkBlocked(retry: () -> Unit): Boolean {
        if (!linksReady) pendingLink = retry
        else if (stack.contains(Screen.Editor) && editorDirty) message(context.getString(R.string.msg_finish_editing))
        else return false
        return true
    }

    fun handleLink(eventId: Long, begin: Long, create: Boolean, createDate: LocalDate?) {
        if (linkBlocked { handleLink(eventId, begin, create, createDate) }) return
        if (stack.contains(Screen.Editor)) { editor = null; editorInitial = null }
        stack.clear()
        if (create) {
            newEvent(createDate ?: LocalDate.now())
            return
        }
        if (eventId <= 0) return
        launchAction {
            val event = withContext(Dispatchers.IO) {
                val draft = repo.getEvent(eventId) ?: return@withContext null
                val occurrenceStart = if (begin > 0) begin else draft.startMillis
                val calendarIds = setOf(draft.calendarId)
                val occurrence = repo.events(occurrenceStart - 1, occurrenceStart + 1, calendarIds, school = true)
                    .firstOrNull { it.id == eventId && it.startMillis == occurrenceStart }
                if (occurrence?.school != null) {
                    val zone = ZoneId.systemDefault()
                    val day = occurrence.span(zone).first
                    val surrounding = repo.events(day.minusDays(1).atStartOfDay(zone).toInstant().toEpochMilli(),
                        day.plusDays(2).atStartOfDay(zone).toInstant().toEpochMilli(), calendarIds, school = true)
                    projectSchoolEvents(surrounding, zone).firstOrNull { projected ->
                        projected.school?.periods?.any { it.eventId == eventId && it.startMillis == occurrenceStart } == true
                    }?.let { return@withContext it }
                }
                if (occurrence != null) return@withContext occurrence
                val cal = repo.calendars().firstOrNull { it.id == draft.calendarId }
                CalendarEvent(eventId, draft.calendarId, draft.title, draft.description, draft.location, occurrenceStart,
                    occurrenceStart + (draft.endMillis - draft.startMillis), draft.allDay, draft.timeZone, draft.rrule,
                    cal?.color ?: 0, cal?.name ?: "")
            }
            if (event == null) {
                message(context.getString(R.string.msg_event_missing))
            } else {
                select(event.span(ZoneId.systemDefault()).first)
                open(Screen.Details(event))
            }
        }
    }

    fun handleSchoolLink(date: LocalDate?, messages: Boolean) {
        require(date == null || date.year in 1900..2100)
        if (linkBlocked { handleSchoolLink(date, messages) }) return
        openCalendar()
        if (date != null) openDay(date, ring = true)
        else {
            settingsPage = if (messages) SettingsPage.Messages else SettingsPage.School
        }
    }

    fun deliverPendingLink() {
        if (!linksReady) return
        pendingLink?.let { link -> pendingLink = null; link() }
    }

    // endregion

    // region editor

    val writableCalendars: List<DeviceCalendar> get() = calendars.filter { it.writable }

    private fun defaultCalendarId(): Long? {
        val writable = writableCalendars
        val last = settings.lastCalendar
        return writable.firstOrNull { it.id == last }?.id
            ?: writable.firstOrNull { it.id in activeIds && !it.local }?.id
            ?: writable.firstOrNull { it.id in activeIds }?.id
            ?: writable.firstOrNull()?.id
    }

    fun newEvent(date: LocalDate) {
        val now = LocalDateTime.now()
        // Today starts at the next half hour, and the default hour stops at midnight rather than running into tomorrow.
        val startTime = if (date != now.toLocalDate()) LocalTime.of(9, 0)
            else LocalTime.MIN.plusMinutes((((now.hour * 60 + now.minute) / 30 + 1) * 30L).coerceAtMost(23 * 60 + 30L))
        val start = date.atTime(startTime)
        val end = minOf(start.plusHours(1), date.atTime(LocalTime.of(23, 59)))
        val state = EditorState(
            id = null, calendarId = defaultCalendarId(), lockedCalendar = false,
            startDate = date, startTime = startTime, endDate = end.toLocalDate(), endTime = end.toLocalTime(),
            timeZone = TimeZone.getDefault().id, rrule = null, originalRrule = null,
            reminders = if (remindersEnabled && notificationsGranted) listOf(15) else emptyList(),
        )
        editor = state
        editorInitial = state
        open(Screen.Editor)
    }

    fun addSchoolSuggestion(suggestion: io.github.sunsind.sunlith.school.SchoolNoticeSuggestion) {
        newEvent(suggestion.date)
        updateEditor { it.copy(title = suggestion.title, notes = suggestion.detail, allDay = true,
            endDate = suggestion.date, reminders = emptyList()) }
    }

    fun editEvent(id: Long) {
        launchAction {
            val draft = withContext(Dispatchers.IO) { repo.getEvent(id) }
            if (draft == null) { message(context.getString(R.string.msg_event_missing)); return@launchAction }
            val state = EditorState.from(draft, ZoneId.systemDefault())
            editor = state
            editorInitial = state
            open(Screen.Editor)
        }
    }

    fun updateEditor(transform: (EditorState) -> EditorState) {
        editor = editor?.let(transform)
    }

    val editorDirty: Boolean get() = editor != null && editor != editorInitial

    fun closeEditor() {
        editor = null
        editorInitial = null
        stack.remove(Screen.Editor)
    }

    fun saveEditor() {
        val state = editor ?: return
        if (saving || state.problem(writableCalendars) != null) return
        if (state.id != null && !editorDirty) { closeEditor(); return }
        saving = true
        launchAction({ saving = false }) {
            val draft = state.toDraft(ZoneId.systemDefault())
            withContext(Dispatchers.IO) { repo.saveEvent(draft) }
            settings.lastCalendar = draft.calendarId
            closeEditor()
            if (state.id != null) stack.removeAll { it is Screen.Details && it.event.id == state.id }
            refreshApp()
            invalidateSearch()
            select(state.startDate)
            reload()
            message(context.getString(R.string.msg_saved))
        }
    }

    fun deleteEvent(id: Long) {
        launchAction {
            withContext(Dispatchers.IO) { repo.deleteEvent(id) }
            if (editor?.id == id) closeEditor()
            stack.removeAll { it is Screen.Details && it.event.id == id }
            refreshApp()
            invalidateSearch()
            reload()
            message(context.getString(R.string.msg_deleted))
        }
    }

    // endregion

    // region calendars and settings

    fun setCalendarActive(id: Long, active: Boolean) {
        val next = if (active) activeIds + id else activeIds - id
        settings.selectedCalendarIds = next
        settings.hasChosenCalendars = true
        activeIds = next
        invalidateSearch()
        refreshApp()
        reload()
    }

    val hasSunlithCalendar: Boolean get() = calendars.any { it.local && it.writable && it.account == "Sunlith" }

    fun createLocalCalendar(showInApp: Boolean = true, onCreated: (Long) -> Unit = {}) {
        launchAction {
            if (!showInApp && !settings.hasChosenCalendars) {
                settings.selectedCalendarIds = activeIds
                settings.hasChosenCalendars = true
            }
            val id = withContext(Dispatchers.IO) { repo.createLocalCalendar() }
            if (showInApp && settings.hasChosenCalendars && id !in activeIds) {
                settings.selectedCalendarIds = activeIds + id
                activeIds = activeIds + id
            }
            val cals = withContext(Dispatchers.IO) { repo.calendars() }
            calendars = cals
            refreshApp()
            reload()
            onCreated(id)
            message(context.getString(R.string.msg_local_created))
        }
    }

    // endregion

    // region search

    private fun clearSearch() {
        searchGeneration++
        searchJob?.cancel()
        searchJob = null
        searchPool = null
        searchResults = emptyList()
        searchLoading = false
    }

    private fun loadSearchPool(force: Boolean) {
        if (!hasPermission) {
            clearSearch()
            return
        }
        if (!force && searchJob?.isActive == true) return
        val generation = ++searchGeneration
        searchJob?.cancel()
        searchLoading = true
        searchJob = viewModelScope.launch {
            try {
                val pool = withContext(Dispatchers.IO) {
                    val zone = ZoneId.systemDefault()
                    val today = LocalDate.now()
                    val ids = settings.visibleCalendarIds(repo.calendars())
                    projectSchoolEvents(repo.events(today.minusDays(365).atStartOfDay(zone).toInstant().toEpochMilli(),
                        today.plusDays(730).atStartOfDay(zone).toInstant().toEpochMilli(), ids, school = true), zone)
                }
                if (generation != searchGeneration) return@launch
                rememberCourseSlots(pool)
                searchPool = pool
                filterSearch()
            } catch (c: CancellationException) {
                throw c
            } catch (e: Exception) {
                if (generation == searchGeneration) message(e.userMessage())
            } finally {
                if (generation == searchGeneration) searchLoading = false
            }
        }
    }

    fun updateSearch(query: String) {
        searchQuery = query
        if (searchPool == null) loadSearchPool(force = false) else filterSearch()
    }

    private fun filterSearch() {
        val terms = searchQuery.trim().lowercase().split(Regex("\\s+")).filter(String::isNotEmpty)
        val pool = searchPool.orEmpty()
        searchResults = if (terms.isEmpty()) emptyList() else pool.filter { e ->
            val hay = "${e.title}\n${e.location}\n${TodoMarker.strip(e.description)}\n${e.calendarName}".lowercase()
            terms.all { it in hay }
        }.take(400)
    }

    // endregion

    // region import / export

    fun startImport(uri: Uri) {
        importUi = ImportUi(uri)
        viewModelScope.launch {
            try {
                val preview = withContext(Dispatchers.IO) {
                    val stream = context.contentResolver.openInputStream(uri) ?: throw FileNotFoundException()
                    stream.use { transfer.inspect(it) }
                }
                if (importUi?.uri != uri) return@launch
                val dest = defaultCalendarId()
                importUi = ImportUi(uri, busy = false, preview = preview, calendarId = dest)
            } catch (c: CancellationException) {
                throw c
            } catch (e: Exception) {
                if (importUi?.uri == uri) importUi = ImportUi(uri, busy = false, error = e.userMessage(R.string.error_read_file))
            }
        }
    }

    fun chooseImportCalendar(id: Long) {
        importUi = importUi?.copy(calendarId = id)
    }

    fun runImport() {
        val state = importUi ?: return
        val calendarId = state.calendarId ?: return
        if (state.busy) return
        importUi = state.copy(busy = true)
        viewModelScope.launch {
            try {
                val report = withContext(Dispatchers.IO) {
                    val stream = context.contentResolver.openInputStream(state.uri) ?: throw FileNotFoundException()
                    stream.use { transfer.import(it, calendarId) }
                }
                importUi = importUi?.copy(busy = false, report = report)
                invalidateSearch()
                refreshApp()
                reload()
            } catch (c: CancellationException) {
                throw c
            } catch (e: Exception) {
                importUi = importUi?.copy(busy = false, error = e.userMessage())
            }
        }
    }

    fun dismissImport() { importUi = null }

    fun export(uri: Uri, calendarIds: Set<Long>) {
        if (exporting) return
        exporting = true
        launchAction({ exporting = false }) {
            val report = withContext(Dispatchers.IO) {
                val staged = File.createTempFile("sunlith-export-", ".ics", context.cacheDir)
                try {
                    val exported = staged.outputStream().use { transfer.export(it, calendarIds) }
                    val stream = runCatching { context.contentResolver.openOutputStream(uri, "wt") }.getOrNull()
                        ?: context.contentResolver.openOutputStream(uri) ?: throw FileNotFoundException()
                    stream.use { output -> staged.inputStream().use { it.copyTo(output) } }
                    exported
                } finally { staged.delete() }
            }
            message(context.resources.getQuantityString(R.plurals.msg_exported, report.exported, report.exported))
            if (report.skipped > 0) {
                message(context.getString(R.string.msg_export_skipped))
            }
        }
    }

    // endregion

    fun message(text: String, actionLabel: String? = null, action: (() -> Unit)? = null) {
        messageChannel.trySend(UiMessage(text, actionLabel, action))
    }

    private fun launchAction(onFinally: () -> Unit = {}, block: suspend () -> Unit) = viewModelScope.launch {
        try {
            block()
        } catch (c: CancellationException) {
            throw c
        } catch (e: Exception) {
            message(e.userMessage())
        } finally {
            onFinally()
        }
    }

    private fun refreshWidgets() {
        AppRefresh.executor.execute { runCatching { AgendaWidgetReceiver.updateAll(context) } }
    }

    private fun refreshApp() {
        runCatching { AppRefresh.refresh(context) }
    }

    private fun Throwable.userMessage(fallback: Int = R.string.error_generic): String = when (this) {
        is SecurityException -> context.getString(R.string.error_permission)
        is FileNotFoundException -> context.getString(R.string.error_read_file)
        else -> message?.takeIf { it.isNotBlank() && it.length < 300 } ?: context.getString(fallback)
    }

    companion object {
        private const val KEY_POSITION = "calendar_position"
    }
}
