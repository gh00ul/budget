package com.gh00ul.budget

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.net.ConnectException
import java.net.MalformedURLException
import java.net.NoRouteToHostException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLException
import javax.net.ssl.SSLHandshakeException

// What reaches the bank server and what the user reads when it fails (BankSync.kt). Regressions, by DEBUG_REPORT ID:
// - BankServer.cleanUrl: https only (plain http just for a server on the phone itself, F-30), and no user:password,
//   query or fragment in the saved address, so nothing secret is stored with it or sent in a URL. Ports outside
//   1..65535 are refused (Phase 6 fix: they used to fail only when connecting, with an error not about the address).
// - BankServer.isUsableKey (F-03, plus the Phase 3 review note): a key with a character that can't go in a header
//   is refused before the header is built, because that error repeats the key. A space inside the key is fine.
// - BankServer.snapshot/sync -> request (Phase 6 fix): an address that isn't a URL is a BankException, not a raw
//   MalformedURLException; an unsendable key is refused first. Both fail before any connection or log call.
// - httpErrorMessage / networkErrorMessage (F-15, E-9, W-5, W-6): different wording for Connect (what was just typed)
//   and a sync (a saved connection), specific DNS/TLS/timeout messages, no status codes, never the exception's text.
// - cleanServerMessage (W-7): server text made one line and capped at 200 characters.
// - isRetryable (W-8): only "nothing answered" and gateway failures (502/504) get the one retry.
// - BankException.network (P-6): only real connection failures are marked, so a sync cut off in the background isn't
//   saved as a sync problem.
// None of these paths reach android.util.Log, Base64, the keystore or a Context, so they run as plain JVM tests.
class BankSyncTest {

    // ---- BankServer.cleanUrl ----

    @Test
    fun `an https address is accepted as typed`() {
        assertEquals("https://bank.example.com", BankServer.cleanUrl("https://bank.example.com"))
    }

    @Test
    fun `surrounding spaces and a trailing slash are trimmed`() {
        assertEquals("https://bank.example.com", BankServer.cleanUrl("  https://bank.example.com/  "))
    }

    @Test
    fun `a port and path are kept without the trailing slash`() {
        assertEquals("https://bank.example.com:8443/budget", BankServer.cleanUrl("https://bank.example.com:8443/budget/"))
    }

    @Test
    fun `plain http is allowed for localhost`() {
        assertEquals("http://localhost:8080", BankServer.cleanUrl("http://localhost:8080/"))
    }

    @Test
    fun `plain http is allowed for the loopback IP`() {
        assertEquals("http://127.0.0.1:8080", BankServer.cleanUrl("http://127.0.0.1:8080"))
    }

    @Test
    fun `plain http is refused for any other host`() {
        for (raw in listOf("http://bank.example.com", "http://10.0.2.2:8080", "http://192.168.1.20:8080")) {
            assertNull(raw, BankServer.cleanUrl(raw))
        }
    }

    @Test
    fun `plain http is refused for a host that only starts with localhost`() {
        assertNull(BankServer.cleanUrl("http://localhost.example.com"))
    }

    @Test
    fun `an address with a user name or password is refused`() {
        for (raw in listOf("https://user:secret@bank.example.com", "https://token@bank.example.com")) {
            assertNull(raw, BankServer.cleanUrl(raw))
        }
    }

    @Test
    fun `an address with a query string is refused`() {
        assertNull(BankServer.cleanUrl("https://bank.example.com/?key=abc"))
    }

    @Test
    fun `an address with a fragment is refused`() {
        assertNull(BankServer.cleanUrl("https://bank.example.com/#key"))
    }

    @Test
    fun `other schemes are refused`() {
        for (raw in listOf("ftp://bank.example.com", "file:///sdcard/bank", "javascript:alert(1)")) {
            assertNull(raw, BankServer.cleanUrl(raw))
        }
    }

    @Test
    fun `an address without a scheme is refused`() {
        assertNull(BankServer.cleanUrl("bank.example.com"))
    }

    @Test
    fun `text that is not an address is refused`() {
        for (raw in listOf("not a url", "https://bank example.com", "https://", "https:///budget")) {
            assertNull(raw, BankServer.cleanUrl(raw))
        }
    }

    @Test
    fun `a blank address is refused`() {
        assertNull(BankServer.cleanUrl(""))
        assertNull(BankServer.cleanUrl("   "))
    }

    @Test
    fun `a port outside 1 to 65535 is refused`() {
        for (raw in listOf("https://bank.example.com:0", "https://bank.example.com:65536", "https://bank.example.com:99999")) {
            assertNull(raw, BankServer.cleanUrl(raw))
        }
    }

    @Test
    fun `ports at the edges of the valid range are accepted`() {
        assertEquals("https://bank.example.com:1", BankServer.cleanUrl("https://bank.example.com:1"))
        assertEquals("https://bank.example.com:65535", BankServer.cleanUrl("https://bank.example.com:65535"))
    }

    @Test
    fun `common ports are still accepted`() {
        assertEquals("https://bank.example.com:443", BankServer.cleanUrl("https://bank.example.com:443"))
        assertEquals("http://localhost:8787", BankServer.cleanUrl("http://localhost:8787"))
    }

    // ---- BankServer.request, reached through snapshot/sync: both checks below fail before any connection ----

    @Test
    fun `an address that is not a URL fails as a bank error on connect`() {
        val e = assertThrows(BankException::class.java) { BankServer.snapshot("not a url", goodKey) }
        assertTrue(e.cause is MalformedURLException)
        assertFalse(e.network)
    }

    @Test
    fun `an address that is not a URL fails as a bank error on sync`() {
        val e = assertThrows(BankException::class.java) { BankServer.sync("bank.example.com", goodKey) }
        assertTrue(e.cause is MalformedURLException)
    }

    @Test
    fun `an unsendable key is refused before the request is built`() {
        val badKey = goodKey + '​'
        // A refused loopback port, so even a regression here can't reach a real server.
        val e = assertThrows(BankException::class.java) { BankServer.snapshot("https://127.0.0.1:1", badKey) }
        assertFalse(e.message!!, e.message!!.contains(goodKey))
        assertFalse(e.network)
    }

    // ---- BankServer.isUsableKey (F-03) ----

    @Test
    fun `a key of 32 characters is usable`() {
        assertTrue(BankServer.isUsableKey("k".repeat(32)))
    }

    @Test
    fun `a key of 31 characters is too short`() {
        assertFalse(BankServer.isUsableKey("k".repeat(31)))
    }

    @Test
    fun `every printable ASCII character is allowed`() {
        val everyPrintable = (' '..'~').joinToString("") // 95 characters, space and tilde included
        assertTrue(BankServer.isUsableKey(everyPrintable))
    }

    @Test
    fun `a space inside the key is allowed`() {
        assertTrue(BankServer.isUsableKey("abcdefghijklmnop qrstuvwxyz0123456789"))
    }

    @Test
    fun `a key with a control character is refused`() {
        for (c in listOf('\t', '\n', '\r', '\u0000', '\u001B', '\u007F')) {
            assertFalse("U+%04X".format(c.code), BankServer.isUsableKey(goodKey + c + goodKey))
        }
    }

    @Test
    fun `a key with a zero-width space is refused`() {
        assertFalse(BankServer.isUsableKey(goodKey + '​' + goodKey))
    }

    @Test
    fun `a key with a non-ASCII character is refused`() {
        for (s in listOf("é", " ", "’", "😀")) { // é, no-break space, curly quote, emoji
            assertFalse(s, BankServer.isUsableKey(goodKey + s + goodKey))
        }
    }

    // ---- httpErrorMessage ----

    @Test
    fun `a rejected key reads differently on connect and on sync`() {
        val connect = httpErrorMessage(401, null, forConnect = true)
        val sync = httpErrorMessage(401, null, forConnect = false)
        assertNotEquals(connect, sync)
        assertTrue(connect, connect.contains("that access key"))
        assertTrue(sync, sync.contains("Disconnect"))
    }

    @Test
    fun `403 gets the same message as 401`() {
        assertEquals(httpErrorMessage(401, null, true), httpErrorMessage(403, null, true))
        assertEquals(httpErrorMessage(401, null, false), httpErrorMessage(403, null, false))
    }

    @Test
    fun `a missing server reads differently on connect and on sync`() {
        val connect = httpErrorMessage(404, null, forConnect = true)
        val sync = httpErrorMessage(404, null, forConnect = false)
        assertNotEquals(connect, sync)
        assertTrue(connect, connect.contains("no bank server at that address"))
        assertTrue(sync, sync.contains("Disconnect"))
    }

    @Test
    fun `a redirect reads differently on connect and on sync`() {
        val connect = httpErrorMessage(301, null, forConnect = true)
        val sync = httpErrorMessage(301, null, forConnect = false)
        assertNotEquals(connect, sync)
        assertTrue(connect, connect.contains("points somewhere else"))
        assertTrue(sync, sync.contains("address has changed"))
    }

    @Test
    fun `the whole 3xx range is treated as a redirect`() {
        for (status in listOf(300, 302, 307, 308, 399)) {
            assertEquals("$status", httpErrorMessage(301, null, true), httpErrorMessage(status, null, true))
            assertEquals("$status", httpErrorMessage(301, null, false), httpErrorMessage(status, null, false))
        }
    }

    @Test
    fun `a busy server gets the same busy message on connect and on sync`() {
        assertEquals(BUSY, httpErrorMessage(429, null, forConnect = true))
        assertEquals(BUSY, httpErrorMessage(429, null, forConnect = false))
    }

    @Test
    fun `a busy server message ignores the server text`() {
        assertEquals(BUSY, httpErrorMessage(429, "Rate limited, slow down", forConnect = false))
    }

    @Test
    fun `a rejected key never shows the server text`() {
        val message = httpErrorMessage(401, "Token abc123secret is invalid", forConnect = true)
        assertFalse(message, message.contains("abc123secret"))
    }

    @Test
    fun `another error shows the server explanation`() {
        assertEquals("Maintenance until 3 PM", httpErrorMessage(500, "Maintenance until 3 PM", forConnect = false))
    }

    @Test
    fun `another error without a server explanation gets the generic text`() {
        for (status in listOf(400, 409, 500, 503)) {
            assertEquals("$status", GENERIC, httpErrorMessage(status, null, forConnect = false))
        }
    }

    @Test
    fun `a blank server explanation gets the generic text`() {
        assertEquals(GENERIC, httpErrorMessage(500, "  \n\t ", forConnect = false))
    }

    @Test
    fun `the server explanation is cleaned before it is shown`() {
        assertEquals("Database offline", httpErrorMessage(500, "Database\n\n   offline\u0000", forConnect = false))
    }

    @Test
    fun `no message shows a status code`() {
        val statuses = listOf(300, 301, 302, 304, 307, 308, 400, 401, 403, 404, 409, 429, 500, 502, 503, 504)
        for (status in statuses) {
            for (forConnect in listOf(true, false)) {
                val message = httpErrorMessage(status, null, forConnect)
                assertFalse("$status/$forConnect: $message", message.any { it.isDigit() })
            }
        }
    }

    // ---- networkErrorMessage ----

    @Test
    fun `an unknown host reads differently on connect and on sync`() {
        val connect = networkErrorMessage(UnknownHostException(), forConnect = true)
        val sync = networkErrorMessage(UnknownHostException(), forConnect = false)
        assertNotEquals(connect, sync)
        assertTrue(connect, connect.contains("Couldn't find a server at that address"))
        assertEquals(CHECK_CONNECTION, sync)
    }

    @Test
    fun `a timeout says the server took too long`() {
        val connect = networkErrorMessage(SocketTimeoutException(), forConnect = true)
        assertTrue(connect, connect.contains("took too long"))
        assertEquals(connect, networkErrorMessage(SocketTimeoutException(), forConnect = false))
    }

    @Test
    fun `a TLS failure asks to check the date and time`() {
        val message = networkErrorMessage(SSLException("handshake"), forConnect = false)
        assertTrue(message, message.contains("secure connection"))
        assertTrue(message, message.contains("date and time"))
    }

    @Test
    fun `a TLS handshake failure gets the same secure connection message`() {
        assertEquals(
            networkErrorMessage(SSLException("x"), forConnect = true),
            networkErrorMessage(SSLHandshakeException("x"), forConnect = true),
        )
    }

    @Test
    fun `any other IO failure asks to check the connection`() {
        for (e in listOf(IOException("x"), ConnectException("x"), NoRouteToHostException("x"))) {
            assertEquals(e.javaClass.simpleName, CHECK_CONNECTION, networkErrorMessage(e, forConnect = true))
            assertEquals(e.javaClass.simpleName, CHECK_CONNECTION, networkErrorMessage(e, forConnect = false))
        }
    }

    @Test
    fun `a network message never repeats the exception text`() {
        val detail = "bank.example.com/v1/sync?sig=SECRET"
        val failures = listOf(
            UnknownHostException(detail), SocketTimeoutException(detail), SSLException(detail),
            ConnectException(detail), IOException(detail),
        )
        for (e in failures) {
            for (forConnect in listOf(true, false)) {
                val message = networkErrorMessage(e, forConnect)
                assertFalse(message, message.contains("SECRET") || message.contains("example"))
            }
        }
    }

    // ---- cleanServerMessage (W-7) ----

    @Test
    fun `a missing server message stays missing`() {
        assertNull(cleanServerMessage(null))
    }

    @Test
    fun `a blank server message becomes missing`() {
        for (raw in listOf("", "   ", "\n\t\r", "\u0000\u0007")) {
            assertNull(raw, cleanServerMessage(raw))
        }
    }

    @Test
    fun `a server message is trimmed`() {
        assertEquals("Bank is down", cleanServerMessage("\n   Bank is down \t "))
    }

    @Test
    fun `runs of spaces and line breaks become one space`() {
        assertEquals("Bank is down now", cleanServerMessage("Bank   is\r\n\r\ndown\t\tnow"))
    }

    @Test
    fun `control characters become one space`() {
        assertEquals("Bank is down", cleanServerMessage("Bank\u0000is\u0007\u001Bdown\u0000"))
    }

    @Test
    fun `a message of exactly 200 characters is kept whole`() {
        val raw = "a".repeat(200)
        assertEquals(raw, cleanServerMessage(raw))
    }

    @Test
    fun `a longer message is cut to 200 characters ending in an ellipsis`() {
        assertEquals("a".repeat(199) + "…", cleanServerMessage("a".repeat(250)))
    }

    @Test
    fun `the cut leaves no space before the ellipsis`() {
        assertEquals("a".repeat(198) + "…", cleanServerMessage("a".repeat(198) + " " + "b".repeat(10)))
    }

    @Test
    fun `the cut never splits an emoji in half`() {
        val cleaned = cleanServerMessage("a".repeat(198) + "😀" + "bbb")!!
        assertEquals("a".repeat(198) + "…", cleaned)
        assertFalse(cleaned.any { it.isSurrogate() })
    }

    // ---- isRetryable (W-8) ----

    @Test
    fun `a refused connection is retried`() {
        assertTrue(isRetryable(ConnectException("Connection refused")))
    }

    @Test
    fun `no route to host is retried`() {
        assertTrue(isRetryable(NoRouteToHostException("No route to host")))
    }

    @Test
    fun `an unknown host is not retried`() {
        assertFalse(isRetryable(UnknownHostException("bank.example.com")))
    }

    @Test
    fun `a timeout is not retried`() {
        assertFalse(isRetryable(SocketTimeoutException("Read timed out")))
    }

    @Test
    fun `a TLS failure is not retried`() {
        assertFalse(isRetryable(SSLException("handshake")))
    }

    @Test
    fun `a plain IO failure is not retried`() {
        assertFalse(isRetryable(IOException("closed")))
    }

    @Test
    fun `gateway failures 502 and 504 are retried`() {
        assertTrue(isRetryable(502))
        assertTrue(isRetryable(504))
    }

    @Test
    fun `other error statuses are not retried`() {
        for (status in listOf(400, 401, 403, 404, 429, 500, 503)) {
            assertFalse("$status", isRetryable(status))
        }
    }

    // ---- BankException (P-6) ----

    @Test
    fun `a bank exception is not a network failure by default`() {
        assertFalse(BankException("The bank server sent no accounts.").network)
        assertFalse(BankException("The bank server had a problem.", IOException("x")).network)
    }

    @Test
    fun `a bank exception is a network failure only when marked`() {
        assertTrue(BankException("Couldn't reach the bank server.", ConnectException("x"), network = true).network)
    }

    @Test
    fun `a bank exception keeps its message and cause`() {
        val cause = SSLException("handshake")
        val e = BankException("Couldn't make a secure connection.", cause, network = true)
        assertEquals("Couldn't make a secure connection.", e.message)
        assertSame(cause, e.cause)
    }

    private companion object {
        val goodKey = "k".repeat(40)
        const val BUSY = "The bank server is busy. Try again in a minute."
        const val GENERIC = "The bank server had a problem. Try again later."
        const val CHECK_CONNECTION = "Couldn't reach the bank server. Check your connection."
    }
}
