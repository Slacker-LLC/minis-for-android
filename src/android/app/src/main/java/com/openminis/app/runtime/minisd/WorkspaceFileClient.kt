package com.openminis.app.runtime.minisd

import android.util.Base64
import com.openminis.app.runtime.ubuntu.UbuntuRuntime
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.util.ArrayDeque
import java.util.UUID

/**
 * App-facing adapter for the fixed persistent workspace RPC. The App never
 * opens `/data/adb/minis` itself; minisd validates the guest path and performs
 * the operation from its privileged boundary.
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
    ): ReadChunk {
        require(length in 1..MAX_READ_CHUNK) { "length must be between 1 and $MAX_READ_CHUNK" }
        ensureBrokerReady()
        return readChunkUnchecked(sessionId, path, offset, length)
    }

    private suspend fun readChunkUnchecked(
        sessionId: String?,
        path: String,
        offset: Long,
        length: Int = MAX_READ_CHUNK,
    ): ReadChunk {
        val result = requireResult(
            UbuntuRuntime.client.workspaceFile(
                operation = "read",
                sessionId = sessionId,
                path = path,
                offset = offset,
                length = length,
            ),
        )
        val encoded = result.optString("data_base64", "")
        val bytes = try {
            Base64.decode(encoded, Base64.DEFAULT)
        } catch (error: IllegalArgumentException) {
            throw Failure("INTERNAL", "invalid base64 response: ${error.message}")
        }
        return ReadChunk(
            bytes = bytes,
            offset = result.optLong("offset", offset),
            totalBytes = result.optLong("total_bytes", bytes.size.toLong()),
            eof = result.optBoolean("eof", true),
        )
    }

    suspend fun readAll(
        sessionId: String?,
        path: String,
        maxBytes: Long = MAX_FILE_BYTES,
    ): ByteArray {
        ensureBrokerReady()
        val out = ByteArrayOutputStream()
        var offset = 0L
        while (true) {
            val chunk = readChunkUnchecked(sessionId, path, offset)
            if (out.size().toLong() + chunk.bytes.size > maxBytes) {
                throw Failure("BAD_PARAMS", "file exceeds $maxBytes bytes: $path")
            }
            if (chunk.offset != offset) {
                throw Failure(
                    "INTERNAL",
                    "read returned offset ${chunk.offset}, expected $offset: $path",
                )
            }
            out.write(chunk.bytes)
            val nextOffset = offset + chunk.bytes.size
            if (chunk.eof) return out.toByteArray()
            if (chunk.bytes.isEmpty() || nextOffset <= offset) {
                throw Failure("INTERNAL", "read made no progress: $path")
            }
            offset = nextOffset
        }
    }

    /** For Android callbacks such as WebView resource interception. */
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
    ): Long {
        ensureBrokerReady()
        val parent = destination.parentFile
        if (parent != null && !parent.isDirectory && !parent.mkdirs()) {
            throw Failure("INTERNAL", "cannot create preview cache directory: $parent")
        }
        val temporary = File(parent ?: destination.absoluteFile.parentFile, ".${destination.name}.minis-tmp-${UUID.randomUUID()}")
        var committed = false
        var offset = 0L
        try {
            FileOutputStream(temporary).use { output ->
                while (true) {
                    val chunk = readChunkUnchecked(sessionId, path, offset)
                    if (offset + chunk.bytes.size > maxBytes) {
                        throw Failure("BAD_PARAMS", "file exceeds $maxBytes bytes: $path")
                    }
                    if (chunk.offset != offset) {
                        throw Failure(
                            "INTERNAL",
                            "read returned offset ${chunk.offset}, expected $offset: $path",
                        )
                    }
                    output.write(chunk.bytes)
                    offset += chunk.bytes.size
                    if (chunk.eof) break
                    if (chunk.bytes.isEmpty() || offset <= chunk.offset) {
                        throw Failure("INTERNAL", "read made no progress: $path")
                    }
                }
                output.fd.sync()
            }
            if (destination.exists() && !destination.delete()) {
                throw Failure("INTERNAL", "cannot replace preview cache file: $destination")
            }
            if (!temporary.renameTo(destination)) {
                throw Failure("INTERNAL", "cannot commit preview cache file: $destination")
            }
            committed = true
            return offset
        } finally {
            if (!committed) temporary.delete()
        }
    }

    fun readToFileBlocking(
        sessionId: String?,
        path: String,
        destination: File,
        maxBytes: Long = MAX_FILE_BYTES,
    ): Long = runBlocking(Dispatchers.IO) {
        readToFile(sessionId, path, destination, maxBytes)
    }

    suspend fun writeBytes(
        sessionId: String?,
        path: String,
        bytes: ByteArray,
    ): Long {
        ensureBrokerReady()
        val temporary = "$path.minis-tmp-${UUID.randomUUID()}"
        var committed = false
        try {
            writeChunks(sessionId, temporary, bytes)
            requireResult(
                UbuntuRuntime.client.workspaceFile(
                    operation = "move",
                    sessionId = sessionId,
                    source = temporary,
                    destination = path,
                ),
            )
            committed = true
            return bytes.size.toLong()
        } finally {
            if (!committed) {
                runCatching {
                    UbuntuRuntime.client.workspaceFile(
                        operation = "delete",
                        sessionId = sessionId,
                        path = temporary,
                    )
                }
            }
        }
    }

    suspend fun appendBytes(sessionId: String?, path: String, bytes: ByteArray): Long {
        ensureBrokerReady()
        if (bytes.isEmpty()) {
            return requireResult(
                UbuntuRuntime.client.workspaceFile(
                    operation = "append",
                    sessionId = sessionId,
                    path = path,
                    dataBase64 = "",
                    createDirs = true,
                ),
            ).optLong("size", 0)
        }
        var size = 0L
        var offset = 0
        while (offset < bytes.size) {
            val end = (offset + MAX_WRITE_CHUNK).coerceAtMost(bytes.size)
            val chunk = bytes.copyOfRange(offset, end)
            size = requireResult(
                UbuntuRuntime.client.workspaceFile(
                    operation = "append",
                    sessionId = sessionId,
                    path = path,
                    dataBase64 = Base64.encodeToString(chunk, Base64.NO_WRAP),
                    createDirs = offset == 0,
                ),
            ).optLong("size", size)
            offset = end
        }
        return size
    }

    /**
     * Stream into a temporary guest file, then commit with a single move. The
     * callback receives each committed chunk so callers can maintain a small
     * local cache without loading the whole file into memory.
     */
    suspend fun writeStream(
        sessionId: String?,
        path: String,
        input: InputStream,
        maxBytes: Long = MAX_FILE_BYTES,
        onChunk: suspend (ByteArray, Long) -> Unit = { _, _ -> },
    ): Long {
        ensureBrokerReady()
        val temporary = "$path.minis-tmp-${UUID.randomUUID()}"
        var committed = false
        var total = 0L
        var wrote = false
        val buffer = ByteArray(MAX_WRITE_CHUNK)
        try {
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                if (count == 0) continue
                if (total + count > maxBytes) {
                    throw Failure("BAD_PARAMS", "file exceeds $maxBytes bytes: $path")
                }
                val chunk = buffer.copyOf(count)
                requireResult(
                    UbuntuRuntime.client.workspaceFile(
                        operation = if (wrote) "append" else "write",
                        sessionId = sessionId,
                        path = temporary,
                        dataBase64 = Base64.encodeToString(chunk, Base64.NO_WRAP),
                        createDirs = !wrote,
                    ),
                )
                wrote = true
                total += count
                onChunk(chunk, total)
            }
            if (!wrote) {
                requireResult(
                    UbuntuRuntime.client.workspaceFile(
                        operation = "write",
                        sessionId = sessionId,
                        path = temporary,
                        dataBase64 = "",
                        createDirs = true,
                    ),
                )
            }
            requireResult(
                UbuntuRuntime.client.workspaceFile(
                        operation = "move",
                        sessionId = sessionId,
                        source = temporary,
                        destination = path,
                ),
            )
            committed = true
            return total
        } finally {
            if (!committed) {
                runCatching {
                    UbuntuRuntime.client.workspaceFile(
                        operation = "delete",
                        sessionId = sessionId,
                        path = temporary,
                    )
                }
            }
        }
    }

    /** Pick a non-conflicting child name using the broker's directory view. */
    suspend fun uniqueChildPath(sessionId: String?, directory: String, filename: String): String {
        require(filename.isNotEmpty() && !filename.contains('/') && !filename.contains('\\')) {
            "filename must be a single path component"
        }
        val used = runCatching {
            val listing = list(sessionId, directory, 500, 0)
            val entries = listing.optJSONArray("entries") ?: return@runCatching emptySet<String>()
            (0 until entries.length()).mapNotNull { entries.optJSONObject(it)?.optString("name") }.toSet()
        }.getOrElse { error ->
            if (error is Failure && error.code == "RUNTIME_UNAVAILABLE") emptySet() else throw error
        }
        if (filename !in used) return "$directory/$filename"
        val dot = filename.lastIndexOf('.')
        val base = if (dot > 0) filename.substring(0, dot) else filename
        val ext = if (dot > 0) filename.substring(dot) else ""
        var index = 1
        while (true) {
            val candidate = "$base-$index$ext"
            if (candidate !in used) return "$directory/$candidate"
            index++
        }
    }

    suspend fun copy(
        sessionId: String?,
        source: String,
        destination: String,
        sourceSessionId: String? = null,
        destinationSessionId: String? = null,
    ): JSONObject {
        ensureBrokerReady()
        return requireResult(
            UbuntuRuntime.client.workspaceFile(
                operation = "copy",
                sessionId = sessionId,
                sourceSessionId = sourceSessionId,
                destinationSessionId = destinationSessionId,
                source = source,
                destination = destination,
            ),
        )
    }

    suspend fun move(
        sessionId: String?,
        source: String,
        destination: String,
        sourceSessionId: String? = null,
        destinationSessionId: String? = null,
    ): JSONObject {
        ensureBrokerReady()
        return requireResult(
            UbuntuRuntime.client.workspaceFile(
                operation = "move",
                sessionId = sessionId,
                sourceSessionId = sourceSessionId,
                destinationSessionId = destinationSessionId,
                source = source,
                destination = destination,
            ),
        )
    }

    suspend fun mkdir(sessionId: String?, path: String): JSONObject {
        ensureBrokerReady()
        return requireResult(
            UbuntuRuntime.client.workspaceFile(
                operation = "mkdir",
                sessionId = sessionId,
                path = path,
            ),
        )
    }

    suspend fun migrationStatus(ensureBroker: Boolean = true): JSONObject {
        if (ensureBroker) ensureBrokerReady()
        return requireResult(
            UbuntuRuntime.client.workspaceFile(operation = "migration_status"),
        )
    }

    suspend fun migrationInfo(
        target: String,
        path: String,
        sessionId: String? = null,
        ensureBroker: Boolean = true,
    ): JSONObject {
        if (ensureBroker) ensureBrokerReady()
        return requireResult(
            UbuntuRuntime.client.workspaceFile(
                operation = "migration_info",
                sessionId = sessionId,
                target = target,
                path = path,
            ),
        )
    }

    suspend fun migrationMkdir(
        target: String,
        path: String,
        sessionId: String? = null,
        ensureBroker: Boolean = true,
    ): JSONObject {
        if (ensureBroker) ensureBrokerReady()
        return requireResult(
            UbuntuRuntime.client.workspaceFile(
                operation = "migration_mkdir",
                sessionId = sessionId,
                target = target,
                path = path,
            ),
        )
    }

    suspend fun migrationWrite(
        target: String,
        path: String,
        dataBase64: String,
        append: Boolean,
        sessionId: String? = null,
        ensureBroker: Boolean = true,
    ): JSONObject {
        if (ensureBroker) ensureBrokerReady()
        return requireResult(
            UbuntuRuntime.client.workspaceFile(
                operation = if (append) "migration_append" else "migration_write",
                sessionId = sessionId,
                target = target,
                path = path,
                dataBase64 = dataBase64,
            ),
        )
    }

    suspend fun migrationComplete(ensureBroker: Boolean = true): JSONObject {
        if (ensureBroker) ensureBrokerReady()
        return requireResult(
            UbuntuRuntime.client.workspaceFile(operation = "migration_complete"),
        )
    }

    suspend fun deleteSession(sessionId: String): JSONObject {
        ensureBrokerReady()
        return requireResult(
            UbuntuRuntime.client.workspaceFile(
                operation = "delete_session",
                sessionId = sessionId,
            ),
        )
    }

    fun deleteSessionBlocking(sessionId: String): JSONObject = runBlocking(Dispatchers.IO) {
        deleteSession(sessionId)
    }

    suspend fun delete(sessionId: String?, path: String): JSONObject {
        ensureBrokerReady()
        return requireResult(
            UbuntuRuntime.client.workspaceFile(
                operation = "delete",
                sessionId = sessionId,
                path = path,
            ),
        )
    }

    suspend fun list(sessionId: String?, path: String, limit: Int, offset: Int): JSONObject {
        ensureBrokerReady()
        return requireResult(
            UbuntuRuntime.client.workspaceFile(
                operation = "list",
                sessionId = sessionId,
                path = path,
                limit = limit,
                offset = offset.toLong(),
            ),
        )
    }

    suspend fun info(sessionId: String?, path: String): JSONObject {
        ensureBrokerReady()
        return requireResult(
            UbuntuRuntime.client.workspaceFile(
                operation = "info",
                sessionId = sessionId,
                path = path,
            ),
        )
    }

    suspend fun listAll(sessionId: String?, path: String): List<JSONObject> {
        val entries = mutableListOf<JSONObject>()
        var offset = 0
        while (true) {
            val page = list(sessionId, path, 500, offset)
            val array = page.optJSONArray("entries")
            if (array != null) {
                for (index in 0 until array.length()) {
                    array.optJSONObject(index)?.let(entries::add)
                }
            }
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
        val directories = ArrayDeque<String>().apply { add(root) }
        val children = mutableListOf<Pair<String, String>>()
        while (directories.isNotEmpty()) {
            val directory = directories.removeFirst()
            for (entry in listAll(sessionId, directory)) {
                val path = childPath(directory, entry.optString("name"))
                if (entry.optString("type") == "dir") {
                    directories.addLast(path)
                    children += path to "dir"
                } else {
                    children += path to entry.optString("type")
                }
            }
        }
        children.asReversed().forEach { (path, _) -> delete(sessionId, path) }
    }

    private suspend fun writeChunks(sessionId: String?, path: String, bytes: ByteArray) {
        if (bytes.isEmpty()) {
            requireResult(
                UbuntuRuntime.client.workspaceFile(
                    operation = "write",
                    sessionId = sessionId,
                    path = path,
                    dataBase64 = "",
                    createDirs = true,
                ),
            )
            return
        }
        var offset = 0
        while (offset < bytes.size) {
            val end = (offset + MAX_WRITE_CHUNK).coerceAtMost(bytes.size)
            val chunk = bytes.copyOfRange(offset, end)
            requireResult(
                UbuntuRuntime.client.workspaceFile(
                    operation = if (offset == 0) "write" else "append",
                    sessionId = sessionId,
                    path = path,
                    dataBase64 = Base64.encodeToString(chunk, Base64.NO_WRAP),
                    createDirs = offset == 0,
                ),
            )
            offset = end
        }
    }

    private suspend fun ensureBrokerReady() {
        val response = UbuntuRuntime.ensureBrokerReady()
        if (!response.ok) {
            throw Failure(
                response.error?.code ?: "RUNTIME_UNAVAILABLE",
                response.error?.detail ?: "minisd broker unavailable",
            )
        }
    }

    private fun childPath(directory: String, name: String): String {
        require(name.isNotEmpty() && name != "." && name != ".." &&
            !name.contains('/') && !name.contains('\u0000')) {
            "invalid directory entry name"
        }
        return "${directory.trimEnd('/')}/$name"
    }

    private fun requireResult(response: MinisdResponse): JSONObject {
        if (!response.ok) {
            val error = response.error
            throw Failure(
                error?.code ?: "RUNTIME_UNAVAILABLE",
                error?.detail?.ifBlank { "workspace file operation failed" }
                    ?: "workspace file operation failed",
            )
        }
        return response.result ?: throw Failure("INTERNAL", "workspace file response has no result")
    }
}
