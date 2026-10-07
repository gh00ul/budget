package com.gh00ul.budget

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDate

// How bills are saved and read back (BudgetLogic.kt: billToJson, billFromJson, savedDate, Freq names).
//
// Covered:
// - Round trip of every repeat with paid marks, as an object and through its saved text; the saved shape (repeat by
//   enum name, ISO dates, no id) and the enum names themselves (renaming one would orphan saved bills).
// - Paid marks: dropped only when both the due date and the marking day are more than PAID_MARK_DAYS old (exactly
//   PAID_MARK_DAYS is kept); unreadable or out-of-range marks are skipped without losing the bill.
// - Older saved shapes: v2.0 {name, amount} → monthly on January 1 of this year; v2.1-2.5 {name, amount, day} →
//   that day of January, clamped to 1..31.
// - Unreadable bills give null instead of crashing (F-09 / N-2 regressions): unknown repeat, unreadable date, date
//   outside 1900-2200 (year +999999, 1800), repeat without a date, missing name, missing or non-numeric amount, and
//   an amount of "NaN" / "Infinity" (a non-finite bill would make billToJson throw on the next save; see S-1).
//   Note: a *number* NaN can't be put in a JSONObject at all (org.json and Android both refuse), so text is the only
//   way a non-finite amount reaches billFromJson in a JVM test.
// - savedDate: null / blank / garbage / impossible dates → null; 1899-12-31 and 2201-01-01 → null; 1900-01-01 and
//   2200-12-31 accepted (N-2: dates far outside that range made the date math run for ages).
//
// billFromJson reads LocalDate.now() (this year, and the paid-mark cutoff), so those tests build their dates from today
// and check again if midnight passes mid-test.
class BudgetLogicJsonTest {

    private fun day(text: String): LocalDate = LocalDate.parse(text)

    // Runs a check that depends on today's date, once more if the date changed while it ran.
    private fun onOneDay(check: (today: LocalDate) -> Unit) {
        val today = LocalDate.now()
        try {
            check(today)
        } catch (e: AssertionError) {
            if (LocalDate.now() == today) throw e
            check(LocalDate.now())
        }
    }

    private fun assertSameBill(expected: Bill, actual: Bill?) {
        val bill = actual ?: throw AssertionError("bill didn't load")
        assertEquals(expected.name, bill.name)
        assertEquals(expected.amount, bill.amount, 0.0)
        assertEquals(expected.freq, bill.freq)
        assertEquals(expected.date, bill.date)
        assertEquals(expected.paid, bill.paid)
    }

    private fun load(text: String): Bill? = billFromJson(JSONObject(text))

    // ---- round trip ----

    @Test
    fun `every repeat survives saving and loading with its paid marks`() = onOneDay { today ->
        for (freq in Freq.entries) {
            val bill = Bill(
                "Rent ${freq.label}", 1234.56, freq, LocalDate.of(2025, 1, 31),
                mapOf(today.minusDays(3) to today.minusDays(4), today to today, today.plusDays(20) to today.minusDays(1)),
            )
            assertSameBill(bill, billFromJson(billToJson(bill)))
        }
    }

    @Test
    fun `every repeat survives saving to text and loading it back`() = onOneDay { today ->
        for (freq in Freq.entries) {
            val bill = Bill("Car payment", 80.5, freq, LocalDate.of(2026, 2, 28), mapOf(today.minusDays(1) to today))
            assertSameBill(bill, load(billToJson(bill).toString()))
        }
    }

    @Test
    fun `a whole-dollar amount saved as an integer reads back as that amount`() {
        val bill = load("""{"name":"Rent","amount":1200,"freq":"MONTHLY","date":"2026-01-01","paid":{}}""")
        assertSameBill(Bill("Rent", 1200.0, Freq.MONTHLY, day("2026-01-01")), bill)
    }

    @Test
    fun `a saved bill has its name, amount, repeat by enum name, ISO dates and paid marks, and no id`() {
        val json = billToJson(
            Bill("Insurance", 600.0, Freq.SEMIANNUAL, day("2026-01-31"), mapOf(day("2026-07-31") to day("2026-07-30"))),
        )
        assertEquals(setOf("name", "amount", "freq", "date", "paid"), json.keys().asSequence().toSet())
        assertEquals("Insurance", json.getString("name"))
        assertEquals(600.0, json.getDouble("amount"), 0.0)
        assertEquals("SEMIANNUAL", json.getString("freq"))
        assertEquals("2026-01-31", json.getString("date"))
        val paid = json.getJSONObject("paid")
        assertEquals(setOf("2026-07-31"), paid.keys().asSequence().toSet())
        assertEquals("2026-07-30", paid.getString("2026-07-31"))
    }

    @Test
    fun `repeat names stay the same because saved bills store them`() {
        assertEquals(
            listOf("MONTHLY", "WEEKLY", "BIWEEKLY", "QUARTERLY", "SEMIANNUAL", "YEARLY"),
            Freq.entries.map { it.name },
        )
    }

    // ---- paid marks ----

    @Test
    fun `a paid mark whose due date and marking day are both older than PAID_MARK_DAYS is dropped`() = onOneDay { today ->
        val old = today.minusDays(PAID_MARK_DAYS + 1)
        val bill = Bill("Gym", 50.0, Freq.MONTHLY, day("2025-01-01"), mapOf(old to old))
        assertEquals(emptyMap<LocalDate, LocalDate>(), billFromJson(billToJson(bill))!!.paid)
    }

    @Test
    fun `a paid mark exactly PAID_MARK_DAYS old is kept`() = onOneDay { today ->
        val edge = today.minusDays(PAID_MARK_DAYS)
        val bill = Bill("Gym", 50.0, Freq.MONTHLY, day("2025-01-01"), mapOf(edge to edge))
        assertEquals(mapOf(edge to edge), billFromJson(billToJson(bill))!!.paid)
    }

    @Test
    fun `an old due date marked paid recently is kept`() = onOneDay { today ->
        val mark = today.minusDays(100) to today.minusDays(1)
        val bill = Bill("Gym", 50.0, Freq.MONTHLY, day("2025-01-01"), mapOf(mark))
        assertEquals(mapOf(mark), billFromJson(billToJson(bill))!!.paid)
    }

    @Test
    fun `a recent due date marked paid long before is kept`() = onOneDay { today ->
        val mark = today.minusDays(10) to today.minusDays(PAID_MARK_DAYS + 30)
        val bill = Bill("Gym", 50.0, Freq.MONTHLY, day("2025-01-01"), mapOf(mark))
        assertEquals(mapOf(mark), billFromJson(billToJson(bill))!!.paid)
    }

    @Test
    fun `unreadable paid marks are skipped and the bill still loads`() = onOneDay { today ->
        val good = today.minusDays(2)
        val marks = JSONObject()
            .put("garbage", today.toString())
            .put(today.minusDays(3).toString(), "not a date")
            .put("+999999-01-01", today.toString())
            .put(today.minusDays(1).toString(), "1800-01-01")
            .put(good.toString(), today.toString())
        val json = JSONObject().put("name", "Gym").put("amount", 50.0).put("freq", "MONTHLY").put("date", "2026-01-01")
            .put("paid", marks)
        assertEquals(mapOf(good to today), billFromJson(json)!!.paid)
    }

    @Test
    fun `a paid field that isn't an object loads the bill without marks`() {
        val bill = load("""{"name":"Gym","amount":50,"freq":"MONTHLY","date":"2026-01-01","paid":"2026-10-01"}""")
        assertSameBill(Bill("Gym", 50.0, Freq.MONTHLY, day("2026-01-01")), bill)
    }

    // ---- older saved shapes ----

    @Test
    fun `a v2_0 bill with just a name and amount loads as monthly on January 1 of this year`() = onOneDay { today ->
        assertSameBill(Bill("Rent", 1200.0, Freq.MONTHLY, LocalDate.of(today.year, 1, 1)), load("""{"name":"Rent","amount":1200}"""))
    }

    @Test
    fun `a v2_1 to v2_5 bill with a day of the month loads as monthly on that day of January this year`() = onOneDay { today ->
        assertSameBill(
            Bill("Phone", 80.5, Freq.MONTHLY, LocalDate.of(today.year, 1, 15)),
            load("""{"name":"Phone","amount":80.5,"day":15}"""),
        )
    }

    @Test
    fun `a legacy day of 0 or below becomes the 1st`() = onOneDay { today ->
        assertEquals(LocalDate.of(today.year, 1, 1), load("""{"name":"Phone","amount":80,"day":0}""")!!.date)
        assertEquals(LocalDate.of(today.year, 1, 1), load("""{"name":"Phone","amount":80,"day":-4}""")!!.date)
    }

    @Test
    fun `a legacy day above 31 becomes the 31st`() = onOneDay { today ->
        assertEquals(LocalDate.of(today.year, 1, 31), load("""{"name":"Phone","amount":80,"day":45}""")!!.date)
    }

    // ---- unreadable bills (F-09, N-2) ----

    @Test
    fun `a bill with an unknown repeat doesn't load (F-09)`() {
        assertNull(load("""{"name":"Rent","amount":1200,"freq":"FORTNIGHTLY","date":"2026-01-01"}"""))
    }

    @Test
    fun `a bill with an unreadable date doesn't load (F-09)`() {
        assertNull(load("""{"name":"Rent","amount":1200,"freq":"MONTHLY","date":"2026-13-45"}"""))
        assertNull(load("""{"name":"Rent","amount":1200,"freq":"MONTHLY","date":"yesterday"}"""))
    }

    @Test
    fun `a bill with a repeat but no date doesn't load (F-09)`() {
        assertNull(load("""{"name":"Rent","amount":1200,"freq":"MONTHLY"}"""))
    }

    @Test
    fun `a bill dated in year 999999 doesn't load (N-2)`() {
        assertNull(load("""{"name":"Rent","amount":1200,"freq":"WEEKLY","date":"+999999-01-01"}"""))
    }

    @Test
    fun `a bill dated in 1800 doesn't load (N-2)`() {
        assertNull(load("""{"name":"Rent","amount":1200,"freq":"WEEKLY","date":"1800-06-15"}"""))
    }

    @Test
    fun `a bill dated on the edges of 1900-2200 loads (N-2)`() {
        assertNotNull(load("""{"name":"Rent","amount":1200,"freq":"MONTHLY","date":"1900-01-01"}"""))
        assertNotNull(load("""{"name":"Rent","amount":1200,"freq":"MONTHLY","date":"2200-12-31"}"""))
    }

    @Test
    fun `a bill without a name doesn't load (F-09)`() {
        assertNull(load("""{"amount":1200,"freq":"MONTHLY","date":"2026-01-01"}"""))
    }

    @Test
    fun `a bill without an amount doesn't load (F-09)`() {
        assertNull(load("""{"name":"Rent","freq":"MONTHLY","date":"2026-01-01"}"""))
    }

    @Test
    fun `a bill with a non-numeric amount doesn't load (F-09)`() {
        assertNull(load("""{"name":"Rent","amount":"twelve","freq":"MONTHLY","date":"2026-01-01"}"""))
    }

    @Test
    fun `a bill with a NaN or infinite amount doesn't load`() {
        assertNull(load("""{"name":"Rent","amount":"NaN","freq":"MONTHLY","date":"2026-01-01"}"""))
        assertNull(load("""{"name":"Rent","amount":"Infinity","freq":"MONTHLY","date":"2026-01-01"}"""))
        assertNull(load("""{"name":"Rent","amount":"-Infinity"}"""))
    }

    // ---- savedDate ----

    @Test
    fun `savedDate gives null for null, blank or garbage`() {
        assertNull(savedDate(null))
        assertNull(savedDate(""))
        assertNull(savedDate("   "))
        assertNull(savedDate("garbage"))
        assertNull(savedDate("2026-02-30"))
        assertNull(savedDate(" 2026-10-06"))
    }

    @Test
    fun `savedDate refuses dates before 1900 (N-2)`() {
        assertNull(savedDate("1899-12-31"))
    }

    @Test
    fun `savedDate refuses dates after 2200 (N-2)`() {
        assertNull(savedDate("2201-01-01"))
        assertNull(savedDate("+999999-01-01"))
    }

    @Test
    fun `savedDate accepts 1900-01-01 and 2200-12-31`() {
        assertEquals(day("1900-01-01"), savedDate("1900-01-01"))
        assertEquals(day("2200-12-31"), savedDate("2200-12-31"))
    }

    @Test
    fun `savedDate reads an ordinary saved date`() {
        assertEquals(LocalDate.of(2026, 10, 6), savedDate("2026-10-06"))
    }
}
