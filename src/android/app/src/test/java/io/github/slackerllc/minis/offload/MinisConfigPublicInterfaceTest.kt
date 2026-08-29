package io.github.slackerllc.minis.offload

import io.github.slackerllc.minis.sandbox.NativeOffloadRequest
import io.github.slackerllc.minis.sandbox.offload.ConfigOffloadHandler
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MinisConfigPublicInterfaceTest {

    @Test
    fun `public minis-config help is discoverable without registry internals`() {
        val result = ConfigOffloadHandler().handle(
            NativeOffloadRequest(
                pid = 123,
                argv = listOf("minis-config", "--help"),
                env = emptyMap(),
                cwd = "/workspace",
                sessionId = "test-session",
            ),
        )

        assertEquals(0, result.exitCode)
        assertTrue(result.output.contains("minis-config - read or change Minis app settings"))
        assertTrue(result.output.contains("list-topics"))
        assertTrue(result.output.contains("topic-help"))
        assertTrue(result.output.contains("set <path> --file <f>"))
        assertTrue(result.output.contains("Every write requires user confirmation in-app"))
        assertFalse(result.output.contains("DebugServer"))
        assertFalse(result.output.contains("PRoot"))
    }
}
