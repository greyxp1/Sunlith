package io.github.sunsind.sunlith.ui.settings

import android.content.ActivityNotFoundException
import android.content.Context
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import io.github.sunsind.sunlith.R
import io.github.sunsind.sunlith.data.CalendarRepository
import io.github.sunsind.sunlith.data.IcsTransfer
import io.github.sunsind.sunlith.data.ImportPreview
import io.github.sunsind.sunlith.data.ImportReport
import io.github.sunsind.sunlith.ui.ClickRow
import io.github.sunsind.sunlith.ui.SunlithViewModel
import io.github.sunsind.sunlith.ui.userMessage
import java.io.File
import java.io.FileNotFoundException
import java.time.LocalDate
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private val IcsTypes = arrayOf("text/calendar", "text/x-vcalendar", "application/ics", "application/octet-stream", "text/plain")

data class ImportUi(
    val uri: Uri,
    val busy: Boolean = true,
    val preview: ImportPreview? = null,
    val calendarId: Long? = null,
    val report: ImportReport? = null,
    val error: String? = null,
)

/** Reads and writes .ics files. [onImported] refreshes whatever shows the imported events. */
class CalendarTransfer internal constructor(
    private val context: Context,
    private val scope: CoroutineScope,
    repo: CalendarRepository,
    private val defaultCalendarId: () -> Long?,
    private val onImported: () -> Unit,
    private val message: (String) -> Unit,
) {
    private val ics = IcsTransfer(repo)
    var importUi by mutableStateOf<ImportUi?>(null); private set
    var exporting by mutableStateOf(false); private set

    fun startImport(uri: Uri) {
        importUi = ImportUi(uri)
        scope.launch {
            try {
                val preview = withContext(Dispatchers.IO) {
                    val stream = context.contentResolver.openInputStream(uri) ?: throw FileNotFoundException()
                    stream.use { ics.inspect(it) }
                }
                if (importUi?.uri != uri) return@launch
                importUi = ImportUi(uri, busy = false, preview = preview, calendarId = defaultCalendarId())
            } catch (c: CancellationException) {
                throw c
            } catch (e: Exception) {
                if (importUi?.uri == uri) importUi = ImportUi(uri, busy = false, error = e.userMessage(context, R.string.error_read_file))
            }
        }
    }

    fun chooseImportCalendar(id: Long) {
        importUi = importUi?.copy(calendarId = id)
    }

    fun runImport() {
        val state = importUi ?: return
        val calendarId = state.calendarId ?: return
        if (state.busy) return
        importUi = state.copy(busy = true)
        scope.launch {
            try {
                val report = withContext(Dispatchers.IO) {
                    val stream = context.contentResolver.openInputStream(state.uri) ?: throw FileNotFoundException()
                    stream.use { ics.import(it, calendarId) }
                }
                importUi = importUi?.copy(busy = false, report = report)
                onImported()
            } catch (c: CancellationException) {
                throw c
            } catch (e: Exception) {
                importUi = importUi?.copy(busy = false, error = e.userMessage(context))
            }
        }
    }

    fun dismissImport() { importUi = null }

    fun export(uri: Uri, calendarIds: Set<Long>) {
        if (exporting) return
        exporting = true
        scope.launch {
            try {
                val report = withContext(Dispatchers.IO) {
                    val staged = File.createTempFile("sunlith-export-", ".ics", context.cacheDir)
                    try {
                        val exported = staged.outputStream().use { ics.export(it, calendarIds) }
                        val stream = runCatching { context.contentResolver.openOutputStream(uri, "wt") }.getOrNull()
                            ?: context.contentResolver.openOutputStream(uri) ?: throw FileNotFoundException()
                        stream.use { output -> staged.inputStream().use { it.copyTo(output) } }
                        exported
                    } finally { staged.delete() }
                }
                message(context.resources.getQuantityString(R.plurals.msg_exported, report.exported, report.exported))
                if (report.skipped > 0) message(context.getString(R.string.msg_export_skipped))
            } catch (c: CancellationException) {
                throw c
            } catch (e: Exception) {
                message(e.userMessage(context))
            } finally {
                exporting = false
            }
        }
    }
}

/** Opens a file to import. Its review dialog also serves files shared to Sunlith from other apps. */
@Composable
internal fun ImportRow(vm: SunlithViewModel) {
    val context = LocalContext.current
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let(vm.transfer::startImport) }
    ClickRow(stringResource(R.string.settings_import)) {
        try { launcher.launch(IcsTypes) } catch (_: ActivityNotFoundException) { vm.message(context.getString(R.string.msg_no_file_picker)) }
    }
}

/** Chooses the calendars to export, then the file to write them to. */
@Composable
internal fun ExportRow(vm: SunlithViewModel) {
    val context = LocalContext.current
    var chooser by rememberSaveable { mutableStateOf(false) }
    var ids by rememberSaveable { mutableStateOf<Set<Long>>(emptySet()) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/calendar")) { uri ->
        if (uri != null) vm.transfer.export(uri, ids)
    }
    ClickRow(stringResource(R.string.settings_export), if (vm.transfer.exporting) stringResource(R.string.settings_exporting) else null) {
        ids = vm.activeIds
        chooser = true
    }
    if (chooser) AlertDialog(
        onDismissRequest = { chooser = false },
        title = { Text(stringResource(R.string.export_title)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                vm.calendars.forEach { cal ->
                    val on = cal.id in ids
                    CalendarChoice(
                        cal, onDevice(cal) ?: cal.accountNote(),
                        Modifier.toggleable(on, role = Role.Checkbox) { ids = if (it) ids + cal.id else ids - cal.id },
                    ) { Checkbox(checked = on, onCheckedChange = null) }
                }
            }
        },
        confirmButton = {
            TextButton(enabled = ids.isNotEmpty(), onClick = {
                chooser = false
                try { launcher.launch("sunlith-${LocalDate.now()}.ics") } catch (_: ActivityNotFoundException) { vm.message(context.getString(R.string.msg_no_file_picker)) }
            }) { Text(stringResource(R.string.action_choose_file)) }
        },
        dismissButton = { TextButton(onClick = { chooser = false }) { Text(stringResource(R.string.action_cancel)) } },
    )
}

@Composable
internal fun ImportDialog(vm: SunlithViewModel) {
    val transfer = vm.transfer
    val state = transfer.importUi ?: return
    val p = MaterialTheme.colorScheme
    val writable = vm.writableCalendars
    val choosing = state.report == null && state.error == null && state.preview != null && state.preview.importable > 0
    AlertDialog(
        onDismissRequest = { if (!state.busy) transfer.dismissImport() },
        title = { Text(stringResource(if (state.report != null) R.string.import_done_title else R.string.import_title)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                val report = state.report
                val preview = state.preview
                when {
                    state.error != null -> Text(state.error, color = p.error)
                    report != null -> {
                        Text(pluralStringResource(R.plurals.import_imported, report.imported, report.imported))
                        if (report.duplicates > 0) Text(pluralStringResource(R.plurals.import_duplicates, report.duplicates, report.duplicates), color = p.onSurfaceVariant)
                        if (report.skipped > 0) Text(pluralStringResource(R.plurals.import_skipped, report.skipped, report.skipped), color = p.onSurfaceVariant)
                        Messages(report.messages)
                    }
                    preview == null -> Row(verticalAlignment = Alignment.CenterVertically) {
                        LoadingIndicator(Modifier.padding(end = 12.dp))
                        Text(stringResource(R.string.import_reading))
                    }
                    else -> {
                        Text(pluralStringResource(R.plurals.import_found, preview.total, preview.total))
                        Text(pluralStringResource(R.plurals.import_importable, preview.importable, preview.importable), color = p.onSurfaceVariant)
                        if (preview.skipped > 0) Text(pluralStringResource(R.plurals.import_will_skip, preview.skipped, preview.skipped), color = p.onSurfaceVariant)
                        Messages(preview.messages)
                        if (preview.importable > 0) {
                            Spacer(Modifier.height(4.dp))
                            Text(stringResource(R.string.import_destination), style = MaterialTheme.typography.titleSmall)
                            if (writable.isEmpty()) {
                                Text(stringResource(R.string.editor_no_writable), color = p.onSurfaceVariant)
                                TextButton(onClick = { vm.createLocalCalendar { transfer.chooseImportCalendar(it) } }) { Text(stringResource(R.string.action_create_local)) }
                            }
                            Column(Modifier.selectableGroup()) {
                                writable.forEach { cal ->
                                    val chosen = state.calendarId == cal.id
                                    CalendarChoice(
                                        cal, cal.accountNote(),
                                        Modifier.selectable(chosen, role = Role.RadioButton) { transfer.chooseImportCalendar(cal.id) },
                                    ) { RadioButton(selected = chosen, onClick = null) }
                                }
                            }
                            if (state.busy) Text(stringResource(R.string.import_running), color = p.onSurfaceVariant)
                        }
                    }
                }
            }
        },
        confirmButton = {
            if (choosing) {
                TextButton(onClick = transfer::runImport, enabled = !state.busy && state.calendarId != null && writable.any { it.id == state.calendarId }) {
                    Text(stringResource(R.string.action_import))
                }
            } else {
                TextButton(onClick = transfer::dismissImport, enabled = !state.busy || state.preview == null) { Text(stringResource(if (state.report != null) R.string.action_done else R.string.action_close)) }
            }
        },
        dismissButton = {
            if (choosing) TextButton(onClick = transfer::dismissImport, enabled = !state.busy) { Text(stringResource(R.string.action_cancel)) }
        },
    )
}

@Composable
private fun Messages(messages: List<String>) {
    if (messages.isEmpty()) return
    val p = MaterialTheme.colorScheme
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        messages.take(8).forEach { Text(it, color = p.onSurfaceVariant, style = MaterialTheme.typography.bodySmall) }
        if (messages.size > 8) Text(pluralStringResource(R.plurals.more_messages, messages.size - 8, messages.size - 8), color = p.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
    }
}
