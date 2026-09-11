package com.openminis.app.runtime.guest

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class GuestCommandBridgeTest {
    @Test
    fun `dispatch preserves stdin session cwd and shell-sensitive arguments`() {
        val prompt = "quote=\"x\"\n中文 $ ` \\ end\n"
        var received: NativeOffloadRequest? = null
        val result = GuestCommandBridge.dispatch(
            cmd = "minis-model-use",
            args = listOf("run", "--model", "image-model"),
            session = "session-42",
            cwd = "/workspace",
            stdin = prompt,
            pid = 321,
        ) { name ->
            assertEquals("minis-model-use", name)
            NativeOffloadHandler {
                received = it
                NativeOffloadResult(0, "ok")
            }
        }
        assertEquals(0, result.exitCode)
        val request = received!!
        assertEquals(321, request.pid)
        assertEquals("session-42", request.sessionId)
        assertEquals("session-42", request.env["MINIS_CHAT_SESSION_ID"])
        assertEquals("/workspace", request.cwd)
        assertEquals(prompt, request.stdin)
        assertEquals(listOf("minis-model-use", "run", "--model", "image-model"), request.argv)
    }

    @Test
    fun `file payload replaces file flag and path with exact content`() {
        val args = listOf("set", "soul.body", "--file", "/tmp/value.json", "--caption", "x")
        val rewritten = GuestCommandBridge.rewriteFileArgument(
            args,
            "\"line1\\nline2 $ ` \\\\\"\"".toByteArray(),
        )
        assertEquals(
            listOf("set", "soul.body", "\"line1\\nline2 $ ` \\\\\"\"", "--caption", "x"),
            rewritten,
        )
        assertEquals(args, GuestCommandBridge.rewriteFileArgument(args, null))
    }

    @Test
    fun `invalid command and session are rejected before handler lookup`() {
        assertThrows(IllegalArgumentException::class.java) {
            GuestCommandBridge.dispatch("su", listOf("-c", "id"), "", "/workspace", "", 1) { error("lookup") }
        }
        assertThrows(IllegalArgumentException::class.java) {
            GuestCommandBridge.dispatch("minis-model-use", emptyList(), "../other", "/workspace", "", 1) { error("lookup") }
        }
    }

    @Test
    fun `unregistered model handler fails explicitly`() {
        val result = GuestCommandBridge.dispatch(
            "minis-model-use",
            listOf("list"),
            "",
            "/workspace",
            "",
            1,
        ) { null }
        assertEquals(127, result.exitCode)
        assertEquals("minis-model-use handler not registered\n", result.output)
    }

    @Test
    fun `connection limiter rejects excess workers and recovers after release`() {
        val limiter = GuestBridgeConnectionLimiter(2)
        assertTrue(limiter.tryAcquire())
        assertTrue(limiter.tryAcquire())
        assertEquals(2, limiter.activeCount())
        assertFalse(limiter.tryAcquire())

        limiter.release()
        assertEquals(1, limiter.activeCount())
        assertTrue(limiter.tryAcquire())
        assertEquals(2, limiter.activeCount())

        limiter.release()
        limiter.release()
        assertEquals(0, limiter.activeCount())
    }
}
