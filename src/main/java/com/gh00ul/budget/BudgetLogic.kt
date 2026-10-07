package com.gh00ul.budget

import org.json.JSONException
import org.json.JSONObject
import java.math.RoundingMode
import java.time.DateTimeException
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.DateTimeParseException
import java.time.temporal.ChronoUnit
import kotlin.math.abs

// The budget's plain logic: bills and when they're due, matching bank payments to bills, money in amount fields,
// and how bills are saved. No Android code here, so it can be unit-tested on its own (src/test).

// How long a bill keeps the dates it was marked paid (older marks are dropped when bills load).
internal const val PAID_MARK_DAYS = 60L

// How often a bill repeats: every `days` days, or every `months` months on the same day of the month.
internal enum class Freq(val label: String, val days: Int, val months: Int) {
    MONTHLY("Monthly", 0, 1),
    WEEKLY("Weekly", 7, 0),
    BIWEEKLY("Every 2 weeks", 14, 0),
    QUARTERLY("Every 3 months", 0, 3),
    SEMIANNUAL("Every 6 months", 0, 6),
    YEARLY("Yearly", 0, 12);

    // Average days between due dates.
    val cycleDays: Double get() = if (days > 0) days.toDouble() else months * 365.25 / 12
}

// date = the first (or any) date the bill is due; it repeats from there on.
// paid = due dates marked paid (early, or already taken by autopay) → the day they were marked.
// id = which bill this is while the app is open. A bill is replaced by a new object whenever it changes (a
// bank sync marking it paid, say), so anything holding an older copy (an open edit sheet, a row being
// swiped) finds the current one with indexOfBill(). Not saved; each load gives fresh ids.
internal class Bill(
    val name: String,
    val amount: Double,
    val freq: Freq,
    val date: LocalDate,
    val paid: Map<LocalDate, LocalDate> = emptyMap(),
    val id: Any = Any(),
) {
    // This bill's share of one week, e.g. $1,200 monthly rent ≈ $275.97 a week.
    val perWeek: Double get() = amount * 7 / freq.cycleDays

    // Every scheduled due date in [from, until), paid or not.
    fun dueDates(from: LocalDate, until: LocalDate): List<LocalDate> {
        val start = if (from.isAfter(date)) from else date
        val dates = mutableListOf<LocalDate>()
        if (freq.days > 0) {
            val offset = Math.floorMod(ChronoUnit.DAYS.between(date, start), freq.days.toLong())
            var due = if (offset == 0L) start else start.plusDays(freq.days - offset)
            while (due.isBefore(until)) {
                dates += due
                due = due.plusDays(freq.days.toLong())
            }
        } else {
            val first = YearMonth.from(date)
            var month = YearMonth.from(start)
            while (month.atDay(1).isBefore(until)) {
                if (Math.floorMod(ChronoUnit.MONTHS.between(first, month), freq.months.toLong()) == 0L) {
                    val due = month.atDay(minOf(date.dayOfMonth, month.lengthOfMonth()))
                    if (!due.isBefore(start) && due.isBefore(until)) dates += due
                }
                month = month.plusMonths(1)
            }
        }
        return dates
    }

    // Due dates in [from, until) that haven't been marked paid.
    fun unpaid(from: LocalDate, until: LocalDate) = dueDates(from, until).filter { it !in paid }

    // How many times it was marked paid on a day after `after`, up to and including `through`.
    fun paidBetween(after: LocalDate, through: LocalDate) = paid.values.count { it.isAfter(after) && !it.isAfter(through) }

    // Next unpaid due date on or after today (even years ahead).
    fun nextDue(today: LocalDate): LocalDate {
        var from = today
        repeat(5) {
            val until = from.plusYears(2)
            dueDates(from, until).firstOrNull { it !in paid }?.let { return it }
            from = until
        }
        return date
    }

    // The due date most recently marked paid, if that was done in the past week.
    fun recentPaidMark(today: LocalDate): LocalDate? =
        paid.entries.filter { !it.value.isBefore(today.minusDays(6)) && !it.value.isAfter(today) }
            .maxByOrNull { it.value.toEpochDay() }?.key

    fun withPaid(due: LocalDate, on: LocalDate) = Bill(name, amount, freq, date, paid + (due to on), id)
    fun withoutPaid(due: LocalDate) = Bill(name, amount, freq, date, paid - due, id)
}

// Moving money between your own accounts, paying a card or loan, or borrowing (a loan or pay advance isn't
// a refund). Spending, though: cash from an ATM, money sent through apps like Cash App or Venmo (and money
// back through them), and pay-later installments.
internal fun isTransfer(t: BankTxn): Boolean {
    val category = "${t.category} ${t.detail}".lowercase()
    if ("withdrawal" in category || "from apps" in category || "bnpl" in category) return false
    val name = t.name.uppercase()
    return "transfer in" in category || "transfer out" in category || "loan payment" in category ||
        "loan disbursement" in category ||
        "credit card payment" in category || name.contains("PAYMENT TO CREDIT CARD") ||
        name.contains("CREDIT CARD PAYMENT") || name.contains("CREDIT CARD PMT") ||
        name.contains("PAYMENT THANK YOU") || name.contains("PAYMENT - THANK YOU") ||
        name.startsWith("TRANSFER TO ") || name.startsWith("TRANSFER FROM ")
}

internal fun isPayroll(t: BankTxn): Boolean {
    val name = t.name.uppercase()
    val detail = t.detail.lowercase()
    return "wages" in detail || "salary" in detail || name.contains("PAYROLL") || name.contains("DIR DEP") ||
        name.contains("DIRECT DEP") || name.contains("SALARY")
}

// The words of a bill's name worth matching on ("Phone bill" → just "phone"): generic words like "bill" are left out,
// so "Phone bill" doesn't match "VERIZON WIRELESS" but "Verizon" does.
internal fun billWords(billName: String) =
    billName.lowercase().split(nonWord).filter { it.length >= 3 && it !in genericBillWords }

private val nonWord = Regex("[^a-z0-9]+")
private val genericBillWords = setOf("bill", "the", "and", "pay", "payment", "monthly", "auto", "autopay", "fee")

// Which transactions paid which bill due dates, as transaction id → (index in `bills`, due date). A match is the
// amount (to the cent, near enough), or the name and an amount within 35%, within 4 days of the due date. Choices
// made by hand ("bill:<name>" in `overrides`) come first. `accounts` = where purchases count from.
// This runs on every redraw, over every transaction × bill, so the bill names are split once here and a name is
// only compared when the amount is close enough for it to matter.
internal fun matchBills(
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
    val words = bills.map { billWords(it.name) }
    for (t in txns) {
        if (t.amount >= 0 || t.account !in accounts || t.id in result || overrides[t.id] != null) continue
        val paid = -t.amount
        val txnName = t.name.lowercase()
        bills.forEachIndexed { i, bill ->
            if (bill.amount <= 0) return@forEachIndexed
            val diff = abs(paid - bill.amount)
            val sameAmount = diff <= maxOf(0.5, bill.amount * 0.01)
            if (!sameAmount && diff > bill.amount * 0.35) return@forEachIndexed
            val nameHit = words[i].any { txnName.contains(it) }
            if (!sameAmount && !nameHit) return@forEachIndexed
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

// A whole number of dollars as "1200", cents as "80.5" (no trailing zeros).
internal fun plain(amount: Double) = amount.toBigDecimal().stripTrailingZeros().toPlainString()

// What goes in an amount field: "1200" or "80.50". Always a '.': the amount fields (numberDecimal) only let
// digits and '.' be typed, whatever the phone's language.
internal fun amountText(amount: Double) =
    if (amount == Math.rint(amount)) plain(amount) else amount.toBigDecimal().setScale(2, RoundingMode.HALF_UP).toPlainString()

// Accepts "1,234.56", "$50", "-20". Anything empty, unreadable or absurdly large counts as no answer. (Everything
// but digits, '.' and '-' is dropped, so "1e9" reads as 19 — the amount fields can't take letters anyway.)
internal fun parseMoney(text: String): Double? =
    text.replace(notMoney, "").toDoubleOrNull()?.takeIf { it.isFinite() && abs(it) < 1e9 }

private val notMoney = Regex("[^0-9.-]") // compiled once: parseMoney runs on every keystroke

internal fun billToJson(bill: Bill) = JSONObject()
    .put("name", bill.name).put("amount", bill.amount)
    .put("freq", bill.freq.name).put("date", bill.date.toString())
    .put("paid", JSONObject().apply { for ((due, on) in bill.paid) put(due.toString(), on.toString()) })

// A saved bill, or null if it can't be read: damaged, or written by a newer version (load() keeps those as
// they are). Only "this data is unreadable" errors are caught here.
internal fun billFromJson(json: JSONObject): Bill? = try {
    val (freq, date) = if (json.has("freq")) {
        Freq.valueOf(json.getString("freq")) to LocalDate.parse(json.getString("date"))
    } else {
        // Bills saved before v2.6 were monthly with just a day of the month. January has 31 days,
        // so any day fits.
        Freq.MONTHLY to LocalDate.of(LocalDate.now().year, 1, json.optInt("day", 1).coerceIn(1, 31))
    }
    val amount = json.getDouble("amount")
    // Paid marks older than two months don't matter any more.
    val cutoff = LocalDate.now().minusDays(PAID_MARK_DAYS)
    val paid = mutableMapOf<LocalDate, LocalDate>()
    json.optJSONObject("paid")?.let { marks ->
        for (key in marks.keys()) {
            val due = savedDate(key) ?: continue
            val on = savedDate(marks.optString(key)) ?: continue
            if (!due.isBefore(cutoff) || !on.isBefore(cutoff)) paid[due] = on
        }
    }
    if (!amount.isFinite() || savedDate(date.toString()) == null) null
    else Bill(json.getString("name"), amount, freq, date, paid)
} catch (e: JSONException) {
    null
} catch (e: DateTimeException) {
    null
} catch (e: IllegalArgumentException) { // an unknown repeat (Freq.valueOf)
    null
}

// A saved date ("2026-10-06"), or null if it's unreadable or outside 1900-2200. Only damaged data is that far
// out, and dates like that would make the date math run for ages.
internal fun savedDate(text: String?): LocalDate? {
    if (text == null) return null
    val date = try {
        LocalDate.parse(text)
    } catch (e: DateTimeParseException) {
        return null
    }
    return date.takeIf { it.year in 1900..2200 }
}
