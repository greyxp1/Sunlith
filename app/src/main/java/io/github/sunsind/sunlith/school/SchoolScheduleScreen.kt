package io.github.sunsind.sunlith.school

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.provider.Settings
import android.text.format.DateUtils
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.autofill.ContentType
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentType
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import io.github.sunsind.sunlith.R
import io.github.sunsind.sunlith.system.notificationsAllowed
import io.github.sunsind.sunlith.ui.ClickRow
import io.github.sunsind.sunlith.ui.LocalPalette
import io.github.sunsind.sunlith.ui.Note
import io.github.sunsind.sunlith.ui.Notice
import io.github.sunsind.sunlith.ui.ScreenTopBar
import io.github.sunsind.sunlith.ui.SectionTitle
import io.github.sunsind.sunlith.ui.SurfaceCard
import io.github.sunsind.sunlith.ui.ToggleRow
import io.github.sunsind.sunlith.ui.ValueRow
import io.github.sunsind.sunlith.ui.settings.checkColors
import io.github.sunsind.sunlith.ui.settings.openAppSettings
import java.time.Instant
import java.time.ZoneId
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import io.github.sunsind.sunlith.system.needsNotificationPermission

private const val STALE_AFTER_MILLIS = 24L * 60 * 60 * 1000
private const val MONTMORENCY_PORTAL = "https://enligne.cmontmorency.qc.ca/ColNet/"

private fun isMontmorencyPortal(address: String) = runCatching {
    SchoolPortalClient.normalize(address).toString().equals(SchoolPortalClient.normalize(MONTMORENCY_PORTAL).toString(), ignoreCase = true)
}.getOrDefault(false)

private val AlertGroups = listOf(
    R.string.school_group_classes to listOf(
        SchoolAlert.CLASS_TIMES, SchoolAlert.ROOMS, SchoolAlert.TEACHERS, SchoolAlert.ADDED_CLASSES, SchoolAlert.REMOVED_CLASSES,
    ),
    R.string.school_group_notices to listOf(SchoolAlert.TEACHER_ABSENCES, SchoolAlert.ASSESSMENTS, SchoolAlert.SCHOOL_DAYS, SchoolAlert.ADMINISTRATION_MESSAGES),
    R.string.school_group_connection to listOf(SchoolAlert.CONNECTION_PROBLEMS),
)

private fun SchoolError.message(): Int = when (this) {
    SchoolError.INVALID_PORTAL -> R.string.school_error_portal
    SchoolError.SIGN_IN -> R.string.school_error_sign_in
    SchoolError.MFA_REQUIRED -> R.string.school_error_mfa
    SchoolError.NETWORK -> R.string.school_error_network
    SchoolError.CALENDAR_PERMISSION -> R.string.school_error_calendar_permission
    SchoolError.INVALID_DATA -> R.string.school_error_data
    SchoolError.UNAVAILABLE -> R.string.school_error_unavailable
    SchoolError.STORAGE -> R.string.school_error_storage
    SchoolError.LEGACY_COLLISION -> R.string.school_error_existing_classes
    SchoolError.CALENDAR_CHANGED -> R.string.school_error_calendar_changed
    SchoolError.CALENDAR_LIMIT -> R.string.school_error_calendar_limit
}

private fun Context.openNotificationSettings() {
    val intent = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
        .putExtra(Settings.EXTRA_APP_PACKAGE, packageName)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    runCatching { startActivity(intent) }.onFailure { openAppSettings() }
}

private fun zoneLabel(id: String): String {
    val offset = runCatching { ZoneId.of(id).rules.getOffset(Instant.now()).id }.getOrNull()
    val utc = when (offset) { null -> ""; "Z" -> " (UTC)"; else -> " (UTC$offset)" }
    return id.replace('_', ' ') + utc
}

internal enum class SchoolPage { Overview, Alerts, Connection, SignIn }

@Composable
internal fun SchoolScheduleScreen(
    controller: SchoolScheduleManager, onBack: () -> Unit, onChooseCalendars: () -> Unit, onOpenMessages: () -> Unit,
    initialPage: SchoolPage, onOpenPage: (SchoolPage) -> Unit,
) {
    val p = LocalPalette.current
    val state by controller.state.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var signingIn by rememberSaveable { mutableStateOf(false) }
    val currentPage = if (!state.connected || signingIn) SchoolPage.SignIn else initialPage
    val title = stringResource(when (currentPage) {
        SchoolPage.Alerts -> R.string.school_notifications
        SchoolPage.Connection -> R.string.school_connection_details
        SchoolPage.SignIn -> if (state.connected) R.string.school_sign_in_again else R.string.school_title
        SchoolPage.Overview -> R.string.school_title
    })

    val localBack = currentPage == SchoolPage.SignIn && state.connected && !state.busy
    val back: () -> Unit = { if (localBack) signingIn = false else onBack() }
    BackHandler(enabled = localBack, onBack = back)

    Scaffold(containerColor = p.background, topBar = { ScreenTopBar(title, onNavigate = back) }) { pad ->
        key(currentPage) {
            var failed by remember { mutableStateOf(false) }
            fun launchAction(action: suspend () -> Unit, after: () -> Unit = {}) {
                failed = false
                scope.launch {
                    try {
                        action()
                        after()
                    } catch (e: CancellationException) {
                        throw e
                    } catch (_: Exception) {
                        failed = true
                    }
                }
            }

            Column(
                Modifier.fillMaxSize().padding(pad).consumeWindowInsets(pad).imePadding()
                    .semantics { paneTitle = title },
            ) {
                if (failed) Notice(stringResource(R.string.school_error_generic), danger = true)
                Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(bottom = 32.dp)) {
                    when (currentPage) {
                        SchoolPage.Overview -> ConnectionError(state, onSignInAgain = { signingIn = true })
                        SchoolPage.Connection -> ConnectionError(state)
                        else -> Unit
                    }
                    when (currentPage) {
                        SchoolPage.SignIn -> SignInForm(
                            state = state,
                            changing = state.connected,
                            onCancel = back,
                            onChooseCalendars = onChooseCalendars,
                        ) { portal, user, password, zone, calendarId, clear ->
                            launchAction({ controller.connect(portal, user, password, zone, calendarId) }) {
                                val now = controller.state.value
                                if (now.connected && now.error == null) {
                                    clear()
                                    signingIn = false
                                }
                            }
                        }
                        SchoolPage.Overview -> {
                            state.pendingChanges?.let { changes ->
                                PendingCard(
                                    changes, state.busy,
                                    onApply = { launchAction({ controller.applyPendingChanges() }) },
                                    onDiscard = { launchAction({ controller.discardPendingChanges() }) },
                                )
                            }
                            StatusCard(state, onSync = { launchAction({ controller.refresh() }) })
                            ClickRow(stringResource(R.string.school_messages_open), onClick = onOpenMessages)
                            ClickRow(stringResource(R.string.school_notifications)) { onOpenPage(SchoolPage.Alerts) }
                            ClickRow(stringResource(R.string.school_connection_details)) { onOpenPage(SchoolPage.Connection) }
                        }
                        SchoolPage.Alerts -> NotificationsSection(
                            state.notifications,
                            onEnabled = { enabled -> launchAction({ controller.setNotificationsEnabled(enabled) }) },
                            onCategory = { alert, enabled -> launchAction({ controller.setCategory(alert, enabled) }) },
                        )
                        SchoolPage.Connection -> ConnectionDetails(
                            state,
                            onSignInAgain = { signingIn = true },
                            onDisconnect = { remove -> launchAction({ controller.disconnect(remove) }) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SignInForm(
    state: SchoolScheduleState,
    changing: Boolean,
    onCancel: () -> Unit,
    onChooseCalendars: () -> Unit,
    onConnect: (String, String, String, String, Long?, () -> Unit) -> Unit,
) {
    val p = LocalPalette.current
    var portal by rememberSaveable { mutableStateOf(state.portalAddress) }
    var school by rememberSaveable { mutableStateOf(if (changing) { if (isMontmorencyPortal(state.portalAddress)) "montmorency" else "custom" } else "") }
    var pickSchool by rememberSaveable { mutableStateOf(false) }
    var username by rememberSaveable { mutableStateOf(if (changing) state.accountName else "") }
    // Never saved to the instance state bundle.
    var password by remember { mutableStateOf("") }
    var showPassword by remember { mutableStateOf(false) }
    var zone by rememberSaveable { mutableStateOf(state.timeZone) }
    var chosenCalendar by rememberSaveable { mutableStateOf(state.calendarId) }
    var more by rememberSaveable { mutableStateOf(false) }
    var pickZone by rememberSaveable { mutableStateOf(false) }
    val calendars = state.availableCalendars
    val calendarId = if (changing) state.calendarId else (chosenCalendar?.takeIf { id -> calendars.any { it.id == id } }
        ?: state.calendarId?.takeIf { id -> calendars.any { it.id == id } }
        ?: calendars.firstOrNull()?.id)
    val destination = calendars.firstOrNull { it.id == calendarId }
    val busy = state.busy
    val ready = !busy && calendarId != null && school.isNotBlank() && portal.isNotBlank() && username.isNotBlank() && password.isNotEmpty()
    val submit = {
        if (ready) {
            showPassword = false
            onConnect(portal.trim(), username.trim(), password, zone, calendarId) { password = "" }
        }
    }

    SectionTitle(stringResource(R.string.school_provider_colnet))
    if (!changing) Note(stringResource(R.string.school_intro))
    if (changing) ValueRow(stringResource(R.string.school_institution), stringResource(if (school == "montmorency") R.string.school_montmorency else R.string.school_other_colnet), stacked = true)
    else ClickRow(
        stringResource(R.string.school_institution),
        stringResource(when (school) { "montmorency" -> R.string.school_montmorency; "custom" -> R.string.school_other_colnet; else -> R.string.school_choose_institution }),
        enabled = !busy,
    ) { pickSchool = true }
    val error = state.error
    if (error != null && !busy) Notice(stringResource(error.message()), danger = true)
    if (changing) {
        if (school == "custom") ValueRow(stringResource(R.string.school_portal_short), portal, stacked = true)
        ValueRow(stringResource(R.string.school_username), username, stacked = true)
    }

    Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (school == "custom" && !changing) OutlinedTextField(
            value = portal, onValueChange = { portal = it },
            label = { Text(stringResource(R.string.school_portal)) },
            singleLine = true, enabled = !busy,
            isError = error == SchoolError.INVALID_PORTAL,
            keyboardOptions = KeyboardOptions(
                capitalization = KeyboardCapitalization.None, autoCorrectEnabled = false,
                keyboardType = KeyboardType.Uri, imeAction = ImeAction.Next,
            ),
            colors = schoolFieldColors(),
            modifier = Modifier.fillMaxWidth(),
        )
        if (school == "custom" && !changing) Text(stringResource(R.string.school_portal_help), color = p.muted, style = MaterialTheme.typography.bodySmall)
        if (!changing) OutlinedTextField(
            value = username, onValueChange = { username = it },
            label = { Text(stringResource(R.string.school_username)) },
            singleLine = true, enabled = !busy,
            isError = error == SchoolError.SIGN_IN,
            keyboardOptions = KeyboardOptions(
                capitalization = KeyboardCapitalization.None, autoCorrectEnabled = false,
                keyboardType = KeyboardType.Text, imeAction = ImeAction.Next,
            ),
            colors = schoolFieldColors(),
            modifier = Modifier.fillMaxWidth().semantics { contentType = ContentType.Username },
        )
        OutlinedTextField(
            value = password, onValueChange = { password = it },
            label = { Text(stringResource(R.string.school_password)) },
            singleLine = true, enabled = !busy,
            isError = error == SchoolError.SIGN_IN,
            visualTransformation = if (showPassword) VisualTransformation.None else PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(
                capitalization = KeyboardCapitalization.None, autoCorrectEnabled = false,
                keyboardType = KeyboardType.Password, imeAction = ImeAction.Done,
            ),
            keyboardActions = KeyboardActions(onDone = { submit() }),
            trailingIcon = {
                IconButton(onClick = { showPassword = !showPassword }, enabled = !busy) {
                    Icon(
                        if (showPassword) Icons.Outlined.VisibilityOff else Icons.Outlined.Visibility,
                        stringResource(if (showPassword) R.string.school_password_hide else R.string.school_password_show),
                        tint = p.muted,
                    )
                }
            },
            colors = schoolFieldColors(),
            modifier = Modifier.fillMaxWidth().semantics { contentType = ContentType.Password },
        )
        Text(stringResource(R.string.school_private_note), color = p.muted, style = MaterialTheme.typography.bodySmall)
    }

    when {
        calendars.size > 1 && !changing -> {
            SectionTitle(stringResource(R.string.school_destination))
            Column(Modifier.selectableGroup()) {
                calendars.forEach { cal ->
                    val on = cal.id == calendarId
                    Row(
                        Modifier.fillMaxWidth().heightIn(min = 48.dp)
                            .selectable(on, enabled = !busy, role = Role.RadioButton) { chosenCalendar = cal.id }
                            .padding(horizontal = 16.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = on, onClick = null, colors = RadioButtonDefaults.colors(selectedColor = p.accent, unselectedColor = p.faint))
                        Spacer(Modifier.width(12.dp))
                        Text(cal.name, Modifier.weight(1f), color = p.ink, style = MaterialTheme.typography.bodyLarge)
                    }
                }
            }
        }
        destination != null -> Note(stringResource(R.string.school_destination_single, destination.name))
        changing && state.calendarsLoaded -> Notice(stringResource(R.string.school_error_calendar_permission), danger = true)
        state.calendarsLoaded -> Notice(stringResource(R.string.school_no_calendar), action = stringResource(R.string.action_choose_calendars), onAction = onChooseCalendars)
    }

    ExpandRow(stringResource(R.string.school_more_options), more) { more = !more }
    if (more) {
        if (changing) ValueRow(stringResource(R.string.school_time_zone), zoneLabel(zone), stacked = true)
        else ClickRow(
            stringResource(R.string.school_time_zone), zoneLabel(zone), enabled = !busy,
        ) { pickZone = true }
        Note(stringResource(R.string.school_time_zone_sub))
    }

    Column(Modifier.padding(horizontal = 16.dp, vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(
            onClick = submit, enabled = ready,
            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
            colors = ButtonDefaults.buttonColors(containerColor = p.accent, contentColor = p.onAccent),
        ) {
            if (busy) {
                CircularProgressIndicator(Modifier.size(18.dp), color = p.onAccent, strokeWidth = 2.dp)
                Spacer(Modifier.width(10.dp))
                Text(stringResource(R.string.school_connecting))
            } else {
                Text(stringResource(R.string.school_connect))
            }
        }
        if (changing) {
            TextButton(onClick = onCancel, enabled = !busy, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                Text(stringResource(R.string.action_cancel))
            }
        }
    }

    if (pickZone) ZoneDialog(zone, onDismiss = { pickZone = false }) { zone = it; pickZone = false }
    if (pickSchool) AlertDialog(
        onDismissRequest = { pickSchool = false },
        containerColor = p.surface,
        title = { Text(stringResource(R.string.school_choose_institution), color = p.ink) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                ZoneOption(stringResource(R.string.school_montmorency), school == "montmorency") {
                    school = "montmorency"; portal = MONTMORENCY_PORTAL; zone = "America/Toronto"; pickSchool = false
                }
                ZoneOption(stringResource(R.string.school_other_colnet), school == "custom") {
                    school = "custom"; portal = ""; zone = ZoneId.systemDefault().id; pickSchool = false
                }
                Text(stringResource(R.string.school_supported_note), color = p.muted, style = MaterialTheme.typography.bodySmall)
            }
        },
        confirmButton = { TextButton(onClick = { pickSchool = false }) { Text(stringResource(R.string.action_cancel)) } },
    )
}

@Composable
private fun ConnectionDetails(
    state: SchoolScheduleState,
    onSignInAgain: () -> Unit,
    onDisconnect: (Boolean) -> Unit,
) {
    val p = LocalPalette.current
    var confirmDisconnect by rememberSaveable { mutableStateOf(false) }

    if (isMontmorencyPortal(state.portalAddress)) {
        ValueRow(stringResource(R.string.school_institution), stringResource(R.string.school_montmorency), stacked = true)
    } else if (state.portalAddress.isNotBlank()) {
        ValueRow(stringResource(R.string.school_portal_short), state.portalAddress, stacked = true)
    }
    if (state.accountName.isNotBlank()) {
        ValueRow(stringResource(R.string.school_username), state.accountName, stacked = true)
    }
    state.availableCalendars.firstOrNull { it.id == state.calendarId }?.let {
        Note(pluralStringResource(R.plurals.school_event_count, state.eventCount, state.eventCount, it.name))
    }
    ValueRow(stringResource(R.string.school_time_zone), zoneLabel(state.timeZone), stacked = true)
    ClickRow(stringResource(R.string.school_sign_in_again), enabled = !state.busy, onClick = onSignInAgain)
    ClickRow(stringResource(R.string.school_disconnect), enabled = !state.busy, color = p.danger) { confirmDisconnect = true }

    if (confirmDisconnect) {
        DisconnectDialog(state.eventCount, onDismiss = { confirmDisconnect = false }) { remove ->
            confirmDisconnect = false
            onDisconnect(remove)
        }
    }
}

@Composable
private fun ConnectionError(state: SchoolScheduleState, onSignInAgain: (() -> Unit)? = null) {
    val error = state.error ?: return
    if (state.busy) return
    val context = LocalContext.current
    val message = stringResource(error.message()) +
        if (error == SchoolError.STORAGE) " " + stringResource(R.string.school_storage_recovery) else ""
    when {
        error == SchoolError.SIGN_IN && onSignInAgain != null ->
            Notice(message, danger = true, action = stringResource(R.string.school_sign_in_again), onAction = onSignInAgain)
        error == SchoolError.CALENDAR_PERMISSION ->
            Notice(message, danger = true, action = stringResource(R.string.school_open_android_settings)) { context.openAppSettings() }
        else -> Notice(message, danger = true)
    }
}

@Composable
private fun StatusCard(state: SchoolScheduleState, onSync: () -> Unit) {
    val p = LocalPalette.current
    val context = LocalContext.current
    val last = state.lastSuccessMillis
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var clockChange by remember { mutableIntStateOf(0) }
    DisposableEffect(context) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) { clockChange++ }
        }
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_TIME_CHANGED)
            addAction(Intent.ACTION_TIMEZONE_CHANGED)
        }
        ContextCompat.registerReceiver(context, receiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
        onDispose { context.unregisterReceiver(receiver) }
    }
    val stale by key(last, clockChange) {
        produceState(last != null && System.currentTimeMillis() - last >= STALE_AFTER_MILLIS, lifecycle) {
            if (last == null) return@produceState
            lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
                while (true) {
                    val remaining = STALE_AFTER_MILLIS - (System.currentTimeMillis() - last)
                    value = remaining <= 0
                    if (value) break
                    delay(remaining)
                }
            }
        }
    }
    SurfaceCard {
        Text(
            state.termName.ifBlank { stringResource(R.string.school_connected) },
            color = p.ink, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Medium,
            modifier = Modifier.semantics { heading() },
        )
        val status = when {
            state.busy -> stringResource(R.string.school_syncing)
            last == null -> stringResource(R.string.school_never_updated)
            else -> stringResource(
                if (stale) R.string.school_stale else R.string.school_updated,
                DateUtils.formatDateTime(
                    context, last, DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_SHOW_TIME or DateUtils.FORMAT_ABBREV_MONTH,
                ),
            )
        }
        Row(Modifier.semantics(mergeDescendants = true) { if (state.busy) liveRegion = LiveRegionMode.Polite }, verticalAlignment = Alignment.CenterVertically) {
            if (state.busy) {
                CircularProgressIndicator(Modifier.size(16.dp), color = p.muted, strokeWidth = 2.dp)
                Spacer(Modifier.width(8.dp))
            }
            Text(status, color = if (stale && !state.busy) p.danger else p.ink, style = MaterialTheme.typography.bodyMedium)
        }
        Button(
            onClick = onSync, enabled = !state.busy,
            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
            colors = ButtonDefaults.buttonColors(containerColor = p.raised, contentColor = p.ink),
        ) { Text(stringResource(R.string.school_sync_now)) }
    }
}

@Composable
private fun PendingCard(
    changes: SchoolChangeSummary,
    busy: Boolean,
    onApply: () -> Unit,
    onDiscard: () -> Unit,
) {
    val p = LocalPalette.current
    SurfaceCard(emphasis = true) {
        Text(
            stringResource(if (changes.linked > 0) R.string.school_link_title else R.string.school_review_title),
            color = p.ink, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Medium,
            modifier = Modifier.semantics { heading(); liveRegion = LiveRegionMode.Polite },
        )
        if (changes.linked > 0) PendingLine(pluralStringResource(R.plurals.school_pending_linked, changes.linked, changes.linked))
        if (changes.linked > 0) PendingLine(stringResource(R.string.school_link_managed))
        if (changes.added > 0) PendingLine(pluralStringResource(R.plurals.school_pending_added, changes.added, changes.added))
        if (changes.updated > 0) PendingLine(pluralStringResource(R.plurals.school_pending_updated, changes.updated, changes.updated))
        if (changes.removed > 0) PendingLine(pluralStringResource(R.plurals.school_pending_removed, changes.removed, changes.removed))
        Text(stringResource(R.string.school_pending_hold), color = p.muted, style = MaterialTheme.typography.bodySmall)
        Spacer(Modifier.height(4.dp))
        Button(
            onClick = onApply, enabled = !busy, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
            colors = ButtonDefaults.buttonColors(containerColor = p.accent, contentColor = p.onAccent),
        ) { Text(stringResource(R.string.school_apply)) }
        TextButton(onClick = onDiscard, enabled = !busy, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
            Text(stringResource(R.string.school_not_now))
        }
    }
}

@Composable
private fun PendingLine(text: String) {
    Text(text, color = LocalPalette.current.ink, style = MaterialTheme.typography.bodyMedium)
}

@Composable
private fun NotificationsSection(
    prefs: SchoolNotificationPreferences,
    onEnabled: (Boolean) -> Unit,
    onCategory: (SchoolAlert, Boolean) -> Unit,
) {
    val p = LocalPalette.current
    val context = LocalContext.current
    var allowed by remember { mutableStateOf(context.notificationsAllowed()) }
    var denied by remember { mutableStateOf(false) }
    LifecycleResumeEffect(Unit) {
        allowed = context.notificationsAllowed()
        if (allowed) denied = false
        onPauseOrDispose { }
    }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        allowed = context.notificationsAllowed()
        if (granted && allowed) {
            denied = false
            onEnabled(true)
        } else {
            denied = true
        }
    }
    val on = prefs.enabled && allowed

    ToggleRow(
        title = stringResource(R.string.school_allow_notifications),
        subtitle = null,
        checked = on,
    ) { enable ->
        if (!enable) {
            onEnabled(false)
        } else if (context.needsNotificationPermission()
        ) {
            launcher.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else if (!allowed) {
            denied = true
        } else {
            onEnabled(true)
        }
    }
    if (denied || (prefs.enabled && !allowed)) {
        Notice(
            stringResource(R.string.school_notifications_blocked),
            action = stringResource(R.string.school_open_android_settings),
            onAction = { context.openNotificationSettings() },
        )
    }
    if (on) {
        AlertGroups.forEach { (group, alerts) ->
            Text(
                stringResource(group),
                Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 2.dp).semantics { heading() },
                color = p.muted, style = MaterialTheme.typography.labelLarge,
            )
            alerts.forEach { alert ->
                val checked = alert in prefs.categories
                Row(
                    Modifier.fillMaxWidth().heightIn(min = 48.dp)
                        .toggleable(checked, role = Role.Checkbox) { keep -> onCategory(alert, keep) }
                        .padding(horizontal = 16.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(stringResource(alert.label()), Modifier.weight(1f), color = p.ink, style = MaterialTheme.typography.bodyLarge)
                    Spacer(Modifier.width(12.dp))
                    Checkbox(checked = checked, onCheckedChange = null, colors = checkColors())
                }
            }
        }
    }
}

@Composable
private fun DisconnectDialog(eventCount: Int, onDismiss: () -> Unit, onConfirm: (Boolean) -> Unit) {
    val p = LocalPalette.current
    var remove by rememberSaveable { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = p.surface,
        title = { Text(stringResource(R.string.school_disconnect_title), color = p.ink) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.school_disconnect_body), color = p.ink, style = MaterialTheme.typography.bodyMedium)
                if (eventCount > 0) {
                    Row(
                        Modifier.fillMaxWidth().heightIn(min = 48.dp).toggleable(remove, role = Role.Checkbox) { remove = it },
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Checkbox(checked = remove, onCheckedChange = null, colors = checkColors())
                        Spacer(Modifier.width(12.dp))
                        Text(
                            pluralStringResource(R.plurals.school_disconnect_remove, eventCount, eventCount),
                            Modifier.weight(1f), color = p.ink, style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                    if (remove) Text(stringResource(R.string.school_disconnect_remove_note), color = p.muted, style = MaterialTheme.typography.bodySmall)
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(remove && eventCount > 0) }, modifier = Modifier.heightIn(min = 48.dp)) {
                Text(
                    stringResource(if (remove && eventCount > 0) R.string.school_disconnect_remove_confirm else R.string.school_disconnect),
                    color = if (remove && eventCount > 0) p.danger else p.accent,
                )
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.action_cancel)) }
        },
    )
}

@Composable
private fun ZoneDialog(current: String, onDismiss: () -> Unit, onPick: (String) -> Unit) {
    val p = LocalPalette.current
    val device = remember { ZoneId.systemDefault().id }
    val zones = remember {
        ZoneId.getAvailableZoneIds()
            .filter { '/' in it && !it.startsWith("Etc/") && !it.startsWith("SystemV/") }
            .sorted()
            .map { it to zoneLabel(it) }
    }
    var query by rememberSaveable { mutableStateOf("") }
    val shown = remember(query) {
        val q = query.trim().lowercase()
        if (q.isEmpty()) zones else zones.filter { it.second.lowercase().contains(q) }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = p.surface,
        title = { Text(stringResource(R.string.school_time_zone), color = p.ink) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = query, onValueChange = { query = it }, singleLine = true,
                    label = { Text(stringResource(R.string.school_time_zone_search)) },
                    keyboardOptions = KeyboardOptions(autoCorrectEnabled = false, imeAction = ImeAction.Search),
                    colors = schoolFieldColors(),
                    modifier = Modifier.fillMaxWidth(),
                )
                LazyColumn(Modifier.fillMaxWidth().heightIn(max = 320.dp).selectableGroup()) {
                    if (query.isBlank()) {
                        item(key = "device") {
                            ZoneOption(stringResource(R.string.school_time_zone_device, zoneLabel(device)), current == device) { onPick(device) }
                            HorizontalDivider(color = p.line)
                        }
                    }
                    if (shown.isEmpty()) {
                        item(key = "none") {
                            Text(stringResource(R.string.school_time_zone_none), Modifier.padding(vertical = 12.dp), color = p.muted)
                        }
                    }
                    items(shown, key = { it.first }) { (id, label) ->
                        ZoneOption(label, current == id) { onPick(id) }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.action_cancel)) }
        },
    )
}

@Composable
private fun ZoneOption(label: String, selected: Boolean, onClick: () -> Unit) {
    val p = LocalPalette.current
    Row(
        Modifier.fillMaxWidth().heightIn(min = 48.dp).selectable(selected, role = Role.RadioButton, onClick = onClick),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = null, colors = RadioButtonDefaults.colors(selectedColor = p.accent, unselectedColor = p.faint))
        Spacer(Modifier.width(8.dp))
        Text(label, Modifier.weight(1f), color = p.ink, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun ExpandRow(title: String, expanded: Boolean, onToggle: () -> Unit) {
    val p = LocalPalette.current
    val state = stringResource(if (expanded) R.string.school_expanded else R.string.school_collapsed)
    Row(
        Modifier.fillMaxWidth().heightIn(min = 56.dp).clickable(role = Role.Button, onClick = onToggle)
            .semantics { stateDescription = state }.padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(title, Modifier.weight(1f), color = p.ink, style = MaterialTheme.typography.bodyLarge)
        Icon(if (expanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore, null, tint = p.muted)
    }
}

@Composable
private fun schoolFieldColors() = LocalPalette.current.let { p ->
    OutlinedTextFieldDefaults.colors(
        focusedTextColor = p.ink, unfocusedTextColor = p.ink, disabledTextColor = p.muted,
        focusedBorderColor = p.accent, unfocusedBorderColor = p.faint, disabledBorderColor = p.line,
        focusedLabelColor = p.ink, unfocusedLabelColor = p.muted, disabledLabelColor = p.faint,
        cursorColor = p.accent, errorBorderColor = p.danger, errorLabelColor = p.danger, errorCursorColor = p.danger,
        focusedContainerColor = Color.Transparent, unfocusedContainerColor = Color.Transparent, disabledContainerColor = Color.Transparent,
    )
}
