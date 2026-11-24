package io.github.sunsind.sunlith.device

import android.content.Context
import android.content.Intent
import android.os.Looper
import androidx.annotation.StringRes
import androidx.core.content.ContextCompat
import io.github.sunsind.sunlith.R
import io.github.sunsind.sunlith.data.CalendarRepository
import io.github.sunsind.sunlith.system.SettingsStore
import java.io.File
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import io.github.sunsind.sunlith.system.hasCalendarAccess
import io.github.sunsind.sunlith.system.needsNotificationPermission

data class DeviceLinkState(val running: Boolean = false, @param:StringRes val error: Int? = null)

enum class DeviceLinkAddressKind { TAILSCALE, VPN, LOCAL_NETWORK }
data class DeviceLinkAddress(val host: String, val kind: DeviceLinkAddressKind)

object DeviceLink {
    const val PORT = 39393
    private val mutableState = MutableStateFlow(DeviceLinkState())
    val state: StateFlow<DeviceLinkState> = mutableState.asStateFlow()
    private val mutableActivity = MutableStateFlow(0L)

    internal val activity: StateFlow<Long> = mutableActivity.asStateFlow()

    internal fun lastRequest(context: Context): Long? = DeviceLinkStore.lastSuccess(context.applicationContext)

    fun start(context: Context) {
        val app = context.applicationContext
        missingPermission(app)?.let { update(DeviceLinkState(false, it)); return }
        try {
            ContextCompat.startForegroundService(app, Intent(app, DeviceLinkService::class.java))
        } catch (_: Exception) {
            update(DeviceLinkState(false, R.string.device_link_start_error))
        }
    }

    fun stop(context: Context) {
        val app = context.applicationContext
        SettingsStore(app).computerAccessEnabled = false
        try {
            app.startService(Intent(app, DeviceLinkService::class.java).setAction(DeviceLinkService.ACTION_STOP))
        } catch (_: IllegalStateException) {
            app.stopService(Intent(app, DeviceLinkService::class.java))
        }
        update(DeviceLinkState(false, null))
    }

    internal fun restoreAfterUpdate(context: Context) {
        if (SettingsStore(context).computerAccessEnabled && DeviceLinkStore.pairing(context) != null) start(context)
    }

    fun addresses(context: Context): List<String> = DeviceLinkNetwork.addresses(context.applicationContext)

    fun addressChoices(context: Context): List<DeviceLinkAddress> = DeviceLinkNetwork.addressChoices(context.applicationContext)

    @Synchronized
    fun createPairing(context: Context, calendarIds: Set<Long>, host: String): String {
        val app = context.applicationContext
        check(Looper.myLooper() != Looper.getMainLooper()) { "Pairing must run off the main thread." }
        require(calendarIds.isNotEmpty()) { "Choose at least one calendar." }
        require(host == LOOPBACK || host in addresses(app)) { "Choose a current private address of this phone." }
        val existing = CalendarRepository(app).calendars().mapTo(mutableSetOf()) { it.id }
        require(calendarIds.all { it in existing }) { "One of the selected calendars is no longer available." }
        val fingerprint = DeviceLinkTls.fingerprint()
        clearPairingShares(app)
        val token = DeviceLinkStore.pair(app, calendarIds)
        activityChanged()
        return String(DeviceLinkJson.write(linkedMapOf(
            "version" to 1,
            "baseUrl" to "https://$host:$PORT",
            "certificateSha256" to fingerprint,
            "token" to token,
        )), Charsets.UTF_8)
    }

    @Synchronized
    fun revoke(context: Context) {
        check(Looper.myLooper() != Looper.getMainLooper()) { "Revoking must run off the main thread." }
        DeviceLinkStore.clear(context.applicationContext)
        clearPairingShares(context)
        activityChanged()
        stop(context)
    }

    internal fun pairedCalendarIds(context: Context): Set<Long>? = DeviceLinkStore.pairing(context.applicationContext)?.calendarIds

    @Synchronized
    fun cachedPairingShare(context: Context): File? = File(context.cacheDir, "pairing-share").listFiles()
        ?.firstOrNull { file ->
            if (!file.isFile || !file.name.endsWith(".json")) false
            else runCatching {
                val token = org.json.JSONObject(file.readText()).getString("token")
                DeviceLinkStore.authorize(context, token) != null
            }.getOrDefault(false)
        }

    private fun clearPairingShares(context: Context) {
        File(context.cacheDir, "pairing-share").listFiles()?.filter { it.isFile }?.forEach { it.delete() }
    }

    internal fun clearError(@StringRes error: Int) {
        mutableState.update { if (it.error == error) it.copy(error = null) else it }
    }

    internal fun update(value: DeviceLinkState) { mutableState.value = value }

    internal fun activityChanged() = mutableActivity.update { it + 1 }

    @StringRes
    internal fun missingPermission(context: Context): Int? {
        if (!context.hasCalendarAccess()) return R.string.device_link_calendar_required
        if (context.needsNotificationPermission()) return R.string.device_link_notification_required
        return null
    }

    private const val LOOPBACK = "127.0.0.1"
}
