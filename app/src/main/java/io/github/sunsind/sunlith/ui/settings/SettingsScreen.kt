package io.github.sunsind.sunlith.ui.settings

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.foundation.Image
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material.icons.outlined.ArrowDropDown
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Slider
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
import androidx.compose.ui.semantics.role
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
import io.github.sunsind.sunlith.system.LauncherIcons
import io.github.sunsind.sunlith.ui.ClickRow
import io.github.sunsind.sunlith.ui.GroupRow
import io.github.sunsind.sunlith.ui.ListGroup
import io.github.sunsind.sunlith.ui.Note
import io.github.sunsind.sunlith.ui.PageScaffold
import io.github.sunsind.sunlith.ui.SectionTitle
import io.github.sunsind.sunlith.ui.SunlithViewModel
import io.github.sunsind.sunlith.ui.SurfaceCard
import io.github.sunsind.sunlith.ui.ToggleRow

const val SOURCE_URL = "https://github.com/SunSinD/Sunlith"
const val MIGRATION_URL = "https://github.com/SunSinD/Sunlith/blob/main/docs/MIGRATION.md"

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
    val p = MaterialTheme.colorScheme
    val context = LocalContext.current
    var migrationHelp by rememberSaveable { mutableStateOf(false) }
    val version = remember {
        runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull() ?: "?"
    }

    val scroll = rememberScrollState()
    PageScaffold(
        stringResource(if (widget) R.string.widget_settings_title else R.string.title_settings),
        canScroll = { scroll.canScrollForward || scroll.canScrollBackward },
    ) { pad ->
        Column(Modifier.fillMaxSize().padding(pad).consumeWindowInsets(pad).verticalScroll(scroll).padding(bottom = 32.dp)) {
            when (widget) {
                // Groups split what is changed often from the calendars and files, then the app itself. The widget keeps
                // its own page, which its home-screen setup opens on its own.
                false -> {
                    ListGroup {
                        item {
                            DropdownRow(
                                stringResource(R.string.settings_theme),
                                vm.themeMode,
                                listOf(
                                    "system" to stringResource(R.string.theme_system),
                                    "light" to stringResource(R.string.theme_light),
                                    "dark" to stringResource(R.string.theme_dark),
                                )
                            ) { vm.themeMode = it }
                        }
                        item { LauncherIconRow(vm) }
                        item {
                            ClickRow(stringResource(R.string.settings_notifications), icon = Icons.AutoMirrored.Outlined.KeyboardArrowRight) {
                                SettingsActivity.open(context, SettingsActivity.Page.Notifications)
                            }
                        }
                    }
                    ListGroup {
                        item { ClickRow(stringResource(R.string.title_calendars), icon = Icons.AutoMirrored.Outlined.KeyboardArrowRight) { SettingsActivity.open(context, SettingsActivity.Page.Calendars) } }
                        item { ClickRow(stringResource(R.string.device_link_title), icon = Icons.AutoMirrored.Outlined.KeyboardArrowRight) { SettingsActivity.open(context, SettingsActivity.Page.Computer) } }
                        item { ImportRow(vm) }
                        item { ExportRow(vm) }
                    }
                    // Help for moving in sits under the import it leads to, as a link rather than another setting.
                    TextButton(onClick = { migrationHelp = true }, Modifier.padding(horizontal = 16.dp).heightIn(min = 48.dp)) {
                        Text(stringResource(R.string.help_migration_title))
                    }

                    ListGroup {
                        item { AppUpdateSection(version) }
                        // An outward arrow, as it leaves the app for the browser instead of opening another page.
                        item { ClickRow(stringResource(R.string.about_source), icon = Icons.AutoMirrored.Outlined.OpenInNew) { context.openUrl(SOURCE_URL, vm) } }
                    }
                }
                true -> {
                    Note(stringResource(R.string.settings_widget_shared))
                    SectionTitle(stringResource(R.string.widget_days))
                    var days by remember(vm.widgetDays) { mutableStateOf(vm.widgetDays.toFloat()) }
                    val daysValue = pluralStringResource(R.plurals.widget_days_value, Math.round(days), Math.round(days))
                    SurfaceCard {
                        Text(daysValue, color = p.onSurface, style = MaterialTheme.typography.bodyLarge)
                        val daysLabel = stringResource(R.string.widget_days)
                        Slider(
                            value = days, onValueChange = { days = Math.round(it).toFloat() },
                            onValueChangeFinished = { vm.widgetDays = Math.round(days) },
                            valueRange = 1f..31f, steps = 29,
                            modifier = Modifier.heightIn(min = 48.dp).semantics { contentDescription = daysLabel; stateDescription = daysValue },
                        )
                    }
                    ListGroup {
                        item { ToggleRow(stringResource(R.string.widget_empty_days), null, vm.widgetShowEmptyDays) { vm.widgetShowEmptyDays = it } }
                        item { ToggleRow(stringResource(R.string.widget_calendar_name), null, vm.widgetShowCalendarName) { vm.widgetShowCalendarName = it } }
                    }
                }
            }
        }
    }

    if (migrationHelp) HelpDialog(stringResource(R.string.help_migration_title), stringResource(R.string.help_migration_body), stringResource(R.string.action_open_guide), { migrationHelp = false }) { context.openUrl(MIGRATION_URL, vm) }

    ImportDialog(vm)
}

/** Home-screen icon choice in one row. Each preview is the real adaptive icon, drawn with this phone's icon mask. */
@Composable
private fun LauncherIconRow(vm: SunlithViewModel) {
    val selected = vm.launcherIcon
    var pendingStyle by rememberSaveable { mutableStateOf<String?>(null) }
    val busy = vm.launcherIconBusy
    val styles = LauncherIcons.Style.entries
    val accent = MaterialTheme.colorScheme.primary
    GroupRow(
        trailing = {
            Row(Modifier.selectableGroup(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                styles.forEach { style ->
                    val chosen = selected == style
                    val iconLabel = stringResource(R.string.launcher_icon_choice, stringResource(style.label))
                    Box(
                        Modifier.clip(CircleShape).border(2.dp, if (chosen) accent else Color.Transparent, CircleShape)
                            .semantics { contentDescription = iconLabel }
                            .selectable(chosen, enabled = !busy, role = Role.RadioButton) {
                                if (!chosen && !busy) pendingStyle = style.key
                            }
                            .padding(4.dp),
                    ) { LauncherIconPreview(style.icon, 44.dp) }
                }
            }
        },
    ) { Text(stringResource(R.string.launcher_icon_title)) }
    val target = styles.firstOrNull { it.key == pendingStyle }
    if (target != null) {
        AlertDialog(
            onDismissRequest = { if (!busy) pendingStyle = null },
            title = { Text(stringResource(R.string.launcher_icon_notice_title)) },
            text = { Text(stringResource(R.string.launcher_icon_notice_body), style = MaterialTheme.typography.bodyMedium) },
            confirmButton = {
                TextButton(
                    enabled = !busy,
                    onClick = {
                        pendingStyle = null
                        vm.changeLauncherIcon(target)
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
    var expanded by remember { mutableStateOf(false) }
    val current = options.firstOrNull { it.first == value }?.second.orEmpty()
    val stacked = LocalDensity.current.fontScale > 1.3f
    GroupRow(
        Modifier.semantics { role = Role.DropdownList }, onClick = { expanded = true },
        supporting = if (stacked) { { Text(current) } } else null,
        trailing = {
            Box {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (!stacked) Text(current, Modifier.widthIn(max = 160.dp), style = MaterialTheme.typography.bodyLarge,
                        maxLines = 2, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.End)
                    Icon(Icons.Outlined.ArrowDropDown, null)
                }
                DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                    options.forEach { (key, label) ->
                        val on = key == value
                        DropdownMenuItem(
                            text = { Text(label) },
                            onClick = { expanded = false; if (!on) onSelect(key) },
                            trailingIcon = { if (on) Icon(Icons.Outlined.Check, null, tint = MaterialTheme.colorScheme.primary) },
                            modifier = Modifier.semantics { selected = on },
                        )
                    }
                }
            }
        },
    ) { Text(title) }
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
