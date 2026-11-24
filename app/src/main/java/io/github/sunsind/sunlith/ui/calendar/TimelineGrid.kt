package io.github.sunsind.sunlith.ui.calendar

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.sunsind.sunlith.ui.Formats
import io.github.sunsind.sunlith.ui.LocalPalette
import io.github.sunsind.sunlith.ui.SunlithPalette
import java.time.LocalDate
import kotlin.math.ceil
import kotlin.math.roundToInt

/**
 * Inset of every hour tile and event card, so a card sits flush on the tiles behind it. Back-to-back events and
 * side-by-side cards keep twice this as a visible gap.
 */
internal val TIMELINE_BLOCK_INSET = 1.dp
private fun timelineMinimumBlockHeight(titleLine: Dp, density: Density): Dp = with(density) {
    val pixels = ceil(titleLine.toPx()).toInt() + (TIMELINE_BLOCK_INSET.roundToPx() + TIMELINE_LABEL_PADDING.roundToPx()) * 2
    maxOf(24.dp, pixels.toDp())
}

/** Leave room for a recognizable title before splitting an overlap into separate lanes. */
internal fun timelineMinLaneWidth(titleSize: Dp): Dp = maxOf(64.dp, titleSize * 5f)

/** Text-derived sizes for the timeline. */
internal class TimelineText(
    val titleSize: Dp, val minBlock: Dp,
    /** Midnight sits this far below the top of the grid, so its rail label is centered on the line. */
    val edgePx: Int,
    val compact: CompactTimes, val hourLabels: List<AnnotatedString>,
) {
    /** Minutes the shortest readable card spans at [zoomPercent]. */
    fun minMinutes(zoomPercent: Int): Int = ceil(minBlock / timelineHourHeight(zoomPercent) * 60f).toInt()
}

@Composable
internal fun rememberTimelineText(f: Formats, titleStyle: TextStyle, railStyle: TextStyle): TimelineText {
    val density = LocalDensity.current
    val measurer = rememberTextMeasurer()
    // Scale the actual text size before multiplying; sp conversion is non-linear at large font sizes.
    val titleSize = with(density) { titleStyle.fontSize.toDp() }
    val titleLine = with(density) { titleStyle.lineHeight.toDp() }
    val measuredTitle = remember(titleStyle, density) {
        with(density) { measurer.measure("Ag", titleStyle.copy(fontWeight = FontWeight.SemiBold), maxLines = 1).size.height.toDp() }
    }
    val railLabelPx = remember(railStyle, density) { measurer.measure("Ag", railStyle, maxLines = 1).size.height }
    val edgePx = with(density) { (railLabelPx / 2f + 3.dp.toPx()).roundToInt() }
    val compact = remember(f) { CompactTimes(f.locale, f.is24Hour, f::range) }
    val hourLabels = remember(f, compact) {
        (0..24).map { hour ->
            if (hour == 24 && f.is24Hour) AnnotatedString(String.format(f.locale, "%02d:%02d", 24, 0))
            else compact.hourLabel(hour)
        }
    }
    return TimelineText(titleSize, timelineMinimumBlockHeight(maxOf(titleLine, measuredTitle), density), edgePx, compact, hourLabels)
}

/** Width for a column of time labels: the widest label plus a little room, kept within a share of the screen. */
@Composable
internal fun rememberTimeGutter(labels: List<AnnotatedString>, style: TextStyle, minimum: Dp, maximum: Dp): Dp {
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    val widest = remember(labels, style, density) {
        with(density) { labels.maxOfOrNull { measurer.measure(it, style).size.width }?.toDp() ?: 0.dp }
    }
    return maxOf(minimum, widest + 8.dp).coerceAtMost(maxOf(minimum, maximum))
}

/** Colors of the tiled hour grid and the current time. Only the actual today's column gets [today]. */
@Immutable
internal class TimelineGridColors(val tile: Color, val today: Color, val now: Color)

private fun timelineGridColors(p: SunlithPalette): TimelineGridColors {
    val tile = lerp(p.background, p.line, if (p.dark) 0.55f else 0.6f)
    return TimelineGridColors(
        tile = tile,
        today = lerp(tile, p.accent, if (p.dark) 0.16f else 0.1f),
        now = p.accent,
    )
}

@Composable
internal fun rememberTimelineGridColors(): TimelineGridColors {
    val p = LocalPalette.current
    return remember(p) { timelineGridColors(p) }
}

/**
 * Rounded hour tiles drawn in one pass, so a week adds no composable per cell. The gaps between tiles replace grid
 * lines.
 */
internal fun DrawScope.drawTimelineGrid(axis: TimelineAxis, color: Color) {
    for (hour in 0 until 24) drawTimelineTile(axis, hour, color)
}

internal fun DrawScope.drawTimelineTile(axis: TimelineAxis, hour: Int, color: Color) {
    val inset = TIMELINE_BLOCK_INSET.toPx()
    val width = size.width - inset * 2
    val top = axis.minuteToY(hour * 60) + inset
    val height = axis.minuteToY((hour + 1) * 60) - inset - top
    if (width > 0f && height > 0f)
        drawRoundRect(color, Offset(inset, top), Size(width, height), CornerRadius(minOf(EVENT_CORNER.toPx(), height / 2f)))
}

internal fun DrawScope.drawNowLine(axis: TimelineAxis, minute: Int, colors: TimelineGridColors) =
    drawNowBar(colors.now, axis.minuteToY(minute), TIMELINE_BLOCK_INSET.toPx())

internal fun DrawScope.drawNowBar(color: Color, y: Float, edge: Float = 0f, left: Float = 0f, width: Float = size.width) {
    val inset = edge + 4.dp.toPx()
    drawLine(color, Offset(left + inset, y), Offset(left + width - inset, y), strokeWidth = 3.dp.toPx(), cap = StrokeCap.Round)
}

@Composable
internal fun HourRail(
    labels: List<AnnotatedString>, style: TextStyle, modifier: Modifier, axis: TimelineAxis,
    /** Pixels a label may extend past the top and bottom of the rail, into the edge room around the grid. */
    overhang: Int,
) {
    val p = LocalPalette.current
    val endPad = with(LocalDensity.current) { 6.dp.roundToPx() }
    val positions = (0..24).map { axis.minuteToY(it * 60) }
    Layout(
        content = {
            labels.forEach { Text(it, color = p.muted, style = style, maxLines = 2, softWrap = false, textAlign = TextAlign.End) }
        },
        modifier = modifier.clearAndSetSemantics { },
    ) { measurables, constraints ->
        val placeables = measurables.map { it.measure(Constraints(maxWidth = constraints.maxWidth)) }
        layout(constraints.maxWidth, constraints.maxHeight) {
            placeables.forEachIndexed { i, placeable ->
                val y = (positions[i] - placeable.height / 2f).roundToInt()
                    .coerceIn(-overhang, (constraints.maxHeight - placeable.height + overhang).coerceAtLeast(-overhang))
                placeable.placeRelative((constraints.maxWidth - placeable.width - endPad).coerceAtLeast(0), y)
            }
        }
    }
}

/**
 * A date above a timeline column. It uses the same type as the Month and Day pickers (the [WeekdayLabels] style
 * above a [dayCircle]-sized date), shrunk only when the column is narrower than the circle. It draws its text with
 * the strip's shared [measurer] instead of composing Text, so a date scrolling in costs one node and no new layout.
 */
@Composable
internal fun TimelineDayHeader(
    day: LocalDate, isToday: Boolean, count: Int, f: Formats, measurer: TextMeasurer, modifier: Modifier,
) {
    val p = LocalPalette.current
    val context = LocalContext.current
    val description = remember(day, isToday, count, f) { dayDescription(context, f, day, isToday, count) }
    val density = LocalDensity.current
    val typography = MaterialTheme.typography
    val circle = with(density) { maxOf(40.dp, typography.bodyLarge.fontSize.toDp() * 2.25f) }
    val weekday = f.weekdayShort(day.dayOfWeek)
    val weekdayStyle = typography.labelSmall.copy(color = if (isToday) p.ink else p.muted)
    val numberStyle = typography.bodyLarge.copy(color = if (isToday) p.onAccent else p.ink,
        fontWeight = if (isToday) FontWeight.SemiBold else FontWeight.Normal)
    Box(
        modifier.heightIn(min = 48.dp).padding(horizontal = 1.dp)
            .semantics { contentDescription = description }
            .drawWithCache {
                val inner = size.width.toInt().coerceAtLeast(0)
                val shortLabel = measurer.measure(weekday, weekdayStyle, maxLines = 1, softWrap = false)
                val label = if (shortLabel.size.width <= inner) shortLabel else measurer.measure(f.weekdayNarrow(day.dayOfWeek), weekdayStyle,
                    maxLines = 1, softWrap = false, overflow = TextOverflow.Clip, constraints = Constraints(maxWidth = inner))
                val number = measurer.measure(day.dayOfMonth.toString(), numberStyle, maxLines = 1, softWrap = false)
                val pad = 2.dp.toPx()
                val diameter = minOf(circle.toPx(), size.width - pad * 2).coerceAtLeast(0f)
                val center = Offset(size.width / 2, pad + label.size.height + pad + diameter / 2)
                onDrawBehind {
                    drawText(label, topLeft = Offset((size.width - label.size.width) / 2, pad))
                    if (isToday) drawCircle(p.accent, diameter / 2, center)
                    drawText(number, topLeft = center - Offset(number.size.width / 2f, number.size.height / 2f))
                }
            },
    )
}
/** Places the minutes of a day on the timeline at [hourPx] per hour. */
internal data class TimelineAxis(val hourPx: Float) {
    val totalHeight: Float get() = 24 * hourPx

    fun minuteToY(minute: Int): Float = minuteToY(minute.toFloat())

    fun minuteToY(minute: Float): Float = minute.coerceIn(0f, 1440f) / 60f * hourPx

    fun yToMinute(y: Float): Float = (y / hourPx * 60f).coerceIn(0f, 1440f)

    fun hourAt(y: Float): Int = (yToMinute(y).toInt() / 60).coerceAtMost(23)

}
