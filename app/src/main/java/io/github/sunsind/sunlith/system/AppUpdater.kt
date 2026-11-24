package io.github.sunsind.sunlith.system

import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.content.pm.Signature
import android.os.Build
import androidx.core.content.pm.PackageInfoCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.URI
import java.net.URISyntaxException
import java.security.MessageDigest
import java.util.Locale
import javax.net.ssl.HttpsURLConnection
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal const val APK_MIME = "application/vnd.android.package-archive"
private const val OCTET_MIME = "application/octet-stream"
private const val APP_PACKAGE = "io.github.sunsind.sunlith"
private const val REPOSITORY = "SunSinD/Sunlith"
private const val SUMS_NAME = "SHA256SUMS.txt"
private const val MAX_METADATA = 1L shl 20
private const val MAX_SUMS = 16L * 1024
private const val MAX_APK = 64L shl 20
private const val SPACE_MARGIN = 16L shl 20
private const val MAX_REDIRECTS = 4

internal enum class UpdateProblem { NETWORK, RATE_LIMITED, NOT_FOUND, NOT_VERIFIED, STORAGE, NO_INSTALLER }

private class UpdateFailure(val problem: UpdateProblem) : Exception(problem.name)

private fun unverified() = UpdateFailure(UpdateProblem.NOT_VERIFIED)

private data class UpdateVersion(val major: Int, val minor: Int, val patch: Int) : Comparable<UpdateVersion> {
    override fun compareTo(other: UpdateVersion) = compareValuesBy(this, other, { it.major }, { it.minor }, { it.patch })
    override fun toString() = "$major.$minor.$patch"

    companion object {
        private val pattern = Regex("(0|[1-9][0-9]{0,5})\\.(0|[1-9][0-9]{0,5})\\.(0|[1-9][0-9]{0,5})")
        fun parse(text: String?) = text?.let(pattern::matchEntire)?.groupValues?.let { (_, major, minor, patch) ->
            UpdateVersion(major.toInt(), minor.toInt(), patch.toInt())
        }
    }
}

private class UpdateAsset(val name: String, val url: URI, val size: Long, val sha256: String)

private class UpdateRelease(val version: UpdateVersion, val apk: UpdateAsset, val sums: UpdateAsset)

private class PendingUpdate(val version: UpdateVersion, val versionCode: Long, val size: Long, val sha256: String)

private class PackageFacts(
    val name: String, val versionName: String?, val versionCode: Long,
    val signers: Set<String>, val signerHistory: Set<String>, val multipleSigners: Boolean,
)

private val sha256Hex = Regex("[0-9a-fA-F]{64}")
private val sumsLine = Regex("([0-9a-fA-F]{64}) [ *](\\S(?:.*\\S)?)")

private fun apkName(version: UpdateVersion) = "Sunlith-v$version.apk"

private fun mime(type: String?) = type?.substringBefore(';')?.trim()?.lowercase(Locale.ROOT)

/** Returns null when the latest stable release is not newer than [installed]; its assets are then not inspected. */
private fun parseRelease(bytes: ByteArray, installed: UpdateVersion?): UpdateRelease? {
    val root = releaseJson.parseStrictJson(bytes, 1_000, ::unverified) as? Map<*, *> ?: throw unverified()
    if (root["draft"] != false || root["prerelease"] != false) throw unverified()
    val version = UpdateVersion.parse((root["tag_name"] as? String)?.takeIf { it.startsWith("v") }?.substring(1)) ?: throw unverified()
    if (installed != null && version <= installed) return null
    val assets = root["assets"] as? List<*> ?: throw unverified()

    fun asset(name: String, max: Long, types: Set<String>): UpdateAsset {
        val matches = assets.filterIsInstance<Map<*, *>>().filter { it["name"] == name }
        if (matches.isEmpty()) throw UpdateFailure(UpdateProblem.NOT_FOUND)
        val asset = matches.singleOrNull() ?: throw unverified()
        if (asset["state"] != "uploaded") throw UpdateFailure(UpdateProblem.NOT_FOUND)
        val size = asset["size"] as? Long
        val url = "https://github.com/$REPOSITORY/releases/download/v$version/$name"
        val digest = (asset["digest"] as? String)?.takeIf { it.startsWith("sha256:") }?.substring(7)
        if (size == null || size !in 1..max || mime(asset["content_type"] as? String) !in types ||
            asset["browser_download_url"] != url || digest == null || !sha256Hex.matches(digest)) throw unverified()
        return UpdateAsset(name, URI(url), size, digest.lowercase(Locale.ROOT))
    }
    return UpdateRelease(version, asset(apkName(version), MAX_APK, setOf(APK_MIME, OCTET_MIME)),
        asset(SUMS_NAME, MAX_SUMS, setOf("text/plain", OCTET_MIME)))
}

private fun checksumFor(bytes: ByteArray, name: String): String {
    val text = strictUtf8(bytes) ?: throw unverified()
    val found = text.split('\n').map { it.removeSuffix("\r") }.filter { it.isNotBlank() }.mapNotNull { line ->
        val match = sumsLine.matchEntire(line) ?: throw unverified()
        if (match.groupValues[2] == name) match.groupValues[1].lowercase(Locale.ROOT) else null
    }
    return found.singleOrNull() ?: throw unverified()
}

private val releaseJson = strictJsonFactory(16, 512 * 1024, 256, MAX_METADATA)

private enum class Source(val host: String, val redirects: Set<String>, val seconds: Long, val headers: Map<String, String>) {
    API("api.github.com", setOf("api.github.com"), 30,
        mapOf("Accept" to "application/vnd.github+json", "X-GitHub-Api-Version" to "2022-11-28")),
    ASSET("github.com", setOf("github.com", "release-assets.githubusercontent.com", "objects.githubusercontent.com"), 600,
        mapOf("Accept" to OCTET_MIME, "Accept-Encoding" to "identity")),
}

/** Lets another coroutine disconnect the current connection, since blocking reads ignore cancellation. */
private class Deadline(seconds: Long) {
    private val end = System.nanoTime() + seconds * 1_000_000_000
    private var closed = false
    private var connection: HttpsURLConnection? = null

    fun remainingMillis(): Int {
        val left = (end - System.nanoTime()) / 1_000_000
        if (left <= 0) throw UpdateFailure(UpdateProblem.NETWORK)
        return left.toInt()
    }

    fun bind(next: HttpsURLConnection) {
        val rejected = synchronized(this) { if (!closed) connection = next; closed }
        if (rejected) { next.disconnect(); throw UpdateFailure(UpdateProblem.NETWORK) }
    }

    fun close() = synchronized(this) { closed = true; connection }?.disconnect()
}

private suspend inline fun <T> network(block: () -> T): T = try { block() } catch (_: IOException) {
    currentCoroutineContext().ensureActive()
    throw UpdateFailure(UpdateProblem.NETWORK)
}

private suspend fun read(uri: URI, source: Source, max: Long): ByteArray =
    ByteArrayOutputStream().also { out -> fetch(uri, source, max, exact = false) { buffer, count -> out.write(buffer, 0, count) } }.toByteArray()

private suspend fun fetch(start: URI, source: Source, max: Long, exact: Boolean, sink: (ByteArray, Int) -> Unit): Unit = coroutineScope {
    val deadline = Deadline(source.seconds)
    // Registered before connecting, so cancellation or the overall deadline also closes a socket stuck in a read.
    val closer = launch(Dispatchers.IO, start = CoroutineStart.UNDISPATCHED) {
        try { delay(source.seconds * 1_000) } finally { deadline.close() }
    }
    try {
        val connection = connect(start, source, deadline)
        val length = connection.contentLengthLong
        if (mime(connection.contentType) == "text/html") throw UpdateFailure(UpdateProblem.NETWORK)
        if (length > max || exact && length >= 0 && length != max) throw unverified()
        val body = network { connection.inputStream }
        val buffer = ByteArray(64 * 1024)
        var total = 0L
        while (true) {
            deadline.remainingMillis()
            val count = network { body.read(buffer) }
            ensureActive()
            if (count < 0) break
            if (total + count > max) throw unverified()
            sink(buffer, count)
            total += count
        }
        if (exact && total != max) throw UpdateFailure(UpdateProblem.NETWORK)
    } finally {
        closer.cancel()
        deadline.close()
    }
}

/** Follows a few HTTPS redirects, and only between the GitHub hosts that serve this release. */
private suspend fun connect(start: URI, source: Source, deadline: Deadline): HttpsURLConnection {
    var uri = start
    var hosts = setOf(source.host)
    repeat(MAX_REDIRECTS + 1) {
        if (!uri.isAbsolute || uri.scheme != "https" || uri.rawUserInfo != null || (uri.port != -1 && uri.port != 443) ||
            uri.host?.lowercase(Locale.ROOT) !in hosts) throw unverified()
        currentCoroutineContext().ensureActive()
        val connection = network { uri.toURL().openConnection() } as HttpsURLConnection
        deadline.bind(connection)
        connection.instanceFollowRedirects = false
        connection.connectTimeout = minOf(15_000, deadline.remainingMillis())
        connection.readTimeout = minOf(20_000, deadline.remainingMillis())
        connection.useCaches = false
        connection.setRequestProperty("User-Agent", "Sunlith-Android")
        for ((name, value) in source.headers) connection.setRequestProperty(name, value)
        val status = network { connection.responseCode }
        deadline.remainingMillis()
        when (status) {
            in 200..299 -> return connection
            301, 302, 303, 307, 308 -> {
                val location = connection.getHeaderField("Location") ?: throw unverified()
                connection.disconnect()
                uri = try { uri.resolve(URI(location)) } catch (_: URISyntaxException) { throw unverified() }
                hosts = source.redirects
            }
            else -> throw UpdateFailure(when (status) {
                403, 429 -> UpdateProblem.RATE_LIMITED
                404, 410 -> UpdateProblem.NOT_FOUND
                else -> UpdateProblem.NETWORK
            })
        }
    }
    throw unverified()
}

private fun sha256(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).toHexString()

private fun sha256(file: File) = MessageDigest.getInstance("SHA-256").run {
    file.forEachBlock { buffer, count -> update(buffer, 0, count) }
    digest().toHexString()
}

/** Throws unless [candidate] is this app at [expected], signed like the installed app or by its rotated successor. */
private fun isNewer(installed: PackageFacts, candidate: PackageFacts, expected: UpdateVersion): Boolean {
    val signed = installed.signers.isNotEmpty() && candidate.signers.isNotEmpty() && (installed.signers == candidate.signers ||
        !installed.multipleSigners && !candidate.multipleSigners && installed.signers.single() in candidate.signerHistory)
    if (installed.name != APP_PACKAGE || candidate.name != APP_PACKAGE || !signed || candidate.versionName != expected.toString()) throw unverified()
    return candidate.versionCode > installed.versionCode
}

/** Manual updater. Nothing here touches the network unless the user asks, and [restore] and [cleanUpAfterReplace] stay offline. */
@Suppress("DEPRECATION")
private class AppUpdater(context: Context) {
    private val packages = context.packageManager
    private val packageName = context.packageName
    private val dir = File(context.cacheDir, "app-update")
    private val prefs = context.getSharedPreferences("app_update", Context.MODE_PRIVATE)
    private val signingFlags = if (Build.VERSION.SDK_INT >= 28) PackageManager.GET_SIGNING_CERTIFICATES else PackageManager.GET_SIGNATURES

    fun apkFile(version: UpdateVersion) = File(dir, apkName(version))

    fun canInstall() = packages.canRequestPackageInstalls()

    fun installedAtLeast(pending: PendingUpdate) = installed().versionCode >= pending.versionCode

    suspend fun check(): UpdateRelease? = withContext(Dispatchers.IO) {
        val current = UpdateVersion.parse(installed().versionName)
        val bytes = read(URI("https://api.github.com/repos/$REPOSITORY/releases/latest"), Source.API, MAX_METADATA)
        parseRelease(bytes, current).also { if (it == null) discard() }
    }

    /** Returns null when the verified APK turns out not to be newer than the installed build. */
    suspend fun download(release: UpdateRelease, progress: (Long) -> Unit): PendingUpdate? = withContext(Dispatchers.IO) {
        val current = installed()
        discard()
        if (!dir.isDirectory && !dir.mkdirs() || dir.usableSpace < release.apk.size + SPACE_MARGIN) throw UpdateFailure(UpdateProblem.STORAGE)
        val sums = read(release.sums.url, Source.ASSET, release.sums.size)
        if (sums.size.toLong() != release.sums.size || sha256(sums) != release.sums.sha256 ||
            checksumFor(sums, release.apk.name) != release.apk.sha256) throw unverified()

        val temp = File(dir, release.apk.name + ".download")
        val target = apkFile(release.version)
        var kept = false
        try {
            val digest = MessageDigest.getInstance("SHA-256")
            try {
                FileOutputStream(temp).use { out ->
                    var total = 0L
                    fetch(release.apk.url, Source.ASSET, release.apk.size, exact = true) { buffer, count ->
                        out.write(buffer, 0, count)
                        digest.update(buffer, 0, count)
                        total += count
                        progress(total)
                    }
                    out.fd.sync()
                }
            } catch (_: IOException) {
                ensureActive()
                throw UpdateFailure(UpdateProblem.STORAGE)
            }
            if (digest.digest().toHexString() != release.apk.sha256) throw unverified()
            if (!temp.renameTo(target)) throw UpdateFailure(UpdateProblem.STORAGE)
            ensureActive()
            val candidate = archive(target) ?: throw unverified()
            if (!isNewer(current, candidate, release.version)) return@withContext null
            PendingUpdate(release.version, candidate.versionCode, release.apk.size, release.apk.sha256).also { pending ->
                prefs.edit().putString("version", pending.version.toString()).putLong("versionCode", pending.versionCode)
                    .putLong("size", pending.size).putString("sha256", pending.sha256).commit()
                kept = true
            }
        } finally {
            temp.delete()
            if (!kept) target.delete()
        }
    }

    /** Re-verifies the saved download from disk before it can be reused. */
    suspend fun restore(): PendingUpdate? = withContext(Dispatchers.IO) {
        load()?.takeIf { pending ->
            val file = apkFile(pending.version)
            runCatching {
                file.isFile && file.length() == pending.size && sha256(file) == pending.sha256 &&
                    archive(file)?.let { isNewer(installed(), it, pending.version) && it.versionCode == pending.versionCode } == true
            }.getOrDefault(false)
        }.also { if (it == null) discard() }
    }

    /** Runs after Android replaces the app, so it only compares versions without hashing. */
    fun cleanUpAfterReplace() {
        val pending = load()
        if (pending == null || runCatching { installedAtLeast(pending) }.getOrDefault(false)) discard()
    }

    fun discard() {
        prefs.edit().clear().commit()
        dir.listFiles()?.forEach { if (it.isFile) it.delete() }
    }

    private fun load(): PendingUpdate? {
        val version = UpdateVersion.parse(prefs.getString("version", null)) ?: return null
        val sha = prefs.getString("sha256", null)?.takeIf { it.length == 64 } ?: return null
        return PendingUpdate(version, prefs.getLong("versionCode", 0), prefs.getLong("size", 0), sha)
    }

    private fun installed() = facts(packages.getPackageInfo(packageName, signingFlags)) ?: throw unverified()

    // Some Android versions only fill in an archive's signing info when GET_SIGNATURES is also set.
    private fun archive(file: File) =
        runCatching { packages.getPackageArchiveInfo(file.path, signingFlags or PackageManager.GET_SIGNATURES)?.let(::facts) }.getOrNull()

    private fun facts(info: PackageInfo): PackageFacts? {
        val current: Array<Signature>?
        val history: Array<Signature>?
        val multiple: Boolean
        if (Build.VERSION.SDK_INT >= 28) {
            val signing = info.signingInfo ?: return null
            multiple = signing.hasMultipleSigners()
            current = signing.apkContentsSigners
            history = if (multiple) current else signing.signingCertificateHistory
        } else {
            current = info.signatures
            multiple = (current?.size ?: 0) > 1
            history = current
        }
        fun hashes(signatures: Array<Signature>?) = signatures.orEmpty().map { sha256(it.toByteArray()) }.toSet()
        return PackageFacts(info.packageName ?: return null, info.versionName, PackageInfoCompat.getLongVersionCode(info),
            hashes(current), hashes(history), multiple)
    }
}

internal sealed interface AppUpdateState {
    data object Idle : AppUpdateState
    data object Checking : AppUpdateState
    data object UpToDate : AppUpdateState
    data class Available(val version: String) : AppUpdateState
    data class Downloading(val version: String, val progress: Float) : AppUpdateState
    data class Preparing(val version: String) : AppUpdateState
    data class NeedsPermission(val version: String) : AppUpdateState
    data class Ready(val version: String) : AppUpdateState
    data class Installing(val version: String) : AppUpdateState
    data class Failed(val problem: UpdateProblem) : AppUpdateState
}

internal sealed interface UpdateCommand {
    data object OpenPermission : UpdateCommand
    data class OpenInstaller(val file: File) : UpdateCommand
}

/**
 * Holds the manual update flow. Creating it only re-checks a saved download, and network work starts from [tap].
 * Activity launches are handed to the visible UI as one-shot [command]s and are never repeated on resume.
 */
class AppUpdateViewModel(application: Application) : AndroidViewModel(application) {
    private val updater = AppUpdater(application)
    private val mutableState = MutableStateFlow<AppUpdateState>(AppUpdateState.Idle)
    internal val state = mutableState.asStateFlow()
    private val mutableCommand = MutableStateFlow<UpdateCommand?>(null)
    internal val command = mutableCommand.asStateFlow()
    private var job: Job? = null
    private var release: UpdateRelease? = null
    private var pending: PendingUpdate? = null
    private var attached = 0

    init {
        step {
            val restored = updater.restore()
            if (restored != null && mutableState.value == AppUpdateState.Idle) {
                pending = restored
                mutableState.value = AppUpdateState.Ready(restored.version.toString())
            }
        }
    }

    internal fun attach() { attached++ }

    internal fun detach() {
        attached = (attached - 1).coerceAtLeast(0)
        if (attached > 0) return
        val dropped = mutableCommand.value
        mutableCommand.value = null
        val current = mutableState.value
        if (dropped is UpdateCommand.OpenInstaller && current is AppUpdateState.Installing) mutableState.value = AppUpdateState.Ready(current.version)
    }

    /** Does whatever the row offers in the current state; a tap while downloading cancels. */
    internal fun tap() {
        val current = mutableState.value
        if (job?.isActive == true && current !is AppUpdateState.Downloading && current !is AppUpdateState.Available) return
        when (current) {
            AppUpdateState.Idle, AppUpdateState.UpToDate -> check()
            is AppUpdateState.Failed -> if (pending == null) check() else step {
                pending = updater.restore()
                mutableState.value = pending?.let { AppUpdateState.Ready(it.version.toString()) } ?: AppUpdateState.Failed(UpdateProblem.NOT_VERIFIED)
            }
            is AppUpdateState.Available -> release?.let(::download)
            is AppUpdateState.Downloading -> {
                job?.cancel()
                mutableState.value = AppUpdateState.Available(current.version)
            }
            is AppUpdateState.Ready, is AppUpdateState.NeedsPermission, is AppUpdateState.Installing -> install()
            AppUpdateState.Checking, is AppUpdateState.Preparing -> Unit
        }
    }

    private fun check() {
        mutableState.value = AppUpdateState.Checking
        step {
            val found = updater.check()
            release = found
            if (found == null) pending = null
            mutableState.value = if (found == null) AppUpdateState.UpToDate else AppUpdateState.Available(found.version.toString())
        }
    }

    private fun download(target: UpdateRelease) {
        val version = target.version.toString()
        val total = target.apk.size
        mutableState.value = AppUpdateState.Downloading(version, 0f)
        step {
            var shown = -1L
            val result = try {
                updater.download(target) { bytes ->
                    val percent = bytes * 100 / total
                    if (percent != shown) {
                        shown = percent
                        val next = if (bytes >= total) AppUpdateState.Preparing(version) else AppUpdateState.Downloading(version, percent / 100f)
                        mutableState.update { if (it is AppUpdateState.Downloading || it is AppUpdateState.Preparing) next else it }
                    }
                }
            } catch (e: CancellationException) {
                withContext(NonCancellable) { discard() }
                throw e
            }
            if (result == null) {
                release = null
                mutableState.value = AppUpdateState.UpToDate
            } else {
                pending = result
                mutableState.value = AppUpdateState.Ready(version)
                if (attached > 0) beginInstall(result, openPermission = false)
            }
        }
    }

    /** Re-checks the saved file, then asks Android for permission or opens its installer. Installing allows a manual reopen. */
    private fun install() {
        val saved = pending ?: return
        mutableState.value = AppUpdateState.Preparing(saved.version.toString())
        step {
            val checked = updater.restore()
            if (checked != null) beginInstall(checked, openPermission = true)
            else {
                pending = null
                mutableState.value = if (updater.installedAtLeast(saved)) AppUpdateState.UpToDate else AppUpdateState.Failed(UpdateProblem.NOT_VERIFIED)
            }
        }
    }

    private fun beginInstall(saved: PendingUpdate, openPermission: Boolean) {
        val version = saved.version.toString()
        if (!updater.canInstall()) {
            mutableState.value = AppUpdateState.NeedsPermission(version)
            if (openPermission && attached > 0) mutableCommand.value = UpdateCommand.OpenPermission
        } else if (attached > 0) {
            mutableState.value = AppUpdateState.Installing(version)
            mutableCommand.value = UpdateCommand.OpenInstaller(updater.apkFile(saved.version))
        } else mutableState.value = AppUpdateState.Ready(version)
    }

    /** The UI calls this right before it starts the activity, so the same command is never launched twice. */
    internal fun consume(command: UpdateCommand): Boolean {
        if (mutableCommand.value != command) return false
        mutableCommand.value = null
        return true
    }

    internal fun onPermissionReturned() {
        val saved = pending ?: return
        if (mutableState.value is AppUpdateState.NeedsPermission && updater.canInstall()) beginInstall(saved, openPermission = false)
    }

    /** Android's result code says nothing reliable here; only the installed version can show the update worked. */
    internal fun onInstallerReturned() {
        val saved = pending ?: return
        if (mutableState.value !is AppUpdateState.Installing) return
        if (runCatching { updater.installedAtLeast(saved) }.getOrDefault(false)) {
            pending = null
            mutableState.value = AppUpdateState.UpToDate
            step { discard() }
        } else mutableState.value = AppUpdateState.Ready(saved.version.toString())
    }

    internal fun onLaunchFailed() {
        mutableState.value = AppUpdateState.Failed(UpdateProblem.NO_INSTALLER)
    }

    private suspend fun discard() = withContext(Dispatchers.IO) { updater.discard() }

    private fun step(block: suspend () -> Unit) {
        val previous = job
        job = viewModelScope.launch {
            previous?.cancelAndJoin()
            try { block() } catch (e: CancellationException) { throw e } catch (e: Exception) {
                ensureActive()
                mutableState.value = AppUpdateState.Failed((e as? UpdateFailure)?.problem ?: UpdateProblem.NETWORK)
            }
        }
    }
}

class AppUpdateCleanupReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        val result = goAsync()
        val app = context.applicationContext
        Thread {
            runCatching { AppUpdater(app).cleanUpAfterReplace() }
            result.finish()
        }.start()
    }
}
