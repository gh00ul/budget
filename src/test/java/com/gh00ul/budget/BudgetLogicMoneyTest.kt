package com.gh00ul.budget

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.util.Locale
import java.util.Random

// Money in amount fields (BudgetLogic.kt: parseMoney, amountText, plain).
//
// Covered:
// - parseMoney: "1,234.56", "$50", "-20", " 12.5 ", ".5"; no answer for "", ".", "-", "1.2.3", a billion or more,
//   a number too big for a Double, "NaN" / "Infinity" (letters are dropped, leaving nothing); just under a billion
//   ("999999999.99") is accepted. Letters are dropped, so "1e9" reads as 19 (documented, see the test).
// - amountText: 1200.0 → "1200", 80.5 → "80.50", 0.1 + 0.2 → "0.30", half-up rounding of the written value, no
//   exponent for big amounts, negatives; plain() without trailing zeros.
// - Round trip: parseMoney(amountText(x)) == x for every cents value tried (0 to $1,000 exhaustively, then seeded
//   random up to just under a billion, negatives too).
// - RV-1 / F-22 regression: amounts always use '.' (Android's numberDecimal fields only accept digits and '.', in
//   every language), so formatting and parsing ignore the phone's language; "12,50" reads as 1250 by design — a
//   comma can't be typed in those fields. F-22 (comma-decimal parsing) was withdrawn after RV-1 showed the change
//   read "12.50" as 1250 on comma-decimal phones.
class BudgetLogicMoneyTest {

    private val startLocale: Locale = Locale.getDefault()

    @After
    fun restoreLocale() {
        Locale.setDefault(startLocale)
    }

    // ---- parseMoney ----

    @Test
    fun `parseMoney reads thousands separators`() {
        assertEquals(1234.56, parseMoney("1,234.56")!!, 0.0)
    }

    @Test
    fun `parseMoney reads a dollar sign`() {
        assertEquals(50.0, parseMoney("$50")!!, 0.0)
    }

    @Test
    fun `parseMoney keeps a minus sign`() {
        assertEquals(-20.0, parseMoney("-20")!!, 0.0)
    }

    @Test
    fun `parseMoney ignores surrounding spaces`() {
        assertEquals(12.5, parseMoney(" 12.5 ")!!, 0.0)
    }

    @Test
    fun `parseMoney reads a leading point`() {
        assertEquals(0.5, parseMoney(".5")!!, 0.0)
    }

    @Test
    fun `parseMoney gives no answer for empty text`() {
        assertNull(parseMoney(""))
        assertNull(parseMoney("   "))
    }

    @Test
    fun `parseMoney gives no answer for a lone point`() {
        assertNull(parseMoney("."))
    }

    @Test
    fun `parseMoney gives no answer for a lone minus`() {
        assertNull(parseMoney("-"))
    }

    @Test
    fun `parseMoney gives no answer for two points`() {
        assertNull(parseMoney("1.2.3"))
    }

    @Test
    fun `parseMoney accepts just under a billion`() {
        assertEquals(999999999.99, parseMoney("999999999.99")!!, 0.0)
        assertEquals(-999999999.99, parseMoney("-999,999,999.99")!!, 0.0)
    }

    @Test
    fun `parseMoney gives no answer for a billion or more`() {
        assertNull(parseMoney("1000000000"))
        assertNull(parseMoney("1,000,000,000"))
        assertNull(parseMoney("-1000000000"))
    }

    @Test
    fun `parseMoney gives no answer for a number too big for a Double`() {
        assertNull(parseMoney("9".repeat(400)))
    }

    @Test
    fun `parseMoney gives no answer for NaN or Infinity text`() {
        assertNull(parseMoney("NaN"))
        assertNull(parseMoney("Infinity"))
        assertNull(parseMoney("-Infinity"))
    }

    @Test
    fun `parseMoney drops letters, so 1e9 reads as 19`() {
        // Current behavior, not an intent: every character but digits, '.' and '-' is removed before reading, so an
        // exponent's "e" disappears. The amount fields (numberDecimal) don't let letters be typed, so it's unlikely
        // to be reachable from the app; reported to the lead rather than asserted as null.
        assertEquals(19.0, parseMoney("1e9")!!, 0.0)
    }

    @Test
    fun `parseMoney reads a comma as a thousands separator, never a decimal point (RV-1, F-22 withdrawn)`() {
        assertEquals(1250.0, parseMoney("12,50")!!, 0.0)
    }

    @Test
    fun `parseMoney reads a point as the decimal point whatever the phone's language (RV-1)`() {
        Locale.setDefault(Locale.GERMANY)
        assertEquals(12.5, parseMoney("12.50")!!, 0.0)
        Locale.setDefault(Locale.FRANCE)
        assertEquals(1234.56, parseMoney("1,234.56")!!, 0.0)
    }

    // ---- amountText and plain ----

    @Test
    fun `amountText writes a whole amount without decimals`() {
        assertEquals("1200", amountText(1200.0))
        assertEquals("0", amountText(0.0))
    }

    @Test
    fun `amountText writes cents with two decimals`() {
        assertEquals("80.50", amountText(80.5))
        assertEquals("1234.56", amountText(1234.56))
    }

    @Test
    fun `amountText hides floating-point noise`() {
        assertEquals("0.30", amountText(0.1 + 0.2))
    }

    @Test
    fun `amountText rounds half up on the amount as written`() {
        assertEquals("12.35", amountText(12.345))
        assertEquals("1.01", amountText(1.005))
    }

    @Test
    fun `amountText keeps a minus sign`() {
        assertEquals("-20.50", amountText(-20.5))
        assertEquals("-20", amountText(-20.0))
    }

    @Test
    fun `amountText never uses an exponent`() {
        assertEquals("123456789", amountText(123456789.0))
        assertEquals("999999999.99", amountText(999999999.99))
        assertEquals("0.00", amountText(1e-7))
    }

    @Test
    fun `amountText always uses a point whatever the phone's language (RV-1)`() {
        for (locale in listOf(Locale.GERMANY, Locale.FRANCE, Locale.forLanguageTag("ar-EG"), Locale.forLanguageTag("hi-IN"))) {
            Locale.setDefault(locale)
            assertEquals(locale.toString(), "1234.50", amountText(1234.5))
        }
    }

    @Test
    fun `plain drops trailing zeros`() {
        assertEquals("1200", plain(1200.0))
        assertEquals("80.5", plain(80.5))
        assertEquals("100.1", plain(100.10))
    }

    @Test
    fun `plain never uses an exponent`() {
        assertEquals("100000000", plain(1e8))
    }

    // ---- round trip ----

    @Test
    fun `every cents amount up to 1000 dollars reads back exactly after amountText`() {
        for (cents in 0..100_000) {
            val amount = cents / 100.0
            assertEquals(amountText(amount), amount, parseMoney(amountText(amount))!!, 0.0)
        }
    }

    @Test
    fun `large and negative cents amounts read back exactly after amountText`() {
        val random = Random(20261006)
        repeat(20_000) {
            val cents = (random.nextLong() % 99_999_999_999L) // up to 999,999,999.99 either way
            val amount = cents / 100.0
            assertEquals(amountText(amount), amount, parseMoney(amountText(amount))!!, 0.0)
        }
    }

    @Test
    fun `amounts read back the same after amountText whatever the phone's language (RV-1)`() {
        Locale.setDefault(Locale.GERMANY)
        for (amount in listOf(12.5, 80.5, 1234.56, 0.3, 1200.0, -45.1)) {
            assertEquals(amount, parseMoney(amountText(amount))!!, 0.0)
        }
    }
}
