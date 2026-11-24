package io.github.sunsind.sunlith.school.messages

import android.content.Context
import android.os.SystemClock
import io.github.sunsind.sunlith.school.SchoolError
import io.github.sunsind.sunlith.school.portal.HtmlToken
import io.github.sunsind.sunlith.school.portal.MAIL_IMAGE_EXTENSIONS
import io.github.sunsind.sunlith.school.portal.MAIL_IMAGE_TYPES
import io.github.sunsind.sunlith.school.portal.SchoolClassicPortal
import io.github.sunsind.sunlith.school.portal.SchoolHtml
import io.github.sunsind.sunlith.school.portal.SchoolHtmlPage
import io.github.sunsind.sunlith.school.portal.SchoolPortalClient
import io.github.sunsind.sunlith.school.sync.SchoolFailure
import io.github.sunsind.sunlith.school.sync.SchoolNotifications
import io.github.sunsind.sunlith.school.sync.SchoolRecord
import io.github.sunsind.sunlith.school.sync.schoolError
import io.github.sunsind.sunlith.school.sync.schoolHash
import io.github.sunsind.sunlith.school.sync.schoolRequire
import io.github.sunsind.sunlith.school.sync.text
import java.net.URI
import java.net.URLEncoder
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull

internal data class SchoolMail(
    val id: String,
    val sender: String,
    val subject: String,
    val received: LocalDateTime,
    val unread: Boolean,
    val link: URI,
)

internal data class SchoolInboxState(
    val messages: List<SchoolMail> = emptyList(),
    val busy: Boolean = false,
    val error: SchoolAdminStatus? = null,
    val hasNext: Boolean = false,
    val loaded: Boolean = false,
    val paging: Boolean = false,
    val pageError: Boolean = false,
)

internal data class SchoolInboxNext(val uri: URI, val form: String)
internal data class SchoolInboxPage(val messages: List<SchoolMail>, val next: SchoolInboxNext?)
internal data class SchoolMailRead(val mail: SchoolMail, val html: String, val attachments: List<String>, val footer: String)

internal class SchoolInbox(private val context: Context, private val record: () -> SchoolRecord?) {
    private data class Session(val connection: String, val portal: SchoolClassicPortal, val page: SchoolInboxPage, val expired: Boolean = false)
    private data class Window(val portal: SchoolClassicPortal, val page: SchoolInboxPage, val messages: List<SchoolMail>)

    private val mutex = Mutex()
    private val lock = Any()
    private var generation = 0
    private var session: Session? = null
    private var lastRefresh: Long? = null
    private val history = context.getSharedPreferences("school-inbox", Context.MODE_PRIVATE)
    private val mutableState = MutableStateFlow(SchoolInboxState())
    val state = mutableState.asStateFlow()

    fun clear() = synchronized(lock) {
        generation++
        session = null
        lastRefresh = null
        history.edit().clear().apply()
        mutableState.value = SchoolInboxState()
    }

    suspend fun refresh(force: Boolean = true, keepPages: Boolean = !force) =
        load(next = false, force = force, keepPages = keepPages)
    suspend fun loadNext() = load(next = true, force = false, keepPages = false)

    private suspend fun load(next: Boolean, force: Boolean, keepPages: Boolean): SchoolAdminStatus? = withContext(Dispatchers.IO) {
        mutex.lock()
        val stamp = synchronized(lock) { generation }
        try {
            val current = record()?.takeIf { it.disconnecting == null } ?: return@withContext null
            val previous = synchronized(lock) {
                if (stamp != generation) return@withContext null
                if (!next && !force && lastRefresh?.let { SystemClock.elapsedRealtime() - it < 5 * 60_000L } == true) {
                    return@withContext null
                }
                session?.takeIf { it.connection == current.connection }
            }
            val request = previous?.page?.next
            if (next && request == null) return@withContext null
            synchronized(lock) {
                if (stamp != generation) return@withContext null
                val prior = if (previous == null) SchoolInboxState() else mutableState.value
                mutableState.value = if (next) prior.copy(paging = true)
                    else prior.copy(busy = true, error = mutableState.value.error.takeIf { !force || keepPages })
            }
            val earlier = synchronized(lock) { mutableState.value.messages }
            val retryPage = next && (mutableState.value.pageError || previous?.expired == true)
            val last = earlier.lastOrNull()
            val window = withTimeout(60_000) {
                if (retryPage) extend(open(current)) { it.size > earlier.size && it.covers(last) }
                else if (next && previous != null && request != null) {
                    val portal = previous.portal
                    val page = portal.nextInbox(request).inboxPage(portal.colnet, earlier)
                    Window(portal, page, (earlier + page.messages).distinctBy { it.id })
                } else {
                    val fresh = open(current)
                    synchronized(lock) {
                        if (stamp != generation) return@withTimeout null
                        notifyArrivals(current, fresh.messages)
                    }
                    val unchanged = earlier.take(fresh.messages.size).map { it.id to it.unread } ==
                        fresh.messages.map { it.id to it.unread }
                    if (keepPages && previous != null && !previous.expired && fresh.page.next != null &&
                        earlier.size > fresh.messages.size && unchanged
                    ) null
                    else extend(fresh) { it.covers(last) }
                }
            }
            synchronized(lock) {
                if (stamp != generation) return@withContext null
                if (!next) lastRefresh = SystemClock.elapsedRealtime()
                if (window == null) {
                    mutableState.value = mutableState.value.copy(error = null)
                } else {
                    val (portal, page, messages) = window
                    schoolRequire(!next || page.next == null || messages.size > earlier.size)
                    session = Session(current.connection, portal, page)
                    mutableState.value = SchoolInboxState(messages, hasNext = page.next != null, loaded = true)
                }
            }
            null
        } catch (e: Exception) {
            if (e is CancellationException && e !is TimeoutCancellationException) throw e
            val status = schoolError(e, SchoolError.UNAVAILABLE).adminStatus()
            synchronized(lock) {
                if (stamp == generation) {
                    if (!next) lastRefresh = if (status.isRetryableInboxFailure()) null else SystemClock.elapsedRealtime()
                    mutableState.value = if (next) mutableState.value.copy(pageError = true)
                        else mutableState.value.copy(error = status)
                }
            }
            status
        } finally {
            synchronized(lock) {
                if (stamp == generation) mutableState.value = mutableState.value.copy(busy = false, paging = false)
            }
            mutex.unlock()
        }
    }

    private suspend fun open(current: SchoolRecord): Window {
        val portal = SchoolClassicPortal(SchoolPortalClient.normalize(current.portal), context)
        val page = portal.openInbox(current.username, current.password).inboxPage(portal.colnet)
        return Window(portal, page, page.messages)
    }

    private suspend fun extend(window: Window, enough: (List<SchoolMail>) -> Boolean): Window {
        val portal = window.portal
        var page = window.page
        val messages = window.messages.toMutableList()
        while (!enough(messages)) {
            val next = page.next ?: break
            page = portal.nextInbox(next).inboxPage(portal.colnet, messages)
            messages += page.messages
        }
        return Window(portal, page, messages)
    }

    private fun List<SchoolMail>.covers(previousLast: SchoolMail?) = previousLast == null ||
        any { it.id == previousLast.id } || lastOrNull()?.received?.isBefore(previousLast.received) == true

    private fun notifyArrivals(current: SchoolRecord, messages: List<SchoolMail>) {
        val newest = messages.maxOfOrNull { it.received }
        val initialized = history.getString("connection", null) == current.connection
        val since = if (initialized) history.getString("received", null)?.let(LocalDateTime::parse) else null
        val known = if (initialized) history.getStringSet("ids", emptySet()).orEmpty() else emptySet()
        // Older versions could identify only read rows, using their numeric checkbox IDs.
        val hadReadOnlyIds = known.any { id -> id.all(Char::isDigit) }
        val arrived = if (initialized) messages.filter {
            since == null || it.received > since ||
                (it.received == since && it.id !in known && (!hadReadOnlyIds || it.unread))
        } else messages.filter { it.unread }
        val watermark = listOfNotNull(newest, since).maxOrNull()
        val ids = messages.filter { it.received == watermark }.map { it.id }.toSet() +
            if (watermark == since) known.filterNot { id -> id.all(Char::isDigit) } else emptySet()
        if ((!initialized || watermark != since || ids != known) && history.edit()
                .putString("connection", current.connection)
                .putString("received", watermark?.toString())
                .putStringSet("ids", ids).commit()
        ) runCatching { SchoolNotifications(context).inbox(current, arrived) }
    }

    suspend fun read(id: String): SchoolMailRead? = withContext(Dispatchers.IO) {
        mutex.withLock {
            val current = record()?.takeIf { it.disconnecting == null } ?: return@withLock null
            val (stamp, portal, mail) = synchronized(lock) {
                val portal = session?.takeIf { it.connection == current.connection && !it.expired }?.portal
                val mail = mutableState.value.messages.firstOrNull { it.id == id }
                Triple(generation, portal, mail)
            }
            val last = synchronized(lock) { mutableState.value.messages.lastOrNull() }
            suspend fun fetch(portal: SchoolClassicPortal, mail: SchoolMail) = try {
                portal.get(mail.link).inboxBody(portal, mail)
            } catch (e: SchoolFailure) {
                if (e.reason != SchoolError.SIGN_IN && e.reason != SchoolError.INVALID_DATA) throw e
                null
            }
            val result = try {
                withTimeout(60_000) {
                    if (portal != null && mail != null) fetch(portal, mail)?.let { return@withTimeout it }
                    val (fresh, page, recovered) = extend(open(current)) { rows -> rows.any { it.id == id } }
                    val selected = recovered.firstOrNull { it.id == id } ?: return@withTimeout null
                    synchronized(lock) {
                        if (stamp != generation) return@withTimeout null
                        if (recovered.covers(last)) {
                            session = Session(current.connection, fresh, page)
                            mutableState.value = SchoolInboxState(recovered, hasNext = page.next != null, loaded = true)
                        } else {
                            session = session?.copy(expired = true)
                        }
                    }
                    fetch(fresh, selected)
                }
            } catch (e: Exception) {
                if (e is CancellationException && e !is TimeoutCancellationException) throw e
                null
            }
            synchronized(lock) {
                if (stamp != generation) return@withLock null
                val inbox = mutableState.value
                if (result != null && inbox.messages.any { it.id == id && it.unread }) mutableState.value = inbox.copy(
                    messages = inbox.messages.map { if (it.id == id) it.copy(unread = false) else it },
                )
                result
            }
        }
    }
}

internal fun SchoolAdminStatus?.isRetryableInboxFailure() =
    this == SchoolAdminStatus.NETWORK || this == SchoolAdminStatus.UNAVAILABLE

private val INBOX_DATE = DateTimeFormatter.ofPattern("uuuu/MM/dd HH:mm", Locale.ROOT)

internal fun SchoolHtmlPage.inboxPage(colnet: URI, earlier: List<SchoolMail> = emptyList()): SchoolInboxPage {
    if (uri.path.endsWith("/logout.asp", ignoreCase = true)) throw SchoolFailure(SchoolError.SIGN_IN)
    val tokens = SchoolHtml.tokens(html)
    schoolRequire(uri.path.equals(colnet.resolve("courriels.asp").path, ignoreCase = true)
        && "cboDossier" in SchoolHtml.attributes(tokens, "select", "name"))
    val detailPath = colnet.resolve("gestcourr.asp").path
    val occurrences = earlier.groupingBy { it.id.substringBefore(':') }.eachCount().toMutableMap()
    val messages = SchoolHtml.tables(tokens).flatMap { it.rows }.mapNotNull { row ->
        val icon = row.getOrNull(1)?.let { cell ->
            SchoolHtml.attributes(cell.tokens, "img", "alt").map(SchoolHtml::normalize)
                .firstOrNull { it == "courriel lu" || it == "courriel non lu" }
        } ?: return@mapNotNull null
        schoolRequire(row.size == 7)
        val link = row[5].links.mapNotNull { SchoolAdminParser.sameFolder(it.href, uri, colnet) }.distinct()
            .singleOrNull { it.path.equals(detailPath, ignoreCase = true) && !it.rawQuery.isNullOrBlank() }
            ?: throw SchoolFailure(SchoolError.INVALID_DATA)
        val received = runCatching { LocalDateTime.parse(row[6].text.trim(), INBOX_DATE) }
            .getOrElse { throw SchoolFailure(SchoolError.INVALID_DATA) }
        val sender = row[2].text.trim()
        val subject = row[5].text.trim()
        // Unread rows have no checkbox ID, and their links belong to the current portal session.
        val fingerprint = schoolHash("$received\n$sender\n$subject")
        val ordinal = occurrences.getOrDefault(fingerprint, 0)
        occurrences[fingerprint] = ordinal + 1
        SchoolMail("$fingerprint:$ordinal", sender, subject, received, icon == "courriel non lu", link)
    }
    schoolRequire(messages.map { it.id }.distinct().size == messages.size)
    val next = SchoolHtml.labeledLinks(tokens) { it.startsWith("page suivante") || it.startsWith("next page") }.distinct()
    schoolRequire(next.size <= 1)
    return SchoolInboxPage(messages, next.singleOrNull()?.let { inboxNext(tokens, colnet, it) })
}

private val NEXT_PAGE_CALL = Regex("""javascript:DoPostBack\('([A-Za-z0-9+/=]+)','([?A-Za-z0-9+/=]+)','',false,false\);?""", RegexOption.IGNORE_CASE)

private fun SchoolHtmlPage.inboxNext(tokens: List<HtmlToken>, colnet: URI, href: String): SchoolInboxNext {
    val parameters = NEXT_PAGE_CALL.matchEntire(href.trim())?.groupValues ?: throw SchoolFailure(SchoolError.INVALID_DATA)
    val form = SchoolHtml.forms(tokens).singleOrNull { it.inputs.any { field -> field["name"] == "_PostBackInfo" } }
        ?: throw SchoolFailure(SchoolError.INVALID_DATA)
    val action = SchoolAdminParser.sameFolder(form.action, uri, colnet)?.takeIf { it.path.equals(uri.path, ignoreCase = true) }
        ?: throw SchoolFailure(SchoolError.INVALID_DATA)
    val fields = form.inputs.filter { it["type"].equals("hidden", ignoreCase = true) && !it["name"].isNullOrBlank() }
        .associate { it.getValue("name") to it["value"].orEmpty() }.toMutableMap()
    fields["_PostBackInfo"] = parameters[1]
    fields["_PostBackPar"] = parameters[2]
    fields["txtLibWebBidon"] = ""
    fields["cboDossier"] = "1"
    fields["cboDeplcDossiers"] = "0"
    return SchoolInboxNext(action, fields.entries.joinToString("&") { (name, value) ->
        "${URLEncoder.encode(name, "UTF-8")}=${URLEncoder.encode(value, "UTF-8")}"
    })
}

private suspend fun SchoolHtmlPage.inboxBody(portal: SchoolClassicPortal, mail: SchoolMail): SchoolMailRead {
    schoolRequire(uri.path.equals(portal.colnet.resolve("gestcourr.asp").path, ignoreCase = true))
    val tokens = SchoolHtml.tokens(html, maxChars = null)
    val body = SchoolHtml.elementContent(tokens, "div", "ckeditor")
        ?: throw SchoolFailure(SchoolError.INVALID_DATA)
    val attachments = SchoolHtml.mailAttachments(tokens.take(body.first - 1))
    fun resolve(value: String) = runCatching { uri.resolve(value.trim()).normalize().toString() }.getOrNull()?.let(SchoolHtml::safeLink)
    var imageBudget = 8 * 1024 * 1024
    val images = mutableMapOf<String, String>()
    val imageDeadline = SystemClock.elapsedRealtime() + 8_000
    val message = SchoolHtml.emailMarkup(tokens.subList(body.first, body.last + 1), ::resolve) { raw ->
        val dataType = raw.substringAfter("data:", "").substringBefore(";base64,")
        if (dataType in MAIL_IMAGE_TYPES) raw
        else resolve(raw)?.takeIf { it.startsWith("https://", ignoreCase = true) }?.let { url ->
            val local = SchoolAdminParser.sameFolder(url, uri, portal.colnet)
            if (local == null || local.path.substringAfterLast('.').lowercase(Locale.ROOT) !in MAIL_IMAGE_EXTENSIONS) url
            else images[url] ?: run {
                val remaining = imageDeadline - SystemClock.elapsedRealtime()
                val data = if (imageBudget > 0 && remaining > 0 && images.size < 16) {
                    withTimeoutOrNull(remaining) { portal.inboxImage(local, minOf(2 * 1024 * 1024, imageBudget)) }
                } else null
                if (data != null) imageBudget -= data.length
                (data ?: url).also { images[url] = it }
            }
        }
    }
    return SchoolMailRead(
        mail, message, attachments.map { SchoolHtml.emailMarkup(it, ::resolve) { null } },
        SchoolHtml.emailMarkup(SchoolHtml.mailFooter(tokens, body.last + 2), ::resolve) { null },
    )
}
