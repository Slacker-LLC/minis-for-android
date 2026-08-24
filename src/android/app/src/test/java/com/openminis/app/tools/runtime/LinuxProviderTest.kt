package com.openminis.app.tools.runtime

import com.openminis.app.tools.ToolExecutionResult
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicBoolean

/**
 * P4 LinuxProvider contract tests (JVM). Availability is constructor-
 * injected, so no runtime singleton is needed; the wrapped handler is a
 * fake that ignores the (null) context, same as ToolRegistryTest.
 */
class LinuxProviderTest {

    private val anyResult = ToolExecutionResult("ok", true)

    @Test
    fun `unavailable short-circuits with ubuntu_runtime_unavailable and skips next`() = runBlocking {
        val provider = LinuxProvider(available = { false })
        val nextCalled = AtomicBoolean(false)

        val result = provider.execute(
            toolName = "linux.shell",
            argsJson = "{}",
            sessionId = "sid",
            context = TestContext.dummy(),
            toolId = "t1",
            next = {
                nextCalled.set(true)
                anyResult
            },
        )

        assertFalse(nextCalled.get())
        assertFalse(result.success)
        assertTrue(result.output.contains("ubuntu_runtime_unavailable"))
        assertTrue(result.output.contains("linux.shell"))
    }

    @Test
    fun `workspace file tools pass through when Ubuntu is unavailable`() = runBlocking {
        val provider = LinuxProvider(available = { false })
        val nextCalled = AtomicBoolean(false)
        val result = provider.execute(
            toolName = "linux.file.list",
            argsJson = "{}",
            sessionId = "sid",
            context = TestContext.dummy(),
            toolId = "t-file",
            next = { nextCalled.set(true); anyResult },
        )
        assertTrue(nextCalled.get())
        assertEquals(anyResult, result)
    }

    @Test
    fun `available passes through next result unchanged`() = runBlocking {
        val provider = LinuxProvider(available = { true })
        val nextCalled = AtomicBoolean(false)

        val result = provider.execute(
            toolName = "linux.file.read",
            argsJson = "{}",
            sessionId = "sid",
            context = TestContext.dummy(),
            toolId = "t2",
            next = {
                nextCalled.set(true)
                anyResult
            },
        )

        assertTrue(nextCalled.get())
        assertEquals(anyResult, result)
    }

    @Test
    fun `handles matches linux prefix boundaries`() {
        val provider = LinuxProvider(available = { true })

        assertTrue(provider.handles("linux.shell"))
        assertTrue(provider.handles("linux.file.read"))
        assertFalse(provider.handles("android.capabilities"))
        assertFalse(provider.handles("linuxx.foo"))
    }
}
