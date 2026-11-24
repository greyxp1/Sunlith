package io.github.sunsind.sunlith.school.portal

import android.annotation.SuppressLint
import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.util.Log
import io.github.sunsind.sunlith.school.SchoolError
import io.github.sunsind.sunlith.school.sync.SchoolFailure
import io.github.sunsind.sunlith.school.sync.schoolRequire
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.URI
import javax.net.ssl.HttpsURLConnection
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.ensureActive

private object SchoolPortalNetwork {
    @SuppressLint("InlinedApi")
    fun routes(context: Context): List<(URI) -> HttpsURLConnection> {
        val default: (URI) -> HttpsURLConnection = { it.toURL().openConnection() as HttpsURLConnection }
        val manager = context.getSystemService(ConnectivityManager::class.java) ?: return listOf(default)
        val direct = try {
            val active = manager.activeNetwork
            val vpn = active?.let(manager::getNetworkCapabilities)?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true
            if (!vpn) return listOf(default)
            @Suppress("DEPRECATION")
            manager.allNetworks.mapNotNull { network ->
                val state = manager.getNetworkCapabilities(network) ?: return@mapNotNull null
                val usable = listOf(NetworkCapabilities.NET_CAPABILITY_INTERNET, NetworkCapabilities.NET_CAPABILITY_VALIDATED,
                    NetworkCapabilities.NET_CAPABILITY_NOT_RESTRICTED, NetworkCapabilities.NET_CAPABILITY_NOT_VPN).all(state::hasCapability) &&
                    !state.hasCapability(NetworkCapabilities.NET_CAPABILITY_CAPTIVE_PORTAL) &&
                    !state.hasTransport(NetworkCapabilities.TRANSPORT_VPN) &&
                    // NOT_SUSPENDED is an inlined key; pre-28 networks are assumed unsuspended without querying it.
                    (Build.VERSION.SDK_INT < 28 || state.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_SUSPENDED))
                if (!usable) return@mapNotNull null
                val priority = when {
                    state.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) || state.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> 0
                    state.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> 1
                    else -> return@mapNotNull null
                }
                priority to network
            }.minByOrNull { it.first }?.second
        } catch (_: SecurityException) { null }
        if (direct == null) return listOf(default)
        // Network.openConnection scopes both DNS and sockets to this phone network. Android still enforces VPN lockdown.
        return listOf({ uri -> direct.openConnection(uri.toURL()) as HttpsURLConnection }, default)
    }
}

/**
 * HTTPS exchanges over [SchoolPortalNetwork.routes], shared by the mobile API and the classic web portal. Changing the route keeps
 * the same HTTPS URL and normal certificate and hostname verification. After a transport failure the fallback stays selected for
 * the rest of this client, rather than repeating a failed route. On the last route an IOException is rethrown and a
 * SecurityException becomes NETWORK. Status codes and bodies are left to [exchange]'s caller.
 */
internal class SchoolHttps(context: Context, private val label: String) {
    private val routes = SchoolPortalNetwork.routes(context)
    private var route = 0

    suspend fun <T> exchange(uri: URI, form: String?, formType: String, setup: (HttpsURLConnection) -> Unit, handle: suspend (HttpsURLConnection, Int) -> T): T {
        while (true) {
            try { return exchangeOn(routes[route](uri), form, formType, setup, handle) } catch (e: IOException) {
                fallBack(e) { throw e }
            } catch (e: SecurityException) {
                fallBack(e) { throw SchoolFailure(SchoolError.NETWORK) }
            }
        }
    }

    private suspend inline fun fallBack(error: Exception, last: () -> Nothing) {
        coroutineContext.ensureActive()
        Log.w("SunlithSchool", "$label ${error.javaClass.simpleName} route=${if (routes.size > 1 && route == 0) "direct" else "default"}")
        if (route == routes.lastIndex) last()
        route++
    }

    private suspend fun <T> exchangeOn(connection: HttpsURLConnection, form: String?, formType: String, setup: (HttpsURLConnection) -> Unit,
        handle: suspend (HttpsURLConnection, Int) -> T): T {
        try {
            connection.instanceFollowRedirects = false
            connection.connectTimeout = 15_000
            connection.readTimeout = 20_000
            connection.useCaches = false
            setup(connection)
            if (form != null) {
                connection.requestMethod = "POST"
                connection.doOutput = true
                connection.setRequestProperty("Content-Type", formType)
                val bytes = form.toByteArray(Charsets.UTF_8)
                connection.setFixedLengthStreamingMode(bytes.size)
                connection.outputStream.use { it.write(bytes) }
            }
            return handle(connection, connection.responseCode)
        } finally { connection.disconnect() }
    }

    companion object {
        /** Reads within 30 seconds, enforcing the byte limit when one is supplied. */
        suspend fun body(connection: HttpsURLConnection, maxBytes: Int?): ByteArray {
            if (maxBytes != null) schoolRequire(connection.contentLengthLong <= maxBytes)
            val bytes = ByteArrayOutputStream()
            connection.inputStream.use { input ->
                val buffer = ByteArray(8192)
                val deadline = System.nanoTime() + 30_000_000_000L
                while (true) {
                    coroutineContext.ensureActive()
                    if (System.nanoTime() > deadline) throw SocketTimeoutException("School response timed out")
                    val count = input.read(buffer)
                    if (count < 0) break
                    if (maxBytes != null) schoolRequire(count <= maxBytes - bytes.size())
                    bytes.write(buffer, 0, count)
                }
            }
            return bytes.toByteArray()
        }
    }
}
