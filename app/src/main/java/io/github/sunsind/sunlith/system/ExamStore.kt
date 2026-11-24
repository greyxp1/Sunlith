package io.github.sunsind.sunlith.system

import android.content.Context

/**
 * Exam marks on school classes by [io.github.sunsind.sunlith.data.examKey], each holding its note. They stay on this
 * installation because the school sync owns the class rows.
 */
class ExamStore(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences("exams", Context.MODE_PRIVATE)

    fun all(): Map<String, String> = preferences.all.mapNotNull { (key, value) -> (value as? String)?.let { key to it } }.toMap()

    fun set(key: String, note: String?) {
        preferences.edit().apply { if (note == null) remove(key) else putString(key, note) }.apply()
    }
}
