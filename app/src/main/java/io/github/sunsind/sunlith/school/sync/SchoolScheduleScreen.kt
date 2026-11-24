package io.github.sunsind.sunlith.school.sync

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.text.format.DateUtils
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.outlined.ArrowDropDown
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
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
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import io.github.sunsind.sunlith.R
import io.github.sunsind.sunlith.SettingsActivity
import io.github.sunsind.sunlith.school.SchoolChangeSummary
import io.github.sunsind.sunlith.school.SchoolError
import io.github.sunsind.sunlith.school.SchoolScheduleState
import io.github.sunsind.sunlith.school.portal.MONTMORENCY_PORTAL
import io.github.sunsind.sunlith.school.portal.isMontmorencyPortal
import io.github.sunsind.sunlith.ui.ClickRow
import io.github.sunsind.sunlith.ui.ListGroup
import io.github.sunsind.sunlith.ui.Notice
import io.github.sunsind.sunlith.ui.PageScaffold
import io.github.sunsind.sunlith.ui.SurfaceCard
import io.github.sunsind.sunlith.ui.ValueRow
import io.github.sunsind.sunlith.ui.settings.openAppSettings
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private const val STALE_AFTER_MILLIS = 24L * 60 * 60 * 1000

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

@Composable
internal fun SchoolScheduleScreen(
    controller: SchoolScheduleManager,
) {
    val p = MaterialTheme.colorScheme
    val state by controller.state.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var signingIn by rememberSaveable { mutableStateOf(false) }
    val showSignIn = !state.connected || signingIn
    val reviewingSetup = !showSignIn && (state.pendingChanges?.legacyLinks ?: 0) > 0
    val title = stringResource(when {
        signingIn && state.connected -> R.string.school_sign_in_again
        reviewingSetup -> R.string.school_finish_setup
        else -> R.string.school_title
    })

    BackHandler(enabled = signingIn && state.connected && !state.busy) { signingIn = false }

    PageScaffold(title) { pad ->
        key(showSignIn, reviewingSetup) {
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
                    if (showSignIn) {
                        SignInForm(state = state, changing = state.connected) { portal, user, password, clear ->
                            launchAction({ controller.connect(portal, user, password) }) {
                                val now = controller.state.value
                                if (now.connected && now.error == null) {
                                    clear()
                                    signingIn = false
                                }
                            }
                        }
                    } else {
                        ConnectionError(state) { signingIn = true }
                        state.pendingChanges?.let { changes ->
                            PendingCard(
                                changes, state.busy,
                                onApply = { launchAction({ controller.applyPendingChanges() }) },
                                onDiscard = { launchAction({ controller.discardPendingChanges() }) },
                            )
                        }
                        if (!reviewingSetup) {
                            SyncSection(state, onSync = { launchAction({ controller.refresh() }) },
                                onDisconnect = { remove -> launchAction({ controller.disconnect(remove) }) })
                            val context = LocalContext.current
                            ListGroup {
                                item {
                                    ClickRow(stringResource(R.string.settings_notifications), icon = Icons.AutoMirrored.Outlined.KeyboardArrowRight) {
                                        SettingsActivity.open(context, SettingsActivity.Page.Notifications)
                                    }
                                }
                            }
                        }
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
    onConnect: (String, String, String, () -> Unit) -> Unit,
) {
    val p = MaterialTheme.colorScheme
    var portal by rememberSaveable { mutableStateOf(state.portalAddress) }
    var school by rememberSaveable { mutableStateOf(if (changing) { if (isMontmorencyPortal(state.portalAddress)) "montmorency" else "custom" } else "") }
    var pickSchool by rememberSaveable { mutableStateOf(false) }
    var username by rememberSaveable { mutableStateOf(if (changing) state.accountName else "") }
    // Never saved to the instance state bundle.
    var password by remember { mutableStateOf("") }
    var showPassword by remember { mutableStateOf(false) }
    val busy = state.busy
    val ready = !busy && school.isNotBlank() && portal.isNotBlank() && username.isNotBlank() && password.isNotEmpty()
    val submit = {
        if (ready) {
            showPassword = false
            onConnect(portal.trim(), username.trim(), password) { password = "" }
        }
    }

    val error = state.error
    ListGroup {
        if (changing) {
            item { ValueRow(stringResource(R.string.school_institution), stringResource(if (school == "montmorency") R.string.school_montmorency else R.string.school_other_colnet), stacked = true) }
            if (school == "custom") item { ValueRow(stringResource(R.string.school_portal_short), portal, stacked = true) }
            item { ValueRow(stringResource(R.string.school_username), username, stacked = true) }
        } else item {
            ClickRow(
                stringResource(when (school) { "montmorency" -> R.string.school_montmorency; "custom" -> R.string.school_other_colnet; else -> R.string.school_choose_institution }),
                enabled = !busy, icon = Icons.Outlined.ArrowDropDown,
            ) { pickSchool = true }
        }
    }
    if (error != null && !busy) Notice(stringResource(error.message()), danger = true)

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
            modifier = Modifier.fillMaxWidth(),
        )
        if (school == "custom" && !changing) Text(stringResource(R.string.school_portal_help), color = p.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
        if (!changing) OutlinedTextField(
            value = username, onValueChange = { username = it },
            label = { Text(stringResource(R.string.school_username)) },
            singleLine = true, enabled = !busy,
            isError = error == SchoolError.SIGN_IN,
            keyboardOptions = KeyboardOptions(
                capitalization = KeyboardCapitalization.None, autoCorrectEnabled = false,
                keyboardType = KeyboardType.Text, imeAction = ImeAction.Next,
            ),
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
                        tint = p.onSurfaceVariant,
                    )
                }
            },
            modifier = Modifier.fillMaxWidth().semantics { contentType = ContentType.Password },
        )
    }

    Column(Modifier.padding(horizontal = 16.dp, vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(
            onClick = submit, enabled = ready,
            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
        ) {
            if (busy) {
                CircularProgressIndicator(Modifier.size(18.dp), color = LocalContentColor.current, strokeWidth = 2.dp)
                Spacer(Modifier.width(10.dp))
                Text(stringResource(R.string.school_connecting))
            } else {
                Text(stringResource(R.string.school_connect))
            }
        }
    }

    if (pickSchool) AlertDialog(
        onDismissRequest = { pickSchool = false },
        title = { Text(stringResource(R.string.school_choose_institution), color = p.onSurface) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                SchoolOption(stringResource(R.string.school_montmorency), school == "montmorency") {
                    school = "montmorency"; portal = MONTMORENCY_PORTAL; pickSchool = false
                }
                SchoolOption(stringResource(R.string.school_other_colnet), school == "custom") {
                    school = "custom"; portal = ""; pickSchool = false
                }
            }
        },
        confirmButton = { TextButton(onClick = { pickSchool = false }) { Text(stringResource(R.string.action_cancel)) } },
    )
}

@Composable
private fun ConnectionError(state: SchoolScheduleState, onSignInAgain: () -> Unit) {
    val error = state.error ?: return
    if (state.busy) return
    val context = LocalContext.current
    val message = stringResource(error.message()) +
        if (error == SchoolError.STORAGE) " " + stringResource(R.string.school_storage_recovery) else ""
    when {
        error == SchoolError.SIGN_IN ->
            Notice(message, danger = true, action = stringResource(R.string.school_sign_in_again), onAction = onSignInAgain)
        error == SchoolError.CALENDAR_PERMISSION ->
            Notice(message, danger = true, action = stringResource(R.string.school_open_android_settings)) { context.openAppSettings() }
        else -> Notice(message, danger = true)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SyncSection(state: SchoolScheduleState, onSync: () -> Unit, onDisconnect: (Boolean) -> Unit) {
    var confirmDisconnect by rememberSaveable { mutableStateOf(false) }
    val p = MaterialTheme.colorScheme
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
    SurfaceCard(padding = PaddingValues(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 8.dp)) {
        Text(
            if (isMontmorencyPortal(state.portalAddress)) stringResource(R.string.school_montmorency)
            else state.portalAddress.ifBlank { stringResource(R.string.school_connected) },
            color = p.onSurface, style = MaterialTheme.typography.titleMediumEmphasized,
            modifier = Modifier.semantics { heading() },
        )
        val status = when {
            state.busy -> stringResource(R.string.school_syncing)
            last == null -> null
            else -> stringResource(
                if (stale) R.string.school_stale else R.string.school_updated,
                DateUtils.formatDateTime(
                    context, last, DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_SHOW_TIME or DateUtils.FORMAT_ABBREV_MONTH,
                ),
            )
        }
        if (state.accountName.isNotBlank()) {
            Text(state.accountName, color = p.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
        }
        if (status != null) {
            Row(
                Modifier.semantics(mergeDescendants = true) { if (state.busy) liveRegion = LiveRegionMode.Polite },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (state.busy) {
                    CircularProgressIndicator(Modifier.size(16.dp), color = p.onSurfaceVariant, strokeWidth = 2.dp)
                    Spacer(Modifier.width(8.dp))
                }
                Text(status, color = if (stale && !state.busy) p.error else p.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
            }
        }
        FlowRow(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilledTonalButton(onClick = onSync, enabled = !state.busy, modifier = Modifier.heightIn(min = 48.dp)) {
                Text(stringResource(R.string.school_sync_now))
            }
            TextButton(onClick = { confirmDisconnect = true }, enabled = !state.busy, modifier = Modifier.heightIn(min = 48.dp)) {
                Text(stringResource(R.string.school_disconnect), color = if (state.busy) p.outline else p.error)
            }
        }
    }
    if (confirmDisconnect) {
        DisconnectDialog(state.hasImportedEvents, onDismiss = { confirmDisconnect = false }) { remove ->
            confirmDisconnect = false
            onDisconnect(remove)
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PendingCard(
    changes: SchoolChangeSummary,
    busy: Boolean,
    onApply: () -> Unit,
    onDiscard: () -> Unit,
) {
    val p = MaterialTheme.colorScheme
    val linking = changes.legacyLinks > 0
    SurfaceCard(color = MaterialTheme.colorScheme.secondaryContainer) {
        Text(
            stringResource(if (linking) R.string.school_link_title else R.string.school_review_title),
            color = p.onSurface, style = MaterialTheme.typography.titleMediumEmphasized,
            modifier = Modifier.semantics { heading(); liveRegion = LiveRegionMode.Polite },
        )
        if (linking) {
            PendingLine(pluralStringResource(R.plurals.school_pending_linked, changes.legacyLinks, changes.legacyLinks))
            Text(stringResource(R.string.school_link_managed), color = p.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
        }
        if (changes.added > 0) PendingLine(pluralStringResource(R.plurals.school_pending_added, changes.added, changes.added))
        if (changes.updated > 0) PendingLine(pluralStringResource(R.plurals.school_pending_updated, changes.updated, changes.updated))
        if (changes.removed > 0) PendingLine(pluralStringResource(R.plurals.school_pending_removed, changes.removed, changes.removed))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = onApply, enabled = !busy, modifier = Modifier.heightIn(min = 48.dp),
            ) { Text(stringResource(if (linking) R.string.school_link_apply else R.string.school_apply)) }
            TextButton(onClick = onDiscard, enabled = !busy, modifier = Modifier.heightIn(min = 48.dp)) {
                Text(stringResource(R.string.school_not_now))
            }
        }
    }
}

@Composable
private fun PendingLine(text: String) {
    Text(text, color = MaterialTheme.colorScheme.onSurface, style = MaterialTheme.typography.bodyMedium)
}


@Composable
private fun DisconnectDialog(hasImportedEvents: Boolean, onDismiss: () -> Unit, onConfirm: (Boolean) -> Unit) {
    val p = MaterialTheme.colorScheme
    var remove by rememberSaveable { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.school_disconnect_title), color = p.onSurface) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.school_disconnect_body), color = p.onSurface, style = MaterialTheme.typography.bodyMedium)
                if (hasImportedEvents) {
                    Row(
                        Modifier.fillMaxWidth().heightIn(min = 48.dp).toggleable(remove, role = Role.Checkbox) { remove = it },
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Checkbox(checked = remove, onCheckedChange = null)
                        Spacer(Modifier.width(12.dp))
                        Text(
                            stringResource(R.string.school_disconnect_remove),
                            Modifier.weight(1f), color = p.onSurface, style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                    if (remove) Text(stringResource(R.string.school_disconnect_remove_note), color = p.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(remove && hasImportedEvents) }, modifier = Modifier.heightIn(min = 48.dp)) {
                Text(
                    stringResource(if (remove && hasImportedEvents) R.string.school_disconnect_remove_confirm else R.string.school_disconnect),
                    color = if (remove && hasImportedEvents) p.error else p.primary,
                )
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.action_cancel)) }
        },
    )
}

@Composable
private fun SchoolOption(label: String, selected: Boolean, onClick: () -> Unit) {
    val p = MaterialTheme.colorScheme
    Row(
        Modifier.fillMaxWidth().heightIn(min = 48.dp).selectable(selected, role = Role.RadioButton, onClick = onClick),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = null)
        Spacer(Modifier.width(8.dp))
        Text(label, Modifier.weight(1f), color = p.onSurface, style = MaterialTheme.typography.bodyMedium)
    }
}
