package io.github.sunsind.sunlith.system

import android.Manifest
import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.CalendarContract
import android.text.format.DateFormat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import io.github.sunsind.sunlith.MainActivity
import io.github.sunsind.sunlith.R
import io.github.sunsind.sunlith.data.CalendarEvent
import io.github.sunsind.sunlith.data.CalendarDates
import io.github.sunsind.sunlith.data.CalendarRepository
import java.text.SimpleDateFormat
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.Duration
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/** Whether the app may post notifications at all, independent of each feature's own setting. */
internal fun Context.notificationsAllowed(): Boolean = NotificationManagerCompat.from(this).areNotificationsEnabled() &&
    (Build.VERSION.SDK_INT < 33 || ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED)

/** Identifies one reminder of one occurrence, as the notification tag and in the shown and snoozed histories. */
private fun reminderKey(eventId: Long, begin: Long, minutes: Int) = "$eventId:$begin:$minutes"
private fun Intent.reminderKey() = reminderKey(getLongExtra("event_id", -1), getLongExtra("event_begin", -1), getIntExtra("minutes", 0))

object ReminderNotifications {
    const val CHANNEL = "event_reminders"
    private const val DAY_MILLIS = 86_400_000L
    fun allowed(context: Context): Boolean = SettingsStore(context).remindersEnabled && context.notificationsAllowed()

    fun post(context: Context, event: CalendarEvent, minutes: Int, repeat: Boolean = false): Boolean {
        if (!allowed(context)) return false
        val key = reminderKey(event.id, event.startMillis, minutes)
        if (!repeat && wasShown(context, key)) return false
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(CHANNEL, context.getString(R.string.notification_channel), NotificationManager.IMPORTANCE_HIGH).apply { description = context.getString(R.string.notification_channel_description) })
        val id = key.hashCode()
        val open = PendingIntent.getActivity(context, id, Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            .setData(Uri.parse("sunlith://event/${event.id}/${event.startMillis}/$minutes"))
            .putExtra("event_id", event.id).putExtra("event_begin", event.startMillis), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val snooze = PendingIntent.getBroadcast(context, id, eventIntent(context, "SNOOZE", event, minutes), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val dismiss = PendingIntent.getBroadcast(context, id, eventIntent(context, "DISMISS", event, minutes), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val time = if (event.allDay) context.getString(R.string.all_day) else context.getString(
            R.string.notification_time_range,
            formatLocalTime(context, event.startMillis),
            formatLocalTime(context, event.endMillis),
        )
        val details = if (event.location.isBlank()) time else context.getString(R.string.notification_location_separator, time, event.location)
        val timing = formatTiming(context, event, System.currentTimeMillis())
        val text = context.getString(R.string.notification_content, timing, details)
        val notification = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_notification).setContentTitle(event.title).setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setCategory(NotificationCompat.CATEGORY_EVENT).setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(open).setAutoCancel(true).setOnlyAlertOnce(false)
            .addAction(0, context.getString(R.string.notification_snooze), snooze).addAction(0, context.getString(R.string.notification_dismiss), dismiss).build()
        try { manager.notify(key, 0, notification) } catch (_: SecurityException) { return false }
        if (!repeat) recordShown(context, key)
        return true
    }

    internal fun wasShown(context: Context, key: String): Boolean = key in shown(context)

    private fun recordShown(context: Context, key: String) {
        val updated = (shown(context).filter { it != key } + key).takeLast(500)
        context.getSharedPreferences("notification_history", Context.MODE_PRIVATE).edit().putString("shown_ordered", updated.joinToString("\n")).apply()
    }

    private fun shown(context: Context) = context.getSharedPreferences("notification_history", Context.MODE_PRIVATE)
        .getString("shown_ordered", null)?.lineSequence()?.filter(String::isNotBlank)?.toList().orEmpty()

    internal fun eventIntent(context: Context, action: String, event: CalendarEvent, minutes: Int): Intent =
        Intent(context, NotificationActionReceiver::class.java).setAction("${context.packageName}.$action")
            .setData(Uri.parse("sunlith://reminder/${event.id}/${event.startMillis}/$minutes/$action"))
            .putExtra("event_id", event.id).putExtra("event_begin", event.startMillis).putExtra("minutes", minutes)

    internal fun postProviderAlerts(context: Context) {
        if (!allowed(context)) return
        val now = System.currentTimeMillis()
        val settings = SettingsStore(context)
        val repo = CalendarRepository(context)
        val ids = settings.visibleCalendarIds(repo.calendars())
        if (ids.isEmpty()) return
        val alerts = context.contentResolver.query(CalendarContract.CalendarAlerts.CONTENT_URI,
            arrayOf(CalendarContract.CalendarAlerts.EVENT_ID, CalendarContract.CalendarAlerts.BEGIN, CalendarContract.CalendarAlerts.MINUTES),
            "${CalendarContract.CalendarAlerts.ALARM_TIME}<=? AND ${CalendarContract.CalendarAlerts.ALARM_TIME}>=? AND ${CalendarContract.CalendarAlerts.STATE}!=?",
            arrayOf(now.toString(), (now - 300_000L).toString(), CalendarContract.CalendarAlerts.STATE_DISMISSED.toString()), null)?.use { cursor ->
            buildList { while (cursor.moveToNext()) add(Triple(cursor.getLong(0), cursor.getLong(1), cursor.getInt(2))) }
        }.orEmpty()
        // One calendar read per month of occurrences, so many due reminders never mean one read each.
        alerts.groupBy { it.second / (30 * DAY_MILLIS) }.values.forEach { group ->
            val events = repo.events(group.minOf { it.second } - DAY_MILLIS, group.maxOf { it.second } + DAY_MILLIS, ids)
                .associateBy { it.id to it.startMillis }
            group.forEach { (id, begin, minutes) -> events[id to begin]?.let { post(context, it, minutes) } }
        }
    }
}

internal enum class ReminderTimingState { FUTURE, STARTING, ONGOING, PAST }

internal data class ReminderTiming(val state: ReminderTimingState, val amount: Duration, val startMillis: Long)

internal fun reminderTiming(event: CalendarEvent, nowMillis: Long, zone: ZoneId): ReminderTiming {
    val start = if (event.allDay) CalendarDates.allDayDate(event.startMillis).atStartOfDay(zone).toInstant().toEpochMilli() else event.startMillis
    val end = if (event.allDay) CalendarDates.allDayDate(event.endMillis).atStartOfDay(zone).toInstant().toEpochMilli() else event.endMillis
    return when {
        nowMillis < start -> ReminderTiming(ReminderTimingState.FUTURE, Duration.ofMillis(start - nowMillis), start)
        nowMillis == start || (nowMillis - start < 60_000L && nowMillis < end) -> ReminderTiming(ReminderTimingState.STARTING, Duration.ZERO, start)
        nowMillis < end && end > start -> ReminderTiming(ReminderTimingState.ONGOING, Duration.ofMillis(nowMillis - start), start)
        else -> ReminderTiming(ReminderTimingState.PAST, Duration.ofMillis(nowMillis - start), start)
    }
}

private fun formatTiming(context: Context, event: CalendarEvent, nowMillis: Long): String {
    val zone = ZoneId.systemDefault()
    val timing = reminderTiming(event, nowMillis, zone)
    val start = formatStart(context, event, zone)
    val starts = context.getString(R.string.notification_starts_at, start)
    val started = context.getString(R.string.notification_started_at, start)
    return when (timing.state) {
        ReminderTimingState.FUTURE -> context.getString(R.string.notification_timing_future, futureDistance(context, timing.amount), starts)
        ReminderTimingState.STARTING -> context.getString(R.string.notification_timing_now, starts)
        ReminderTimingState.ONGOING -> context.getString(R.string.notification_timing_ongoing, started)
        ReminderTimingState.PAST -> context.getString(R.string.notification_timing_past, pastDistance(context, timing.amount), start)
    }
}

private fun futureDistance(context: Context, duration: Duration): String = distance(context, duration, R.string.notification_in_less_than_minute,
    R.plurals.notification_in_minutes, R.plurals.notification_in_hours, R.plurals.notification_in_days)

private fun pastDistance(context: Context, duration: Duration): String = distance(context, duration, R.string.notification_less_than_minute_ago,
    R.plurals.notification_minutes_ago, R.plurals.notification_hours_ago, R.plurals.notification_days_ago)

internal enum class ReminderDistanceUnit { LESS_THAN_MINUTE, MINUTES, HOURS, DAYS }

internal data class ReminderDistance(val unit: ReminderDistanceUnit, val count: Long)

internal fun reminderDistance(duration: Duration): ReminderDistance {
    val millis = duration.toMillis().coerceAtLeast(0)
    if (millis < 60_000L) return ReminderDistance(ReminderDistanceUnit.LESS_THAN_MINUTE, 0)
    fun rounded(unit: Long) = millis / unit + if (millis % unit >= unit / 2) 1 else 0
    val minutes = rounded(60_000L)
    if (minutes < 60) return ReminderDistance(ReminderDistanceUnit.MINUTES, minutes)
    val hours = rounded(3_600_000L)
    if (hours < 24) return ReminderDistance(ReminderDistanceUnit.HOURS, hours)
    return ReminderDistance(ReminderDistanceUnit.DAYS, rounded(86_400_000L))
}

private fun distance(context: Context, duration: Duration, lessThanMinute: Int, minutesId: Int, hoursId: Int, daysId: Int): String {
    val distance = reminderDistance(duration)
    val resource = when (distance.unit) {
        ReminderDistanceUnit.LESS_THAN_MINUTE -> return context.getString(lessThanMinute)
        ReminderDistanceUnit.MINUTES -> minutesId
        ReminderDistanceUnit.HOURS -> hoursId
        ReminderDistanceUnit.DAYS -> daysId
    }
    return context.resources.getQuantityString(resource, distance.count.coerceAtMost(Int.MAX_VALUE.toLong()).toInt(), distance.count)
}

private fun formatLocalTime(context: Context, millis: Long): String {
    val locale = context.resources.configuration.locales[0] ?: Locale.getDefault()
    val pattern = DateFormat.getBestDateTimePattern(locale, if (DateFormat.is24HourFormat(context)) "Hm" else "hm")
    return SimpleDateFormat(pattern, locale).apply { timeZone = TimeZone.getDefault() }.format(Date(millis))
}

private fun formatStart(context: Context, event: CalendarEvent, zone: ZoneId): String {
    val locale = context.resources.configuration.locales[0] ?: Locale.getDefault()
    val pattern = DateFormat.getBestDateTimePattern(locale, "yMMMd")
    val instant = if (event.allDay) {
        CalendarDates.allDayDate(event.startMillis).atStartOfDay(zone).toInstant()
    } else Instant.ofEpochMilli(event.startMillis)
    return SimpleDateFormat(pattern, locale).apply { timeZone = TimeZone.getDefault() }.format(Date.from(instant))
}

object ReminderScheduler {
    private const val REQUEST_CODE = 4032
    fun reschedule(context: Context) {
        val alarm = context.getSystemService(AlarmManager::class.java)
        alarm.cancel(nextIntent(context))
        if (!ReminderNotifications.allowed(context) || ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CALENDAR) != PackageManager.PERMISSION_GRANTED) {
            SnoozedReminders.clear(context)
            return
        }
        SnoozedReminders.restore(context)
        val repo = CalendarRepository(context)
        val settings = SettingsStore(context)
        val ids = settings.visibleCalendarIds(repo.calendars())
        if (ids.isEmpty()) return
        val zone = ZoneId.systemDefault()
        val now = System.currentTimeMillis()
        val events = repo.events(LocalDate.now().minusDays(1).atStartOfDay(zone).toInstant().toEpochMilli(), LocalDate.now().plusDays(366).atStartOfDay(zone).toInstant().toEpochMilli(), ids)
        val reminders = repo.reminderMinutes(events.map { it.id })
        var next: Triple<CalendarEvent, Int, Long>? = null
        events.forEach { event -> reminders[event.id].orEmpty().forEach { minutes ->
            val at = effectiveStart(event) - minutes.toLong() * 60_000L
            val key = reminderKey(event.id, event.startMillis, minutes)
            if (at in (now - 120_000L)..now && !ReminderNotifications.wasShown(context, key)) ReminderNotifications.post(context, event, minutes)
            if (at > now && !ReminderNotifications.wasShown(context, key) && (next == null || at < next!!.third)) next = Triple(event, minutes, at)
        } }
        next?.let { (event, minutes, at) ->
            val intent = nextIntent(context, ReminderNotifications.eventIntent(context, "REMIND", event, minutes))
            scheduleAlarm(alarm, at, intent)
        }
    }

    internal fun scheduleAlarm(manager: AlarmManager, at: Long, intent: PendingIntent) {
        try {
            if (Build.VERSION.SDK_INT < 31 || manager.canScheduleExactAlarms()) manager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, intent)
            else manager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, intent)
        } catch (_: SecurityException) { manager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, intent) }
    }

    internal fun resolve(context: Context, intent: Intent): CalendarEvent? {
        val id = intent.getLongExtra("event_id", -1)
        val begin = intent.getLongExtra("event_begin", -1)
        if (id <= 0 || begin <= 0) return null
        val repo = CalendarRepository(context)
        val draft = repo.getEvent(id) ?: return null
        val settings = SettingsStore(context)
        if (settings.hasChosenCalendars && draft.calendarId !in settings.selectedCalendarIds) return null
        return repo.events(begin - 86_400_000L, begin + 86_400_000L, setOf(draft.calendarId)).firstOrNull { it.id == id && it.startMillis == begin }
    }

    private fun effectiveStart(event: CalendarEvent): Long = if (event.allDay)
        Instant.ofEpochMilli(event.startMillis).atZone(ZoneOffset.UTC).toLocalDate().atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
    else event.startMillis

    private fun nextIntent(context: Context, data: Intent? = null): PendingIntent {
        val intent = Intent(context, NotificationActionReceiver::class.java).setAction("${context.packageName}.NEXT_REMINDER")
        data?.extras?.let(intent::putExtras)
        return PendingIntent.getBroadcast(context, REQUEST_CODE, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }
}

private object SnoozedReminders {
    private fun preferences(context: Context) = context.getSharedPreferences("snoozed_reminders", Context.MODE_PRIVATE)
    private fun pending(context: Context, intent: Intent): PendingIntent = PendingIntent.getBroadcast(context, intent.reminderKey().hashCode(),
        Intent(intent).setAction("${context.packageName}.SNOOZED").setData(Uri.parse("sunlith://snooze/${intent.reminderKey()}")),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

    fun schedule(context: Context, event: CalendarEvent, minutes: Int, at: Long) {
        val intent = ReminderNotifications.eventIntent(context, "SNOOZED", event, minutes)
        preferences(context).edit().putLong(intent.reminderKey(), at).apply()
        ReminderScheduler.scheduleAlarm(context.getSystemService(AlarmManager::class.java), at, pending(context, intent))
    }

    fun fire(context: Context, intent: Intent) {
        val prefs = preferences(context)
        if (!prefs.contains(intent.reminderKey())) return
        prefs.edit().remove(intent.reminderKey()).apply()
        ReminderScheduler.resolve(context, intent)?.let { ReminderNotifications.post(context, it, intent.getIntExtra("minutes", 0), repeat = true) }
    }

    fun cancel(context: Context, intent: Intent) {
        preferences(context).edit().remove(intent.reminderKey()).apply()
        context.getSystemService(AlarmManager::class.java).cancel(pending(context, intent))
    }

    fun restore(context: Context) {
        val prefs = preferences(context)
        val now = System.currentTimeMillis()
        prefs.all.forEach { (key, value) ->
            val intent = intent(context, key)
            val at = value as? Long
            if (intent == null || at == null) { prefs.edit().remove(key).apply(); return@forEach }
            if (at < now - 86_400_000L || runCatching { ReminderScheduler.resolve(context, intent) }.getOrNull() == null) cancel(context, intent)
            else if (at <= now) fire(context, intent)
            else ReminderScheduler.scheduleAlarm(context.getSystemService(AlarmManager::class.java), at, pending(context, intent))
        }
    }

    fun clear(context: Context) {
        preferences(context).all.keys.forEach { key -> intent(context, key)?.let { cancel(context, it) } }
        preferences(context).edit().clear().apply()
    }

    /** Rebuilds the snoozed reminder's broadcast from its [reminderKey]. */
    private fun intent(context: Context, key: String): Intent? {
        val parts = key.split(':').takeIf { it.size == 3 } ?: return null
        return Intent(context, NotificationActionReceiver::class.java)
            .putExtra("event_id", parts[0].toLongOrNull() ?: -1).putExtra("event_begin", parts[1].toLongOrNull() ?: -1)
            .putExtra("minutes", parts[2].toIntOrNull() ?: 0)
    }
}

class NotificationActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val pending = goAsync()
        AppRefresh.executor.execute {
            try {
                val action = intent.action?.substringAfterLast('.')
                if (action == "DISMISS") {
                    val tag = intent.reminderKey()
                    context.getSystemService(NotificationManager::class.java).cancel(tag, 0)
                    SnoozedReminders.cancel(context, intent)
                    return@execute
                }
                if (action == "SNOOZED") {
                    SnoozedReminders.fire(context, intent)
                    return@execute
                }
                val event = runCatching { ReminderScheduler.resolve(context, intent) }.getOrNull()
                if (event != null) {
                    val minutes = intent.getIntExtra("minutes", 0)
                    val tag = reminderKey(event.id, event.startMillis, minutes)
                    when (action) {
                        "SNOOZE" -> {
                            context.getSystemService(NotificationManager::class.java).cancel(tag, 0)
                            SnoozedReminders.schedule(context, event, minutes, System.currentTimeMillis() + 600_000L)
                        }
                        "NEXT_REMINDER" -> ReminderNotifications.post(context, event, minutes)
                    }
                }
                runCatching { ReminderScheduler.reschedule(context) }
            } catch (_: SecurityException) {
                context.getSystemService(NotificationManager::class.java).cancelAll()
            } finally { pending.finish() }
        }
    }
}

class CalendarReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (!ReminderNotifications.allowed(context)) return
        val pending = goAsync()
        AppRefresh.executor.execute {
            try {
                runCatching { ReminderNotifications.postProviderAlerts(context) }
                runCatching { ReminderScheduler.reschedule(context) }
            } finally { pending.finish() }
        }
    }
}
