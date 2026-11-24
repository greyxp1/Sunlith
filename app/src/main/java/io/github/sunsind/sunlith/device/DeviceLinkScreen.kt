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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import io.github.sunsind.sunlith.R
import io.github.sunsind.sunlith.system.needsNotificationPermission
import io.github.sunsind.sunlith.ui.ClickRow
import io.github.sunsind.sunlith.ui.GroupItem
import io.github.sunsind.sunlith.ui.GroupRow
import io.github.sunsind.sunlith.ui.ListGroup
import io.github.sunsind.sunlith.ui.PageScaffold
import io.github.sunsind.sunlith.ui.SectionTitle
import io.github.sunsind.sunlith.ui.SunlithViewModel
import io.github.sunsind.sunlith.ui.ToggleRow
import io.github.sunsind.sunlith.ui.plusBottom
import io.github.sunsind.sunlith.ui.selectState
import io.github.sunsind.sunlith.ui.settings.CalendarRow
import io.github.sunsind.sunlith.ui.settings.accountNote
import io.github.sunsind.sunlith.ui.settings.onDevice
import io.github.sunsind.sunlith.ui.settings.openAppSettings
import io.github.sunsind.sunlith.ui.settings.readOnly
import io.github.sunsind.sunlith.ui.toggleState
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
internal fun DeviceLinkScreen(vm: SunlithViewModel) {
    val context = LocalContext.current
    val p = MaterialTheme.colorScheme
    val scope = rememberCoroutineScope()
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val connection by DeviceLink.state.collectAsStateWithLifecycle()
    var lastRequest by remember { mutableStateOf<Long?>(null) }
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
    var host by rememberSaveable { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var unpair by remember { mutableStateOf(false) }
    var pairingFile by remember { mutableStateOf<File?>(null) }
    var setup by rememberSaveable { mutableStateOf(false) }
    var details by rememberSaveable(paired) { mutableStateOf(false) }
    val pairingShareDirectory = remember(context.applicationContext) { File(context.applicationContext.cacheDir, PAIRING_SHARE_DIR) }
    fun providerUri(file: File): Uri =
        FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file, COMPUTER_SETUP_ARCHIVE)
    fun shareArchive(uri: Uri) {
        try {
            val send = Intent(Intent.ACTION_SEND).apply {
                type = "application/zip"
                putExtra(Intent.EXTRA_STREAM, uri)
                clipData = ClipData.newRawUri(COMPUTER_SETUP_ARCHIVE, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            context.startActivity(Intent.createChooser(send, context.getString(R.string.connection_setup_share_title)))
        } catch (_: ActivityNotFoundException) {
            vm.message(context.getString(R.string.device_link_no_share))
        }
    }

    LaunchedEffect(lifecycle, paired) {
        lastRequest = null
        if (paired == true) lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            DeviceLink.activity.collect {
                lastRequest = withContext(Dispatchers.IO) { DeviceLink.lastRequest(context) }
            }
        }
    }

    LaunchedEffect(Unit) {
        val savedIds = withContext(Dispatchers.IO) { DeviceLink.pairedCalendarIds(context) }
        if (savedIds != null) ids = savedIds
        paired = savedIds != null
        pairingFile = withContext(Dispatchers.IO) { DeviceLink.cachedPairingShare(context) }
    }
    LaunchedEffect(lifecycle) {
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

    var pendingShare by rememberSaveable { mutableStateOf<Uri?>(null) }
    val notificationLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) DeviceLink.start(context)
        else vm.message(context.getString(R.string.device_link_notification_required), context.getString(R.string.action_open_settings)) { context.openAppSettings() }
        pendingShare?.let(::shareArchive)
        pendingShare = null
    }
    fun enableAccess(archive: Uri? = null) {
        if (context.needsNotificationPermission()) {
            pendingShare = archive
            notificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            DeviceLink.start(context)
            archive?.let(::shareArchive)
        }
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
                    val config = DeviceLink.createPairing(context, calendarIds, address)
                    created = true
                    pairingShareFile.writeText(config, Charsets.UTF_8)
                    providerUri(computerSetupArchive(context, pairingShareFile))
                }
                ids = calendarIds
                paired = true
                pairingFile = pairingShareFile
                enableAccess(uri)
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

    fun reshare(file: File) {
        setup = false
        scope.launch {
            busy = true
            try {
                val uri = withContext(Dispatchers.IO) { providerUri(computerSetupArchive(context, file)) }
                shareArchive(uri)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                vm.message(context.getString(R.string.device_link_export_error))
            } finally { busy = false }
        }
    }

    PageScaffold(stringResource(R.string.device_link_title)) { padding ->
        LazyColumn(Modifier.fillMaxSize(), contentPadding = padding.plusBottom(40.dp)) {
            if (paired == true) {
                item {
                    ListGroup {
                        item {
                            val last = lastRequest
                            val status = stringResource(if (connection.running) R.string.device_link_available else R.string.device_link_paused)
                            val contact = when {
                                last != null -> stringResource(R.string.device_link_last_request, connectionDate(context, last))
                                connection.running -> stringResource(R.string.device_link_no_contact)
                                else -> null
                            }
                            ToggleRow(
                                stringResource(R.string.device_link_allow_access),
                                subtitle = listOfNotNull(status, contact).joinToString("\n"),
                                checked = connection.running,
                                enabled = !busy,
                            ) { enabled ->
                                if (enabled) enableAccess() else DeviceLink.stop(context)
                            }
                        }
                        item {
                            ClickRow(stringResource(R.string.connection_setup_command),
                                stringResource(R.string.connection_setup_command_hint), enabled = !busy,
                                icon = Icons.AutoMirrored.Outlined.KeyboardArrowRight) { setup = true }
                        }
                        item {
                            ClickRow(stringResource(R.string.device_link_options),
                                icon = if (details) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore) { details = !details }
                        }
                        if (details && host.isNotBlank()) item {
                            val label = stringResource(R.string.device_link_address)
                            ClickRow(label, host, icon = Icons.Outlined.ContentCopy) { copyComputerText(context, label, host) }
                        }
                    }
                }
                if (details) item { SectionTitle(stringResource(R.string.device_link_access)) }
            } else if (paired == false) {
                item {
                    Text(stringResource(R.string.connection_setup_step1_body),
                        Modifier.padding(horizontal = 16.dp, vertical = 8.dp), color = p.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
                }
                item { SectionTitle(stringResource(R.string.connection_setup_step2_title)) }
            }
            if (paired == false || (paired == true && details)) {
                val shown = vm.calendars.filter { paired == false || it.id in ids }
                val createLocal = paired == false && !vm.hasSunlithCalendar
                val count = shown.size + if (createLocal) 1 else 0
                itemsIndexed(shown, key = { _, calendar -> calendar.id }) { index, calendar ->
                    val on = calendar.id in ids
                    GroupItem(index, count) {
                        CalendarRow(
                            calendar, listOfNotNull(onDevice(calendar) ?: calendar.accountNote(), readOnly(calendar)),
                            if (paired == false) Modifier.toggleState(on, Role.Checkbox) else Modifier.semantics(mergeDescendants = true) {},
                            onClick = if (paired == false) { { ids = if (on) ids - calendar.id else ids + calendar.id } } else null,
                            enabled = !busy,
                            trailing = if (paired == false) { { Checkbox(on, onCheckedChange = null, enabled = !busy) } } else null,
                        )
                    }
                }
                if (createLocal) item {
                    GroupItem(count - 1, count) {
                        ClickRow(stringResource(R.string.action_create_local), enabled = !busy, icon = Icons.Outlined.Add) {
                            vm.createLocalCalendar(showInApp = false) { ids = ids + it }
                        }
                    }
                }
            }
            if (paired == false) {
                if (addresses?.isEmpty() == true) item {
                    Text(stringResource(R.string.device_link_no_network), Modifier.padding(16.dp), color = p.onSurfaceVariant)
                } else if (choices.size > 1) item {
                    ListGroup {
                        item {
                            val selected = choices.firstOrNull { it.host == host }
                            ClickRow(stringResource(R.string.device_link_address), subtitle = stringResource(addressKindRes(selected?.kind)),
                                icon = if (details) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore) { details = !details }
                        }
                        if (details) choices.forEach { address ->
                            item {
                                val chosen = address.host == host
                                GroupRow(
                                    Modifier.selectState(chosen), onClick = { host = address.host }, enabled = !busy,
                                    leading = { RadioButton(chosen, onClick = null, enabled = !busy) },
                                    supporting = { Text(stringResource(addressKindRes(address.kind))) },
                                ) { Text(address.host) }
                            }
                        }
                    }
                }
                item {
                    Button(onClick = { connectComputer() },
                        enabled = !busy && vm.calendars.any { it.id in ids } && host.isNotBlank(),
                        modifier = Modifier.fillMaxWidth().padding(16.dp).heightIn(min = 56.dp)) {
                        Text(stringResource(R.string.connection_setup_connect_computer))
                    }
                }
            }
            if (paired == true && details) item {
                ListGroup { item { ClickRow(stringResource(R.string.device_link_unpair), enabled = !busy, color = p.error) { unpair = true } } }
            }
        }
    }
    if (setup) ComputerSetupDialog(onShare = pairingFile?.let { file -> { reshare(file) } }) { setup = false }
    if (unpair) AlertDialog(
        onDismissRequest = { unpair = false },
        title = { Text(stringResource(R.string.device_link_unpair)) },
        text = { Text(stringResource(R.string.device_link_unpair_body)) },
        confirmButton = { TextButton(onClick = {
            unpair = false
            scope.launch {
                busy = true
                try {
                    withContext(Dispatchers.IO) { DeviceLink.revoke(context) }
                    paired = false
                    pairingFile = null
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

private const val PAIRING_SHARE_DIR = "pairing-share"

private fun addressKindRes(kind: DeviceLinkAddressKind?): Int = when (kind) {
    DeviceLinkAddressKind.TAILSCALE -> R.string.connection_setup_address_tailscale
    DeviceLinkAddressKind.VPN -> R.string.connection_setup_address_other_vpn
    else -> R.string.connection_setup_address_wifi
}

private fun connectionDate(context: android.content.Context, millis: Long): String = DateUtils.formatDateTime(
    context, millis, DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_SHOW_TIME or DateUtils.FORMAT_SHOW_YEAR,
)
