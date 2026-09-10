package com.openminis.app.tools

import android.content.Context
import com.openminis.app.data.model.AgentToolDefinition
import com.openminis.app.data.model.AgentToolParam
import com.openminis.app.runtime.RuntimePathRegistry
import com.openminis.app.runtime.files.WorkspaceFileClient
import org.json.JSONObject

object FileEditTool {
    const val NAME = "file_edit"

    fun definition(): AgentToolDefinition = AgentToolDefinition(
        name = NAME,
        description = "Make targeted edits to an existing file using exact string replacement. ALWAYS use file_read first to see the current file contents before editing. Prefer file_edit over file_write when modifying existing files — only the changed part needs to be specified. The old_string must match exactly one location in the file (including whitespace/indentation), unless replace_all is true.",
        parameters = mapOf(
            "tool_title" to AgentToolParam("string", "A concise 5-10 word summary of what this tool call does, shown to the user (e.g. 'Fix typo in Python script', 'Update config value'). Use the same language as the user."),
            "path" to AgentToolParam("string", "Absolute Linux path to the file to edit (e.g. /root/script.py)"),
            "old_string" to AgentToolParam("string", "The exact text to find in the file. Must match precisely including whitespace and indentation. Must be unique in the file unless replace_all is true."),
            "new_string" to AgentToolParam("string", "The replacement text. Use empty string to delete old_string."),
            "replace_all" to AgentToolParam("boolean", "If true, replace ALL occurrences of old_string (default: false)"),
        ),
        required = listOf("tool_title", "path", "old_string", "new_string"),
        propertyOrdering = listOf("tool_title", "path", "old_string", "new_string", "replace_all"),
    )

    suspend fun execute(argsJson: String, sessionId: String, context: Context): ToolExecutionResult {
        return try {
            val args = JSONObject(argsJson)
            val path = args.optString("path", "")
            val oldString = args.optString("old_string", "")
            val newString = args.optString("new_string", "")
            val replaceAll = args.optBoolean("replace_all", false)
            val toolTitle = args.optString("tool_title", NAME)

            if (path.isBlank()) {
                return ToolExecutionResult("Error: 'path' is required", false, toolTitle = toolTitle)
            }
            if (oldString.isEmpty()) {
                return ToolExecutionResult("Error: 'old_string' is required and cannot be empty", false, toolTitle = toolTitle)
            }

            // Per-session permission preset (DSH /permission) gate. This is a
            // product security boundary and must stay independent of the Linux
            // backend implementation.
            if (!SessionPermissionStore.allowsFileWrite(context, sessionId, path)) {
                return ToolExecutionResult(
                    "Error: session permission preset `workspace-write` only allows writing under " +
                        "/var/minis/workspace (and per-session /var/minis/* dirs); refusing to edit $path.",
                    false, toolTitle = toolTitle,
                )
            }

            // Upstream read-only mount behavior, routed through the Ubuntu/Root
            // runtime path registry instead of PRootKernel.
            if (RuntimePathRegistry.isLinuxPathUnderReadOnlyMount(path)) {
                return ToolExecutionResult(
                    "Error: $path is inside a read-only mounted folder and cannot be modified. " +
                        "Toggle writability in Settings → Mount External Folders if this is a mistake.",
                    false, toolTitle = toolTitle,
                )
            }

            val externalMountPath = ExternalMountAccess.isPath(path)
            val metadata = if (externalMountPath) {
                ExternalMountAccess.info(path)
            } else {
                WorkspaceFileClient.info(sessionId, path)
            }
            if (!metadata.optBoolean("exists", false)) {
                return ToolExecutionResult("Error: File not found: $path", false, toolTitle = toolTitle)
            }

            // PRootKernel.resolveSessionHostPath is replaced only at this I/O
            // boundary; edit matching and replacement semantics remain upstream.
            val content = if (externalMountPath) {
                ExternalMountAccess.read(path, WorkspaceFileClient.MAX_FILE_BYTES).toString(Charsets.UTF_8)
            } else {
                WorkspaceFileClient.readAll(sessionId, path).toString(Charsets.UTF_8)
            }

            var count = 0
            var searchFrom = 0
            while (true) {
                val idx = content.indexOf(oldString, searchFrom)
                if (idx < 0) break
                count++
                searchFrom = idx + oldString.length
            }

            if (count == 0) {
                return ToolExecutionResult("Error: old_string not found in $path", false, toolTitle = toolTitle)
            }

            if (count > 1 && !replaceAll) {
                return ToolExecutionResult(
                    "Error: old_string found $count times in $path. Use replace_all=true to replace all occurrences, " +
                        "or provide a more specific old_string that matches exactly once.",
                    false, toolTitle = toolTitle,
                )
            }

            val newContent = if (replaceAll) {
                content.replace(oldString, newString)
            } else {
                content.replaceFirst(oldString, newString)
            }
            val bytes = newContent.toByteArray(Charsets.UTF_8)
            if (externalMountPath) {
                ExternalMountAccess.write(path, bytes, append = false)
            } else {
                WorkspaceFileClient.writeBytes(sessionId, path, bytes)
            }

            val replacements = if (replaceAll) count else 1
            ToolExecutionResult(
                "Edited $path ($replacements replacement(s), ${newContent.length} bytes)",
                true,
                toolTitle = toolTitle,
            )
        } catch (e: Exception) {
            ToolExecutionResult("Error editing file: ${e.message}", false)
        }
    }
}
