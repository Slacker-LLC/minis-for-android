package com.openminis.app.tools

import android.content.Context
import com.openminis.app.data.model.AgentToolDefinition
import com.openminis.app.data.model.AgentToolParam
import com.openminis.app.runtime.RuntimePathRegistry
import com.openminis.app.runtime.files.WorkspaceFileClient
import com.openminis.app.tools.internal.FileEditEngine
import com.openminis.app.tools.internal.FileMutationQueue
import org.json.JSONArray
import org.json.JSONObject

object FileEditTool {
    const val NAME = "file_edit"
    private const val MAX_DIFF_CHARS = 12_000

    fun definition(): AgentToolDefinition {
        val editItem = AgentToolParam(
            type = "object",
            description = "One replacement. The call matches each old_text against the original file, not against the result of earlier edits.",
            properties = mapOf(
                "old_text" to AgentToolParam("string", "The exact text to replace. It must match one unique region of the original file, and the regions must not overlap."),
                "new_text" to AgentToolParam("string", "The replacement text. Use an empty string to delete the matched text."),
            ),
            requiredProperties = listOf("old_text", "new_text"),
        )
        return AgentToolDefinition(
            name = NAME,
            description = "Edit one existing text file with one or more atomic replacements. " +
            "Always read the relevant region with file_read first. " +
            "Use file_edit, not file_write, to change an existing file.\n\n" +
            "The call matches all edits against the same original text and rejects overlapping edits. " +
            "It matches the exact text first. Then it uses a careful fuzzy match for Unicode punctuation, special spaces, and trailing whitespace. " +
            "It keeps the original BOM and the CRLF or LF style.",
            parameters = mapOf(
                "tool_title" to ToolText.TOOL_TITLE,
                "path" to AgentToolParam("string", "The absolute Linux path of the file to edit. Example: /var/minis/workspace/app/src/Main.kt."),
                "edits" to AgentToolParam("array", "One or more replacements that do not overlap. If changes are near each other in the same block, merge them into one edit.", items = editItem),
                // Legacy fields remain accepted at runtime so old tool traces / clients do not break.
                "old_string" to AgentToolParam("string", "Legacy field for a single edit. Use edits[].old_text instead."),
                "new_string" to AgentToolParam("string", "Legacy field for a single edit. Use edits[].new_text instead."),
                "replace_all" to AgentToolParam("boolean", "Legacy field. If true with old_string and new_string, replace all exact matches."),
            ),
            required = listOf("tool_title", "path", "edits"),
            propertyOrdering = listOf("tool_title", "path", "edits", "old_string", "new_string", "replace_all"),
        )
    }

    suspend fun execute(argsJson: String, sessionId: String, context: Context): ToolExecutionResult {
        return try {
            val args = JSONObject(argsJson)
            val path = args.optString("path", "")
            val toolTitle = args.optString("tool_title", NAME)
            if (path.isBlank()) {
                return ToolExecutionResult("Error: 'path' is required", false, toolTitle = toolTitle)
            }

            // Per-session permission preset is a product security boundary and
            // stays independent of the Linux backend implementation.
            if (!SessionPermissionStore.allowsFileWrite(context, sessionId, path)) {
                return ToolExecutionResult(
                    "Error: session permission preset `workspace-write` only allows writing under " +
                        "/var/minis/workspace (and per-session /var/minis/* dirs); refusing to edit $path.",
                    false, toolTitle = toolTitle,
                )
            }

            // Keep upstream read-only-mount semantics while routing the check
            // through the Direct Ubuntu/Root path registry.
            if (RuntimePathRegistry.isLinuxPathUnderReadOnlyMount(path)) {
                return ToolExecutionResult(
                    "Error: $path is inside a read-only mounted folder and cannot be modified. " +
                        "Toggle writability in Settings → Mount External Folders if this is a mistake.",
                    false, toolTitle = toolTitle,
                )
            }

            // Read, match and write under one per-target transaction so another
            // file_write/file_edit cannot invalidate the snapshot in between.
            FileMutationQueue.withKey(FileMutationQueue.keyFor(sessionId, path)) {
                val metadata = WorkspaceFileClient.info(sessionId, path)
                if (!metadata.optBoolean("exists", false)) {
                    return@withKey ToolExecutionResult("Error: File not found: $path", false, toolTitle = toolTitle)
                }
                val type = metadata.optString("type", "")
                if (type.isNotBlank() && type != "file") {
                    return@withKey ToolExecutionResult("Error: Path is not a regular file: $path", false, toolTitle = toolTitle)
                }

                // The edit rewrites the whole file, so every byte has to survive
                // the decode: refuse a file that is not valid UTF-8 instead of
                // turning its bad bytes into U+FFFD.
                val content = try {
                    Charsets.UTF_8.newDecoder()
                        .onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
                        .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT)
                        .decode(java.nio.ByteBuffer.wrap(WorkspaceFileClient.readAll(sessionId, path)))
                        .toString()
                } catch (_: java.nio.charset.CharacterCodingException) {
                    return@withKey ToolExecutionResult(
                        "Error: $path is not valid UTF-8; file_edit would change bytes outside the edit. " +
                            "Use a shell tool for binary or non-UTF-8 files.",
                        false, toolTitle = toolTitle,
                    )
                }
                val edits = parseEdits(args)
                if (edits.isEmpty()) {
                    return@withKey ToolExecutionResult(
                        "Error: provide a non-empty 'edits' array (or legacy old_string/new_string)",
                        false, toolTitle = toolTitle,
                    )
                }

                // Preserve legacy replace_all semantics separately: Pi-style
                // multi-edit requires unique matches, while old traces may
                // intentionally replace every exact occurrence.
                if (!args.has("edits") && args.optBoolean("replace_all", false)) {
                    val old = args.optString("old_string", "")
                    val new = args.optString("new_string", "")
                    if (old.isEmpty()) {
                        return@withKey ToolExecutionResult("Error: old_string cannot be empty", false, toolTitle = toolTitle)
                    }
                    val normalized = FileEditEngine.normalizeLf(content)
                    val normalizedOld = FileEditEngine.normalizeLf(old)
                    val count = Regex.escape(normalizedOld).toRegex().findAll(normalized).count()
                    if (count == 0) {
                        return@withKey ToolExecutionResult("Error: old_string not found in $path", false, toolTitle = toolTitle)
                    }
                    val updated = normalized.replace(normalizedOld, FileEditEngine.normalizeLf(new))
                    val restored = FileEditEngine.restoreLineEnding(updated, FileEditEngine.detectLineEnding(content))
                    val bytes = WorkspaceFileClient.writeBytes(sessionId, path, restored.toByteArray(Charsets.UTF_8))
                    return@withKey ToolExecutionResult("Edited $path ($count replacements, $bytes bytes)", true, toolTitle = toolTitle)
                }

                val result = FileEditEngine.apply(content, edits, path)
                val bytes = WorkspaceFileClient.writeBytes(sessionId, path, result.newContent.toByteArray(Charsets.UTF_8))
                val fuzzyNote = if (result.fuzzyMatchCount > 0) ", ${result.fuzzyMatchCount} fuzzy match(es)" else ""
                val lineNote = result.firstChangedLine?.let { ", first changed line $it" }.orEmpty()
                val diff = takeCodePoints(result.diff, MAX_DIFF_CHARS)
                val diffNote = if (result.diff.length > MAX_DIFF_CHARS) "\n[diff truncated to $MAX_DIFF_CHARS chars]" else ""
                ToolExecutionResult(
                    "Edited $path (${result.replacementCount} block(s)$fuzzyNote$lineNote, $bytes bytes)" +
                        if (diff.isNotBlank()) "\n\n$diff$diffNote" else "",
                    true,
                    toolTitle = toolTitle,
                )
            }
        } catch (e: Exception) {
            ToolExecutionResult("Error editing file: ${e.message}", false)
        }
    }

    /** Take at most [max] UTF-16 code units without splitting a surrogate pair. */
    private fun takeCodePoints(text: String, max: Int): String {
        if (text.length <= max) return text
        var end = max
        if (Character.isHighSurrogate(text[end - 1]) && end < text.length &&
            Character.isLowSurrogate(text[end])
        ) {
            end -= 1
        }
        return text.substring(0, end)
    }

    internal fun parseEdits(args: JSONObject): List<FileEditEngine.Edit> {
        val out = mutableListOf<FileEditEngine.Edit>()
        val array: JSONArray? = when (val raw = args.opt("edits")) {
            is JSONArray -> raw
            is String -> runCatching { JSONArray(raw) }.getOrNull()
            is JSONObject -> JSONArray().put(raw)
            else -> null
        }
        if (array != null) {
            for (i in 0 until array.length()) {
                // A null or non-object entry is a malformed request: refuse all of
                // it rather than apply the rest and report success.
                val item = array.optJSONObject(i)
                    ?: throw IllegalArgumentException("edits[$i] must be an object with old_text and new_text")
                val oldText = if (item.has("old_text")) {
                    item.optString("old_text", "")
                } else {
                    item.optString("oldText", "")
                }
                val newText = if (item.has("new_text")) {
                    item.optString("new_text", "")
                } else {
                    item.optString("newText", "")
                }
                out += FileEditEngine.Edit(oldText, newText)
            }
        }
        if (out.isEmpty() && args.has("old_string") && args.has("new_string")) {
            out += FileEditEngine.Edit(args.optString("old_string", ""), args.optString("new_string", ""))
        }
        return out
    }
}
