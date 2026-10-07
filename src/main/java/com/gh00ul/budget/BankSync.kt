package com.gh00ul.budget

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import android.util.Log
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URI
import java.net.URL
import java.net.UnknownHostException
import java.security.GeneralSecurityException
import java.security.KeyStore
import java.security.ProviderException
import java.time.DateTimeException
import java.time.LocalDate
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.net.ssl.SSLException

// Bank sync through the ClearBudget bank server (gh00ul/clearbudget-personal, bank-sync/). That server holds
// the Plaid keys and the bank connection. This app only keeps the server's address, its access key
// (encrypted with a key that never leaves the phone's keystore), and the last couple of months of
// transactions.

// The message is for the user; the cause (a DNS, TLS or keystore failure, ...) is kept for diagnosing.
class BankException(message: String, cause: Throwable? = null) : Exception(message, cause)

// Transactions dated further ahead than this are bad data (and dates near the end of time break later date math).
private const val MAX_DAYS_AHEAD = 31L

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
            val banks = response.optJSONArray("items")?.let { items ->
                (0 until items.length()).mapNotNull { items.optJSONObject(it) }.associate { it.optString("item_id") to it.optString("institution_name") }
            }.orEmpty()
            sync?.optJSONArray("errors")?.let { list ->
                for (i in 0 until list.length()) {
                    val e = list.optJSONObject(i) ?: continue
                    val name = banks[e.optString("item_id")]?.takeIf { it.isNotBlank() } ?: "Your bank"
                    errors += when (e.optString("code")) {
                        "ITEM_LOGIN_REQUIRED" -> "$name needs you to sign in again. Open ClearBudget and tap Connect bank " +
                            "to fix it; until then, new transactions won't come in."
                        "SYNC_IN_PROGRESS" -> continue // ClearBudget is syncing it right now; the data is still fine
                        else -> "$name: " + (e.optString("message").takeIf { it.isNotBlank() } ?: "sync failed. Try again later.")
                    }
                }
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

    // A key that can go in a request header as-is: long enough, and only printable ASCII (a space is allowed inside;
    // callers trim the ends).
    fun isUsableKey(key: String): Boolean = key.length >= 32 && key.all { it in ' '..'~' }

    private fun request(base: String, key: String, method: String, path: String, body: JSONObject?): JSONObject {
        // Checked before the header is set: a bad character there fails with an error that repeats the key.
        if (!isUsableKey(key)) throw BankException("The access key has characters that can't be sent. Copy it again from your bank server.")
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
                // The server's own explanation when the reply is JSON; a plain reply gets the generic message below.
                val message = try {
                    JSONObject(text).optJSONObject("error")?.optString("message")
                } catch (e: JSONException) {
                    null
                }
                throw BankException(when (status) {
                    401, 403 -> "The access key was rejected. Check it in Settings."
                    404 -> "That address isn't a bank server. Check it in Settings."
                    429 -> "The bank server is busy. Try again in a minute."
                    else -> message?.takeIf { it.isNotBlank() } ?: "The bank server had a problem ($status)."
                })
            }
            return try {
                JSONObject(text)
            } catch (e: JSONException) {
                throw BankException("The bank server sent something unreadable.", e)
            }
        } catch (e: IOException) {
            // The specific kinds come first: they're all IOExceptions too.
            Log.w("Budget", "Bank server request failed: ${e.javaClass.simpleName}")
            throw BankException(when (e) {
                is UnknownHostException -> "Couldn't find the bank server. Check the address in Settings and your connection."
                is SocketTimeoutException -> "The bank server took too long to answer. Try again in a minute."
                is SSLException -> "Couldn't make a secure connection to the bank server."
                else -> "Couldn't reach the bank server. Check your connection."
            }, e)
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
        // The server always sends this list, even when it's empty. A missing one isn't "no transactions":
        // saving it that way would throw away every choice made on a transaction.
        val txns = json.optJSONArray("transactions") ?: throw BankException("The bank server's reply was incomplete. Try again later.")
        val today = LocalDate.now()
        val oldest = today.minusDays(KEEP_DAYS)
        val latest = today.plusDays(MAX_DAYS_AHEAD)
        var skipped = 0
        val kept = (0 until txns.length()).mapNotNull { i ->
            val t = txns.optJSONObject(i)?.let(::txnFromServer)
            if (t == null) skipped++
            t?.takeIf { !it.date.isBefore(oldest) && !it.date.isAfter(latest) }
        }
        if (skipped > 0) Log.w("Budget", "Skipped $skipped unreadable transactions from the bank server")
        return BankSnapshot(
            (0 until accounts.length()).mapNotNull { accounts.optJSONObject(it)?.let(::accountFromJson) },
            kept,
            emptyList(),
        )
    }

    // One transaction from the server, or null when a field is missing or malformed (just that one is skipped).
    private fun txnFromServer(t: JSONObject): BankTxn? = try {
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
    } catch (e: JSONException) {
        null
    } catch (e: DateTimeException) {
        null
    }
}

private fun JSONObject.text(key: String) = optString(key).takeIf { has(key) && !isNull(key) && it.isNotBlank() }

// Cents as dollars. Missing or not a number is null, never a made-up $0.00.
private fun JSONObject.cents(key: String) =
    if (has(key) && !isNull(key)) optLong(key, Long.MIN_VALUE).takeIf { it != Long.MIN_VALUE }?.let { it / 100.0 } else null

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

// One saved transaction, or null when it can't be read (just that one is skipped).
private fun txnFromSaved(t: JSONObject): BankTxn? = try {
    BankTxn(t.getString("id"), t.getString("account"), LocalDate.parse(t.getString("date")), t.getString("name"),
        t.getDouble("amount"), t.optBoolean("pending"), t.optString("category"), t.optString("detail"))
} catch (e: JSONException) {
    null
} catch (e: DateTimeException) {
    null
}

// The access key as the keystore encrypted it. The IV is random every time.
class SealedKey(val iv: String, val data: String)

// Encrypts the access key for BankStore.connect. Safe on any thread; the keystore can be slow, so not the main one.
fun sealAccessKey(key: String): SealedKey {
    val message = "This phone couldn't store the access key securely. Try again, and restart the phone if it keeps happening."
    return try {
        Keys.encrypt(key)
    } catch (e: GeneralSecurityException) {
        throw keystoreFailure(message, e)
    } catch (e: ProviderException) {
        throw keystoreFailure(message, e)
    } catch (e: IOException) {
        throw keystoreFailure(message, e)
    }
}

// Keystore trouble, logged by kind only, as a message the user can act on.
private fun keystoreFailure(message: String, e: Exception): BankException {
    Log.w("Budget", "Keystore failed: ${e.javaClass.simpleName}")
    return BankException(message, e)
}

// What's kept on the phone ("bank" preferences, left out of backups).
class BankStore(context: Context) {
    private val prefs = context.getSharedPreferences("bank", Context.MODE_PRIVATE)

    val url: String? get() = prefs.getString("url", null)
    val accountId: String? get() = prefs.getString("account", null) // the checking account the balance comes from
    val isLinked get() = url != null && accountId != null && prefs.contains("key")
    val checkedAt: Long get() = prefs.getLong("checked_at", 0L) // last good sync, epoch ms
    val failedAt: Long get() = prefs.getLong("failed_at", 0L) // last failed sync, epoch ms; 0 after a good one
    val error: String? get() = prefs.getString("error", null)

    // The key's IV is random for every connect, so it identifies one connection; it's null once disconnected.
    val connection: String? get() = prefs.getString("key_iv", null)

    // Read once, then kept in memory (the screen redraws often).
    private var cachedAccounts: List<BankAccount>? = null
    private var cachedTxns: List<BankTxn>? = null
    private var cachedOverrides: Map<String, String>? = null

    val accounts: List<BankAccount>
        get() = cachedAccounts ?: readList("accounts", ::accountFromJson).also { cachedAccounts = it }

    val account: BankAccount? get() = accounts.firstOrNull { it.id == accountId }

    val txns: List<BankTxn>
        get() = cachedTxns ?: run {
            val latest = LocalDate.now().plusDays(MAX_DAYS_AHEAD)
            readList("txns", ::txnFromSaved).filter { !it.date.isAfter(latest) }
        }.also { cachedTxns = it }

    // Choices made on a transaction: "spend", "skip", or "bill:<bill name>".
    val overrides: Map<String, String>
        get() = cachedOverrides ?: readOverrides().also { cachedOverrides = it }

    fun setOverride(txnId: String, choice: String?) {
        saveOverrides(overrides.toMutableMap().apply { if (choice == null) remove(txnId) else put(txnId, choice) })
    }

    // A renamed bill keeps the payments that were assigned to it by hand. Returns those transactions, so an Undo
    // can move back exactly them (another bill may already have the new name).
    fun renameBillOverrides(from: String, to: String): Set<String> {
        val old = "bill:$from"
        if (from == to) return emptySet()
        val moved = overrides.filterValues { it == old }.keys
        if (moved.isNotEmpty()) saveOverrides(overrides.mapValues { (txnId, choice) -> if (txnId in moved) "bill:$to" else choice })
        return moved
    }

    // A new connection replaces any old one. The key is already sealed (sealAccessKey), so this only writes.
    fun connect(url: String, key: SealedKey, accountId: String) {
        clear()
        prefs.edit()
            .putString("url", url).putString("key_iv", key.iv).putString("key", key.data).putString("account", accountId)
            .apply()
    }

    fun chooseAccount(accountId: String) = prefs.edit().putString("account", accountId).apply()

    // The access key, decrypted. The keystore can be slow, so call this off the main thread.
    fun key(): String {
        val message = "The saved access key can't be read on this phone any more. In Settings, tap your bank, disconnect, and connect it again."
        val iv = prefs.getString("key_iv", null)
        val data = prefs.getString("key", null)
        if (iv == null || data == null) throw BankException(message)
        return try {
            Keys.decrypt(SealedKey(iv, data))
        } catch (e: GeneralSecurityException) {
            throw keystoreFailure(message, e)
        } catch (e: ProviderException) {
            throw keystoreFailure(message, e)
        } catch (e: IOException) {
            throw keystoreFailure(message, e)
        } catch (e: IllegalArgumentException) { // the saved text isn't valid Base64
            throw keystoreFailure(message, e)
        }
    }

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
            .remove("failed_at")
            .putString("error", snapshot.errors.firstOrNull())
            .apply()
    }

    // A failed sync: what to show, and when it happened (so automatic syncs can back off).
    fun saveError(message: String) =
        prefs.edit().putString("error", message).putLong("failed_at", System.currentTimeMillis()).apply()

    fun clear() {
        cachedAccounts = null
        cachedTxns = null
        cachedOverrides = null
        prefs.edit().clear().apply()
    }

    private fun saveOverrides(updated: Map<String, String>) {
        cachedOverrides = updated
        prefs.edit().putString("overrides", JSONObject(updated as Map<*, *>).toString()).apply()
    }

    // A saved list, item by item: an unreadable item is skipped (and counted in the log) instead of losing them all.
    private fun <T> readList(name: String, read: (JSONObject) -> T?): List<T> {
        val raw = prefs.getString(name, null) ?: return emptyList()
        val list = try {
            JSONArray(raw)
        } catch (e: JSONException) {
            Log.w("Budget", "Saved $name isn't readable")
            return emptyList()
        }
        val items = ArrayList<T>(list.length())
        var skipped = 0
        for (i in 0 until list.length()) {
            val item = list.optJSONObject(i)?.let(read)
            if (item == null) skipped++ else items += item
        }
        if (skipped > 0) Log.w("Budget", "Skipped $skipped unreadable saved $name")
        return items
    }

    // Saved choices; one that isn't text is skipped (and counted in the log).
    private fun readOverrides(): Map<String, String> {
        val raw = prefs.getString("overrides", null) ?: return emptyMap()
        val json = try {
            JSONObject(raw)
        } catch (e: JSONException) {
            Log.w("Budget", "Saved overrides isn't readable")
            return emptyMap()
        }
        val choices = mutableMapOf<String, String>()
        var skipped = 0
        for (txnId in json.keys()) {
            val choice = json.opt(txnId) as? String
            if (choice == null) skipped++ else choices[txnId] = choice
        }
        if (skipped > 0) Log.w("Budget", "Skipped $skipped unreadable saved overrides")
        return choices
    }
}

// An AES key kept in the Android keystore; it encrypts the bank server's access key.
private object Keys {
    private const val ALIAS = "budget_bank_server_key"
    private const val TRANSFORM = "AES/GCM/NoPadding"

    fun encrypt(text: String): SealedKey {
        val cipher = Cipher.getInstance(TRANSFORM)
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val data = cipher.doFinal(text.toByteArray())
        return SealedKey(Base64.encodeToString(cipher.iv, Base64.NO_WRAP), Base64.encodeToString(data, Base64.NO_WRAP))
    }

    fun decrypt(sealed: SealedKey): String {
        val cipher = Cipher.getInstance(TRANSFORM)
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, Base64.decode(sealed.iv, Base64.NO_WRAP)))
        return String(cipher.doFinal(Base64.decode(sealed.data, Base64.NO_WRAP)))
    }

    // One thread at a time: if two both made the key, the second would replace the first and the saved
    // access key could never be read again.
    @Synchronized
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
