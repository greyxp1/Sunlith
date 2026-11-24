package io.github.sunsind.sunlith.ui.calendar

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.interaction.collectIsDraggedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerDefaults
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.LayoutDirection
import kotlin.math.abs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/** Keeps unsupported two-finger gestures from becoming a date tap, scroll or period swipe. */
internal fun Modifier.ignoreMultiplePointers(): Modifier = pointerInput(Unit) {
    awaitEachGesture {
        awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
        var multiple = false
        while (true) {
            val event = awaitPointerEvent(PointerEventPass.Initial)
            multiple = multiple || event.changes.count { it.pressed } > 1
            if (multiple) event.changes.forEach { it.consume() }
            if (event.changes.none { it.pressed }) break
        }
    }
}

@Composable
internal fun rememberTodaySlide(): TodaySlide {
    val frame = rememberGraphicsLayer()
    return remember(frame) { TodaySlide(frame) }
}

/** Slides the rendered viewport out while the destination is positioned offscreen. */
internal class TodaySlide(private val frame: GraphicsLayer) {
    private var capture by mutableStateOf(false)
    private var progress by mutableStateOf<Float?>(null)
    private var direction = 1
    private var outgoing by mutableStateOf<ImageBitmap?>(null)
    val showingDestination: Boolean get() = (progress ?: 1f) >= 0.5f
    val isRunning by derivedStateOf { capture || progress != null }

    suspend fun showTarget(delta: Int, prepare: suspend () -> Unit) {
        direction = if (delta > 0) 1 else -1
        capture = true
        try {
            val captured = withTimeoutOrNull(250) { snapshotFlow { !capture }.first { it } } == true
            if (captured) outgoing = frame.toImageBitmap()
            prepare()
            if (captured) animate(0f, 1f, animationSpec = tween(430, easing = FastOutSlowInEasing)) { value, _ ->
                progress = value
            }
        } finally {
            capture = false
            progress = null
            outgoing = null
        }
    }

    fun drawOn(modifier: Modifier): Modifier = modifier.pointerInput(this) {
        awaitPointerEventScope {
            while (true) {
                val event = awaitPointerEvent(PointerEventPass.Initial)
                if (capture || progress != null) event.changes.forEach { it.consume() }
            }
        }
    }.drawWithContent {
        if (capture && size.width >= 1f && size.height >= 1f) {
            frame.record { this@drawWithContent.drawContent() }
            progress = 0f
            capture = false
        }
        val fraction = progress
        if (fraction == null) {
            drawContent()
        } else {
            val travel = size.width * direction * if (layoutDirection == LayoutDirection.Ltr) 1f else -1f
            clipRect {
                translate(left = travel * (1f - fraction)) { this@drawWithContent.drawContent() }
                val snapshot = outgoing
                if (snapshot == null) drawLayer(frame)
                else drawImage(snapshot, topLeft = Offset(-travel * fraction, 0f))
            }
        }
    }
}

/**
 * Horizontal paging between periods (month, week, day). The content follows the finger, the next or previous
 * period slides in beside it, and the change is committed only when the finger is released past a quarter of the
 * width or flung clearly sideways. The date changes once, after the slide settles, so nothing reloads mid-gesture.
 */
@Stable
internal class PeriodSwipeState(private val scope: CoroutineScope, val todaySlide: TodaySlide) {
    var pager by mutableStateOf<PagerState?>(null)
    /** The page whose period the screen last committed. */
    var acceptedPage by mutableIntStateOf(0)

    /**
     * Pages between the committed period and the one a swipe is heading for, so a header can mark the destination as
     * soon as the pager picks it instead of after the page settles.
     */
    val ahead: Int get() = if (todaySlide.showingDestination) pager?.let {
        (if (it.isScrollInProgress) it.targetPage else it.currentPage) - acceptedPage
    } ?: 0 else 0
    var firstPeriod: Long = 0
    val visiblePeriod: Long?
        get() = pager?.let { firstPeriod + if (todaySlide.showingDestination) it.currentPage else acceptedPage }
    var motion by mutableStateOf(true)
    var onCommit: (Int) -> Unit = {}
    private var job: Job? = null
    private var queuedSteps = 0
    var goingToday by mutableStateOf(false)
        private set

    /** Stops a step in progress. */
    fun cancel() {
        job?.cancel()
        job = null
        queuedSteps = 0
        goingToday = false
    }

    /** Slides to the page [steps] away and then runs [then], so Today travels like the arrows instead of jumping. */
    fun animateBy(steps: Int, then: () -> Unit) {
        cancel()
        val pages = pager
        if (pages == null || steps == 0 || !motion) return then()
        goingToday = true
        job = scope.launch {
            try {
                val target = (acceptedPage + steps).coerceIn(0, pages.pageCount - 1)
                animateTo(pages, target, 440)
                then()
            } finally {
                if (job === coroutineContext[Job]) {
                    job = null
                    goingToday = false
                }
            }
        }
    }

    suspend fun animateTo(pages: PagerState, target: Int, adjacentMillis: Int) {
        val delta = target - pages.currentPage
        if (abs(delta) > 1) todaySlide.showTarget(delta) { pages.scrollToPage(target) }
        else pages.animateScrollToPage(target, animationSpec = tween(adjacentMillis, easing = FastOutSlowInEasing))
    }

    /** Used by the header arrows and accessibility actions, so they move the same way as a swipe. */
    fun animateStep(dir: Int) {
        if (job?.isActive == true) {
            queuedSteps += dir
            return
        }
        val pages = pager ?: return onCommit(dir)
        job = scope.launch {
            var next = dir
            try {
                do {
                    val target = (pages.settledPage + next).coerceIn(0, pages.pageCount - 1)
                    if (motion) pages.animateScrollToPage(target, animationSpec = tween(280, easing = FastOutSlowInEasing))
                    else pages.scrollToPage(target)
                    next = queuedSteps.compareTo(0)
                    queuedSteps -= next
                } while (next != 0)
            } finally {
                if (job === coroutineContext[Job]) {
                    job = null
                    queuedSteps = 0
                }
            }
        }
    }
}

@Composable
internal fun rememberPeriodSwipe(motion: Boolean, onCommit: (Int) -> Unit): PeriodSwipeState {
    val scope = rememberCoroutineScope()
    val todaySlide = rememberTodaySlide()
    val state = remember(scope, todaySlide) { PeriodSwipeState(scope, todaySlide) }
    SideEffect {
        state.motion = motion
        state.onCommit = onCommit
    }
    return state
}

/** The visible page remains the same composition and scroll state when its settled date is committed. */
@Composable
internal fun PagedPeriod(
    state: PeriodSwipeState,
    period: Long,
    modifier: Modifier = Modifier,
    /** Each page fills the pager unless [wrap], when the pager takes the height of its pages, as a header does. */
    wrap: Boolean = false,
    userScrollEnabled: Boolean = true,
    commitWhenSettled: Boolean = false,
    animatePeriodChanges: Boolean = false,
    content: @Composable (Long) -> Unit,
) {
    val origin = rememberSaveable { period }
    val center = 1_000_000
    val expected = (center + period - origin).toInt().coerceIn(0, 2_000_000)
    val pager = rememberPagerState(initialPage = expected, pageCount = { 2_000_001 })
    val accepted = remember(pager) { intArrayOf(expected) }
    val syncing = remember(pager) { booleanArrayOf(false) }
    val firstSync = remember(pager) { booleanArrayOf(true) }
    var syncTarget by remember(pager) { mutableStateOf<Int?>(null) }
    DisposableEffect(state, pager) {
        state.acceptedPage = accepted[0]
        state.firstPeriod = origin - center
        state.pager = pager
        onDispose { if (state.pager === pager) state.pager = null }
    }
    SideEffect {
        if (firstSync[0] || expected != accepted[0]) {
            val animate = !firstSync[0] && animatePeriodChanges && state.motion
            firstSync[0] = false
            syncing[0] = true
            accepted[0] = expected
            state.acceptedPage = expected
            if (!state.goingToday || pager.currentPage != expected) state.cancel()
            // A tapped date and its page must land in the same measure pass. A launched scroll can
            // leave the previous page under the newly selected date for the first rendered frame.
            val needsScroll = pager.currentPage != expected || pager.currentPageOffsetFraction != 0f
            syncTarget = expected.takeIf { needsScroll && animate }
            if (needsScroll && !animate) pager.requestScrollToPage(expected)
        }
    }
    LaunchedEffect(pager, syncTarget, state.motion) {
        val target = syncTarget ?: return@LaunchedEffect
        if (state.motion) state.animateTo(pager, target, 300) else pager.scrollToPage(target)
        syncTarget = null
    }
    val dragged = pager.interactionSource.collectIsDraggedAsState()
    LaunchedEffect(pager, commitWhenSettled) {
        fun candidate(): Int? = when {
            dragged.value || state.goingToday || syncTarget != null -> null
            commitWhenSettled -> pager.settledPage.takeUnless { pager.isScrollInProgress }
            else -> pager.targetPage
        }
        // Agenda previews its visible day separately and commits after landing. Other pagers
        // can commit on release, once the target survives the frame where a new drag could catch it.
        snapshotFlow { candidate() }.distinctUntilChanged().collectLatest { target ->
            if (target == null) return@collectLatest
            withFrameNanos { }
            if (candidate() != target) return@collectLatest
            if (syncing[0]) {
                if (target == accepted[0]) syncing[0] = false
            } else {
                val delta = target - accepted[0]
                accepted[0] = target
                state.acceptedPage = target
                if (delta != 0) state.onCommit(delta)
            }
        }
    }
    HorizontalPager(
        state = pager,
        modifier = state.todaySlide.drawOn(modifier),
        userScrollEnabled = userScrollEnabled && syncTarget == null,
        key = { origin + it - center },
        beyondViewportPageCount = 1,
        flingBehavior = PagerDefaults.flingBehavior(
            state = pager,
            snapAnimationSpec = if (state.motion) spring(stiffness = Spring.StiffnessMedium, dampingRatio = Spring.DampingRatioNoBouncy) else snap(),
            snapPositionalThreshold = 0.25f,
        ),
    ) { page ->
        Box((if (wrap) Modifier.fillMaxWidth() else Modifier.fillMaxSize()).then(if (page == expected) Modifier else Modifier.clearAndSetSemantics { })) {
            content(origin + page - center)
        }
    }
}
