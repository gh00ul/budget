package com.gh00ul.budget

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.YearMonth
import java.util.Locale

// One month of the budget for the Summary's month card and Export (MonthExport.kt).
//
// Covered:
// - monthPaydays: every payday in the month, including a month that starts on payday.
// - monthBills: every due date in the month in date order (then name), a monthly bill on the 31st in a 30-day month,
//   every-2-weeks bills twice or three times, bills not due that month left out, paid marks carried over.
// - MonthBill status and done (paid, date passed, due today, due).
// - MonthReport totals: pay, bills, bills done / left, spent (purchases minus refunds, never below zero; null without
//   a bank).
// - csvField (commas, quotes, line breaks, text a spreadsheet would run as a formula) and csvAmount (always "-1200.00").
// - monthCsv: byte-order mark, CRLF lines, summary lines (spent and end of month only when known), rows in date order
//   with paychecks before bills on the same day, money out negative, purchase details; monthText in US English.
//
// Everything takes its dates as arguments, so nothing depends on today's date.
class MonthExportTest {

    private fun day(text: String): LocalDate = LocalDate.parse(text)

    private val october = YearMonth.of(2026, 10)

    private fun txn(id: String, date: String, name: String, amount: Double, account: String = "chk", pending: Boolean = false) =
        BankTxn(id, account, day(date), name, amount, pending, "Food And Drink", "")

    private fun report(
        paychecks: List<MonthPay> = listOf(MonthPay(day("2026-10-02"), 780.0, PayStatus.RECEIVED)),
        bills: List<MonthBill> = emptyList(),
        purchases: List<BankTxn>? = null,
        endBillsOnly: Double? = null,
        endIfSpending: Double? = null,
    ) = MonthReport(
        month = october, today = day("2026-10-15"), paychecks = paychecks, bills = bills, purchases = purchases,
        accountNames = mapOf("chk" to "Checking ····1234", "card" to "Visa ····9876"),
        bank = 1842.37, bankIsEstimate = false, safe = 130.13, endBillsOnly = endBillsOnly, endIfSpending = endIfSpending,
    )

    // ---------- Paydays and bills in the month ----------

    @Test
    fun paydaysAreEveryPaydayInTheMonth() {
        assertEquals(listOf("2026-10-02", "2026-10-09", "2026-10-16", "2026-10-23", "2026-10-30").map(::day),
            monthPaydays(october, DayOfWeek.FRIDAY))
        assertEquals(listOf("2026-10-01", "2026-10-08", "2026-10-15", "2026-10-22", "2026-10-29").map(::day),
            monthPaydays(october, DayOfWeek.THURSDAY))
    }

    @Test
    fun paydaysIncludeTheFirstWhenTheMonthStartsOnPayday() {
        // May 1, 2026 is a Friday.
        assertEquals(listOf("2026-05-01", "2026-05-08", "2026-05-15", "2026-05-22", "2026-05-29").map(::day),
            monthPaydays(YearMonth.of(2026, 5), DayOfWeek.FRIDAY))
    }

    @Test
    fun billsAreEveryDueDateInDateOrder() {
        val bills = listOf(
            Bill("Rent", 1200.0, Freq.MONTHLY, day("2026-01-01")),
            Bill("Gym", 25.0, Freq.BIWEEKLY, day("2026-10-03")),
            Bill("Car registration", 180.0, Freq.YEARLY, day("2027-03-12")), // not this month
            Bill("Phone", 65.0, Freq.MONTHLY, day("2026-09-17")),
            Bill("Electric", 95.0, Freq.MONTHLY, day("2026-08-17")), // same day as Phone: by name
        )
        val listed = monthBills(bills, october)
        assertEquals(
            listOf("2026-10-01 Rent", "2026-10-03 Gym", "2026-10-17 Electric", "2026-10-17 Gym", "2026-10-17 Phone",
                "2026-10-31 Gym"),
            listed.map { "${it.date} ${it.name}" },
        )
        assertEquals(1200.0 + 25 * 3 + 65 + 95, listed.sumOf { it.amount }, 1e-9)
    }

    @Test
    fun aBillOnThe31stIsDueOnTheLastDayOfAShortMonth() {
        val listed = monthBills(listOf(Bill("Rent", 900.0, Freq.MONTHLY, day("2026-01-31"))), YearMonth.of(2026, 11))
        assertEquals(listOf(day("2026-11-30")), listed.map { it.date })
    }

    @Test
    fun paidMarksCarryOver() {
        val bill = Bill("Spotify", 11.99, Freq.MONTHLY, day("2026-10-16"), mapOf(day("2026-10-16") to day("2026-10-14")))
        val listed = monthBills(listOf(bill), october).single()
        assertEquals(day("2026-10-14"), listed.paidOn)
        assertEquals(Freq.MONTHLY, listed.freq)
    }

    @Test
    fun billStatus() {
        val today = day("2026-10-15")
        fun due(date: String, paidOn: String? = null) = MonthBill(day(date), "Bill", 10.0, Freq.MONTHLY, paidOn?.let(::day))
        assertEquals("Paid", due("2026-10-20", paidOn = "2026-10-14").status(today))
        assertEquals("Due date passed", due("2026-10-14").status(today))
        assertEquals("Due today", due("2026-10-15").status(today))
        assertEquals("Due", due("2026-10-16").status(today))
        assertTrue(due("2026-10-20", paidOn = "2026-10-14").done(today))
        assertTrue(due("2026-10-14").done(today))
        assertFalse(due("2026-10-15").done(today)) // due today: not out of the bank yet
        assertFalse(due("2026-10-16").done(today))
    }

    // ---------- Totals ----------

    @Test
    fun totals() {
        val r = report(
            paychecks = listOf(
                MonthPay(day("2026-10-02"), 780.0, PayStatus.RECEIVED),
                MonthPay(day("2026-10-23"), 910.0, PayStatus.EXPECTED),
            ),
            bills = listOf(
                MonthBill(day("2026-10-01"), "Rent", 1200.0, Freq.MONTHLY, null), // passed
                MonthBill(day("2026-10-16"), "Spotify", 11.99, Freq.MONTHLY, day("2026-10-14")), // paid early
                MonthBill(day("2026-10-18"), "Phone", 65.0, Freq.MONTHLY, null),
                MonthBill(day("2026-10-28"), "Netflix", 15.49, Freq.MONTHLY, null),
            ),
        )
        assertEquals(1690.0, r.payTotal, 1e-9)
        assertEquals(1292.48, r.billsTotal, 1e-9)
        assertEquals(1211.99, r.billsDone, 1e-9)
        assertEquals(80.49, r.billsLeft, 1e-9)
        assertNull(r.spent) // no bank
    }

    @Test
    fun spentIsPurchasesMinusRefundsNeverBelowZero() {
        val r = report(purchases = listOf(
            txn("1", "2026-10-03", "Grocer", -82.40),
            txn("2", "2026-10-05", "Gas", -40.0, account = "card"),
            txn("3", "2026-10-06", "Grocer refund", 12.40),
        ))
        assertEquals(110.0, r.spent!!, 1e-9)
        assertEquals(0.0, report(purchases = listOf(txn("1", "2026-10-03", "Refund", 5.0))).spent!!, 0.0)
        assertEquals(0.0, report(purchases = emptyList()).spent!!, 0.0)
    }

    // ---------- CSV ----------

    @Test
    fun csvFieldQuotesWhatNeedsIt() {
        assertEquals("Rent", csvField("Rent"))
        assertEquals("", csvField(""))
        assertEquals("\"Smith, John\"", csvField("Smith, John"))
        assertEquals("\"The \"\"Best\"\" Gym\"", csvField("The \"Best\" Gym"))
        assertEquals("\"two\nlines\"", csvField("two\nlines"))
    }

    @Test
    fun csvFieldKeepsSpreadsheetsFromRunningText() {
        assertEquals("\"'=HYPERLINK(\"\"x\"\")\"", csvField("=HYPERLINK(\"x\")"))
        assertEquals("'+1 800 555 0100", csvField("+1 800 555 0100"))
        assertEquals("'-5 refund", csvField("-5 refund"))
        assertEquals("'@SUM(A1)", csvField("@SUM(A1)"))
        assertEquals("'\tTab", csvField("\tTab"))
        assertEquals("A=B", csvField("A=B")) // only at the start
    }

    @Test
    fun csvAmountsAlwaysHaveTwoDecimalsAndADot() {
        assertEquals("-1200.00", csvAmount(-1200.0))
        assertEquals("0.30", csvAmount(0.1 + 0.2))
        assertEquals("0.00", csvAmount(-0.0))
        assertEquals("0.00", csvAmount(-0.004))
        assertEquals("1234567.89", csvAmount(1234567.891))
        assertEquals("15.49", csvAmount(15.49))
    }

    @Test
    fun csvLayout() {
        val csv = monthCsv(report(
            paychecks = listOf(
                MonthPay(day("2026-10-02"), 780.0, PayStatus.RECEIVED),
                MonthPay(day("2026-10-23"), 910.0, PayStatus.EXPECTED),
            ),
            bills = listOf(
                MonthBill(day("2026-10-02"), "Gym", 25.0, Freq.BIWEEKLY, null),
                MonthBill(day("2026-10-16"), "Spotify", 11.99, Freq.MONTHLY, day("2026-10-14")),
            ),
            purchases = listOf(
                txn("1", "2026-10-02", "Joe's Diner, Main St", -18.5, account = "card"),
                txn("2", "2026-10-15", "Coffee", -4.25, pending = true),
            ),
            endBillsOnly = 1203.88, endIfSpending = 850.0,
        ))
        assertTrue(csv.startsWith(BYTE_ORDER_MARK))
        assertTrue(csv.endsWith("\r\n"))
        val lines = csv.removePrefix(BYTE_ORDER_MARK).removeSuffix("\r\n").split("\r\n")
        assertFalse(lines.any { '\n' in it })
        assertEquals(
            listOf(
                "Budget for October 2026",
                "Exported,2026-10-15",
                "",
                "Summary,Amount",
                "Pay this month,1690.00",
                "Bills this month,36.99",
                "Bills still to pay,0.00",
                "Pay minus bills,1653.01",
                "Spent so far (bank purchases minus refunds),22.75",
                "Bank balance today,1842.37",
                "Safe to spend this week,130.13",
                "\"End of month, after bills\",1203.88",
                "\"End of month, if you spend your weekly money\",850.00",
                "",
                "Date,Type,Name,Amount,Status,Details",
                // Same day: paycheck, then bill, then purchase.
                "2026-10-02,Paycheck,Pay,780.00,Received,",
                "2026-10-02,Bill,Gym,-25.00,Due date passed,Every 2 weeks",
                "2026-10-02,Purchase,\"Joe's Diner, Main St\",-18.50,,Visa ····9876",
                "2026-10-15,Purchase,Coffee,-4.25,Pending,Checking ····1234",
                "2026-10-16,Bill,Spotify,-11.99,Paid 2026-10-14,Monthly",
                "2026-10-23,Paycheck,Pay,910.00,Expected,",
            ),
            lines,
        )
    }

    @Test
    fun csvLeavesOutWhatIsntKnown() {
        val csv = monthCsv(report(bills = listOf(MonthBill(day("2026-10-20"), "Phone", 65.0, Freq.MONTHLY, null))))
        assertFalse("Spent so far" in csv) // no bank linked
        assertFalse("End of month" in csv) // a month that's over
        assertTrue("2026-10-20,Bill,Phone,-65.00,Due,Monthly\r\n" in csv)
    }

    @Test
    fun csvSaysWhatIsStillToPay() {
        // Paid, past its date (counted as gone from the bank), and two still to come.
        val csv = monthCsv(report(bills = listOf(
            MonthBill(day("2026-10-01"), "Rent", 1200.0, Freq.MONTHLY, null),
            MonthBill(day("2026-10-14"), "Spotify", 11.99, Freq.MONTHLY, day("2026-10-12")),
            MonthBill(day("2026-10-15"), "Water", 30.0, Freq.MONTHLY, null),
            MonthBill(day("2026-10-20"), "Phone", 65.0, Freq.MONTHLY, null),
        )))
        assertTrue("Bills this month,1306.99\r\nBills still to pay,95.00\r\n" in csv)
    }

    @Test
    fun csvRefundsArePositive() {
        val csv = monthCsv(report(purchases = listOf(txn("1", "2026-10-06", "Store refund", 12.4))))
        assertTrue("2026-10-06,Refund,Store refund,12.40,,Checking ····1234\r\n" in csv)
    }

    // ---------- Text ----------

    @Test
    fun text() {
        val text = monthText(report(
            paychecks = listOf(
                MonthPay(day("2026-10-02"), 780.0, PayStatus.RECEIVED),
                MonthPay(day("2026-10-09"), 780.0, PayStatus.NOT_SEEN),
                MonthPay(day("2026-10-23"), 910.0, PayStatus.EXPECTED),
            ),
            bills = listOf(
                MonthBill(day("2026-10-01"), "Rent", 1200.0, Freq.MONTHLY, null),
                MonthBill(day("2026-10-15"), "Water", 30.0, Freq.MONTHLY, null),
                MonthBill(day("2026-10-16"), "Spotify", 11.99, Freq.MONTHLY, day("2026-10-14")),
                MonthBill(day("2026-10-18"), "Phone", 65.0, Freq.MONTHLY, null),
            ),
            purchases = listOf(txn("1", "2026-10-03", "Grocer", -82.40)),
            endBillsOnly = 1203.88, endIfSpending = 850.0,
        ), Locale.US)
        assertEquals(
            """
            Budget for October 2026 (as of Thu, Oct 15)

            Pay: $2,470.00 · 3 paychecks
            Bills: $1,306.99 · $95.00 still to pay
            Pay minus bills: $1,163.01
            Spent so far: $82.40 (from your bank)
            Bank today: $1,842.37
            Safe to spend this week: $130.13
            End of October: $1,203.88 after bills · $850.00 if you spend your weekly money

            Paychecks
            Fri, Oct 2 · $780.00
            Fri, Oct 9 · $780.00 (not seen in your bank)
            Fri, Oct 23 · $910.00 (expected)

            Bills
            Thu, Oct 1 · Rent · $1,200.00 (due date passed)
            Thu, Oct 15 · Water · $30.00 (due today)
            Fri, Oct 16 · Spotify · $11.99 (paid Oct 14)
            Sun, Oct 18 · Phone · $65.00

            """.trimIndent(),
            text,
        )
    }

    @Test
    fun textWithNothingToPayAndNoBank() {
        val text = monthText(report(
            bills = listOf(MonthBill(day("2026-10-01"), "Rent", 1200.0, Freq.MONTHLY, null)),
        ), Locale.US)
        assertTrue("Bills: $1,200.00\n" in text) // nothing left to pay: no "still to pay"
        assertFalse("Spent so far" in text)
        assertFalse("End of October" in text)
        assertTrue("Pay: $780.00 · 1 paycheck\n" in text)
    }

    @Test
    fun fileNameAndTitle() {
        assertEquals("budget-2026-10.csv", monthFileName(october))
        assertEquals("budget-2027-01.csv", monthFileName(YearMonth.of(2027, 1)))
        assertEquals("October 2026", monthTitle(october, Locale.US))
    }
}
