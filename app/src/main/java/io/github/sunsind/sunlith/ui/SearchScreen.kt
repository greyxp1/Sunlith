package io.github.sunsind.sunlith.ui

import android.os.Build
import android.view.HapticFeedbackConstants
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import io.github.sunsind.sunlith.R
import io.github.sunsind.sunlith.data.CalendarEvent
import io.github.sunsind.sunlith.data.TodoMarker
import io.github.sunsind.sunlith.data.key
import io.github.sunsind.sunlith.data.span
import io.github.sunsind.sunlith.ui.calendar.CalendarEventCard
import io.github.sunsind.sunlith.ui.calendar.PillGestureLatch
import io.github.sunsind.sunlith.ui.calendar.canceled
import io.github.sunsind.sunlith.ui.calendar.searchPullDistance
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

@Composable
fun SearchScreen(vm: SunlithViewModel, active: Boolean) {
    val p = MaterialTheme.colorScheme
    val f = rememberFormats()
    val focus = remember { FocusRequester() }
    val focusManager = LocalFocusManager.current
    val searchLabel = stringResource(R.string.search_hint)
    val keyboard = LocalSoftwareKeyboardController.current
    val zone = ZoneId.systemDefault()
    val today = LocalDate.now()
    val groups = remember(vm.search.results) {
        val upcoming = vm.search.results.filter { !it.span(zone).last.isBefore(today) }.sortedBy { it.startMillis }
        val past = vm.search.results.filter { it.span(zone).last.isBefore(today) }.sortedByDescending { it.startMillis }
        upcoming to past
    }

    // Pulling down on the bar, or past the top of the results, hands the bar back to the toolbar and follows the
    // finger down. Let go far enough or fast enough and search closes, otherwise it glides back open.
    val morph = vm.searchMorph
    val scope = rememberCoroutineScope()
    val haptics = LocalHapticFeedback.current
    val hostView = LocalView.current
    val activation = with(LocalDensity.current) { 76.dp.toPx() }
    val retreat = with(LocalDensity.current) { 26.dp.toPx() }
    val catchDistance = with(LocalDensity.current) { 24.dp.toPx() }
    var latched by remember { mutableStateOf(false) }
    val catchProgress by animateFloatAsState(if (latched) 1f else 0f, MaterialTheme.motionScheme.fastSpatialSpec())
    val pull = remember(vm, keyboard, haptics, hostView, activation, retreat) {
        object : NestedScrollConnection {
            var pulled = 0f
            private var latch = PillGestureLatch(activation, retreat, fixedThreshold = true)
            private var armed = 0
            private var dragging = false
            // Where the bar was when the pull began, which is partway up while it is still gliding into place.
            var from = 1f
            val tracker = VelocityTracker()
            fun drag(delta: Float, time: Long): Float {
                val before = pulled
                pulled = (pulled + delta).coerceAtLeast(0f)
                if (!dragging && pulled > 0f) {
                    dragging = true
                    keyboard?.hide()
                    from = morph.progress
                }
                tracker.addPosition(time, Offset(0f, pulled))
                val next = latch.update(pulled) { it > 0 }
                if (next != armed) {
                    if (next > 0) haptics.performHapticFeedback(HapticFeedbackType.GestureThresholdActivate)
                    else if (Build.VERSION.SDK_INT >= 34) hostView.performHapticFeedback(HapticFeedbackConstants.GESTURE_THRESHOLD_DEACTIVATE)
                }
                armed = next
                latched = next > 0
                if (dragging) morph.pullTo(from - searchPullDistance(pulled, activation) / morph.climb) {
                    -catchDistance * catchProgress / morph.climb
                }
                return pulled - before
            }
            fun release(velocity: Float = tracker.calculateVelocity().y, canceled: Boolean = false) {
                if (dragging) {
                    morph.freezePull()
                    val flick = velocity > 500f && pulled >= activation * 0.30f &&
                        pulled + (velocity * 0.085f).coerceAtMost(activation * 0.65f) >= latch.threshold
                    if (!canceled && (armed > 0 || flick)) {
                        if (armed == 0) haptics.performHapticFeedback(HapticFeedbackType.GestureThresholdActivate)
                        vm.back()
                    } else scope.launch {
                        focus.requestFocus()
                        keyboard?.show()
                        morph.settle(1f)
                    }
                }
                pulled = 0f
                dragging = false
                from = 1f
                armed = 0
                latched = false
                latch = PillGestureLatch(activation, retreat, fixedThreshold = true)
                tracker.resetTracking()
            }
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset =
                if (pulled > 0f && available.y < 0f) Offset(0f, drag(available.y, System.currentTimeMillis())) else Offset.Zero
            override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset =
                if (source == NestedScrollSource.UserInput && available.y > 0f) Offset(0f, drag(available.y, System.currentTimeMillis())) else Offset.Zero
            override suspend fun onPreFling(available: Velocity): Velocity {
                if (!dragging) return Velocity.Zero
                release(available.y)
                return available
            }
        }
    }
    val opened by remember(morph) { derivedStateOf { morph.progress >= 1f } }
    LaunchedEffect(focus, active) {
        // Start the IME as the search bar moves into place.
        if (active) focus.requestFocus() else focusManager.clearFocus()
    }
    Scaffold(
        // The results are gone by the time the bar is halfway back down.
        Modifier.nestedScroll(pull).graphicsLayer { alpha = ((morph.progress - 0.5f) * 2f).coerceIn(0f, 1f) },
        containerColor = Color.Transparent,
        topBar = {
            Column(Modifier.statusBarsPadding().pointerInput(pull) {
                detectVerticalDragGestures(onDragEnd = { pull.release() }, onDragCancel = { pull.release(canceled = true) }) { change, amount ->
                    change.consume()
                    pull.drag(amount, change.uptimeMillis)
                }
            }.graphicsLayer { alpha = if (opened) 1f else 0f }) {
                // The same pill as the calendar's toolbar, which grows into this bar when it is swiped up.
                Box(Modifier.padding(SEARCH_BAR_MARGIN).fillMaxWidth().height(56.dp).clip(CircleShape)
                    .background(MaterialTheme.colorScheme.card).border(1.dp, MaterialTheme.colorScheme.outlineVariant, CircleShape)) {
                    BasicTextField(
                        value = vm.search.query, onValueChange = vm.search::update,
                        modifier = Modifier.fillMaxSize().padding(horizontal = if (vm.search.query.isEmpty()) 20.dp else 52.dp)
                            .focusRequester(focus).semantics { contentDescription = searchLabel },
                        textStyle = MaterialTheme.typography.bodyLarge.copy(color = p.onSurface, textAlign = if (vm.search.query.isEmpty()) TextAlign.Start else TextAlign.Center),
                        cursorBrush = SolidColor(p.primary), singleLine = true,
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                        keyboardActions = KeyboardActions(onSearch = { keyboard?.hide() }),
                        decorationBox = { field ->
                            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                if (vm.search.query.isEmpty()) {
                                    Box {
                                        Text(searchLabel, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyLarge,
                                            maxLines = 1, overflow = TextOverflow.Ellipsis)
                                        Box(Modifier.matchParentSize(), contentAlignment = Alignment.CenterStart) { field() }
                                    }
                                } else field()
                            }
                        },
                    )
                    if (vm.search.query.isNotEmpty()) {
                        IconButton(onClick = { vm.search.update("") }, Modifier.align(Alignment.CenterEnd).padding(end = 4.dp)) {
                            Icon(Icons.Outlined.Close, stringResource(R.string.action_clear), tint = p.onSurfaceVariant)
                        }
                    }
                }
            }
        },
    ) { pad ->
        val listState = rememberLazyListState()
        val direction = LocalLayoutDirection.current
        // Reserve space at the end of the list so even the last result can scroll clear of the keyboard.
        val bottomInset = maxOf(pad.calculateBottomPadding(), WindowInsets.ime.asPaddingValues().calculateBottomPadding())
        LazyColumn(
            Modifier.fillMaxSize().padding(
                start = pad.calculateStartPadding(direction), top = pad.calculateTopPadding(), end = pad.calculateEndPadding(direction),
            )
                .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
                .drawWithContent {
                    drawContent()
                    if (listState.canScrollBackward) {
                        val fade = 18.dp.toPx().coerceAtMost(size.height)
                        if (fade > 0f) drawRect(Brush.verticalGradient(listOf(Color.Transparent, Color.Black), endY = fade),
                            size = Size(size.width, fade), blendMode = BlendMode.DstIn)
                    }
                },
            state = listState,
            contentPadding = PaddingValues(start = 12.dp, top = 8.dp, end = 12.dp, bottom = 8.dp + bottomInset),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            when {
                vm.search.query.isBlank() -> {}
                // Loading no longer draws a line under the search field. Results already shown stay while it runs.
                vm.search.loading && vm.search.results.isEmpty() -> item(key = "loading") { ListLoading() }
                vm.search.results.isEmpty() -> item { Hint(stringResource(R.string.search_none)) }
            }
            results(groups.first, null, f, vm)
            results(groups.second, R.string.search_past, f, vm)
        }
    }
}

private fun androidx.compose.foundation.lazy.LazyListScope.results(events: List<CalendarEvent>, header: Int?, f: Formats, vm: SunlithViewModel) {
    if (events.isEmpty()) return
    if (header != null) item(key = "h-$header") { SectionTitle(stringResource(header), inset = 16.dp) }
    items(events, key = { "${header ?: 0}-" + it.key() }) { e ->
        val day = e.span(ZoneId.systemDefault()).first
        Column {
            Text(
                f.dayShortWithYear(day), color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.labelLarge,
                modifier = Modifier.padding(start = 4.dp, top = 8.dp, bottom = 4.dp).semantics { heading() },
            )
            CalendarEventCard(e, day, f, onClick = { vm.open(Screen.Details(e)) })
        }
    }
}

@Composable
private fun Hint(text: String) {
    Text(text, Modifier.fillMaxWidth().padding(16.dp), color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
}

/** Space around the search bar, which the calendar's toolbar grows into when swiped up. */
internal val SEARCH_BAR_MARGIN = 8.dp

/** Events from a year back to two years ahead, loaded by [loadPool] once per search and filtered as the query changes. */
class EventSearch internal constructor(
    private val scope: CoroutineScope,
    private val canLoad: () -> Boolean,
    private val onLoaded: (List<CalendarEvent>) -> Unit,
    private val onError: (Exception) -> Unit,
    private val loadPool: suspend () -> List<CalendarEvent>,
) {
    var query by mutableStateOf(""); private set
    var results by mutableStateOf(emptyList<CalendarEvent>()); private set
    var loading by mutableStateOf(false); private set
    private var pool: List<CalendarEvent>? = null
    private var job: Job? = null
    private var generation = 0L

    fun clear() {
        generation++
        job?.cancel()
        job = null
        pool = null
        results = emptyList()
        loading = false
    }

    /** Rereads the events, or with [force] off only when no read is already under way. */
    fun load(force: Boolean) {
        if (!canLoad()) {
            clear()
            return
        }
        if (!force && job?.isActive == true) return
        val current = ++generation
        job?.cancel()
        loading = true
        job = scope.launch {
            try {
                val events = loadPool()
                if (current != generation) return@launch
                onLoaded(events)
                pool = events
                filter()
            } catch (c: CancellationException) {
                throw c
            } catch (e: Exception) {
                if (current == generation) onError(e)
            } finally {
                if (current == generation) loading = false
            }
        }
    }

    fun update(query: String) {
        this.query = query
        if (pool == null) load(force = false) else filter()
    }

    private fun filter() {
        val terms = query.trim().lowercase().split(Regex("\\s+")).filter(String::isNotEmpty)
        results = if (terms.isEmpty()) emptyList() else pool.orEmpty().filter { e ->
            val hay = "${e.title}\n${e.location}\n${TodoMarker.strip(e.description)}\n${e.calendarName}".lowercase()
            terms.all { it in hay }
        }.take(400)
    }
}

/** How far the toolbar has risen into the search bar, shared by the toolbar, the calendar backdrop and search itself. */
@Stable
class SearchMorph {
    // Finishes within a pixel of either end. The default threshold stops 1% short, a visible jump over the whole climb.
    private val rise = Animatable(0f, visibilityThreshold = 0.0005f)
    private class Pull(val position: Float, val catchOffset: () -> Float)
    private var pull by mutableStateOf<Pull?>(null)
    val progress: Float get() = (pull?.let { it.position + it.catchOffset() } ?: rise.value).coerceIn(0f, 1f)

    fun pullTo(position: Float, catchOffset: () -> Float) {
        pull = Pull(position, catchOffset)
    }

    // Commit and cancel start from the frame the user released, before the latch springs back.
    fun freezePull() {
        if (pull != null) pullTo(progress) { 0f }
    }

    suspend fun settle(target: Float, motion: Boolean = true) {
        val held = pull
        if (held != null) {
            rise.snapTo(progress)
            if (pull !== held) return
            pull = null
        }
        if (motion) rise.animateTo(target, Glide) else rise.snapTo(target)
    }

    /** Pixels the toolbar travels from rest to the search bar, so search can follow a finger pulling it back down. */
    var climb = 1f

    companion object {
        /** A quick glide that settles without overshooting, so the toolbar never looks caught at either end. */
        val Glide = spring(stiffness = Spring.StiffnessMediumLow, visibilityThreshold = 0.0005f)
    }
}
