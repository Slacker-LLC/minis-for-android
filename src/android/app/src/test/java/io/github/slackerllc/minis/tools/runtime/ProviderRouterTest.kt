package io.github.slackerllc.minis.tools.runtime

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * P4 router contract tests (JVM). ProviderRouter and ToolRegistry are global
 * singletons, so @Before resets both before every test.
 */
class ProviderRouterTest {

    private class FakeHandler(private val name: String, private val output: String = "ok") : ToolHandler {
        override val definition = io.github.slackerllc.minis.data.model.AgentToolDefinition(
            name = name,
            description = "fake $name",
            parameters = emptyMap(),
        )
        override suspend fun execute(
            argsJson: String,
            sessionId: String,
            context: android.content.Context,
            toolId: String,
        ): io.github.slackerllc.minis.tools.ToolExecutionResult =
            io.github.slackerllc.minis.tools.ToolExecutionResult(output, true)
    }

    @Before
    fun reset() {
        ProviderRouter.reset()
    }

    @Test
    fun `prefix routing maps each tool name to its claiming provider`() {
        ProviderRouter.register(PrefixProvider("android", listOf("android.")))
        ProviderRouter.register(PrefixProvider("linux", listOf("linux.")))
        ProviderRouter.register(PrefixProvider("core", listOf("system.", "agent.")))
        ProviderRouter.register(PrefixProvider("mcp", listOf("mcp.")))
        ProviderRouter.register(PrefixProvider("skill", listOf("skill.")))
        ProviderRouter.register(PrefixProvider("root", listOf("root.")))

        assertEquals("android", ProviderRouter.route("android.capabilities")?.id)
        assertEquals("linux", ProviderRouter.route("linux.shell")?.id)
        assertEquals("root", ProviderRouter.route("root.shell")?.id)
        assertEquals("core", ProviderRouter.route("system.jobs")?.id)
        assertEquals("core", ProviderRouter.route("agent.goal")?.id)
        assertEquals("mcp", ProviderRouter.route("mcp.foo")?.id)
        assertEquals("skill", ProviderRouter.route("skill.bar")?.id)
    }

    @Test
    fun `no provider claims unknown tool returns null for backward compat`() {
        // reset() in @Before leaves the router empty
        assertNull(ProviderRouter.route("linux.shell"))
    }

    @Test
    fun `prefix boundary does not match without trailing dot`() {
        ProviderRouter.register(PrefixProvider("android", listOf("android.")))
        assertNotNull(ProviderRouter.route("android.capabilities"))
        assertNull(ProviderRouter.route("androidx.foo"))
    }

    @Test
    fun `tool executor wraps handler through provider and falls back when reset`() {
        ToolRegistry.register(FakeHandler("linux.shell"))
        ProviderRouter.register(LinuxProvider(available = { false }))

        // provider gate short-circuits before the handler runs
        val gated = runBlocking {
            ToolExecutor.execute(
                name = "linux.shell",
                argsJson = "{}",
                sessionId = "s",
                context = TestContext.dummy(),
                caller = ToolPermissionManager.CALLER_LOCAL,
            )
        }
        assertFalse(gated.success)
        assertTrue(gated.output.contains("ubuntu_runtime_unavailable"))

        // no provider → default handler path is unchanged
        ProviderRouter.reset()
        val passthrough = runBlocking {
            ToolExecutor.execute(
                name = "linux.shell",
                argsJson = "{}",
                sessionId = "s",
                context = TestContext.dummy(),
                caller = ToolPermissionManager.CALLER_LOCAL,
            )
        }
        assertTrue(passthrough.success)
        assertEquals("ok", passthrough.output)
    }
}
