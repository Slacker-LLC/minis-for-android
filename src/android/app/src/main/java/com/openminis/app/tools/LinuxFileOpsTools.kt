package com.openminis.app.tools

import android.content.Context
import com.openminis.app.data.model.AgentToolDefinition
import com.openminis.app.data.model.AgentToolParam
import com.openminis.app.runtime.RuntimePathRegistry
import com.openminis.app.runtime.minisd.WorkspaceFileClient
import com.openminis.app.tools.runtime.ToolHandler
import org.json.JSONObject

/**
 * linux.file.* operations — host-side implementations over the workspace
 * (/data/adb/minis/workspace, bind-mounted into the Ubuntu guest as
 * /workspace). Pure [LinuxFileOps] + thin per-tool [ToolHandler]s so the
 * permission table keeps one policy key per tool.
 */
object LinuxFileOps {

    const val MAX_LIST_ENTRIES = 500
    const val MAX_GREP_RESULTS = 200
    const val MAX_SEARCH_RESULTS = 200
    const val MAX_HEAD_TAIL_LINES = 1000
    private const val MAX_TEXT_FILE_BYTES = 50L * 1024 * 1024
    private const val MAX_GREP_FILE_BYTES = 4L * 1024 * 1024

    private fun writable(sessionId: String, path: String, context: Context): ToolExecutionResult? {
        if (!SessionPermissionStore.allowsFileWrite(context, sessionId, path)) {
            return ToolExecutionResult("Error: session permission denies writing $path", false)
        }
        if (RuntimePathRegistry.isLinuxPathUnderReadOnlyMount(path)) {
            return ToolExecutionResult("Error: $path is inside a read-only mounted folder", false)
        }
        return null
    }

    private fun childPath(root: String, name: String): String {
        val base = root.trimEnd('/')
        return if (base.isEmpty() || base == ".") {
            name
        } else {
            "$base/$name"
        }
    }

    private suspend fun entriesUnder(
        sessionId: String,
        rootPath: String,
        recursive: Boolean,
        maxEntries: Int,
    ): List<WorkspaceEntry> {
        if (ExternalMountAccess.isPath(rootPath)) {
            return ExternalMountAccess.walk(rootPath, recursive, maxEntries).map {
                WorkspaceEntry(it.path, it.name, it.type, it.size)
            }
        }
        val queue = java.util.ArrayDeque<String>()
        queue.add(rootPath)
        val result = mutableListOf<WorkspaceEntry>()
        while (queue.isNotEmpty() && result.size < maxEntries) {
            val current = queue.removeFirst()
            val listing = WorkspaceFileClient.list(sessionId, current, MAX_LIST_ENTRIES, 0)
            val items = listing.optJSONArray("entries") ?: continue
            for (index in 0 until items.length()) {
                val item = items.optJSONObject(index) ?: continue
                val path = childPath(current, item.optString("name"))
                val entry = WorkspaceEntry(
                    path = path,
                    name = item.optString("name"),
                    type = item.optString("type"),
                    size = item.optLong("size", 0),
                )
                result += entry
                if (recursive && entry.type == "dir" && result.size < maxEntries) {
                    queue.add(path)
                }
                if (result.size >= maxEntries) break
            }
        }
        return result
    }

    private data class WorkspaceEntry(
        val path: String,
        val name: String,
        val type: String,
        val size: Long,
    )

    suspend fun append(sessionId: String, path: String, content: String, context: Context): ToolExecutionResult {
        writable(sessionId, path, context)?.let { return it }
        return try {
            com.openminis.app.tools.internal.FileMutationQueue.withKey("$sessionId\u0000$path") {
                if (ExternalMountAccess.isPath(path)) {
                    ExternalMountAccess.write(path, content.toByteArray(Charsets.UTF_8), append = true)
                } else {
                    WorkspaceFileClient.appendBytes(sessionId, path, content.toByteArray(Charsets.UTF_8))
                }
                ToolExecutionResult("appended ${content.length} chars to $path", true)
            }
        } catch (t: Throwable) {
            ToolExecutionResult("Error: append failed: ${t.message}", false)
        }
    }

    suspend fun copy(sessionId: String, source: String, destination: String, context: Context): ToolExecutionResult {
        writable(sessionId, destination, context)?.let { return it }
        return try {
            val type = if (ExternalMountAccess.isPath(source) || ExternalMountAccess.isPath(destination)) {
                if (!ExternalMountAccess.isPath(source) || !ExternalMountAccess.isPath(destination)) {
                    return ToolExecutionResult("Error: cross-boundary external mount copy is unsupported", false)
                }
                ExternalMountAccess.copy(source, destination)
                ExternalMountAccess.info(source).optString("type")
            } else {
                WorkspaceFileClient.copy(sessionId, source, destination)
                WorkspaceFileClient.info(sessionId, source).optString("type")
            }
            if (type == "dir") {
                ToolExecutionResult("copied directory $source -> $destination", true)
            } else {
                ToolExecutionResult("copied $source -> $destination", true)
            }
        } catch (t: Throwable) {
            ToolExecutionResult("Error: copy failed: ${t.message}", false)
        }
    }

    suspend fun move(sessionId: String, source: String, destination: String, context: Context): ToolExecutionResult {
        writable(sessionId, source, context)?.let { return it }
        writable(sessionId, destination, context)?.let { return it }
        return try {
            if (ExternalMountAccess.isPath(source) || ExternalMountAccess.isPath(destination)) {
                if (!ExternalMountAccess.isPath(source) || !ExternalMountAccess.isPath(destination)) {
                    return ToolExecutionResult("Error: cross-boundary external mount move is unsupported", false)
                }
                ExternalMountAccess.move(source, destination)
            } else {
                WorkspaceFileClient.move(sessionId, source, destination)
            }
            ToolExecutionResult("moved $source -> $destination", true)
        } catch (t: Throwable) {
            ToolExecutionResult("Error: move failed: ${t.message}", false)
        }
    }

    suspend fun delete(sessionId: String, path: String, context: Context): ToolExecutionResult {
        writable(sessionId, path, context)?.let { return it }
        return try {
            if (ExternalMountAccess.isPath(path)) {
                ExternalMountAccess.delete(path)
            } else {
                WorkspaceFileClient.delete(sessionId, path)
            }
            ToolExecutionResult("deleted $path", true)
        } catch (t: Throwable) {
            ToolExecutionResult("Error: delete failed: ${t.message}", false)
        }
    }

    suspend fun list(sessionId: String, path: String, limit: Int, offset: Int, context: Context): ToolExecutionResult {
        return try {
            val out = if (ExternalMountAccess.isPath(path)) {
                ExternalMountAccess.list(
                    path,
                    limit.coerceIn(1, MAX_LIST_ENTRIES),
                    offset.coerceAtLeast(0),
                )
            } else {
                WorkspaceFileClient.list(
                    sessionId,
                    path,
                    limit.coerceIn(1, MAX_LIST_ENTRIES),
                    offset.coerceAtLeast(0),
                )
            }
            ToolExecutionResult(out.toString(2), true)
        } catch (t: Throwable) {
            ToolExecutionResult("Error: list failed: ${t.message}", false)
        }
    }

    suspend fun search(
        sessionId: String,
        filename: String,
        folderPath: String?,
        fileType: String?,
        recursive: Boolean,
        limit: Int,
        context: Context,
    ): ToolExecutionResult {
        val rootPath = folderPath ?: "/workspace"
        val needle = filename.lowercase()
        val ext = fileType?.lowercase()?.removePrefix(".")
        return try {
            val entries = entriesUnder(
                sessionId,
                rootPath,
                recursive,
                limit.coerceIn(1, MAX_SEARCH_RESULTS),
            )
            val results = entries.filter { entry ->
                entry.name.lowercase().contains(needle) &&
                    (ext == null || entry.name.substringAfterLast('.', "").lowercase() == ext)
            }
            val arr = org.json.JSONArray()
            results.forEach { entry ->
                arr.put(
                    JSONObject().apply {
                        put("path", entry.path)
                        put("type", entry.type)
                        put("size", entry.size)
                    },
                )
            }
            ToolExecutionResult(
                JSONObject().apply {
                    put("query", filename)
                    put("results", arr)
                    put("count", results.size)
                }.toString(2),
                true,
            )
        } catch (t: Throwable) {
            ToolExecutionResult("Error: search failed: ${t.message}", false)
        }
    }

    suspend fun grep(
        sessionId: String,
        path: String,
        pattern: String,
        contextLines: Int,
        maxResults: Int,
        context: Context,
    ): ToolExecutionResult {
        val regex = try {
            Regex(pattern, RegexOption.IGNORE_CASE)
        } catch (t: Throwable) {
            return ToolExecutionResult("Error: invalid regex: ${t.message}", false)
        }
        val boundedContext = contextLines.coerceIn(0, 20)
        val hitLimit = maxResults.coerceIn(1, MAX_GREP_RESULTS)
        return try {
            val metadata = if (ExternalMountAccess.isPath(path)) {
                ExternalMountAccess.info(path)
            } else {
                WorkspaceFileClient.info(sessionId, path)
            }
            val files = if (metadata.optString("type") == "dir") {
                entriesUnder(sessionId, path, true, MAX_GREP_RESULTS * 10)
                    .filter { it.type == "file" }
            } else {
                listOf(
                    WorkspaceEntry(
                        path = path,
                        name = path.substringAfterLast('/'),
                        type = metadata.optString("type"),
                        size = metadata.optLong("size", 0),
                    ),
                )
            }
            val hits = mutableListOf<JSONObject>()
            outer@ for (file in files) {
                if (file.type != "file" || file.size > MAX_GREP_FILE_BYTES) continue
                val lines = try {
                    readBytes(sessionId, file.path, MAX_GREP_FILE_BYTES)
                        .toString(Charsets.UTF_8)
                        .lines()
                } catch (_: Throwable) {
                    continue
                }
                for (i in lines.indices) {
                    if (regex.containsMatchIn(lines[i])) {
                        val from = (i - boundedContext).coerceAtLeast(0)
                        val to = (i + boundedContext).coerceAtMost(lines.size - 1)
                        hits.add(
                            JSONObject().apply {
                                put("file", file.path)
                                put("line", i + 1)
                                put("match", lines[i].trim().take(300))
                                put("context", lines.subList(from, to + 1).joinToString("\n").take(2000))
                            },
                        )
                        if (hits.size >= hitLimit) break@outer
                    }
                }
            }
            ToolExecutionResult(
                JSONObject().apply {
                    put("pattern", pattern)
                    put("results", org.json.JSONArray(hits))
                    put("count", hits.size)
                }.toString(2),
                true,
            )
        } catch (t: Throwable) {
            ToolExecutionResult("Error: grep failed: ${t.message}", false)
        }
    }

    suspend fun headTail(sessionId: String, path: String, position: String, lines: Int, context: Context): ToolExecutionResult {
        val n = lines.coerceIn(1, MAX_HEAD_TAIL_LINES)
        return try {
            val content = readBytes(sessionId, path, MAX_TEXT_FILE_BYTES)
                .toString(Charsets.UTF_8)
                .lines()
            val output = if (position == "tail") {
                content.takeLast(n).joinToString("\n")
            } else {
                content.take(n).joinToString("\n")
            }
            ToolExecutionResult(output, true)
        } catch (t: Throwable) {
            ToolExecutionResult("Error: read failed: ${t.message}", false)
        }
    }

    suspend fun info(sessionId: String, path: String, context: Context): ToolExecutionResult = try {
        val metadata = if (ExternalMountAccess.isPath(path)) {
            ExternalMountAccess.info(path)
        } else {
            WorkspaceFileClient.info(sessionId, path)
        }
        ToolExecutionResult(metadata.toString(2), true)
    } catch (t: Throwable) {
        ToolExecutionResult("Error: info failed: ${t.message}", false)
    }

    private suspend fun readBytes(sessionId: String, path: String, maxBytes: Long): ByteArray =
        if (ExternalMountAccess.isPath(path)) {
            ExternalMountAccess.read(path, maxBytes)
        } else {
            WorkspaceFileClient.readAll(sessionId, path, maxBytes)
        }
}

// ── thin handlers ───────────────────────────────────────────────────────────

abstract class LinuxFileOpHandler(
    name: String,
    description: String,
    params: Map<String, AgentToolParam>,
    required: List<String> = emptyList(),
    private val run: suspend (JSONObject, String, Context) -> ToolExecutionResult,
) : ToolHandler {
    override val definition: AgentToolDefinition =
        AgentToolDefinition(name = name, description = description, parameters = params, required = required)
    override suspend fun execute(argsJson: String, sessionId: String, context: Context, toolId: String): ToolExecutionResult {
        val args = runCatching { JSONObject(argsJson) }.getOrElse { JSONObject() }
        return run(args, sessionId, context)
    }
}

class LinuxFileAppendHandler : LinuxFileOpHandler(
    "linux.file.append", "Append content to a file in the workspace. Parent directories are created automatically.",
    mapOf(
        "path" to AgentToolParam("string", "File path relative to workspace"),
        "content" to AgentToolParam("string", "Content to append"),
    ),
    listOf("path", "content"),
    run = { a, sid, ctx -> LinuxFileOps.append(sid, a.optString("path"), a.optString("content"), ctx) },
)

class LinuxFileCopyHandler : LinuxFileOpHandler(
    "linux.file.copy", "Copy a file or directory inside the workspace.",
    mapOf(
        "source" to AgentToolParam("string", "Source path"),
        "destination" to AgentToolParam("string", "Destination path"),
    ),
    listOf("source", "destination"),
    run = { a, sid, ctx -> LinuxFileOps.copy(sid, a.optString("source"), a.optString("destination"), ctx) },
)

class LinuxFileMoveHandler : LinuxFileOpHandler(
    "linux.file.move", "Move or rename a file or directory inside the workspace.",
    mapOf(
        "source" to AgentToolParam("string", "Source path"),
        "destination" to AgentToolParam("string", "Destination path"),
    ),
    listOf("source", "destination"),
    run = { a, sid, ctx -> LinuxFileOps.move(sid, a.optString("source"), a.optString("destination"), ctx) },
)

class LinuxFileDeleteHandler : LinuxFileOpHandler(
    "linux.file.delete", "Delete a file or directory in the workspace. Destructive — requires confirmation.",
    mapOf("path" to AgentToolParam("string", "Path to delete")),
    listOf("path"),
    run = { a, sid, ctx -> LinuxFileOps.delete(sid, a.optString("path"), ctx) },
)

class LinuxFileListHandler : LinuxFileOpHandler(
    "linux.file.list", "List entries of a directory in the workspace (sorted by name, paged).",
    mapOf(
        "path" to AgentToolParam("string", "Directory path (default: workspace root)"),
        "offset" to AgentToolParam("integer", "0-based offset"),
        "limit" to AgentToolParam("integer", "Max entries (default 100, max 500)"),
    ),
    listOf("path"),
    run = { a, sid, ctx ->
        LinuxFileOps.list(
            sid, a.optString("path", "."), a.optInt("limit", 100), a.optInt("offset", 0), ctx,
        )
    },
)

class LinuxFileSearchHandler : LinuxFileOpHandler(
    "linux.file.search", "Search files by name inside the workspace.",
    mapOf(
        "filename" to AgentToolParam("string", "Filename substring (case-insensitive)"),
        "folder" to AgentToolParam("string", "Folder to search (default: workspace root)"),
        "file_type" to AgentToolParam("string", "Optional extension filter (e.g. py)"),
        "recursive" to AgentToolParam("boolean", "Descend into subdirectories (default true)"),
        "limit" to AgentToolParam("integer", "Max results (default 100, max 200)"),
    ),
    listOf("filename"),
    run = { a, sid, ctx ->
        LinuxFileOps.search(
            sid,
            a.optString("filename"),
            a.optString("folder").ifBlank { null },
            a.optString("file_type").ifBlank { null },
            a.optBoolean("recursive", true),
            a.optInt("limit", 100),
            ctx,
        )
    },
)

class LinuxFileGrepHandler : LinuxFileOpHandler(
    "linux.file.grep", "Search lines matching a regex in a file or directory (case-insensitive).",
    mapOf(
        "path" to AgentToolParam("string", "File or directory path"),
        "pattern" to AgentToolParam("string", "Regex pattern"),
        "context_lines" to AgentToolParam("integer", "Context lines around matches (default 1)"),
        "max_results" to AgentToolParam("integer", "Max matches (default 50, max 200)"),
    ),
    listOf("pattern", "path"),
    run = { a, sid, ctx ->
        LinuxFileOps.grep(
            sid,
            a.optString("path", "."),
            a.optString("pattern"),
            a.optInt("context_lines", 1),
            a.optInt("max_results", 50),
            ctx,
        )
    },
)

class LinuxFileHeadTailHandler : LinuxFileOpHandler(
    "linux.file.head_tail", "Read the first (head) or last (tail) N lines of a file.",
    mapOf(
        "path" to AgentToolParam("string", "File path"),
        "position" to AgentToolParam("string", "head or tail", enumValues = listOf("head", "tail")),
        "lines" to AgentToolParam("integer", "Number of lines (default 50, max 1000)"),
    ),
    listOf("path"),
    run = { a, sid, ctx ->
        LinuxFileOps.headTail(sid, a.optString("path"), a.optString("position", "head"), a.optInt("lines", 50), ctx)
    },
)

class LinuxFileInfoHandler : LinuxFileOpHandler(
    "linux.file.info", "Get file metadata (type, size, timestamps, permissions).",
    mapOf("path" to AgentToolParam("string", "File path")),
    listOf("path"),
    run = { a, sid, ctx -> LinuxFileOps.info(sid, a.optString("path"), ctx) },
)
