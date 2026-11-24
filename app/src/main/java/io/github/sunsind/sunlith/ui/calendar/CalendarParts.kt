package io.github.sunsind.sunlith.ui.calendar

import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.Interaction
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.sunsind.sunlith.R
import io.github.sunsind.sunlith.data.CalendarEvent
import io.github.sunsind.sunlith.ui.Formats
import io.github.sunsind.sunlith.ui.LocalPalette
import io.github.sunsind.sunlith.ui.weekDays
import java.time.LocalDate
import kotlinx.coroutines.flow.Flow

@Composable
internal fun Modifier.calendarTap(
    color: Color = Color.Unspecified,
    onClickLabel: String? = null,
    onClick: () -> Unit,
): Modifier = clickable(remember { TapOnlyPress() }, ripple(color = color), onClickLabel = onClickLabel, role = Role.Button, onClick = onClick)

/** Ripples start on release, once a press can no longer turn into a swipe. */
internal class TapOnlyPress : MutableInteractionSource {
    private val shown = MutableInteractionSource()
    override val interactions: Flow<Interaction> = shown.interactions

    override suspend fun emit(interaction: Interaction) {
        when (interaction) {
            is PressInteraction.Press, is PressInteraction.Cancel -> Unit
            is PressInteraction.Release -> {
                shown.emit(interaction.press)
                shown.emit(interaction)
            }
            else -> shown.emit(interaction)
        }
    }

    override fun tryEmit(interaction: Interaction): Boolean = when (interaction) {
        is PressInteraction.Press, is PressInteraction.Cancel -> true
        is PressInteraction.Release -> shown.tryEmit(interaction.press) && shown.tryEmit(interaction)
        else -> shown.tryEmit(interaction)
    }
}

@Composable
internal fun WeekdayLabels(f: Formats) {
    val p = LocalPalette.current
    val narrow = LocalDensity.current.fontScale > 1.3f
    Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp).clearAndSetSemantics { }) {
        weekDays.forEach { dow ->
            Text(
                (if (narrow) f.weekdayNarrow(dow) else f.weekdayShort(dow)), Modifier.weight(1f), color = p.muted, style = MaterialTheme.typography.labelSmall,
                textAlign = TextAlign.Center, maxLines = 1, overflow = TextOverflow.Clip,
            )
        }
    }
}

/** Circle size for a date number: grows with the font size, but never wider than its column. */
@Composable
internal fun dayCircle(): Dp {
    val size = MaterialTheme.typography.bodyLarge.fontSize
    return with(LocalDensity.current) { maxOf(40.dp, size.toDp() * 2.25f) }
}

@Composable
internal fun DayCell(
    day: LocalDate, isSelected: Boolean, isToday: Boolean, inMonth: Boolean, events: List<CalendarEvent>, f: Formats,
    measurer: TextMeasurer, modifier: Modifier, onSelect: (LocalDate) -> Unit,
    addEvent: Boolean, reveal: (() -> Float)? = null,
) {
    val p = LocalPalette.current
    val context = LocalContext.current
    val description = remember(day, isToday, events.size, f) { dayDescription(context, f, day, isToday, events.size) }
    val accents = events.take(3).map { eventAccent(it) }
    val circle = dayCircle()
    val clickLabel = if (addEvent) stringResource(R.string.action_add_event) else null
    val style = MaterialTheme.typography.bodyLarge.copy(
        color = when { isSelected -> p.onAccent; inMonth -> p.ink; else -> p.faint },
        fontWeight = if (isToday || isSelected) FontWeight.SemiBold else FontWeight.Normal,
        textAlign = TextAlign.Center,
    )
    Box(
        modifier.layout { measurable, constraints ->
            val width = constraints.maxWidth
            val diameter = minOf(circle.roundToPx(), (width - 4.dp.roundToPx()).coerceAtLeast(0))
            val height = maxOf(48.dp.roundToPx(), diameter + 14.dp.roundToPx())
            val child = measurable.measure(Constraints.fixed(width, height))
            layout(width, height) { child.place(0, 0) }
        }
            .drawWithCache {
                val diameter = minOf(circle.toPx(), (size.width - 4.dp.toPx()).coerceAtLeast(0f))
                val center = Offset(size.width / 2, 3.dp.toPx() + diameter / 2)
                val text = measurer.measure(day.dayOfMonth.toString(), style, maxLines = 1, softWrap = false,
                    constraints = Constraints(maxWidth = diameter.toInt().coerceAtLeast(0)))
                val dots = accents.map { if (inMonth) it else p.faint }
                onDrawBehind {
                    // Fit the entire date and its dots into the space the row has earned.
                    scale(reveal?.invoke() ?: 1f, pivot = Offset(center.x, 0f)) {
                        if (isSelected) drawCircle(p.accent, diameter / 2, center)
                        else if (isToday) drawCircle(p.ink, diameter / 2 - 0.75.dp.toPx(), center, style = Stroke(1.5.dp.toPx()))
                        drawText(text, topLeft = Offset(center.x - text.size.width / 2, center.y - text.size.height / 2))
                        val dotSize = 5.dp.toPx()
                        val gap = 3.dp.toPx()
                        val left = (size.width - dots.size * dotSize - (dots.size - 1).coerceAtLeast(0) * gap) / 2
                        dots.forEachIndexed { index, color ->
                            drawCircle(color, dotSize / 2, Offset(left + index * (dotSize + gap) + dotSize / 2, 6.dp.toPx() + diameter + dotSize / 2))
                        }
                    }
                }
            }
            .calendarTap(onClickLabel = clickLabel) {
                onSelect(day)
            }
            .clearAndSetSemantics { contentDescription = description; selected = isSelected; role = Role.Button },
    )
}


/** Visible label and spoken description for a date header or cell. */
internal fun dayDescription(context: android.content.Context, f: Formats, day: LocalDate, isToday: Boolean, count: Int): String = buildString {
    append(f.dayLong(day))
    if (isToday) append(", ").append(context.getString(R.string.today))
    append(", ").append(context.resources.getQuantityString(R.plurals.event_count, count, count))
}
