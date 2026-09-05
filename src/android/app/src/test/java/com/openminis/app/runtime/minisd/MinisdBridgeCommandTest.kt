package com.openminis.app.runtime.minisd

import com.openminis.app.runtime.guest.NativeOffloadHandler
import com.openminis.app.runtime.guest.NativeOffloadRequest
import com.openminis.app.runtime.guest.NativeOffloadResult
import com.openminis.app.runtime.guest.NativeOffloadServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MinisdBridgeCommandTest {

    @Test
    fun `supported bridge commands include minis-config and minis-model-use`() {
        val validCommands = listOf("minis-config", "/usr/local/bin/minis-config", "minis-model-use", "/opt/minis/bin/minis-model-use")
        for (cmd in validCommands) {
            val cmdName = cmd.substringAfterLast('/')
            assertTrue(cmdName == "minis-config" || cmdName == "minis-model-use")
        }
    }

    @Test
    fun `native offload server can look up registered handler by name`() {
        val dummyHandler = NativeOffloadHandler { request ->
            NativeOffloadResult(0, "mock output for ${request.argv.first()}\n")
        }
        NativeOffloadServer.register("test-bridge-handler", dummyHandler)
        val resolved = NativeOffloadServer.getHandler("test-bridge-handler")
        assertNotNull(resolved)
        val result = resolved!!.handle(
            NativeOffloadRequest(
                pid = 1234,
                argv = listOf("test-bridge-handler", "arg1"),
                env = emptyMap(),
                cwd = "/workspace",
            )
        )
        assertEquals(0, result.exitCode)
        assertEquals("mock output for test-bridge-handler\n", result.output)
    }
}
