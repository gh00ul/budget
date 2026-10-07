package com.gh00ul.budget

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import java.util.Random
import kotlin.math.abs

// Which bank payments paid which bills (BudgetLogic.kt: matchBills, billWords).
//
// Covered:
// - T-2 regression: matchBills (bill names split once, names compared only when the amount is close) gives exactly
//   the same matches as the matcher before the speed-up (git show 25b5a8c~1, MainActivity.billMatches/nameMatches,
//   copied below with a Regex per comparison) over 1,500 seeded random scenarios: generic bill names ("Phone bill"),
//   duplicate names, $0 and negative bills, amounts equal / within the cent tolerance / near 1% / near and at 35% /
//   far, deposits, names with and without the bill's words, "bill:<name>" (existing or not), "skip", "spend", and
//   accounts in and out of the spend set.
// - Matching rules: exact amount within 4 days (both sides) matches, 5 days doesn't; within 1% matches without a
//   name; a name hit matches up to 35% off (30% yes, 40% no); generic words aren't a name hit; a name hit beats an
//   amount-only match; one due date isn't taken twice (the closer payment wins); "bill:<name>" chosen by hand wins
//   and takes its due date; "skip" / "spend" / a hand choice for a deleted bill leave the payment unmatched; $0
//   bills, deposits and payments outside the spend accounts are ignored.
// - billWords: which words of a bill's name are matched on.
class BudgetLogicMatchTest {

    private val spend = setOf("checking", "card")

    private fun day(text: String): LocalDate = LocalDate.parse(text)

    private fun monthly(name: String, amount: Double, dayOfMonth: Int = 1) =
        Bill(name, amount, Freq.MONTHLY, LocalDate.of(2026, 1, dayOfMonth))

    private fun txn(id: String, name: String, amount: Double, date: String, account: String = "checking") =
        BankTxn(id, account, day(date), name, amount, false, "", "")

    private fun match(bills: List<Bill>, txns: List<BankTxn>, overrides: Map<String, String> = emptyMap()) =
        matchBills(bills, txns, overrides, spend)

    // ---- the amount ----

    @Test
    fun `an exact amount 4 days after the due date matches without a name`() {
        val result = match(listOf(monthly("Rent", 1200.0)), listOf(txn("t1", "ACH DEBIT 0042", -1200.0, "2026-10-05")))
        assertEquals(mapOf("t1" to (0 to day("2026-10-01"))), result)
    }

    @Test
    fun `an exact amount 4 days before the due date matches`() {
        val result = match(listOf(monthly("Rent", 1200.0)), listOf(txn("t1", "ACH DEBIT 0042", -1200.0, "2026-09-27")))
        assertEquals(mapOf("t1" to (0 to day("2026-10-01"))), result)
    }

    @Test
    fun `an exact amount 5 days from the due date doesn't match`() {
        val txns = listOf(
            txn("after", "ACH DEBIT 0042", -1200.0, "2026-10-06"),
            txn("before", "ACH DEBIT 0042", -1200.0, "2026-09-26"),
        )
        assertEquals(emptyMap<String, Pair<Int, LocalDate>>(), match(listOf(monthly("Rent", 1200.0)), txns))
    }

    @Test
    fun `an amount within 1 percent matches without a name`() {
        val result = match(listOf(monthly("Rent", 1200.0)), listOf(txn("t1", "ACH DEBIT 0042", -1211.99, "2026-10-01")))
        assertEquals(mapOf("t1" to (0 to day("2026-10-01"))), result)
    }

    @Test
    fun `an amount 2 percent off doesn't match without a name`() {
        val result = match(listOf(monthly("Rent", 1200.0)), listOf(txn("t1", "ACH DEBIT 0042", -1224.0, "2026-10-01")))
        assertEquals(emptyMap<String, Pair<Int, LocalDate>>(), result)
    }

    // ---- the name ----

    @Test
    fun `a name hit with an amount 30 percent over matches`() {
        val result = match(listOf(monthly("Verizon", 100.0)), listOf(txn("t1", "VERIZON WIRELESS", -130.0, "2026-10-02")))
        assertEquals(mapOf("t1" to (0 to day("2026-10-01"))), result)
    }

    @Test
    fun `a name hit with an amount 30 percent under matches`() {
        val result = match(listOf(monthly("Verizon", 100.0)), listOf(txn("t1", "VERIZON WIRELESS", -70.0, "2026-10-02")))
        assertEquals(mapOf("t1" to (0 to day("2026-10-01"))), result)
    }

    @Test
    fun `a name hit with an amount 40 percent off doesn't match`() {
        val txns = listOf(
            txn("over", "VERIZON WIRELESS", -140.0, "2026-10-02"),
            txn("under", "VERIZON WIRELESS", -60.0, "2026-10-03"),
        )
        assertEquals(emptyMap<String, Pair<Int, LocalDate>>(), match(listOf(monthly("Verizon", 100.0)), txns))
    }

    @Test
    fun `an amount 30 percent off without a name hit doesn't match`() {
        val result = match(listOf(monthly("Verizon", 100.0)), listOf(txn("t1", "ACME CORP", -130.0, "2026-10-02")))
        assertEquals(emptyMap<String, Pair<Int, LocalDate>>(), result)
    }

    @Test
    fun `generic bill words alone are not a name hit`() {
        val result = match(
            listOf(monthly("The Electric Bill", 100.0)),
            listOf(txn("t1", "THE MONTHLY BILL AUTOPAY FEE", -120.0, "2026-10-02")),
        )
        assertEquals(emptyMap<String, Pair<Int, LocalDate>>(), result)
    }

    @Test
    fun `a name hit is preferred over another bill with the exact amount`() {
        val bills = listOf(monthly("Gym", 50.0), monthly("Verizon", 55.0))
        val result = match(bills, listOf(txn("t1", "VERIZON", -50.0, "2026-10-01")))
        assertEquals(mapOf("t1" to (1 to day("2026-10-01"))), result)
    }

    // ---- one due date, one payment ----

    @Test
    fun `one due date isn't taken by two payments`() {
        val txns = listOf(txn("t1", "ACH DEBIT", -1200.0, "2026-10-01"), txn("t2", "ACH DEBIT", -1200.0, "2026-10-02"))
        assertEquals(mapOf("t1" to (0 to day("2026-10-01"))), match(listOf(monthly("Rent", 1200.0)), txns))
    }

    @Test
    fun `the payment closer to the due date gets it whatever the order`() {
        val txns = listOf(txn("later", "ACH DEBIT", -1200.0, "2026-10-03"), txn("closer", "ACH DEBIT", -1200.0, "2026-10-01"))
        assertEquals(mapOf("closer" to (0 to day("2026-10-01"))), match(listOf(monthly("Rent", 1200.0)), txns))
    }

    // ---- choices made by hand ----

    @Test
    fun `a payment chosen by hand goes to the named bill's nearest due date`() {
        val bills = listOf(monthly("Rent", 1200.0), monthly("Gym", 50.0, dayOfMonth = 15))
        val result = match(bills, listOf(txn("t1", "SOMETHING", -999.0, "2026-10-20")), mapOf("t1" to "bill:Gym"))
        assertEquals(mapOf("t1" to (1 to day("2026-10-15"))), result)
    }

    @Test
    fun `a payment chosen by hand wins over an exact amount match`() {
        val bills = listOf(monthly("Rent", 1200.0), monthly("Gym", 50.0))
        val result = match(bills, listOf(txn("t1", "RENT", -1200.0, "2026-10-01")), mapOf("t1" to "bill:Gym"))
        assertEquals(mapOf("t1" to (1 to day("2026-10-01"))), result)
    }

    @Test
    fun `a due date chosen by hand isn't matched again automatically`() {
        val txns = listOf(txn("t1", "SOMETHING", -999.0, "2026-10-01"), txn("t2", "GYM", -50.0, "2026-10-02"))
        val result = match(listOf(monthly("Gym", 50.0)), txns, mapOf("t1" to "bill:Gym"))
        assertEquals(mapOf("t1" to (0 to day("2026-10-01"))), result)
    }

    @Test
    fun `a hand choice naming a bill that no longer exists leaves the payment unmatched`() {
        val result = match(
            listOf(monthly("Rent", 1200.0)),
            listOf(txn("t1", "RENT", -1200.0, "2026-10-01")),
            mapOf("t1" to "bill:Old rent"),
        )
        assertEquals(emptyMap<String, Pair<Int, LocalDate>>(), result)
    }

    @Test
    fun `a hand choice for a name two bills share goes to the first of them`() {
        val bills = listOf(monthly("Rent", 1200.0), monthly("Gym", 50.0), monthly("Gym", 60.0))
        val result = match(bills, listOf(txn("t1", "SOMETHING", -60.0, "2026-10-01")), mapOf("t1" to "bill:Gym"))
        assertEquals(mapOf("t1" to (1 to day("2026-10-01"))), result)
    }

    @Test
    fun `a payment marked skip isn't matched`() {
        val result = match(listOf(monthly("Rent", 1200.0)), listOf(txn("t1", "RENT", -1200.0, "2026-10-01")), mapOf("t1" to "skip"))
        assertEquals(emptyMap<String, Pair<Int, LocalDate>>(), result)
    }

    @Test
    fun `a payment marked spend isn't matched`() {
        val result = match(listOf(monthly("Rent", 1200.0)), listOf(txn("t1", "RENT", -1200.0, "2026-10-01")), mapOf("t1" to "spend"))
        assertEquals(emptyMap<String, Pair<Int, LocalDate>>(), result)
    }

    // ---- what's never matched ----

    @Test
    fun `a zero-amount bill is never matched`() {
        // Without the check, 40 cents is within the 50-cent tolerance of $0 (and the score would divide by zero).
        val result = match(listOf(monthly("Free trial", 0.0)), listOf(txn("t1", "FREE TRIAL", -0.40, "2026-10-01")))
        assertEquals(emptyMap<String, Pair<Int, LocalDate>>(), result)
    }

    @Test
    fun `a deposit isn't matched`() {
        val result = match(listOf(monthly("Rent", 1200.0)), listOf(txn("t1", "RENT", 1200.0, "2026-10-01")))
        assertEquals(emptyMap<String, Pair<Int, LocalDate>>(), result)
    }

    @Test
    fun `a payment from an account outside the spend accounts isn't matched`() {
        val result = match(listOf(monthly("Rent", 1200.0)), listOf(txn("t1", "RENT", -1200.0, "2026-10-01", account = "savings")))
        assertEquals(emptyMap<String, Pair<Int, LocalDate>>(), result)
    }

    // ---- billWords ----

    @Test
    fun `billWords keeps a brand name`() {
        assertEquals(listOf("verizon"), billWords("Verizon"))
    }

    @Test
    fun `billWords drops generic words but keeps phone in Phone bill`() {
        assertEquals(listOf("phone"), billWords("Phone bill"))
    }

    @Test
    fun `billWords has nothing for a name made only of generic words`() {
        assertEquals(emptyList<String>(), billWords("The Monthly Auto Pay Fee"))
    }

    @Test
    fun `billWords drops words shorter than 3 letters`() {
        assertEquals(emptyList<String>(), billWords("AT&T"))
        assertEquals(listOf("car"), billWords("Car payment #2"))
    }

    @Test
    fun `billWords splits on anything that isn't a letter or digit`() {
        assertEquals(listOf("water", "sewer"), billWords("Water & Sewer"))
    }

    // ---- T-2 regression: same results as before the speed-up ----

    @Test
    fun `matchBills gives the same matches as the matcher before the T-2 speed-up`() {
        var byHand = 0
        var byAmount = 0
        var byNameNotAmount = 0
        repeat(1500) { seed ->
            val s = scenario(Random(seed.toLong()))
            val before = originalBillMatches(s.bills, s.txns, s.overrides, s.accounts)
            val now = matchBills(s.bills, s.txns, s.overrides, s.accounts)
            assertEquals("scenario $seed", before, now)
            for ((id, match) in now) {
                val t = s.txns.first { it.id == id }
                val bill = s.bills[match.first]
                when {
                    s.overrides[id]?.startsWith("bill:") == true -> byHand++
                    abs(-t.amount - bill.amount) <= maxOf(0.5, bill.amount * 0.01) -> byAmount++
                    else -> byNameNotAmount++
                }
            }
        }
        // The scenarios really exercise every way to match, so equal results mean something.
        assertTrue("hand choices: $byHand", byHand >= 100)
        assertTrue("amount matches: $byAmount", byAmount >= 100)
        assertTrue("name matches with a different amount: $byNameNotAmount", byNameNotAmount >= 100)
    }

    private class Scenario(
        val bills: List<Bill>,
        val txns: List<BankTxn>,
        val overrides: Map<String, String>,
        val accounts: Set<String>,
    )

    private val billNames = listOf(
        "Rent", "Phone bill", "Verizon", "Car payment", "Netflix", "Gym membership", "The Electric Bill", "AT&T",
        "Water & Sewer", "Auto insurance", "Monthly fee", "Spotify", "Student loan", "Comcast Internet", "Pay",
    )
    private val billAmounts = listOf(0.0, -5.0, 0.4, 1.0, 9.99, 15.49, 50.0, 55.0, 80.0, 100.0, 120.0, 1200.0, 1234.56)
    private val otherNames = listOf(
        "CHIPOTLE 1234", "SHELL OIL 5521", "AMAZON MKTP US", "ACH DEBIT", "POS PURCHASE", "AUTOPAY THE BILL FEE",
        "ONLINE PAYMENT", "VENMO", "",
    )
    private val nameEndings = listOf("", " WIRELESS", " PMT 0921", " ONLINE PAYMENT", " AUTOPAY", " *1234")
    private val allAccounts = listOf("checking", "card", "savings")

    private fun <T> List<T>.pick(random: Random): T? = if (isEmpty()) null else this[random.nextInt(size)]

    private fun cents(amount: Double) = Math.round(amount * 100) / 100.0

    private fun scenario(r: Random): Scenario {
        val start = LocalDate.of(2026, 9, 1)
        val bills = List(r.nextInt(9)) {
            val amount = if (r.nextInt(3) == 0) r.nextInt(300_000) / 100.0 else billAmounts.pick(r)!!
            Bill(billNames.pick(r)!!, amount, Freq.entries.pick(r)!!, start.plusDays(r.nextInt(700) - 500L))
        }
        val accounts = allAccounts.filter { r.nextInt(4) != 0 }.toSet()
        val txns = List(r.nextInt(31)) { k ->
            val target = bills.pick(r)
            val owed = target?.amount ?: 50.0
            val amount = when (r.nextInt(11)) {
                0, 1 -> -owed // exact
                2 -> -(owed + r.nextDouble() - 0.5) // within 50 cents
                3 -> -cents(owed * (1 + (r.nextDouble() * 2 - 1) * 0.012)) // around 1%
                4 -> -cents(owed * (1 + (r.nextDouble() * 2 - 1) * 0.36)) // around 35%
                5 -> -owed * (if (r.nextBoolean()) 1.35 else 0.65) // exactly 35% off
                6 -> -cents(owed * (1 + (r.nextDouble() * 2 - 1) * 0.3)) // inside 35%
                7 -> -owed * (2 + r.nextDouble() * 3) // far
                8 -> owed // a deposit or refund
                9 -> -r.nextInt(200_000) / 100.0 // anything
                else -> -cents(owed * (1 + (r.nextDouble() * 2 - 1) * 0.5))
            }
            val name = when (r.nextInt(5)) {
                0, 1 -> (target?.name ?: "Verizon").uppercase() + nameEndings.pick(r)
                2 -> billNames.pick(r)!!.uppercase()
                else -> otherNames.pick(r)!!
            }
            BankTxn("t$k", allAccounts.pick(r)!!, start.plusDays(r.nextInt(61).toLong()), name, amount, r.nextBoolean(), "", "")
        }
        val overrides = txns.filter { r.nextInt(5) == 0 }.associate { t ->
            t.id to when (r.nextInt(5)) {
                0, 1 -> "bill:" + (bills.pick(r)?.name ?: "Rent")
                2 -> "bill:Deleted bill"
                3 -> "skip"
                else -> "spend"
            }
        }
        return Scenario(bills, txns, overrides, accounts)
    }

    // ---- the matcher before T-2 (25b5a8c~1, MainActivity.nameMatches / billMatches), unchanged except that the
    // screen's bills, bank.txns, bank.overrides and spendAccounts() are parameters ----

    // "Phone bill" and "VERIZON WIRELESS" don't match; "Verizon" does.
    private fun originalNameMatches(billName: String, txnName: String): Boolean {
        val name = txnName.lowercase()
        return billName.lowercase().split(Regex("[^a-z0-9]+"))
            .any { it.length >= 3 && it !in originalGenericBillWords && name.contains(it) }
    }

    private val originalGenericBillWords = setOf("bill", "the", "and", "pay", "payment", "monthly", "auto", "autopay", "fee")

    private fun originalBillMatches(
        bills: List<Bill>, txns: List<BankTxn>, overrides: Map<String, String>, accounts: Set<String>,
    ): Map<String, Pair<Int, LocalDate>> {
        val result = mutableMapOf<String, Pair<Int, LocalDate>>()
        val taken = mutableSetOf<Pair<Int, LocalDate>>()
        for (t in txns) {
            val name = overrides[t.id]?.takeIf { it.startsWith("bill:") }?.removePrefix("bill:") ?: continue
            val i = bills.indexOfFirst { it.name == name }.takeIf { it >= 0 } ?: continue
            val due = bills[i].dueDates(t.date.minusDays(45), t.date.plusDays(46))
                .minByOrNull { abs(ChronoUnit.DAYS.between(it, t.date)) } ?: continue
            result[t.id] = i to due
            taken += i to due
        }
        class Candidate(val txn: BankTxn, val bill: Int, val due: LocalDate, val score: Double)
        val candidates = mutableListOf<Candidate>()
        for (t in txns) {
            if (t.amount >= 0 || t.account !in accounts || t.id in result || overrides[t.id] != null) continue
            val paid = -t.amount
            bills.forEachIndexed { i, bill ->
                if (bill.amount <= 0) return@forEachIndexed
                val nameHit = originalNameMatches(bill.name, t.name)
                val diff = abs(paid - bill.amount)
                if (diff > maxOf(0.5, bill.amount * 0.01) && !(nameHit && diff <= bill.amount * 0.35)) return@forEachIndexed
                for (due in bill.dueDates(t.date.minusDays(4), t.date.plusDays(5))) {
                    val days = abs(ChronoUnit.DAYS.between(due, t.date))
                    candidates += Candidate(t, i, due, (if (nameHit) 0.0 else 100.0) + diff / bill.amount * 50 + days)
                }
            }
        }
        for (c in candidates.sortedBy { it.score }) {
            if (c.txn.id in result || (c.bill to c.due) in taken) continue
            result[c.txn.id] = c.bill to c.due
            taken += c.bill to c.due
        }
        return result
    }
}
