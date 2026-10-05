package com.gh00ul.budget

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.security.KeyStore
import java.time.LocalDate
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

// Bank sync through the ClearBudget bank server (gh00ul/clearbudget-personal, bank-sync/). That server holds
// the Plaid keys and the bank connection. This app only keeps the server's address, its access key
// (encrypted with a key that never leaves the phone's keystore), and the last couple of months of
// transactions.

class BankException(message: String) : Exception(message)

class BankAccount(
    val id: String,
    val name: String,
    val mask: String?,
    val type: String, // "depository", "credit", ...
    val subtype: String?, // "checking", "credit card", ...
    val institution: String?,
    val current: Double?,
    val available: Double?,
) {
    val isChecking get() = type == "depository" && subtype == "checking"
    val isCredit get() = type == "credit"

    // "Checking ····1234"
    val label get() = name + (mask?.let { " ····$it" } ?: "")
}

// One bank or card transaction. Money in is positive, spending is negative.
class BankTxn(
    val id: String,
    val account: String,
    val date: LocalDate,
    val name: String,
    val amount: Double,
    val pending: Boolean,
    val category: String, // "Food And Drink", "Transfer Out", "Income", ...
    val detail: String, // "Transfer Out Withdrawal", "Income Wages", ...
)

class BankSnapshot(val accounts: List<BankAccount>, val txns: List<BankTxn>, val errors: List<String>)

// Talks to the bank server. Every call blocks, so run it off the main thread.
object BankServer {
    private const val MAX_BYTES = 16 * 1024 * 1024
    private const val KEEP_DAYS = 70L // older transactions aren't needed

    // The server's last bank data, without asking Plaid for anything new.
    fun snapshot(url: String, key: String): BankSnapshot = parse(request(url, key, "GET", "/v1/snapshot", null))

    // Asks Plaid for anything new, then returns everything. The server handles two bank logins per call.
    fun sync(url: String, key: String): BankSnapshot {
        var after: String? = null
        val errors = mutableListOf<String>()
        repeat(20) {
            val response = request(url, key, "POST", "/v1/sync", JSONObject().apply { after?.let { put("start_after_item_id", it) } })
            val sync = response.optJSONObject("sync")
            sync?.optJSONArray("errors")?.let { list ->
                for (i in 0 until list.length()) list.optJSONObject(i)?.optString("message")?.takeIf { it.isNotBlank() }?.let { errors += it }
            }
            val next = if (sync == null || sync.isNull("next_item_id")) null
            else sync.optString("next_item_id").takeIf { it.isNotBlank() && it != after }
            if (next == null) return parse(response).let { BankSnapshot(it.accounts, it.txns, errors.distinct()) }
            after = next
        }
        throw BankException("The bank server kept asking for more. Try again later.")
    }

    // https only, except a server on the phone itself (for testing).
    fun cleanUrl(raw: String): String? {
        val uri = runCatching { URI(raw.trim()) }.getOrNull() ?: return null
        val host = uri.host ?: return null
        val local = host == "localhost" || host == "127.0.0.1"
        val ok = (uri.scheme.equals("https", true) || (local && uri.scheme.equals("http", true))) &&
            uri.rawUserInfo == null && uri.rawQuery == null && uri.rawFragment == null
        return if (ok) uri.toString().trimEnd('/') else null
    }

    private fun request(base: String, key: String, method: String, path: String, body: JSONObject?): JSONObject {
        val conn = URL(base + path).openConnection() as HttpURLConnection
        try {
            conn.requestMethod = method
            conn.connectTimeout = 15_000
            conn.readTimeout = 60_000
            conn.instanceFollowRedirects = false
            conn.setRequestProperty("Accept", "application/json")
            conn.setRequestProperty("Authorization", "Bearer $key")
            if (body != null) {
                conn.doOutput = true
                conn.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                conn.outputStream.use { it.write(body.toString().toByteArray()) }
            }
            val status = conn.responseCode
            val text = (if (status in 200..299) conn.inputStream else conn.errorStream)?.use { readLimited(it) }.orEmpty()
            if (status !in 200..299) {
                val message = runCatching { JSONObject(text).optJSONObject("error")?.optString("message") }.getOrNull()
                throw BankException(when (status) {
                    401, 403 -> "The access key was rejected. Check it in Settings."
                    404 -> "That address isn't a bank server. Check it in Settings."
                    429 -> "The bank server is busy. Try again in a minute."
                    else -> message?.takeIf { it.isNotBlank() } ?: "The bank server had a problem ($status)."
                })
            }
            return runCatching { JSONObject(text) }.getOrElse { throw BankException("The bank server sent something unreadable.") }
        } catch (e: IOException) {
            throw BankException("Couldn't reach the bank server. Check your connection.")
        } finally {
            conn.disconnect()
        }
    }

    private fun readLimited(stream: InputStream): String {
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        while (true) {
            val n = stream.read(buffer)
            if (n < 0) break
            if (out.size() + n > MAX_BYTES) throw BankException("The bank server sent too much data.")
            out.write(buffer, 0, n)
        }
        return out.toString("UTF-8")
    }

    private fun parse(json: JSONObject): BankSnapshot {
        val accounts = json.optJSONArray("accounts") ?: throw BankException("The bank server sent no accounts.")
        val txns = json.optJSONArray("transactions") ?: JSONArray()
        val cutoff = LocalDate.now().minusDays(KEEP_DAYS)
        return BankSnapshot(
            (0 until accounts.length()).mapNotNull { accounts.optJSONObject(it)?.let(::accountFromJson) },
            (0 until txns.length()).mapNotNull { i ->
                txns.optJSONObject(i)?.let(::txnFromServer)?.takeIf { !it.date.isBefore(cutoff) }
            },
            emptyList(),
        )
    }

    private fun txnFromServer(t: JSONObject): BankTxn? = runCatching {
        BankTxn(
            id = t.getString("external_id"),
            account = t.getString("account_external_id"),
            date = LocalDate.parse(t.getString("date")),
            name = t.optString("merchant").takeIf { !t.isNull("merchant") && it.isNotBlank() } ?: t.optString("name", "Transaction"),
            amount = t.getLong("amount_cents") / 100.0,
            pending = t.optBoolean("pending"),
            category = t.optString("category").takeIf { !t.isNull("category") }.orEmpty(),
            detail = t.optString("category_detail").takeIf { !t.isNull("category_detail") }.orEmpty(),
        )
    }.getOrNull()
}

private fun JSONObject.text(key: String) = optString(key).takeIf { has(key) && !isNull(key) && it.isNotBlank() }
private fun JSONObject.cents(key: String) = if (has(key) && !isNull(key)) optLong(key) / 100.0 else null

fun accountFromJson(a: JSONObject): BankAccount? {
    val id = a.text("external_id") ?: return null
    return BankAccount(
        id, a.text("name") ?: "Account", a.text("mask"), a.text("type") ?: "other", a.text("subtype"),
        a.text("institution_name"), a.cents("current_balance_cents"), a.cents("available_balance_cents"),
    )
}

fun accountToJson(a: BankAccount): JSONObject = JSONObject()
    .put("external_id", a.id).put("name", a.name).put("mask", a.mask).put("type", a.type).put("subtype", a.subtype)
    .put("institution_name", a.institution)
    .put("current_balance_cents", a.current?.let { Math.round(it * 100) })
    .put("available_balance_cents", a.available?.let { Math.round(it * 100) })

// What's kept on the phone ("bank" preferences, left out of backups).
class BankStore(context: Context) {
    private val prefs = context.getSharedPreferences("bank", Context.MODE_PRIVATE)

    val url: String? get() = prefs.getString("url", null)
    val accountId: String? get() = prefs.getString("account", null) // the checking account the balance comes from
    val isLinked get() = url != null && accountId != null && prefs.contains("key")
    val checkedAt: Long get() = prefs.getLong("checked_at", 0L) // last good sync, epoch ms
    val error: String? get() = prefs.getString("error", null)

    // Read once, then kept in memory (the screen redraws often).
    private var cachedAccounts: List<BankAccount>? = null
    private var cachedTxns: List<BankTxn>? = null
    private var cachedOverrides: Map<String, String>? = null

    val accounts: List<BankAccount>
        get() = cachedAccounts ?: runCatching {
            val list = JSONArray(prefs.getString("accounts", "[]"))
            (0 until list.length()).mapNotNull { accountFromJson(list.getJSONObject(it)) }
        }.getOrDefault(emptyList()).also { cachedAccounts = it }

    val account: BankAccount? get() = accounts.firstOrNull { it.id == accountId }

    val txns: List<BankTxn>
        get() = cachedTxns ?: runCatching {
            val list = JSONArray(prefs.getString("txns", "[]"))
            (0 until list.length()).mapNotNull { i ->
                val t = list.getJSONObject(i)
                runCatching {
                    BankTxn(t.getString("id"), t.getString("account"), LocalDate.parse(t.getString("date")), t.getString("name"),
                        t.getDouble("amount"), t.optBoolean("pending"), t.optString("category"), t.optString("detail"))
                }.getOrNull()
            }
        }.getOrDefault(emptyList()).also { cachedTxns = it }

    // Choices made on a transaction: "spend", "skip", or "bill:<bill name>".
    val overrides: Map<String, String>
        get() = cachedOverrides ?: runCatching {
            val json = JSONObject(prefs.getString("overrides", "{}"))
            json.keys().asSequence().associateWith { json.getString(it) }
        }.getOrDefault(emptyMap()).also { cachedOverrides = it }

    fun setOverride(txnId: String, choice: String?) {
        val updated = overrides.toMutableMap().apply { if (choice == null) remove(txnId) else put(txnId, choice) }
        cachedOverrides = updated
        prefs.edit().putString("overrides", JSONObject(updated as Map<*, *>).toString()).apply()
    }

    fun connect(url: String, key: String, accountId: String) {
        val (iv, data) = Keys.encrypt(key)
        clear()
        prefs.edit()
            .putString("url", url).putString("key_iv", iv).putString("key", data).putString("account", accountId)
            .apply()
    }

    fun chooseAccount(accountId: String) = prefs.edit().putString("account", accountId).apply()

    fun key(): String = runCatching { Keys.decrypt(prefs.getString("key_iv", null)!!, prefs.getString("key", null)!!) }
        .getOrElse { throw BankException("The saved access key can't be read. Connect the bank again in Settings.") }

    fun saveSnapshot(snapshot: BankSnapshot) {
        val accounts = JSONArray().apply { snapshot.accounts.forEach { put(accountToJson(it)) } }
        val txns = JSONArray().apply {
            snapshot.txns.forEach { t ->
                put(JSONObject().put("id", t.id).put("account", t.account).put("date", t.date.toString()).put("name", t.name)
                    .put("amount", t.amount).put("pending", t.pending).put("category", t.category).put("detail", t.detail))
            }
        }
        // Choices on transactions that are gone (a pending one that posted under a new ID) aren't needed.
        val ids = snapshot.txns.map { it.id }.toSet()
        val kept = overrides.filterKeys { it in ids }
        cachedAccounts = snapshot.accounts
        cachedTxns = snapshot.txns
        cachedOverrides = kept
        prefs.edit()
            .putString("accounts", accounts.toString())
            .putString("txns", txns.toString())
            .putString("overrides", JSONObject(kept as Map<*, *>).toString())
            .putLong("checked_at", System.currentTimeMillis())
            .putString("error", snapshot.errors.firstOrNull())
            .apply()
    }

    fun saveError(message: String) = prefs.edit().putString("error", message).apply()

    fun clear() {
        cachedAccounts = null
        cachedTxns = null
        cachedOverrides = null
        prefs.edit().clear().apply()
    }
}

// An AES key kept in the Android keystore; it encrypts the bank server's access key.
private object Keys {
    private const val ALIAS = "budget_bank_server_key"
    private const val TRANSFORM = "AES/GCM/NoPadding"

    fun encrypt(text: String): Pair<String, String> {
        val cipher = Cipher.getInstance(TRANSFORM)
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val data = cipher.doFinal(text.toByteArray())
        return Base64.encodeToString(cipher.iv, Base64.NO_WRAP) to Base64.encodeToString(data, Base64.NO_WRAP)
    }

    fun decrypt(iv: String, data: String): String {
        val cipher = Cipher.getInstance(TRANSFORM)
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, Base64.decode(iv, Base64.NO_WRAP)))
        return String(cipher.doFinal(Base64.decode(data, Base64.NO_WRAP)))
    }

    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(
                KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256)
                    .build()
            )
        }.generateKey()
    }
}
