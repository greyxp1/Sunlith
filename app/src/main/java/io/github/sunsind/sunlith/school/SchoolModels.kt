package io.github.sunsind.sunlith.school

enum class SchoolAlert {
    TEACHER_ABSENCES, CLASS_TIMES, ROOMS, TEACHERS, ADDED_CLASSES, REMOVED_CLASSES,
    ASSESSMENTS, SCHOOL_DAYS,
}

enum class SchoolError {
    INVALID_PORTAL, SIGN_IN, MFA_REQUIRED, NETWORK, CALENDAR_PERMISSION,
    INVALID_DATA, UNAVAILABLE, STORAGE, LEGACY_COLLISION, CALENDAR_CHANGED, CALENDAR_LIMIT,
}

data class SchoolChangeSummary(val added: Int, val updated: Int, val removed: Int, val legacyLinks: Int)

data class SchoolScheduleState(
    val connected: Boolean = false,
    val busy: Boolean = false,
    val portalAddress: String = "",
    val accountName: String = "",
    val lastSuccessMillis: Long? = null,
    val error: SchoolError? = null,
    val hasImportedEvents: Boolean = false,
    val notificationsEnabled: Boolean = false,
    val pendingChanges: SchoolChangeSummary? = null,
)
