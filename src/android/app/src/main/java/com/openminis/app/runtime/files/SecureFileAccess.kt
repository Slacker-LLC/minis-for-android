package com.openminis.app.runtime.files

import com.openminis.app.runtime.ubuntu.UbuntuPaths
import java.io.File
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.channels.SeekableByteChannel
import java.nio.file.DirectoryStream
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.NoSuchFileException
import java.nio.file.OpenOption
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.SecureDirectoryStream
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.BasicFileAttributeView
import java.nio.file.attribute.BasicFileAttributes
import java.util.UUID

/**
 * File operations rooted at directory handles. No operation below a trusted
 * root reopens a path through the global filesystem namespace.
 */
internal object SecureFileAccess {
    private val NOFOLLOW = arrayOf(LinkOption.NOFOLLOW_LINKS)
    private val READ_OPTIONS: Set<OpenOption> = setOf(StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS)

    data class Attributes(
        val type: String,
        val size: Long,
        val modified: Long,
        val isDirectory: Boolean,
        val isRegularFile: Boolean,
        val isSymbolicLink: Boolean,
    )

    private data class Parent(
        val stream: SecureDirectoryStream<Path>,
        val leaf: Path?,
        val opened: List<DirectoryStream<Path>>,
    )

    fun readChunk(
        path: UbuntuPaths.SecureFilePath,
        offset: Long,
        length: Int,
        maxLength: Int,
    ): WorkspaceFileClient.ReadChunk {
        require(offset >= 0L) { "offset must be non-negative" }
        require(length in 1..maxLength) { "length must be between 1 and $maxLength" }
        return withParent(path) { parent ->
            val attributes = requireRegularFile(parent, path)
            if (offset >= attributes.size) {
                return@withParent WorkspaceFileClient.ReadChunk(ByteArray(0), offset, attributes.size, true)
            }
            val count = minOf(length.toLong(), attributes.size - offset).toInt()
            val bytes = ByteArray(count)
            openRead(parent).use { channel ->
                channel.position(offset)
                readFully(channel, bytes)
            }
            WorkspaceFileClient.ReadChunk(bytes, offset, attributes.size, offset + count >= attributes.size)
        }
    }

    fun readAll(path: UbuntuPaths.SecureFilePath, maxBytes: Long): ByteArray = withParent(path) { parent ->
        val attributes = requireRegularFile(parent, path)
        if (attributes.size > maxBytes) {
            throw WorkspaceFileClient.Failure("BAD_PARAMS", "file exceeds $maxBytes bytes")
        }
        if (attributes.size > Int.MAX_VALUE) {
            throw WorkspaceFileClient.Failure("BAD_PARAMS", "file is too large")
        }
        val bytes = ByteArray(attributes.size.toInt())
        openRead(parent).use { readFully(it, bytes) }
        bytes
    }

    fun readToFile(path: UbuntuPaths.SecureFilePath, destination: File, maxBytes: Long): Long =
        withParent(path) { parent ->
            val attributes = requireRegularFile(parent, path)
            if (attributes.size > maxBytes) {
                throw WorkspaceFileClient.Failure("BAD_PARAMS", "file exceeds $maxBytes bytes")
            }
            val targetParent = destination.absoluteFile.parentFile
                ?: throw WorkspaceFileClient.Failure("INTERNAL", "destination has no parent: $destination")
            if (!targetParent.isDirectory && !targetParent.mkdirs()) {
                throw WorkspaceFileClient.Failure("INTERNAL", "cannot create destination directory: $targetParent")
            }
            val temporary = File(targetParent, ".${destination.name}.minis-tmp-${UUID.randomUUID()}")
            var committed = false
            try {
                openRead(parent).use { input ->
                    java.io.FileOutputStream(temporary).use { output ->
                        copyChannel(input, output.channel, maxBytes)
                        output.fd.sync()
                    }
                }
                if (SafeFileTree.existsNoFollow(destination) && !SafeFileTree.deleteRecursively(destination)) {
                    throw WorkspaceFileClient.Failure("IO_ERROR", "cannot replace destination: $destination")
                }
                if (!temporary.renameTo(destination)) {
                    throw WorkspaceFileClient.Failure("IO_ERROR", "cannot commit destination: $destination")
                }
                committed = true
                destination.length()
            } finally {
                if (!committed) temporary.delete()
            }
        }

    fun writeBytes(path: UbuntuPaths.SecureFilePath, bytes: ByteArray, maxBytes: Long): Long {
        if (bytes.size.toLong() > maxBytes) {
            throw WorkspaceFileClient.Failure("BAD_PARAMS", "file exceeds $maxBytes bytes")
        }
        return writeAtomic(path) { channel ->
            writeFully(channel, bytes)
            bytes.size.toLong()
        }
    }

    fun appendBytes(path: UbuntuPaths.SecureFilePath, bytes: ByteArray, maxBytes: Long): Long =
        withParentAfterEnsure(path) { parent ->
            val leaf = parent.leaf ?: throw WorkspaceFileClient.Failure("BAD_PARAMS", "path is a directory")
            val current = attributesOrNull(parent, path)?.also { attributes ->
                if (attributes.isSymbolicLink || !attributes.isRegularFile) {
                    throw WorkspaceFileClient.Failure("NOT_FILE", "path is not a file")
                }
            }?.size ?: 0L
            if (current + bytes.size > maxBytes) {
                throw WorkspaceFileClient.Failure("BAD_PARAMS", "file exceeds $maxBytes bytes")
            }
            val options = setOf(
                StandardOpenOption.CREATE,
                StandardOpenOption.WRITE,
                StandardOpenOption.APPEND,
                LinkOption.NOFOLLOW_LINKS,
            )
            parent.stream.newByteChannel(leaf, options).use { channel -> writeFully(channel, bytes) }
            current + bytes.size
        }

    suspend fun writeStream(
        path: UbuntuPaths.SecureFilePath,
        input: InputStream,
        maxBytes: Long,
        onChunk: suspend (ByteArray, Long) -> Unit,
    ): Long {
        return withParentSuspendAfterEnsure(path) { parent ->
            val leaf = parent.leaf ?: throw WorkspaceFileClient.Failure("BAD_PARAMS", "path is a directory")
            val temporary = PathsFor.temp(leaf)
            var total = 0L
            var committed = false
            try {
                parent.stream.newByteChannel(
                    temporary,
                    setOf(StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS),
                ).use { output ->
                    val buffer = ByteArray(WorkspaceFileClient.MAX_WRITE_CHUNK)
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        if (count == 0) continue
                        if (total + count > maxBytes) {
                            throw WorkspaceFileClient.Failure("BAD_PARAMS", "file exceeds $maxBytes bytes")
                        }
                        writeFully(output, buffer, count)
                        total += count
                        onChunk(buffer.copyOf(count), total)
                    }
                }
                deleteEntryIfPresent(parent.stream, leaf)
                parent.stream.move(temporary, parent.stream, leaf)
                committed = true
                total
            } finally {
                if (!committed) deleteEntryIfPresent(parent.stream, temporary)
            }
        }
    }

    fun copy(source: UbuntuPaths.SecureFilePath, destination: UbuntuPaths.SecureFilePath): String {
        if (samePath(source, destination)) return info(source).type
        if (isDescendant(destination, source)) {
            throw WorkspaceFileClient.Failure("BAD_PARAMS", "cannot copy a directory into itself")
        }
        val attributes = attributesOrNull(source)
            ?: throw WorkspaceFileClient.Failure("NOT_FOUND", "source does not exist")
        if (attributes.isSymbolicLink) {
            throw WorkspaceFileClient.Failure("BAD_PARAMS", "refusing to copy symbolic link")
        }
        if (attributes.isDirectory) {
            copyDirectory(source, destination)
        } else if (attributes.isRegularFile) {
            copyFile(source, destination, attributes.size)
        } else {
            throw WorkspaceFileClient.Failure("BAD_PARAMS", "unsupported source type")
        }
        return info(destination).type
    }

    fun move(source: UbuntuPaths.SecureFilePath, destination: UbuntuPaths.SecureFilePath): String {
        if (samePath(source, destination)) return info(source).type
        if (isDescendant(destination, source)) {
            throw WorkspaceFileClient.Failure("BAD_PARAMS", "cannot move a directory into itself")
        }
        val sourceAttributes = attributesOrNull(source)
            ?: throw WorkspaceFileClient.Failure("NOT_FOUND", "source does not exist")
        if (sourceAttributes.isSymbolicLink) {
            throw WorkspaceFileClient.Failure("BAD_PARAMS", "refusing to move symbolic link")
        }
        ensureParent(destination)
        val moved = runCatching {
            withParent(source) { sourceParent ->
                withParent(destination) { destinationParent ->
                    val sourceLeaf = sourceParent.leaf
                        ?: throw WorkspaceFileClient.Failure("BAD_PARAMS", "cannot move a root")
                    val destinationLeaf = destinationParent.leaf
                        ?: throw WorkspaceFileClient.Failure("BAD_PARAMS", "cannot replace a root")
                    deleteEntryIfPresent(destinationParent.stream, destinationLeaf)
                    sourceParent.stream.move(sourceLeaf, destinationParent.stream, destinationLeaf)
                }
            }
            true
        }.getOrDefault(false)
        if (!moved) {
            copy(source, destination)
            delete(source)
        }
        return info(destination).type
    }

    fun mkdir(path: UbuntuPaths.SecureFilePath): Attributes {
        if (path.components.isEmpty()) return info(path)
        ensureDirectories(path)
        return info(path)
    }

    fun delete(path: UbuntuPaths.SecureFilePath): Boolean {
        if (path.components.isEmpty()) {
            throw WorkspaceFileClient.Failure("BAD_PARAMS", "refusing to delete a trusted root")
        }
        return withParent(path) { parent ->
            val leaf = parent.leaf ?: return@withParent false
            if (attributesOrNull(parent, path) == null) return@withParent false
            deleteEntryIfPresent(parent.stream, leaf)
            true
        }
    }

    fun info(path: UbuntuPaths.SecureFilePath): Attributes = withParent(path) { parent ->
        attributesOrNull(parent, path)
            ?: throw WorkspaceFileClient.Failure("NOT_FOUND", "path does not exist")
    }

    fun infoOrNull(path: UbuntuPaths.SecureFilePath): Attributes? = runCatching { info(path) }
        .getOrNull()

    /** Probe a directory without reopening a name through the global path. */
    fun probeWritable(root: File): Boolean {
        val leaf = Paths.get(".minis-probe-${UUID.randomUUID()}")
        return try {
            val directory = openRoot(root)
            try {
                directory.newByteChannel(
                    leaf,
                    setOf(StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS),
                ).use { channel ->
                    writeFully(channel, byteArrayOf(0))
                }
                directory.deleteFile(leaf)
                true
            } finally {
                directory.close()
            }
        } catch (_: Exception) {
            false
        }
    }

    fun list(path: UbuntuPaths.SecureFilePath, limit: Int, offset: Int): Pair<List<Pair<String, Attributes>>, Int> =
        withDirectory(path) { directory ->
            val names = directory.stream.iterator().asSequence()
                .map { it.fileName.toString() }
                .sortedWith(String.CASE_INSENSITIVE_ORDER)
                .toList()
            val page = names.drop(offset).take(limit).map { name ->
                name to attributesAt(directory.stream, Paths.get(name))
            }
            val next = offset + page.size
            page to if (next < names.size) next else -1
        }

    private fun copyDirectory(source: UbuntuPaths.SecureFilePath, destination: UbuntuPaths.SecureFilePath) {
        val destinationAttributes = attributesOrNull(destination)
        if (destinationAttributes != null && !destinationAttributes.isDirectory) delete(destination)
        ensureDirectories(destination)
        val children = withDirectory(source) { directory ->
            directory.stream.iterator().asSequence().map { it.fileName.toString() }.toList()
        }
        children.forEach { name -> copy(source.child(name), destination.child(name)) }
    }

    private fun copyFile(
        source: UbuntuPaths.SecureFilePath,
        destination: UbuntuPaths.SecureFilePath,
        size: Long,
    ) {
        if (size > WorkspaceFileClient.MAX_FILE_BYTES) {
            throw WorkspaceFileClient.Failure("BAD_PARAMS", "file exceeds ${WorkspaceFileClient.MAX_FILE_BYTES} bytes")
        }
        ensureParent(destination)
        withParent(source) { sourceParent ->
            withParent(destination) { destinationParent ->
                val sourceLeaf = sourceParent.leaf
                    ?: throw WorkspaceFileClient.Failure("BAD_PARAMS", "source is a directory")
                val destinationLeaf = destinationParent.leaf
                    ?: throw WorkspaceFileClient.Failure("BAD_PARAMS", "destination is a directory")
                val temporary = PathsFor.temp(destinationLeaf)
                var committed = false
                try {
                    sourceParent.stream.newByteChannel(sourceLeaf, READ_OPTIONS).use { input ->
                        destinationParent.stream.newByteChannel(
                            temporary,
                            setOf(StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS),
                        ).use { output -> copyChannel(input, output, size) }
                    }
                    deleteEntryIfPresent(destinationParent.stream, destinationLeaf)
                    destinationParent.stream.move(temporary, destinationParent.stream, destinationLeaf)
                    committed = true
                } finally {
                    if (!committed) deleteEntryIfPresent(destinationParent.stream, temporary)
                }
            }
        }
    }

    private fun writeAtomic(path: UbuntuPaths.SecureFilePath, write: (SeekableByteChannel) -> Long): Long {
        return withParentAfterEnsure(path) { parent ->
            val leaf = parent.leaf ?: throw WorkspaceFileClient.Failure("BAD_PARAMS", "path is a directory")
            val temporary = PathsFor.temp(leaf)
            var committed = false
            try {
                val result = parent.stream.newByteChannel(
                    temporary,
                    setOf(StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS),
                ).use(write)
                deleteEntryIfPresent(parent.stream, leaf)
                parent.stream.move(temporary, parent.stream, leaf)
                committed = true
                result
            } finally {
                if (!committed) deleteEntryIfPresent(parent.stream, temporary)
            }
        }
    }

    private fun ensureParent(path: UbuntuPaths.SecureFilePath) {
        if (path.components.isEmpty()) {
            throw WorkspaceFileClient.Failure("BAD_PARAMS", "path has no file name")
        }
        ensureDirectories(path.copy(components = path.components.dropLast(1)))
    }

    private fun ensureDirectories(path: UbuntuPaths.SecureFilePath) {
        if (path.components.isEmpty()) return
        val result = SecureFileOps.ensureDirectories(path.root.path, path.components)
        if (result != 0) {
            throw WorkspaceFileClient.Failure("IO_ERROR", "cannot create directory path: errno=${-result}")
        }
    }

    private fun withParentAfterEnsure(
        path: UbuntuPaths.SecureFilePath,
        block: (Parent) -> Long,
    ): Long {
        ensureParent(path)
        return withParent(path, block)
    }

    private suspend fun withParentSuspendAfterEnsure(
        path: UbuntuPaths.SecureFilePath,
        block: suspend (Parent) -> Long,
    ): Long {
        ensureParent(path)
        return withParentSuspend(path, block)
    }

    private fun requireRegularFile(parent: Parent, path: UbuntuPaths.SecureFilePath): Attributes {
        val attributes = attributesOrNull(parent, path)
            ?: throw WorkspaceFileClient.Failure("NOT_FOUND", "file does not exist")
        if (attributes.isSymbolicLink) throw WorkspaceFileClient.Failure("NOT_FILE", "refusing symbolic link")
        if (!attributes.isRegularFile) throw WorkspaceFileClient.Failure("NOT_FILE", "path is not a file")
        return attributes
    }

    private fun openRead(parent: Parent): SeekableByteChannel {
        val leaf = parent.leaf ?: throw WorkspaceFileClient.Failure("NOT_FILE", "path is a directory")
        return parent.stream.newByteChannel(leaf, READ_OPTIONS)
    }

    private fun attributesOrNull(path: UbuntuPaths.SecureFilePath): Attributes? = runCatching { info(path) }
        .getOrNull()

    private fun attributesOrNull(parent: Parent, path: UbuntuPaths.SecureFilePath): Attributes? =
        runCatching { readAttributes(parent, path) }.getOrNull()

    private fun readAttributes(parent: Parent, path: UbuntuPaths.SecureFilePath): Attributes {
        if (parent.leaf == null) {
            return Files.readAttributes(path.root.toPath(), BasicFileAttributes::class.java, *NOFOLLOW)
                .toSecureAttributes()
        }
        return attributesAt(parent.stream, parent.leaf)
    }

    private fun attributesAt(parent: SecureDirectoryStream<Path>, entry: Path): Attributes {
        val raw = parent.getFileAttributeView(
            entry,
            BasicFileAttributeView::class.java,
            *NOFOLLOW,
        )?.readAttributes() ?: throw NoSuchFileException(entry.toString())
        return raw.toSecureAttributes()
    }

    private fun BasicFileAttributes.toSecureAttributes(): Attributes = Attributes(
        type = when {
            isDirectory -> "dir"
            isRegularFile -> "file"
            else -> "other"
        },
        size = if (isRegularFile) size() else 0L,
        modified = lastModifiedTime().toMillis(),
        isDirectory = isDirectory,
        isRegularFile = isRegularFile,
        isSymbolicLink = isSymbolicLink,
    )

    private fun <T> withParent(
        path: UbuntuPaths.SecureFilePath,
        block: (Parent) -> T,
    ): T {
        val opened = ArrayList<DirectoryStream<Path>>(path.components.size + 1)
        try {
            var current = openRoot(path.root)
            opened += current
            if (path.components.isEmpty()) return block(Parent(current, null, opened))
            path.components.dropLast(1).forEach { component ->
                current = current.newDirectoryStream(Paths.get(component), *NOFOLLOW)
                opened += current
            }
            return block(Parent(current, Paths.get(path.components.last()), opened))
        } finally {
            opened.asReversed().forEach { runCatching { it.close() } }
        }
    }

    private suspend fun <T> withParentSuspend(
        path: UbuntuPaths.SecureFilePath,
        block: suspend (Parent) -> T,
    ): T {
        val opened = ArrayList<DirectoryStream<Path>>(path.components.size + 1)
        try {
            var current = openRoot(path.root)
            opened += current
            if (path.components.isEmpty()) return block(Parent(current, null, opened))
            path.components.dropLast(1).forEach { component ->
                current = current.newDirectoryStream(Paths.get(component), *NOFOLLOW)
                opened += current
            }
            return block(Parent(current, Paths.get(path.components.last()), opened))
        } finally {
            opened.asReversed().forEach { runCatching { it.close() } }
        }
    }

    private fun <T> withDirectory(
        path: UbuntuPaths.SecureFilePath,
        block: (Parent) -> T,
    ): T = withParent(path) { parent ->
        val attributes = readAttributes(parent, path)
        if (attributes.isSymbolicLink || !attributes.isDirectory) {
            throw WorkspaceFileClient.Failure("NOT_DIR", "path is not a directory")
        }
        if (parent.leaf == null) return@withParent block(parent)
        val child = parent.stream.newDirectoryStream(parent.leaf, *NOFOLLOW)
        val secureChild = child as? SecureDirectoryStream<Path>
            ?: run { child.close(); throw WorkspaceFileClient.Failure("IO_ERROR", "secure directory handles unavailable") }
        try {
            block(Parent(secureChild, null, listOf(secureChild)))
        } finally {
            secureChild.close()
        }
    }

    private fun openRoot(root: File): SecureDirectoryStream<Path> {
        val path = root.toPath()
        val attributes = Files.readAttributes(path, BasicFileAttributes::class.java, *NOFOLLOW)
        if (attributes.isSymbolicLink || !attributes.isDirectory) {
            throw WorkspaceFileClient.Failure("NOT_DIR", "secure root is not a directory: $root")
        }
        val stream = Files.newDirectoryStream(path)
        return stream as? SecureDirectoryStream<Path>
            ?: run { stream.close(); throw WorkspaceFileClient.Failure("IO_ERROR", "secure directory handles unavailable") }
    }

    private fun deleteEntryIfPresent(parent: SecureDirectoryStream<Path>, entry: Path) {
        val attributes = try {
            parent.getFileAttributeView(entry, BasicFileAttributeView::class.java, *NOFOLLOW)?.readAttributes()
        } catch (_: NoSuchFileException) {
            return
        } catch (error: Exception) {
            throw WorkspaceFileClient.Failure("IO_ERROR", "cannot inspect entry ${entry.fileName}: ${error.message}")
        } ?: return
        if (attributes.isSymbolicLink || !attributes.isDirectory) {
            parent.deleteFile(entry)
            return
        }
        val child = parent.newDirectoryStream(entry, *NOFOLLOW)
        try {
            child.iterator().forEachRemaining { nested -> deleteEntryIfPresent(child, nested.fileName) }
        } finally {
            child.close()
        }
        parent.deleteDirectory(entry)
    }

    private fun readFully(channel: SeekableByteChannel, output: ByteArray) {
        var offset = 0
        while (offset < output.size) {
            val count = channel.read(ByteBuffer.wrap(output, offset, output.size - offset))
            if (count < 0) break
            if (count == 0) continue
            offset += count
        }
    }

    private fun writeFully(channel: SeekableByteChannel, bytes: ByteArray, length: Int = bytes.size) {
        var offset = 0
        while (offset < length) {
            val count = channel.write(ByteBuffer.wrap(bytes, offset, length - offset))
            if (count <= 0) throw WorkspaceFileClient.Failure("IO_ERROR", "short file write")
            offset += count
        }
    }

    private fun copyChannel(input: SeekableByteChannel, output: SeekableByteChannel, maxBytes: Long) {
        val buffer = ByteBuffer.allocate(WorkspaceFileClient.MAX_WRITE_CHUNK)
        var total = 0L
        while (true) {
            buffer.clear()
            val count = input.read(buffer)
            if (count < 0) break
            if (count == 0) continue
            total += count
            if (total > maxBytes) throw WorkspaceFileClient.Failure("BAD_PARAMS", "file exceeds $maxBytes bytes")
            buffer.flip()
            while (buffer.hasRemaining()) output.write(buffer)
        }
    }

    private fun samePath(left: UbuntuPaths.SecureFilePath, right: UbuntuPaths.SecureFilePath): Boolean =
        left.root.path == right.root.path && left.components == right.components

    private fun isDescendant(candidate: UbuntuPaths.SecureFilePath, ancestor: UbuntuPaths.SecureFilePath): Boolean =
        candidate.root.path == ancestor.root.path &&
            candidate.components.size > ancestor.components.size &&
            candidate.components.subList(0, ancestor.components.size) == ancestor.components

    private object PathsFor {
        fun temp(leaf: Path): Path = Paths.get(".${leaf.fileName}.minis-tmp-${UUID.randomUUID()}")
    }
}
