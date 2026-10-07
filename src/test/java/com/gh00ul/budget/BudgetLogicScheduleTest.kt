package com.gh00ul.budget

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.DayOfWeek
import java.time.LocalDate
import java.util.Random

// When bills are due (BudgetLogic.kt: Freq, Bill).
//
// Covered:
// - dueDates: monthly on the 31st (Feb 28/29, Apr 30, back to the 31st); weekly/every-2-weeks offsets from an old start;
//   every 3 / 6 / 12 months cycling and clamping; a bill starting after `from`; `from` inclusive, `until` exclusive;
//   empty when until <= from; every repeat checked against a brute-force schedule over seeded random dates.
// - unpaid, paidBetween (after exclusive, through inclusive), nextDue (skips paid, first unpaid on/after today, years
//   ahead), recentPaidMark (marked within the last 6 days, by marking date).
// - cycleDays and perWeek numbers.
// - withPaid / withoutPaid keep the bill's id and don't change the original (F-06 regression: a bill edited while a
//   bank sync marked it paid lost the edit, because bills were tracked by object identity).
//
// Everything here takes its dates as arguments, so nothing depends on today's date.
class BudgetLogicScheduleTest {

    private fun day(text: String): LocalDate = LocalDate.parse(text)

    private fun days(vararg text: String): List<LocalDate> = text.map(LocalDate::parse)

    private fun bill(freq: Freq, date: String, amount: Double = 100.0, paid: Map<LocalDate, LocalDate> = emptyMap()) =
        Bill("Test", amount, freq, day(date), paid)

    // The schedule worked out the slow way: step from the bill's first date (months from the first date each time,
    // so the 31st comes back after a short month) and keep what falls in [from, until).
    private fun slowDueDates(freq: Freq, first: LocalDate, from: LocalDate, until: LocalDate): List<LocalDate> {
        val dates = mutableListOf<LocalDate>()
        var k = 0L
        while (true) {
            val due = if (freq.days > 0) first.plusDays(k * freq.days) else first.plusMonths(k * freq.months)
            if (!due.isBefore(until)) return dates
            if (!due.isBefore(from)) dates += due
            k++
        }
    }

    // ---- dueDates: monthly ----

    @Test
    fun `monthly on the 31st falls on the last day of shorter months and comes back to the 31st`() {
        val rent = bill(Freq.MONTHLY, "2025-01-31")
        assertEquals(
            days("2025-01-31", "2025-02-28", "2025-03-31", "2025-04-30", "2025-05-31"),
            rent.dueDates(day("2025-01-01"), day("2025-06-01")),
        )
    }

    @Test
    fun `monthly on the 31st falls on February 29 in a leap year`() {
        val rent = bill(Freq.MONTHLY, "2023-12-31")
        assertEquals(days("2024-01-31", "2024-02-29", "2024-03-31"), rent.dueDates(day("2024-01-01"), day("2024-04-01")))
    }

    @Test
    fun `a bill that starts after from has no due dates before its start`() {
        val gym = bill(Freq.MONTHLY, "2026-03-15")
        assertEquals(days("2026-03-15", "2026-04-15", "2026-05-15"), gym.dueDates(day("2026-01-01"), day("2026-06-01")))
    }

    @Test
    fun `from is inclusive and until is exclusive`() {
        val gym = bill(Freq.MONTHLY, "2026-01-15")
        assertEquals(days("2026-03-15"), gym.dueDates(day("2026-03-15"), day("2026-04-15")))
    }

    // ---- dueDates: weekly and every 2 weeks ----

    @Test
    fun `weekly from an old start date lands on the same weekday every 7 days`() {
        val groceries = bill(Freq.WEEKLY, "2020-01-03") // a Friday
        val dates = groceries.dueDates(day("2026-10-01"), day("2026-11-01"))
        assertEquals(days("2026-10-02", "2026-10-09", "2026-10-16", "2026-10-23", "2026-10-30"), dates)
        assertTrue(dates.all { it.dayOfWeek == DayOfWeek.FRIDAY })
    }

    @Test
    fun `every 2 weeks keeps its offset from an old start date`() {
        val childcare = bill(Freq.BIWEEKLY, "2026-01-02")
        // 2026-10-09 is 280 days (20 × 14) after the start; 2026-10-02 would be 273, which isn't a multiple of 14.
        assertEquals(days("2026-10-09", "2026-10-23"), childcare.dueDates(day("2026-10-01"), day("2026-11-01")))
    }

    @Test
    fun `weekly includes from when from is a due date`() {
        val groceries = bill(Freq.WEEKLY, "2026-01-02")
        assertEquals(days("2026-10-02", "2026-10-09"), groceries.dueDates(day("2026-10-02"), day("2026-10-10")))
    }

    @Test
    fun `weekly that starts after from begins on its start date`() {
        val groceries = bill(Freq.WEEKLY, "2026-03-04")
        assertEquals(days("2026-03-04", "2026-03-11", "2026-03-18"), groceries.dueDates(day("2026-03-01"), day("2026-03-20")))
    }

    // ---- dueDates: every 3, 6 and 12 months ----

    @Test
    fun `every 3 months cycles from its start month and clamps to short months`() {
        val water = bill(Freq.QUARTERLY, "2025-11-30")
        assertEquals(
            days("2026-02-28", "2026-05-30", "2026-08-30", "2026-11-30"),
            water.dueDates(day("2026-01-01"), day("2027-01-01")),
        )
    }

    @Test
    fun `every 6 months cycles from its start month and comes back to the 31st`() {
        val insurance = bill(Freq.SEMIANNUAL, "2024-08-31")
        assertEquals(
            days("2025-02-28", "2025-08-31", "2026-02-28", "2026-08-31"),
            insurance.dueDates(day("2025-01-01"), day("2027-01-01")),
        )
    }

    @Test
    fun `yearly on February 29 falls on February 28 except in leap years`() {
        val renewal = bill(Freq.YEARLY, "2024-02-29")
        assertEquals(
            days("2025-02-28", "2026-02-28", "2027-02-28", "2028-02-29"),
            renewal.dueDates(day("2025-01-01"), day("2029-01-01")),
        )
    }

    // ---- dueDates: empty ranges ----

    @Test
    fun `no due dates when until equals from`() {
        for (freq in Freq.entries) {
            val due = bill(freq, "2026-03-01")
            assertEquals(freq.name, emptyList<LocalDate>(), due.dueDates(day("2026-03-01"), day("2026-03-01")))
        }
    }

    @Test
    fun `no due dates when until is before from`() {
        for (freq in Freq.entries) {
            val due = bill(freq, "2026-03-01")
            assertEquals(freq.name, emptyList<LocalDate>(), due.dueDates(day("2026-03-10"), day("2026-03-05")))
        }
    }

    // ---- dueDates: every repeat against the slow schedule ----

    @Test
    fun `every repeat matches the schedule worked out the slow way`() {
        val random = Random(20261006)
        repeat(400) { case ->
            val freq = Freq.entries[random.nextInt(Freq.entries.size)]
            val first = LocalDate.of(2015, 1, 1).plusDays(random.nextInt(4000).toLong())
            val from = LocalDate.of(2015, 1, 1).plusDays(random.nextInt(5000).toLong())
            val until = from.plusDays(random.nextInt(800).toLong() - 30)
            val due = Bill("Test", 10.0, freq, first)
            assertEquals("case $case: $freq from $first, [$from, $until)",
                slowDueDates(freq, first, from, until), due.dueDates(from, until))
        }
    }

    // ---- unpaid ----

    @Test
    fun `unpaid leaves out due dates marked paid`() {
        val childcare = bill(Freq.BIWEEKLY, "2026-01-02", paid = mapOf(day("2026-10-09") to day("2026-10-08")))
        assertEquals(days("2026-10-23"), childcare.unpaid(day("2026-10-01"), day("2026-11-01")))
    }

    // ---- paidBetween ----

    @Test
    fun `paidBetween doesn't count a mark made on the after day`() {
        val gym = bill(Freq.MONTHLY, "2026-01-01", paid = mapOf(day("2026-10-01") to day("2026-10-01")))
        assertEquals(0, gym.paidBetween(day("2026-10-01"), day("2026-10-10")))
    }

    @Test
    fun `paidBetween counts a mark made on the through day`() {
        val gym = bill(Freq.MONTHLY, "2026-01-01", paid = mapOf(day("2026-10-01") to day("2026-10-10")))
        assertEquals(1, gym.paidBetween(day("2026-10-01"), day("2026-10-10")))
    }

    @Test
    fun `paidBetween counts only marks made inside the range`() {
        val groceries = bill(
            Freq.WEEKLY, "2026-01-02",
            paid = mapOf(
                day("2026-09-25") to day("2026-09-30"), // before
                day("2026-10-02") to day("2026-10-02"), // inside
                day("2026-10-09") to day("2026-10-08"), // inside
                day("2026-10-16") to day("2026-10-11"), // after
            ),
        )
        assertEquals(2, groceries.paidBetween(day("2026-10-01"), day("2026-10-10")))
    }

    // ---- nextDue ----

    @Test
    fun `nextDue is the first due date on or after today`() {
        val gym = bill(Freq.MONTHLY, "2026-01-15")
        assertEquals(day("2026-10-15"), gym.nextDue(day("2026-10-06")))
    }

    @Test
    fun `nextDue is today when today is an unpaid due date`() {
        val gym = bill(Freq.MONTHLY, "2026-01-15")
        assertEquals(day("2026-10-15"), gym.nextDue(day("2026-10-15")))
    }

    @Test
    fun `nextDue skips due dates marked paid`() {
        val gym = bill(Freq.MONTHLY, "2026-01-15", paid = mapOf(day("2026-10-15") to day("2026-10-05")))
        assertEquals(day("2026-11-15"), gym.nextDue(day("2026-10-06")))
    }

    @Test
    fun `nextDue skips several paid due dates in a row`() {
        val groceries = bill(
            Freq.WEEKLY, "2026-01-02",
            paid = listOf("2026-10-09", "2026-10-16", "2026-10-23").associate { day(it) to day("2026-10-06") },
        )
        assertEquals(day("2026-10-30"), groceries.nextDue(day("2026-10-06")))
    }

    @Test
    fun `nextDue ignores an unpaid due date that has passed`() {
        val gym = bill(Freq.MONTHLY, "2026-01-15")
        assertEquals(day("2026-11-15"), gym.nextDue(day("2026-10-20")))
    }

    @Test
    fun `nextDue finds a first due date more than 2 years ahead`() {
        val renewal = bill(Freq.YEARLY, "2029-05-01")
        assertEquals(day("2029-05-01"), renewal.nextDue(day("2026-10-06")))
    }

    // ---- recentPaidMark ----

    @Test
    fun `recentPaidMark finds a mark made 6 days ago`() {
        val gym = bill(Freq.MONTHLY, "2026-01-01", paid = mapOf(day("2026-10-01") to day("2026-09-30")))
        assertEquals(day("2026-10-01"), gym.recentPaidMark(day("2026-10-06")))
    }

    @Test
    fun `recentPaidMark ignores a mark made 7 days ago`() {
        val gym = bill(Freq.MONTHLY, "2026-01-01", paid = mapOf(day("2026-10-01") to day("2026-09-29")))
        assertNull(gym.recentPaidMark(day("2026-10-06")))
    }

    @Test
    fun `recentPaidMark ignores a mark dated after today`() {
        val gym = bill(Freq.MONTHLY, "2026-01-01", paid = mapOf(day("2026-11-01") to day("2026-10-07")))
        assertNull(gym.recentPaidMark(day("2026-10-06")))
    }

    @Test
    fun `recentPaidMark picks the due date marked most recently, not the latest due date`() {
        val groceries = bill(
            Freq.WEEKLY, "2026-01-02",
            paid = mapOf(
                day("2026-10-09") to day("2026-10-01"), // marked early
                day("2026-10-02") to day("2026-10-04"), // marked later
            ),
        )
        assertEquals(day("2026-10-02"), groceries.recentPaidMark(day("2026-10-06")))
    }

    // ---- cycleDays and perWeek ----

    @Test
    fun `cycleDays is the average days between due dates`() {
        assertEquals(30.4375, Freq.MONTHLY.cycleDays, 0.0)
        assertEquals(7.0, Freq.WEEKLY.cycleDays, 0.0)
        assertEquals(14.0, Freq.BIWEEKLY.cycleDays, 0.0)
        assertEquals(91.3125, Freq.QUARTERLY.cycleDays, 0.0)
        assertEquals(182.625, Freq.SEMIANNUAL.cycleDays, 0.0)
        assertEquals(365.25, Freq.YEARLY.cycleDays, 0.0)
    }

    @Test
    fun `perWeek of 1200 monthly rent is about 275 dollars 98`() {
        assertEquals(1200 * 7 / 30.4375, bill(Freq.MONTHLY, "2026-01-01", amount = 1200.0).perWeek, 1e-9)
        assertEquals(275.98, bill(Freq.MONTHLY, "2026-01-01", amount = 1200.0).perWeek, 0.005)
    }

    @Test
    fun `perWeek of weekly and every-2-weeks bills is the amount per 7 days`() {
        assertEquals(50.0, bill(Freq.WEEKLY, "2026-01-01", amount = 50.0).perWeek, 1e-9)
        assertEquals(50.0, bill(Freq.BIWEEKLY, "2026-01-01", amount = 100.0).perWeek, 1e-9)
    }

    @Test
    fun `perWeek of a yearly bill spreads it over 365 and a quarter days`() {
        assertEquals(7.0, bill(Freq.YEARLY, "2026-01-01", amount = 365.25).perWeek, 1e-9)
    }

    // ---- withPaid / withoutPaid (F-06) ----

    @Test
    fun `withPaid keeps the bill's id (F-06)`() {
        val rent = bill(Freq.MONTHLY, "2026-01-01", amount = 1200.0)
        assertSame(rent.id, rent.withPaid(day("2026-10-01"), day("2026-09-30")).id)
    }

    @Test
    fun `withoutPaid keeps the bill's id (F-06)`() {
        val rent = bill(Freq.MONTHLY, "2026-01-01", amount = 1200.0, paid = mapOf(day("2026-10-01") to day("2026-09-30")))
        assertSame(rent.id, rent.withoutPaid(day("2026-10-01")).id)
    }

    @Test
    fun `withPaid adds the mark to a copy and leaves the original unchanged`() {
        val rent = Bill("Rent", 1200.0, Freq.MONTHLY, day("2026-01-01"))
        val paid = rent.withPaid(day("2026-10-01"), day("2026-09-30"))
        assertEquals(emptyMap<LocalDate, LocalDate>(), rent.paid)
        assertEquals(mapOf(day("2026-10-01") to day("2026-09-30")), paid.paid)
        assertEquals("Rent", paid.name)
        assertEquals(1200.0, paid.amount, 0.0)
        assertEquals(Freq.MONTHLY, paid.freq)
        assertEquals(day("2026-01-01"), paid.date)
    }

    @Test
    fun `withoutPaid removes only that mark from a copy and leaves the original unchanged`() {
        val marks = mapOf(day("2026-09-01") to day("2026-09-01"), day("2026-10-01") to day("2026-09-30"))
        val rent = Bill("Rent", 1200.0, Freq.MONTHLY, day("2026-01-01"), marks)
        val unpaid = rent.withoutPaid(day("2026-10-01"))
        assertEquals(marks, rent.paid)
        assertEquals(mapOf(day("2026-09-01") to day("2026-09-01")), unpaid.paid)
        assertEquals("Rent", unpaid.name)
        assertEquals(1200.0, unpaid.amount, 0.0)
        assertEquals(Freq.MONTHLY, unpaid.freq)
        assertEquals(day("2026-01-01"), unpaid.date)
    }

    @Test
    fun `withPaid on a due date already marked replaces the marking day`() {
        val rent = bill(Freq.MONTHLY, "2026-01-01", paid = mapOf(day("2026-10-01") to day("2026-09-28")))
        assertEquals(mapOf(day("2026-10-01") to day("2026-10-02")), rent.withPaid(day("2026-10-01"), day("2026-10-02")).paid)
    }
}
