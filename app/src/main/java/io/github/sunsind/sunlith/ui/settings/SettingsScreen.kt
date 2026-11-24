package io.github.sunsind.sunlith.ui.settings

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.ArrowDropDown
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.toBitmap
import io.github.sunsind.sunlith.R
import io.github.sunsind.sunlith.SettingsActivity
import io.github.sunsind.sunlith.data.DeviceCalendar
import io.github.sunsind.sunlith.system.LauncherIcons
import io.github.sunsind.sunlith.ui.ClickRow
import io.github.sunsind.sunlith.ui.LocalPalette
import io.github.sunsind.sunlith.ui.Note
import io.github.sunsind.sunlith.ui.Notice
import io.github.sunsind.sunlith.ui.ScreenTopBar
import io.github.sunsind.sunlith.ui.SectionTitle
import io.github.sunsind.sunlith.ui.SunlithViewModel
import io.github.sunsind.sunlith.ui.ToggleRow
import io.github.sunsind.sunlith.ui.event.ColorDot
import java.time.LocalDate
import io.github.sunsind.sunlith.system.needsNotificationPermission

const val SOURCE_URL = "https://github.com/SunSinD/Sunlith"
const val MIGRATION_URL = "https://github.com/SunSinD/Sunlith/blob/main/docs/MIGRATION.md"

private val IcsTypes = arrayOf("text/calendar", "text/x-vcalendar", "application/ics", "application/octet-stream", "text/plain")

fun Context.openUrl(url: String, vm: SunlithViewModel) {
    try {
        startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
    } catch (_: ActivityNotFoundException) {
        vm.message(getString(R.string.msg_no_browser))
    }
}

fun Context.openAppSettings() {
    runCatching {
        startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}

/**
 * Settings on one page, or with [widget] only the widget's, which the widget's own edit button opens since its options
 * apply to the home-screen widget alone.
 */
@Composable
fun SettingsScreen(vm: SunlithViewModel, widget: Boolean = false) {
    val p = LocalPalette.current
    val context = LocalContext.current
    var help by rememberSaveable { mutableStateOf<Int?>(null) }
    var exportChooser by rememberSaveable { mutableStateOf(false) }
    var exportIds by rememberSaveable { mutableStateOf<Set<Long>>(emptySet()) }

    val notifLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        vm.onNotificationPermissionResult(granted, fromOnboarding = false)
        if (!granted) vm.message(context.getString(R.string.msg_notifications_denied), context.getString(R.string.action_open_settings)) { context.openAppSettings() }
    }
    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let(vm::startImport) }
    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/calendar")) { uri ->
        if (uri != null) vm.export(uri, exportIds)
    }
    val version = remember {
        runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull() ?: "?"
    }

    val title = stringResource(if (widget) R.string.widget_settings_title else R.string.title_settings)
    Scaffold(containerColor = p.background, topBar = { ScreenTopBar(title) }) { pad ->
        Column(Modifier.fillMaxSize().padding(pad).consumeWindowInsets(pad).verticalScroll(rememberScrollState()).padding(bottom = 32.dp)) {
            when (widget) {
                // One page with no section titles: dividers split what is changed often from the calendars and files,
                // then the app itself. The widget keeps its own page, which its home-screen setup opens on its own.
                false -> {
                    DropdownRow(
                        stringResource(R.string.settings_theme),
                        vm.themeMode,
                        listOf(
                            "system" to stringResource(R.string.theme_system),
                            "light" to stringResource(R.string.theme_light),
                            "dark" to stringResource(R.string.theme_dark),
                        )
                    ) { vm.themeMode = it }
                    LauncherIconRow(vm)
                    ToggleRow(
                        stringResource(R.string.settings_reminders_toggle),
                        if (vm.remindersEnabled && !vm.notificationsGranted) stringResource(R.string.settings_reminders_blocked) else null,
                        vm.remindersEnabled && vm.notificationsGranted,
                    ) { on ->
                        if (!on) vm.remindersEnabled = false
                        else if (context.needsNotificationPermission()) {
                            notifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                        } else vm.remindersEnabled = true
                    }

                    SettingsDivider()
                    ClickRow(stringResource(R.string.title_calendars), icon = Icons.AutoMirrored.Outlined.KeyboardArrowRight) { SettingsActivity.open(context, SettingsActivity.Page.Calendars) }
                    ClickRow(stringResource(R.string.device_link_title), icon = Icons.AutoMirrored.Outlined.KeyboardArrowRight) { SettingsActivity.open(context, SettingsActivity.Page.Computer) }
                    ClickRow(stringResource(R.string.settings_import)) {
                        try { importLauncher.launch(IcsTypes) } catch (_: ActivityNotFoundException) { vm.message(context.getString(R.string.msg_no_file_picker)) }
                    }
                    ClickRow(stringResource(R.string.settings_export), if (vm.exporting) stringResource(R.string.settings_exporting) else null) {
                        exportIds = vm.activeIds
                        exportChooser = true
                    }
                    // Help for moving in sits under the import it leads to, as a link rather than another setting.
                    Text(stringResource(R.string.help_migration_title), Modifier.fillMaxWidth().clickable(role = Role.Button) { help = R.string.help_migration_title }
                        .heightIn(min = 48.dp).padding(horizontal = 16.dp, vertical = 14.dp), color = p.accent, style = MaterialTheme.typography.bodyMedium)

                    SettingsDivider()
                    AppUpdateSection(version)
                    // An outward arrow, as it leaves the app for the browser instead of opening another page.
                    ClickRow(stringResource(R.string.about_source), icon = Icons.AutoMirrored.Outlined.OpenInNew) { context.openUrl(SOURCE_URL, vm) }
                }
                true -> {
                    Note(stringResource(R.string.settings_widget_shared))
                    SectionTitle(stringResource(R.string.widget_days))
                    var days by remember(vm.widgetDays) { mutableStateOf(vm.widgetDays.toFloat()) }
                    val daysValue = pluralStringResource(R.plurals.widget_days_value, Math.round(days), Math.round(days))
                    Text(daysValue, Modifier.padding(horizontal = 16.dp), color = p.ink, style = MaterialTheme.typography.bodyLarge)
                    val daysLabel = stringResource(R.string.widget_days)
                    Slider(
                        value = days, onValueChange = { days = Math.round(it).toFloat() },
                        onValueChangeFinished = { vm.widgetDays = Math.round(days) },
                        valueRange = 1f..31f, steps = 29,
                        modifier = Modifier.padding(horizontal = 16.dp).heightIn(min = 48.dp).semantics { contentDescription = daysLabel; stateDescription = daysValue },
                        colors = sliderColors(),
                    )
                    ToggleRow(stringResource(R.string.widget_empty_days), null, vm.widgetShowEmptyDays) { vm.widgetShowEmptyDays = it }
                    ToggleRow(stringResource(R.string.widget_calendar_name), null, vm.widgetShowCalendarName) { vm.widgetShowCalendarName = it }
                }
            }
        }
    }

    when (help) {
        R.string.help_migration_title -> HelpDialog(stringResource(R.string.help_migration_title), stringResource(R.string.help_migration_body), stringResource(R.string.action_open_guide), { help = null }) { context.openUrl(MIGRATION_URL, vm) }
    }

    if (exportChooser) {
        CalendarPickDialog(
            title = stringResource(R.string.export_title), calendars = vm.calendars, selected = exportIds,
            onToggle = { id, on -> exportIds = if (on) exportIds + id else exportIds - id },
            confirm = stringResource(R.string.action_choose_file), confirmEnabled = exportIds.isNotEmpty(),
            onDismiss = { exportChooser = false },
        ) {
            exportChooser = false
            try { exportLauncher.launch("sunlith-${LocalDate.now()}.ics") } catch (_: ActivityNotFoundException) { vm.message(context.getString(R.string.msg_no_file_picker)) }
        }
    }

    ImportDialog(vm)
}

/** Home-screen icon choice in one row. Each preview is the real adaptive icon, drawn with this phone's icon mask. */
@Composable
private fun LauncherIconRow(vm: SunlithViewModel) {
    val p = LocalPalette.current
    val context = LocalContext.current
    val selected = vm.launcherIcon
    var pendingStyle by rememberSaveable { mutableStateOf<String?>(null) }
    val busy = vm.launcherIconBusy
    val styles = LauncherIcons.Style.entries
    Row(Modifier.fillMaxWidth().heightIn(min = 72.dp).padding(horizontal = 16.dp, vertical = 8.dp).selectableGroup(),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(stringResource(R.string.launcher_icon_title), Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge, color = p.ink)
        styles.forEach { style ->
            val chosen = selected == style
            val iconLabel = stringResource(R.string.launcher_icon_choice, stringResource(style.label))
            Box(
                Modifier.clip(CircleShape).border(2.dp, if (chosen) p.accent else Color.Transparent, CircleShape)
                    .semantics { contentDescription = iconLabel }
                    .selectable(chosen, enabled = !busy, role = Role.RadioButton) {
                        if (!chosen && !busy) {
                            pendingStyle = style.key
                        }
                    }
                    .padding(4.dp),
            ) { LauncherIconPreview(style.icon, 44.dp) }
        }
    }
    val target = styles.firstOrNull { it.key == pendingStyle }
    if (target != null) {
        AlertDialog(
            onDismissRequest = { if (!busy) pendingStyle = null },
            containerColor = p.surface,
            title = { Text(stringResource(R.string.launcher_icon_notice_title), color = p.ink) },
            text = { Text(stringResource(R.string.launcher_icon_notice_body), color = p.ink, style = MaterialTheme.typography.bodyMedium) },
            confirmButton = {
                TextButton(
                    enabled = !busy,
                    onClick = {
                        val chosenStyle = target
                        pendingStyle = null
                        vm.changeLauncherIcon(chosenStyle)
                    },
                ) { Text(stringResource(R.string.launcher_icon_apply)) }
            },
            dismissButton = {
                TextButton(enabled = !busy, onClick = { pendingStyle = null }) { Text(stringResource(R.string.action_cancel)) }
            },
        )
    }
}

@Composable
private fun LauncherIconPreview(icon: Int, size: Dp) {
    val context = LocalContext.current
    val px = with(LocalDensity.current) { size.roundToPx() }
    val bitmap = remember(icon, px) { ContextCompat.getDrawable(context, icon)?.toBitmap(px, px)?.asImageBitmap() }
    if (bitmap != null) Image(bitmap, contentDescription = null, modifier = Modifier.size(size))
}

/** Shows the current choice, stacking it below the title at large text sizes. */
@Composable
internal fun <T> DropdownRow(title: String, value: T, options: List<Pair<T, String>>, onSelect: (T) -> Unit) {
    val p = LocalPalette.current
    var expanded by remember { mutableStateOf(false) }
    val current = options.firstOrNull { it.first == value }?.second.orEmpty()
    val stacked = LocalDensity.current.fontScale > 1.3f
    Column(
        Modifier.fillMaxWidth().heightIn(min = 56.dp).clickable(role = Role.DropdownList) { expanded = true }
            .padding(start = 16.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
        verticalArrangement = Arrangement.Center,
    ) {
        if (stacked) Text(title, style = MaterialTheme.typography.bodyLarge, color = p.ink)
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            if (!stacked) {
                Text(title, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge, color = p.ink)
                Spacer(Modifier.width(12.dp))
            }
            Box(if (stacked) Modifier.fillMaxWidth() else Modifier.weight(1f, fill = false)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(current, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge, color = p.muted, maxLines = 2, overflow = TextOverflow.Ellipsis,
                        textAlign = if (stacked) TextAlign.Start else TextAlign.End)
                    Icon(Icons.Outlined.ArrowDropDown, null, tint = p.muted)
                }
                DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }, containerColor = p.surface) {
                    options.forEach { (key, label) ->
                        val on = key == value
                        DropdownMenuItem(
                            text = { Text(label, color = p.ink) },
                            onClick = { expanded = false; if (!on) onSelect(key) },
                            trailingIcon = { if (on) Icon(Icons.Outlined.Check, null, tint = p.accent) },
                            modifier = Modifier.semantics { selected = on },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun sliderColors() = LocalPalette.current.let { p ->
    SliderDefaults.colors(thumbColor = p.accent, activeTrackColor = p.accent, inactiveTrackColor = p.raised)
}

@Composable
private fun HelpDialog(title: String, body: String, action: String, onDismiss: () -> Unit, onAction: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(body, Modifier.verticalScroll(rememberScrollState()), style = MaterialTheme.typography.bodyMedium) },
        confirmButton = { TextButton(onClick = onAction) { Text(action) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_close)) } },
    )
}

@Composable
private fun CalendarPickDialog(
    title: String, calendars: List<DeviceCalendar>, selected: Set<Long>, onToggle: (Long, Boolean) -> Unit,
    confirm: String, confirmEnabled: Boolean, onDismiss: () -> Unit, onConfirm: () -> Unit,
) {
    val p = LocalPalette.current
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                calendars.forEach { cal ->
                    val on = cal.id in selected
                    Row(
                        Modifier.fillMaxWidth().heightIn(min = 48.dp).toggleable(on, role = Role.Checkbox) { onToggle(cal.id, it) },
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Checkbox(checked = on, onCheckedChange = null, colors = checkColors())
                        Spacer(Modifier.width(12.dp))
                        ColorDot(cal.color)
                        Spacer(Modifier.width(10.dp))
                        Column(Modifier.weight(1f)) {
                            Text(cal.name, maxLines = 2, overflow = TextOverflow.Ellipsis, color = p.ink)
                            val account = if (cal.local) stringResource(R.string.calendar_on_device) else cal.account
                            if (account.isNotBlank() && account != cal.name) {
                                Text(account, color = p.muted, style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onConfirm, enabled = confirmEnabled) { Text(confirm) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}

@Composable
fun checkColors() = LocalPalette.current.let { p ->
    CheckboxDefaults.colors(checkedColor = p.accent, uncheckedColor = p.faint, checkmarkColor = p.onAccent)
}

@Composable
private fun ImportDialog(vm: SunlithViewModel) {
    val state = vm.importUi ?: return
    val p = LocalPalette.current
    val writable = vm.writableCalendars
    AlertDialog(
        onDismissRequest = { if (!state.busy) vm.dismissImport() },
        title = { Text(stringResource(if (state.report != null) R.string.import_done_title else R.string.import_title)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                val report = state.report
                val preview = state.preview
                when {
                    state.error != null -> Text(state.error, color = p.danger)
                    report != null -> {
                        Text(pluralStringResource(R.plurals.import_imported, report.imported, report.imported))
                        if (report.duplicates > 0) Text(pluralStringResource(R.plurals.import_duplicates, report.duplicates, report.duplicates), color = p.muted)
                        if (report.skipped > 0) Text(pluralStringResource(R.plurals.import_skipped, report.skipped, report.skipped), color = p.muted)
                        Messages(report.messages)
                    }
                    preview == null -> Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(color = p.muted, strokeWidth = 2.dp, modifier = Modifier.padding(end = 12.dp))
                        Text(stringResource(R.string.import_reading))
                    }
                    else -> {
                        Text(pluralStringResource(R.plurals.import_found, preview.total, preview.total))
                        Text(pluralStringResource(R.plurals.import_importable, preview.importable, preview.importable), color = p.muted)
                        if (preview.skipped > 0) Text(pluralStringResource(R.plurals.import_will_skip, preview.skipped, preview.skipped), color = p.muted)
                        Messages(preview.messages)
                        if (preview.importable > 0) {
                            Spacer(Modifier.height(4.dp))
                            Text(stringResource(R.string.import_destination), style = MaterialTheme.typography.titleSmall)
                            if (writable.isEmpty()) {
                                Text(stringResource(R.string.editor_no_writable), color = p.muted)
                                TextButton(onClick = { vm.createLocalCalendar { vm.chooseImportCalendar(it) } }) { Text(stringResource(R.string.action_create_local)) }
                            }
                            Column(Modifier.selectableGroup()) {
                                writable.forEach { cal ->
                                    Row(
                                        Modifier.fillMaxWidth().heightIn(min = 48.dp).selectable(state.calendarId == cal.id, role = Role.RadioButton) { vm.chooseImportCalendar(cal.id) },
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        RadioButton(selected = state.calendarId == cal.id, onClick = null, colors = RadioButtonDefaults.colors(selectedColor = p.accent, unselectedColor = p.faint))
                                        Spacer(Modifier.width(8.dp))
                                        ColorDot(cal.color)
                                        Spacer(Modifier.width(10.dp))
                                        Column {
                                            Text(cal.name, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                            if (cal.account.isNotBlank() && cal.account != cal.name) Text(cal.account, color = p.muted, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                        }
                                    }
                                }
                            }
                            if (state.busy) Text(stringResource(R.string.import_running), color = p.muted)
                        }
                    }
                }
            }
        },
        confirmButton = {
            val preview = state.preview
            if (state.report == null && state.error == null && preview != null && preview.importable > 0) {
                TextButton(onClick = vm::runImport, enabled = !state.busy && state.calendarId != null && writable.any { it.id == state.calendarId }) {
                    Text(stringResource(R.string.action_import))
                }
            } else {
                TextButton(onClick = vm::dismissImport, enabled = !state.busy || state.preview == null) { Text(stringResource(if (state.report != null) R.string.action_done else R.string.action_close)) }
            }
        },
        dismissButton = {
            if (state.report == null && state.error == null && state.preview != null && state.preview.importable > 0) {
                TextButton(onClick = vm::dismissImport, enabled = !state.busy) { Text(stringResource(R.string.action_cancel)) }
            }
        },
    )
}

@Composable
private fun Messages(messages: List<String>) {
    if (messages.isEmpty()) return
    val p = LocalPalette.current
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        messages.take(8).forEach { Text(it, color = p.muted, style = MaterialTheme.typography.bodySmall) }
        if (messages.size > 8) Text(pluralStringResource(R.plurals.more_messages, messages.size - 8, messages.size - 8), color = p.muted, style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
fun CalendarsScreen(vm: SunlithViewModel) {
    val context = LocalContext.current
    val p = LocalPalette.current
    val error = vm.loadError
    val calendarsKnown = vm.calendars.isNotEmpty() || (vm.loaded && error == null)
    Scaffold(containerColor = p.background, topBar = { ScreenTopBar(stringResource(R.string.title_calendars)) }) { pad ->
        LazyColumn(Modifier.fillMaxSize().padding(pad), contentPadding = PaddingValues(bottom = 48.dp)) {
            if (vm.calendars.isEmpty()) {
                when {
                    error != null -> item { Notice(error, danger = true, action = stringResource(R.string.action_retry)) { vm.reload() } }
                    !vm.loaded -> item { CircularProgressIndicator(Modifier.padding(16.dp), color = p.accent) }
                    else -> item { Note(stringResource(R.string.no_calendars_on_phone)) }
                }
            }
            items(vm.calendars, key = { it.id }) { cal ->
                val on = cal.id in vm.activeIds
                Row(
                    Modifier.fillMaxWidth().heightIn(min = 56.dp)
                        .toggleable(on, role = Role.Checkbox) { vm.setCalendarActive(cal.id, it) }
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    ColorDot(cal.color)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(cal.name, style = MaterialTheme.typography.bodyLarge)
                        val notes = buildList {
                            if (cal.account.isNotBlank() && cal.account != cal.name) add(cal.account)
                            if (cal.local) add(stringResource(R.string.calendar_on_device))
                            if (!cal.writable) add(stringResource(R.string.calendar_read_only))
                        }
                        if (notes.isNotEmpty()) Text(notes.joinToString(", "), color = p.muted, style = MaterialTheme.typography.bodySmall)
                    }
                    Spacer(Modifier.width(12.dp))
                    Checkbox(checked = on, onCheckedChange = null, colors = checkColors())
                }
            }
            item { SettingsDivider() }
            if (calendarsKnown && !vm.hasSunlithCalendar) item {
                ClickRow(stringResource(R.string.action_create_local), stringResource(R.string.settings_local_sub), icon = Icons.Outlined.Add) {
                    vm.createLocalCalendar()
                }
            }
            item { ClickRow(stringResource(R.string.school_title), icon = Icons.AutoMirrored.Outlined.KeyboardArrowRight) { SettingsActivity.open(context, SettingsActivity.Page.School) } }
        }
    }
}

@Composable
private fun SettingsDivider() = HorizontalDivider(Modifier.padding(vertical = 8.dp), color = LocalPalette.current.line)
