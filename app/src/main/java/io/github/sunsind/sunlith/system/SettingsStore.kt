package io.github.sunsind.sunlith.system

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import io.github.sunsind.sunlith.data.DeviceCalendar
import kotlin.properties.ReadWriteProperty
import kotlin.reflect.KProperty
import org.json.JSONObject

class SettingsStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("sunlith", Context.MODE_PRIVATE)

    private val uiPrefs = context.applicationContext.getSharedPreferences("sunlith_ui", Context.MODE_PRIVATE)

    internal var lastView by uiPrefs.pref({ getString("view_mode", null) }) { putString("view_mode", it) }
    internal var lastCalendar by uiPrefs.pref({ getLong("last_calendar", -1) }) { putLong("last_calendar", it) }
    internal var notificationStepDone by uiPrefs.bool("notify_asked", false)
    internal var aiIntroDone by uiPrefs.bool("ai_intro_done", false)
    internal var computerSetupRequested by uiPrefs.bool("computer_setup_requested", false)
    internal var computerAccessEnabled by prefs.bool("computer_access_enabled", false)

    var courseColorSlots: Map<String, Int>
        get() = runCatching {
            val saved = JSONObject(prefs.getString("course_color_slots", "{}").orEmpty())
            saved.keys().asSequence().associateWith { saved.getInt(it) }.filterValues { it in 0..9 }
        }.getOrDefault(emptyMap())
        set(value) { prefs.edit().putString("course_color_slots", JSONObject(value).toString()).apply() }

    var selectedCalendarIds: Set<Long> by prefs.pref({ getStringSet("calendars", emptySet()).orEmpty().mapNotNull(String::toLongOrNull).toSet() }) {
        putStringSet("calendars", it.map(Long::toString).toSet())
    }
    var hasChosenCalendars by prefs.bool("chosen", false)
    var remindersEnabled by prefs.bool("reminders", true)
    var themeMode by choice("theme", "system", "dark", "light")
    var widgetDays by int("widget_days", 7) { it.coerceIn(1, 31) }
    var widgetShowCalendarName by prefs.bool("widget_calendar_name", false)
    var widgetShowEmptyDays by prefs.bool("widget_empty_days", true)
    var weekTimelineZoom by int("week_timeline_zoom", 100) { it.coerceIn(50, 200) }
    /** Whether Day shows the whole month above the day rather than its week. */
    var calendarExpanded by prefs.bool("calendar_expanded", true)

    var visibleWeekDays: Int by prefs.pref({ getInt("visible_week_days", 5).takeIf { it == 3 || it == 7 } ?: 5 }) {
        require(it == 3 || it == 5 || it == 7)
        putInt("visible_week_days", it)
    }

    private fun <T> SharedPreferences.pref(read: SharedPreferences.() -> T, write: SharedPreferences.Editor.(T) -> Unit) = object : ReadWriteProperty<Any?, T> {
        override fun getValue(thisRef: Any?, property: KProperty<*>): T = read(this@pref)
        override fun setValue(thisRef: Any?, property: KProperty<*>, value: T) = this@pref.edit { write(value) }
    }

    private fun SharedPreferences.bool(key: String, fallback: Boolean) = pref({ getBoolean(key, fallback) }) { putBoolean(key, it) }

    private fun int(key: String, fallback: Int, fix: (Int) -> Int) = prefs.pref({ fix(getInt(key, fallback)) }) { putInt(key, fix(it)) }

    /** A string limited to [values], reading [fallback] for anything else. */
    private fun choice(key: String, fallback: String, vararg others: String) = (setOf(fallback) + others).let { values ->
        prefs.pref({ getString(key, fallback)?.takeIf { it in values } ?: fallback }) { require(it in values); putString(key, it) }
    }
}

/** Calendars the app shows: the chosen ones that still exist, or before any choice the calendars the provider marks visible. */
internal fun SettingsStore.visibleCalendarIds(calendars: List<DeviceCalendar>): Set<Long> =
    if (hasChosenCalendars) selectedCalendarIds.intersect(calendars.mapTo(mutableSetOf()) { it.id })
    else calendars.filter { it.visible }.mapTo(mutableSetOf()) { it.id }
