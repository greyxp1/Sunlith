package io.github.sunsind.sunlith.ui.calendar

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import io.github.sunsind.sunlith.R
import io.github.sunsind.sunlith.data.CalendarEvent
import io.github.sunsind.sunlith.ui.Formats
import io.github.sunsind.sunlith.ui.LocalPalette
import io.github.sunsind.sunlith.ui.gridStart
import java.time.LocalDate
import java.time.YearMonth
import kotlin.math.floor

private fun fullMonthChipSlots(row: Dp, number: Dp, chip: Dp, density: Density): Int = with(density) {
    // Keep the fractional chip stride; allow half a pixel for rounding in the available space.
    floor(((row - number - 10.dp).toPx() + 0.5f) / chip.toPx().coerceAtLeast(1f)).toInt().coerceAtLeast(0)
}

/**
 * A month grid that fills the screen with six fixed rows, ruled with Google Calendar's hairlines, and shows event
 * titles as solid chips inside each date. Rows never grow with their events: a date shows as many titles as fit, and
 * tapping a date lists them all. When the text is too large for six rows to fit, the rows keep a readable minimum height
 * and the grid scrolls.
 *
 * Event chips share the date’s tap target.
 */
@Composable
internal fun FullMonthGrid(
    month: YearMonth, selected: LocalDate, today: LocalDate,
    eventsOn: (LocalDate) -> List<CalendarEvent>, f: Formats,
    onOpenDate: (LocalDate) -> Unit, modifier: Modifier = Modifier,
    row: Dp, number: Dp, chip: Dp, viewportHeight: Dp, textMeasurer: TextMeasurer,
    todayRequest: Int = 0, active: Boolean = true, onTodayDirectionChanged: (TodayDirection?) -> Unit = {},
) {
    val density = LocalDensity.current
    val start = remember(month) { gridStart(month) }
    val dates = remember(start) { List(42) { start.plusDays(it.toLong()) } }
    val monthDirection = todayDirection(today, month.atDay(1), month.atEndOfMonth(), horizontal = true)
    Column(modifier.fillMaxSize()) {
        Box(Modifier.weight(1f).fillMaxWidth()) {
            val scrolls = row * 6 > viewportHeight
            key(month) {
                val scroll = rememberScrollState()
                val rowPx = with(density) { row.toPx() }
                val dateHeightPx = with(density) { (number + 6.dp).toPx() }
                val heightPx = with(density) { viewportHeight.toPx() }
                val reportDirection by rememberUpdatedState(onTodayDirectionChanged)
                var placedDate by rememberSaveable { mutableLongStateOf(Long.MIN_VALUE) }
                var placedTodayRequest by remember { mutableIntStateOf(todayRequest) }
                if (active && scrolls) {
                    LaunchedEffect(selected, todayRequest, rowPx) {
                        if (placedDate == selected.toEpochDay() && placedTodayRequest == todayRequest) return@LaunchedEffect
                        val targetRow = (java.time.temporal.ChronoUnit.DAYS.between(start, selected) / 7).coerceIn(0, 5)
                        val y = (targetRow * rowPx).toInt()
                        // The month opens on its row at once. Later moves, such as Today, glide there.
                        if (placedDate == Long.MIN_VALUE) scroll.scrollTo(y) else scroll.animateScrollTo(y)
                        placedDate = selected.toEpochDay()
                        placedTodayRequest = todayRequest
                    }
                    LaunchedEffect(scroll, today, start, monthDirection, rowPx, dateHeightPx, heightPx, onTodayDirectionChanged) {
                        snapshotFlow {
                            val firstRow = kotlin.math.ceil(scroll.value / rowPx).toInt().coerceIn(0, 5)
                            val lastRow = kotlin.math.floor((scroll.value + heightPx + 1 - dateHeightPx) / rowPx).toInt().coerceIn(firstRow, 5)
                            monthDirection ?: todayDirection(today, dates[firstRow * 7], dates[lastRow * 7 + 6])
                        }.collect { reportDirection(it) }
                    }
                } else if (active) {
                    SideEffect { reportDirection(monthDirection ?: todayDirection(today, dates.first(), dates.last())) }
                }
                Column(if (scrolls) Modifier.verticalScroll(scroll) else Modifier) {
                    repeat(6) { r ->
                        // With each tile's own margin, the columns line up under the weekday labels.
                        Row(Modifier.fillMaxWidth().height(row).padding(horizontal = 2.5.dp)) {
                            repeat(7) { c ->
                                val day = dates[r * 7 + c]
                                FullMonthCell(
                                    day, day == selected, day == today, YearMonth.from(day) == month, eventsOn(day), f,
                                    number, chip, row, textMeasurer,
                                    modifier = Modifier.weight(1f).fillMaxHeight(),
                                ) { onOpenDate(day) }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun FullMonthCell(
    day: LocalDate, isSelected: Boolean, isToday: Boolean, inMonth: Boolean, events: List<CalendarEvent>, f: Formats,
    number: Dp, chip: Dp, row: Dp, measurer: TextMeasurer, modifier: Modifier,
    onClick: () -> Unit,
) {
    val p = LocalPalette.current
    val context = LocalContext.current
    val courses = LocalCourseColors.current
    val untitled = stringResource(R.string.untitled)
    val description = remember(day, isToday, events.size, f) {
        dayDescription(context, f, day, isToday, events.size)
    }
    val selectLabel = stringResource(R.string.calendar_open_day_events)
    val grid = rememberTimelineGridColors()
    // Tiles match the hour grid, so Month and the timelines read as one calendar. Days from other months fade back.
    val tile = when { isToday -> grid.today; inMonth -> grid.tile; else -> grid.tile.copy(alpha = grid.tile.alpha * 0.45f) }
    val shape = RoundedCornerShape(8.dp)
    val slots = fullMonthChipSlots(row, number, chip, LocalDensity.current)
    val numberStyle = MaterialTheme.typography.bodyMedium.copy(
        color = when { isSelected -> p.onAccent; inMonth -> p.ink; else -> p.faint },
        fontWeight = if (isToday || isSelected) FontWeight.SemiBold else FontWeight.Normal,
    )
    val chipStyle = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Medium)
    Box(
        modifier
            .padding(1.5.dp)
            .clip(shape)
            .drawWithCache {
                val inset = 3.dp.toPx()
                val top = 4.dp.toPx()
                val available = (size.width - inset * 2).coerceAtLeast(0f)
                val numberSize = number.toPx()
                val circleWidth = minOf(numberSize, available)
                val circleLeft = (size.width - circleWidth) / 2
                val numberText = measurer.measure(day.dayOfMonth.toString(), numberStyle, maxLines = 1,
                    softWrap = false, constraints = Constraints(maxWidth = circleWidth.toInt().coerceAtLeast(0)))
                val chipHeight = (chip - 2.dp).toPx()
                val chipTop = top + numberSize + 2.dp.toPx()
                val padding = 3.dp.toPx()
                val rtl = layoutDirection == LayoutDirection.Rtl
                val labels = events.take(slots).map { event ->
                    val colors = eventColors(event, p, courses, dimmed = !inMonth)
                    val title = event.displayTitle().ifBlank { untitled }
                    val markerWidth = if (event.todo) 10.dp.toPx() else 0f
                    val width = (available - 2 * padding - markerWidth).toInt().coerceAtLeast(0)
                    val text = measurer.measure(title, chipStyle, maxLines = 1, softWrap = false,
                        overflow = TextOverflow.Visible, constraints = Constraints(maxWidth = width))
                    Triple(event, colors, text)
                }
                val hiddenColors = events.asSequence().drop(slots).take(2)
                    .map { eventColors(it, p, courses, dimmed = !inMonth).container }.toList()
                onDrawBehind {
                    drawRect(tile)
                    val circleRadius = minOf(circleWidth, numberSize) / 2
                    val center = Offset(size.width / 2, top + numberSize / 2)
                    if (isSelected) drawCircle(p.accent, circleRadius, center)
                    else if (isToday) drawCircle(p.ink, circleRadius - 0.75.dp.toPx(), center, style = Stroke(1.5.dp.toPx()))
                    drawText(numberText, topLeft = Offset(circleLeft + (circleWidth - numberText.size.width) / 2,
                        top + (numberSize - numberText.size.height) / 2))
                    if (labels.isNotEmpty()) {
                        val lastY = chipTop + labels.lastIndex * chip.toPx()
                        for (index in hiddenColors.indices.reversed()) {
                            val depth = index + 1
                            val side = depth * 3.dp.toPx()
                            val width = available - side * 2
                            if (width > 0f) {
                                drawRoundRect(hiddenColors[index], Offset(inset + side, lastY + depth * 2.5.dp.toPx()),
                                    Size(width, chipHeight), CornerRadius(4.dp.toPx()))
                            }
                        }
                    }
                    labels.forEachIndexed { index, (event, colors, text) ->
                        val y = chipTop + index * chip.toPx()
                        val bounds = Rect(Offset(inset, y), Size(available, chipHeight))
                        drawRoundRect(colors.container, bounds.topLeft, bounds.size, CornerRadius(4.dp.toPx()))
                        if (event.canceled) drawCanceledBars(colors.content, bounds.topLeft, bounds.size)
                        val markerWidth = if (event.todo) 10.dp.toPx() else 0f
                        if (event.todo) {
                            val iconSize = minOf(7.dp.toPx(), chipHeight - 4.dp.toPx()).coerceAtLeast(1f)
                            val iconX = if (rtl) bounds.right - padding - iconSize else bounds.left + padding
                            drawRoundRect(colors.content, Offset(iconX, y + (chipHeight - iconSize) / 2), Size(iconSize, iconSize),
                                CornerRadius(1.dp.toPx()), style = Stroke(1.dp.toPx()))
                        }
                        val left = bounds.left + padding + if (rtl) 0f else markerWidth
                        val right = bounds.right - padding - if (rtl) markerWidth else 0f
                        if (right > left) {
                            val x = if (rtl && !text.didOverflowWidth) right - text.size.width else left
                            clipRect(left, y, right, bounds.bottom) {
                                drawEventTitle(text, Offset(x, y + (chipHeight - text.size.height) / 2), colors.content)
                            }
                        }
                    }
                }
            }
            .calendarTap(onClickLabel = selectLabel, onClick = onClick)
            .semantics { contentDescription = description; selected = isSelected },
    )
}
