package com.openminis.app.data.repository

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import android.content.ContextWrapper
import java.nio.file.Files

/**
 * The MCP system-prompt fragment.
 *
 * It used to tell the model to run `minis-mcp-cli …`, a guest command that does
 * not exist (06-CURRENT-GAPS): a model that followed the instruction lost its turn
 * to "command not found", while the tools it actually wanted were already
 * registered as `mcp_<server>_<tool>`. These pin the wording that says so.
 */
class MCPPromptFragmentTest {

    // Same lightweight construction the hot-reload test uses: the repository's
    // SQLite helper is only touched by the session-override paths, and this test
    // calls the pure text builder.
    private val dir = Files.createTempDirectory("mcp-prompt-fragment").toFile()
    private val repository = MCPRepository(ContextWrapper(null), dir)

    private val fragment = repository.mcpPromptFragmentText(
        listOf("docs" to "Documentation server", "jira" to null),
    )

    @Test
    fun `the fragment names the registered tool form`() {
        assertTrue(fragment, fragment.contains("mcp_<server>_<tool>"))
        assertTrue(fragment, fragment.contains("mcp_docs_search"))
        assertTrue(fragment, fragment.contains("No shell command is involved"))
    }

    @Test
    fun `the fragment never points at the missing CLI`() {
        assertFalse(fragment, fragment.contains("minis-mcp-cli"))
    }

    @Test
    fun `servers and notes are listed`() {
        assertTrue(fragment, fragment.contains("- docs: Documentation server"))
        assertTrue(fragment, fragment.contains("- jira\n"))
        assertTrue(fragment, fragment.contains("\$\$VARNAME"))
    }
}
