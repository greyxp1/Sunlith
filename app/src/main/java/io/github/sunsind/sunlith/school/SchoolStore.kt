package io.github.sunsind.sunlith.school

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import java.io.File
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import org.json.JSONArray
import org.json.JSONObject

internal data class SchoolOwned(
    val item: SchoolItem, val rowId: Long, val uid: String, val marker: String, val revision: Map<String, String?>,
    /** Last content this sync wrote, separate from later observations of a row edited in another app. Null in older records. */
    val writtenRevision: Map<String, String?>? = null,
) {
    fun json() = JSONObject().put("item", item.json()).put("row", rowId).put("uid", uid).put("marker", marker)
        .put("revision", JSONObject().also { j -> revision.forEach { (k, v) -> j.put(k, v ?: JSONObject.NULL) } })
        .also { json -> writtenRevision?.let { values -> json.put("writtenRevision", JSONObject().also { j -> values.forEach { (k, v) -> j.put(k, v ?: JSONObject.NULL) } }) } }
    companion object {
        fun read(j: JSONObject): SchoolOwned {
            val r = j.getJSONObject("revision")
            val written = j.optJSONObject("writtenRevision")
            return SchoolOwned(SchoolItem.read(j.getJSONObject("item")), j.getLong("row"), j.getString("uid"), j.getString("marker"),
                r.keys().asSequence().associateWith { if (r.isNull(it)) null else r.getString(it) },
                written?.let { values -> values.keys().asSequence().associateWith { if (values.isNull(it)) null else values.getString(it) } })
        }
    }
}

internal data class SchoolJournal(val after: List<SchoolOwned>, val removed: List<SchoolOwned>, val baseline: List<SchoolItem>, val at: Long, val linked: List<SchoolOwned> = emptyList(), val expected: List<SchoolOwned>? = null, val termKey: String = "", val termName: String = "", val complete: Boolean = true) {
    fun json() = JSONObject().put("after", JSONArray(after.map { it.json() })).put("removed", JSONArray(removed.map { it.json() }))
        .put("baseline", JSONArray(baseline.map { it.json() })).put("at", at).put("linked", JSONArray(linked.map { it.json() }))
        .put("expected", expected?.let { JSONArray(it.map { owned -> owned.json() }) } ?: JSONObject.NULL)
        .put("termKey", termKey).put("termName", termName).put("complete", complete)
    companion object {
        fun read(j: JSONObject) = SchoolJournal(j.getJSONArray("after").owned(), j.getJSONArray("removed").owned(), j.getJSONArray("baseline").items(), j.getLong("at"), j.getJSONArray("linked").owned(), if (j.isNull("expected")) null else j.getJSONArray("expected").owned(), j.optString("termKey", ""), j.optString("termName", ""), j.optBoolean("complete", true))
    }
}

internal data class SchoolRecord(
    val portal: String, val username: String, val password: String, val zone: String, val calendarId: Long,
    val account: String, val connection: String, val token: String, val expiresAt: Long,
    val term: String = "", val baseline: List<SchoolItem> = emptyList(), val ledger: List<SchoolOwned> = emptyList(),
    val lastSuccess: Long? = null, val notifications: SchoolNotificationPreferences = SchoolNotificationPreferences(),
    val lastFailure: String? = null, val journal: SchoolJournal? = null, val calendarIdentity: Map<String, String?> = emptyMap(), val disconnecting: String? = null,
    val termKey: String = "",
    val pendingReview: String? = null,
    val termSource: SchoolTerm? = null,
    /** Whether the current failure streak already reached the connection notification step. Only meaningful while [lastFailure] is set. */
    val failureNotified: Boolean = false,
) {
    /** A first full sync finished. Records keep writing the flag so older versions can still read them. */
    val initialized: Boolean get() = lastSuccess != null
    fun json(): JSONObject = JSONObject().put("version", 1).put("portal", portal).put("username", username).put("password", password)
        .put("zone", zone).put("calendar", calendarId).put("account", account).put("connection", connection).put("token", token).put("expires", expiresAt)
        .put("term", term).put("baseline", JSONArray(baseline.map { it.json() })).put("ledger", JSONArray(ledger.map { it.json() }))
        .put("initialized", initialized).put("success", lastSuccess ?: JSONObject.NULL).put("notify", notifications.enabled)
        .put("categories", JSONArray(notifications.categories.map { it.name })).put("failure", lastFailure ?: JSONObject.NULL).put("journal", journal?.json() ?: JSONObject.NULL)
        .put("administrationCategory", true)
        .put("calendarIdentity", JSONObject().also { j -> calendarIdentity.forEach { (k, v) -> j.put(k, v ?: JSONObject.NULL) } })
        .put("disconnecting", disconnecting ?: JSONObject.NULL)
        .put("termKey", termKey)
        .put("pendingReview", pendingReview ?: JSONObject.NULL)
        .put("termSource", termSource?.let { JSONObject().put("code", it.code).put("name", it.name).put("start", it.start.toString()).put("end", it.end.toString()) } ?: JSONObject.NULL)
        .put("failureNotified", failureNotified)
    companion object {
        fun read(j: JSONObject): SchoolRecord {
            schoolRequire(j.getInt("version") == 1)
            return SchoolRecord(j.getString("portal"), j.getString("username"), j.getString("password"), j.getString("zone"), j.getLong("calendar"),
                j.getString("account"), j.getString("connection"), j.getString("token"), j.getLong("expires"), j.getString("term"),
                j.getJSONArray("baseline").items(), j.getJSONArray("ledger").owned(),
                if (j.isNull("success")) null else j.getLong("success"), SchoolNotificationPreferences(j.getBoolean("notify"),
                    j.getJSONArray("categories").strings().map { SchoolAlert.valueOf(it) }.toSet().let {
                        if (j.optBoolean("administrationCategory", false)) it else it + SchoolAlert.ADMINISTRATION_MESSAGES
                    }),
                if (j.isNull("failure")) null else j.getString("failure"), if (j.isNull("journal")) null else SchoolJournal.read(j.getJSONObject("journal")),
                j.getJSONObject("calendarIdentity").let { r -> r.keys().asSequence().associateWith { if (r.isNull(it)) null else r.getString(it) } },
                if (j.isNull("disconnecting")) null else j.getString("disconnecting"), j.optString("termKey", ""),
                if (j.isNull("pendingReview")) null else j.getString("pendingReview"),
                j.optJSONObject("termSource")?.let { SchoolTerm(it.getString("code"), it.getString("name"), java.time.LocalDate.parse(it.getString("start")), java.time.LocalDate.parse(it.getString("end"))) },
                // Earlier versions alerted on the first failure of every streak.
                j.optBoolean("failureNotified", true))
        }
    }
}
private fun JSONArray.items() = (0 until length()).map { SchoolItem.read(getJSONObject(it)) }
private fun JSONArray.owned() = (0 until length()).map { SchoolOwned.read(getJSONObject(it)) }

internal class SchoolStore(context: Context) {
    private val file = SchoolSecureFile(context, "school-connection-v1.bin", "sunlith-school-v1", "sunlith-school-connection-v1", 12 * 1024 * 1024)
    private val key = context.noBackupFilesDir.path
    /** Reads and decrypts the stored record. */
    fun load(): SchoolRecord? = synchronized(lock) {
        val record = if (!file.exists()) null
            else try { SchoolRecord.read(JSONObject(file.read())) } catch (_: Exception) { memory.remove(key); throw SchoolFailure(SchoolError.STORAGE) }
        record.also { memory[key] = it }
    }
    /**
     * The record as last read or written in this process, for frequent readers such as event annotation and edit checks.
     * Every write goes through this class, so the copy matches the file without decrypting it again.
     */
    fun current(): SchoolRecord? = synchronized(lock) { if (key in memory) memory[key] else load() }
    fun save(record: SchoolRecord) = synchronized(lock) {
        try { file.write(record.json().toString()) } catch (_: Exception) { throw SchoolFailure(SchoolError.STORAGE) }
        memory[key] = record
    }
    fun clear() = synchronized(lock) {
        memory.remove(key)
        file.delete(); if (file.exists()) throw SchoolFailure(SchoolError.STORAGE)
        memory[key] = null
    }
    companion object {
        private val lock = Any()
        private val memory = HashMap<String, SchoolRecord?>()
    }
}

/** An AtomicFile holding one AndroidKeyStore AES-GCM payload: a version byte, the 12 byte IV, then the ciphertext. */
internal class SchoolSecureFile(context: Context, name: String, private val alias: String, aad: String, private val maxBytes: Int) {
    private val file = AtomicFile(File(context.noBackupFilesDir, name))
    private val aad = aad.toByteArray(Charsets.UTF_8)
    fun exists() = file.baseFile.exists() || File(file.baseFile.path + ".bak").exists()
    fun read(): String {
        val data = file.openRead().use { input -> schoolRequire(input.available() <= maxBytes); input.readBytes() }
        schoolRequire(data.size >= 29 && data[0] == 1.toByte())
        val cipher = cipher(Cipher.DECRYPT_MODE, GCMParameterSpec(128, data.copyOfRange(1, 13)))
        return String(cipher.doFinal(data.copyOfRange(13, data.size)), Charsets.UTF_8)
    }
    fun write(text: String) {
        val cipher = cipher(Cipher.ENCRYPT_MODE, null)
        val bytes = byteArrayOf(1) + cipher.iv + cipher.doFinal(text.toByteArray(Charsets.UTF_8))
        schoolRequire(bytes.size <= maxBytes)
        val stream = file.startWrite()
        try { stream.write(bytes); file.finishWrite(stream) } catch (e: Exception) { file.failWrite(stream); throw e }
    }
    fun delete() = file.delete()
    private fun cipher(mode: Int, spec: GCMParameterSpec?) = Cipher.getInstance("AES/GCM/NoPadding").apply {
        if (spec == null) init(mode, key()) else init(mode, key(), spec)
        updateAAD(aad)
    }
    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(alias, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true).build())
        }.generateKey()
    }
}
