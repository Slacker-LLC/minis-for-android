package com.openminis.app.data.storage

import android.content.Context
import com.openminis.app.data.model.MediaRef
import java.io.File
import java.io.InputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import com.openminis.app.runtime.files.SafeFileTree
import java.io.IOException
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes

class MediaStore internal constructor(val mediaBaseDir: File) {
    constructor(context: Context) : this(File(context.filesDir, "media"))

    fun saveMedia(
        data: ByteArray,
        mimeType: String,
        sessionId: String,
        originalFileName: String? = null,
    ): MediaRef {
        val id = UUID.randomUUID().toString()
        val dateDir = SimpleDateFormat("yyyy/MM/dd", Locale.US).format(Date())
        val originalExt = originalFileName
            ?.substringAfterLast('.', "")
            ?.takeIf { it.length in 1..10 && it.all { c -> c.isLetterOrDigit() } }
            ?.lowercase()
        val ext = originalExt ?: extensionFor(mimeType)
        val relativePath = "$dateDir/$sessionId/$id.$ext"
        val file = File(mediaBaseDir, relativePath)
        file.parentFile?.mkdirs()
        try {
            file.writeBytes(data)
        } catch (error: Exception) {
            file.delete()
            throw error
        }
        return MediaRef(
            id = id,
            relativePath = relativePath,
            mimeType = mimeType,
            originalFileName = originalFileName,
        )
    }

    /**
     * Streaming variant: copy from [source] InputStream into the same
     * dated path layout as [saveMedia], without loading the whole file
     * into memory. Used by non-image attachments (APKs, archives, large
     * binaries) where a `readBytes()` would OOM for anything over ~100MB
     * on low-RAM devices.
     *
     * Returns null on I/O failure; the caller treats this as "skip this
     * attachment" without aborting the send.
     */
    fun saveMediaStreamed(
        source: InputStream,
        mimeType: String,
        sessionId: String,
        originalFileName: String? = null,
    ): MediaRef? {
        val id = UUID.randomUUID().toString()
        val dateDir = SimpleDateFormat("yyyy/MM/dd", Locale.US).format(Date())
        val originalExt = originalFileName
            ?.substringAfterLast('.', "")
            ?.takeIf { it.length in 1..10 && it.all { c -> c.isLetterOrDigit() } }
            ?.lowercase()
        val ext = originalExt ?: extensionFor(mimeType)
        val relativePath = "$dateDir/$sessionId/$id.$ext"
        val file = File(mediaBaseDir, relativePath)
        file.parentFile?.mkdirs()
        return try {
            file.outputStream().use { out -> source.copyTo(out) }
            MediaRef(
                id = id,
                relativePath = relativePath,
                mimeType = mimeType,
                originalFileName = originalFileName,
            )
        } catch (t: Throwable) {
            runCatching { file.delete() }
            null
        }
    }

    fun loadMedia(ref: MediaRef): ByteArray? {
        val file = File(mediaBaseDir, ref.relativePath)
        return if (file.exists()) file.readBytes() else null
    }

    /** The dated media layout is yyyy/MM/dd/sessionId; never follow links into another data tree. */
    private fun sessionDirectories(sessionIds: Set<String>): List<File> {
        if (!SafeFileTree.existsNoFollow(mediaBaseDir)) return emptyList()
        val root = mediaBaseDir.toPath()
        val result = mutableListOf<File>()
        Files.walkFileTree(root, object : SimpleFileVisitor<Path>() {
            override fun preVisitDirectory(path: Path, attrs: BasicFileAttributes): FileVisitResult {
                if (root.relativize(path).nameCount == 4 && path.fileName.toString() in sessionIds) {
                    result += path.toFile()
                    return FileVisitResult.SKIP_SUBTREE
                }
                return FileVisitResult.CONTINUE
            }
            override fun visitFileFailed(path: Path, error: IOException): FileVisitResult = throw error
        })
        return result
    }

    fun sessionSizes(sessionIds: Set<String>): Map<String, Long> {
        val sizes = mutableMapOf<String, Long>()
        for (directory in sessionDirectories(sessionIds)) {
            var bytes = 0L
            Files.walkFileTree(directory.toPath(), object : SimpleFileVisitor<Path>() {
                override fun visitFile(path: Path, attrs: BasicFileAttributes): FileVisitResult {
                    if (attrs.isRegularFile) bytes += attrs.size()
                    return FileVisitResult.CONTINUE
                }
                override fun visitFileFailed(path: Path, error: IOException): FileVisitResult = throw error
            })
            sizes[directory.name] = (sizes[directory.name] ?: 0L) + bytes
        }
        return sizes
    }

    fun deleteSessionMedia(sessionId: String): Boolean {
        // Attempt every dated folder even if one removal fails.
        return sessionDirectories(setOf(sessionId)).map(SafeFileTree::deleteRecursively).all { it }
    }

    private fun extensionFor(mimeType: String): String {
        return when {
            mimeType.contains("jpeg") || mimeType.contains("jpg") -> "jpg"
            mimeType.contains("png") -> "png"
            mimeType.contains("gif") -> "gif"
            mimeType.contains("webp") -> "webp"
            mimeType.contains("pdf") -> "pdf"
            else -> "bin"
        }
    }
}
