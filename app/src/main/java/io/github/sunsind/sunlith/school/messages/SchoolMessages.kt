package io.github.sunsind.sunlith.school.messages

import io.github.sunsind.sunlith.school.SchoolError
data class SchoolPortalLink(val code: String, val description: String)

/** The college's own Col.NET links. An empty [Loaded] list means the college publishes none, which is not an error. */
sealed interface SchoolPortalLinks {
    data class Loaded(val links: List<SchoolPortalLink>) : SchoolPortalLinks
    /** [status] says why: SIGN_IN, NETWORK, UNAVAILABLE, UNRECOGNIZED (unexpected answer) or NOT_LOADED (not connected). */
    data class Failed(val status: SchoolAdminStatus) : SchoolPortalLinks
}

enum class SchoolAdminStatus { NOT_LOADED, OK, SIGN_IN, UNRECOGNIZED, NETWORK, UNAVAILABLE, STORAGE }

data class SchoolMessagesState(
    val connected: Boolean = false,
    val busy: Boolean = false,
    val admin: List<SchoolAdminNotice> = emptyList(),
    val adminStatus: SchoolAdminStatus = SchoolAdminStatus.NOT_LOADED,
    val collegeNotices: List<SchoolCollegeNotice> = emptyList(),
    /** Public Col.NET sign-in page; carries no session. */
    val portalUrl: String? = null,
    /** Null until links were loaded once. False when the college publishes no extra Col.NET links. */
    val portalLinksAvailable: Boolean? = null,
)

internal fun SchoolError.adminStatus() = when (this) {
    SchoolError.SIGN_IN, SchoolError.MFA_REQUIRED -> SchoolAdminStatus.SIGN_IN
    SchoolError.NETWORK -> SchoolAdminStatus.NETWORK
    SchoolError.UNAVAILABLE -> SchoolAdminStatus.UNAVAILABLE
    else -> SchoolAdminStatus.UNRECOGNIZED
}
