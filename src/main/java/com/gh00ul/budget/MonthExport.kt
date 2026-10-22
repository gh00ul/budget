package com.gh00ul.budget

import java.math.RoundingMode
import java.text.NumberFormat
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.time.temporal.TemporalAdjusters
import java.util.Locale

// One month of the budget, for the Summary's month card and "Export": what's built from the saved data
// (Budget.monthReport) and the two ways it's written out, a spreadsheet file (CSV) and text to share. No Android
// code, so it can be unit-tested on its own.

internal enum class PayStatus(val text: String) {
    RECEIVED("Received"),
    EXPECTED("Expected"),
    NOT_SEEN("Not seen in your bank"), // bank linked, but no matching deposit two days on
}

internal class MonthPay(val date: LocalDate, val amount: Double, val status: PayStatus)

// One time a bill is due in the month. paidOn = the day it was marked paid, if it was.
internal class MonthBill(val date: LocalDate, val name: String, val amount: Double, val freq: Freq, val paidOn: LocalDate?) {
    // Marked paid, or its date has passed (the app then counts it as gone from the bank).
    fun done(today: LocalDate) = paidOn != null || date.isBefore(today)

    fun status(today: LocalDate) = when {
        paidOn != null -> "Paid"
        date.isBefore(today) -> "Due date passed"
        date == today -> "Due today"
        else -> "Due"
    }
}

internal class MonthReport(
    val month: YearMonth,
    val today: LocalDate,
    val paychecks: List<MonthPay>,
    val bills: List<MonthBill>,
    val purchases: List<BankTxn>?, // the bank's purchases and refunds this month; null when no bank is linked
    val accountNames: Map<String, String>, // bank account id → "Checking ····1234"
    val bank: Double, // today
    val bankIsEstimate: Boolean,
    val safe: Double, // safe to spend this week
    val endBillsOnly: Double?, // the bank on the month's last day, after bills; null for a month that's over
    val endIfSpending: Double?, // ...and if the weekly money is spent too
) {
    val payTotal get() = cents(paychecks.sumOf { it.amount })
    val billsTotal get() = cents(bills.sumOf { it.amount })
    val billsDone get() = cents(bills.filter { it.done(today) }.sumOf { it.amount })
    val billsLeft get() = cents(billsTotal - billsDone)
    val spent: Double? get() = purchases?.let { list -> cents(maxOf(0.0, -list.sumOf { it.amount })) }
}

// Every payday in the month.
internal fun monthPaydays(month: YearMonth, payday: DayOfWeek): List<LocalDate> =
    generateSequence(month.atDay(1).with(TemporalAdjusters.nextOrSame(payday))) { it.plusWeeks(1) }
        .takeWhile { YearMonth.from(it) == month }
        .toList()

// Every time a bill is due in the month, in date order.
internal fun monthBills(bills: List<Bill>, month: YearMonth): List<MonthBill> =
    bills.flatMap { bill ->
        bill.dueDates(month.atDay(1), month.atEndOfMonth().plusDays(1))
            .map { MonthBill(it, bill.name, bill.amount, bill.freq, bill.paid[it]) }
    }.sortedWith(compareBy<MonthBill> { it.date }.thenBy { it.name })

internal fun monthTitle(month: YearMonth, locale: Locale = Locale.getDefault()) =
    "${month.month.getDisplayName(TextStyle.FULL, locale)} ${month.year}"

// "budget-2026-10.csv"
internal fun monthFileName(month: YearMonth) = "budget-$month.csv"

// The month as a spreadsheet: a short summary, then one row per paycheck, bill and bank purchase. Amounts are plain
// numbers ("-1200.00": money out is negative) so a spreadsheet can add them up, and dates are 2026-10-01. It starts
// with a byte-order mark so Excel reads it as UTF-8, and lines end in CRLF (RFC 4180).
internal fun monthCsv(r: MonthReport): String {
    val rows = mutableListOf<List<String>>()
    fun text(vararg cells: String) = cells.map(::csvField)
    rows += text("Budget for ${monthTitle(r.month, Locale.US)}")
    rows += text("Exported", r.today.toString())
    rows += emptyList<String>()
    rows += text("Summary", "Amount")
    fun line(label: String, amount: Double) {
        rows += text(label) + csvAmount(amount)
    }
    line("Pay this month", r.payTotal)
    line("Bills this month", r.billsTotal)
    line("Bills still to pay", r.billsLeft)
    line("Pay minus bills", r.payTotal - r.billsTotal)
    r.spent?.let { line("Spent so far (bank purchases minus refunds)", it) }
    line(if (r.bankIsEstimate) "Bank balance today (estimated)" else "Bank balance today", r.bank)
    line("Safe to spend this week", r.safe)
    r.endBillsOnly?.let { line("End of month, after bills", it) }
    r.endIfSpending?.let { line("End of month, if you spend your weekly money", it) }
    rows += emptyList<String>()
    rows += text("Date", "Type", "Name", "Amount", "Status", "Details")

    class Entry(val date: LocalDate, val order: Int, val type: String, val name: String, val amount: Double,
                val status: String, val details: String)
    val entries = mutableListOf<Entry>()
    for (p in r.paychecks) entries += Entry(p.date, 0, "Paycheck", "Pay", p.amount, p.status.text, "")
    for (b in r.bills) {
        val status = b.paidOn?.let { "Paid $it" } ?: b.status(r.today)
        entries += Entry(b.date, 1, "Bill", b.name, -b.amount, status, b.freq.label)
    }
    for (t in r.purchases.orEmpty()) {
        entries += Entry(t.date, 2, if (t.amount > 0) "Refund" else "Purchase", t.name, t.amount,
            if (t.pending) "Pending" else "", r.accountNames[t.account].orEmpty())
    }
    for (e in entries.sortedWith(compareBy<Entry> { it.date }.thenBy { it.order }.thenBy { it.name })) {
        rows += listOf(csvField(e.date.toString()), csvField(e.type), csvField(e.name), csvAmount(e.amount),
            csvField(e.status), csvField(e.details))
    }
    return BYTE_ORDER_MARK + rows.joinToString("") { it.joinToString(",") + "\r\n" }
}

// The month as text for a message or email.
internal fun monthText(r: MonthReport, locale: Locale = Locale.getDefault()): String {
    val money = NumberFormat.getCurrencyInstance(locale)
    val day = DateTimeFormatter.ofPattern("EEE, MMM d", locale)
    val shortDay = DateTimeFormatter.ofPattern("MMM d", locale)
    val monthName = r.month.month.getDisplayName(TextStyle.FULL, locale)
    val unpaid = r.bills.count { !it.done(r.today) }
    return buildString {
        append("Budget for ${monthTitle(r.month, locale)} (as of ${r.today.format(day)})\n\n")
        append("Pay: ${money.format(r.payTotal)} · ${r.paychecks.size} paycheck${if (r.paychecks.size == 1) "" else "s"}\n")
        append("Bills: ${money.format(r.billsTotal)}")
        if (unpaid > 0) append(" · ${money.format(r.billsLeft)} still to pay")
        append("\n")
        append("Pay minus bills: ${money.format(cents(r.payTotal - r.billsTotal))}\n")
        r.spent?.let { append("Spent so far: ${money.format(it)} (from your bank)\n") }
        append("Bank today: ${money.format(r.bank)}${if (r.bankIsEstimate) " (estimated)" else ""}\n")
        append("Safe to spend this week: ${money.format(r.safe)}\n")
        if (r.endBillsOnly != null) {
            append("End of $monthName: ${money.format(r.endBillsOnly)} after bills")
            r.endIfSpending?.let { append(" · ${money.format(it)} if you spend your weekly money") }
            append("\n")
        }
        if (r.paychecks.isNotEmpty()) {
            append("\nPaychecks\n")
            for (p in r.paychecks) {
                append("${p.date.format(day)} · ${money.format(p.amount)}")
                if (p.status != PayStatus.RECEIVED) append(" (${p.status.text.lowercase(locale)})")
                append("\n")
            }
        }
        if (r.bills.isNotEmpty()) {
            append("\nBills\n")
            for (b in r.bills) {
                append("${b.date.format(day)} · ${b.name} · ${money.format(b.amount)}")
                when {
                    b.paidOn != null -> append(" (paid ${b.paidOn.format(shortDay)})")
                    !b.date.isAfter(r.today) -> append(" (${b.status(r.today).lowercase(locale)})")
                }
                append("\n")
            }
        }
    }.trimEnd() + "\n"
}

// Put at the start of the file so Excel reads it as UTF-8 (it would garble "····1234" otherwise).
internal val BYTE_ORDER_MARK = Char(0xFEFF).toString()

// A text cell: quoted when it holds a comma, quote or line break. Text a spreadsheet would run as a formula (it starts
// with = + - @, a tab or a return; bank transaction names come from outside the app) gets a ' in front.
internal fun csvField(text: String): String {
    val safe = if (text.isNotEmpty() && text[0] in "=+-@\t\r") "'$text" else text
    return if (safe.any { it == ',' || it == '"' || it == '\n' || it == '\r' }) "\"${safe.replace("\"", "\"\"")}\"" else safe
}

// A number cell: "-1200.00", always with a '.' and two decimals, whatever the phone's language.
internal fun csvAmount(amount: Double): String = cents(amount).toBigDecimal().setScale(2, RoundingMode.HALF_UP).toPlainString()
