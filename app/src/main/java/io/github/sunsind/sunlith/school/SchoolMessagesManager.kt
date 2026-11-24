package io.github.sunsind.sunlith.school

import android.content.Context
import android.os.SystemClock
import android.util.Log
import java.time.LocalDate
import java.time.ZoneId
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject

/** Notice events for a term, and confirmed class liftings or null when no notice read has succeeded yet (keep existing marks). */
internal data class SchoolNoticeEvents(val items: List<SchoolItem>, val lifts: List<SchoolClassLift>?)


private data class SchoolMessagesCache(
    val account: String, val connection: String,
    val admin: List<SchoolAdminNotice> = emptyList(), val details: Map<String, SchoolAdminDetail> = emptyMap(),
    val adminAt: Long? = null, val adminStatus: SchoolAdminStatus = SchoolAdminStatus.NOT_LOADED,
    val seen: Map<String, String?> = emptyMap(),
) {
    fun json(): JSONObject = JSONObject().put("version", 1).put("account", account).put("connection", connection)
        .put("admin", JSONArray(admin.map { JSONObject().put("id", it.id).put("date", it.date.toString()).put("subject", it.subject).put("sender", it.sender).put("link", it.detailAvailable) }))
        .put("details", JSONObject().also { d -> details.forEach { (k, v) -> d.put(k, detailJson(v)) } })
        .put("seen", JSONObject().also { s -> seen.forEach { (id, body) -> s.put(id, body ?: JSONObject.NULL) } })
        .put("adminAt", adminAt ?: JSONObject.NULL).put("adminStatus", adminStatus.name)

    fun belongsTo(record: SchoolRecord) = account == record.account && connection == record.connection

    fun remember(): SchoolMessagesCache {
        val history = LinkedHashMap(seen)
        admin.forEach { message ->
            val fingerprint = message.body?.let(::schoolHash) ?: history[message.id]
            history.remove(message.id)
            history[message.id] = fingerprint
        }
        return copy(seen = history.entries.toList().takeLast(5_000).associate { it.toPair() })
    }

    fun arrivals(previous: SchoolMessagesCache): List<SchoolAdminNotice> {
        if (previous.adminAt == null) return emptyList()
        val history = previous.seen
        fun meaning(message: SchoolAdminNotice): SchoolCollegeNotice? =
            SchoolNoticeInterpreter.interpret(listOf(message)).singleOrNull()?.copy(text = "", detailRead = false, sender = "")
        val previousMessages = previous.admin.associateBy { it.id }
        return admin.filter { message ->
            !history.containsKey(message.id) || (message.body != null && when {
                history[message.id] != null -> history[message.id] != schoolHash(message.body)
                else -> meaning(message)?.let { it != previousMessages[message.id]?.let(::meaning) } == true
            })
        }
    }

    // Keep whole bodies, newest first. A body that cannot fit is shown explicitly as title only.
    fun bounded(): SchoolMessagesCache {
        val kept = linkedMapOf<String, SchoolAdminDetail>()
        var bytes = copy(details = emptyMap()).json().toString().toByteArray(Charsets.UTF_8).size + 29
        admin.sortedByDescending { it.date }.forEach { message ->
            details[message.id]?.let { detail ->
                val entry = JSONObject().put(message.id, detailJson(detail)).toString().toByteArray(Charsets.UTF_8).size - 2
                val added = entry + if (kept.isEmpty()) 0 else 1
                if (bytes + added <= SchoolMessagesStore.MAX_BYTES) { kept[message.id] = detail; bytes += added }
            }
        }
        return copy(details = kept, admin = admin.map { it.copy(body = kept[it.id]?.body, category = kept[it.id]?.category.orEmpty()) })
    }
    companion object {
        private fun detailJson(v: SchoolAdminDetail) = JSONObject().put("date", v.date.toString()).put("subject", v.subject)
            .put("source", v.source).put("category", v.category).put("body", v.body)
        fun read(j: JSONObject): SchoolMessagesCache {
            schoolRequire(j.getInt("version") == 1)
            val details = j.getJSONObject("details").let { d -> d.keys().asSequence().associateWith { k -> d.getJSONObject(k).let { v -> SchoolAdminDetail(LocalDate.parse(v.getString("date")), v.getString("subject"), v.getString("source"), v.getString("category"), v.getString("body")) } } }
            val admin = j.getJSONArray("admin").let { a -> (0 until a.length()).map { a.getJSONObject(it).let { r ->
                val id = r.getString("id"); val detail = details[id]
                SchoolAdminNotice(id, LocalDate.parse(r.getString("date")), r.getString("subject"), r.getString("sender"), detail?.category.orEmpty(), detail?.body, r.getBoolean("link"))
            } } }
            return SchoolMessagesCache(j.getString("account"), j.getString("connection"), admin, details,
                if (j.isNull("adminAt")) null else j.getLong("adminAt"), SchoolAdminStatus.valueOf(j.getString("adminStatus")),
                j.optJSONObject("seen")?.let { s -> s.keys().asSequence().associateWith { id -> if (s.isNull(id)) null else s.getString(id) } }.orEmpty())
        }
    }
}

private class SchoolMessagesStore(context: Context) {
    private val file = SchoolSecureFile(context, "school-messages-v1.bin", "sunlith-school-messages-v1", "sunlith-school-messages-v1", MAX_BYTES)
    fun load(): SchoolMessagesCache? = synchronized(LOCK) {
        if (!file.exists()) return@synchronized null
        try { SchoolMessagesCache.read(JSONObject(file.read())) } catch (_: Exception) { clear(); null }
    }
    fun save(cache: SchoolMessagesCache) = synchronized(LOCK) { file.write(cache.json().toString()) }
    fun clear() = synchronized(LOCK) { file.delete() }
    companion object {
        internal val LOCK = Any()
        internal const val MAX_BYTES = 8 * 1024 * 1024
    }
}

internal class SchoolMessagesManager private constructor(private val context: Context) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutex = Mutex()
    private val generation = AtomicLong()
    private val store = SchoolMessagesStore(context)
    private val mutableState = MutableStateFlow(SchoolMessagesState())
    val state: StateFlow<SchoolMessagesState> = mutableState.asStateFlow()
    val inbox = SchoolInbox(context, ::record)
    @Volatile private var cache: SchoolMessagesCache? = null
    @Volatile private var session: Pair<String, SchoolLogin>? = null
    @Volatile private var lastAttempt: Long? = null
    @Volatile private var signInBlockedToken: String? = null
    @Volatile private var active: Job? = null

    init { scope.launch { synchronized(SchoolMessagesStore.LOCK) { cache = current(record()) }; publish(record()) } }

    suspend fun refresh() { scope.async { sync(force = true) }.await() }
    fun refreshSoon() {
        scope.launch { sync(force = false) }
        scope.launch { inbox.refresh(force = false) }
    }
    suspend fun refreshForJob() = coroutineScope {
        launch {
            if (inbox.refresh(force = false).isRetryableInboxFailure()) {
                delay(SCHOOL_JOB_RETRY_DELAY)
                inbox.refresh(force = false)
            }
        }
        sync(force = false, waitForActive = true)
    }

    /** Cache only. Before any successful administration read, notice events already in the calendar are kept as they are. */
    fun notices(record: SchoolRecord, term: SchoolTerm): SchoolNoticeEvents {
        val cached = cached(record)
        val inTerm = { date: LocalDate -> !date.isBefore(term.start) && !date.isAfter(term.end) }
        if (cached?.adminAt == null) return SchoolNoticeEvents(record.baseline.filter { it.kind == SchoolNoticeInterpreter.NOTICE_KIND && inTerm(it.date) }, null)
        val notices = SchoolNoticeInterpreter.interpret(cached.admin)
        val unknownDates = unresolvedNoticeDates(cached.admin, notices, record.baseline)
        val preserved = record.baseline.filter { it.kind == SchoolNoticeInterpreter.NOTICE_KIND && it.date in unknownDates }
        val preservedIds = preserved.map { it.id }.toSet()
        val keepFrom = LocalDate.now(ZoneId.of(record.zone)).minusDays(SchoolScheduleManager.NOTICE_DAYS)
        val items = (SchoolNoticeInterpreter.items(notices).filterNot { it.id in preservedIds } + preserved)
            .filter { inTerm(it.date) && !it.date.isBefore(keepFrom) }
        // An unread or missing source is not a withdrawal. Keep only marks already on each class for that day; a
        // newly arriving class must not gain a cancellation without a read confirmation.
        val lifts = (SchoolNoticeInterpreter.lifts(notices) + unknownDates.map { SchoolClassLift(it, null, it, preserveOnly = true) })
            .filter { inTerm(it.date) }
        return SchoolNoticeEvents(items, lifts)
    }

    private fun unresolvedNoticeDates(admin: List<SchoolAdminNotice>, notices: List<SchoolCollegeNotice>, baseline: List<SchoolItem>): Set<LocalDate> {
        val knownDates = notices.filter { it.detailRead }.map { it.targetDate }.toSet()
        val titles = admin.filter { it.body == null }.map { it.subject }.toSet()
        val previousDates = baseline.filter { item -> item.kind == SchoolNoticeInterpreter.NOTICE_KIND &&
            item.notes.any { note -> titles.any { note == "Objet : $it" } } }.map { it.date }
        val interpretedDates = notices.map { it.targetDate }.toSet()
        val missingDates = baseline.filter {
            it.kind == SchoolNoticeInterpreter.NOTICE_KIND || it.collegeLift.isNotEmpty()
        }.map { it.date }.filterNot { it in interpretedDates }
        return (notices.filter { !it.detailRead }.map { it.targetDate } + previousDates + missingDates)
            .filterNot { it in knownDates }.toSet()
    }

    fun clear() {
        generation.incrementAndGet()
        inbox.clear()
        active?.cancel()
        synchronized(SchoolMessagesStore.LOCK) { store.clear(); cache = null }
        session = null; lastAttempt = null; signInBlockedToken = null
        mutableState.value = SchoolMessagesState()
    }

    suspend fun loadPortalLinks(): SchoolPortalLinks = withContext(Dispatchers.IO) {
        val stamp = generation.get()
        val record = record()?.takeIf { it.disconnecting == null } ?: return@withContext SchoolPortalLinks.Failed(SchoolAdminStatus.NOT_LOADED)
        val client = client(record)
        val result = try {
            SchoolPortalLinks.Loaded(withTimeout(60_000) { withSession(client, record) { token -> client.portalLinks(token) } }.map { SchoolPortalLink(it.first, it.second) })
        } catch (e: Exception) {
            if (e is CancellationException && e !is TimeoutCancellationException) throw e
            diagnose("links", e)
            SchoolPortalLinks.Failed(schoolError(e, SchoolError.UNAVAILABLE).adminStatus())
        }
        if (stillCurrent(record, stamp) != null) {
            if (result is SchoolPortalLinks.Loaded) mutableState.value = mutableState.value.copy(portalLinksAvailable = result.links.isNotEmpty())
            result
        } else SchoolPortalLinks.Failed(SchoolAdminStatus.NOT_LOADED)
    }

    suspend fun resolvePortalLink(code: String): String? = withContext(Dispatchers.IO) {
        val stamp = generation.get()
        val record = record()?.takeIf { it.disconnecting == null } ?: return@withContext null
        val client = client(record)
        val link = withSession(client, record) { token ->
            if (client.portalLinks(token).none { it.first == code }) null else client.portalLink(token, code)
        }
        if (stillCurrent(record, stamp) != null) link else null
    }

    private suspend fun sync(force: Boolean, waitForActive: Boolean = false) {
        if (waitForActive) mutex.lock() else if (!mutex.tryLock()) return
        try {
            val now = SystemClock.elapsedRealtime()
            if (!force && lastAttempt?.let { now - it < BACKGROUND_INTERVAL } == true) return
            val stamp = generation.get()
            val record = record()
            if (record == null || record.disconnecting != null) {
                synchronized(SchoolMessagesStore.LOCK) { store.clear(); cache = null }
                publish(null); return
            }
            if (!force && signInBlockedToken == record.token) return
            lastAttempt = now
            active = kotlinx.coroutines.currentCoroutineContext()[Job]
            mutableState.value = mutableState.value.copy(busy = true)
            var next = synchronized(SchoolMessagesStore.LOCK) { current(record) } ?: SchoolMessagesCache(record.account, record.connection)
            val previous = next
            val client = client(record)
            val previousNotices = SchoolNoticeInterpreter.interpret(next.admin)
            try {
                val zone = ZoneId.of(record.zone)
                val result = withTimeout(120_000) { SchoolClassicPortal(client.base, context).readAdministration(record.username, record.password, next.details, LocalDate.now(zone)) }
                next = if (result.outcome == SchoolAdminStatus.OK) {
                    val details = result.details
                    next.copy(admin = result.rows.map { row ->
                        val d = details[row.id]
                        SchoolAdminNotice(row.id, row.date, row.subject, row.sender, d?.category.orEmpty(), d?.body, row.link != null)
                    }, details = details, adminAt = System.currentTimeMillis(), adminStatus = if (result.detailsStale) SchoolAdminStatus.NETWORK else SchoolAdminStatus.OK).remember()
                } else next.copy(adminStatus = result.outcome)
            } catch (e: Exception) {
                if (e is CancellationException && e !is TimeoutCancellationException) throw e
                diagnose("admin", e)
                next = next.copy(adminStatus = schoolError(e, SchoolError.UNAVAILABLE).adminStatus())
            }
            signInBlockedToken = if (next.adminStatus == SchoolAdminStatus.SIGN_IN) record.token else null
            val arrived = next.arrivals(previous)
            next = next.bounded()
            val committed = synchronized(SchoolMessagesStore.LOCK) {
                val latest = stillCurrent(record, stamp)
                if (latest == null) false
                else {
                    try {
                        store.save(next); cache = next; publish(latest)
                        if (arrived.isNotEmpty()) try { SchoolNotifications(context).messages(latest, arrived) } catch (e: Exception) { diagnose("notification", e) }
                        true
                    }
                    catch (e: Exception) { diagnose("cache", e); mutableState.value = mutableState.value.copy(adminStatus = SchoolAdminStatus.STORAGE); false }
                }
            }
            if (!committed) return
            if (generation.get() == stamp && SchoolNoticeInterpreter.interpret(next.admin) != previousNotices) SchoolScheduleManager.get(context).syncNotices()
        } finally {
            active = null
            mutableState.value = mutableState.value.copy(busy = false)
            mutex.unlock()
        }
    }

    private suspend fun <T> withSession(client: SchoolPortalClient, record: SchoolRecord, block: suspend (String) -> T): T = client.signedIn(
        session?.takeIf { it.first == record.account && it.second.expiresAt > System.currentTimeMillis() + 60_000 }?.second?.token
            ?: record.token.takeIf { record.expiresAt > System.currentTimeMillis() + 60_000 },
        record.username, record.password, { session = record.account to it }, block)

    private fun current(record: SchoolRecord?): SchoolMessagesCache? {
        if (record == null) { store.clear(); return null }
        val loaded = cache ?: store.load()
        if (loaded != null && !loaded.belongsTo(record)) { store.clear(); return null }
        return loaded
    }

    private fun publish(record: SchoolRecord?) {
        val c = if (record == null) null else cached(record)
        if (record == null) { mutableState.value = SchoolMessagesState(busy = mutableState.value.busy); return }
        val notices = SchoolNoticeInterpreter.interpret(c?.admin.orEmpty())
        val today = LocalDate.now(ZoneId.of(record.zone))
        mutableState.value = mutableState.value.copy(
            connected = true,
            admin = c?.admin.orEmpty(), adminStatus = c?.adminStatus ?: SchoolAdminStatus.NOT_LOADED,
            collegeNotices = notices.filter { !it.targetDate.isBefore(today.minusDays(14)) },
            portalUrl = runCatching { SchoolPortalClient.normalize(record.portal).resolve("../login.asp").normalize().toString() }.getOrNull(),
        )
    }

    private fun cached(record: SchoolRecord) = synchronized(SchoolMessagesStore.LOCK) { cache?.takeIf { it.belongsTo(record) } }
    /** The stored connection, while it is still [record]'s and was neither cleared nor disconnected since [stamp]. */
    private fun stillCurrent(record: SchoolRecord, stamp: Long): SchoolRecord? =
        record()?.takeIf { generation.get() == stamp && it.account == record.account && it.connection == record.connection && it.disconnecting == null }
    private fun client(record: SchoolRecord) = SchoolPortalClient(record.portal, language(), context)
    private fun record(): SchoolRecord? = try { SchoolStore(context).current() } catch (_: SchoolFailure) { null }
    private fun language() = context.resources.configuration.locales[0].language
    private fun diagnose(part: String, e: Exception) = Log.w("SunlithSchool", "Messages $part stopped type=${e.javaClass.simpleName}")

    companion object {
        private const val BACKGROUND_INTERVAL = 5 * 60_000L
        @Volatile private var instance: SchoolMessagesManager? = null
        fun get(context: Context): SchoolMessagesManager = instance ?: synchronized(this) {
            instance ?: SchoolMessagesManager(context.applicationContext).also { instance = it }
        }
    }
}
