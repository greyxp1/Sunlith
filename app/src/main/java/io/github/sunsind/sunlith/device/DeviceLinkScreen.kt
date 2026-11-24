package io.github.sunsind.sunlith.device

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.Intent
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.Uri
import android.text.format.DateUtils
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import io.github.sunsind.sunlith.R
import io.github.sunsind.sunlith.SettingsActivity.Page
import io.github.sunsind.sunlith.ui.ClickRow
import io.github.sunsind.sunlith.ui.LocalPalette
import io.github.sunsind.sunlith.ui.ScreenTopBar
import io.github.sunsind.sunlith.ui.SectionTitle
import io.github.sunsind.sunlith.ui.SunlithViewModel
import io.github.sunsind.sunlith.ui.ToggleRow
import io.github.sunsind.sunlith.ui.ValueRow
import io.github.sunsind.sunlith.ui.event.ColorDot
import io.github.sunsind.sunlith.ui.settings.openAppSettings
import io.github.sunsind.sunlith.ui.settings.openUrl
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import io.github.sunsind.sunlith.system.needsNotificationPermission

@Composable
internal fun DeviceLinkScreen(
    vm: SunlithViewModel,
    page: Page,
    onOpenPage: (Page) -> Unit,
) {
    val context = LocalContext.current
    val p = LocalPalette.current
    val scope = rememberCoroutineScope()
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val connection by DeviceLink.state.collectAsStateWithLifecycle()
    var history by remember { mutableStateOf<List<DeviceLinkChange>?>(null) }
    var diagnostics by remember { mutableStateOf<DeviceLinkDiagnostics?>(null) }
    // Null until the saved pairing is read. The last known answer is reused, so the screen never draws the
    // unpaired setup with every calendar first and then collapses to the shared ones.
    var paired by remember { mutableStateOf(DeviceLinkScreenMemory.paired) }
    var ids by rememberSaveable { mutableStateOf(if (DeviceLinkScreenMemory.paired == true) DeviceLinkScreenMemory.ids else emptySet()) }
    SideEffect {
        DeviceLinkScreenMemory.paired = paired
        if (paired == true) DeviceLinkScreenMemory.ids = ids
    }
    var addresses by remember { mutableStateOf<List<DeviceLinkAddress>?>(null) }
    val choices = addresses.orEmpty()
    val tailscaleAddress = addresses?.any { it.kind == DeviceLinkAddressKind.TAILSCALE }
    var host by rememberSaveable { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var unpair by remember { mutableStateOf(false) }
    var sharedUri by remember { mutableStateOf<Uri?>(null) }
    val pairingShareDirectory = remember(context.applicationContext) { File(context.applicationContext.cacheDir, PAIRING_SHARE_DIR) }
    fun providerUri(file: File): Uri =
        FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file, PAIRING_SHARE_NAME)
    fun sharePairingUri(uri: Uri) {
        try {
            val send = Intent(Intent.ACTION_SEND).apply {
                type = "application/json"
                putExtra(Intent.EXTRA_STREAM, uri)
                clipData = ClipData.newRawUri("Sunlith pairing", uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            context.startActivity(Intent.createChooser(send, context.getString(R.string.connection_setup_share_title)))
        } catch (_: ActivityNotFoundException) {
            vm.message(context.getString(R.string.device_link_no_share))
        }
    }

    LaunchedEffect(lifecycle, page) {
        if (page == Page.Computer) return@LaunchedEffect
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            DeviceLink.activity.collect {
                when (page) {
                    Page.History -> history = withContext(Dispatchers.IO) { DeviceLink.history(context) }
                    Page.Diagnostics -> diagnostics = withContext(Dispatchers.IO) { DeviceLink.diagnostics(context) }
                    else -> Unit
                }
            }
        }
    }

    LaunchedEffect(Unit) {
        val savedIds = withContext(Dispatchers.IO) { DeviceLink.pairedCalendarIds(context) }
        if (savedIds != null) ids = savedIds
        paired = savedIds != null
        // Re-offer the cached file without rotating its token.
        if (savedIds != null && sharedUri == null) {
            sharedUri = withContext(Dispatchers.IO) {
                DeviceLink.cachedPairingShare(context)?.let(::providerUri)
            }
        }
        if (savedIds == null) sharedUri = null
    }
    LaunchedEffect(lifecycle, page, paired) {
        if (page == Page.History || (page == Page.Computer && paired != false)) return@LaunchedEffect
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            callbackFlow {
                val manager = context.getSystemService(ConnectivityManager::class.java)
                val callback = object : ConnectivityManager.NetworkCallback() {
                    override fun onAvailable(network: Network) { trySend(Unit) }
                    override fun onLost(network: Network) { trySend(Unit) }
                    override fun onLinkPropertiesChanged(network: Network, properties: LinkProperties) { trySend(Unit) }
                }
                val request = NetworkRequest.Builder().removeCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN).build()
                manager.registerNetworkCallback(request, callback)
                trySend(Unit)
                awaitClose { manager.unregisterNetworkCallback(callback) }
            }.conflate().collect {
                val next = withContext(Dispatchers.IO) { DeviceLink.addressChoices(context) }
                addresses = next
                if (next.none { it.host == host }) host = next.firstOrNull()?.host.orEmpty()
            }
        }
    }
    LaunchedEffect(connection.error) {
        connection.error?.let {
            vm.message(context.getString(it))
            DeviceLink.clearError(it)
        }
    }

    val notificationLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) DeviceLink.start(context)
        else vm.message(context.getString(R.string.device_link_notification_required), context.getString(R.string.action_open_settings)) { context.openAppSettings() }
    }
    fun connectComputer() {
        val calendarIds = vm.calendars.filter { it.id in ids }.mapTo(mutableSetOf()) { it.id }
        val address = host
        if (busy || calendarIds.isEmpty() || address.isBlank()) return
        scope.launch {
            busy = true
            var created = false
            val pairingShareFile = File(pairingShareDirectory, "sunlith-pairing-${java.util.UUID.randomUUID()}.json")
            try {
                val uri = withContext(Dispatchers.IO) {
                    check(pairingShareDirectory.isDirectory || pairingShareDirectory.mkdirs())
                    // Resolve the shipped provider before creating a pairing and its access key.
                    val uri = providerUri(pairingShareFile)
                    val config = DeviceLink.createPairing(context, calendarIds, address)
                    created = true
                    pairingShareFile.writeText(config, Charsets.UTF_8)
                    uri
                }
                ids = calendarIds
                paired = true
                sharedUri = uri
                sharePairingUri(uri)
            } catch (c: CancellationException) {
                if (created) withContext(kotlinx.coroutines.NonCancellable + Dispatchers.IO) {
                    DeviceLink.revoke(context)
                }
                throw c
            } catch (_: Exception) {
                if (created) withContext(Dispatchers.IO) { DeviceLink.revoke(context) }
                paired = false
                vm.message(context.getString(R.string.device_link_export_error))
            } finally { busy = false }
        }
    }

    if (page == Page.History) {
        ConnectionHistoryScreen(history, busy, onClear = {
            scope.launch {
                busy = true
                try { withContext(Dispatchers.IO) { DeviceLink.clearHistory(context) } }
                catch (_: Exception) { vm.message(context.getString(R.string.device_link_failed)) }
                finally { busy = false }
            }
        })
        return
    }
    if (page == Page.Diagnostics) {
        Scaffold(containerColor = p.background, topBar = { ScreenTopBar(stringResource(R.string.device_link_details)) }) { padding ->
            LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(vertical = 16.dp)) {
                item { ValueRow(stringResource(R.string.device_link_status), stringResource(if (connection.running) R.string.device_link_on else R.string.device_link_off), stacked = true) }
                item { ValueRow(stringResource(R.string.connection_tailscale), stringResource(tailscaleStatusRes(tailscaleAddress)), stacked = true) }
                diagnostics?.let { data ->
                    item { ValueRow(stringResource(R.string.device_link_paired_since), data.pairedAtMillis?.let { connectionDate(context, it) }
                        ?: stringResource(if (paired == true) R.string.device_link_older_pairing else R.string.device_link_not_paired), stacked = true) }
                    item { ValueRow(stringResource(R.string.device_link_last_request), data.lastSuccessMillis?.let { connectionDate(context, it) } ?: stringResource(R.string.device_link_no_contact), stacked = true) }
                }
                addresses?.let { known ->
                    item { ValueRow(stringResource(R.string.device_link_address), known.joinToString("\n") { it.host }.ifBlank { context.getString(R.string.device_link_no_network) }, stacked = true) }
                }
                item { Text(stringResource(R.string.device_link_details_help), Modifier.padding(16.dp), color = p.muted, style = MaterialTheme.typography.bodyMedium) }
                if (paired == true) item {
                    ClickRow(stringResource(R.string.device_link_background)) { context.openAppSettings() }
                }
            }
        }
        return
    }
    Scaffold(containerColor = p.background, topBar = {
        ScreenTopBar(stringResource(R.string.device_link_title))
    }) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(bottom = 40.dp)) {
            if (paired == true) item {
                ToggleRow(
                    stringResource(R.string.device_link_allow_access),
                    stringResource(R.string.connection_setup_paired_body), checked = connection.running, enabled = !busy,
                ) { enabled ->
                    if (!enabled) DeviceLink.stop(context)
                    else if (context.needsNotificationPermission()) {
                        notificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                    } else DeviceLink.start(context)
                }
            } else if (paired == false) {
                item { SectionTitle(stringResource(R.string.connection_setup_step1_title)) }
                item {
                    Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text(stringResource(R.string.connection_setup_step1_body), color = p.muted, style = MaterialTheme.typography.bodyMedium)
                        TailscaleStatus(tailscaleAddress)
                    }
                }
            }
            if (paired == false) {
                item { SectionTitle(stringResource(R.string.connection_setup_step2_title)) }
                item { Text(stringResource(R.string.connection_setup_step2_body), Modifier.padding(horizontal = 16.dp), color = p.muted, style = MaterialTheme.typography.bodyMedium) }
            } else if (paired == true) {
                item { SectionTitle(stringResource(R.string.device_link_access)) }
            }
            if (paired != null) items(vm.calendars.filter { paired == false || it.id in ids }, key = { it.id }) { calendar ->
                Row(
                    Modifier.fillMaxWidth().heightIn(min = 56.dp)
                        .then(if (paired == false) Modifier.toggleable(calendar.id in ids, enabled = !busy, role = Role.Checkbox) {
                            ids = if (it) ids + calendar.id else ids - calendar.id
                        } else Modifier.semantics(mergeDescendants = true) {}).padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    ColorDot(calendar.color)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(calendar.name, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        if (calendar.local || calendar.account != calendar.name) Text(
                            if (calendar.local) stringResource(R.string.calendar_on_device) else calendar.account,
                            color = p.muted, style = MaterialTheme.typography.bodySmall, maxLines = 2,
                        )
                        if (!calendar.writable) Text(stringResource(R.string.calendar_read_only), color = p.muted, style = MaterialTheme.typography.bodySmall)
                    }
                    if (paired == false) Checkbox(calendar.id in ids, onCheckedChange = null, enabled = !busy,
                        colors = CheckboxDefaults.colors(checkedColor = p.accent, checkmarkColor = p.onAccent))
                }
            }
            if (paired == false) {
                if (!vm.hasSunlithCalendar) item {
                    ClickRow(stringResource(R.string.action_create_local), enabled = !busy, icon = Icons.Outlined.Add) {
                        vm.createLocalCalendar(showInApp = false) { ids = ids + it }
                    }
                }
                item { SectionTitle(stringResource(R.string.connection_setup_step3_title)) }
                item { Text(stringResource(if (choices.firstOrNull { it.host == host }?.kind == DeviceLinkAddressKind.TAILSCALE)
                    R.string.connection_setup_step3_body else R.string.connection_setup_step3_local_body), Modifier.padding(horizontal = 16.dp), color = p.muted, style = MaterialTheme.typography.bodyMedium) }
                if (addresses?.isEmpty() == true) item {
                    Text(stringResource(R.string.device_link_no_network), Modifier.padding(16.dp), color = p.muted)
                } else if (choices.size == 1) item {
                    // Default is already the best address; show it in words instead of a one-item radio list.
                    Text(stringResource(R.string.connection_setup_address_one, choices.first().host, stringResource(addressKindRes(choices.first().kind))),
                        Modifier.padding(horizontal = 16.dp), color = p.muted, style = MaterialTheme.typography.bodyMedium)
                } else if (choices.size > 1) {
                    // Several reachable addresses: keep the user's choice, best (Tailscale) first and pre-selected.
                    item { Text(stringResource(R.string.device_link_address), Modifier.padding(horizontal = 16.dp), color = p.muted, style = MaterialTheme.typography.bodyMedium) }
                    item {
                        Column(Modifier.selectableGroup()) {
                            choices.forEach { address ->
                                Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).selectable(address.host == host, enabled = !busy, role = Role.RadioButton) { host = address.host }.padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                                    RadioButton(address.host == host, onClick = null, enabled = !busy, colors = RadioButtonDefaults.colors(selectedColor = p.accent, unselectedColor = p.faint))
                                    Spacer(Modifier.width(12.dp))
                                    Column(Modifier.weight(1f)) {
                                        Text(address.host)
                                        Text(stringResource(addressKindRes(address.kind)), color = p.muted, style = MaterialTheme.typography.bodySmall)
                                    }
                                }
                            }
                        }
                    }
                }
                item {
                    Button(onClick = { connectComputer() },
                        enabled = !busy && vm.calendars.any { it.id in ids } && host.isNotBlank(),
                        modifier = Modifier.fillMaxWidth().padding(16.dp).heightIn(min = 48.dp)) {
                        Text(stringResource(R.string.connection_setup_connect_computer))
                    }
                }
            }
            if (paired != null) {
                item { HorizontalDivider(color = p.line, modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) }
                if (paired == true) {
                    sharedUri?.let { uri ->
                        item { ClickRow(stringResource(R.string.connection_setup_share_again), enabled = !busy) { sharePairingUri(uri) } }
                    }
                }
                item { ClickRow(stringResource(R.string.device_link_history), icon = Icons.AutoMirrored.Outlined.KeyboardArrowRight) { onOpenPage(Page.History) } }
                item { ClickRow(stringResource(R.string.device_link_details), icon = Icons.AutoMirrored.Outlined.KeyboardArrowRight) { onOpenPage(Page.Diagnostics) } }
                item {
                    ClickRow(stringResource(R.string.device_link_guide)) { context.openUrl(COMPUTER_SETUP_GUIDE, vm) }
                }
                if (paired == true) item {
                    ClickRow(stringResource(R.string.device_link_unpair), enabled = !busy, color = p.danger) { unpair = true }
                }
            }
        }
    }
    if (unpair) AlertDialog(
        onDismissRequest = { unpair = false }, containerColor = p.surface,
        title = { Text(stringResource(R.string.device_link_unpair)) },
        text = { Text(stringResource(R.string.device_link_unpair_body)) },
        confirmButton = { TextButton(onClick = {
            unpair = false
            scope.launch {
                busy = true
                try {
                    withContext(Dispatchers.IO) { DeviceLink.revoke(context) }
                    paired = false
                    sharedUri = null
                    vm.message(context.getString(R.string.device_link_unpaired))
                } catch (_: Exception) { vm.message(context.getString(R.string.device_link_failed)) }
                finally { busy = false }
            }
        }) { Text(stringResource(R.string.device_link_unpair)) } },
        dismissButton = { TextButton(onClick = { unpair = false }) { Text(stringResource(R.string.action_cancel)) } },
    )
}

/** Last pairing answer in this process, so reopening the screen starts in the right state. */
private object DeviceLinkScreenMemory {
    var paired: Boolean? = null
    var ids: Set<Long> = emptySet()
}

private const val COMPUTER_SETUP_GUIDE = "https://github.com/SunSinD/Sunlith/blob/main/docs/AI-INTEGRATION.md#direct-phone-connection"
private const val PAIRING_SHARE_DIR = "pairing-share"
private const val PAIRING_SHARE_NAME = "sunlith-pairing.json"

private fun addressKindRes(kind: DeviceLinkAddressKind?): Int = when (kind) {
    DeviceLinkAddressKind.TAILSCALE -> R.string.connection_setup_address_tailscale
    DeviceLinkAddressKind.VPN -> R.string.connection_setup_address_other_vpn
    else -> R.string.connection_setup_address_wifi
}

private fun connectionDate(context: android.content.Context, millis: Long): String = DateUtils.formatDateTime(
    context, millis, DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_SHOW_TIME or DateUtils.FORMAT_SHOW_YEAR,
)

private fun tailscaleStatusRes(detected: Boolean?): Int = when (detected) {
    true -> R.string.connection_tailscale_detected
    false -> R.string.connection_tailscale_missing
    null -> R.string.connection_tailscale_checking
}

@Composable
private fun TailscaleStatus(detected: Boolean?) {
    Row(Modifier.fillMaxWidth().semantics(mergeDescendants = true) {}, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(stringResource(R.string.connection_tailscale), Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
        Text(stringResource(tailscaleStatusRes(detected)), Modifier.weight(1f), color = LocalPalette.current.muted, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun ConnectionHistoryScreen(history: List<DeviceLinkChange>?, busy: Boolean, onClear: () -> Unit) {
    val p = LocalPalette.current
    val context = LocalContext.current
    var confirmClear by rememberSaveable { mutableStateOf(false) }
    Scaffold(containerColor = p.background, topBar = {
        ScreenTopBar(stringResource(R.string.device_link_history), actions = {
            if (!history.isNullOrEmpty()) TextButton(onClick = { confirmClear = true }, enabled = !busy) { Text(stringResource(R.string.device_link_history_clear)) }
        })
    }) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(bottom = 32.dp)) {
            item { Text(stringResource(R.string.device_link_history_help), Modifier.padding(16.dp), color = p.muted, style = MaterialTheme.typography.bodyMedium) }
            if (history?.isEmpty() == true) item { Text(stringResource(R.string.device_link_history_empty), Modifier.padding(16.dp), color = p.muted) }
            items(history.orEmpty()) { change ->
                Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    val title = change.title?.takeIf { it.isNotBlank() } ?: stringResource(R.string.untitled)
                    Text(when (change.action) {
                        DeviceLinkChangeAction.CREATE -> stringResource(R.string.device_link_history_added, title)
                        DeviceLinkChangeAction.UPDATE -> stringResource(R.string.device_link_history_edited, title)
                        DeviceLinkChangeAction.DELETE -> stringResource(R.string.device_link_history_deleted, title)
                        DeviceLinkChangeAction.IMPORT -> stringResource(R.string.device_link_history_imported, change.imported, change.duplicates, change.skipped)
                    }, style = MaterialTheme.typography.bodyLarge)
                    Text(connectionDate(context, change.atMillis), color = p.muted, style = MaterialTheme.typography.bodySmall)
                    if (change.fields.isNotEmpty()) Text(change.fields.joinToString(", ") { field -> context.getString(when (field) {
                        DeviceLinkChangeField.TITLE -> R.string.device_link_field_title
                        DeviceLinkChangeField.DESCRIPTION -> R.string.device_link_field_description
                        DeviceLinkChangeField.LOCATION -> R.string.device_link_field_location
                        DeviceLinkChangeField.TIME -> R.string.device_link_field_time
                        DeviceLinkChangeField.RECURRENCE -> R.string.device_link_field_recurrence
                        DeviceLinkChangeField.REMINDERS -> R.string.device_link_field_reminders
                    }) }, color = p.muted, style = MaterialTheme.typography.bodyMedium)
                }
                HorizontalDivider(color = p.line, modifier = Modifier.padding(horizontal = 16.dp))
            }
        }
    }
    if (confirmClear) AlertDialog(onDismissRequest = { confirmClear = false }, containerColor = p.surface,
        title = { Text(stringResource(R.string.device_link_history_clear)) },
        text = { Text(stringResource(R.string.device_link_history_clear_body)) },
        confirmButton = { TextButton(onClick = { confirmClear = false; onClear() }, enabled = !busy) { Text(stringResource(R.string.device_link_history_clear)) } },
        dismissButton = { TextButton(onClick = { confirmClear = false }) { Text(stringResource(R.string.action_cancel)) } },
    )
}
