package io.github.sunsind.sunlith.ui.settings

import android.Manifest
import android.content.Context
import android.content.Intent
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.sunsind.sunlith.R
import io.github.sunsind.sunlith.device.DeviceLink
import io.github.sunsind.sunlith.device.DeviceLinkService
import io.github.sunsind.sunlith.school.sync.SchoolNotifications
import io.github.sunsind.sunlith.school.sync.SchoolScheduleManager
import io.github.sunsind.sunlith.system.ReminderNotifications
import io.github.sunsind.sunlith.system.needsNotificationPermission
import io.github.sunsind.sunlith.ui.ListGroup
import io.github.sunsind.sunlith.ui.Notice
import io.github.sunsind.sunlith.ui.PageScaffold
import io.github.sunsind.sunlith.ui.SunlithViewModel
import io.github.sunsind.sunlith.ui.ToggleRow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Sunlith's own notification switches. They keep their saved state when Android blocks the app, so allowing it again
 * restores them. Android's page lists a channel only after it exists, so opening it creates every channel in use first.
 */
@Composable
fun NotificationsScreen(vm: SunlithViewModel) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val school = remember(context) { SchoolScheduleManager.get(context) }
    val schoolState by school.state.collectAsStateWithLifecycle()
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { vm.refreshNotificationAccess() }
    fun turnOn() {
        if (context.needsNotificationPermission()) permission.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    val scroll = rememberScrollState()
    PageScaffold(stringResource(R.string.settings_notifications), canScroll = { scroll.canScrollForward || scroll.canScrollBackward }) { pad ->
        Column(Modifier.fillMaxSize().padding(pad).consumeWindowInsets(pad).verticalScroll(scroll).padding(bottom = 32.dp)) {
            if (!vm.notificationsAllowed) Notice(stringResource(R.string.notifications_blocked), action = stringResource(R.string.notifications_android_settings)) {
                scope.launch {
                    val connected = school.isConnected()
                    val paired = withContext(Dispatchers.IO) { DeviceLink.pairedCalendarIds(context) != null }
                    context.openNotificationSettings(connected, paired)
                }
            }
            ListGroup {
                item {
                    ToggleRow(stringResource(R.string.settings_reminders_toggle), null, vm.remindersEnabled) { on ->
                        vm.remindersEnabled = on
                        if (on) turnOn()
                    }
                }
                if (schoolState.connected) item {
                    ToggleRow(stringResource(R.string.notifications_school), stringResource(R.string.notifications_school_sub), schoolState.notificationsEnabled) { on ->
                        scope.launch { runCatching { school.setNotificationsEnabled(on) } }
                        if (on) turnOn()
                    }
                }
            }
        }
    }
}

private fun Context.openNotificationSettings(school: Boolean, deviceLink: Boolean) {
    ReminderNotifications.registerChannel(this)
    if (school) SchoolNotifications(this).registerChannels()
    if (deviceLink) DeviceLinkService.registerChannel(this)
    runCatching { startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, packageName)) }
        .onFailure { openAppSettings() }
}
