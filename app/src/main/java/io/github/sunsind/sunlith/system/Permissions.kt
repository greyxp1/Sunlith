package io.github.sunsind.sunlith.system

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat

internal fun Context.hasPermission(permission: String): Boolean =
    ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED

internal fun Context.hasCalendarAccess(): Boolean =
    hasPermission(Manifest.permission.READ_CALENDAR) && hasPermission(Manifest.permission.WRITE_CALENDAR)

internal fun Context.needsNotificationPermission(): Boolean =
    Build.VERSION.SDK_INT >= 33 && !hasPermission(Manifest.permission.POST_NOTIFICATIONS)

/** Whether the app may post notifications, independent of each feature's own setting. */
internal fun Context.notificationsAllowed(): Boolean =
    !needsNotificationPermission() && NotificationManagerCompat.from(this).areNotificationsEnabled()
