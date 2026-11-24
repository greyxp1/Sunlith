package io.github.sunsind.sunlith.school

import android.content.Context
import java.net.URI
import java.net.URLEncoder
import java.time.LocalDate
import java.time.ZoneId
import java.util.Locale
import kotlinx.coroutines.ensureActive
import kotlin.coroutines.coroutineContext
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener

internal data class SchoolLogin(val token: String, val expiresAt: Long)
internal class SchoolPortalClient(address: String, private val language: String, context: Context) {
    private val https = SchoolHttps(context, "Portal")
    val base: URI = normalize(address)
    suspend fun login(username: String, password: String): SchoolLogin {
        schoolRequire(username.isNotBlank() && password.isNotEmpty() && username.length <= 1000 && password.length <= 4000)
        val form = listOf("username" to username, "password" to password, "grant_type" to "password").joinToString("&") { (k, v) -> "$k=${URLEncoder.encode(v, "UTF-8")}" }
        val j = request("token", null, false, form) as JSONObject
        val token = j.requiredText("access_token")
        schoolRequire(token.length in 10..16_000 && token.none { it == '\r' || it == '\n' })
        val seconds = j.getLong("expires_in")
        schoolRequire(seconds in 1..(366L * 86_400))
        return SchoolLogin(token, System.currentTimeMillis() + seconds * 1000)
    }
    /** Runs [block] with [token], signing in first when it is null and once more after the portal refuses it. */
    suspend fun <T> signedIn(token: String?, username: String, password: String, onLogin: (SchoolLogin) -> Unit, block: suspend (String) -> T): T {
        suspend fun fresh() = login(username, password).also(onLogin).token
        val known = token ?: fresh()
        return try { block(known) } catch (e: SchoolFailure) { if (e.reason != SchoolError.SIGN_IN) throw e; block(fresh()) }
    }
    suspend fun fetch(token: String, zone: ZoneId, previous: List<SchoolItem>, initialized: Boolean, termKey: String): SchoolFeed {
        val mfa = request("api/infoMFA", token, false) as JSONObject
        if (mfa.requiredBoolean("IndMFA")) throw SchoolFailure(SchoolError.MFA_REQUIRED)
        val access = request("api/accesPermis", token, false) as JSONObject
        if (!access.requiredBoolean("Acces")) throw SchoolFailure(SchoolError.SIGN_IN)
        val info = request("api/informations", token, false) as JSONObject
        val account = schoolHash("$base\n${info.requiredText("CleUnique")}")
        schoolRequire(info.has("ServicesAffiches") && !info.isNull("ServicesAffiches"))
        val services = info.get("ServicesAffiches").toString().uppercase(Locale.ROOT)
        if (!services.contains("AGENDA") || !services.contains("CANCELED_SESSIONS")) throw SchoolFailure(SchoolError.UNAVAILABLE)
        val termRows = request("api/periodesEtudes", token, true) as JSONArray
        schoolRequire(termRows.length() in 1..100)
        val selectedRaw = info.opt("PeriodeEtudesHoraire")
        val selected = when (selectedRaw) { is JSONObject -> selectedRaw.text("Code"); is String, is Number -> selectedRaw.toString(); else -> "" }
        val term = SchoolSource.selectTerm((0 until termRows.length()).map { SchoolSource.term(termRows.getJSONObject(it)) }, selected, LocalDate.now(zone))
        val incremental = initialized && termKey == term.key
        val rows = request("api/agenda?dateDebut=${SchoolSource.wireDate(term.start)}&dateFin=${SchoolSource.wireDate(term.end)}&cleUniqueEleve=${URLEncoder.encode(info.requiredText("CleUnique"), "UTF-8")}", token, true) as JSONArray
        val agenda = SchoolSource.agenda(rows, term, zone)
        val thisMonday = LocalDate.now(zone).with(java.time.temporal.TemporalAdjusters.previousOrSame(java.time.DayOfWeek.MONDAY))
        val absences = term.mondays().filter { !incremental || !it.isBefore(thisMonday) }.flatMap { monday ->
            coroutineContext.ensureActive()
            SchoolSource.absences(request("api/coursAnnules?DateDebut=${SchoolSource.wireDate(monday)}", token, true) as JSONArray, monday, zone)
        }
        val merged = SchoolSource.merge(agenda, absences, term).toMutableList()
        val byId = merged.withIndex().associate { it.value.id to it.index }.toMutableMap()
        if (incremental) previous.filter { it.teacherAbsent && it.date.isBefore(thisMonday) && !it.date.isBefore(term.start) && !it.date.isAfter(term.end) }.forEach { old ->
            val index = byId[old.id]
            if (index != null && merged[index].classMatch == old.classMatch) merged[index] = merged[index].copy(teacherAbsent = true, notes = (merged[index].notes + old.notes).distinct())
            else if (old.kind == "absence" && index == null) {
                byId[old.id] = merged.size
                merged += old
            }
        }
        return SchoolFeed(account, term, merged.sortedWith(compareBy<SchoolItem> { it.startMillis }.thenBy { it.id }))
    }
    suspend fun portalLinks(token: String): List<Pair<String, String>> {
        val info = request("api/informations", token, false) as JSONObject
        // A missing or null list means the college publishes no links. Any other non-list value is an unexpected answer.
        if (!info.has("Liens") || info.isNull("Liens")) return emptyList()
        val links = info.get("Liens") as? JSONArray ?: throw SchoolFailure(SchoolError.INVALID_DATA)
        schoolRequire(links.length() <= 200)
        return (0 until links.length()).mapNotNull { links.optJSONObject(it) }.mapNotNull { j ->
            val code = j.text("Code"); val description = j.text("Description")
            if (code.isBlank() || description.isBlank() || code.length > 200) null else code to description.take(200)
        }.distinctBy { it.first }
    }
    /** Returns a one-time portal URL for immediate opening. It is never stored or logged. */
    suspend fun portalLink(token: String, code: String): String? {
        schoolRequire(code.isNotBlank() && code.length <= 200)
        val j = request("api/lienPortail?cleCodeLien=${URLEncoder.encode(code, "UTF-8")}", token, false) as JSONObject
        val url = j.text("UrlLien").trim()
        return SchoolHtml.safeLink(url)?.takeIf { it.startsWith("https://", ignoreCase = true) }
    }
    private suspend fun request(relative: String, token: String?, array: Boolean, form: String? = null): Any {
        coroutineContext.ensureActive()
        val url = base.resolve(relative)
        schoolRequire(url.scheme == base.scheme && url.host == base.host && url.port == base.port && url.path.startsWith(base.path))
        return https.exchange(url, form, "application/x-www-form-urlencoded; charset=UTF-8", { connection ->
            connection.setRequestProperty("Accept", "application/json")
            connection.setRequestProperty("X-Coba-Result", if (array) "Array" else "Object")
            connection.setRequestProperty("X-Coba-Langue-Courante", if (language == "fr") "fr" else "en")
            connection.setRequestProperty("X-Coba-Mobile-Version", "4.6.3")
            if (token != null) connection.setRequestProperty("Authorization", "Bearer $token")
        }) { connection, status ->
            if (status == 401) throw SchoolFailure(SchoolError.SIGN_IN)
            if (status in 500..599) throw SchoolFailure(SchoolError.UNAVAILABLE)
            val mime = connection.contentType?.substringBefore(';')?.trim()?.lowercase(Locale.ROOT)
            if (mime == "text/html") throw SchoolFailure(SchoolError.NETWORK)
            if (status == 403 || (form != null && status == 400)) throw SchoolFailure(SchoolError.SIGN_IN)
            if (status !in 200..299) throw SchoolFailure(SchoolError.NETWORK)
            schoolRequire(mime?.let { it == "application/json" || it.endsWith("+json") } == true)
            val body = String(SchoolHttps.body(connection, MAX_BYTES), Charsets.UTF_8)
            boundedNesting(body)
            val parser = JSONTokener(body)
            val result = parser.nextValue()
            if (unavailable(result)) throw SchoolFailure(SchoolError.UNAVAILABLE)
            schoolRequire(parser.nextClean() == '\u0000' && if (array) result is JSONArray else result is JSONObject)
            result
        }
    }
    companion object {
        private const val MAX_BYTES = 4 * 1024 * 1024
        private fun boundedNesting(body: String) {
            var depth = 0; var quoted = false; var escaped = false
            body.forEach { char ->
                if (quoted) { if (escaped) escaped = false else if (char == '\\') escaped = true else if (char == '"') quoted = false }
                else when (char) { '"' -> quoted = true; '{', '[' -> { depth++; schoolRequire(depth <= 32) }; '}', ']' -> { depth--; schoolRequire(depth >= 0) } }
            }
            schoolRequire(!quoted && depth == 0)
        }
        private fun unavailable(value: Any?): Boolean = when (value) {
            is String -> value == "ServicesNonDisponibles"
            is JSONObject -> value.keys().asSequence().any {
                (it == "ServicesNonDisponibles" && value.opt(it) == true) || unavailable(value.opt(it))
            }
            is JSONArray -> (0 until value.length()).any { unavailable(value.opt(it)) }
            else -> false
        }
        fun normalize(value: String): URI {
            try {
                val uri = URI(value.trim())
                if (uri.scheme != "https" || uri.host.isNullOrBlank() || uri.rawUserInfo != null || uri.fragment != null || uri.port !in listOf(-1, 443)) throw SchoolFailure(SchoolError.INVALID_PORTAL)
                val path = uri.path.orEmpty()
                val index = path.lowercase(Locale.ROOT).indexOf("/colnet")
                if (index < 0 || (path.length > index + 7 && path[index + 7] != '/')) throw SchoolFailure(SchoolError.INVALID_PORTAL)
                val prefix = path.substring(0, index + 7)
                if (path.split('/').any { it == ".." || it == "." } || '%' in uri.rawPath || '\\' in path) throw SchoolFailure(SchoolError.INVALID_PORTAL)
                return URI("https", null, uri.host.lowercase(Locale.ROOT), -1, "$prefix/mobile/", null, null)
            } catch (e: SchoolFailure) { throw e } catch (_: Exception) { throw SchoolFailure(SchoolError.INVALID_PORTAL) }
        }
    }
}
