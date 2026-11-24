package io.github.sunsind.sunlith.system

import android.content.Context
import android.content.SharedPreferences
import kotlin.properties.ReadWriteProperty
import kotlin.reflect.KProperty
import org.json.JSONObject

class SettingsStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("sunlith", Context.MODE_PRIVATE)

    var courseColorSlots: Map<String, Int>
        get() = runCatching {
            val saved = JSONObject(prefs.getString("course_color_slots", "{}").orEmpty())
            saved.keys().asSequence().associateWith { saved.getInt(it) }.filterValues { it in 0..9 }
        }.getOrDefault(emptyMap())
        set(value) { prefs.edit().putString("course_color_slots", JSONObject(value).toString()).apply() }

    init {
        if (!prefs.getBoolean("list_defaults_v2", false)) {
            val migration = prefs.edit().putBoolean("list_defaults_v2", true)
            // Restore the preset default while retaining explicitly customized layouts.
            if (prefs.getString("layout_preset", "sunlith") in setOf("sunlith", "planner") &&
                prefs.getString("day_layout", "cards") == "timeline") migration.putString("day_layout", "cards")
            migration.apply()
        }
        if (prefs.contains(LEGACY_WORKWEEK)) {
            // v0.6.8 stored the opposite toggle. Only an explicit "off" meant the user chose rolling days.
            val legacy = prefs.getBoolean(LEGACY_WORKWEEK, false)
            val edit = prefs.edit().remove(LEGACY_WORKWEEK)
            if (!prefs.contains(ROLLING_FIVE_DAYS)) edit.putBoolean(ROLLING_FIVE_DAYS, !legacy)
            edit.apply()
        }
    }

    var selectedCalendarIds: Set<Long> by pref({ getStringSet("calendars", emptySet()).orEmpty().mapNotNull(String::toLongOrNull).toSet() }) {
        putStringSet("calendars", it.map(Long::toString).toSet())
    }
    var hasChosenCalendars by bool("chosen", false)
    var remindersEnabled by bool("reminders", true)
    var themeMode by choice("theme", "system", "dark", "light")
    var weekStartsMonday by bool("monday", true)
    var widgetOpacity by int("widget_opacity", 100) { ((it.coerceIn(0, 100) + 2) / 5) * 5 }
    var widgetDays by int("widget_days", 7) { it.coerceIn(1, 31) }
    var widgetShowCalendarName by bool("widget_calendar_name", false)
    var widgetShowEmptyDays by bool("widget_empty_days", true)
    var agendaDisplay by choice("agenda_display", "all", "compact", "events")
    var collapseCalendarOnScroll by bool("collapse_calendar_on_scroll", false)
    var startView by choice("start_view", "Month", "Week", "Day", "last")
    var navigationAnimation by bool("navigation_animation", true)
    var materialYou by bool("material_you", true)
    var showNavigationArrows by bool("navigation_arrows", false)
    var swipeNavigation by bool("swipe_navigation", true)
    var keepSelectedDate by bool("keep_selected_date", false)
    var agendaRange by choice("agenda_range", "continuous", "month", "week", "day")
    var monthLayout by choice("month_layout", "split", "full")
    var weekLayout by choice("week_layout", "list", "timeline", "continuous")
    var groupEmptyHours by bool("group_empty_hours", false)
    var dayLayout by choice("day_layout", "cards", "timeline", "schedule")
    var weekTimelineZoom by int("week_timeline_zoom", 100) { it.coerceIn(50, 200) }

    /** The 5-day timeline shows Monday to Friday unless the user opts into five days starting at the selected date. */
    var rollingFiveDays by bool(ROLLING_FIVE_DAYS, false)

    /** Tapping a date above a Week timeline selects it only when the user opts in. */
    var weekHeaderSelection by bool("week_header_selection", false)

    var visibleWeekDays: Int by pref({ getInt("visible_week_days", 5).takeIf { it in setOf(3, 5, 7) } ?: 5 }) {
        require(it in setOf(3, 5, 7)); putInt("visible_week_days", it)
    }

    private fun <T> pref(read: SharedPreferences.() -> T, write: SharedPreferences.Editor.(T) -> Unit) = object : ReadWriteProperty<Any?, T> {
        override fun getValue(thisRef: Any?, property: KProperty<*>): T = prefs.read()
        override fun setValue(thisRef: Any?, property: KProperty<*>, value: T) = prefs.edit().apply { write(value) }.apply()
    }

    private fun bool(key: String, fallback: Boolean) = pref({ getBoolean(key, fallback) }) { putBoolean(key, it) }

    private fun int(key: String, fallback: Int, fix: (Int) -> Int) = pref({ fix(getInt(key, fallback)) }) { putInt(key, fix(it)) }

    /** A string limited to [values], reading [fallback] for anything else. */
    private fun choice(key: String, fallback: String, vararg others: String) = (setOf(fallback) + others).let { values ->
        pref({ getString(key, fallback)?.takeIf { it in values } ?: fallback }) { require(it in values); putString(key, it) }
    }

    companion object {
        const val DEFAULT_WEEK_LAYOUT = "continuous"
        const val DEFAULT_VISIBLE_DAYS = 5
        const val DEFAULT_TIMELINE_TINT = 40
        val WEEK_LAYOUTS = setOf("list", "continuous")
        val VISIBLE_DAY_CHOICES = listOf(3, 5, 7)
        private const val WEEK_LAYOUT = "week_layout"
        private const val VISIBLE_DAYS = "visible_week_days"
        private const val TIMELINE_TINT = "timeline_tint"
        private const val CONTINUOUS_WEEK_V3 = "continuous_week_v3"
    }
}
