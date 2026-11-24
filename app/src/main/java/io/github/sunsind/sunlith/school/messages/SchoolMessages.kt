package io.github.sunsind.sunlith.school.messages

import io.github.sunsind.sunlith.school.SchoolError

enum class SchoolAdminStatus { NOT_LOADED, OK, SIGN_IN, UNRECOGNIZED, NETWORK, UNAVAILABLE, STORAGE }

data class SchoolMessagesState(
    val connected: Boolean = false,
    val busy: Boolean = false,
    val admin: List<SchoolAdminNotice> = emptyList(),
    val adminStatus: SchoolAdminStatus = SchoolAdminStatus.NOT_LOADED,
    val collegeNotices: List<SchoolCollegeNotice> = emptyList(),
    /** Public Col.NET sign-in page; carries no session. */
    val portalUrl: String? = null,
)

internal fun SchoolError.adminStatus() = when (this) {
    SchoolError.SIGN_IN, SchoolError.MFA_REQUIRED -> SchoolAdminStatus.SIGN_IN
    SchoolError.NETWORK -> SchoolAdminStatus.NETWORK
    SchoolError.UNAVAILABLE -> SchoolAdminStatus.UNAVAILABLE
    else -> SchoolAdminStatus.UNRECOGNIZED
}
