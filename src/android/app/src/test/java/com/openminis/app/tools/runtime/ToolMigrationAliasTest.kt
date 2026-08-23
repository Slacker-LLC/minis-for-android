package com.openminis.app.tools.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Before
import org.junit.Test

/**
 * D12 migration alias contract tests (JVM): each `linux.file.*` name and its
 * old flat alias must resolve to the same definition and handler instance.
 */
class ToolMigrationAliasTest {

    @Before
    fun setUp() {
        ToolRegistry.register(LinuxFileReadHandler(), aliasNames = listOf("file_read"))
        ToolRegistry.register(LinuxFileWriteHandler(), aliasNames = listOf("file_write"))
        ToolRegistry.register(LinuxFileEditHandler(), aliasNames = listOf("file_edit"))
    }

    private fun assertAliasPair(newName: String, oldName: String) {
        assertEquals(newName, ToolRegistry.canonicalName(newName))
        assertEquals(newName, ToolRegistry.canonicalName(oldName))
        assertNotNull(ToolRegistry.definition(newName))
        assertNotNull(ToolRegistry.definition(oldName))
        // same definition object (description/params identical by construction)
        assertEquals(ToolRegistry.definition(newName), ToolRegistry.definition(oldName))
        assertEquals(
            ToolRegistry.definition(newName)?.description,
            ToolRegistry.definition(oldName)?.description,
        )
        assertEquals(
            ToolRegistry.definition(newName)?.parameters,
            ToolRegistry.definition(oldName)?.parameters,
        )
        // same handler instance => same execution path
        assertSame(ToolRegistry.handler(newName), ToolRegistry.handler(oldName))
    }

    @Test
    fun `linux file read shares definition and handler with file_read`() {
        assertAliasPair("linux.file.read", "file_read")
    }

    @Test
    fun `linux file write shares definition and handler with file_write`() {
        assertAliasPair("linux.file.write", "file_write")
    }

    @Test
    fun `linux file edit shares definition and handler with file_edit`() {
        assertAliasPair("linux.file.edit", "file_edit")
    }
}
