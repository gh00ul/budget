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
import java.net.ConnectException
import java.net.HttpURLConnection
import java.net.MalformedURLException
import java.net.NoRouteToHostException
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

// The message is for the user; the cause (a DNS, TLS or keystore failure, ...) is kept for diagnosing. network is
// true only when the server couldn't be reached or didn't answer in time, so trying again later may just work.
class BankException(message: String, cause: Throwable? = null, val network: Boolean = false) : Exception(message, cause)

// Transactions dated further ahead than this are bad data (and dates near the end of time break later date math).
private const val MAX_DAYS_AHEAD = 31L

// Older transactions (and the choices made on them) aren't needed.
private const val KEEP_DAYS = 70L

// The longest server message shown as-is.
private const val MAX_MESSAGE = 200

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
    private const val MAX_PAGES = 20
    private const val SYNC_BUDGET_MS = 120_000L // a whole sync, every page and retry included
    private const val CONNECT_BUDGET_MS = 60_000L // checking a new connection
    private const val CONNECT_TIMEOUT_MS = 15_000L
    private const val READ_TIMEOUT_MS = 60_000L
    private const val RETRY_DELAY_MS = 2_000L

    // The server's last bank data, without asking Plaid for anything new. This checks a new connection, so it has
    // its own minute and messages about what was just typed.
    fun snapshot(url: String, key: String): BankSnapshot =
        parse(request(url, key, "GET", "/v1/snapshot", null, deadlineAfter(CONNECT_BUDGET_MS), forConnect = true))

    // Asks Plaid for anything new, then returns everything. The server handles two bank logins per call, so this
    // asks again until every bank is done, for at most MAX_PAGES calls and 2 minutes. Each reply carries all of the
    // server's data, so stopping early still returns the latest, with a note that some banks weren't refreshed.
    fun sync(url: String, key: String): BankSnapshot {
        val deadline = deadlineAfter(SYNC_BUDGET_MS)
        val errors = mutableListOf<String>()
        var after: String? = null
        // The first page has nothing to fall back on, so any failure there (taking too long included) stands.
        var response = syncPage(url, key, null, deadline)
        var pages = 1
        while (true) {
            errors += syncErrors(response)
            val sync = response.optJSONObject("sync")
            val next = if (sync == null || sync.isNull("next_item_id")) null
            else sync.optString("next_item_id").takeIf { it.isNotBlank() && it != after }
            if (next == null) return parse(response).let { BankSnapshot(it.accounts, it.txns, errors.distinct()) }
            if (pages == MAX_PAGES) return partial(response, errors, "page limit")
            if (msLeft(deadline) <= 0) return partial(response, errors, "out of time")
            after = next
            response = try {
                syncPage(url, key, next, deadline)
            } catch (e: BankException) {
                // A later page that runs out of time doesn't undo the pages already done.
                if (e.cause !is SocketTimeoutException) throw e
                return partial(response, errors, "out of time")
            }
            pages++
        }
    }

    // https only, except a server on the phone itself (for testing).
    fun cleanUrl(raw: String): String? {
        val uri = runCatching { URI(raw.trim()) }.getOrNull() ?: return null
        val host = uri.host ?: return null
        val local = host == "localhost" || host == "127.0.0.1"
        // (A port past 65535 would only fail later, when connecting, with an error that isn't about the address.)
        val ok = (uri.scheme.equals("https", true) || (local && uri.scheme.equals("http", true))) &&
            uri.rawUserInfo == null && uri.rawQuery == null && uri.rawFragment == null && uri.port in -1..65535 && uri.port != 0
        return if (ok) uri.toString().trimEnd('/') else null
    }

    // A key that can go in a request header as-is: long enough, and only printable ASCII (a space is allowed inside;
    // callers trim the ends).
    fun isUsableKey(key: String): Boolean = key.length >= 32 && key.all { it in ' '..'~' }

    // A deadline budgetMs from now, on a clock that doesn't jump when the phone's time is changed.
    private fun deadlineAfter(budgetMs: Long) = System.nanoTime() + budgetMs * 1_000_000

    // Milliseconds left before the deadline; 0 or less once it has passed.
    private fun msLeft(deadline: Long) = (deadline - System.nanoTime()) / 1_000_000

    // One sync call: the server syncs the next two bank logins after `after`, then replies with everything.
    private fun syncPage(url: String, key: String, after: String?, deadline: Long): JSONObject {
        val body = JSONObject().apply { after?.let { put("start_after_item_id", it) } }
        return request(url, key, "POST", "/v1/sync", body, deadline, forConnect = false)
    }

    // The bank problems a sync page reports, as messages. ClearBudget syncing a bank right now isn't one.
    private fun syncErrors(response: JSONObject): List<String> {
        val list = response.optJSONObject("sync")?.optJSONArray("errors") ?: return emptyList()
        val banks = response.optJSONArray("items")?.let { items ->
            (0 until items.length()).mapNotNull { items.optJSONObject(it) }.associate { it.optString("item_id") to it.optString("institution_name") }
        }.orEmpty()
        val messages = mutableListOf<String>()
        for (i in 0 until list.length()) {
            val e = list.optJSONObject(i) ?: continue
            val name = banks[e.optString("item_id")]?.takeIf { it.isNotBlank() } ?: "Your bank"
            messages += when (e.optString("code")) {
                "ITEM_LOGIN_REQUIRED" -> "$name needs you to sign in again in ClearBudget (where you linked it), not in this app. " +
                    "Until then, new transactions won't come in."
                "SYNC_IN_PROGRESS" -> continue // ClearBudget is syncing it right now; the data is still fine
                else -> "$name: " + (cleanServerMessage(e.text("message")) ?: "sync failed. Try again later.")
            }
        }
        return messages
    }

    // Stopping a sync early: the last page's data (every page carries everything the server has), plus a note that
    // some banks weren't refreshed.
    private fun partial(response: JSONObject, errors: List<String>, why: String): BankSnapshot {
        Log.w("Budget", "Bank sync stopped early: $why")
        val snapshot = parse(response)
        val note = "Some of your banks weren't refreshed this time. Try Sync now later."
        return BankSnapshot(snapshot.accounts, snapshot.txns, (errors + note).distinct())
    }

    // One call to the server, made a second time after a short pause when nothing answered at all or a gateway in
    // front of the server failed (both calls are safe to repeat). It all has to finish before the deadline.
    private fun request(
        base: String, key: String, method: String, path: String, body: JSONObject?, deadline: Long, forConnect: Boolean,
    ): JSONObject {
        // Checked before the header is set: a bad character there fails with an error that repeats the key.
        if (!isUsableKey(key)) throw BankException("The access key has characters that can't be sent. Copy it again from your bank server.")
        val url = try {
            URL(base + path)
        } catch (e: MalformedURLException) { // only an address that skipped cleanUrl
            throw BankException("That isn't a bank server address. Check it and try again.", e)
        }
        var retried = false
        while (true) {
            val reply = try {
                exchange(url, key, method, body, deadline)
            } catch (e: IOException) {
                if (!retried && isRetryable(e) && pauseToRetry(deadline, e.javaClass.simpleName)) {
                    retried = true
                    continue
                }
                Log.w("Budget", "Bank server request failed: ${e.javaClass.simpleName}")
                throw BankException(networkErrorMessage(e, forConnect), e, network = true)
            }
            if (reply.status in 200..299) {
                return try {
                    JSONObject(reply.text)
                } catch (e: JSONException) {
                    throw BankException("The bank server sent something unreadable. Try again later.", e)
                }
            }
            if (!retried && isRetryable(reply.status) && pauseToRetry(deadline, "HTTP ${reply.status}")) {
                retried = true
                continue
            }
            Log.w("Budget", "Bank server answered HTTP ${reply.status}")
            throw BankException(httpErrorMessage(reply.status, serverMessage(reply.text), forConnect))
        }
    }

    private class Reply(val status: Int, val text: String)

    // Sends one request and reads the whole reply. Connecting and every wait for data are capped by the time left
    // before the deadline. An IOException means the server couldn't be reached or didn't answer in time.
    private fun exchange(url: URL, key: String, method: String, body: JSONObject?, deadline: Long): Reply {
        val left = msLeft(deadline)
        if (left <= 0) throw SocketTimeoutException("No time left")
        val conn = url.openConnection() as HttpURLConnection
        try {
            conn.requestMethod = method
            conn.connectTimeout = left.coerceIn(1L, CONNECT_TIMEOUT_MS).toInt()
            conn.readTimeout = left.coerceIn(1L, READ_TIMEOUT_MS).toInt()
            conn.instanceFollowRedirects = false // a redirect is reported (httpErrorMessage), never followed
            conn.setRequestProperty("Accept", "application/json")
            conn.setRequestProperty("Authorization", "Bearer $key")
            if (body != null) {
                conn.doOutput = true
                conn.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                conn.outputStream.use { it.write(body.toString().toByteArray()) }
            }
            val status = conn.responseCode
            val text = (if (status in 200..299) conn.inputStream else conn.errorStream)?.use { readLimited(it, deadline) }.orEmpty()
            return Reply(status, text)
        } finally {
            conn.disconnect()
        }
    }

    // Waits before the one retry. False (no retry) when that would leave too little time for it, or when the
    // thread is being stopped.
    private fun pauseToRetry(deadline: Long, failure: String): Boolean {
        if (msLeft(deadline) < RETRY_DELAY_MS * 2) return false
        Log.w("Budget", "Bank server request failed: $failure; retrying")
        return try {
            Thread.sleep(RETRY_DELAY_MS)
            true
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt() // keep the stop request for the code that checks it
            false
        }
    }

    // The server's own explanation, when its error reply is JSON with one.
    private fun serverMessage(reply: String): String? = try {
        JSONObject(reply).optJSONObject("error")?.text("message")
    } catch (e: JSONException) {
        null // a plain reply (a proxy's error page, say) gets the generic message
    }

    // The whole reply, up to MAX_BYTES. A server still trickling bytes at the deadline counts as too slow
    // (the timeout is turned into the "took too long" message like any other).
    private fun readLimited(stream: InputStream, deadline: Long): String {
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        while (true) {
            if (msLeft(deadline) <= 0) throw SocketTimeoutException("Ran out of time reading the reply")
            val n = stream.read(buffer)
            if (n < 0) break
            if (out.size() + n > MAX_BYTES) throw BankException("The bank server sent too much data. Try again later.")
            out.write(buffer, 0, n)
        }
        return out.toString("UTF-8")
    }

    private fun parse(json: JSONObject): BankSnapshot {
        val accounts = json.optJSONArray("accounts")
            ?: throw BankException("The bank server sent no accounts. Try again later, or check that your bank is linked in ClearBudget.")
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

// What to tell the user when the server answered with an error. A connect is about what was just typed; a sync
// is about a saved connection that used to work. Status codes stay out of the text (they're in the log).
internal fun httpErrorMessage(status: Int, serverMessage: String?, forConnect: Boolean): String = when (status) {
    401, 403 -> if (forConnect) {
        "The bank server didn't accept that access key. Check it and try again."
    } else {
        "The bank server didn't accept the access key. If it changed, in Settings tap your bank, Disconnect, " +
            "then Connect your bank with the new key."
    }
    404 -> if (forConnect) {
        "There's no bank server at that address. Check it and try again."
    } else {
        "The bank server's address doesn't answer any more. If it changed, in Settings tap your bank, Disconnect, " +
            "then connect again with the new address."
    }
    in 300..399 -> if (forConnect) {
        "That address points somewhere else. Use the bank server's current https:// address."
    } else {
        "The bank server's address has changed. In Settings tap your bank, Disconnect, then connect again with the new address."
    }
    429 -> "The bank server is busy. Try again in a minute."
    else -> cleanServerMessage(serverMessage) ?: "The bank server had a problem. Try again later."
}

// What to tell the user when the server couldn't be reached or didn't answer in time. The specific kinds come
// first: they're all IOExceptions too.
internal fun networkErrorMessage(e: IOException, forConnect: Boolean): String = when (e) {
    is UnknownHostException -> if (forConnect) {
        "Couldn't find a server at that address. Check it and your connection."
    } else {
        "Couldn't reach the bank server. Check your connection."
    }
    is SocketTimeoutException -> "The bank server took too long to answer. Try again in a minute."
    is SSLException -> "Couldn't make a secure connection to the bank server. Check that your phone's date and time are right, " +
        "then try again."
    else -> "Couldn't reach the bank server. Check your connection."
}

// Failures worth one more try: nothing answered at all (the phone's connection may have just come back). Never a
// rejection, a TLS problem, an unknown address or a slow answer.
internal fun isRetryable(e: IOException) = e is ConnectException || e is NoRouteToHostException

// A gateway in front of the server failed or gave up; the server itself may be fine a moment later.
internal fun isRetryable(status: Int) = status == 502 || status == 504

// A server's own error text made fit to show: one line (runs of spaces, line breaks and control characters become
// one space), at most MAX_MESSAGE characters. Null when nothing is left.
internal fun cleanServerMessage(raw: String?): String? {
    if (raw == null) return null
    val text = buildString {
        for (c in raw) {
            if (!c.isWhitespace() && !c.isISOControl()) append(c)
            else if (isNotEmpty() && last() != ' ') append(' ')
        }
    }.trimEnd()
    if (text.isEmpty()) return null
    if (text.length <= MAX_MESSAGE) return text
    val cut = text.take(MAX_MESSAGE - 1).let { if (it.last().isHighSurrogate()) it.dropLast(1) else it } // no half emoji
    return cut.trimEnd() + "…"
}

// Plaid posts a pending purchase as a new transaction under a new ID, and the bank server doesn't pass Plaid's
// pending_transaction_id through, so a choice made on the pending one would be lost. It's copied to the posted one
// only when the match is certain: the pending one had a choice and is gone; the posted one is new and has no choice;
// same account; exactly the same amount; posted from 1 day before to 10 days after; and neither has any other
// candidate (two same-amount purchases at once are left alone). The pending one's entry stays; keptChoices drops it.
internal fun carryOverChoices(old: List<BankTxn>, new: List<BankTxn>, choices: Map<String, String>): Map<String, String> {
    val oldIds = old.mapTo(HashSet()) { it.id }
    val newIds = new.mapTo(HashSet()) { it.id }
    // Every pending transaction that disappeared counts when checking that a match is unique, not just those with a
    // choice: otherwise two same-amount purchases could hand one's choice to the other's posted copy.
    val vanished = old.filter { it.pending && it.id !in newIds }
    val gone = vanished.filter { it.id in choices }
    val arrived = new.filter { !it.pending && it.id !in oldIds && it.id !in choices }
    // Epoch days, not plusDays/minusDays: no date math that can overflow on a damaged saved date.
    fun matches(x: BankTxn, y: BankTxn) = x.account == y.account &&
        Math.round(x.amount * 100) == Math.round(y.amount * 100) &&
        y.date.toEpochDay() - x.date.toEpochDay() in -1L..10L
    val carried = choices.toMutableMap()
    for (x in gone) {
        val y = arrived.filter { matches(x, it) }.singleOrNull() ?: continue
        if (vanished.count { matches(it, y) } != 1) continue
        carried[y.id] = choices[x.id] ?: continue
    }
    return carried
}

// The choices worth keeping after a sync: on a transaction the server still sends, or on one last seen within
// KEEP_DAYS. So a reply that's missing a bank for a while doesn't erase its choices, and they still age out. A choice
// on a transaction neither list knows is dropped.
internal fun keptChoices(old: List<BankTxn>, new: List<BankTxn>, choices: Map<String, String>, today: LocalDate): Map<String, String> {
    val current = new.mapTo(HashSet()) { it.id }
    val oldest = today.minusDays(KEEP_DAYS)
    val recent = old.filter { !it.date.isBefore(oldest) }.mapTo(HashSet()) { it.id }
    return choices.filterKeys { it in current || it in recent }
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

// One saved transaction, or null when it can't be read (just that one is skipped). An amount that isn't a real
// number (NaN, infinity) counts as unreadable: it would spoil every total it's added to.
private fun txnFromSaved(t: JSONObject): BankTxn? = try {
    val amount = t.getDouble("amount")
    if (amount.isFinite()) {
        BankTxn(t.getString("id"), t.getString("account"), LocalDate.parse(t.getString("date")), t.getString("name"),
            amount, t.optBoolean("pending"), t.optString("category"), t.optString("detail"))
    } else {
        null
    }
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
        val message = "This phone can't read the saved access key any more. In Settings, tap your bank, Disconnect, " +
            "then Connect your bank again. You'll need the server address and access key."
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
        // Choices follow a pending purchase to its posted copy, then the ones no longer needed are dropped.
        val before = this.txns
        val carried = carryOverChoices(before, snapshot.txns, overrides)
        val moved = carried.size - overrides.size
        if (moved > 0) Log.i("Budget", "Carried $moved choices from pending to posted transactions")
        val kept = keptChoices(before, snapshot.txns, carried, LocalDate.now())
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

    // Saved choices; one that isn't text is skipped (and counted in the log). When none of it can be read, the text
    // is first copied to "overrides_unreadable", since the next save replaces "overrides" and it could still be fixed.
    private fun readOverrides(): Map<String, String> {
        val raw = prefs.getString("overrides", null) ?: return emptyMap()
        val json = try {
            JSONObject(raw)
        } catch (e: JSONException) {
            if (prefs.getString("overrides_unreadable", null) != raw) prefs.edit().putString("overrides_unreadable", raw).apply()
            Log.w("Budget", "Saved overrides isn't readable; copied to overrides_unreadable")
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
