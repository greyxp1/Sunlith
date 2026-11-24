package io.github.sunsind.sunlith.system

import android.content.Context
import androidx.core.content.edit

/**
 * Exam marks on school classes by [io.github.sunsind.sunlith.data.examKey] and personal notes by
 * [io.github.sunsind.sunlith.data.noteKey]. They stay on this installation for events Sunlith does not write: the
 * school sync owns class rows, and a repeating series shares one description.
 */
class LocalEventStore(context: Context) {
    private val exams = context.applicationContext.getSharedPreferences("exams", Context.MODE_PRIVATE)
    private val notes = context.applicationContext.getSharedPreferences("notes", Context.MODE_PRIVATE)

    init {
        val legacy = exams.all.mapNotNull { (key, value) -> (value as? String)?.let { key to it } }
        if (legacy.isNotEmpty()) {
            notes.edit { legacy.forEach { (key, text) -> if (text.isNotBlank() && !notes.contains(key)) putString(key, text) } }
            exams.edit { legacy.forEach { (key, _) -> putBoolean(key, true) } }
        }
    }

    fun exams(): Set<String> = exams.all.keys.toSet()

    fun notes(): Map<String, String> = notes.all.mapNotNull { (key, value) -> (value as? String)?.let { key to it } }.toMap()

    fun setExam(key: String, marked: Boolean) {
        exams.edit { if (marked) putBoolean(key, true) else remove(key) }
    }

    fun setNote(key: String, note: String) {
        notes.edit { if (note.isBlank()) remove(key) else putString(key, note) }
    }
}
