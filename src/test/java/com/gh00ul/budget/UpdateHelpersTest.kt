package com.gh00ul.budget

import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.time.LocalDate
import java.util.Random

// The plain top-level helpers at the end of MainActivity.kt: isNewer, copyUpTo, readUpTo, jsonWritable.
//
// Covered:
// - isNewer: "3.10.0" > "3.9.2" (numbers, not text); equal → false; a missing part counts as 0 ("3.2" vs "3.2.0");
//   non-numeric parts count as 0; a leading "v" isn't understood (the update check strips it first). RV-4 moved it
//   off the screen class; the behavior is unchanged.
// - copyUpTo (W-10, F-17): copies everything when the size is unknown (-1, and 0 is treated the same), exactly right
//   or larger; throws IOException as soon as more than the limit has arrived, writes nothing past the limit, and stops
//   an endless download within one 64 KB buffer of the limit.
// - readUpTo (F-16): UTF-8 text (also when a character is split across reads); a body exactly at the limit is fine,
//   one byte more throws IOException; the stream is closed either way.
// - jsonWritable (S-1): normal objects and lists are writable; NaN / infinite numbers, an object holding NaN (as
//   Android's parser reads it), and a list holding such an object are not.
//   jsonWritable walks the value itself (no JSONStringer), so these run on the JVM. The two cases that need Android's
//   parser (which reads a bare NaN in saved text as a number) skip when the test library reads it differently.
class UpdateHelpersTest {

    // ---- isNewer ----

    @Test
    fun `a two-digit minor version is newer than a one-digit one`() {
        assertTrue(isNewer("3.10.0", "3.9.2"))
    }

    @Test
    fun `an older version isn't newer`() {
        assertFalse(isNewer("3.9.2", "3.10.0"))
    }

    @Test
    fun `the same version isn't newer`() {
        assertFalse(isNewer("3.2.0", "3.2.0"))
    }

    @Test
    fun `a missing part counts as zero`() {
        assertFalse(isNewer("3.2", "3.2.0"))
        assertFalse(isNewer("3.2.0", "3.2"))
        assertTrue(isNewer("3.2.1", "3.2"))
    }

    @Test
    fun `a major version outweighs bigger minor and patch numbers`() {
        assertTrue(isNewer("4.0.0", "3.99.99"))
    }

    @Test
    fun `non-numeric parts count as zero`() {
        assertFalse(isNewer("3.2.0-beta", "3.2.0"))
        assertTrue(isNewer("3.x.1", "3.0.0"))
        assertTrue(isNewer("1.0.0", ""))
    }

    @Test
    fun `a leading v isn't understood, so the caller strips it`() {
        // The update check does tag_name.removePrefix("v") first; "v3" would read as 0.
        assertFalse(isNewer("v3.3.0", "3.2.0"))
        assertTrue(isNewer("3.3.0", "3.2.0"))
    }

    // ---- copyUpTo ----

    private fun bytes(size: Int) = ByteArray(size).also { Random(size.toLong()).nextBytes(it) }

    @Test
    fun `copyUpTo copies everything when the size is unknown`() {
        val data = bytes(200_000)
        val out = ByteArrayOutputStream()
        assertEquals(200_000L, copyUpTo(ByteArrayInputStream(data), out, -1))
        assertArrayEquals(data, out.toByteArray())
    }

    @Test
    fun `copyUpTo treats a size of 0 as unknown`() {
        val data = bytes(1000)
        val out = ByteArrayOutputStream()
        assertEquals(1000L, copyUpTo(ByteArrayInputStream(data), out, 0))
        assertArrayEquals(data, out.toByteArray())
    }

    @Test
    fun `copyUpTo copies everything when the download is exactly the size`() {
        val data = bytes(200_000)
        val out = ByteArrayOutputStream()
        assertEquals(200_000L, copyUpTo(ByteArrayInputStream(data), out, 200_000))
        assertArrayEquals(data, out.toByteArray())
    }

    @Test
    fun `copyUpTo copies everything when the download is smaller than the size`() {
        val data = bytes(1000)
        val out = ByteArrayOutputStream()
        assertEquals(1000L, copyUpTo(ByteArrayInputStream(data), out, 5000))
        assertArrayEquals(data, out.toByteArray())
    }

    @Test
    fun `copyUpTo copies an empty download`() {
        val out = ByteArrayOutputStream()
        assertEquals(0L, copyUpTo(ByteArrayInputStream(ByteArray(0)), out, -1))
        assertEquals(0, out.size())
    }

    @Test
    fun `copyUpTo refuses a download one byte larger than the size (W-10)`() {
        val out = ByteArrayOutputStream()
        assertThrows(IOException::class.java) { copyUpTo(ByteArrayInputStream(bytes(1001)), out, 1000) }
        assertEquals(0, out.size())
    }

    @Test
    fun `copyUpTo writes nothing past the size when the download is larger (W-10)`() {
        val data = bytes(200_000)
        val out = ByteArrayOutputStream()
        assertThrows(IOException::class.java) { copyUpTo(ByteArrayInputStream(data), out, 100_000) }
        assertTrue("wrote ${out.size()}", out.size() <= 100_000)
        assertArrayEquals(data.copyOf(out.size()), out.toByteArray())
    }

    @Test
    fun `copyUpTo stops an endless download within one buffer of the size (W-10)`() {
        var served = 0L
        val endless = object : InputStream() {
            override fun read() = 7.also { served++ }
            override fun read(b: ByteArray, off: Int, len: Int): Int {
                b.fill(7, off, off + len)
                served += len
                return len
            }
        }
        val out = ByteArrayOutputStream()
        assertThrows(IOException::class.java) { copyUpTo(endless, out, 100_000) }
        assertTrue("read $served", served <= 100_000 + 64 * 1024)
        assertTrue("wrote ${out.size()}", out.size() <= 100_000)
    }

    // ---- readUpTo ----

    private class Recording(data: ByteArray, private val chunk: Int = Int.MAX_VALUE) : InputStream() {
        private val input = ByteArrayInputStream(data)
        var closed = false

        override fun read() = input.read()
        override fun read(b: ByteArray, off: Int, len: Int) = input.read(b, off, minOf(len, chunk))
        override fun close() {
            closed = true
        }
    }

    @Test
    fun `readUpTo returns the body as UTF-8 text`() {
        val text = """{"tag_name":"v3.2.1","body":"Café — €5 ✓"}"""
        assertEquals(text, readUpTo(ByteArrayInputStream(text.toByteArray(Charsets.UTF_8)), 1 shl 20))
    }

    @Test
    fun `readUpTo decodes characters split across reads`() {
        val text = "é€✓".repeat(5000)
        assertEquals(text, readUpTo(Recording(text.toByteArray(Charsets.UTF_8), chunk = 1), 1 shl 20))
    }

    @Test
    fun `readUpTo accepts a body exactly at the limit`() {
        assertEquals("0123456789", readUpTo(ByteArrayInputStream("0123456789".toByteArray()), 10))
    }

    @Test
    fun `readUpTo refuses a body over the limit`() {
        assertThrows(IOException::class.java) { readUpTo(ByteArrayInputStream("0123456789A".toByteArray()), 10) }
    }

    @Test
    fun `readUpTo refuses a large body read in pieces`() {
        assertThrows(IOException::class.java) { readUpTo(Recording(ByteArray(100_000), chunk = 3000), 50_000) }
    }

    @Test
    fun `readUpTo closes the stream after reading`() {
        val input = Recording("ok".toByteArray())
        readUpTo(input, 10)
        assertTrue(input.closed)
    }

    @Test
    fun `readUpTo closes the stream when the body is too large`() {
        val input = Recording(ByteArray(100))
        assertThrows(IOException::class.java) { readUpTo(input, 10) }
        assertTrue(input.closed)
    }

    // ---- jsonWritable (S-1) ----

    // A JSON list holding a NaN, the way Android's parser can leave one in damaged saved text; null when this
    // org.json refuses to build one (the JVM library validates where Android's doesn't).
    private fun nanList(): JSONArray? = try {
        JSONArray(listOf(Double.NaN)).takeIf { it.opt(0) is Double }
    } catch (e: JSONException) {
        null
    }

    @Test
    fun `a normal bill object is writable`() {
        assertTrue(jsonWritable(billToJson(Bill("Rent", 1200.5, Freq.MONTHLY, LocalDate.of(2026, 1, 1)))))
    }

    @Test
    fun `a normal list of bills is writable`() {
        val list = JSONArray()
            .put(billToJson(Bill("Rent", 1200.0, Freq.MONTHLY, LocalDate.of(2026, 1, 1))))
            .put(JSONObject().put("name", "From a newer version").put("freq", "FORTNIGHTLY"))
        assertTrue(jsonWritable(list))
    }

    @Test
    fun `a NaN or infinite number is not writable (S-1)`() {
        assertFalse(jsonWritable(Double.NaN))
        assertFalse(jsonWritable(Double.POSITIVE_INFINITY))
        assertFalse(jsonWritable(Double.NEGATIVE_INFINITY))
    }

    @Test
    fun `a saved list item that Android's parser reads as NaN is not writable (S-1)`() {
        // Android's JSONArray parser accepts a bare NaN as a number; load() then keeps the original text aside.
        val item = JSONArray("[NaN]").opt(0)
        assumeTrue("only Android's parser reads a bare NaN as a number", item is Double)
        assertFalse(jsonWritable(item))
    }

    @Test
    fun `an object holding NaN is not writable (S-1)`() {
        // put() refuses NaN, but a damaged parse can still leave one inside an object; the walk must find it.
        val list = nanList()
        assumeTrue("this org.json refuses to hold a NaN at all (Android's parser can)", list != null)
        assertFalse(jsonWritable(JSONObject().put("name", "Rent").put("amounts", list!!)))
    }

    @Test
    fun `a list holding an object with NaN is not writable (S-1)`() {
        val nan = nanList()
        assumeTrue("this org.json refuses to hold a NaN at all (Android's parser can)", nan != null)
        val bad = JSONObject().put("name", "Damaged").put("list", nan!!)
        assertFalse(jsonWritable(JSONArray().put(billToJson(Bill("Rent", 1.0, Freq.MONTHLY, LocalDate.of(2026, 1, 1)))).put(bad)))
    }
}
