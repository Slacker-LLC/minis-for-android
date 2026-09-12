package com.openminis.app.runtime.files

import com.openminis.app.runtime.RuntimePathRegistry
import com.openminis.app.runtime.ubuntu.UbuntuPaths
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.InputStream

/**
 * App-owned guest workspace file API.
 *
 * Guest names are resolved to a trusted directory root plus components and
 * then opened with directory-handle operations. In particular, no operation
 * reopens a previously checked path through java.io.File.
 */
internal object WorkspaceFileClient {
    const val MAX_READ_CHUNK = 512 * 1024
    const val MAX_WRITE_CHUNK = 32 * 1024
    const val MAX_FILE_BYTES = 50L * 1024 * 1024

    data class ReadChunk(
        val bytes: ByteArray,
        val offset: Long,
        val totalBytes: Long,
        val eof: Boolean,
    )

    class Failure(
        val code: String,
        detail: String,
    ) : IllegalStateException("$code: $detail")

    suspend fun readChunk(
        sessionId: String?,
        path: String,
        offset: Long = 0,
        length: Int = MAX_READ_CHUNK,
    ): ReadChunk = withContext(Dispatchers.IO) {
        SecureFileAccess.readChunk(requirePath(sessionId, path), offset, length, MAX_READ_CHUNK)
    }

    suspend fun readAll(
        sessionId: String?,
        path: String,
        maxBytes: Long = MAX_FILE_BYTES,
    ): ByteArray = withContext(Dispatchers.IO) {
        SecureFileAccess.readAll(requirePath(sessionId, path), maxBytes)
    }

    fun readAllBlocking(
        sessionId: String?,
        path: String,
        maxBytes: Long = MAX_FILE_BYTES,
    ): ByteArray = runBlocking(Dispatchers.IO) { readAll(sessionId, path, maxBytes) }

    suspend fun readToFile(
        sessionId: String?,
        path: String,
        destination: File,
        maxBytes: Long = MAX_FILE_BYTES,
    ): Long = withContext(Dispatchers.IO) {
        SecureFileAccess.readToFile(requirePath(sessionId, path), destination, maxBytes)
    }

    fun readToFileBlocking(
        sessionId: String?,
        path: String,
        destination: File,
        maxBytes: Long = MAX_FILE_BYTES,
    ): Long = runBlocking(Dispatchers.IO) { readToFile(sessionId, path, destination, maxBytes) }

    suspend fun writeBytes(sessionId: String?, path: String, bytes: ByteArray): Long =
        withContext(Dispatchers.IO) {
            requireWritablePath(path)
            SecureFileAccess.writeBytes(requirePath(sessionId, path), bytes, MAX_FILE_BYTES)
        }

    suspend fun appendBytes(sessionId: String?, path: String, bytes: ByteArray): Long =
        withContext(Dispatchers.IO) {
            requireWritablePath(path)
            SecureFileAccess.appendBytes(requirePath(sessionId, path), bytes, MAX_FILE_BYTES)
        }

    suspend fun writeStream(
        sessionId: String?,
        path: String,
        input: InputStream,
        maxBytes: Long = MAX_FILE_BYTES,
        onChunk: suspend (ByteArray, Long) -> Unit = { _, _ -> },
    ): Long = withContext(Dispatchers.IO) {
        requireWritablePath(path)
        SecureFileAccess.writeStream(requirePath(sessionId, path), input, maxBytes, onChunk)
    }

    suspend fun uniqueChildPath(sessionId: String?, directory: String, filename: String): String {
        require(filename.isNotEmpty() && !filename.contains('/') && !filename.contains('\\')) {
            "filename must be a single path component"
        }
        val used = runCatching {
            val array = list(sessionId, directory, 500, 0).optJSONArray("entries") ?: JSONArray()
            buildSet {
                for (i in 0 until array.length()) array.optJSONObject(i)?.optString("name")?.let(::add)
            }
        }.getOrDefault(emptySet<String>())
        if (filename !in used) return childPath(directory, filename)
        val dot = filename.lastIndexOf('.')
        val base = if (dot > 0) filename.substring(0, dot) else filename
        val ext = if (dot > 0) filename.substring(dot) else ""
        var index = 1
        while (true) {
            val candidate = "$base-$index$ext"
            if (candidate !in used) return childPath(directory, candidate)
            index++
        }
    }

    suspend fun copy(
        sessionId: String?,
        source: String,
        destination: String,
        sourceSessionId: String? = null,
        destinationSessionId: String? = null,
    ): JSONObject = withContext(Dispatchers.IO) {
        requireWritablePath(destination)
        val sourcePath = requirePath(sourceSessionId ?: sessionId, source)
        val destinationPath = requirePath(destinationSessionId ?: sessionId, destination)
        val type = SecureFileAccess.copy(sourcePath, destinationPath)
        JSONObject().put("copied", true).put("type", type)
    }

    suspend fun move(
        sessionId: String?,
        source: String,
        destination: String,
        sourceSessionId: String? = null,
        destinationSessionId: String? = null,
    ): JSONObject = withContext(Dispatchers.IO) {
        requireWritablePath(source)
        requireWritablePath(destination)
        val sourcePath = requirePath(sourceSessionId ?: sessionId, source)
        val destinationPath = requirePath(destinationSessionId ?: sessionId, destination)
        val type = SecureFileAccess.move(sourcePath, destinationPath)
        JSONObject().put("moved", true).put("type", type)
    }

    suspend fun mkdir(sessionId: String?, path: String): JSONObject = withContext(Dispatchers.IO) {
        requireWritablePath(path)
        val attributes = SecureFileAccess.mkdir(requirePath(sessionId, path))
        infoJson(attributes)
    }

    suspend fun deleteSession(sessionId: String): JSONObject = withContext(Dispatchers.IO) {
        if (!UbuntuPaths.isSafeSessionId(sessionId)) throw Failure("BAD_PARAMS", "invalid session id")
        val path = UbuntuPaths.SecureFilePath(
            root = File(UbuntuPaths.hostSessions).absoluteFile,
            components = listOf(sessionId),
        )
        val deleted = SecureFileAccess.delete(path)
        if (!deleted) throw Failure("IO_ERROR", "cannot delete session $sessionId")
        JSONObject().put("deleted", true)
    }

    fun deleteSessionBlocking(sessionId: String): JSONObject = runBlocking(Dispatchers.IO) { deleteSession(sessionId) }

    suspend fun delete(sessionId: String?, path: String): JSONObject = withContext(Dispatchers.IO) {
        requireWritablePath(path)
        val target = requirePath(sessionId, path)
        JSONObject().put("deleted", SecureFileAccess.delete(target))
    }

    suspend fun list(sessionId: String?, path: String, limit: Int, offset: Int): JSONObject =
        withContext(Dispatchers.IO) {
            require(limit in 1..5000) { "limit out of range" }
            require(offset >= 0) { "offset must be non-negative" }
            if (path.trimEnd('/') == "/var/minis/mounts") {
                return@withContext listMountRoots(limit, offset)
            }
            val (entries, next) = SecureFileAccess.list(requirePath(sessionId, path), limit, offset)
            val array = JSONArray()
            entries.forEach { (name, attributes) -> array.put(infoJson(attributes).put("name", name)) }
            JSONObject().put("entries", array).put("next_offset", next)
        }

    suspend fun info(sessionId: String?, path: String): JSONObject = withContext(Dispatchers.IO) {
        if (path.trimEnd('/') == "/var/minis/mounts") {
            return@withContext JSONObject()
                .put("exists", true)
                .put("type", "dir")
                .put("size", 0L)
                .put("modified", 0L)
        }
        val target = resolveOptional(sessionId, path)
            ?: return@withContext JSONObject().put("exists", false)
        val attributes = SecureFileAccess.infoOrNull(target)
            ?: return@withContext JSONObject().put("exists", false)
        infoJson(attributes)
    }

    suspend fun listAll(sessionId: String?, path: String): List<JSONObject> {
        val entries = mutableListOf<JSONObject>()
        var offset = 0
        while (true) {
            val page = list(sessionId, path, 500, offset)
            val array = page.optJSONArray("entries") ?: JSONArray()
            for (index in 0 until array.length()) array.optJSONObject(index)?.let(entries::add)
            val next = page.optInt("next_offset", -1)
            if (next < 0) return entries
            offset = next
        }
    }

    suspend fun treeSize(sessionId: String?, root: String): Long {
        val rootInfo = info(sessionId, root)
        return when (rootInfo.optString("type")) {
            "file" -> rootInfo.optLong("size", 0L)
            "dir" -> {
                val directories = ArrayDeque<String>().apply { add(root) }
                var total = 0L
                while (directories.isNotEmpty()) {
                    val directory = directories.removeFirst()
                    for (entry in listAll(sessionId, directory)) {
                        when (entry.optString("type")) {
                            "file" -> total += entry.optLong("size", 0L).coerceAtLeast(0L)
                            "dir" -> directories.addLast(childPath(directory, entry.optString("name")))
                        }
                    }
                }
                total
            }
            else -> 0L
        }
    }

    suspend fun deleteChildren(sessionId: String?, root: String) {
        val entries = listAll(sessionId, root)
        for (entry in entries) delete(sessionId, childPath(root, entry.optString("name")))
    }

    private suspend fun resolveOptional(sessionId: String?, path: String): UbuntuPaths.SecureFilePath? =
        UbuntuPaths.resolveSecureForFileAccess(sessionId, path)

    private suspend fun requirePath(sessionId: String?, path: String): UbuntuPaths.SecureFilePath =
        resolveOptional(sessionId, path)
            ?: throw Failure("BAD_PARAMS", "path is outside the Minis guest namespace or unavailable: $path")

    private fun requireWritablePath(path: String) {
        if (UbuntuPaths.isExternalMountPath(path) && !UbuntuPaths.isExternalMountWritable(path)) {
            throw Failure("READ_ONLY", "mounted folder is read-only: $path")
        }
    }

    private fun infoJson(attributes: SecureFileAccess.Attributes): JSONObject = JSONObject()
        .put("exists", true)
        .put("type", attributes.type)
        .put("size", attributes.size)
        .put("modified", attributes.modified)

    private fun listMountRoots(limit: Int, offset: Int): JSONObject {
        val all = RuntimePathRegistry.mountedFoldersStore?.entries?.value.orEmpty()
            .filter { it.isActive }
            .sortedBy { it.name.lowercase() }
        val page = all.drop(offset).take(limit)
        val array = JSONArray()
        page.forEach { entry ->
            array.put(JSONObject()
                .put("name", entry.name)
                .put("exists", true)
                .put("type", "dir")
                .put("size", 0L)
                .put("modified", entry.createdAt))
        }
        val next = offset + page.size
        return JSONObject().put("entries", array).put("next_offset", if (next < all.size) next else -1)
    }

    private fun childPath(directory: String, name: String): String {
        require(name.isNotEmpty() && name != "." && name != ".." &&
            !name.contains('/') && !name.contains('\\') && !name.contains('\u0000')) {
            "invalid directory entry name"
        }
        return "${directory.trimEnd('/')}/$name"
    }
}
