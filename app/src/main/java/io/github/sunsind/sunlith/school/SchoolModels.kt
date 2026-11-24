package io.github.sunsind.sunlith.school

import java.time.ZoneId

enum class SchoolAlert {
    TEACHER_ABSENCES, CLASS_TIMES, ROOMS, TEACHERS, ADDED_CLASSES, REMOVED_CLASSES,
    ASSESSMENTS, SCHOOL_DAYS, CONNECTION_PROBLEMS, ADMINISTRATION_MESSAGES,
}

data class SchoolNotificationPreferences(
    val enabled: Boolean = false,
    val categories: Set<SchoolAlert> = SchoolAlert.entries.toSet(),
)

enum class SchoolError {
    INVALID_PORTAL, SIGN_IN, MFA_REQUIRED, NETWORK, CALENDAR_PERMISSION,
    INVALID_DATA, UNAVAILABLE, STORAGE, LEGACY_COLLISION, CALENDAR_CHANGED, CALENDAR_LIMIT,
}

data class SchoolChangeSummary(val added: Int, val updated: Int, val removed: Int, val linked: Int)

data class SchoolCalendarChoice(val id: Long, val name: String)

data class SchoolScheduleState(
    val connected: Boolean = false,
    val busy: Boolean = false,
    val portalAddress: String = "",
    val accountName: String = "",
    val termName: String = "",
    val timeZone: String = ZoneId.systemDefault().id,
    val lastSuccessMillis: Long? = null,
    val error: SchoolError? = null,
    val calendarId: Long? = null,
    val availableCalendars: List<SchoolCalendarChoice> = emptyList(),
    val calendarsLoaded: Boolean = false,
    val eventCount: Int = 0,
    val notifications: SchoolNotificationPreferences = SchoolNotificationPreferences(),
    val pendingChanges: SchoolChangeSummary? = null,
)
