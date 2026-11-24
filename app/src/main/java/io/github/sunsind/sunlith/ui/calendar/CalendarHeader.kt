package io.github.sunsind.sunlith.ui.calendar

import android.os.Build
import android.view.HapticFeedbackConstants
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.outlined.ArrowDownward
import androidx.compose.material.icons.outlined.ArrowUpward
import androidx.compose.material.icons.outlined.Checklist
import androidx.compose.material.icons.outlined.MailOutline
import androidx.compose.material.icons.outlined.Settings as SettingsIcon
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import io.github.sunsind.sunlith.R
import io.github.sunsind.sunlith.SettingsActivity
import io.github.sunsind.sunlith.school.SchoolScheduleManager
import io.github.sunsind.sunlith.school.SchoolMessagesManager
import io.github.sunsind.sunlith.ui.Formats
import io.github.sunsind.sunlith.ui.LocalPalette
import io.github.sunsind.sunlith.ui.SEARCH_BAR_MARGIN
import io.github.sunsind.sunlith.ui.Screen
import io.github.sunsind.sunlith.ui.SunlithViewModel
import io.github.sunsind.sunlith.ui.ViewMode
import java.time.YearMonth
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private fun stepLabels(vm: SunlithViewModel): Pair<Int, Int> = when (vm.viewMode) {
    ViewMode.Month -> R.string.action_prev_month to R.string.action_next_month
    ViewMode.Week -> R.string.action_prev_week to R.string.action_next_week
    ViewMode.Day -> R.string.action_prev_day to R.string.action_next_day
}

/**
 * Floating "Today" shortcut. It appears only when the shown period or the agenda's visible dates leave today, sits
 * centred above the bottom edge, and keeps clear of the add button on the right.
 */
@Composable
internal fun TodayPill(direction: TodayDirection?, motion: Boolean, onClick: () -> Unit, modifier: Modifier) {
    var lastDirection by remember { mutableStateOf(TodayDirection.Up) }
    val arrow = direction ?: lastDirection
    SideEffect { if (direction != null) lastDirection = direction }
    if (motion) {
        AnimatedVisibility(
            direction != null, modifier,
            enter = fadeIn(tween(150)),
            exit = fadeOut(tween(120)),
        ) { TodayPillContent(arrow, onClick) }
    } else if (direction != null) {
        Box(modifier) { TodayPillContent(arrow, onClick) }
    }
}

@Composable
private fun TodayPillContent(direction: TodayDirection, onClick: () -> Unit) {
    val p = LocalPalette.current
    val label = stringResource(R.string.action_today)
    val shape = CircleShape
    Box(
        Modifier.padding(start = 88.dp, end = 88.dp, bottom = 20.dp).minimumInteractiveComponentSize()
            .shadow(3.dp, shape).clip(shape).background(p.surface).border(1.dp, p.line, shape)
            .clickable(role = Role.Button, onClick = onClick)
            .clearAndSetSemantics { contentDescription = label; role = Role.Button; onClick { onClick(); true } }
            .padding(12.dp),
        contentAlignment = Alignment.Center,
    ) {
        Icon(todayIcon(direction),
            contentDescription = null, tint = p.ink, modifier = Modifier.size(22.dp))
    }
}

/** The month shown, turning with a swipe in progress ([shift] periods away) rather than after it settles. */
@Composable
private fun rememberShownMonth(vm: SunlithViewModel, shift: () -> Int): androidx.compose.runtime.State<YearMonth> {
    val currentShift by rememberUpdatedState(shift)
    return remember(vm) { derivedStateOf {
        when {
            vm.continuousTimeline() -> YearMonth.from(vm.timelineViewportStart ?: vm.selectedDate)
            vm.viewMode == ViewMode.Month -> vm.month.plusMonths(currentShift().toLong())
            vm.viewMode == ViewMode.Week -> YearMonth.from(vm.selectedDate.plusWeeks(currentShift().toLong()))
            else -> YearMonth.from(vm.selectedDate.plusDays(currentShift().toLong()))
        }
    } }
}

@Composable
internal fun BottomBar(
    vm: SunlithViewModel, f: Formats, onTitle: () -> Unit,
    onStep: (Int) -> Unit, onStepView: (Int, Float) -> Unit, viewTarget: (Int) -> ViewMode,
    onDragView: (Float, Float) -> Unit, pillDrag: () -> Float, shift: () -> Int, modifier: Modifier,
) {
    val context = LocalContext.current
    val school = remember(context) { SchoolScheduleManager.get(context.applicationContext) }
    val schoolState by school.state.collectAsStateWithLifecycle()
    val morph = vm.searchMorph
    var armedAxis by remember { mutableIntStateOf(0) }
    val searchCatch by animateFloatAsState(if (armedAxis == 2) 1f else 0f, spring(stiffness = 520f))
    val viewCatch by animateFloatAsState(if (armedAxis == 2) 0f else armedAxis.toFloat(), spring(stiffness = 700f))
    val stepView by rememberUpdatedState(onStepView)
    val dragView by rememberUpdatedState(onDragView)
    val haptics = LocalHapticFeedback.current
    val hostView = LocalView.current
    val p = LocalPalette.current
    val month by rememberShownMonth(vm, shift)
    val scope = rememberCoroutineScope()
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    val density = LocalDensity.current
    val travel = LocalWindowInfo.current.containerSize.width.coerceAtLeast(1).toFloat()
    val direction = if (rtl) 1f else -1f
    val activation = with(density) { 76.dp.toPx() }
    val viewActivation = with(density) { 58.dp.toPx() }
    val retreat = with(density) { 26.dp.toPx() }
    val catchDistance = with(density) { 24.dp.toPx() }
    val margin = with(density) { SEARCH_BAR_MARGIN.roundToPx() }
    val barTop = WindowInsets.statusBars.getTop(density) + margin
    val barWidth = LocalWindowInfo.current.containerSize.width - margin * 2
    // Rising runs from 0 at rest to 1 as the search bar and follows the finger until release.
    val searching = Screen.Search in vm.stack
    val rising by remember { derivedStateOf { morph.progress > 0f } }
    var restTop by remember { mutableFloatStateOf(0f) }
    var restWidth by remember { mutableIntStateOf(0) }
    val climb = { (restTop - barTop).coerceAtLeast(1f) }
    // Search opens as the toolbar sets off, hidden until it arrives, so a finger can already pull it back down.
    fun search() = vm.openSearch()
    // Whether the touch in progress has turned into a swipe, so the buttons it started on never act on it.
    val swiped = remember { booleanArrayOf(false) }
    var tapPending by remember { mutableStateOf(false) }
    fun tap(action: () -> Unit): () -> Unit = {
        if (!swiped[0] && !tapPending) {
            tapPending = true
            scope.launch {
                // Let the tap ripple show before the destination covers the pill.
                delay(80)
                tapPending = false
                action()
            }
        }
    }
    @Composable
    fun ActionIcon(icon: ImageVector, label: Int, unread: Boolean = false, action: () -> Unit) {
        val unreadMail = if (unread) stringResource(R.string.school_mail_unread) else null
        IconButton(
            onClick = tap(action),
            modifier = Modifier.semantics { if (unreadMail != null) stateDescription = unreadMail },
            interactionSource = remember { TapOnlyPress() },
            colors = IconButtonDefaults.iconButtonColors(contentColor = p.ink),
        ) {
            if (unread) BadgedBox(badge = { Badge(containerColor = p.accent) }) { Icon(icon, stringResource(label)) }
            else Icon(icon, stringResource(label))
        }
    }
    val searchLabel = stringResource(R.string.action_search)
    val viewLabels = listOf(stringResource(R.string.view_month), stringResource(R.string.view_week), stringResource(R.string.view_day))
    val fieldStart = { searchPullDistance(activation, activation).coerceAtMost(climb() * 0.75f) }
    val searchFieldAmount = {
        // The upward pull moves the pill first. Widen it into the search field after it catches.
        val start = fieldStart()
        ((morph.progress * climb() - start) / (climb() - start)).coerceIn(0f, 1f)
    }
    val searchLabelAmount = { ((morph.progress * climb() - fieldStart()) / catchDistance).coerceIn(0f, 1f) }
    val (prev, next) = stepLabels(vm)
    val prevLabel = stringResource(prev)
    val nextLabel = stringResource(next)
    Box(
        // Hidden under search's own bar while search is fully open. Pulled down, it takes over from that bar.
        modifier
            .pointerInput(rtl, travel, activation, viewActivation, retreat, haptics, hostView) {
                // Read before the buttons on the toolbar, so a swipe that starts on one takes the touch from it and never
                // also presses it.
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                    swiped[0] = false
                    var total = Offset.Zero
                    // A finger can catch the toolbar halfway through its glide, and drags it from where it is.
                    val from = morph.progress
                    var sideways: Boolean? = null
                    var released = false
                    val viewLatch = PillGestureLatch(viewActivation, retreat)
                    val searchLatch = PillGestureLatch(activation, retreat, fixedThreshold = true)
                    var armed = 0
                    var lastMove = down.uptimeMillis
                    var releasedAt = lastMove
                    val tracker = VelocityTracker().apply { addPosition(down.uptimeMillis, Offset.Zero) }
                    try {
                        while (true) {
                            val change = awaitPointerEvent(PointerEventPass.Initial).changes.firstOrNull { it.id == down.id } ?: break
                            if (!change.pressed) {
                                if (sideways != null) change.consume()
                                releasedAt = change.uptimeMillis
                                released = true
                                break
                            }
                            val movement = change.positionChange()
                            total += movement
                            if (movement != Offset.Zero) {
                                lastMove = change.uptimeMillis
                                tracker.addPosition(lastMove, total)
                            }
                            val first = sideways == null
                            if (first && total.getDistance() < viewConfiguration.touchSlop) continue
                            change.consume()
                            swiped[0] = true
                            val across = sideways ?: (abs(total.x) > abs(total.y)).also { sideways = it }
                            val axis = if (across) total.x * direction else -total.y
                            val distance = (abs(axis) - viewConfiguration.touchSlop).coerceAtLeast(0f) * kotlin.math.sign(axis)
                            val latch = if (across) viewLatch else searchLatch
                            val next = latch.update(distance) { step -> across || step > 0 }
                            if (next != armed) {
                                if (next != 0) haptics.performHapticFeedback(HapticFeedbackType.GestureThresholdActivate)
                                else if (Build.VERSION.SDK_INT >= 34) hostView.performHapticFeedback(
                                    HapticFeedbackConstants.GESTURE_THRESHOLD_DEACTIVATE,
                                )
                            }
                            armed = next
                            armedAxis = if (!across && next > 0) 2 else next
                            if (across) {
                                // The latch controls release and haptics. A reversal keeps the page under the finger.
                                val pagePull = (abs(distance) - viewActivation).coerceAtLeast(0f) * kotlin.math.sign(distance) / travel
                                dragView(distance / travel, pagePull)
                            } else {
                                val resisted = searchPullDistance(distance, activation)
                                morph.pullTo(from + resisted / climb()) { catchDistance * searchCatch / climb() }
                            }
                        }
                    } finally {
                        if (sideways == false) morph.freezePull()
                        armedAxis = 0
                        if (!released && sideways == true) stepView(0, 0f)
                        if (!released && sideways == false) scope.launch { morph.settle(0f) }
                    }
                    if (!released) return@awaitEachGesture
                    val velocity = if (releasedAt - lastMove <= 75) tracker.calculateVelocity() else Velocity.Zero
                    when (sideways) {
                        true -> {
                            val speed = velocity.x / travel * direction
                            val pull = (abs(total.x) - viewConfiguration.touchSlop).coerceAtLeast(0f)
                            val step = if (total.x * direction > 0f) 1 else -1
                            val flick = armed == 0 && pull >= viewActivation * 0.30f && step * speed >= 0.85f &&
                                pull + abs(velocity.x) * 0.08f >= viewLatch.threshold
                            if (flick) haptics.performHapticFeedback(HapticFeedbackType.GestureThresholdActivate)
                            stepView(if (flick) step else armed, speed)
                            if (morph.progress > 0f) scope.launch { morph.settle(0f) }
                        }
                        false -> {
                            val pulled = (-total.y - viewConfiguration.touchSlop).coerceAtLeast(0f)
                            val flick = velocity.y < -500f && pulled >= activation * 0.30f &&
                                pulled + (-velocity.y * 0.085f).coerceAtMost(activation * 0.65f) >= searchLatch.threshold
                            if (armed > 0 || flick) {
                                if (armed == 0) haptics.performHapticFeedback(HapticFeedbackType.GestureThresholdActivate)
                                search()
                            } else scope.launch { morph.settle(0f) }
                        }
                        null -> {}
                    }
                }
            }
            .graphicsLayer {
                translationX = (pillDrag() * 40.dp.toPx() + viewCatch * 8.dp.toPx()) * (1f - morph.progress) * direction
                translationY = -climb() * morph.progress
                alpha = if (searching && morph.progress >= 1f) 0f else 1f
            }
            .onGloballyPositioned { if (morph.progress == 0f) { restTop = it.positionInRoot().y; morph.climb = climb() } }
            .onSizeChanged { if (morph.progress == 0f) restWidth = it.width }
            .layout { measurable, constraints ->
                // While rising, the toolbar widens from its own width to the search bar's.
                val r = searchFieldAmount()
                val placeable = if (r == 0f || restWidth == 0) measurable.measure(constraints)
                    else (restWidth + (barWidth - restWidth) * r).roundToInt().let { measurable.measure(constraints.copy(minWidth = it, maxWidth = it)) }
                layout(placeable.width, placeable.height) { placeable.place(0, 0) }
            }
            .height(56.dp).shadow(3.dp, CircleShape).clip(CircleShape)
            .background(p.surface)
            .border(1.dp, p.line, CircleShape),
    ) {
        Row(Modifier.fillMaxHeight().graphicsLayer { alpha = 1f - searchLabelAmount() }.padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Text(buildAnnotatedString {
                    append(f.shortMonth(month))
                    withStyle(SpanStyle(color = p.muted, fontWeight = FontWeight.Normal, fontSize = 0.72.em)) { append(" " + month.year) }
                },
                Modifier.weight(1f, fill = false).fillMaxHeight().clip(CircleShape)
                    .calendarTap(p.ink, onClickLabel = stringResource(R.string.action_jump_to_date), onClick = tap(onTitle))
                    .semantics {
                        heading()
                        contentDescription = f.monthYear(month)
                        stateDescription = viewLabels[vm.viewMode.ordinal]
                        customActions = buildList {
                            add(CustomAccessibilityAction(searchLabel) { vm.openSearch(); true })
                            add(CustomAccessibilityAction(prevLabel) { onStep(-1); true })
                            add(CustomAccessibilityAction(nextLabel) { onStep(1); true })
                            for (delta in listOf(-1, 1)) {
                                val target = viewTarget(delta)
                                add(CustomAccessibilityAction(viewLabels[target.ordinal]) { onStepView(delta, 0f); true })
                            }
                        }
                    }
                    .wrapContentHeight().padding(horizontal = 12.dp),
                color = p.ink, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            ActionIcon(Icons.Outlined.Checklist, R.string.todo_title, action = vm::openTodos)
            if (schoolState.connected) {
                val inbox by remember(context) { SchoolMessagesManager.get(context).inbox.state }.collectAsStateWithLifecycle()
                ActionIcon(Icons.Outlined.MailOutline, R.string.school_messages_title, inbox.messages.any { it.unread }) {
                    SettingsActivity.open(context, SettingsActivity.Page.Messages)
                }
            }
            ActionIcon(Icons.Outlined.SettingsIcon, R.string.title_settings) { SettingsActivity.open(context) }
        }
        if (rising) Box(Modifier.matchParentSize().graphicsLayer { alpha = searchLabelAmount() }.clearAndSetSemantics { }) {
            val query = vm.searchQuery
            Text(query.ifBlank { stringResource(R.string.search_hint) },
                Modifier.align(Alignment.Center).padding(horizontal = if (query.isBlank()) 20.dp else 52.dp),
                color = if (query.isBlank()) p.faint else p.ink, style = MaterialTheme.typography.bodyLarge,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

private fun todayIcon(direction: TodayDirection) = when (direction) {
    TodayDirection.Up -> Icons.Outlined.ArrowUpward
    TodayDirection.Down -> Icons.Outlined.ArrowDownward
    TodayDirection.Previous -> Icons.AutoMirrored.Outlined.KeyboardArrowLeft
    TodayDirection.Next -> Icons.AutoMirrored.Outlined.KeyboardArrowRight
}

/** Both pill gestures stay armed through small reversals, with a separate distance to rearm. */
internal class PillGestureLatch(
    private val activation: Float, private val retreat: Float, private val fixedThreshold: Boolean = false,
) {
    var threshold = activation
        private set
    private var direction = 0
    private var armed = false
    private var peak = 0f

    fun update(distance: Float, canArm: (Int) -> Boolean): Int {
        val step = distance.compareTo(0f)
        val amount = abs(distance)
        if (step != direction) {
            direction = step
            threshold = activation
            armed = false
        }
        if (armed) {
            peak = maxOf(peak, amount)
            val releasePoint = (if (fixedThreshold) activation else peak) - retreat
            if (amount <= releasePoint || !canArm(step)) {
                armed = false
                if (!fixedThreshold) threshold = maxOf(activation, amount + retreat)
            }
        } else if (amount >= threshold && canArm(step)) {
            armed = true
            peak = amount
        }
        return if (armed) step else 0
    }
}

// The pill resists at first, then follows the remaining distance directly once it catches.
internal fun searchPullDistance(distance: Float, activation: Float): Float =
    distance.coerceAtMost(activation) * 0.42f + (distance - activation).coerceAtLeast(0f)

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

/** Room the floating toolbar takes above the gesture bar, which views leave free so nothing stays hidden under it. */
internal val TOOLBAR_CLEARANCE = 80.dp
