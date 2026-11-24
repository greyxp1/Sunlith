package io.github.sunsind.sunlith.school

import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import io.github.sunsind.sunlith.R
import io.github.sunsind.sunlith.ui.LocalPalette
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale
import kotlin.math.roundToInt
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import androidx.compose.runtime.rememberCoroutineScope

@Composable
internal fun InboxList(
    inbox: SchoolInboxState, list: LazyListState, loadNext: suspend () -> Unit, refresh: () -> Unit, onOpenPortal: () -> Unit,
    onOpen: (SchoolMail) -> Unit,
) {
    val scope = rememberCoroutineScope()
    val today = LocalDate.now()
    LaunchedEffect(inbox.messages.size, inbox.hasNext, inbox.pageError) {
        if (inbox.hasNext && !inbox.pageError) {
            snapshotFlow { list.layoutInfo.visibleItemsInfo.any { it.key == "inbox-end" } }.first { it }
            loadNext()
        }
    }
    LazyColumn(Modifier.fillMaxSize(), state = list, contentPadding = PaddingValues(top = 4.dp, bottom = 24.dp)) {
        // Keep a top anchor when mail arrives, without moving someone who is reading older rows.
        item(key = "inbox-start") {
            when {
                inbox.error != null -> MessageProblem(inboxProblem(inbox.error), refresh, onOpenPortal)
                inbox.messages.isNotEmpty() -> Spacer(Modifier.height(1.dp))
                inbox.loaded && !inbox.busy -> Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
                    Text(stringResource(R.string.school_inbox_empty), color = LocalPalette.current.muted)
                }
                else -> MessageLoading(R.string.school_inbox_loading)
            }
        }
        items(inbox.messages, key = { it.id }) { mail ->
            val date = mail.received.toLocalDate()
            val whenReceived = when (date) {
                today -> shortTime(mail.received.toLocalTime())
                today.minusDays(1) -> stringResource(R.string.school_inbox_yesterday)
                else -> shortDate(date)
            }
            SchoolMessageRow(mail.subject, mail.sender, whenReceived, mail.unread) { onOpen(mail) }
        }
        if (inbox.hasNext) item(key = "inbox-end") {
            when {
                inbox.paging -> MessageLoading(R.string.school_inbox_loading_older)
                inbox.pageError -> MessageProblem(R.string.school_inbox_page_failed, { scope.launch { loadNext() } })
                else -> Spacer(Modifier.height(1.dp))
            }
        }
    }
}

internal fun inboxProblem(error: SchoolAdminStatus): Int = when (error) {
    SchoolAdminStatus.SIGN_IN -> R.string.school_inbox_sign_in
    SchoolAdminStatus.NETWORK -> R.string.school_inbox_network
    else -> R.string.school_inbox_unavailable
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun SchoolMessageRow(
    subject: String, sender: String, time: String, unread: Boolean? = null, onOpen: () -> Unit,
) {
    val p = LocalPalette.current
    val status = unread?.let { stringResource(if (it) R.string.school_inbox_unread else R.string.school_inbox_read) }
    val openLabel = stringResource(R.string.school_open_message)
    val weight = if (unread == true) FontWeight.Bold else FontWeight.Medium
    Column {
        Row(
            Modifier.fillMaxWidth().heightIn(min = 72.dp)
                .background(if (unread == true) p.accent.copy(alpha = 0.075f) else Color.Transparent)
                .clickable(role = Role.Button, onClickLabel = openLabel, onClick = onOpen)
                .semantics { if (status != null) stateDescription = status }
                .padding(horizontal = 16.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            if (unread != null) {
                Box(Modifier.padding(top = 6.dp).size(8.dp)
                    .background(if (unread) p.accent else Color.Transparent, CircleShape))
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(subject.ifBlank { stringResource(R.string.untitled) }, color = p.ink, style = MaterialTheme.typography.bodyLarge,
                    fontWeight = weight, maxLines = 2, overflow = TextOverflow.Ellipsis)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    if (sender.isNotBlank()) Text(sender, color = if (unread == true) p.ink else p.muted,
                        style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(time, color = p.muted, style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
        HorizontalDivider(Modifier.padding(start = if (unread == null) 16.dp else 34.dp), color = p.line)
    }
}

@Composable
internal fun MessageLoading(label: Int) {
    val p = LocalPalette.current
    Row(Modifier.fillMaxWidth().padding(16.dp).semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite },
        horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.CenterHorizontally), verticalAlignment = Alignment.CenterVertically) {
        CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = p.accent)
        Text(stringResource(label), color = p.muted, style = MaterialTheme.typography.bodyMedium)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun MessageProblem(label: Int, retry: () -> Unit, portal: (() -> Unit)? = null) {
    SchoolMessageCard {
        Text(stringResource(label), Modifier.semantics { liveRegion = LiveRegionMode.Polite },
            color = LocalPalette.current.ink, style = MaterialTheme.typography.bodyMedium)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(retry) { Text(stringResource(R.string.action_retry)) }
            if (portal != null) TextButton(portal) { Text(stringResource(R.string.school_admin_open_portal)) }
        }
    }
}

@Composable
internal fun InboxDetail(id: String, mail: SchoolMail?, inbox: SchoolInbox, portalUrl: String?, onOpenPortal: () -> Unit) {
    val p = LocalPalette.current
    val uri = LocalUriHandler.current
    var result by remember(id) { mutableStateOf<SchoolMailRead?>(null) }
    var loading by remember(id) { mutableStateOf(true) }
    var attempt by remember(id) { mutableIntStateOf(0) }
    LaunchedEffect(id, attempt) {
        loading = true
        result = inbox.read(id)
        loading = false
    }
    val read = result
    if (read != null) {
        val locale = LocalConfiguration.current.locales[0]
        val format = remember(locale) { DateTimeFormatter.ofLocalizedDateTime(FormatStyle.FULL, FormatStyle.SHORT).withLocale(locale) }
        val sender = read.mail.sender.takeIf(String::isNotBlank)?.let { stringResource(R.string.school_mail_from, it) }
        EmailBody(
            read, read.mail.subject.ifBlank { stringResource(R.string.untitled) }, sender, read.mail.received.format(format), portalUrl,
            Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 8.dp).clip(RoundedCornerShape(16.dp)),
        ) { url -> runCatching { uri.openUri(url) } }
    } else Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        mail?.subject?.takeIf(String::isNotBlank)?.let { subject ->
            Text(subject, Modifier.padding(horizontal = 16.dp, vertical = 8.dp).semantics { heading() },
                style = MaterialTheme.typography.titleLarge, color = p.ink)
        }
        if (loading) MessageLoading(R.string.school_inbox_opening)
        else MessageProblem(R.string.school_inbox_message_unavailable, { attempt++ }, onOpenPortal)
    }
}

@Composable
private fun EmailBody(read: SchoolMailRead, subject: String, sender: String?, date: String, portalUrl: String?, modifier: Modifier, openLink: (String) -> Unit) {
    val p = LocalPalette.current
    val zoom = (100 * LocalDensity.current.fontScale).roundToInt()
    val locale = LocalConfiguration.current.locales[0].toLanguageTag()
    val attachmentsTitle = stringResource(R.string.school_mail_attachments)
    val openPortalTitle = stringResource(R.string.school_admin_open_portal)
    val document = remember(read, subject, sender, date, locale, p, attachmentsTitle, portalUrl, openPortalTitle) {
        val attachments = if (read.attachments.isEmpty()) "" else {
            val files = read.attachments.joinToString("<br>")
            val portal = portalUrl?.let(SchoolHtml::safeLink)?.takeIf { read.attachments.any { " href=\"" !in it } }
                ?.let { "<br><a href=\"${SchoolHtml.escapeMarkup(it)}\">${SchoolHtml.escapeMarkup(openPortalTitle)}</a>" }.orEmpty()
            "<aside><h2>${SchoolHtml.escapeMarkup(attachmentsTitle)}</h2>$files$portal</aside>"
        }
        val footer = read.footer.takeIf { it.isNotBlank() }?.let { "<footer>$it</footer>" }.orEmpty()
        val darkStyle = if (p.dark) """
            article :not(img) { color:inherit !important; background-color:transparent !important; }
            article a[href] { color:${p.accent.css()} !important; }
        """ else ""
        """
        <!doctype html><html lang="$locale"><head><meta charset="utf-8">
        <meta name="viewport" content="width=device-width, initial-scale=1">
        <meta name="referrer" content="no-referrer">
        <meta http-equiv="Content-Security-Policy" content="default-src 'none'; style-src 'unsafe-inline'; img-src https: data:; form-action 'none'; base-uri 'none'">
        <style>
        body { margin:0; background:${p.background.css()}; color:${p.ink.css()}; font:16px/1.55 sans-serif; overflow-wrap:anywhere; }
        header { padding:18px 16px; background:${p.surface.css()}; color:${p.ink.css()}; border-bottom:1px solid ${p.line.css()}; }
        header h1 { margin:0 0 8px; font-size:1.375em; line-height:1.2; }
        header p { margin:3px 0 0; color:${p.muted.css()}; font-size:.875em; }
        article { padding:14px 16px 18px; overflow-x:auto; text-align:start; }
        article :is(p,div,li,td,th,span,font) { line-height:1.55 !important; }
        article p { margin:.75em 0 !important; }
        a { color:${p.accent.css()}; }
        img { max-width:100% !important; height:auto !important; }
        aside, footer { margin:0 16px; padding:12px 0; border-top:1px solid ${p.line.css()}; }
        aside h2 { margin:0 0 4px; font-size:1em; }
        footer { color:${p.muted.css()}; font-size:.875em; }
        $darkStyle
        </style></head><body><header><h1>${SchoolHtml.escapeMarkup(subject)}</h1>
        ${sender?.let { "<p>${SchoolHtml.escapeMarkup(it)}</p>" }.orEmpty()}<p>${SchoolHtml.escapeMarkup(date)}</p>
        </header><article>${read.html}</article>$attachments$footer</body></html>
        """.trimIndent()
    }
    key(document) {
        AndroidView(
            factory = { context ->
                WebView(context).apply {
                    setBackgroundColor(p.surface.toArgb())
                    settings.allowContentAccess = false
                    settings.builtInZoomControls = true
                    settings.displayZoomControls = false
                    webViewClient = object : WebViewClient() {
                        override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
                            if (!request.isForMainFrame || request.url.toString() != EMAIL_DOCUMENT_URL) return null
                            return WebResourceResponse("text/html", "UTF-8", 200, "OK",
                                mapOf("Cache-Control" to "no-store"), document.byteInputStream())
                        }

                        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                            val url = request.url.toString()
                            if (request.isForMainFrame && request.hasGesture() && url != EMAIL_DOCUMENT_URL) {
                                SchoolHtml.safeLink(url)?.let(openLink)
                            }
                            return true
                        }
                    }
                    // WebView's data URL navigation drops large documents with embedded images.
                    loadUrl(EMAIL_DOCUMENT_URL)
                }
            },
            update = { it.settings.textZoom = zoom },
            modifier = modifier,
            onRelease = WebView::destroy,
        )
    }
}

private fun Color.css() = String.format(Locale.ROOT, "#%06x", toArgb() and 0xffffff)

private const val EMAIL_DOCUMENT_URL = "https://sunlith.invalid/email"
