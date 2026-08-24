package com.openminis.app.tools

import android.content.Context
import com.openminis.app.data.model.AgentToolDefinition
import com.openminis.app.data.model.AgentToolParam
import com.openminis.app.sandbox.MinisKernel
import com.openminis.app.sandbox.ubuntu.UbuntuPaths
import com.openminis.app.tools.internal.FileMutationQueue
import com.openminis.app.tools.runtime.ToolHandler
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * linux.file.* operations — host-side implementations over the workspace
 * (App filesDir/minis/workspace, bind-mounted into the Ubuntu guest as
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

    private fun resolve(sessionId: String, path: String, context: Context): File? =
        UbuntuPaths.resolveSessionHostPath(sessionId, path, context)

    // small carrier so the object can stay suspend-free
    data class Carrier(val ok: Boolean, val message: String = "", val file: File? = null)

    private fun carrier(sessionId: String, path: String, context: Context): Carrier {
        val f = resolve(sessionId, path, context)
        return if (f == null) Carrier(false, "Error: Cannot resolve path: $path") else Carrier(true, file = f)
    }

    private fun writable(sessionId: String, path: String, context: Context): ToolExecutionResult? {
        if (!SessionPermissionStore.allowsFileWrite(context, sessionId, path)) {
            return ToolExecutionResult("Error: session permission denies writing $path", false)
        }
        if (MinisKernel.isLinuxPathUnderReadOnlyMount(path)) {
            return ToolExecutionResult("Error: $path is inside a read-only mounted folder", false)
        }
        return null
    }

    private fun guestPath(rootPath: String, root: File, file: File): String {
        val relative = file.relativeTo(root).path.replace(File.separatorChar, '/')
        return rootPath.trimEnd('/').let { if (relative.isEmpty()) it else "$it/$relative" }
    }

    private fun inside(root: File, file: File): Boolean = runCatching {
        val base = root.canonicalPath
        val target = file.canonicalPath
        target == base || target.startsWith(base + File.separator)
    }.getOrDefault(false)

    fun append(sessionId: String, path: String, content: String, context: Context): ToolExecutionResult {
        writable(sessionId, path, context)?.let { return it }
        val c = carrier(sessionId, path, context)
        if (!c.ok) return ToolExecutionResult(c.message, false)
        val f = c.file!!
        return try {
            FileMutationQueue.withFile(f) {
                f.parentFile?.mkdirs()
                f.appendText(content)
                ToolExecutionResult("appended ${content.length} chars to $path", true)
            }
        } catch (t: Throwable) {
            ToolExecutionResult("Error: append failed: ${t.message}", false)
        }
    }

    fun copy(sessionId: String, source: String, destination: String, context: Context): ToolExecutionResult {
        writable(sessionId, destination, context)?.let { return it }
        val src = resolve(sessionId, source, context)
            ?: return ToolExecutionResult("Error: Cannot resolve source: $source", false)
        val dst = resolve(sessionId, destination, context)
            ?: return ToolExecutionResult("Error: Cannot resolve destination: $destination", false)
        if (!src.exists()) return ToolExecutionResult("Error: source not found: $source", false)
        if (src.absolutePath == dst.absolutePath) return ToolExecutionResult("Error: source == destination", false)
        if (src.isDirectory && inside(src, dst)) return ToolExecutionResult("Error: destination cannot be inside source", false)
        return try {
            FileMutationQueue.withFile(dst) {
                if (src.isDirectory) {
                    src.copyRecursively(dst, overwrite = true)
                    ToolExecutionResult("copied directory $source -> $destination", true)
                } else {
                    dst.parentFile?.mkdirs()
                    src.copyTo(dst, overwrite = true)
                    ToolExecutionResult("copied $source -> $destination", true)
                }
            }
        } catch (t: Throwable) {
            ToolExecutionResult("Error: copy failed: ${t.message}", false)
        }
    }

    fun move(sessionId: String, source: String, destination: String, context: Context): ToolExecutionResult {
        writable(sessionId, source, context)?.let { return it }
        writable(sessionId, destination, context)?.let { return it }
        val src = resolve(sessionId, source, context)
            ?: return ToolExecutionResult("Error: Cannot resolve source: $source", false)
        val dst = resolve(sessionId, destination, context)
            ?: return ToolExecutionResult("Error: Cannot resolve destination: $destination", false)
        if (!src.exists()) return ToolExecutionResult("Error: source not found: $source", false)
        if (src.absolutePath == dst.absolutePath) return ToolExecutionResult("Error: source == destination", false)
        if (src.isDirectory && inside(src, dst)) return ToolExecutionResult("Error: destination cannot be inside source", false)
        return try {
            FileMutationQueue.withFile(src) {
                dst.parentFile?.mkdirs()
                if (!src.renameTo(dst)) {
                    src.copyRecursively(dst, overwrite = true)
                    src.deleteRecursively()
                }
                ToolExecutionResult("moved $source -> $destination", true)
            }
        } catch (t: Throwable) {
            ToolExecutionResult("Error: move failed: ${t.message}", false)
        }
    }

    fun delete(sessionId: String, path: String, context: Context): ToolExecutionResult {
        writable(sessionId, path, context)?.let { return it }
        val f = resolve(sessionId, path, context)
            ?: return ToolExecutionResult("Error: Cannot resolve path: $path", false)
        if (!f.exists()) return ToolExecutionResult("Error: not found: $path", false)
        return try {
            FileMutationQueue.withFile(f) {
                val ok = if (f.isDirectory) f.deleteRecursively() else f.delete()
                ToolExecutionResult(if (ok) "deleted $path" else "Error: delete failed", ok)
            }
        } catch (t: Throwable) {
            ToolExecutionResult("Error: delete failed: ${t.message}", false)
        }
    }

    fun list(sessionId: String, path: String, limit: Int, offset: Int, context: Context): ToolExecutionResult {
        val f = resolve(sessionId, path, context)
            ?: return ToolExecutionResult("Error: Cannot resolve path: $path", false)
        if (!f.exists()) return ToolExecutionResult("Error: not found: $path", false)
        if (!f.isDirectory) return ToolExecutionResult("Error: not a directory: $path", false)
        return try {
            val all = f.listFiles()?.sortedBy { it.name } ?: emptyList()
            val page = all.drop(offset.coerceAtLeast(0)).take(limit.coerceIn(1, MAX_LIST_ENTRIES))
            val arr = JSONArray()
            for (item in page) {
                arr.put(
                    JSONObject().apply {
                        put("name", item.name)
                        put("type", if (item.isDirectory) "dir" else "file")
                        put("size", if (item.isFile) item.length() else 0)
                        put("modified", item.lastModified())
                    },
                )
            }
            val out = JSONObject().apply {
                put("path", path)
                put("entries", arr)
                put("total", all.size)
                put("offset", offset)
                put("limit", page.size)
            }
            ToolExecutionResult(out.toString(2), true)
        } catch (t: Throwable) {
            ToolExecutionResult("Error: list failed: ${t.message}", false)
        }
    }

    fun search(
        sessionId: String,
        filename: String,
        folderPath: String?,
        fileType: String?,
        recursive: Boolean,
        limit: Int,
        context: Context,
    ): ToolExecutionResult {
        val rootPath = folderPath ?: "/workspace"
        val root = resolve(sessionId, rootPath, context)
            ?: return ToolExecutionResult("Error: Cannot resolve path: $rootPath", false)
        if (!root.isDirectory) return ToolExecutionResult("Error: not a directory: $rootPath", false)
        val needle = filename.lowercase()
        val ext = fileType?.lowercase()?.removePrefix(".")
        val results = mutableListOf<File>()
        val files = if (recursive) root.walkTopDown().filter { it != root } else (root.listFiles()?.asSequence() ?: emptySequence())
        for (f in files) {
            if (inside(root, f) && f.name.lowercase().contains(needle) &&
                (ext == null || f.extension.lowercase() == ext)
            ) {
                results.add(f)
                if (results.size >= limit.coerceIn(1, MAX_SEARCH_RESULTS)) break
            }
        }
        val arr = JSONArray()
        results.forEach { f ->
            arr.put(
                JSONObject().apply {
                    put("path", guestPath(rootPath, root, f))
                    put("type", if (f.isDirectory) "dir" else "file")
                    put("size", if (f.isFile) f.length() else 0)
                },
            )
        }
        val out = JSONObject().apply {
            put("query", filename)
            put("results", arr)
            put("count", results.size)
        }
        return ToolExecutionResult(out.toString(2), true)
    }

    fun grep(
        sessionId: String,
        path: String,
        pattern: String,
        contextLines: Int,
        maxResults: Int,
        context: Context,
    ): ToolExecutionResult {
        val f = resolve(sessionId, path, context)
            ?: return ToolExecutionResult("Error: Cannot resolve path: $path", false)
        if (!f.exists()) return ToolExecutionResult("Error: not found: $path", false)
        val regex = try {
            Regex(pattern, RegexOption.IGNORE_CASE)
        } catch (t: Throwable) {
            return ToolExecutionResult("Error: invalid regex: ${t.message}", false)
        }
        val files = if (f.isDirectory) f.walkTopDown().filter { it.isFile } else sequenceOf(f)
        val boundedContext = contextLines.coerceIn(0, 20)
        val hitLimit = maxResults.coerceIn(1, MAX_GREP_RESULTS)
        val hits = mutableListOf<JSONObject>()
        outer@ for (file in files) {
            if (!inside(f, file) || file.length() > MAX_GREP_FILE_BYTES) continue
            val lines = try {
                file.readLines()
            } catch (t: Throwable) {
                continue
            }
            for (i in lines.indices) {
                if (regex.containsMatchIn(lines[i])) {
                    val from = (i - boundedContext).coerceAtLeast(0)
                    val to = (i + boundedContext).coerceAtMost(lines.size - 1)
                    hits.add(
                        JSONObject().apply {
                            put("file", guestPath(path, f, file))
                            put("line", i + 1)
                            put("match", lines[i].trim().take(300))
                            put("context", lines.subList(from, to + 1).joinToString("\n").take(2000))
                        },
                    )
                    if (hits.size >= hitLimit) break@outer
                }
            }
        }
        val out = JSONObject().apply {
            put("pattern", pattern)
            put("results", JSONArray(hits))
            put("count", hits.size)
        }
        return ToolExecutionResult(out.toString(2), true)
    }

    fun headTail(sessionId: String, path: String, position: String, lines: Int, context: Context): ToolExecutionResult {
        val f = resolve(sessionId, path, context)
            ?: return ToolExecutionResult("Error: Cannot resolve path: $path", false)
        if (!f.exists() || f.isDirectory) return ToolExecutionResult("Error: not a file: $path", false)
        if (f.length() > MAX_TEXT_FILE_BYTES) return ToolExecutionResult("Error: file too large: $path", false)
        val n = lines.coerceIn(1, MAX_HEAD_TAIL_LINES)
        return try {
            val output = if (position == "tail") {
                val tail = java.util.ArrayDeque<String>(n)
                f.bufferedReader().useLines { source ->
                    source.forEach { line ->
                        if (tail.size == n) tail.removeFirst()
                        tail.addLast(line)
                    }
                }
                tail.joinToString("\n")
            } else {
                f.bufferedReader().use { reader ->
                    buildList {
                        repeat(n) { reader.readLine()?.let(::add) ?: return@repeat }
                    }.joinToString("\n")
                }
            }
            ToolExecutionResult(output, true)
        } catch (t: Throwable) {
            ToolExecutionResult("Error: read failed: ${t.message}", false)
        }
    }

    fun info(sessionId: String, path: String, context: Context): ToolExecutionResult {
        val f = resolve(sessionId, path, context)
            ?: return ToolExecutionResult("Error: Cannot resolve path: $path", false)
        if (!f.exists()) return ToolExecutionResult("Error: not found: $path", false)
        val out = JSONObject().apply {
            put("path", path)
            put("type", if (f.isDirectory) "dir" else "file")
            put("size", if (f.isFile) f.length() else 0)
            put("modified", f.lastModified())
            put("readable", f.canRead())
            put("writable", f.canWrite())
        }
        return ToolExecutionResult(out.toString(2), true)
    }
}

// ── thin handlers ───────────────────────────────────────────────────────────

abstract class LinuxFileOpHandler(
    name: String,
    description: String,
    params: Map<String, AgentToolParam>,
    required: List<String> = emptyList(),
    private val run: (JSONObject, String, Context) -> ToolExecutionResult,
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
