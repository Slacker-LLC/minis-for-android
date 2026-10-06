package com.openminis.app.auth

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.StringReader
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

class OAuthCallbackServerTest {
    private var server: OAuthCallbackServer? = null

    @After
    fun stop() {
        server?.onExternalCancel = null
        server?.stop()
    }

    private fun freePort(): Int = ServerSocket(0, 1, InetAddress.getLoopbackAddress()).use { it.localPort }

    private fun get(port: Int, target: String): String =
        Socket(InetAddress.getLoopbackAddress(), port).use { s ->
            s.soTimeout = 3_000
            s.getOutputStream().write("GET $target HTTP/1.1\r\nHost: localhost\r\n\r\n".toByteArray())
            s.getOutputStream().flush()
            runCatching { s.getInputStream().readBytes().toString(Charsets.UTF_8) }.getOrDefault("")
        }

    private fun waitForBind(s: OAuthCallbackServer, port: Int) {
        val deadline = System.currentTimeMillis() + 3_000
        while (System.currentTimeMillis() < deadline) {
            if (runCatching { Socket(InetAddress.getLoopbackAddress(), port).close() }.isSuccess) return
            Thread.sleep(20)
        }
        error("server did not start on $port")
    }

    // ---- query parsing -------------------------------------------------

    @Test
    fun percentEncodedCharactersInTheCodeSurviveExactlyOnce() {
        val p = OAuthCallbackServer.parseQuery("code=a%2Bb&state=x%26y&plus=a+b&empty=&bare")
        assertEquals("a+b", p["code"])
        assertEquals("x&y", p["state"])
        assertEquals("a b", p["plus"])
        assertEquals("", p["empty"])
        assertEquals("", p["bare"])
    }

    @Test
    fun aMalformedPercentSequenceDoesNotThrow() {
        assertEquals(emptyMap<String, String>(), OAuthCallbackServer.parseQuery("code=%zz"))
        assertEquals("ok", OAuthCallbackServer.parseQuery("bad=%zz&good=ok")["good"])
        assertTrue(OAuthCallbackServer.parseQuery(null).isEmpty())
    }

    @Test
    fun anOverlongLineIsRefusedBeforeItIsBuffered() {
        val long = "a".repeat(100_000) + "\n"
        try {
            OAuthCallbackServer.readBoundedLine(StringReader(long))
            org.junit.Assert.fail("expected the limit to apply")
        } catch (_: java.io.IOException) {
        }
        assertEquals("GET / HTTP/1.1", OAuthCallbackServer.readBoundedLine(StringReader("GET / HTTP/1.1\r\n")))
        assertNull(OAuthCallbackServer.readBoundedLine(StringReader("")))
    }

    // ---- the live listener ---------------------------------------------

    @Test
    fun aCodeWithEncodedCharactersReachesTheCallbackIntact() {
        val port = freePort()
        val got = AtomicReference<Pair<String, String?>>()
        val done = CountDownLatch(1)
        server = OAuthCallbackServer(port, onCode = { c, s -> got.set(c to s); done.countDown() }).also { it.start() }
        waitForBind(server!!, port)

        get(port, "/callback?code=a%2Bb%26c&state=s1")

        assertTrue(done.await(3, TimeUnit.SECONDS))
        assertEquals("a+b&c" to "s1", got.get())
    }

    @Test
    fun aWrongStateIsRejectedAndTheRealCallbackStillWorks() {
        val port = freePort()
        val codes = mutableListOf<String>()
        val done = CountDownLatch(1)
        server = OAuthCallbackServer(port, expectedState = "good", onCode = { c, _ -> codes += c; done.countDown() })
            .also { it.start() }
        waitForBind(server!!, port)

        assertTrue(get(port, "/callback?code=forged&state=evil").startsWith("HTTP/1.1 400"))
        assertTrue(get(port, "/callback?code=forged").startsWith("HTTP/1.1 400"))
        assertTrue(codes.isEmpty())

        get(port, "/callback?code=real&state=good")
        assertTrue(done.await(3, TimeUnit.SECONDS))
        assertEquals(listOf("real"), codes)
    }

    @Test
    fun aDeniedAuthorizationEndsTheWait() {
        val port = freePort()
        val cancelled = CountDownLatch(1)
        server = OAuthCallbackServer(port, expectedState = "s", onCode = { _, _ -> error("no code expected") })
            .also {
                it.onExternalCancel = { cancelled.countDown() }
                it.start()
            }
        waitForBind(server!!, port)

        get(port, "/callback?error=access_denied&state=s")

        assertTrue("the wait should end on denial", cancelled.await(3, TimeUnit.SECONDS))
    }

    @Test
    fun whenEveryPortIsTakenTheWaitEndsInsteadOfHanging() {
        val taken = ServerSocket(0, 1, InetAddress.getLoopbackAddress())
        try {
            val cancelled = CountDownLatch(1)
            server = OAuthCallbackServer(taken.localPort, onCode = { _, _ -> }).also {
                it.onExternalCancel = { cancelled.countDown() }
                it.start()
            }
            assertTrue(cancelled.await(3, TimeUnit.SECONDS))
        } finally {
            taken.close()
        }
    }

    @Test
    fun anOversizedRequestLineIsDroppedAndTheServerKeepsListening() {
        val port = freePort()
        val done = CountDownLatch(1)
        server = OAuthCallbackServer(port, onCode = { _, _ -> done.countDown() }).also { it.start() }
        waitForBind(server!!, port)

        Socket(InetAddress.getLoopbackAddress(), port).use { s ->
            s.soTimeout = 3_000
            s.getOutputStream().write(("GET /" + "a".repeat(200_000) + " HTTP/1.1\r\n\r\n").toByteArray())
            s.getOutputStream().flush()
            runCatching { s.getInputStream().readBytes() }
        }
        assertFalse(done.count == 0L)

        get(port, "/callback?code=ok")
        assertTrue(done.await(3, TimeUnit.SECONDS))
    }
}
