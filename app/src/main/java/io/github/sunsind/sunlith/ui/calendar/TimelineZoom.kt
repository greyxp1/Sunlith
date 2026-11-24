package io.github.sunsind.sunlith.ui.calendar

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.SpringSpec
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.spring
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.stopScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerId
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.layout.layout
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.roundToInt
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

private const val TIMELINE_ZOOM_MIN = 50
internal const val TIMELINE_ZOOM_MAX = 200

/** Zoom changes the time scale, never the user's font size. */
internal fun timelineHourHeight(zoomPercent: Int): Dp = timelineHourHeight(zoomPercent.coerceIn(TIMELINE_ZOOM_MIN, TIMELINE_ZOOM_MAX).toFloat())

/** The live scale while pinching, which may briefly pass the saved range. */
internal fun timelineHourHeight(zoomPercent: Float): Dp = 64.dp * (zoomPercent.coerceAtLeast(10f) / 100f)

/**
 * Pinch scale with a soft rubber band past the saved range: the limit is felt, then [settledZoomPercent] springs back.
 */
private fun pinchZoomPercent(startPercent: Float, scale: Float): Float {
    val raw = startPercent * scale
    val min = TIMELINE_ZOOM_MIN.toFloat()
    val max = TIMELINE_ZOOM_MAX.toFloat()
    return when {
        raw > max -> max + (raw - max) * PINCH_RUBBER_BAND
        raw < min -> min - (min - raw) * PINCH_RUBBER_BAND
        else -> raw
    }.coerceIn(min * 0.75f, max * 1.25f)
}

private const val PINCH_RUBBER_BAND = 0.3f

/** The start value that [pinchZoomPercent] turns back into [live] at scale 1, so a new finger pair never jumps. */
private fun unbandedZoomPercent(live: Float): Float {
    val min = TIMELINE_ZOOM_MIN.toFloat()
    val max = TIMELINE_ZOOM_MAX.toFloat()
    return when {
        live > max -> max + (live - max) / PINCH_RUBBER_BAND
        live < min -> min - (min - live) / PINCH_RUBBER_BAND
        else -> live
    }
}

/**
 * The glide from a released scale to the saved one. Critically damped, so a pinch released past the range returns
 * to its limit without dipping below it and bouncing back.
 */
private fun timelineZoomSettleSpec(): SpringSpec<Float> =
    spring(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = Spring.StiffnessMediumLow)

/** A released pinch settles inside the saved range, in 5% steps. */
private fun settledZoomPercent(live: Float): Int =
    ((live.coerceIn(TIMELINE_ZOOM_MIN.toFloat(), TIMELINE_ZOOM_MAX.toFloat()) / 5f).roundToInt() * 5).coerceIn(TIMELINE_ZOOM_MIN, TIMELINE_ZOOM_MAX)

/** The scroll offset that keeps [minute] under [focalY], kept inside the content like the scroll state would. */
private fun focalScroll(axis: TimelineAxis, minute: Float, focalY: Float, contentHeight: Float, viewportHeight: Float): Float =
    (axis.minuteToY(minute) - focalY).coerceIn(0f, (contentHeight - viewportHeight).coerceAtLeast(0f))

/**
 * A [minute] and the viewport y ([focalY]) where it is drawn. Keeping it there while the grid around it is rescaled
 * keeps that spot of the day physically still on screen.
 */
internal class TimelineAnchor(val minute: Float, val focalY: Float) {
    /** The scroll offset that draws [minute] at [focalY] on [axis], whose grid starts [top] px into the content. */
    fun scrollOn(axis: TimelineAxis, top: Float): Float = top + axis.minuteToY(minute) - focalY

    /** The same minute after the content scrolled by [delta] px, for example under a finger that is already dragging. */
    fun scrolledBy(delta: Float): TimelineAnchor = TimelineAnchor(minute, focalY - delta)
}

/**
 * The minute drawn at viewport [focalY] when the content is scrolled to [scrollY] and the grid starts [top] px into
 * it. Above midnight (the leading edge) or below the grid's end, the anchor is the edge minute at the y where it is
 * actually drawn, so a scroll inside the edge room is kept instead of snapping to the grid.
 */
private fun timelineAnchor(axis: TimelineAxis, top: Float, scrollY: Float, focalY: Float = 0f): TimelineAnchor {
    val minute = axis.yToMinute(scrollY + focalY - top)
    return TimelineAnchor(minute, top + axis.minuteToY(minute) - scrollY)
}

/** Today shows the hour before [currentMinute], so the last hour stays in view above the now line. */
internal fun previousHour(currentMinute: Int): Int = (currentMinute / 60 - 1).coerceAtLeast(0) * 60

/** Keeps the grid's leading room above the snapped line so its label clears the all-day band and seam. */
internal fun timelineSnapScroll(axis: TimelineAxis, minute: Int, bandHeight: Int): Float =
    (axis.minuteToY(minute) - bandHeight).coerceAtLeast(0f)

/** [top] is the room above midnight inside the scrolling content, so the check uses where the time is really drawn. */
internal fun timelineTodayDirection(axis: TimelineAxis, minute: Int, scrollY: Int, viewportHeight: Int, top: Float): TodayDirection? {
    if (viewportHeight <= 0) return null
    val y = top + axis.minuteToY(minute)
    return when {
        y < scrollY -> TodayDirection.Up
        y > scrollY + viewportHeight -> TodayDirection.Down
        else -> null
    }
}

/** The zoom shown while two fingers pinch or the release springs back; null when the saved zoom applies. */
@Stable
internal class TimelineZoom {
    var live by mutableStateOf<Float?>(null)
    var settle: Job? = null
    /** The saved value the running [settle] lands on, known before the saved state recomposes. */
    var target: Int? = null
    /** True while a two-finger gesture owns the timeline, false during the spring. */
    var pinching by mutableStateOf(false)
    /**
     * The saved zoom that card packing keeps from the moment a pinch starts
     * until its settle ends. Committing on release then cannot repack the cards under a scale that is still moving;
     * they repack once, together with the end of the live scale.
     */
    var frozen by mutableStateOf<Int?>(null)

    /**
     * The minute under the fingers when the scale settled, and the scroll it was read at. Clearing [frozen] can
     * repack cards between the top of the screen and the fingers, so the timeline restores this point
     * instead of its top minute. Read once by the next settled composition ([takeHandoff]).
     */
    private var handoff: TimelineAnchor? = null
    private var handoffScroll = 0

    /** Ends the live scale and the frozen layout in one snapshot write, so one composition shows the settled grid. */
    fun finish(anchor: TimelineAnchor? = null, scrollY: Int = 0) {
        handoff = anchor
        handoffScroll = scrollY
        live = null
        frozen = null
    }

    /** The settled anchor, moved by any scrolling since [finish] so a drag that already started is kept, then cleared. */
    fun takeHandoff(scrollY: Int): TimelineAnchor? =
        handoff?.scrolledBy((scrollY - handoffScroll).toFloat()).also { handoff = null }
}

/**
 * Scroll positions computed from the newest grid are applied after the scrolling content below this modifier is
 * measured and before it is placed. A rescaled or newly opened grid is therefore first drawn already at
 * its position, without hiding it for the frames an effect would need. [target] returns the wanted offset or null.
 */
internal fun Modifier.timelinePosition(scroll: ScrollState, target: () -> Float?): Modifier = layout { measurable, constraints ->
    val placeable = measurable.measure(constraints)
    layout(placeable.width, placeable.height) {
        target()?.let { y ->
            val wanted = y.roundToInt().coerceIn(0, scroll.maxValue.coerceAtLeast(0))
            if (wanted != scroll.value) scroll.dispatchRawDelta((wanted - scroll.value).toFloat())
        }
        placeable.place(0, 0)
    }
}

/**
 * Two-finger pinch on the vertical distance between the fingers. One finger is never consumed, so page swipes, vertical
 * scrolling and taps behave as before. Two fingers must deliberately change their separation beyond twice the system
 * touch slop and 8% of their starting span. Multi-touch cancels event clicks; only an active pinch drives the scale.
 * The minute under the fingers stays under them, the
 * value is saved on release, then the scale glides to it without overshoot, still anchored at the fingers. A new
 * finger pair continues from the current scale. A touch during the glide, or a cancelled gesture, lands on the saved
 * value without a remap. The layout stays [TimelineZoom.frozen] until the scale is settled; the minute under the
 * fingers (or under the new touch) then stays at that same screen y when the settled grid repacks its cards.
 */
internal fun Modifier.timelinePinch(
    zoom: TimelineZoom,
    scroll: ScrollState,
    scope: CoroutineScope,
    motion: Boolean,
    saved: () -> Int,
    /** The grid drawn at a zoom percent. It starts [topPx] below the top of the scrolling content. */
    axisAt: (Float) -> TimelineAxis,
    topPx: Float,
    /** Full scrolling content height for a grid, including its edges. */
    contentHeight: (TimelineAxis) -> Float,
    onCommit: (Int) -> Unit,
    /**
     * A pinch that spreads more across than up and down reports each step of its sideways spread instead of zooming
     * the hours. Reports the cumulative span ratio, with its logarithmic velocity at release.
     */
    onSpread: ((Float, Float?) -> Unit)? = null,
): Modifier = pointerInput(zoom, scroll, scope, motion, onSpread) {
    // The minute drawn at viewport y [focalY] on the grid at [percent], with the content scrolled to [scrollY].
    fun anchorAt(percent: Float, scrollY: Float, focalY: Float) = timelineAnchor(axisAt(percent), topPx, scrollY, focalY)
    fun scrollFor(percent: Float, minute: Float, focalY: Float) = axisAt(percent).let { target ->
        focalScroll(target, minute, focalY - topPx, contentHeight(target), scroll.viewportSize.toFloat())
    }
    val minSpan = 48.dp.toPx()
    val minWidth = 20.dp.toPx()
    val spanSlop = viewConfiguration.touchSlop * 2f
    fun minuteAt(percent: Float, focalY: Float) = anchorAt(percent, scroll.value.toFloat(), focalY).minute
    fun follow(percent: Float, minute: Float, focalY: Float) {
        scroll.dispatchRawDelta(scrollFor(percent, minute, focalY) - scroll.value)
    }
    // Ends the live scale and hands the point under the fingers, read from the actual scroll, to the settled grid.
    fun settle(percent: Float, focalY: Float) {
        zoom.finish(anchorAt(percent, scroll.value.toFloat(), focalY), scroll.value)
    }
    // Shows the saved scale for one frame while still live, so clearing it changes neither the axis nor the scroll.
    fun land(target: Int, minute: Float, focalY: Float) {
        val value = target.toFloat()
        zoom.target = target
        zoom.live = value
        val placed = scrollFor(value, minute, focalY)
        scroll.dispatchRawDelta(placed - scroll.value)
        zoom.settle = scope.launch {
            withFrameNanos { }
            // Shift by any change of the target position only, so a drag that already started keeps its own movement.
            scroll.dispatchRawDelta(scrollFor(value, minute, focalY) - placed)
            settle(value, focalY)
        }
    }
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
        if (zoom.settle?.isActive == true) {
            zoom.settle?.cancel()
            zoom.settle = null
            val live = zoom.live
            if (live != null) land(zoom.target ?: saved(), minuteAt(live, down.position.y), down.position.y)
            else zoom.finish()
        }
        var pinching = false
        var across = false
        var spreadScale = 1f
        val spreadTracker = VelocityTracker()
        var spreadSample = 0L
        var spreadReleased = false
        var pair: Pair<PointerId, PointerId>? = null
        var startSpan = 1f
        var startWidth = 1f
        var startPercent = 0f
        var minute = 0f
        var focalY = 0f
        var released = false
        var hadMultiplePointers = false
        try {
            while (true) {
                val event = awaitPointerEvent(PointerEventPass.Initial)
                val pressed = event.changes.filter { it.pressed }
                if (across && pressed.size < 2 && !spreadReleased) {
                    val time = event.changes.maxOf { it.uptimeMillis }
                    val speed = if (time - spreadSample <= 75) spreadTracker.calculateVelocity().x else 0f
                    onSpread?.invoke(spreadScale, speed)
                    spreadReleased = true
                }
                if (pressed.isEmpty()) break
                if (spreadReleased) {
                    event.changes.forEach { it.consume() }
                    continue
                }
                if (pressed.size >= 2) {
                    if (!hadMultiplePointers) {
                        zoom.pinching = true
                        scope.launch { scroll.stopScroll() }
                    }
                    hadMultiplePointers = true
                    val a = pressed[0]
                    val b = pressed[1]
                    val span = maxOf(abs(a.position.y - b.position.y), minSpan)
                    val width = maxOf(abs(a.position.x - b.position.x), minWidth)
                    val time = maxOf(a.uptimeMillis, b.uptimeMillis)
                    focalY = (a.position.y + b.position.y) / 2f
                    val ids = a.id to b.id
                    if (ids != pair) {
                        // A new pair keeps the current scale and takes the minute now under its own midpoint.
                        pair = ids
                        val current = zoom.live ?: saved().toFloat()
                        startPercent = unbandedZoomPercent(current)
                        startSpan = span
                        startWidth = width
                        spreadTracker.resetTracking()
                        spreadSample = time
                        spreadTracker.addPosition(time, Offset(ln(spreadScale), 0f))
                        minute = minuteAt(current, focalY)
                    } else if (across) {
                        if (width != startWidth) {
                            spreadScale *= width / startWidth
                            startWidth = width
                            spreadSample = time
                            spreadTracker.addPosition(time, Offset(ln(spreadScale), 0f))
                            onSpread?.invoke(spreadScale, null)
                        }
                    } else {
                        if (!pinching) {
                            val tall = abs(span - startSpan) > maxOf(spanSlop, startSpan * 0.08f)
                            val wide = onSpread != null && abs(width - startWidth) > maxOf(viewConfiguration.touchSlop * 0.55f, startWidth * 0.025f)
                            if (!tall && !wide) {
                                event.changes.forEach { it.consume() }
                                continue
                            }
                            if (wide && (!tall || abs(width - startWidth) > abs(span - startSpan))) {
                                across = true
                                spreadScale = width / startWidth
                                startWidth = width
                                spreadSample = time
                                spreadTracker.addPosition(time, Offset(ln(spreadScale), 0f))
                                onSpread?.invoke(spreadScale, null)
                                event.changes.forEach { it.consume() }
                                continue
                            }
                            pinching = true
                            zoom.pinching = true
                            if (zoom.frozen == null) zoom.frozen = saved()
                            zoom.settle?.cancel()
                            zoom.settle = null
                            scope.launch { scroll.stopScroll() }
                            // Activation starts at the current fingers without applying the ignored jitter as a jump.
                            startSpan = span
                            minute = minuteAt(saved().toFloat(), focalY)
                        }
                        val percent = pinchZoomPercent(startPercent, span / startSpan)
                        zoom.live = percent
                        follow(percent, minute, focalY)
                    }
                }
                // After a pinch, the finger left on screen must not turn into a scroll or a tap.
                if (hadMultiplePointers) event.changes.forEach { it.consume() }
            }
            released = true
        } finally {
            zoom.pinching = false
            if (across && !spreadReleased) onSpread?.invoke(if (released) spreadScale else 1f, 0f)
            // Detached or restarted input: save what the fingers showed instead of leaving a stale live scale.
            if (!released && pinching) zoom.live?.let { live ->
                val target = settledZoomPercent(live)
                onCommit(target)
                if (scope.isActive) {
                    land(target, minute, focalY)
                } else zoom.finish()
            }
        }
        val from = zoom.live
        if (!pinching || from == null) return@awaitEachGesture
        val target = settledZoomPercent(from)
        onCommit(target)
        zoom.target = target
        val anchor = minute
        val y = focalY
        zoom.settle = scope.launch {
            // The layout is frozen, so the commit above changes nothing on screen while the scale moves.
            follow(from, anchor, y)
            if (motion && from != target.toFloat()) {
                animate(from, target.toFloat(), animationSpec = timelineZoomSettleSpec()) { value, _ ->
                    zoom.live = value
                    follow(value, anchor, y)
                }
            }
            // Compose the saved scale once while still live, so clearing it changes neither the axis nor the scroll.
            zoom.live = target.toFloat()
            follow(target.toFloat(), anchor, y)
            withFrameNanos { }
            follow(target.toFloat(), anchor, y)
            settle(target.toFloat(), y)
        }
    }
}

@Stable
internal class TimelineRemap(axis: TimelineAxis, top: Float) {
    var previousAxis by mutableStateOf(axis)
    var previousTop by mutableFloatStateOf(top)
    var pendingAnchor by mutableStateOf<TimelineAnchor?>(null)
}

/** Remembers the viewed minute whenever the hour scale, grouping or top edge changes; placement restores it. */
@Composable
internal fun rememberTimelineRemap(scroll: ScrollState, axis: TimelineAxis, topPx: Float, zoom: TimelineZoom, live: Boolean): TimelineRemap {
    val remap = remember(scroll) { TimelineRemap(axis, topPx) }
    SideEffect {
        if (live) {
            // The pinch keeps its own focal minute in place; a remap would fight the fingers, and an older one must
            // not replay once the scale settles.
            remap.previousAxis = axis
            remap.previousTop = topPx
            remap.pendingAnchor = null
            zoom.takeHandoff(scroll.value)
        } else {
            val settled = zoom.takeHandoff(scroll.value)
            if (remap.previousAxis != axis || remap.previousTop != topPx) {
                // A shorter grid can clamp ScrollState during measure. Read the old position first; placement of the
                // new grid restores it in the same frame (see timelinePosition). A settled pinch keeps the minute
                // under the fingers.
                remap.pendingAnchor = settled ?: remap.pendingAnchor ?: timelineAnchor(remap.previousAxis, remap.previousTop, scroll.value.toFloat())
                remap.previousAxis = axis
                remap.previousTop = topPx
            }
        }
    }
    return remap
}
