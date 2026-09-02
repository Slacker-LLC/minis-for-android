package com.openminis.app.tools

import com.openminis.app.runtime.RuntimePathRegistry
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.nio.file.Files

/** Direct App-side access for SAF-backed external mounts only. */
internal object ExternalMountAccess {
    private const val PREFIX = "/var/minis/mounts/"

    data class Entry(
        val path: String,
        val name: String,
        val type: String,
        val size: Long,
        val modified: Long,
    )

    fun isPath(path: String): Boolean = path == "/var/minis/mounts" || path.startsWith(PREFIX)

    fun resolve(path: String): File? =
        if (isPath(path)) RuntimePathRegistry.resolveHostPath(path) else null

    fun read(path: String, maxBytes: Long): ByteArray {
        val file = requireFile(path)
        if (file.length() > maxBytes) throw IllegalArgumentException("file too large: $path")
        return file.readBytes()
    }

    fun write(path: String, bytes: ByteArray, append: Boolean): Long {
        val file = requireTarget(path)
        file.parentFile?.mkdirs()
        if (append) file.appendBytes(bytes) else file.writeBytes(bytes)
        return file.length()
    }

    fun info(path: String): JSONObject {
        val file = requireExisting(path)
        val symlink = Files.isSymbolicLink(file.toPath())
        return JSONObject().apply {
            put("path", path)
            put("type", when {
                symlink -> "link"
                file.isDirectory -> "dir"
                file.isFile -> "file"
                else -> "other"
            })
            put("size", if (file.isFile) file.length() else 0)
            put("modified", file.lastModified())
            put("readable", file.canRead())
            put("writable", file.canWrite())
        }
    }

    fun list(path: String, limit: Int, offset: Int): JSONObject {
        val directory = requireExisting(path)
        if (!directory.isDirectory) throw IllegalArgumentException("not a directory: $path")
        val all = directory.listFiles()?.sortedBy { it.name }.orEmpty()
        val safeOffset = offset.coerceAtLeast(0)
        val safeLimit = limit.coerceIn(1, 500)
        val page = all.drop(safeOffset).take(safeLimit)
        val entries = JSONArray()
        page.forEach { file ->
            val symlink = Files.isSymbolicLink(file.toPath())
            entries.put(JSONObject().apply {
                put("name", file.name)
                put("type", when {
                    symlink -> "link"
                    file.isDirectory -> "dir"
                    else -> "file"
                })
                put("size", if (file.isFile) file.length() else 0)
                put("modified", file.lastModified())
            })
        }
        return JSONObject().apply {
            put("path", path)
            put("entries", entries)
            put("total", all.size)
            put("offset", safeOffset)
            put("limit", safeLimit)
            if (safeOffset + page.size < all.size) put("next_offset", safeOffset + page.size)
        }
    }

    fun copy(source: String, destination: String) {
        requireBothExternal(source, destination)
        val src = requireExisting(source)
        val dst = requireTarget(destination)
        rejectSelfOrChild(src, dst, source, destination)
        dst.parentFile?.mkdirs()
        val ok = if (src.isDirectory) src.copyRecursively(dst, overwrite = true) else {
            src.copyTo(dst, overwrite = true)
            true
        }
        if (!ok) throw IllegalStateException("copy failed: $source -> $destination")
    }

    fun move(source: String, destination: String) {
        requireBothExternal(source, destination)
        val src = requireExisting(source)
        val dst = requireTarget(destination)
        rejectSelfOrChild(src, dst, source, destination)
        dst.parentFile?.mkdirs()
        if (!src.renameTo(dst)) {
            copy(source, destination)
            if (!src.deleteRecursively()) throw IllegalStateException("delete source failed: $source")
        }
    }

    fun delete(path: String) {
        val file = requireExisting(path)
        if (!file.deleteRecursively()) throw IllegalStateException("delete failed: $path")
    }

    fun walk(rootPath: String, recursive: Boolean, maxEntries: Int): List<Entry> {
        val root = requireExisting(rootPath)
        if (!root.isDirectory) throw IllegalArgumentException("not a directory: $rootPath")
        val result = mutableListOf<Entry>()
        val queue = ArrayDeque<Pair<File, String>>()
        queue.addLast(root to rootPath.trimEnd('/'))
        while (queue.isNotEmpty() && result.size < maxEntries) {
            val (directory, logicalPath) = queue.removeFirst()
            for (child in directory.listFiles()?.sortedBy { it.name }.orEmpty()) {
                if (result.size >= maxEntries) break
                val symlink = Files.isSymbolicLink(child.toPath())
                val isDirectory = child.isDirectory && !symlink
                val path = "$logicalPath/${child.name}"
                result += Entry(
                    path = path,
                    name = child.name,
                    type = when {
                        symlink -> "link"
                        isDirectory -> "dir"
                        child.isFile -> "file"
                        else -> "other"
                    },
                    size = if (child.isFile) child.length() else 0,
                    modified = child.lastModified(),
                )
                if (recursive && isDirectory) queue.addLast(child to path)
            }
            if (!recursive) break
        }
        return result
    }

    private fun requireExisting(path: String): File {
        val file = resolve(path) ?: throw IllegalArgumentException("cannot resolve external mount: $path")
        if (!file.exists()) throw IllegalArgumentException("not found: $path")
        return file
    }

    private fun requireFile(path: String): File {
        val file = requireExisting(path)
        if (!file.isFile) throw IllegalArgumentException("not a file: $path")
        return file
    }

    private fun requireTarget(path: String): File {
        val file = resolve(path) ?: throw IllegalArgumentException("cannot resolve external mount: $path")
        if (file.exists() && !file.isFile && !file.isDirectory) {
            throw IllegalArgumentException("unsupported target: $path")
        }
        return file
    }

    private fun requireBothExternal(source: String, destination: String) {
        if (!isPath(source) || !isPath(destination)) {
            throw IllegalArgumentException("cross-boundary external mount copy/move is unsupported")
        }
    }

    private fun rejectSelfOrChild(src: File, dst: File, source: String, destination: String) {
        val sourcePath = runCatching { src.canonicalPath }.getOrElse { src.absolutePath }
        val destinationPath = runCatching { dst.canonicalPath }.getOrElse { dst.absolutePath }
        if (sourcePath == destinationPath) throw IllegalArgumentException("source == destination")
        if (src.isDirectory && destinationPath.startsWith(sourcePath + File.separator)) {
            throw IllegalArgumentException("destination cannot be inside source: $source -> $destination")
        }
    }
}
