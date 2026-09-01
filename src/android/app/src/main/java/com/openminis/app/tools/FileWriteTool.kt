package com.openminis.app.tools

import android.content.Context
import com.openminis.app.data.model.AgentToolDefinition
import com.openminis.app.data.model.AgentToolParam
import com.openminis.app.runtime.RuntimePathRegistry
import com.openminis.app.runtime.minisd.WorkspaceFileClient
import com.openminis.app.tools.internal.FileMutationQueue
import com.openminis.app.tools.internal.FileRevision
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
        timeoutMs = 30_000L,
    )

    suspend fun execute(argsJson: String, sessionId: String, context: Context): ToolExecutionResult {
        return try {
            val args = JSONObject(argsJson)
            val path = args.optString("path", "")
            val content = args.optString("content", "")
            val append = args.optBoolean("append", false)
            // Transport-only optimistic-concurrency guard used by Web Remote.
            // It is intentionally not advertised in the LLM tool schema.
            val expectedSha256 = args.optString("expected_sha256", "").trim().lowercase()
            val toolTitle = args.optString("tool_title", NAME)

            if (path.isBlank()) {
                return ToolExecutionResult("Error: 'path' is required", false, toolTitle = toolTitle)
            }

            // Per-session permission preset (DSH /permission) gate. read-only
            // and workspace-write presets must really refuse out-of-bounds writes.
            if (!SessionPermissionStore.allowsFileWrite(context, sessionId, path)) {
                return ToolExecutionResult(
                    "Error: session permission preset `workspace-write` only allows writing under " +
                        "/var/minis/workspace (and per-session /var/minis/* dirs). This path is not" +
                        " allowed. Switch the session to `danger-full-access` on the device if this" +
                        " write must proceed.",
                    false, toolTitle = toolTitle,
                )
            }

            // T219: read-only mount guard. Reject before opening so we don't
            // half-create files inside a Locked external mount and surface a
            // friendly hint pointing the user at Settings. Mirrors iOS
            // ExternalMountCoordinator.isLinuxPathUnderReadOnlyMount used by
            // AIChatViewModel.fileWrite (AIChatViewModel.swift:8333-8341).
            if (RuntimePathRegistry.isLinuxPathUnderReadOnlyMount(path)) {
                return ToolExecutionResult(
                    "Error: $path is inside a read-only mounted folder and cannot be modified. " +
                        "Toggle writability in Settings → Mount External Folders if this is a mistake.",
                    false, toolTitle = toolTitle,
                )
            }

            // Validate UTF-8
            val contentBytes = try {
                content.toByteArray(Charsets.UTF_8)
            } catch (_: Exception) {
                return ToolExecutionResult("Error: Content is not valid UTF-8", false, toolTitle = toolTitle)
            }

            // Pi-style per-file mutation queue: concurrent writes/edits against
            // the same guest target are serialized in request order. The actual
            // file remains behind minisd because App cannot cross the SELinux
            // boundary to /data/adb/minis directly.
            FileMutationQueue.withKey("$sessionId\u0000$path") {
                val externalMountFile = if (path == "/var/minis/mounts" || path.startsWith("/var/minis/mounts/")) {
                    RuntimePathRegistry.resolveHostPath(path)
                        ?: return@withKey ToolExecutionResult("Error: Cannot resolve path: $path", false, toolTitle = toolTitle)
                } else {
                    null
                }
                if (expectedSha256.isNotEmpty()) {
                    val current = try {
                        if (externalMountFile != null) {
                            if (!externalMountFile.exists() || !externalMountFile.isFile) {
                                return@withKey ToolExecutionResult(
                                    "Error: File changed since it was opened (it no longer exists): $path",
                                    false, toolTitle = toolTitle,
                                )
                            }
                            externalMountFile.readBytes()
                        } else {
                            WorkspaceFileClient.readAll(sessionId, path)
                        }
                    } catch (error: WorkspaceFileClient.Failure) {
                        if (error.code == "RUNTIME_UNAVAILABLE") {
                            return@withKey ToolExecutionResult(
                                "Error: File changed since it was opened (it no longer exists): $path",
                                false, toolTitle = toolTitle,
                            )
                        }
                        throw error
                    }
                    if (!FileRevision.sha256(current).equals(expectedSha256, ignoreCase = true)) {
                        return@withKey ToolExecutionResult(
                            "Error: File changed since it was opened; reload before saving: $path",
                            false, toolTitle = toolTitle,
                        )
                    }
                }

                val bytes = if (externalMountFile != null) {
                    ExternalMountAccess.write(path, contentBytes, append)
                } else if (append) {
                    WorkspaceFileClient.appendBytes(sessionId, path, contentBytes)
                } else {
                    WorkspaceFileClient.writeBytes(sessionId, path, contentBytes)
                }

                if (externalMountFile != null) {
                    com.openminis.app.logging.AppLogger.info(
                        "FileWrite",
                        "mount write path=$path host=${externalMountFile.absolutePath} bytes=$bytes " +
                            "landedOk=${externalMountFile.exists()} via=android-mount",
                    )
                }
                ToolExecutionResult("Wrote to $path ($bytes bytes)", true, toolTitle = toolTitle)
            }
        } catch (e: Exception) {
            ToolExecutionResult("Error writing file: ${e.message}", false)
        }
    }
}
