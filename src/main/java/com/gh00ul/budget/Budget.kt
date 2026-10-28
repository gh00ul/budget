package com.gh00ul.budget

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.YearMonth
import java.time.temporal.ChronoUnit
import java.time.temporal.TemporalAdjusters
import kotlin.math.abs

// The saved budget ("budget" preferences, plus the bank's in BankStore) and the money math on it. The app screen
// (MainActivity), the home-screen widget (BudgetWidget) and the monthly export all work from this, so they always show
// the same numbers. Nothing is read until load(); the context is only used then, so a screen can make one of these
// while it's still being created.
internal class Budget(private val context: Context) {
    val prefs: SharedPreferences by lazy { context.getSharedPreferences("budget", Context.MODE_PRIVATE) }

    // Bank sync (BankSync.kt). The balance comes from checking; purchases on checking and cards count as spent.
    val bank by lazy { BankStore(context) }

    val bills = mutableListOf<Bill>()
    var balance = 0.0
    var balanceUpdated: LocalDate? = null
    var pendingPay: LocalDate? = null // payday whose paycheck hadn't landed when the balance was entered
    var earlyPay: LocalDate? = null // an upcoming payday whose paycheck is already in the synced balance
    var weeklyIncome = 0.0
    var payday: DayOfWeek = DayOfWeek.FRIDAY
    var billsNone = false // said "I have no regular bills" during setup
    // One-off paycheck amounts (overtime, short week) that replace the weekly income on that date.
    val paycheckChanges = mutableMapOf<LocalDate, Double>()
    // Where "spent since payday" counts from: a balance and the day it applies to. Usually the last balance
    // carried forward to payday ("projected"), otherwise the first balance entered this week.
    var weekStart: LocalDate? = null // the payday that began the period
    var weekStartBalance = 0.0
    var weekStartTaken: LocalDate? = null
    var weekStartProjected = false

    // Saved bills this version can't read (damaged, or written by a newer version). They're written back untouched
    // on every save instead of being dropped, so nothing is lost for good.
    val unreadableBills = mutableListOf<Any>()

    // ---------- The money math ----------

    fun lastPayday(today: LocalDate): LocalDate = today.with(TemporalAdjusters.previousOrSame(payday))

    fun paycheckOn(date: LocalDate) = paycheckChanges[date] ?: weeklyIncome

    // The balance entered, plus a paycheck that hadn't landed yet, minus one that landed before its payday
    // (moving the balance forward adds it back on that payday).
    fun effectiveBalance(): Double {
        val updated = balanceUpdated ?: return balance
        return balance +
            (pendingPay?.takeIf { !it.isAfter(updated) }?.let { paycheckOn(it) } ?: 0.0) -
            (earlyPay?.takeIf { it.isAfter(updated) }?.let { paycheckOn(it) } ?: 0.0)
    }

    // Unpaid bills due in [from, until).
    fun billsDueIn(from: LocalDate, until: LocalDate) = cents(bills.sumOf { it.amount * it.unpaid(from, until).size })

    // Money that left the bank for bills between a balance on `from` and one on `to`: scheduled bills due in
    // [from, to) that weren't marked paid, plus bills marked paid on a day in (from, to].
    fun billsOut(from: LocalDate, to: LocalDate) =
        bills.sumOf { it.amount * (it.unpaid(from, to).size + it.paidBetween(from, to)) }

    // A balance entered on `from`, moved forward to `to`: plus paychecks after `from` (up to and including
    // `to`), minus bills that went out in between.
    fun project(amount: Double, from: LocalDate, to: LocalDate): Double {
        if (!from.isBefore(to)) return amount
        val pays = generateSequence(from.with(TemporalAdjusters.next(payday))) { it.plusWeeks(1) }
            .takeWhile { !it.isAfter(to) }
            .sumOf { paycheckOn(it) }
        return amount + pays - billsOut(from, to)
    }

    private fun currentBalance(today: LocalDate) = balanceUpdated?.let { project(effectiveBalance(), it, today) } ?: balance

    // The bill buffer: how much has to stay in the bank today so every unpaid bill over the next year gets
    // paid on time, given that each paycheck puts one week's share of the bills toward them (or all of it, if
    // the paycheck is smaller than that).
    private fun setAside(today: LocalDate, share: Double, bank: Double): Buffer {
        val horizon = today.plusDays(372)
        val paydays = generateSequence(today.with(TemporalAdjusters.next(payday))) { it.plusWeeks(1) }
            .takeWhile { !it.isAfter(horizon) }
            .toList()
        var next = 0
        var contributed = 0.0
        var total = 0.0
        var worst = 0.0
        var tightest: Pair<Bill, LocalDate>? = null
        var firstShort: Pair<Bill, LocalDate>? = null
        bills.flatMap { bill -> bill.unpaid(today, horizon).map { it to bill } }
            .sortedBy { it.first }
            .forEach { (date, bill) ->
                while (next < paydays.size && !paydays[next].isAfter(date)) {
                    contributed += minOf(share, paycheckOn(paydays[next]))
                    next++
                }
                total += bill.amount
                val need = total - contributed
                if (need > worst + 0.004) {
                    worst = need
                    tightest = bill to date
                }
                if (firstShort == null && need > bank + 0.004) firstShort = bill to date
            }
        return Buffer(cents(worst), tightest, firstShort)
    }

    // Safe to spend = the smaller of
    //   spending money: this week's pay − the bills' weekly share − what's been spent since payday, and
    //   spare money: what's in the bank − the bill buffer.
    fun computeWeek(today: LocalDate): Week {
        val period = lastPayday(today)
        val next = today.with(TemporalAdjusters.next(payday))
        val pay = paycheckOn(period)
        val share = cents(bills.sumOf { it.perWeek })
        // With the bank linked, spent = this pay week's purchases, so the whole week always counts.
        val bankSpent = if (bank.isLinked && bank.checkedAt > 0) bankSpending(period, today).first else null
        val start = weekStartTaken
        val tracking = bankSpent == null && weekStart == period && start != null
        // Tracking that only started mid-week (no earlier balance to carry forward) covers the rest of the week.
        val daysTracked =
            if (tracking && start!!.isAfter(period)) ChronoUnit.DAYS.between(start, next).coerceIn(1, 7) else 7L
        val spent = when {
            bankSpent != null -> bankSpent
            tracking -> cents(maxOf(0.0, weekStartBalance - effectiveBalance() - billsOut(start!!, balanceUpdated ?: today)))
            else -> null
        }
        val afterBills = cents(pay - share)
        val weekMoney = cents(afterBills * daysTracked / 7)
        val spendingMoney = cents(weekMoney - (spent ?: 0.0))
        val bankNow = cents(currentBalance(today))
        val buffer = setAside(today, share, bankNow)
        val free = cents(bankNow - buffer.amount)
        return Week(
            payday = period, nextPayday = next, daysLeft = ChronoUnit.DAYS.between(today, next),
            pay = pay, billsShare = share, afterBills = afterBills, daysTracked = daysTracked, weekMoney = weekMoney,
            trackedFrom = if (bankSpent != null) period else if (tracking) start else null,
            startBalance = if (tracking) weekStartBalance else null,
            spent = spent, spendingMoney = spendingMoney, bank = bankNow,
            bankIsEstimate = balanceUpdated?.isBefore(today) == true && abs(bankNow - balance) > 0.004,
            setAside = buffer.amount, tightest = buffer.tightest, firstShort = buffer.firstShort,
            free = free, safe = cents(minOf(spendingMoney, free)), fromBank = bankSpent != null,
        )
    }

    // The month the end-of-month numbers are for: this month, or next month once this month has no paydays left.
    fun targetMonthEnd(today: LocalDate, nextPayday: LocalDate): LocalDate {
        val monthEnd = YearMonth.from(today).atEndOfMonth()
        return if (!nextPayday.isAfter(monthEnd)) monthEnd else YearMonth.from(today).plusMonths(1).atEndOfMonth()
    }

    // The bank on `end` (a last day of the month): after bills and before any other spending, and if you spend your
    // weekly money. Today's paycheck (if today is payday) is already in the balance.
    fun monthEnd(today: LocalDate, week: Week, end: LocalDate): MonthEnd {
        val afterEnd = end.plusDays(1)
        val paydays = generateSequence(week.nextPayday) { it.plusWeeks(1) }.takeWhile { !it.isAfter(end) }.toList()
        val billsOnly = cents(week.bank + paydays.sumOf { paycheckOn(it) } - billsDueIn(today, afterEnd))
        val thisWeekPart = if (week.daysLeft > 0) minOf(1.0, ChronoUnit.DAYS.between(today, afterEnd).toDouble() / week.daysLeft) else 1.0
        val spendRest = paydays.sumOf { p ->
            maxOf(0.0, paycheckOn(p) - week.billsShare) * minOf(7L, ChronoUnit.DAYS.between(p, afterEnd)) / 7
        }
        val ifSpending = cents(billsOnly - maxOf(0.0, week.safe) * thisWeekPart - spendRest)
        return MonthEnd(end, paydays, billsOnly, ifSpending)
    }

    fun isSetUp() = weeklyIncome > 0 && balanceUpdated != null

    // This month (or any month) for the Summary's month card and the export: every paycheck and bill in it, the
    // bank's purchases (when it's linked; the month card, redrawn often, doesn't need them), and where things stand
    // today.
    fun monthReport(
        month: YearMonth, today: LocalDate, week: Week = computeWeek(today), withPurchases: Boolean = true,
    ): MonthReport {
        val linked = bank.isLinked && bank.checkedAt > 0
        val paychecks = monthPaydays(month, payday).map { date ->
            // With the bank linked, a paycheck that has landed counts at what actually came in.
            val deposit = if (linked && !date.isAfter(today)) payDeposit(date, today) else null
            val status = when {
                deposit != null -> PayStatus.RECEIVED
                date.isAfter(today) || date == today && linked -> PayStatus.EXPECTED
                linked && ChronoUnit.DAYS.between(date, today) >= 2 -> PayStatus.NOT_SEEN
                linked -> PayStatus.EXPECTED // it can land a day late
                else -> PayStatus.RECEIVED
            }
            MonthPay(date, deposit?.amount ?: paycheckOn(date), status)
        }
        val purchases = if (linked && withPurchases) {
            sortTxns().filter { it.kind == Kind.SPEND && YearMonth.from(it.txn.date) == month }.map { it.txn }
                .sortedWith(compareBy<BankTxn> { it.date }.thenBy { it.name })
        } else {
            null
        }
        val end = if (YearMonth.from(today) == month) monthEnd(today, week, month.atEndOfMonth()) else null
        return MonthReport(
            month = month, today = today, paychecks = paychecks, bills = monthBills(bills, month), purchases = purchases,
            accountNames = bank.accounts.associate { it.id to it.label },
            bank = week.bank, bankIsEstimate = week.bankIsEstimate, safe = maxOf(0.0, week.safe),
            endBillsOnly = end?.billsOnly, endIfSpending = end?.ifSpending,
        )
    }

    // ---------- Bank sync ----------

    // Purchases count from the checking account the balance comes from, and from credit cards.
    private fun spendAccounts(): Set<String> =
        bank.accounts.filter { it.id == bank.accountId || it.isCredit }.map { it.id }.toSet()

    // The deposit that is that payday's paycheck: income into checking, at least half the expected pay, from
    // two days before (paid early) to a day after.
    fun payDeposit(payday: LocalDate, upTo: LocalDate = LocalDate.now()): BankTxn? {
        val expected = paycheckOn(payday)
        if (expected <= 0) return null
        val last = minOf(payday.plusDays(1).toEpochDay(), upTo.toEpochDay())
        return bank.txns.firstOrNull {
            it.account == bank.accountId && it.amount >= expected * 0.5 &&
                (isPayroll(it) || it.category.startsWith("Income", true)) &&
                it.date.toEpochDay() in payday.minusDays(2).toEpochDay()..last
        }
    }

    // Which transactions paid which bill due dates (see matchBills in BudgetLogic.kt).
    fun billMatches(): Map<String, Pair<Int, LocalDate>> = matchBills(bills, bank.txns, bank.overrides, spendAccounts())

    private fun sortTxns(): List<Sorted> {
        val accounts = spendAccounts()
        val matches = billMatches()
        val overrides = bank.overrides
        return bank.txns.filter { it.account in accounts }.map { t ->
            val kind = when {
                overrides[t.id] == "skip" -> Kind.SKIPPED
                t.id in matches -> Kind.BILL
                overrides[t.id] == "spend" -> Kind.SPEND
                isTransfer(t) -> Kind.TRANSFER
                t.amount > 0 && (t.category.startsWith("Income", true) || isPayroll(t)) -> Kind.INCOME
                else -> Kind.SPEND // a refund (money back) counts against spending
            }
            Sorted(t, kind, matches[t.id])
        }
    }

    // Spent from `from` through `to`: purchases minus refunds, and every transaction in that time.
    fun bankSpending(from: LocalDate, to: LocalDate): Pair<Double, List<Sorted>> {
        val week = sortTxns().filter { !it.txn.date.isBefore(from) && !it.txn.date.isAfter(to) }
        return cents(maxOf(0.0, -week.filter { it.kind == Kind.SPEND }.sumOf { it.txn.amount })) to week
    }

    // Bills the bank shows as paid get their "paid" mark, dated the day the money left.
    // Payments older than the paid marks kept on bills (PAID_MARK_DAYS) are left alone: their marks were trimmed on
    // purpose, and marking them again would announce the same old bills after every reload.
    fun markBillsFromBank(): List<String> {
        val txnDates = bank.txns.associate { it.id to it.date }
        val cutoff = LocalDate.now().minusDays(PAID_MARK_DAYS)
        val names = mutableListOf<String>()
        for ((txnId, match) in billMatches()) {
            val (i, due) = match
            val bill = bills.getOrNull(i) ?: continue
            if (due in bill.paid) continue
            val paidOn = txnDates[txnId] ?: continue
            if (due.isBefore(cutoff) && paidOn.isBefore(cutoff)) continue
            bills[i] = bill.withPaid(due, paidOn)
            names += bill.name
        }
        return names.distinct()
    }

    // ---------- Loading and saving ----------

    // A saved value of the wrong type can only come from a damaged file. It's treated as missing rather than
    // crashing the app before the update banner (which could bring a fix) shows up; a list is copied aside first.
    private fun <T> read(key: String, get: () -> T): T? = try {
        get()
    } catch (e: ClassCastException) {
        Log.w("Budget", "Ignoring saved \"$key\": wrong type")
        if (key == "bills" || key == "paycheck_changes") keepUnreadable(key, prefs.all[key].toString())
        null
    }

    // Also run again after a restore (replaceSaved), so the lists start empty.
    fun load() {
        bills.clear()
        unreadableBills.clear()
        paycheckChanges.clear()
        fun text(key: String) = read(key) { prefs.getString(key, null) }
        fun date(key: String) = savedDate(text(key))
        fun number(key: String) = text(key)?.toDoubleOrNull()?.takeIf { it.isFinite() }
        fun flag(key: String) = read(key) { prefs.getBoolean(key, false) } ?: false
        balance = number("balance") ?: 0.0
        balanceUpdated = date("balance_updated")
        pendingPay = date("pending_pay")
        earlyPay = date("early_pay")
        weeklyIncome = number("weekly_income") ?: 0.0
        payday = read("payday") { prefs.getInt("payday", DayOfWeek.FRIDAY.value) }?.takeIf { it in 1..7 }
            ?.let { DayOfWeek.of(it) } ?: DayOfWeek.FRIDAY
        billsNone = flag("bills_none")
        text("bills")?.let { saved ->
            try {
                val list = JSONArray(saved)
                for (i in 0 until list.length()) {
                    val item = list.opt(i)
                    val bill = (item as? JSONObject)?.let { billFromJson(it) }
                    when {
                        bill != null -> bills += bill
                        item == null -> Unit
                        // An item JSON can read but not write back (e.g. an amount of NaN) would make the whole
                        // list unwritable on the next save; keep the original text aside instead.
                        !jsonWritable(item) -> keepUnreadable("bills", saved)
                        else -> unreadableBills += item
                    }
                }
                if (unreadableBills.isNotEmpty()) Log.w("Budget", "Keeping ${unreadableBills.size} saved bills this version can't read")
            } catch (e: JSONException) {
                keepUnreadable("bills", saved)
            }
        }

        // Keep paycheck changes from this pay week on, and any the last balance still depends on.
        val periodStart = lastPayday(LocalDate.now())
        val keepFrom = balanceUpdated?.takeIf { it.isBefore(periodStart) } ?: periodStart
        text("paycheck_changes")?.let { saved ->
            try {
                val changes = JSONObject(saved)
                for (key in changes.keys()) {
                    val day = savedDate(key) ?: continue
                    val amount = changes.optDouble(key)
                    if (!day.isBefore(keepFrom) && amount.isFinite()) paycheckChanges[day] = amount
                }
            } catch (e: JSONException) {
                keepUnreadable("paycheck_changes", saved)
            }
        }

        weekStart = date("week_start")
        weekStartBalance = number("week_start_balance") ?: 0.0
        weekStartTaken = date("week_start_taken")
        weekStartProjected = flag("week_start_projected")
    }

    // A whole saved list that isn't valid JSON is copied aside before the next save replaces it, so it can still be
    // recovered (it's included in Android's backup like the rest). The first copy is kept; a later, different one
    // goes next to it.
    private fun keepUnreadable(key: String, raw: String) {
        Log.w("Budget", "Saved \"$key\" is unreadable; keeping a copy")
        val first = "${key}_unreadable"
        val copyKey = if (!prefs.contains(first) || read(first) { prefs.getString(first, null) } == raw) first else "${first}_latest"
        prefs.edit().putString(copyKey, raw).apply()
    }

    fun save() {
        val saved = JSONArray()
        for (bill in bills) saved.put(billToJson(bill))
        for (item in unreadableBills) saved.put(item)
        val changes = JSONObject()
        for ((date, amount) in paycheckChanges) changes.put(date.toString(), amount)
        // A list JSON can't write (toString() would give null) must never erase the saved bills.
        val billsText = if (jsonWritable(saved)) saved.toString() else {
            Log.w("Budget", "Bills couldn't be written; keeping the saved copy")
            read("bills") { prefs.getString("bills", null) }
        }
        prefs.edit()
            .putString("balance", balance.toString())
            .putString("balance_updated", balanceUpdated?.toString())
            .putString("pending_pay", pendingPay?.toString())
            .putString("early_pay", earlyPay?.toString())
            .putString("weekly_income", weeklyIncome.toString())
            .putInt("payday", payday.value)
            .putBoolean("bills_none", billsNone)
            .putString("bills", billsText)
            .putString("paycheck_changes", changes.toString())
            .putString("week_start", weekStart?.toString())
            .putString("week_start_balance", weekStartBalance.toString())
            .putString("week_start_taken", weekStartTaken?.toString())
            .putBoolean("week_start_projected", weekStartProjected)
            .apply()
    }

    // Everything saved is replaced with `values` (a backup's, or what was saved before it, for Undo) and read in
    // again, as on opening the app.
    fun replaceSaved(values: Map<String, *>) {
        val edit = prefs.edit().clear()
        for ((key, value) in values) {
            when (value) {
                is String -> edit.putString(key, value)
                is Int -> edit.putInt(key, value)
                is Boolean -> edit.putBoolean(key, value)
                is Long -> edit.putLong(key, value)
                is Float -> edit.putFloat(key, value)
            }
        }
        edit.apply()
        load()
    }
}

// The "bill buffer": what has to stay in the bank for upcoming bills.
internal class Buffer(
    val amount: Double,
    val tightest: Pair<Bill, LocalDate>?, // where the buffer is needed most
    val firstShort: Pair<Bill, LocalDate>?, // the first bill the bank can't cover, if any
)

// Everything behind "Safe to spend" for the current pay week.
internal class Week(
    val payday: LocalDate, // the payday that started this pay period
    val nextPayday: LocalDate,
    val daysLeft: Long, // days until the next payday
    val pay: Double,
    val billsShare: Double, // one week's share of all bills
    val afterBills: Double, // pay - billsShare
    val daysTracked: Long, // 7, or fewer if spending tracking only started mid-week
    val weekMoney: Double, // afterBills for the tracked days
    val trackedFrom: LocalDate?, // the day "spent" counts from
    val startBalance: Double?, // the balance on that day
    val spent: Double?, // null = no balance entered since payday, so spending is unknown
    val spendingMoney: Double, // weekMoney - spent
    val bank: Double, // the last balance entered, moved forward to today
    val bankIsEstimate: Boolean,
    val setAside: Double, // "bill buffer"
    val tightest: Pair<Bill, LocalDate>?,
    val firstShort: Pair<Bill, LocalDate>?,
    val free: Double, // "spare money": bank - setAside
    val safe: Double, // the smaller of spendingMoney and free
    val fromBank: Boolean, // spent = purchases synced from the bank, not balance changes
)

// The bank on the last day of a month. paydays = the paychecks still to come before then.
internal class MonthEnd(val date: LocalDate, val paydays: List<LocalDate>, val billsOnly: Double, val ifSpending: Double)

// How a synced transaction counts.
internal enum class Kind { SPEND, BILL, TRANSFER, INCOME, SKIPPED }

internal class Sorted(val txn: BankTxn, val kind: Kind, val bill: Pair<Int, LocalDate>?) // bill = (index in bills, due date)
