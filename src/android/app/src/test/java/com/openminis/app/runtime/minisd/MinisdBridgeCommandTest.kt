package com.openminis.app.runtime.minisd

import com.openminis.app.runtime.guest.ModelUseOffloadHandler
import com.openminis.app.runtime.guest.NativeOffloadHandler
import com.openminis.app.runtime.guest.NativeOffloadRequest
import com.openminis.app.runtime.guest.NativeOffloadResult
import com.openminis.app.runtime.guest.OffloadArgs
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class MinisdBridgeCommandTest {
    @Test
    fun `production dispatch preserves stdin session cwd and shell-sensitive arguments`() {
        val prompt = "quote=\"x\"\n中文 $ ` \\ end\n"
        var received: NativeOffloadRequest? = null
        val result = MinisdConfigBridgeServer.dispatchRequest(
            request(listOf("/opt/minis/bin/minis-model-use", "run", "--model", "image-model"))
                .put("stdin", prompt).put("session", "session-42").put("cwd", "/workspace"),
            321,
        ) { name ->
            assertEquals("minis-model-use", name)
            NativeOffloadHandler { received = it; NativeOffloadResult(0, "ok") }
        }
        assertEquals(0, result.exitCode)
        val dispatched = received!!
        assertEquals(321, dispatched.pid)
        assertEquals("session-42", dispatched.sessionId)
        assertEquals("session-42", dispatched.env["MINIS_CHAT_SESSION_ID"])
        assertEquals("/workspace", dispatched.cwd)
        assertEquals(prompt, ModelUseOffloadHandler.resolveInput(OffloadArgs(dispatched.argv.drop(1)), dispatched) { _, _ -> error("stdin must not read a file") })
    }

    @Test
    fun `file input keeps session scope and takes precedence over stdin`() {
        val req = NativeOffloadRequest(1, listOf("minis-model-use"), emptyMap(), "/workspace", "session-42", "ignored")
        val input = ModelUseOffloadHandler.resolveInput(OffloadArgs(listOf("run", "--input", "/workspace/prompt.json")), req) { path, session ->
            assertEquals("/workspace/prompt.json", path)
            assertEquals("session-42", session)
            "file content"
        }
        assertEquals("file content", input)
        assertNull(ModelUseOffloadHandler.resolveInput(OffloadArgs(listOf("run", "--input")), req) { _, _ -> error("missing path") })
    }

    @Test
    fun `config remains on its existing handler with old request fields`() {
        val result = MinisdConfigBridgeServer.dispatchRequest(request(listOf("minis-config", "get", "soul.body")), 1) { name ->
            assertEquals("minis-config", name)
            NativeOffloadHandler { assertEquals("", it.stdin); NativeOffloadResult(7, "existing policy result") }
        }
        assertEquals(7, result.exitCode)
    }

    @Test
    fun `invalid command session and argument types never dispatch`() {
        val requests = listOf(
            request(listOf("su", "-c", "id")),
            request(listOf("minis-model-use")).put("session", "../other"),
            request(listOf("minis-model-use")).put("session", "."),
            request(listOf("minis-model-use")).put("argv", JSONArray().put("minis-model-use").put(42)),
            request(listOf("minis-model-use")).put("stdin", JSONObject()),
        )
        for (request in requests) {
            try {
                MinisdConfigBridgeServer.dispatchRequest(request, 1) { error("must reject before handler lookup") }
                fail("Expected invalid bridge request")
            } catch (expected: IllegalArgumentException) {
                // Validation rejection.
            } catch (expected: IllegalStateException) {
                assertNotEquals("must reject before handler lookup", expected.message)
            }
        }
    }

    @Test
    fun `unregistered model handler fails explicitly`() {
        val result = MinisdConfigBridgeServer.dispatchRequest(request(listOf("minis-model-use", "list")), 1) { null }
        assertEquals(127, result.exitCode)
    }

    private fun request(argv: List<String>) = JSONObject().put("argv", JSONArray(argv))
}
