package com.openminis.app.tools

import android.content.Context
import com.openminis.app.data.model.AgentToolDefinition
import com.openminis.app.data.model.AgentToolParam
import com.openminis.app.runtime.RuntimePathRegistry
import com.openminis.app.runtime.files.WorkspaceFileClient
import org.json.JSONObject

object FileWriteTool {
    const val NAME = "file_write"

    fun definition(): AgentToolDefinition = AgentToolDefinition(
        name = NAME,
        description = "Write content to a file on the Linux filesystem. Faster than shell_execute for writing files. Creates the file if it doesn't exist. Use append mode to add to existing files.",
        parameters = mapOf(
            "tool_title" to AgentToolParam("string", "A concise 5-10 word summary of what this tool call does, shown to the user (e.g. 'Create Python statistics script', 'Write configuration file'). Use the same language as the user."),
            "path" to AgentToolParam("string", "Absolute Linux path to write (e.g. /root/test.txt)"),
            "content" to AgentToolParam("string", "The text content to write to the file"),
            "append" to AgentToolParam("boolean", "If true, append to existing file instead of overwriting (default: false)"),
            "create_dirs" to AgentToolParam("boolean", "If true, create parent directories if they don't exist (default: false)"),
        ),
        required = listOf("tool_title", "path", "content"),
        propertyOrdering = listOf("tool_title", "path", "content", "append", "create_dirs"),
    )

    suspend fun execute(argsJson: String, sessionId: String, context: Context): ToolExecutionResult {
        return try {
            val args = JSONObject(argsJson)
            val path = args.optString("path", "")
            val content = args.optString("content", "")
            val append = args.optBoolean("append", false)
            val toolTitle = args.optString("tool_title", NAME)

            if (path.isBlank()) {
                return ToolExecutionResult("Error: 'path' is required", false, toolTitle = toolTitle)
            }

            // Per-session permission preset (DSH /permission) gate. This is a
            // product security boundary and must stay independent of the Linux
            // backend implementation.
            if (!SessionPermissionStore.allowsFileWrite(context, sessionId, path)) {
                return ToolExecutionResult(
                    "Error: session permission preset `workspace-write` only allows writing under " +
                        "/var/minis/workspace (and per-session /var/minis/* dirs). This path is not" +
                        " allowed. Switch the session to `danger-full-access` on the device if this" +
                        " write must proceed.",
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

            val contentBytes = try {
                content.toByteArray(Charsets.UTF_8)
            } catch (_: Exception) {
                return ToolExecutionResult("Error: Content is not valid UTF-8", false, toolTitle = toolTitle)
            }

            // PRootKernel.resolveSessionHostPath is replaced only at this I/O
            // boundary. WorkspaceFileClient keeps the upstream per-session file
            // model while resolving paths against the Ubuntu chroot workspace.
            val bytes = if (ExternalMountAccess.isPath(path)) {
                ExternalMountAccess.write(path, contentBytes, append)
            } else if (append) {
                WorkspaceFileClient.appendBytes(sessionId, path, contentBytes)
            } else {
                WorkspaceFileClient.writeBytes(sessionId, path, contentBytes)
            }

            if (ExternalMountAccess.isPath(path)) {
                com.openminis.app.logging.AppLogger.info(
                    "FileWrite",
                    "mount write path=$path bytes=$bytes via=external-mount",
                )
            }
            ToolExecutionResult("Wrote to $path ($bytes bytes)", true, toolTitle = toolTitle)
        } catch (e: Exception) {
            ToolExecutionResult("Error writing file: ${e.message}", false)
        }
    }
}
