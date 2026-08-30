package com.openminis.app.data.repository

import android.content.ContextWrapper
import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MCPRepositoryHotReloadTest {
    private fun withRepository(block: (MCPRepository, File, () -> Int) -> Unit) {
        val dir = Files.createTempDirectory("mcp-repository-hot-reload").toFile()
        var reloads = 0
        try {
            val repository = MCPRepository(ContextWrapper(null), dir)
            repository.onServerConfigsChanged = { reloads += 1 }
            block(repository, dir) { reloads }
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun globalConfigMutationsReloadExactlyOnceAndNoOpsDoNotReload() =
        withRepository { repository, dir, reloadCount ->
            val alpha = MCPRepository.MCPServerConfig(
                id = "alpha",
                url = "https://alpha.example/mcp",
                createdAt = 10L,
            )

            assertTrue(repository.add(alpha))
            assertEquals(1, reloadCount())

            // Identical last-write-wins input is a no-op and must not tear down a
            // healthy MCP session just to reconnect the same configuration.
            assertTrue(repository.add(alpha))
            assertEquals(1, reloadCount())

            val updated = alpha.copy(note = "updated")
            assertTrue(repository.update(updated))
            assertEquals(2, reloadCount())
            assertTrue(repository.update(updated))
            assertEquals(2, reloadCount())

            repository.setEnabled("alpha", true)
            assertEquals(2, reloadCount())
            repository.setEnabled("alpha", false)
            assertEquals(3, reloadCount())

            val imported = repository.importJSON(
                """
                {
                  "mcpServers": {
                    "beta": {
                      "url": "https://beta.example/mcp",
                      "enabled": true,
                      "createdAt": 20
                    }
                  }
                }
                """.trimIndent(),
            )
            assertEquals(listOf("beta"), imported.map { it.id })
            assertEquals(4, reloadCount())

            // Preview and an unchanged disk refresh are read-only/no-op paths.
            assertEquals(1, repository.previewImport("""{"url":"https://preview.example/mcp"}"""))
            assertEquals(4, reloadCount())
            repository.reloadFromDisk()
            assertEquals(4, reloadCount())

            // Simulate minis-mcp-cli or another supported external writer. The next
            // explicit disk refresh must publish the new state and hot-reload once.
            File(dir, "servers.json").writeText(
                """
                {
                  "mcpServers": {
                    "external": {
                      "url": "https://external.example/mcp",
                      "enabled": true,
                      "createdAt": 30
                    }
                  }
                }
                """.trimIndent(),
            )
            repository.reloadFromDisk()
            assertEquals(5, reloadCount())
            assertEquals(listOf("external"), repository.servers.value.map { it.id })
        }

    @Test
    fun reloadCallbackFailureDoesNotPretendPersistedMutationRolledBack() {
        val dir = Files.createTempDirectory("mcp-repository-hot-reload-failure").toFile()
        try {
            val repository = MCPRepository(ContextWrapper(null), dir)
            repository.onServerConfigsChanged = { error("synthetic reload failure") }
            val server = MCPRepository.MCPServerConfig(
                id = "persisted",
                url = "https://persisted.example/mcp",
                createdAt = 1L,
            )

            assertTrue(repository.add(server))
            assertEquals(listOf("persisted"), repository.servers.value.map { it.id })
            assertTrue(File(dir, "servers.json").readText().contains("persisted"))
        } finally {
            dir.deleteRecursively()
        }
    }
}