package com.openminis.app.tools

import com.openminis.app.tools.runtime.LinuxReadImageHandler
import com.openminis.app.tools.runtime.ToolRegistry
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * The image-reading tool is offered only to a model that can see images or has a Vision Group to describe
 * them. The tool registry holds a copy of it under its canonical name, and that copy must not bring it back.
 */
class ReadImageGateTest {
    private val handler = LinuxReadImageHandler()

    @Before
    fun register() {
        ToolRegistry.register(handler, aliasNames = listOf("read_image"))
    }

    @After
    fun unregister() {
        ToolRegistry.unregister(handler.definition.name)
    }

    private fun names(supportsImageInput: Boolean, vision: Boolean) =
        AgentTools.makeAgentTools(supportsImageInput = supportsImageInput, visionGroupConfigured = vision).map { it.name }

    private fun hasImageTool(names: List<String>) = "read_image" in names || handler.definition.name in names

    @Test
    fun `a text-only model with no vision group is not offered the image tool in either name`() {
        assertFalse(hasImageTool(names(supportsImageInput = false, vision = false)))
    }

    @Test
    fun `a model that sees images is offered it once`() {
        val n = names(supportsImageInput = true, vision = false)
        assertTrue(hasImageTool(n))
        assertTrue(n.count { it == "read_image" || it == handler.definition.name } == 1)
    }

    @Test
    fun `a vision group is enough`() {
        val n = names(supportsImageInput = false, vision = true)
        assertTrue(hasImageTool(n))
        assertTrue(n.count { it == "read_image" || it == handler.definition.name } == 1)
    }

    @Test
    fun `the minimal tool set never offers it`() {
        val n = AgentTools.makeAgentTools(
            supportsImageInput = true,
            presetToolset = com.openminis.app.remote.AgentPresetRegistry.Toolset.CORE,
        ).map { it.name }
        assertFalse(hasImageTool(n))
    }
}
