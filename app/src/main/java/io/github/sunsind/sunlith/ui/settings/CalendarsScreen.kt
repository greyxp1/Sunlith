package io.github.sunsind.sunlith.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material3.Checkbox
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.sunsind.sunlith.R
import io.github.sunsind.sunlith.SettingsActivity
import io.github.sunsind.sunlith.data.DeviceCalendar
import io.github.sunsind.sunlith.ui.ClickRow
import io.github.sunsind.sunlith.ui.GroupItem
import io.github.sunsind.sunlith.ui.GroupRow
import io.github.sunsind.sunlith.ui.ListGroup
import io.github.sunsind.sunlith.ui.Note
import io.github.sunsind.sunlith.ui.Notice
import io.github.sunsind.sunlith.ui.PageScaffold
import io.github.sunsind.sunlith.ui.SunlithViewModel
import io.github.sunsind.sunlith.ui.event.ColorDot
import io.github.sunsind.sunlith.ui.plusBottom
import io.github.sunsind.sunlith.ui.toggleState

@Composable
fun CalendarsScreen(vm: SunlithViewModel) {
    val context = LocalContext.current
    val error = vm.loadError
    val calendarsKnown = vm.calendars.isNotEmpty() || (vm.loaded && error == null)
    PageScaffold(stringResource(R.string.title_calendars)) { pad ->
        LazyColumn(Modifier.fillMaxSize(), contentPadding = pad.plusBottom(48.dp)) {
            if (vm.calendars.isEmpty()) {
                when {
                    error != null -> item { Notice(error, danger = true, action = stringResource(R.string.action_retry)) { vm.reload() } }
                    !vm.loaded -> item { LoadingIndicator(Modifier.padding(16.dp)) }
                    else -> item { Note(stringResource(R.string.no_calendars_on_phone)) }
                }
            }
            itemsIndexed(vm.calendars, key = { _, cal -> cal.id }) { index, cal ->
                val on = cal.id in vm.activeIds
                GroupItem(index, vm.calendars.size) {
                    CalendarRow(
                        cal, listOfNotNull(cal.accountNote(), onDevice(cal), readOnly(cal)),
                        Modifier.toggleState(on, Role.Checkbox), onClick = { vm.setCalendarActive(cal.id, !on) },
                        trailing = { Checkbox(checked = on, onCheckedChange = null) },
                    )
                }
            }
            item {
                ListGroup {
                    if (calendarsKnown && !vm.hasSunlithCalendar) item {
                        ClickRow(stringResource(R.string.action_create_local), stringResource(R.string.settings_local_sub), icon = Icons.Outlined.Add) {
                            vm.createLocalCalendar()
                        }
                    }
                    item { ClickRow(stringResource(R.string.school_title), icon = Icons.AutoMirrored.Outlined.KeyboardArrowRight) { SettingsActivity.open(context, SettingsActivity.Page.School) } }
                }
            }
        }
    }
}

/** The calendar's account, unless it only repeats the calendar's name. */
internal fun DeviceCalendar.accountNote(): String? = account.takeIf { it.isNotBlank() && it != name }

@Composable
internal fun onDevice(cal: DeviceCalendar): String? = if (cal.local) stringResource(R.string.calendar_on_device) else null

@Composable
internal fun readOnly(cal: DeviceCalendar): String? = if (!cal.writable) stringResource(R.string.calendar_read_only) else null

/** A calendar in a grouped list: its color, its name and [notes] below. [modifier] describes any choice it offers. */
@Composable
internal fun CalendarRow(
    cal: DeviceCalendar, notes: List<String>, modifier: Modifier = Modifier, onClick: (() -> Unit)? = null,
    enabled: Boolean = true, trailing: (@Composable () -> Unit)? = null,
) {
    GroupRow(
        modifier, onClick = onClick, enabled = enabled, leading = { ColorDot(cal.color) }, trailing = trailing,
        supporting = if (notes.isNotEmpty()) { { Text(notes.joinToString(", ")) } } else null,
    ) { Text(cal.name, maxLines = 2, overflow = TextOverflow.Ellipsis) }
}

/** A calendar choice in a dialog, after its [control]. [modifier] makes the whole row toggle or select it. */
@Composable
internal fun CalendarChoice(cal: DeviceCalendar, note: String?, modifier: Modifier, control: @Composable () -> Unit) {
    Row(modifier.fillMaxWidth().heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
        control()
        Spacer(Modifier.width(12.dp))
        ColorDot(cal.color)
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            // Dialog text is muted by default. The name keeps full contrast above its note.
            Text(cal.name, color = MaterialTheme.colorScheme.onSurface, maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (note != null) Text(
                note, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall,
                maxLines = 2, overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
