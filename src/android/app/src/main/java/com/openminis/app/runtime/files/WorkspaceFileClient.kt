package com.openminis.app.runtime.files

import com.openminis.app.runtime.RuntimePathRegistry
import com.openminis.app.runtime.ubuntu.UbuntuPaths
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.io.RandomAccessFile
import java.util.ArrayDeque
import java.util.UUID

/**
 * Compatibility name for the App-owned workspace file API.
 *
 * Despite the historical package, this implementation contains no minisd/RPC
 * transport. Canonical user data lives in app-private storage and Android file
 * tools access it directly, matching upstream's storage model. The object will
 * retain the guest-path API while using direct App-owned file I/O.
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
        require(offset >= 0L) { "offset must be non-negative" }
        require(length in 1..MAX_READ_CHUNK) { "length must be between 1 and $MAX_READ_CHUNK" }
        val file = requireFile(sessionId, path)
        val total = file.length()
        if (offset >= total) return@withContext ReadChunk(ByteArray(0), offset, total, true)
        val count = minOf(length.toLong(), total - offset).toInt()
        val bytes = ByteArray(count)
        RandomAccessFile(file, "r").use { raf ->
            raf.seek(offset)
            raf.readFully(bytes)
        }
        ReadChunk(bytes, offset, total, offset + count >= total)
    }

    suspend fun readAll(
        sessionId: String?,
        path: String,
        maxBytes: Long = MAX_FILE_BYTES,
    ): ByteArray = withContext(Dispatchers.IO) {
        val file = requireFile(sessionId, path)
        val size = file.length()
        if (size > maxBytes) throw Failure("BAD_PARAMS", "file exceeds $maxBytes bytes: $path")
        FileInputStream(file).use { it.readBytes() }
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
        val source = requireFile(sessionId, path)
        if (source.length() > maxBytes) throw Failure("BAD_PARAMS", "file exceeds $maxBytes bytes: $path")
        val parent = destination.absoluteFile.parentFile
            ?: throw Failure("INTERNAL", "destination has no parent: $destination")
        if (!parent.isDirectory && !parent.mkdirs()) {
            throw Failure("INTERNAL", "cannot create destination directory: $parent")
        }
        val temporary = File(parent, ".${destination.name}.minis-tmp-${UUID.randomUUID()}")
        var committed = false
        try {
            FileInputStream(source).use { input ->
                FileOutputStream(temporary).use { output ->
                    input.copyTo(output, MAX_WRITE_CHUNK)
                    output.fd.sync()
                }
            }
            replaceFile(temporary, destination)
            committed = true
            destination.length()
        } finally {
            if (!committed) temporary.delete()
        }
    }

    fun readToFileBlocking(
        sessionId: String?,
        path: String,
        destination: File,
        maxBytes: Long = MAX_FILE_BYTES,
    ): Long = runBlocking(Dispatchers.IO) { readToFile(sessionId, path, destination, maxBytes) }

    suspend fun writeBytes(sessionId: String?, path: String, bytes: ByteArray): Long =
        withContext(Dispatchers.IO) {
            if (bytes.size.toLong() > MAX_FILE_BYTES) {
                throw Failure("BAD_PARAMS", "file exceeds $MAX_FILE_BYTES bytes: $path")
            }
            requireWritablePath(path)
            val target = resolveRequired(sessionId, path)
            val parent = target.parentFile ?: throw Failure("BAD_PARAMS", "path has no parent: $path")
            if (!parent.isDirectory && !parent.mkdirs()) throw Failure("IO_ERROR", "cannot create $parent")
            val temporary = File(parent, ".${target.name}.minis-tmp-${UUID.randomUUID()}")
            var committed = false
            try {
                FileOutputStream(temporary).use { output ->
                    output.write(bytes)
                    output.fd.sync()
                }
                replaceFile(temporary, target)
                committed = true
                bytes.size.toLong()
            } finally {
                if (!committed) temporary.delete()
            }
        }

    suspend fun appendBytes(sessionId: String?, path: String, bytes: ByteArray): Long =
        withContext(Dispatchers.IO) {
            requireWritablePath(path)
            val target = resolveRequired(sessionId, path)
            val current = if (target.isFile) target.length() else 0L
            if (current + bytes.size > MAX_FILE_BYTES) {
                throw Failure("BAD_PARAMS", "file exceeds $MAX_FILE_BYTES bytes: $path")
            }
            target.parentFile?.let { if (!it.isDirectory && !it.mkdirs()) throw Failure("IO_ERROR", "cannot create $it") }
            FileOutputStream(target, true).use { output ->
                output.write(bytes)
                output.fd.sync()
            }
            target.length()
        }

    suspend fun writeStream(
        sessionId: String?,
        path: String,
        input: InputStream,
        maxBytes: Long = MAX_FILE_BYTES,
        onChunk: suspend (ByteArray, Long) -> Unit = { _, _ -> },
    ): Long {
        requireWritablePath(path)
        val target = resolveRequired(sessionId, path)
        val parent = target.parentFile ?: throw Failure("BAD_PARAMS", "path has no parent: $path")
        if (!parent.isDirectory && !parent.mkdirs()) throw Failure("IO_ERROR", "cannot create $parent")
        val temporary = File(parent, ".${target.name}.minis-tmp-${UUID.randomUUID()}")
        var total = 0L
        var committed = false
        try {
            withContext(Dispatchers.IO) {
                FileOutputStream(temporary).use { output ->
                    val buffer = ByteArray(MAX_WRITE_CHUNK)
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        if (count == 0) continue
                        if (total + count > maxBytes) {
                            throw Failure("BAD_PARAMS", "file exceeds $maxBytes bytes: $path")
                        }
                        output.write(buffer, 0, count)
                        total += count
                        onChunk(buffer.copyOf(count), total)
                    }
                    output.fd.sync()
                }
                replaceFile(temporary, target)
                committed = true
            }
            return total
        } finally {
            if (!committed) temporary.delete()
        }
    }

    suspend fun uniqueChildPath(sessionId: String?, directory: String, filename: String): String {
        require(filename.isNotEmpty() && !filename.contains('/') && !filename.contains('\\')) {
            "filename must be a single path component"
        }
        val used = try {
            val array = list(sessionId, directory, 500, 0).optJSONArray("entries") ?: JSONArray()
            buildSet {
                for (i in 0 until array.length()) array.optJSONObject(i)?.optString("name")?.let(::add)
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            emptySet()
        }
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
        val sourceFile = resolveRequired(sourceSessionId ?: sessionId, source)
        if (!sourceFile.exists()) throw Failure("NOT_FOUND", "source does not exist: $source")
        val destinationFile = resolveRequired(destinationSessionId ?: sessionId, destination)
        copyEntry(sourceFile, destinationFile)
        JSONObject().put("copied", true).put("type", fileType(destinationFile))
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
        val sourceFile = resolveRequired(sourceSessionId ?: sessionId, source)
        if (!sourceFile.exists()) throw Failure("NOT_FOUND", "source does not exist: $source")
        val destinationFile = resolveRequired(destinationSessionId ?: sessionId, destination)
        destinationFile.parentFile?.mkdirs()
        if (SafeFileTree.existsNoFollow(destinationFile) && !SafeFileTree.deleteRecursively(destinationFile)) {
            throw Failure("IO_ERROR", "cannot replace destination: $destination")
        }
        if (!sourceFile.renameTo(destinationFile)) {
            copyEntry(sourceFile, destinationFile)
            if (!SafeFileTree.deleteRecursively(sourceFile)) {
                throw Failure("IO_ERROR", "cannot remove source after copy: $source")
            }
        }
        JSONObject().put("moved", true).put("type", fileType(destinationFile))
    }

    suspend fun mkdir(sessionId: String?, path: String): JSONObject = withContext(Dispatchers.IO) {
        requireWritablePath(path)
        val directory = resolveRequired(sessionId, path)
        if (directory.exists() && !directory.isDirectory) throw Failure("NOT_DIR", "path is not a directory: $path")
        if (!directory.isDirectory && !directory.mkdirs()) throw Failure("IO_ERROR", "cannot create directory: $path")
        infoJson(directory)
    }

    suspend fun deleteSession(sessionId: String): JSONObject = withContext(Dispatchers.IO) {
        if (!UbuntuPaths.isSafeSessionId(sessionId)) throw Failure("BAD_PARAMS", "invalid session id")
        val target = UbuntuPaths.sessionDir(sessionId) ?: throw Failure("BAD_PARAMS", "invalid session path")
        val deleted = SafeFileTree.deleteRecursively(target)
        if (!deleted) throw Failure("IO_ERROR", "cannot delete session $sessionId")
        JSONObject().put("deleted", true)
    }

    fun deleteSessionBlocking(sessionId: String): JSONObject = runBlocking(Dispatchers.IO) { deleteSession(sessionId) }

    suspend fun delete(sessionId: String?, path: String): JSONObject = withContext(Dispatchers.IO) {
        requireWritablePath(path)
        val target = resolveRequired(sessionId, path)
        if (!SafeFileTree.existsNoFollow(target)) return@withContext JSONObject().put("deleted", false)
        if (!SafeFileTree.deleteRecursively(target)) throw Failure("IO_ERROR", "cannot delete: $path")
        JSONObject().put("deleted", true)
    }

    suspend fun list(sessionId: String?, path: String, limit: Int, offset: Int): JSONObject =
        withContext(Dispatchers.IO) {
            require(limit in 1..5000) { "limit out of range" }
            require(offset >= 0) { "offset must be non-negative" }
            if (path.trimEnd('/') == "/var/minis/mounts") {
                return@withContext listMountRoots(limit, offset)
            }
            val directory = resolveRequired(sessionId, path)
            if (!directory.isDirectory) throw Failure("NOT_DIR", "not a directory: $path")
            val children = directory.listFiles()?.sortedBy { it.name.lowercase() }
                ?: throw Failure("IO_ERROR", "cannot list directory: $path")
            val page = children.drop(offset).take(limit)
            val array = JSONArray()
            page.forEach { child -> array.put(infoJson(child).put("name", child.name)) }
            val next = offset + page.size
            JSONObject()
                .put("entries", array)
                .put("next_offset", if (next < children.size) next else -1)
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
        if (!target.exists()) return@withContext JSONObject().put("exists", false)
        infoJson(target)
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
        for (entry in entries) {
            delete(sessionId, childPath(root, entry.optString("name")))
        }
    }

    private suspend fun resolveOptional(sessionId: String?, path: String): File? =
        UbuntuPaths.resolveForFileAccess(sessionId, path)

    private suspend fun resolveRequired(sessionId: String?, path: String): File =
        resolveOptional(sessionId, path)
            ?: throw Failure("BAD_PARAMS", "path is outside the Minis guest namespace or unavailable: $path")

    private suspend fun requireFile(sessionId: String?, path: String): File {
        val file = resolveRequired(sessionId, path)
        if (!file.isFile) {
            if (!file.exists()) throw Failure("NOT_FOUND", "file does not exist: $path")
            throw Failure("NOT_FILE", "path is not a file: $path")
        }
        return file
    }

    private fun requireWritablePath(path: String) {
        if (UbuntuPaths.isExternalMountPath(path) && !UbuntuPaths.isExternalMountWritable(path)) {
            throw Failure("READ_ONLY", "mounted folder is read-only: $path")
        }
    }

    private fun infoJson(file: File): JSONObject = JSONObject()
        .put("exists", file.exists())
        .put("type", fileType(file))
        .put("size", if (file.isFile) file.length() else 0L)
        .put("modified", file.lastModified())

    private fun fileType(file: File): String = when {
        file.isDirectory -> "dir"
        file.isFile -> "file"
        else -> "other"
    }

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

    private fun copyEntry(source: File, destination: File) {
        if (source.canonicalPath == destination.canonicalPath) return
        if (SafeFileTree.isSymbolicLink(source)) {
            throw Failure("BAD_PARAMS", "refusing to recursively copy symbolic link: $source")
        }
        if (SafeFileTree.isSymbolicLink(destination)) {
            if (!SafeFileTree.deleteRecursively(destination)) {
                throw Failure("IO_ERROR", "cannot replace symbolic-link destination: $destination")
            }
        }
        destination.parentFile?.mkdirs()
        if (source.isDirectory) {
            if (SafeFileTree.existsNoFollow(destination) && !destination.isDirectory) {
                if (!SafeFileTree.deleteRecursively(destination)) {
                    throw Failure("IO_ERROR", "cannot replace $destination")
                }
            }
            if (!destination.isDirectory && !destination.mkdirs()) throw Failure("IO_ERROR", "cannot create $destination")
            source.listFiles()?.forEach { child -> copyEntry(child, File(destination, child.name)) }
                ?: throw Failure("IO_ERROR", "cannot list $source")
        } else if (source.isFile) {
            val parent = destination.parentFile
                ?: throw Failure("BAD_PARAMS", "destination has no parent: $destination")
            val temp = File(parent, ".${destination.name}.minis-tmp-${UUID.randomUUID()}")
            FileInputStream(source).use { input ->
                FileOutputStream(temp).use { output ->
                    input.copyTo(output, MAX_WRITE_CHUNK)
                    output.fd.sync()
                }
            }
            replaceFile(temp, destination)
        } else {
            throw Failure("BAD_PARAMS", "unsupported source type: $source")
        }
    }

    private fun replaceFile(temporary: File, destination: File) {
        if (SafeFileTree.existsNoFollow(destination) && !SafeFileTree.deleteRecursively(destination)) {
            temporary.delete()
            throw Failure("IO_ERROR", "cannot replace destination: $destination")
        }
        if (!temporary.renameTo(destination)) {
            try {
                FileInputStream(temporary).use { input ->
                    FileOutputStream(destination).use { output ->
                        input.copyTo(output, MAX_WRITE_CHUNK)
                        output.fd.sync()
                    }
                }
            } catch (error: Exception) {
                destination.delete()
                throw Failure("IO_ERROR", "cannot commit file: ${error.message}")
            } finally {
                temporary.delete()
            }
        }
    }

    private fun childPath(directory: String, name: String): String {
        require(name.isNotEmpty() && name != "." && name != ".." &&
            !name.contains('/') && !name.contains('\\') && !name.contains('\u0000')) {
            "invalid directory entry name"
        }
        return "${directory.trimEnd('/')}/$name"
    }
}
