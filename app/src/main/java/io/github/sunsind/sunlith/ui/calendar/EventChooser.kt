package io.github.sunsind.sunlith.ui.calendar

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material3.AlertDialogDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import io.github.sunsind.sunlith.R
import io.github.sunsind.sunlith.data.CalendarEvent
import io.github.sunsind.sunlith.ui.Formats
import io.github.sunsind.sunlith.ui.LocalPalette
import io.github.sunsind.sunlith.ui.Screen
import io.github.sunsind.sunlith.ui.SunlithViewModel
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * The list behind a group card, a stack or "+N": one outlined row per event with its color, every field a card can
 * hide (teacher absence, exact times, room) and a chevron, so each row reads as something to open. The dialog is as
 * tall as its rows, up to the screen. A row calls [onOpen] first and only then
 * [onClose], so a caller can keep the window until the frame that draws the event and no frame shows the calendar
 * in between.
 */
@Composable
internal fun EventChooser(
    events: List<CalendarEvent>, f: Formats, onOpen: (CalendarEvent) -> Unit, onClose: () -> Unit,
    date: LocalDate? = null, emptyLabel: String? = null, onAdd: (() -> Unit)? = null,
) {
    val context = LocalContext.current
    val p = LocalPalette.current
    val titleText = date?.let(f::dayLong) ?: context.resources.getQuantityString(R.plurals.event_count, events.size, events.size)
    val openLabel = stringResource(R.string.timeline_event_open)
    Dialog(onDismissRequest = onClose) {
        Surface(
            Modifier.fillMaxWidth(),
            shape = AlertDialogDefaults.shape, color = AlertDialogDefaults.containerColor,
            tonalElevation = AlertDialogDefaults.TonalElevation,
        ) {
            Column(Modifier.padding(top = 20.dp, bottom = 4.dp)) {
                Text(titleText, Modifier.padding(horizontal = 20.dp).semantics { heading() }, color = p.ink,
                    style = MaterialTheme.typography.titleLarge)
                Spacer(Modifier.height(12.dp))
                if (date != null) {
                    val ordered = remember(events) { agendaOrder(events) }
                    AgendaEventList(ordered, date, f, { event -> onOpen(event); onClose() },
                        Modifier.weight(1f, fill = false), emptyLabel = emptyLabel)
                } else {
                    Column(
                        Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()).padding(horizontal = 12.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        events.forEach { event -> TimelineEventChoice(event, f, openLabel) { onOpen(event); onClose() } }
                    }
                }
                if (onAdd != null) {
                    Button(
                        onClick = { onAdd(); onClose() },
                        modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 8.dp),
                    ) { Text(stringResource(R.string.action_add_event)) }
                } else if (date == null) Row(
                    Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.End,
                ) {
                    TextButton(onClick = onClose) { Text(stringResource(R.string.action_close)) }
                }
            }
        }
    }
}

@Composable
private fun TimelineEventChoice(event: CalendarEvent, f: Formats, openLabel: String, onClick: () -> Unit) {
    val context = LocalContext.current
    val p = LocalPalette.current
    val title = event.displayTitle().ifBlank { context.getString(R.string.untitled) }
    val zone = ZoneId.systemDefault()
    val startDay = Instant.ofEpochMilli(event.startMillis).atZone(zone).toLocalDate()
    val endDay = Instant.ofEpochMilli(event.endMillis).atZone(zone).toLocalDate()
    val time = when {
        startDay == endDay -> f.range(f.time(event.startMillis), f.time(event.endMillis))
        else -> f.range("${f.dayLong(startDay)} ${f.time(event.startMillis)}", "${f.dayLong(endDay)} ${f.time(event.endMillis)}")
    }
    val status = absenceStatus(event, f)
    val description = event.spokenDescription(f, time)
    val shape = RoundedCornerShape(12.dp)
    Row(
        Modifier.fillMaxWidth().heightIn(min = 56.dp).height(IntrinsicSize.Min).clip(shape)
            .background(p.ink.copy(alpha = if (p.dark) 0.05f else 0.03f)).border(1.dp, p.line, shape)

            .clickable(role = Role.Button, onClickLabel = openLabel, onClick = onClick)
            .clearAndSetSemantics {
                contentDescription = description
                role = Role.Button
                onClick(openLabel) { onClick(); true }
            }
            .padding(start = 10.dp, end = 6.dp, top = 10.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.width(4.dp).fillMaxHeight().clip(CircleShape).background(eventAccent(event)))
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            AgendaCardText(event.withTodoMark(title), p.ink, MaterialTheme.typography.bodyLarge, null, "title",
                fontWeight = FontWeight.SemiBold, maxLines = 3)
            if (!event.allDay) Text(time, color = p.muted, style = MaterialTheme.typography.bodySmall)
            event.classroom()?.let { Text(it, color = p.muted, style = MaterialTheme.typography.bodySmall) }
            if (status != null) Text(status, color = p.ink, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold)
        }
        Icon(Icons.AutoMirrored.Outlined.KeyboardArrowRight, null, Modifier.padding(start = 4.dp).size(20.dp), tint = p.muted)
    }
}

internal class ChooserHandoff(
    val open: (CalendarEvent) -> Unit, val add: (LocalDate) -> Unit, val close: () -> Unit,
)

/**
 * The event chooser closes itself before it opens an event. Removing its window first left the calendar on screen
 * for a frame or two before Details was drawn. The chooser leaves only after
 * the frame that draws Details, so the screen goes straight from the chooser to the event.
 */
@Composable
internal fun rememberChooserHandoff(vm: SunlithViewModel, dismiss: () -> Unit): ChooserHandoff {
    val scope = rememberCoroutineScope()
    val currentDismiss by rememberUpdatedState(dismiss)
    return remember(vm, scope) {
        var opening = false
        var closing = false
        fun open(action: () -> Unit) {
            if (!opening) {
                opening = true
                action()
            }
        }
        ChooserHandoff(
            open = { event -> open { vm.open(Screen.Details(event)) } },
            add = { date -> open { vm.newEvent(date) } },
            close = {
                if (!closing) {
                    closing = true
                    // Runs after the tap handler, so an open that follows this close is already known.
                    scope.launch {
                        try {
                            if (opening) { withFrameNanos { }; withFrameNanos { } }
                        } finally {
                            opening = false
                            closing = false
                            currentDismiss()
                        }
                    }
                }
            },
        )
    }
}
