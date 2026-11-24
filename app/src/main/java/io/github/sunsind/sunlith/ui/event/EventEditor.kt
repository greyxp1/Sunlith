@file:OptIn(ExperimentalMaterial3Api::class)

package io.github.sunsind.sunlith.ui.event

import android.Manifest
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Notes
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.CheckBox
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Event
import androidx.compose.material.icons.outlined.LocationOn
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material.icons.outlined.Repeat
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.InputChip
import androidx.compose.material3.InputChipDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.error
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.sunsind.sunlith.R
import io.github.sunsind.sunlith.data.DeviceCalendar
import io.github.sunsind.sunlith.ui.Formats
import io.github.sunsind.sunlith.ui.LocalPalette
import io.github.sunsind.sunlith.ui.Notice
import io.github.sunsind.sunlith.ui.RepeatPreset
import io.github.sunsind.sunlith.ui.ScreenTopBar
import io.github.sunsind.sunlith.ui.SunlithViewModel
import io.github.sunsind.sunlith.ui.eventTint
import io.github.sunsind.sunlith.ui.rememberFormats
import io.github.sunsind.sunlith.ui.settings.openAppSettings
import io.github.sunsind.sunlith.ui.timeLabel
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneOffset
import io.github.sunsind.sunlith.system.needsNotificationPermission

private enum class Picking { StartDate, StartTime, EndDate, EndTime }

private val ReminderPresets = listOf(0, 5, 10, 15, 30, 60, 120, 1440, 2880, 10080)

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun EventEditorScreen(vm: SunlithViewModel) {
    val state = vm.editor ?: return
    val p = LocalPalette.current
    val f = rememberFormats()
    val context = androidx.compose.ui.platform.LocalContext.current
    var confirmDiscard by rememberSaveable { mutableStateOf(false) }
    var confirmDelete by rememberSaveable { mutableStateOf(false) }
    var picking by rememberSaveable { mutableStateOf<Picking?>(null) }
    var pendingReminder by rememberSaveable { mutableStateOf<Int?>(null) }
    val writable = vm.writableCalendars
    val problem = state.problem(writable)
    val problemMessage = problem?.let { stringResource(it) }
    val endInvalid = state.endInvalid()

    val close: () -> Unit = { if (vm.editorDirty) confirmDiscard = true else vm.closeEditor() }
    BackHandler(enabled = vm.editorDirty, onBack = close)

    val notifLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        val minutes = pendingReminder
        pendingReminder = null
        vm.onNotificationPermissionResult(granted, fromOnboarding = false)
        if (minutes != null) vm.updateEditor { it.copy(reminders = (it.reminders + minutes).distinct().sorted()) }
        if (!granted) vm.message(context.getString(R.string.msg_notifications_denied), context.getString(R.string.action_open_settings)) { context.openAppSettings() }
    }
    fun addReminder(minutes: Int) {
        val needs = context.needsNotificationPermission()
        if (needs) {
            pendingReminder = minutes
            notifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            if (!vm.remindersEnabled) vm.remindersEnabled = true
            vm.updateEditor { it.copy(reminders = (it.reminders + minutes).distinct().sorted()) }
        }
    }

    Scaffold(
        containerColor = p.background,
        topBar = {
            ScreenTopBar(
                title = stringResource(if (state.id == null) R.string.editor_new else R.string.editor_edit),
                navigationIcon = Icons.Outlined.Close,
                navigationLabel = stringResource(R.string.action_close),
                onNavigate = close,
            ) {
                TextButton(onClick = vm::saveEditor, enabled = problem == null && !vm.saving,
                    modifier = Modifier.padding(end = 4.dp).semantics { if (problemMessage != null) stateDescription = problemMessage }) {
                    Text(stringResource(R.string.action_save), style = MaterialTheme.typography.labelLarge)
                }
            }
        },
    ) { pad ->
        Column(
            Modifier.fillMaxSize().padding(pad).consumeWindowInsets(pad).imePadding()
                .verticalScroll(rememberScrollState()).padding(bottom = 24.dp),
        ) {
            TextField(
                value = state.title,
                onValueChange = { v -> vm.updateEditor { it.copy(title = v) } },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp),
                textStyle = MaterialTheme.typography.headlineSmall,
                label = { Text(stringResource(R.string.editor_title_hint)) },
                colors = plainFieldColors(),
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                maxLines = 3,
            )
            if (state.editsSeries) Notice(stringResource(R.string.editor_series_note), muted = true)
            HorizontalDivider(color = p.line)

            EditorRow(Icons.Outlined.Schedule, onClick = null, modifier = Modifier.toggleable(state.allDay, role = Role.Switch) { value ->
                vm.updateEditor { it.copy(allDay = value) }
            }) {
                Text(stringResource(R.string.all_day), Modifier.weight(1f).padding(end = 16.dp), style = MaterialTheme.typography.bodyLarge)
                Switch(checked = state.allDay, onCheckedChange = null, colors = switchColors())
            }
            DateTimeRow(
                label = stringResource(R.string.editor_starts), date = f.dayShort(state.startDate),
                dateLabel = stringResource(R.string.editor_start_date), timeLabel = stringResource(R.string.editor_start_time),
                time = if (state.allDay) null else f.time(state.startTime), error = false,
                onDate = { picking = Picking.StartDate }, onTime = { picking = Picking.StartTime },
            )
            DateTimeRow(
                label = stringResource(R.string.editor_ends), date = f.dayShort(state.endDate),
                dateLabel = stringResource(R.string.editor_end_date), timeLabel = stringResource(R.string.editor_end_time),
                time = if (state.allDay) null else f.time(state.endTime), error = endInvalid,
                onDate = { picking = Picking.EndDate }, onTime = { picking = Picking.EndTime },
            )
            if (endInvalid) {
                Text(stringResource(R.string.editor_end_before_start), Modifier.padding(start = 56.dp, end = 16.dp, bottom = 8.dp)
                    .semantics { liveRegion = LiveRegionMode.Polite }, color = p.danger, style = MaterialTheme.typography.bodyMedium)
            }
            if (!state.allDay && state.timeZone != java.util.TimeZone.getDefault().id) {
                Text(stringResource(R.string.editor_timezone_note, state.timeZone), Modifier.padding(start = 56.dp, end = 16.dp, bottom = 8.dp), color = p.muted, style = MaterialTheme.typography.bodySmall)
            }
            HorizontalDivider(color = p.line)

            CalendarChooser(state, writable, vm)
            RepeatChooser(state, vm, f)
            EditorRow(Icons.Outlined.CheckBox, onClick = null, modifier = Modifier.toggleable(state.todo, role = Role.Switch) { value ->
                vm.updateEditor { it.copy(todo = value) }
            }) {
                Column(Modifier.weight(1f).padding(end = 16.dp)) {
                    Text(stringResource(R.string.todo_tag), style = MaterialTheme.typography.bodyLarge)
                    Text(stringResource(if (state.recurring) R.string.todo_tag_series_hint else R.string.todo_tag_hint),
                        color = p.muted, style = MaterialTheme.typography.bodySmall)
                }
                Switch(checked = state.todo, onCheckedChange = null, colors = switchColors())
            }
            var addOpen by remember { mutableStateOf(false) }
            EditorRow(Icons.Outlined.Notifications, onClick = null) {
                Column(Modifier.weight(1f)) {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), itemVerticalAlignment = Alignment.CenterVertically) {
                        state.reminders.forEach { minutes ->
                            InputChip(
                                selected = false,
                                onClick = { vm.updateEditor { it.copy(reminders = it.reminders - minutes) } },
                                label = { Text(f.reminder(minutes)) },
                                trailingIcon = { Icon(Icons.Outlined.Close, stringResource(R.string.editor_remove_reminder, f.reminder(minutes)), Modifier.size(InputChipDefaults.IconSize)) },
                            )
                        }
                        Box {
                            AssistChip(
                                onClick = { addOpen = true },
                                label = { Text(stringResource(R.string.editor_add_reminder)) },
                                leadingIcon = { Icon(Icons.Outlined.Add, null, Modifier.size(16.dp)) },
                            )
                            DropdownMenu(expanded = addOpen, onDismissRequest = { addOpen = false }) {
                                ReminderPresets.filter { it !in state.reminders }.forEach { minutes ->
                                    DropdownMenuItem(text = { Text(f.reminder(minutes)) }, onClick = { addOpen = false; addReminder(minutes) })
                                }
                            }
                        }
                    }
                    if (state.reminders.isNotEmpty() && (!vm.remindersEnabled || !vm.notificationsGranted)) {
                        Text(stringResource(R.string.editor_reminders_off), color = p.muted, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
            HorizontalDivider(color = p.line)

            EditorField(Icons.Outlined.LocationOn, state.location, stringResource(R.string.editor_location_hint), singleLine = true) { v -> vm.updateEditor { it.copy(location = v) } }
            EditorField(Icons.AutoMirrored.Outlined.Notes, state.notes, stringResource(R.string.editor_notes_hint), singleLine = false) { v -> vm.updateEditor { it.copy(notes = v) } }

            problem?.let { if (it != R.string.editor_end_before_start && state.title.isNotBlank()) Notice(stringResource(it), muted = true) }

            if (state.id != null) {
                Spacer(Modifier.height(16.dp))
                TextButton(onClick = { confirmDelete = true }, modifier = Modifier.padding(horizontal = 8.dp).heightIn(min = 48.dp)) {
                    Text(stringResource(if (state.editsSeries) R.string.action_delete_series else R.string.action_delete_event), color = p.danger)
                }
            }
        }
    }

    when (picking) {
        Picking.StartDate -> DatePick(state.startDate, { picking = null }) { d -> vm.updateEditor { it.withStartDate(d) } }
        Picking.EndDate -> DatePick(state.endDate, { picking = null }) { d -> vm.updateEditor { it.copy(endDate = d) } }
        Picking.StartTime -> TimePick(state.startTime, f.is24Hour, { picking = null }) { t -> vm.updateEditor { it.withStartTime(t) } }
        Picking.EndTime -> TimePick(state.endTime, f.is24Hour, { picking = null }) { t -> vm.updateEditor { it.copy(endTime = t) } }
        null -> Unit
    }

    if (confirmDiscard) {
        AlertDialog(
            onDismissRequest = { confirmDiscard = false },
            title = { Text(stringResource(R.string.editor_discard_title)) },
            text = { Text(stringResource(R.string.editor_discard_body)) },
            confirmButton = { TextButton(onClick = { confirmDiscard = false; vm.closeEditor() }) { Text(stringResource(R.string.action_discard), color = p.danger) } },
            dismissButton = { TextButton(onClick = { confirmDiscard = false }) { Text(stringResource(R.string.action_keep_editing)) } },
        )
    }
    if (confirmDelete && state.id != null) {
        DeleteDialog(series = state.editsSeries, onDismiss = { confirmDelete = false }) {
            confirmDelete = false
            vm.deleteEvent(state.id)
        }
    }
}

@Composable
fun DeleteDialog(series: Boolean, onDismiss: () -> Unit, onConfirm: () -> Unit) {
    val p = LocalPalette.current
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(if (series) R.string.delete_series_title else R.string.delete_event_title)) },
        text = { Text(stringResource(if (series) R.string.delete_series_body else R.string.delete_event_body)) },
        confirmButton = { TextButton(onClick = onConfirm) { Text(stringResource(if (series) R.string.action_delete_series else R.string.action_delete), color = p.danger) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}

@Composable
private fun CalendarChooser(state: EditorState, writable: List<DeviceCalendar>, vm: SunlithViewModel) {
    val p = LocalPalette.current
    var open by remember { mutableStateOf(false) }
    val current = vm.calendars.firstOrNull { it.id == state.calendarId }
    if (writable.isEmpty()) {
        EditorRow(Icons.Outlined.Event, onClick = null) {
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.editor_no_writable), style = MaterialTheme.typography.bodyLarge)
                TextButton(onClick = { vm.createLocalCalendar { id -> vm.updateEditor { it.copy(calendarId = id) } } }, modifier = Modifier.heightIn(min = 48.dp)) {
                    Text(stringResource(R.string.action_create_local))
                }
            }
        }
        return
    }
    Box {
        EditorRow(Icons.Outlined.Event, onClick = if (state.lockedCalendar) null else ({ open = true })) {
            if (current != null) ColorDot(current.color, Modifier.padding(end = 12.dp))
            Column(Modifier.weight(1f)) {
                Text(current?.name ?: stringResource(R.string.editor_choose_calendar), style = MaterialTheme.typography.bodyLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
                val sub = when {
                    current == null -> null
                    state.lockedCalendar -> stringResource(R.string.editor_calendar_locked, current.account)
                    else -> current.account.takeIf { !current.local && it.isNotBlank() && it != current.name }
                }
                sub?.let { Text(it, color = p.muted, style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis) }
            }
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            writable.forEach { cal ->
                DropdownMenuItem(
                    leadingIcon = { ColorDot(cal.color) },
                    text = {
                        Column {
                            Text(cal.name, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            if (!cal.local && cal.account.isNotBlank() && cal.account != cal.name) Text(cal.account, color = p.muted, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    },
                    onClick = { open = false; vm.updateEditor { it.copy(calendarId = cal.id) } },
                )
            }
        }
    }
}

@Composable
private fun RepeatChooser(state: EditorState, vm: SunlithViewModel, f: Formats) {
    val p = LocalPalette.current
    var open by remember { mutableStateOf(false) }
    val originalCustom = state.originalRrule?.takeIf { RepeatPreset.match(it) == null }
    Box {
        EditorRow(Icons.Outlined.Repeat, onClick = { open = true }) {
            Column(Modifier.weight(1f)) {
                Text(f.repeat(state.rrule), style = MaterialTheme.typography.bodyLarge)
                if (state.rrule != null && RepeatPreset.match(state.rrule) == null) {
                    Text(stringResource(R.string.editor_custom_kept), color = p.muted, style = MaterialTheme.typography.bodySmall)
                }
            }
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            RepeatPreset.entries.forEach { preset ->
                DropdownMenuItem(text = { Text(stringResource(preset.label)) }, onClick = { open = false; vm.updateEditor { it.copy(rrule = preset.rule) } })
            }
            if (originalCustom != null) {
                DropdownMenuItem(text = { Text(stringResource(R.string.repeat_keep_custom, f.repeat(originalCustom))) }, onClick = { open = false; vm.updateEditor { it.copy(rrule = originalCustom) } })
            }
        }
    }
}

@Composable
@OptIn(ExperimentalLayoutApi::class)
private fun DateTimeRow(label: String, date: String, dateLabel: String, timeLabel: String, time: String?, error: Boolean, onDate: () -> Unit, onTime: () -> Unit) {
    val p = LocalPalette.current
    val color = if (error) p.danger else p.ink
    val errorMessage = if (error) stringResource(R.string.editor_end_before_start) else null
    val density = LocalDensity.current
    val measurer = rememberTextMeasurer()
    val dateWidth = with(density) {
        measurer.measure(date, MaterialTheme.typography.bodyLarge, maxLines = 1, softWrap = false).size.width.toDp()
    } + 20.dp
    BoxWithConstraints(Modifier.fillMaxWidth().padding(start = 56.dp, end = 8.dp)) {
        val labelAbove = dateWidth > maxWidth - 72.dp
        FlowRow(Modifier.fillMaxWidth(), itemVerticalAlignment = Alignment.CenterVertically) {
            Text(label, if (labelAbove) Modifier.fillMaxWidth() else Modifier.width(72.dp), color = p.muted,
                style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            FlowRow(if (labelAbove) Modifier.fillMaxWidth() else Modifier.weight(1f),
                horizontalArrangement = Arrangement.SpaceBetween, itemVerticalAlignment = Alignment.CenterVertically) {
                PickerText(date, dateLabel, color, errorMessage, onDate)
                if (time != null) PickerText(time, timeLabel, color, errorMessage, onTime)
            }
        }
    }
}

@Composable
private fun PickerText(text: String, label: String, color: Color, errorMessage: String?, onClick: () -> Unit) {
    Box(
        Modifier.heightIn(min = 48.dp).clip8().clickable(role = Role.Button, onClick = onClick)
            .clearAndSetSemantics {
                contentDescription = "$label, $text"
                role = Role.Button
                if (errorMessage != null) error(errorMessage)
                onClick { onClick(); true }
            }
            .padding(horizontal = 10.dp),
        contentAlignment = Alignment.CenterStart,
    ) { Text(text, color = color, style = MaterialTheme.typography.bodyLarge, maxLines = 1, softWrap = false) }
}

@Composable
private fun EditorRow(icon: ImageVector, onClick: (() -> Unit)?, modifier: Modifier = Modifier, content: @Composable androidx.compose.foundation.layout.RowScope.() -> Unit) {
    val p = LocalPalette.current
    val base = modifier.fillMaxWidth().heightIn(min = 56.dp)
    Row(
        (if (onClick != null) base.clickable(role = Role.Button, onClick = onClick) else base).padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, tint = p.muted, modifier = Modifier.size(24.dp))
        Spacer(Modifier.width(16.dp))
        content()
    }
}

@Composable
private fun EditorField(icon: ImageVector, value: String, hint: String, singleLine: Boolean, onChange: (String) -> Unit) {
    val p = LocalPalette.current
    Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(start = 16.dp), verticalAlignment = if (singleLine) Alignment.CenterVertically else Alignment.Top) {
        Icon(icon, null, tint = p.muted, modifier = Modifier.padding(top = if (singleLine) 0.dp else 16.dp).size(24.dp))
        TextField(
            value = value, onValueChange = onChange, modifier = Modifier.weight(1f),
            label = { Text(hint) }, colors = plainFieldColors(), singleLine = singleLine,
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
        )
    }
}

@Composable
fun plainFieldColors() = TextFieldDefaults.colors(
    focusedContainerColor = Color.Transparent, unfocusedContainerColor = Color.Transparent, disabledContainerColor = Color.Transparent,
    focusedIndicatorColor = Color.Transparent, unfocusedIndicatorColor = Color.Transparent, disabledIndicatorColor = Color.Transparent,
)

@Composable
fun switchColors() = LocalPalette.current.let { p ->
    SwitchDefaults.colors(
        checkedThumbColor = p.onAccent, checkedTrackColor = p.accent, checkedBorderColor = p.accent,
        uncheckedThumbColor = p.muted, uncheckedTrackColor = p.raised, uncheckedBorderColor = p.faint,
    )
}

@Composable
fun ColorDot(color: Int, modifier: Modifier = Modifier) {
    val p = LocalPalette.current
    Box(modifier.size(12.dp).background(eventTint(color, p), CircleShape))
}

@Composable
fun DatePick(initial: LocalDate, onDismiss: () -> Unit, onPick: (LocalDate) -> Unit) {
    val state = rememberDatePickerState(initialSelectedDateMillis = initial.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli())
    DatePickerDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = {
                state.selectedDateMillis?.let { onPick(Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate()) }
                onDismiss()
            }) { Text(stringResource(R.string.action_ok)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    ) { DatePicker(state) }
}

@Composable
private fun TimePick(initial: LocalTime, is24: Boolean, onDismiss: () -> Unit, onPick: (LocalTime) -> Unit) {
    val state = rememberTimePickerState(initial.hour, initial.minute, is24)
    AlertDialog(
        onDismissRequest = onDismiss,
        text = { Box(Modifier.verticalScroll(rememberScrollState()), contentAlignment = Alignment.Center) { TimePicker(state) } },
        confirmButton = { TextButton(onClick = { onPick(LocalTime.of(state.hour, state.minute)); onDismiss() }) { Text(stringResource(R.string.action_ok)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}

fun Modifier.clip8(): Modifier = this.then(Modifier.clip(RoundedCornerShape(8.dp)))
