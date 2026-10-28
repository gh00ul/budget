package com.gh00ul.budget

import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.time.DayOfWeek
import java.time.LocalDate

// Everything Budget has saved, as one file, to move it to a new phone (Settings › Move to a new phone). The budget's
// saved values go in exactly as saved; from the bank, only the server address and the choices made on transactions.
// The access key never leaves the phone: it's sealed with a keystore key that can't be copied, so on the new phone
// the bank is connected again (with the address filled in). No Android code, so it can be unit-tested on its own.

internal const val BACKUP_VERSION = 1

// A backup is a few kilobytes; anything near this size is some other file.
internal const val BACKUP_MAX_BYTES = 2_000_000

// The bank's saved values a backup carries. The rest is the connection itself, or comes back with the next sync.
private val BACKUP_BANK_KEYS = setOf("url", "overrides")

internal fun backupFileName(day: LocalDate) = "budget-backup-$day.json"

// The saved values are text, whole numbers and yes/no (all the budget ever saves); those are what JSON brings back
// as the same type.
private fun keepable(value: Any?) = value is String || value is Int || value is Boolean

internal fun backupJson(budget: Map<String, *>, bank: Map<String, *>, made: LocalDate, appVersion: String): String {
    fun values(saved: Map<String, *>, keep: (String) -> Boolean) = JSONObject().apply {
        for ((key, value) in saved) if (keep(key) && keepable(value)) put(key, value)
    }
    return JSONObject()
        .put("budget_backup", BACKUP_VERSION)
        .put("made", made.toString())
        .put("app_version", appVersion)
        .put("budget", values(budget) { true })
        .put("bank", values(bank) { it in BACKUP_BANK_KEYS })
        .toString(2)
}

// A file that can't be restored; the message says why, for the person picking it.
internal class BackupException(message: String) : Exception(message)

internal class Backup(
    val made: LocalDate?,
    val budget: Map<String, Any>, // the "budget" preferences as saved: String, Int or Boolean
    val bankUrl: String?,
    val choices: Map<String, String>, // BankStore.overrides
) {
    // For the "Restore this backup?" question.
    val weeklyIncome get() = (budget["weekly_income"] as? String)?.toDoubleOrNull()?.takeIf { it.isFinite() && it > 0 }
    val payday get() = (budget["payday"] as? Int)?.takeIf { it in 1..7 }?.let { DayOfWeek.of(it) }
    val balance get() = (budget["balance"] as? String)?.toDoubleOrNull()?.takeIf { it.isFinite() }
    val balanceUpdated get() = savedDate(budget["balance_updated"] as? String)
    val billCount get() = (budget["bills"] as? String)?.let {
        try {
            JSONArray(it).length()
        } catch (e: JSONException) {
            null
        }
    } ?: 0
}

internal const val NOT_A_BACKUP = "That file isn't a Budget backup. Pick the one named budget-backup-….json."

internal fun readBackup(text: String): Backup {
    val root = try {
        JSONObject(text.removePrefix(Char(0xFEFF).toString())) // a byte-order mark, if an editor added one
    } catch (e: JSONException) {
        throw BackupException(NOT_A_BACKUP)
    }
    val version = root.opt("budget_backup") as? Int ?: throw BackupException(NOT_A_BACKUP)
    if (version > BACKUP_VERSION) {
        throw BackupException("That backup is from a newer version of Budget. Update Budget on this phone first " +
            "(Settings › Check for updates), then restore it.")
    }
    val saved = root.optJSONObject("budget") ?: throw BackupException(NOT_A_BACKUP)
    val budget = mutableMapOf<String, Any>()
    for (key in saved.keys()) saved.opt(key)?.takeIf(::keepable)?.let { budget[key] = it }
    if (budget.isEmpty()) throw BackupException("That backup is empty: it was saved before anything was set up.")

    val bank = root.optJSONObject("bank")
    val choices = mutableMapOf<String, String>()
    (bank?.opt("overrides") as? String)?.let { raw ->
        try {
            val json = JSONObject(raw)
            for (txnId in json.keys()) (json.opt(txnId) as? String)?.let { choices[txnId] = it }
        } catch (e: JSONException) {
            // Only the choices on transactions are lost; they can be made again.
        }
    }
    return Backup(
        made = savedDate(root.opt("made") as? String),
        budget = budget,
        bankUrl = (bank?.opt("url") as? String)?.takeIf { it.isNotBlank() },
        choices = choices,
    )
}
