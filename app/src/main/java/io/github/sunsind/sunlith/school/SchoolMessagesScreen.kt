package io.github.sunsind.sunlith.school

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.compose.LifecycleResumeEffect
import io.github.sunsind.sunlith.R
import io.github.sunsind.sunlith.ui.LocalPalette
import io.github.sunsind.sunlith.ui.Note
import io.github.sunsind.sunlith.ui.ScreenTopBar
import io.github.sunsind.sunlith.ui.SectionTitle
import io.github.sunsind.sunlith.ui.SurfaceCard
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SchoolMessagesScreen(
    controller: SchoolMessagesManager,
    messageId: String?,
    showNotices: Boolean,
    onOpenMessage: (String) -> Unit,
    onOpenDate: (LocalDate) -> Unit = {},
) {
    val p = LocalPalette.current
    val state by controller.state.collectAsStateWithLifecycle()
    val inbox by controller.inbox.state.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val uri = LocalUriHandler.current
    val pager = rememberPagerState(initialPage = if (showNotices) NOTICES_PAGE else INBOX_PAGE) { 2 }
    val section = pager.settledPage
    val noticesList = rememberLazyListState()
    val inboxList = rememberLazyListState()
    var links by remember { mutableStateOf<List<SchoolPortalLink>?>(null) }
    var linkFailed by remember { mutableStateOf(false) }
    var linksProblem by remember { mutableStateOf<SchoolAdminStatus?>(null) }
    var linksLoading by remember { mutableStateOf(false) }
    var resolvingLink by remember { mutableStateOf(false) }
    var portalJob by remember { mutableStateOf<Job?>(null) }

    val admin = state.admin.firstOrNull { it.id == messageId }
    val mailId = messageId?.takeIf { it.startsWith("inbox:") }?.removePrefix("inbox:")
    val mail = inbox.messages.firstOrNull { it.id == mailId }
    LifecycleResumeEffect(section, messageId, state.connected) {
        val refresh = if (messageId == null && section == INBOX_PAGE && state.connected) {
            scope.launch { controller.inbox.refresh(keepPages = true) }
        } else null
        onPauseOrDispose { refresh?.cancel() }
    }
    LaunchedEffect(section, messageId, state.connected) {
        if (messageId == null && section != INBOX_PAGE && state.connected) {
            try { controller.refresh() } catch (e: CancellationException) { throw e } catch (_: Exception) { }
        }
    }
    fun openPortal() { state.portalUrl?.let { runCatching { uri.openUri(it) } } }
    fun dismissLinks() {
        portalJob?.cancel()
        links = null
        linksProblem = null
        linksLoading = false
        resolvingLink = false
    }
    LifecycleResumeEffect(Unit) {
        onPauseOrDispose { dismissLinks() }
    }
    LaunchedEffect(pager.currentPage) {
        if (pager.currentPage == INBOX_PAGE) dismissLinks()
    }

    val showingInbox = messageId == null && section == INBOX_PAGE
    var completedRefreshes by remember { mutableIntStateOf(0) }
    var refreshingPage by remember { mutableStateOf<Int?>(null) }
    fun refreshPage(page: Int, pulled: Boolean = false) {
        if (refreshingPage == page) return
        if (pulled) refreshingPage = page
        scope.launch {
            try { if (page == INBOX_PAGE) controller.inbox.refresh() else controller.refresh() }
            catch (e: CancellationException) { throw e } catch (_: Exception) { }
            finally {
                if (pulled && refreshingPage == page) refreshingPage = null
                if (isActive) completedRefreshes++
            }
        }
    }
    val refresh: () -> Unit = { refreshPage(section) }
    val snackbar = remember { SnackbarHostState() }
    val listError = when {
        messageId != null -> null
        showingInbox -> inbox.error?.let(::inboxProblem)
        else -> noticeProblem(state.adminStatus, state.busy).takeUnless { state.adminStatus == SchoolAdminStatus.NOT_LOADED }
    }
    val activeList = if (showingInbox) inboxList else noticesList
    val errorText = listError?.let { stringResource(it) }
    val retryLabel = stringResource(R.string.action_retry)
    LaunchedEffect(errorText, activeList, completedRefreshes) {
        if (errorText != null && (activeList.firstVisibleItemIndex > 0 || activeList.firstVisibleItemScrollOffset > 0)) {
            if (snackbar.showSnackbar(errorText, retryLabel, withDismissAction = true) == SnackbarResult.ActionPerformed) refresh()
        }
    }
    Scaffold(containerColor = p.background, snackbarHost = { SnackbarHost(snackbar) }, topBar = {
        if (messageId != null && mailId == null) ScreenTopBar(stringResource(R.string.school_notice_detail)) {
            if (state.portalUrl != null) {
                IconButton(onClick = ::openPortal) {
                    Icon(Icons.AutoMirrored.Outlined.OpenInNew, stringResource(R.string.school_admin_open_portal), tint = p.ink)
                }
            }
        }
    }) { pad ->
        Box(Modifier.fillMaxSize().padding(pad), contentAlignment = Alignment.TopCenter) {
            Box(Modifier.widthIn(max = 720.dp).fillMaxSize()) {
                when {
                    !state.connected -> Note(stringResource(R.string.school_messages_not_connected), live = true)
                    mailId != null -> InboxDetail(mailId, mail, controller.inbox, state.portalUrl, ::openPortal)
                    admin != null -> AdminDetail(admin, state.collegeNotices.firstOrNull { it.sourceId == admin.id }, ::openPortal, onOpenDate)
                    messageId != null -> Note(stringResource(R.string.school_admin_empty))
                    else -> Column(Modifier.fillMaxSize()) {
                        MessageTabs(pager.currentPage) { page ->
                            if (page != pager.currentPage) {
                                dismissLinks()
                                scope.launch { pager.animateScrollToPage(page) }
                            }
                        }
                        HorizontalPager(state = pager, modifier = Modifier.weight(1f)) { page ->
                            PullToRefreshBox(
                                isRefreshing = refreshingPage == page,
                                onRefresh = { refreshPage(page, pulled = true) },
                                modifier = Modifier.fillMaxSize(),
                            ) {
                                if (page == INBOX_PAGE) {
                                    InboxList(inbox, inboxList, { controller.inbox.loadNext() }, { refreshPage(page) }, ::openPortal) {
                                        onOpenMessage("inbox:${it.id}")
                                    }
                                } else {
                                    AdminList(
                                        state.admin, state.adminStatus, state.collegeNotices, noticesList,
                                        onOpen = { dismissLinks(); onOpenMessage(it.id) }, onOpenDate = onOpenDate,
                                        onOpenPortal = ::openPortal,
                                        onLinks = if (state.portalLinksAvailable == false) null else ({
                                            if (!linksLoading) portalJob = scope.launch {
                                                linksLoading = true
                                                try {
                                                    when (val result = controller.loadPortalLinks()) {
                                                        is SchoolPortalLinks.Loaded -> if (result.links.isEmpty()) openPortal() else { linkFailed = false; links = result.links }
                                                        is SchoolPortalLinks.Failed -> linksProblem = result.status
                                                    }
                                                } finally { if (isActive) linksLoading = false }
                                            }
                                        }),
                                        linksLoading = linksLoading, busy = state.busy, onRetry = { refreshPage(page) },
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
    linksProblem?.let { problem ->
        AlertDialog(onDismissRequest = { linksProblem = null },
            confirmButton = { TextButton({ linksProblem = null; openPortal() }, enabled = state.portalUrl != null) { Text(stringResource(R.string.school_admin_open_portal)) } },
            dismissButton = { TextButton({ linksProblem = null }) { Text(stringResource(R.string.school_messages_close)) } },
            title = { Text(stringResource(R.string.school_admin_portal_links)) },
            text = {
                Text(stringResource(when (problem) {
                    SchoolAdminStatus.SIGN_IN -> R.string.school_admin_portal_links_sign_in
                    SchoolAdminStatus.UNAVAILABLE -> R.string.school_admin_status_unavailable
                    SchoolAdminStatus.UNRECOGNIZED -> R.string.school_admin_portal_links_unrecognized
                    SchoolAdminStatus.NOT_LOADED -> R.string.school_messages_not_connected
                    else -> R.string.school_admin_portal_links_network
                }), Modifier.semantics { liveRegion = LiveRegionMode.Polite }, color = p.ink)
            })
    }
    links?.let { list ->
        AlertDialog(onDismissRequest = ::dismissLinks,
            confirmButton = { TextButton(::dismissLinks) { Text(stringResource(R.string.school_messages_close)) } },
            title = { Text(stringResource(R.string.school_admin_portal_links)) },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    if (resolvingLink) MessageLoading(R.string.school_mail_opening_link)
                    if (linkFailed) Text(stringResource(R.string.school_admin_portal_link_failed),
                        Modifier.semantics { liveRegion = LiveRegionMode.Polite }, color = p.danger)
                    list.forEach { link ->
                        Text(link.description, Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable(enabled = !resolvingLink, role = Role.Button) {
                            resolvingLink = true
                            linkFailed = false
                            portalJob = scope.launch {
                                try {
                                    val url = controller.resolvePortalLink(link.code)
                                    if (url == null) linkFailed = true else { uri.openUri(url); links = null }
                                } catch (e: CancellationException) { throw e }
                                catch (_: Exception) { linkFailed = true }
                                finally { if (isActive) resolvingLink = false }
                            }
                        }.padding(vertical = 12.dp), color = p.ink, style = MaterialTheme.typography.bodyLarge)
                    }
                }
            })
    }
}

private const val INBOX_PAGE = 0
private const val NOTICES_PAGE = 1

@Composable
private fun MessageTabs(selectedPage: Int, onSelect: (Int) -> Unit) {
    val p = LocalPalette.current
    PrimaryTabRow(selectedPage, containerColor = p.background, contentColor = p.accent) {
        listOf(R.string.school_inbox_tab, R.string.school_messages_section_main).forEachIndexed { page, label ->
            Tab(selected = page == selectedPage, onClick = { onSelect(page) }, text = { Text(stringResource(label)) },
                selectedContentColor = p.accent, unselectedContentColor = p.muted)
        }
    }
}

@Composable
private fun AdminList(
    notices: List<SchoolAdminNotice>, status: SchoolAdminStatus, college: List<SchoolCollegeNotice>,
    listState: LazyListState, onOpen: (SchoolAdminNotice) -> Unit, onOpenDate: (LocalDate) -> Unit,
    onOpenPortal: () -> Unit, onLinks: (() -> Unit)?, linksLoading: Boolean, busy: Boolean, onRetry: () -> Unit,
) {
    LazyColumn(Modifier.fillMaxSize(), state = listState, contentPadding = PaddingValues(bottom = 32.dp)) {
        val message = noticeProblem(status, busy)
        if (busy && notices.isEmpty()) item { MessageLoading(R.string.school_messages_refreshing) }
        if (message != null) item { MessageProblem(message, onRetry, onOpenPortal) }
        if (onLinks != null) item {
            TextButton(onLinks, Modifier.padding(horizontal = 8.dp), enabled = !linksLoading) {
                Text(stringResource(if (linksLoading) R.string.school_mail_loading_links else R.string.school_admin_portal_links))
            }
        }
        if (college.isNotEmpty()) {
            item { SectionTitle(stringResource(R.string.school_admin_notices)) }
            items(college, key = { it.identity }) { n -> CollegeCard(n, onOpenDate) { notices.firstOrNull { it.id == n.sourceId }?.let(onOpen) } }
        }
        if (notices.isNotEmpty() && college.isNotEmpty()) item { SectionTitle(stringResource(R.string.school_admin_list)) }
        if (notices.isEmpty() && status == SchoolAdminStatus.OK) item { Note(stringResource(R.string.school_admin_empty), live = true) }
        items(notices, key = { it.id }) { n ->
            SchoolMessageRow(n.subject, n.sender, shortDate(n.date)) { onOpen(n) }
        }
    }
}

private fun noticeProblem(status: SchoolAdminStatus, busy: Boolean): Int? = when (status) {
    SchoolAdminStatus.OK -> null
    SchoolAdminStatus.NOT_LOADED -> if (busy) null else R.string.school_admin_not_loaded
    SchoolAdminStatus.SIGN_IN -> R.string.school_admin_status_sign_in
    SchoolAdminStatus.UNRECOGNIZED -> R.string.school_admin_status_unrecognized
    SchoolAdminStatus.NETWORK -> R.string.school_admin_status_network
    SchoolAdminStatus.UNAVAILABLE -> R.string.school_admin_status_unavailable
    SchoolAdminStatus.STORAGE -> R.string.school_messages_storage
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CollegeCard(n: SchoolCollegeNotice, onOpenDate: (LocalDate) -> Unit, onSource: () -> Unit) {
    val p = LocalPalette.current
    val confirmed = n.status == SchoolNoticeStatus.CONFIRMED
    SchoolMessageCard(emphasis = true) {
        Text(n.subject, Modifier.semantics { heading() }, color = p.ink, style = MaterialTheme.typography.titleMedium)
        Text(longDate(n.targetDate), color = p.ink, style = MaterialTheme.typography.bodyMedium)
        Text(stringResource(when (n.status) { SchoolNoticeStatus.CONFIRMED -> R.string.school_admin_confirmed; SchoolNoticeStatus.UPDATED -> R.string.school_admin_updated; else -> R.string.school_admin_warning }),
            color = if (confirmed) p.danger else p.accent, style = MaterialTheme.typography.labelLarge)
        Text(listOf(n.sender, stringResource(R.string.school_admin_posted, shortDate(n.sourceDate))).filter { it.isNotBlank() }.joinToString(", "), color = p.muted, style = MaterialTheme.typography.bodySmall)
        n.until?.let { Text(stringResource(R.string.school_admin_until, shortTime(it)), color = p.ink, style = MaterialTheme.typography.bodyMedium) }
        n.decisionAt?.let { Text(stringResource(R.string.school_admin_decision, shortTime(it)), color = p.ink, style = MaterialTheme.typography.bodyMedium) }
        n.resume?.let { Text(stringResource(R.string.school_admin_resume, longDate(it)), color = p.ink, style = MaterialTheme.typography.bodyMedium) }
        if (n.exceptions.isNotEmpty()) {
            Text(stringResource(R.string.school_admin_exceptions), Modifier.padding(top = 4.dp), color = p.muted, style = MaterialTheme.typography.labelMedium)
            n.exceptions.forEach { Text("• $it", color = p.ink, style = MaterialTheme.typography.bodySmall) }
        }
        if (n.superseded.isNotEmpty()) Text(stringResource(R.string.school_admin_superseded), color = p.muted, style = MaterialTheme.typography.bodySmall)
        if (!n.detailRead) Text(stringResource(R.string.school_admin_title_only), color = p.muted, style = MaterialTheme.typography.bodySmall)
        Text(stringResource(if (n.classesMarked) R.string.school_admin_classes_marked else R.string.school_admin_scope_note), color = p.muted, style = MaterialTheme.typography.bodySmall)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton({ onOpenDate(n.targetDate) }, Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.school_admin_show_day)) }
            TextButton(onSource, Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.school_admin_open_notice)) }
        }
    }
}

@Composable
private fun AdminDetail(n: SchoolAdminNotice, college: SchoolCollegeNotice?, onOpenPortal: () -> Unit, onOpenDate: (LocalDate) -> Unit) {
    val p = LocalPalette.current
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).navigationBarsPadding().padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(n.subject, Modifier.semantics { heading() }, color = p.ink, style = MaterialTheme.typography.headlineSmall)
        Text(longDate(n.date), color = p.muted, style = MaterialTheme.typography.bodySmall)
        if (n.sender.isNotBlank()) Text(stringResource(R.string.school_admin_source, n.sender), color = p.ink, style = MaterialTheme.typography.bodyMedium)
        if (n.category.isNotBlank()) Text(stringResource(R.string.school_admin_category, n.category), color = p.muted, style = MaterialTheme.typography.bodySmall)
        HorizontalDivider(Modifier.padding(vertical = 8.dp), color = p.line)
        if (n.body != null) SelectionContainer { Text(n.body, color = p.ink, style = MaterialTheme.typography.bodyLarge) }
        else {
            Text(stringResource(R.string.school_admin_title_only), color = p.muted, style = MaterialTheme.typography.bodyMedium)
            TextButton(onOpenPortal, Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.school_admin_open_portal)) }
        }
        if (college != null) TextButton({ onOpenDate(college.targetDate) }, Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.school_admin_show_day)) }
    }
}

// Message cards end in text buttons, so they keep a tighter bottom edge.
@Composable
internal fun SchoolMessageCard(emphasis: Boolean = false, content: @Composable ColumnScope.() -> Unit) = SurfaceCard(
    Modifier.padding(horizontal = 16.dp, vertical = 6.dp), emphasis, PaddingValues(start = 16.dp, end = 16.dp, top = 14.dp, bottom = 6.dp), content,
)

@Composable
internal fun shortDate(date: LocalDate): String = date.format(DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withLocale(LocalConfiguration.current.locales[0]))
@Composable
private fun longDate(date: LocalDate): String = date.format(DateTimeFormatter.ofLocalizedDate(FormatStyle.FULL).withLocale(LocalConfiguration.current.locales[0]))
@Composable
internal fun shortTime(time: LocalTime): String = time.format(DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT).withLocale(LocalConfiguration.current.locales[0]))
