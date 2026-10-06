package com.openminis.app.tools.runtime

import com.openminis.app.data.model.AgentToolDefinition
import com.openminis.app.mcp.client.MCPProvider
import com.openminis.app.tools.ToolExecutionResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread

class ToolRegistryConflictTest {
    private class Fake(name: String) : ToolHandler {
        override val definition = AgentToolDefinition(name = name, description = name, parameters = emptyMap())
        override suspend fun execute(argsJson: String, sessionId: String, context: android.content.Context, toolId: String) =
            ToolExecutionResult(definition.name, true)
    }

    @Test
    fun `a tool whose wire name another tool holds is refused, not overwritten`() {
        val suffix = System.nanoTime()
        val first = Fake("mcp.a.b$suffix.search")
        val second = Fake("mcp.a_b$suffix.search") // same wire name: mcp_a_b<suffix>_search
        assertEquals(first.definition.apiName, second.definition.apiName)
        try {
            assertTrue(ToolRegistry.register(first))
            assertFalse(ToolRegistry.register(second))
            assertSame("the wire name still reaches the first tool", first, ToolRegistry.handler(first.definition.apiName))
            assertFalse(ToolRegistry.definitions().any { it.name == second.definition.name })
        } finally {
            ToolRegistry.unregister(first.definition.name)
            ToolRegistry.unregister(second.definition.name)
        }
    }

    @Test
    fun `an alias held by another tool stays with it and the new tool still registers`() {
        val suffix = System.nanoTime()
        val owner = Fake("test.owner$suffix")
        val newcomer = Fake("test.newcomer$suffix")
        try {
            assertTrue(ToolRegistry.register(owner, listOf("shared_alias_$suffix")))
            assertTrue("a contested alias must not cost the tool its registration",
                ToolRegistry.register(newcomer, listOf("shared_alias_$suffix", "own_alias_$suffix")))
            assertEquals(owner.definition.name, ToolRegistry.canonicalName("shared_alias_$suffix"))
            assertSame(newcomer, ToolRegistry.handler(newcomer.definition.name))
            assertEquals(newcomer.definition.name, ToolRegistry.canonicalName("own_alias_$suffix"))
        } finally {
            ToolRegistry.unregister(owner.definition.name)
            ToolRegistry.unregister(newcomer.definition.name)
        }
    }

    @Test
    fun `re-registering the same tool replaces it and keeps its aliases`() {
        val suffix = System.nanoTime()
        val first = Fake("test.again$suffix")
        val replacement = Fake("test.again$suffix")
        try {
            assertTrue(ToolRegistry.register(first, listOf("again_alias_$suffix")))
            assertTrue(ToolRegistry.register(replacement, listOf("again_alias_$suffix")))
            assertSame(replacement, ToolRegistry.handler("again_alias_$suffix"))
        } finally {
            ToolRegistry.unregister(first.definition.name)
        }
    }

    @Test
    fun `a tool name that is another tool's alias is refused`() {
        val suffix = System.nanoTime()
        val owner = Fake("test.holder$suffix")
        val squatter = Fake("squat_$suffix")
        try {
            assertTrue(ToolRegistry.register(owner, listOf("squat_$suffix")))
            assertFalse(ToolRegistry.register(squatter))
            assertEquals(owner.definition.name, ToolRegistry.canonicalName("squat_$suffix"))
        } finally {
            ToolRegistry.unregister(owner.definition.name)
            ToolRegistry.unregister(squatter.definition.name)
        }
    }

    @Test
    fun `server ids that sanitize alike stay distinct`() {
        assertEquals("docs_one", MCPProvider.sanitizeId("docs_one"))
        assertNotEquals(MCPProvider.sanitizeId("docs one"), MCPProvider.sanitizeId("docs_one"))
        assertNotEquals(MCPProvider.sanitizeId("docs one"), MCPProvider.sanitizeId("docs/one"))
        assertTrue(MCPProvider.sanitizeId("docs one").matches(Regex("[a-zA-Z0-9_.-]+")))
    }

    @Test
    fun `registry reads during concurrent reloads never see a torn map`() {
        val failure = AtomicReference<Throwable?>(null)
        val start = CountDownLatch(1)
        val suffix = System.nanoTime()
        val writer = thread {
            start.await()
            runCatching {
                repeat(2_000) { i ->
                    val name = "mcp.stress$suffix.tool$i"
                    ToolRegistry.register(Fake(name), listOf("stress_alias_${suffix}_$i"))
                    ToolRegistry.unregister(name)
                }
            }.onFailure { failure.compareAndSet(null, it) }
        }
        val reader = thread {
            start.await()
            runCatching {
                repeat(2_000) { i ->
                    ToolRegistry.definitions()
                    ToolRegistry.canonicalName("stress_alias_${suffix}_$i")
                    ToolRegistry.aliasesFor("mcp.stress$suffix.tool$i")
                    ToolRegistry.definitionsForCaller(ToolPermissionManager.CALLER_LOCAL)
                }
            }.onFailure { failure.compareAndSet(null, it) }
        }
        start.countDown()
        writer.join(30_000)
        reader.join(30_000)
        failure.get()?.let { throw AssertionError("concurrent registry access failed", it) }
    }
}
