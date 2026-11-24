package io.github.sunsind.sunlith.system

import android.Manifest
import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.CalendarContract
import android.text.format.DateUtils
import androidx.core.app.NotificationCompat
import io.github.sunsind.sunlith.MainActivity
import io.github.sunsind.sunlith.R
import io.github.sunsind.sunlith.data.CalendarEvent
import io.github.sunsind.sunlith.data.CalendarRepository
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset

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
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
            .setData(Uri.parse("sunlith://event/${event.id}/${event.startMillis}/$minutes"))
            .putExtra("event_id", event.id).putExtra("event_begin", event.startMillis), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val snooze = PendingIntent.getBroadcast(context, id, eventIntent(context, "SNOOZE", event, minutes), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val dismiss = PendingIntent.getBroadcast(context, id, eventIntent(context, "DISMISS", event, minutes), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val start = ReminderScheduler.effectiveStart(event)
        val today = Instant.ofEpochMilli(start).atZone(ZoneId.systemDefault()).toLocalDate() == LocalDate.now()
        val date = if (today) null else DateUtils.formatDateTime(context, start, DateUtils.FORMAT_SHOW_DATE)
        val time = when {
            !event.allDay -> DateUtils.formatDateRange(context, event.startMillis, event.endMillis,
                DateUtils.FORMAT_SHOW_TIME or if (today) 0 else DateUtils.FORMAT_SHOW_DATE)
            today -> context.getString(R.string.all_day)
            else -> context.getString(R.string.notification_location_separator, date, context.getString(R.string.all_day))
        }
        val text = if (event.location.isBlank()) time else context.getString(R.string.notification_location_separator, time, event.location)
        val notification = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_notification).setContentTitle(event.title).setContentText(text)
            // The header counts down to the start, then shows how long ago it began, so it stays right while it waits.
            .setWhen(start).setShowWhen(true)
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

object ReminderScheduler {
    private const val REQUEST_CODE = 4032
    fun reschedule(context: Context) {
        val alarm = context.getSystemService(AlarmManager::class.java)
        alarm.cancel(nextIntent(context))
        if (!ReminderNotifications.allowed(context) || !context.hasPermission(Manifest.permission.READ_CALENDAR)) {
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
            if (at in (now - 120_000L)..now) ReminderNotifications.post(context, event, minutes)
            if (at > now && (next == null || at < next!!.third) && !ReminderNotifications.wasShown(context, key)) next = Triple(event, minutes, at)
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

    internal fun effectiveStart(event: CalendarEvent): Long = if (event.allDay)
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
        val id = parts[0].toLongOrNull() ?: return null
        val begin = parts[1].toLongOrNull() ?: return null
        val minutes = parts[2].toIntOrNull() ?: return null
        return Intent(context, NotificationActionReceiver::class.java)
            .putExtra("event_id", id).putExtra("event_begin", begin).putExtra("minutes", minutes)
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
