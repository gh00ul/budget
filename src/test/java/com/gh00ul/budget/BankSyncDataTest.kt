package com.gh00ul.budget

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

// The saved bank data rules in BankSync.kt. Regressions, by DEBUG_REPORT ID:
// - accountFromJson / accountToJson: a saved account reads back the same; one without an id is skipped (F-13: just
//   that one, not the list); a balance that isn't a number is null, never a made-up $0.00 (F-14).
// - carryOverChoices (Phase 5, pending -> posted): a "how it counts" choice follows a pending purchase to its posted
//   copy only when the match is certain: same account, same amount to the cent, posted 1 day before to 10 days
//   after, the posted one new and without a choice, and exactly one candidate each way. Every pending purchase that
//   vanished counts as a candidate, with or without a choice (Phase 6 fix: one purchase's choice could otherwise land
//   on another's posted copy).
// - keptChoices (S-7): a choice survives a reply that's missing a bank while its transaction is at most 70 days old,
//   and is dropped once it's older than that or unknown.
// Dates are relative to LocalDate.now(), as in the production code. These paths don't touch android.util.Log; JSON
// runs on the real org.json (testImplementation), since android.jar's is a stub in JVM tests.
class BankSyncDataTest {

    private val today: LocalDate = LocalDate.now()

    // ---- accountFromJson / accountToJson ----

    @Test
    fun `an account survives a save and load unchanged`() {
        val account = BankAccount("acc_1", "Checking", "1234", "depository", "checking", "First Bank", 1234.56, 1200.05)
        assertSameAccount(account, accountFromJson(accountToJson(account)))
    }

    @Test
    fun `an account without optional fields survives a save and load`() {
        val account = BankAccount("acc_2", "Card", null, "credit", null, null, null, null)
        assertSameAccount(account, accountFromJson(accountToJson(account)))
    }

    @Test
    fun `a negative balance survives a save and load`() {
        val account = BankAccount("acc_3", "Visa", "9876", "credit", "credit card", "First Bank", -250.75, -0.01)
        assertSameAccount(account, accountFromJson(accountToJson(account)))
    }

    @Test
    fun `an account without an id is skipped`() {
        assertNull(accountFromJson(JSONObject().put("name", "Checking").put("type", "depository")))
    }

    @Test
    fun `an account with a blank or null id is skipped`() {
        assertNull(accountFromJson(JSONObject().put("external_id", "   ").put("name", "Checking")))
        assertNull(accountFromJson(JSONObject().put("external_id", JSONObject.NULL).put("name", "Checking")))
    }

    @Test
    fun `a balance that is not a number is null, not zero`() {
        val account = accountFromJson(
            JSONObject().put("external_id", "acc_1").put("current_balance_cents", "abc").put("available_balance_cents", 12345)
        )
        assertNotNull(account)
        assertNull(account!!.current)
        assertEquals(123.45, account.available!!, 1e-9)
    }

    @Test
    fun `a balance of the wrong JSON type is null`() {
        for (value in listOf<Any>(true, JSONObject().put("cents", 100), JSONArray().put(100))) {
            val account = accountFromJson(JSONObject().put("external_id", "acc_1").put("current_balance_cents", value))
            assertNull("$value", account!!.current)
        }
    }

    @Test
    fun `a missing or JSON null balance is null`() {
        val account = accountFromJson(JSONObject().put("external_id", "acc_1").put("available_balance_cents", JSONObject.NULL))
        assertNull(account!!.current)
        assertNull(account.available)
    }

    @Test
    fun `a zero balance stays zero`() {
        val account = accountFromJson(JSONObject().put("external_id", "acc_1").put("current_balance_cents", 0))
        assertEquals(0.0, account!!.current!!, 0.0)
    }

    @Test
    fun `balance cents become dollars`() {
        val account = accountFromJson(JSONObject().put("external_id", "acc_1").put("current_balance_cents", 1234567))
        assertEquals(12345.67, account!!.current!!, 1e-9)
    }

    @Test
    fun `a missing or blank name and type fall back to defaults`() {
        val missing = accountFromJson(JSONObject().put("external_id", "acc_1"))!!
        val blank = accountFromJson(JSONObject().put("external_id", "acc_1").put("name", " ").put("type", ""))!!
        for (account in listOf(missing, blank)) {
            assertEquals("Account", account.name)
            assertEquals("other", account.type)
        }
    }

    @Test
    fun `JSON null optional fields are read as missing`() {
        val json = JSONObject().put("external_id", "acc_1")
        for (key in listOf("mask", "subtype", "institution_name")) json.put(key, JSONObject.NULL)
        val account = accountFromJson(json)!!
        assertNull(account.mask)
        assertNull(account.subtype)
        assertNull(account.institution)
    }

    @Test
    fun `a checking account is recognised`() {
        val account = account(type = "depository", subtype = "checking")
        assertTrue(account.isChecking)
        assertFalse(account.isCredit)
    }

    @Test
    fun `a savings account or one without a subtype is not checking`() {
        assertFalse(account(type = "depository", subtype = "savings").isChecking)
        assertFalse(account(type = "depository", subtype = null).isChecking)
    }

    @Test
    fun `a credit card is credit and not checking`() {
        val account = account(type = "credit", subtype = "credit card")
        assertTrue(account.isCredit)
        assertFalse(account.isChecking)
    }

    @Test
    fun `the label adds the last digits of the account number`() {
        assertEquals("Checking ····1234", account(mask = "1234").label)
    }

    @Test
    fun `the label without a mask is just the name`() {
        assertEquals("Checking", account(mask = null).label)
    }

    // ---- carryOverChoices (pending -> posted) ----

    @Test
    fun `a choice follows a pending purchase to its posted copy`() {
        val carried = carryOverChoices(listOf(pending("p1", 5)), listOf(posted("t1", 3)), mapOf("p1" to "skip"))
        assertEquals("skip", carried["t1"])
    }

    @Test
    fun `the pending purchase keeps its own entry after a carry-over`() {
        val carried = carryOverChoices(listOf(pending("p1", 5)), listOf(posted("t1", 3)), mapOf("p1" to "skip"))
        assertEquals("skip", carried["p1"])
    }

    @Test
    fun `posted one day before the pending date still carries`() {
        val carried = carryOverChoices(listOf(pending("p1", 5)), listOf(posted("t1", 6)), mapOf("p1" to "skip"))
        assertEquals("skip", carried["t1"])
    }

    @Test
    fun `posted ten days after the pending date still carries`() {
        val carried = carryOverChoices(listOf(pending("p1", 12)), listOf(posted("t1", 2)), mapOf("p1" to "skip"))
        assertEquals("skip", carried["t1"])
    }

    @Test
    fun `posted two days before the pending date does not carry`() {
        val choices = mapOf("p1" to "skip")
        assertEquals(choices, carryOverChoices(listOf(pending("p1", 5)), listOf(posted("t1", 7)), choices))
    }

    @Test
    fun `posted eleven days after the pending date does not carry`() {
        val choices = mapOf("p1" to "skip")
        assertEquals(choices, carryOverChoices(listOf(pending("p1", 12)), listOf(posted("t1", 1)), choices))
    }

    @Test
    fun `an amount one cent different does not carry`() {
        val choices = mapOf("p1" to "skip")
        val carried = carryOverChoices(listOf(pending("p1", 5)), listOf(posted("t1", 3, amount = -42.18)), choices)
        assertEquals(choices, carried)
    }

    @Test
    fun `amounts equal to the cent match despite floating point noise`() {
        val noisy = -0.1 - 0.2 // -0.30000000000000004
        val carried = carryOverChoices(
            listOf(pending("p1", 5, amount = noisy)), listOf(posted("t1", 3, amount = -0.30)), mapOf("p1" to "skip"),
        )
        assertEquals("skip", carried["t1"])
    }

    @Test
    fun `a posted transaction on another account does not get the choice`() {
        val choices = mapOf("p1" to "skip")
        val carried = carryOverChoices(listOf(pending("p1", 5)), listOf(posted("t1", 3, account = "card")), choices)
        assertEquals(choices, carried)
    }

    @Test
    fun `a transaction that is still pending does not get the choice`() {
        val choices = mapOf("p1" to "skip")
        assertEquals(choices, carryOverChoices(listOf(pending("p1", 5)), listOf(pending("p2", 3)), choices))
    }

    @Test
    fun `a posted transaction that already has a choice keeps its own`() {
        val choices = mapOf("p1" to "skip", "t1" to "spend")
        assertEquals(choices, carryOverChoices(listOf(pending("p1", 5)), listOf(posted("t1", 3)), choices))
    }

    @Test
    fun `a posted transaction seen before does not get the choice`() {
        val choices = mapOf("p1" to "skip")
        val t1 = posted("t1", 3)
        assertEquals(choices, carryOverChoices(listOf(pending("p1", 5), t1), listOf(t1), choices))
    }

    @Test
    fun `two posted candidates leave the choice where it is`() {
        val choices = mapOf("p1" to "skip")
        val carried = carryOverChoices(listOf(pending("p1", 5)), listOf(posted("t1", 3), posted("t2", 3)), choices)
        assertEquals(choices, carried)
    }

    @Test
    fun `two pending purchases with choices for one posted copy leave it alone`() {
        val choices = mapOf("p1" to "skip", "p2" to "bill:Rent")
        val carried = carryOverChoices(listOf(pending("p1", 5), pending("p2", 4)), listOf(posted("t1", 3)), choices)
        assertEquals(choices, carried)
    }

    @Test
    fun `a vanished pending purchase without a choice blocks the carry-over`() {
        // Same account, same amount, both gone, one posted copy: it may be p2's, so p1's choice must stay put.
        val choices = mapOf("p1" to "skip")
        val carried = carryOverChoices(listOf(pending("p1", 5), pending("p2", 4)), listOf(posted("t1", 3)), choices)
        assertEquals(choices, carried)
    }

    @Test
    fun `a same-amount pending purchase that is still pending does not block the carry-over`() {
        val p2 = pending("p2", 4)
        val carried = carryOverChoices(listOf(pending("p1", 5), p2), listOf(p2, posted("t1", 3)), mapOf("p1" to "skip"))
        assertEquals("skip", carried["t1"])
    }

    @Test
    fun `a vanished pending purchase of another amount does not block the carry-over`() {
        val old = listOf(pending("p1", 5), pending("p2", 4, amount = -9.99))
        val carried = carryOverChoices(old, listOf(posted("t1", 3)), mapOf("p1" to "skip"))
        assertEquals("skip", carried["t1"])
    }

    @Test
    fun `a choice on a posted transaction that disappears does not move`() {
        val choices = mapOf("x1" to "skip")
        assertEquals(choices, carryOverChoices(listOf(posted("x1", 5)), listOf(posted("t1", 3)), choices))
    }

    @Test
    fun `a pending purchase still in the new list does not hand over its choice`() {
        val choices = mapOf("p1" to "skip")
        val p1 = pending("p1", 5)
        assertEquals(choices, carryOverChoices(listOf(p1), listOf(p1, posted("t1", 3)), choices))
    }

    @Test
    fun `two different purchases each carry to their own posted copy`() {
        val old = listOf(pending("p1", 5, amount = -42.17), pending("p2", 5, amount = -9.99))
        val new = listOf(posted("t1", 3, amount = -42.17), posted("t2", 4, amount = -9.99))
        val carried = carryOverChoices(old, new, mapOf("p1" to "skip", "p2" to "bill:Phone"))
        assertEquals("skip", carried["t1"])
        assertEquals("bill:Phone", carried["t2"])
    }

    @Test
    fun `nothing carries when there are no choices`() {
        assertEquals(emptyMap<String, String>(), carryOverChoices(listOf(pending("p1", 5)), listOf(posted("t1", 3)), emptyMap()))
    }

    // ---- keptChoices (S-7) ----

    @Test
    fun `a choice on a transaction the server still sends is kept`() {
        val choices = mapOf("t1" to "spend")
        assertEquals(choices, keptChoices(emptyList(), listOf(posted("t1", 3)), choices, today))
    }

    @Test
    fun `a choice still in the new list is kept even when its old copy is past 70 days`() {
        val choices = mapOf("t1" to "spend")
        assertEquals(choices, keptChoices(listOf(posted("t1", 100)), listOf(posted("t1", 100)), choices, today))
    }

    @Test
    fun `a bank missing from one reply keeps its choices`() {
        val old = listOf(posted("a1", 5, account = "bank_a"), posted("b1", 10, account = "bank_b"))
        val new = listOf(posted("a1", 5, account = "bank_a")) // bank B missing from this reply
        val choices = mapOf("a1" to "spend", "b1" to "bill:Rent")
        assertEquals(choices, keptChoices(old, new, choices, today))
    }

    @Test
    fun `a choice on a transaction exactly 70 days old is kept`() {
        val choices = mapOf("t1" to "skip")
        assertEquals(choices, keptChoices(listOf(posted("t1", 70)), emptyList(), choices, today))
    }

    @Test
    fun `a choice on a transaction 71 days old and no longer sent is dropped`() {
        val choices = mapOf("t1" to "skip")
        assertEquals(emptyMap<String, String>(), keptChoices(listOf(posted("t1", 71)), emptyList(), choices, today))
    }

    @Test
    fun `a choice on a transaction neither list knows is dropped`() {
        val kept = keptChoices(listOf(posted("t1", 5)), listOf(posted("t2", 3)), mapOf("ghost" to "skip"), today)
        assertEquals(emptyMap<String, String>(), kept)
    }

    // ---- helpers ----

    private fun account(type: String = "depository", subtype: String? = "checking", mask: String? = "1234") =
        BankAccount("acc_1", "Checking", mask, type, subtype, "First Bank", 100.0, 90.0)

    private fun pending(id: String, daysAgo: Long, amount: Double = -42.17, account: String = "chk") =
        txn(id, daysAgo, amount, account, pending = true)

    private fun posted(id: String, daysAgo: Long, amount: Double = -42.17, account: String = "chk") =
        txn(id, daysAgo, amount, account, pending = false)

    private fun txn(id: String, daysAgo: Long, amount: Double, account: String, pending: Boolean) =
        BankTxn(id, account, today.minusDays(daysAgo), "Corner Cafe", amount, pending, "Food And Drink", "Food And Drink Coffee")

    private fun assertSameAccount(expected: BankAccount, actual: BankAccount?) {
        assertNotNull(actual)
        actual!!
        assertEquals(expected.id, actual.id)
        assertEquals(expected.name, actual.name)
        assertEquals(expected.mask, actual.mask)
        assertEquals(expected.type, actual.type)
        assertEquals(expected.subtype, actual.subtype)
        assertEquals(expected.institution, actual.institution)
        assertEquals(expected.current, actual.current)
        assertEquals(expected.available, actual.available)
    }
}
