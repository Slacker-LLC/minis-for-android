package com.openminis.app.tools

import com.openminis.app.runtime.minisd.WorkspaceFileClient
import org.json.JSONArray
import org.json.JSONObject

/** Broker-only access to the minisd-owned external mount namespace. */
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

    suspend fun read(path: String, maxBytes: Long): ByteArray {
        require(isPath(path)) { "not an external mount path: $path" }
        return WorkspaceFileClient.readAll(null, path, maxBytes)
    }

    fun readBlocking(path: String, maxBytes: Long = WorkspaceFileClient.MAX_FILE_BYTES): ByteArray {
        require(isPath(path)) { "not an external mount path: $path" }
        return WorkspaceFileClient.readAllBlocking(null, path, maxBytes)
    }

    suspend fun write(path: String, bytes: ByteArray, append: Boolean): Long {
        require(isPath(path)) { "not an external mount path: $path" }
        return if (append) {
            WorkspaceFileClient.appendBytes(null, path, bytes)
        } else {
            WorkspaceFileClient.writeBytes(null, path, bytes)
        }
    }

    suspend fun info(path: String): JSONObject {
        require(isPath(path)) { "not an external mount path: $path" }
        return WorkspaceFileClient.info(null, path)
    }

    suspend fun list(path: String, limit: Int, offset: Int): JSONObject {
        require(isPath(path)) { "not an external mount path: $path" }
        return WorkspaceFileClient.list(null, path, limit, offset)
    }

    suspend fun copy(source: String, destination: String) {
        requireBothExternal(source, destination)
        WorkspaceFileClient.copy(null, source, destination)
    }

    suspend fun move(source: String, destination: String) {
        requireBothExternal(source, destination)
        WorkspaceFileClient.move(null, source, destination)
    }

    suspend fun delete(path: String) {
        require(isPath(path)) { "not an external mount path: $path" }
        WorkspaceFileClient.delete(null, path)
    }

    suspend fun walk(rootPath: String, recursive: Boolean, maxEntries: Int): List<Entry> {
        require(isPath(rootPath)) { "not an external mount path: $rootPath" }
        val result = mutableListOf<Entry>()
        val queue = ArrayDeque<String>().apply { addLast(rootPath.trimEnd('/')) }
        val boundedMax = maxEntries.coerceAtLeast(1)
        while (queue.isNotEmpty() && result.size < boundedMax) {
            val directory = queue.removeFirst()
            val listing = list(directory, 500, 0)
            val entries = listing.optJSONArray("entries") ?: JSONArray()
            for (index in 0 until entries.length()) {
                if (result.size >= boundedMax) break
                val item = entries.optJSONObject(index) ?: continue
                val name = item.optString("name")
                if (name.isEmpty() || name == "." || name == ".." ||
                    name.contains('/') || name.contains('\\') || name.contains('\u0000')
                ) continue
                val path = "$directory/$name"
                val type = item.optString("type", "other")
                result += Entry(
                    path = path,
                    name = name,
                    type = type,
                    size = item.optLong("size", 0L),
                    modified = item.optLong("modified", 0L),
                )
                if (recursive && type == "dir") queue.addLast(path)
            }
            if (!recursive) break
        }
        return result
    }

    private fun requireBothExternal(source: String, destination: String) {
        require(isPath(source) && isPath(destination)) {
            "cross-boundary external mount copy/move is unsupported"
        }
    }
}
