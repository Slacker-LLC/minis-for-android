package com.openminis.app.tools

import android.content.Context
import com.openminis.app.data.model.AgentToolDefinition
import com.openminis.app.data.model.AgentToolParam
import com.openminis.app.runtime.minisd.WorkspaceFileClient
import org.json.JSONObject

object FileReadTool {
    const val NAME = "file_read"

    fun definition(): AgentToolDefinition = AgentToolDefinition(
        name = NAME,
        description = "Read a file from the Linux filesystem. Faster than shell_execute for reading files — no shell overhead. Returns file content with metadata. Rejects binary files.",
        parameters = mapOf(
            "tool_title" to AgentToolParam("string", "A concise 5-10 word summary of what this tool call does, shown to the user (e.g. 'Read Python script contents', 'Check system configuration file'). Use the same language as the user."),
            "path" to AgentToolParam("string", "Absolute Linux path to read (e.g. /var/minis/workspace/data.csv)"),
            "offset" to AgentToolParam("integer", "1-based line number to start reading from (default: 1). Ignored when direction is 'tail'. If a previous read was truncated, its header ends with next_offset=N — pass that as offset to continue from where it stopped."),
            "lines" to AgentToolParam("integer", "Maximum number of lines to return (default: all lines up to max_length)"),
            "max_length" to AgentToolParam("integer", "Maximum character length of returned content (default: 15000)"),
            "direction" to AgentToolParam("string", "Read direction: 'head' (from start, default) or 'tail' (from end of file)"),
        ),
        required = listOf("tool_title", "path"),
        propertyOrdering = listOf("tool_title", "path", "offset", "lines", "direction", "max_length"),
        timeoutMs = 60_000L,
    )

    suspend fun execute(argsJson: String, sessionId: String, context: Context): ToolExecutionResult {
        return try {
            val args = JSONObject(argsJson)
            val path = args.optString("path", "")
            val toolTitle = args.optString("tool_title", NAME)
            val offset = args.optInt("offset", 1).coerceAtLeast(1)
            // T-FILEREAD-CAP: hard upper bound on returned content length.
            // Pre-cap, the agent could ask for `max_length=1_000_000` and we
            // would happily inline a 400 KB base64 image into a tool_result —
            // which then renders as a single user-message bubble and locks
            // up Compose's StaticLayout / LineBreaker for tens of seconds
            // (see HangDetector report for session
            // e84882d7-2087-47f8-9300-ff2c897fe0b4: 820 KB partsJson, 43 s
            // hang in nComputeLineBreaks). Cap at 80 KB regardless of
            // requested value; the truncation tail below tells the agent the
            // full file size so it can paginate with offset/lines if needed.
            // iOS mirrors this cap in AIChatViewModel.executeFileRead.
            val MAX_LENGTH_HARD_CAP = 80_000
            val maxLength = args.optInt("max_length", 15000).coerceAtLeast(0).coerceAtMost(MAX_LENGTH_HARD_CAP)
            val direction = args.optString("direction", "head")

            if (path.isBlank()) {
                return ToolExecutionResult("Error: 'path' is required", false, toolTitle = toolTitle)
            }

            val fileBytes = if (ExternalMountAccess.isPath(path)) {
                ExternalMountAccess.read(path, 50L * 1024 * 1024)
            } else {
                WorkspaceFileClient.readAll(
                    sessionId = sessionId,
                    path = path,
                    maxBytes = 50L * 1024 * 1024,
                )
            }
            val size = fileBytes.size.toLong()

            // Binary detection: check first 8192 bytes for null bytes.
            val isBinary = fileBytes.take(8192).any { it == 0.toByte() }

            if (isBinary) {
                return ToolExecutionResult(
                    "[$path | $size bytes | binary file — cannot display contents]",
                    true, toolTitle = toolTitle
                )
            }

            val allLines = fileBytes.toString(Charsets.UTF_8).lines()
            val totalLines = allLines.size

            val requestedLines = if (args.has("lines")) args.optInt("lines").coerceAtLeast(0) else null

            val selectedLines = if (direction == "tail") {
                val count = requestedLines ?: totalLines
                val start = (totalLines - count).coerceAtLeast(0)
                allLines.subList(start, totalLines)
            } else {
                val start = (offset - 1).coerceIn(0, totalLines)
                val end = if (requestedLines != null) {
                    (start + requestedLines).coerceAtMost(totalLines)
                } else {
                    totalLines
                }
                allLines.subList(start, end)
            }

            val showStart = if (direction == "tail") {
                (totalLines - selectedLines.size) + 1
            } else {
                offset
            }
            val showEnd = showStart + selectedLines.size - 1

            val output = FileReadOutputFormatter.format(
                path = path,
                size = size,
                totalLines = totalLines,
                selectedLines = selectedLines,
                showStart = showStart,
                direction = direction,
                maxLength = maxLength,
            )
            ToolExecutionResult(output, true, toolTitle = toolTitle)
        } catch (e: Exception) {
            ToolExecutionResult("Error reading file: ${e.message}", false)
        }
    }
}
