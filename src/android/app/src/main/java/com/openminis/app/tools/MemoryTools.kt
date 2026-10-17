package com.openminis.app.tools

import com.openminis.app.data.repository.MemoryRepository
import org.json.JSONObject
import java.util.UUID

/**
 * Execution for memory_write and memory_get. The tool definitions live in [AgentTools].
 */
object MemoryTools {

    // -- Execution --

    data class ToolResult(
        val output: String,
        val success: Boolean,
        val toolTitle: String = "",
        val entryId: String? = null,
    )

    fun executeMemoryWrite(inputJson: String, repository: MemoryRepository, sourceSessionId: String? = null): ToolResult {
        return try {
            val obj = JSONObject(inputJson)
            val content = obj.optString("content", "")
            val toolTitle = obj.optString("tool_title", "memory_write")

            if (content.isBlank()) {
                ToolResult("Error: Missing required 'content' parameter", false, toolTitle)
            } else {
                val entryId = UUID.randomUUID().toString()
                val result = repository.writeMemory(content, sourceSessionId, obj.optString("kind", "task_fact"), entryId)
                val success = result.startsWith("Memory saved")
                ToolResult(result, success, toolTitle, entryId.takeIf { success })
            }
        } catch (e: Exception) {
            ToolResult("Error: ${e.message}", false)
        }
    }

    fun executeMemoryGet(inputJson: String, repository: MemoryRepository): ToolResult {
        return try {
            val obj = JSONObject(inputJson)
            val keywords = obj.optString("keywords", "")
            val scope = obj.optString("scope", "all")
            val toolTitle = obj.optString("tool_title", "memory_get")

            val result = repository.getMemory(keywords, scope)
            ToolResult(result, true, toolTitle)
        } catch (e: Exception) {
            ToolResult("Error: ${e.message}", false)
        }
    }
}
