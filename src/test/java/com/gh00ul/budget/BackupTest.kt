package com.gh00ul.budget

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.DayOfWeek
import java.time.LocalDate

// The backup file for moving to a new phone (Backup.kt).
//
// Covered:
// - backupJson then readBackup gives back every saved budget value with its type (text, whole number, yes/no).
// - From the bank, only the server address and the choices on transactions; never the access key, its IV, the
//   account or the synced transactions.
// - The summary shown before restoring (pay, payday, bill count, balance and its date, when it was saved).
// - Files that aren't backups (not JSON, other JSON, no budget, empty budget), one from a newer version, a byte-order
//   mark, damaged choices.
class BackupTest {

    private val budget = mapOf(
        "balance" to "1842.37",
        "balance_updated" to "2026-10-27",
        "weekly_income" to "780.0",
        "payday" to 5,
        "bills_none" to false,
        "bills" to """[{"name":"Rent","amount":1200.0},{"name":"Phone","amount":45.0}]""",
        "paycheck_changes" to """{"2026-10-30":820.0}""",
        "week_start_projected" to true,
    )

    private val bank = mapOf(
        "url" to "https://bank.example.workers.dev",
        "key" to "SEALED-KEY",
        "key_iv" to "IV",
        "account" to "chk",
        "txns" to "[]",
        "checked_at" to 1_761_000_000_000L,
        "overrides" to """{"t1":"skip","t2":"bill:Rent"}""",
    )

    private val made = LocalDate.parse("2026-10-28")

    private fun roundTrip() = readBackup(backupJson(budget, bank, made, "3.4.0"))

    @Test
    fun budgetValuesComeBackWithTheirTypes() {
        val backup = roundTrip()
        assertEquals(budget, backup.budget)
        assertEquals(5, backup.budget["payday"])
        assertEquals(true, backup.budget["week_start_projected"])
    }

    @Test
    fun bankKeepsOnlyAddressAndChoices() {
        val json = backupJson(budget, bank, made, "3.4.0")
        assertFalse(json.contains("SEALED-KEY"))
        assertFalse(json.contains("key_iv"))
        assertFalse(json.contains("\"account\""))
        assertFalse(json.contains("checked_at"))
        val backup = readBackup(json)
        assertEquals("https://bank.example.workers.dev", backup.bankUrl)
        assertEquals(mapOf("t1" to "skip", "t2" to "bill:Rent"), backup.choices)
    }

    @Test
    fun summary() {
        val backup = roundTrip()
        assertEquals(made, backup.made)
        assertEquals(780.0, backup.weeklyIncome!!, 0.0)
        assertEquals(DayOfWeek.FRIDAY, backup.payday)
        assertEquals(2, backup.billCount)
        assertEquals(1842.37, backup.balance!!, 0.0)
        assertEquals(LocalDate.parse("2026-10-27"), backup.balanceUpdated)
    }

    @Test
    fun noBankAndNoBills() {
        val backup = readBackup(backupJson(mapOf("weekly_income" to "500.0", "bills_none" to true), emptyMap<String, Any>(), made, "3.4.0"))
        assertNull(backup.bankUrl)
        assertTrue(backup.choices.isEmpty())
        assertEquals(0, backup.billCount)
        assertNull(backup.balanceUpdated)
    }

    @Test
    fun notABackup() {
        for (text in listOf("", "hello", "[1,2]", """{"name":"something else"}""", """{"budget_backup":1}""")) {
            val e = assertThrows(BackupException::class.java) { readBackup(text) }
            assertEquals(NOT_A_BACKUP, e.message)
        }
    }

    @Test
    fun emptyBudget() {
        val e = assertThrows(BackupException::class.java) {
            readBackup("""{"budget_backup":1,"budget":{}}""")
        }
        assertTrue(e.message!!.contains("empty"))
    }

    @Test
    fun newerVersion() {
        val e = assertThrows(BackupException::class.java) {
            readBackup("""{"budget_backup":${BACKUP_VERSION + 1},"budget":{"balance":"1.0"}}""")
        }
        assertTrue(e.message!!.contains("newer version"))
    }

    @Test
    fun byteOrderMark() {
        assertEquals(budget, readBackup(Char(0xFEFF).toString() + backupJson(budget, bank, made, "3.4.0")).budget)
    }

    @Test
    fun damagedChoicesAreDroppedNotFatal() {
        val backup = readBackup("""{"budget_backup":1,"budget":{"balance":"1.0"},"bank":{"overrides":"not json"}}""")
        assertTrue(backup.choices.isEmpty())
        assertEquals("1.0", backup.budget["balance"])
    }

    @Test
    fun fileName() {
        assertEquals("budget-backup-2026-10-28.json", backupFileName(made))
    }
}
