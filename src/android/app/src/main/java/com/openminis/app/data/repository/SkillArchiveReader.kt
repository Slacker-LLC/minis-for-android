package com.openminis.app.data.repository

import java.io.InputStream
import java.util.zip.ZipInputStream

/** Raised when an archive is refused. The message names the rule that failed
 *  and is safe to log: it never contains file contents. */
internal class SkillArchiveException(message: String) : Exception(message)

internal data class SkillArchiveFile(val relativePath: String, val bytes: ByteArray)

internal class SkillArchive(
    val skillMarkdown: String,
    val files: List<SkillArchiveFile>,
    val skippedEntries: Int,
)

/**
 * [T-android-skill-archive-bounds] Bounded reader for imported skill archives.
 *
 * An imported ZIP is untrusted input. The previous reader pulled every entry
 * into memory with no ceiling at all, so one archive could exhaust the heap, and
 * it silently dropped entries it did not like instead of refusing the archive.
 *
 * Rules, all fail-closed:
 *
 * - the compressed stream, one entry, and the sum of entries each have a budget;
 * - entry count and path length/component count are bounded;
 * - names must be relative, must not contain `..`, a backslash, a NUL or an
 *   empty component, and must not repeat;
 * - `SKILL.md` is required at the archive root or exactly one directory deep.
 *
 * Hidden entries (any component starting with `.`) and `__MACOSX` metadata are
 * skipped and counted rather than treated as violations: they carry no payload
 * and legitimate archives ship them.
 */
internal object SkillArchiveReader {

    /** Budget for the compressed archive as it is read off the stream. */
    const val MAX_ARCHIVE_BYTES = 32L * 1024 * 1024

    /** Budget for one decompressed entry. */
    const val MAX_ENTRY_BYTES = 4L * 1024 * 1024

    /** Budget for all decompressed entries together. */
    const val MAX_TOTAL_BYTES = 16L * 1024 * 1024

    const val MAX_ENTRIES = 512
    const val MAX_PATH_CHARS = 240
    const val MAX_PATH_COMPONENTS = 12

    private const val CHUNK_BYTES = 8 * 1024

    fun read(input: InputStream): SkillArchive {
        val counting = CountingInputStream(input)
        val files = mutableListOf<SkillArchiveFile>()
        val seen = mutableSetOf<String>()
        var skillMarkdown: String? = null
        var prefix: String? = null
        var entries = 0
        var skipped = 0
        var totalBytes = 0L

        ZipInputStream(counting).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                entries += 1
                if (entries > MAX_ENTRIES) {
                    throw SkillArchiveException("archive has more than $MAX_ENTRIES entries")
                }
                val name = entry.name
                if (entry.isDirectory) {
                    zip.closeEntry()
                    continue
                }
                // Safety is checked before anything is skipped: `..` also
                // starts with a dot, and a hidden-entry shortcut must never
                // become a way past the path rules.
                validatePath(name)
                if (isSkippable(name)) {
                    skipped += 1
                    zip.closeEntry()
                    continue
                }
                if (!seen.add(name)) {
                    throw SkillArchiveException("archive repeats the entry '$name'")
                }
                val bytes = zip.readBounded()
                totalBytes += bytes.size
                if (totalBytes > MAX_TOTAL_BYTES) {
                    throw SkillArchiveException("archive expands past $MAX_TOTAL_BYTES bytes")
                }
                val discoveredPrefix = skillPrefixFor(name)
                if (discoveredPrefix != null) {
                    if (skillMarkdown != null) {
                        throw SkillArchiveException("archive contains more than one SKILL.md")
                    }
                    prefix = discoveredPrefix
                    skillMarkdown = bytes.toString(Charsets.UTF_8)
                } else {
                    files += SkillArchiveFile(name, bytes)
                }
                zip.closeEntry()
            }
        }

        val markdown = skillMarkdown
            ?: throw SkillArchiveException("archive has no SKILL.md at the root or one level deep")
        if (markdown.isBlank()) throw SkillArchiveException("SKILL.md is empty")

        // Entries that arrived before SKILL.md still need prefix stripping, so
        // the relative paths are resolved once the prefix is known.
        val archivePrefix = prefix.orEmpty()
        val relative = files.map { file ->
            val path = if (archivePrefix.isNotEmpty() && file.relativePath.startsWith(archivePrefix)) {
                file.relativePath.drop(archivePrefix.length)
            } else {
                file.relativePath
            }
            if (path.isEmpty()) {
                throw SkillArchiveException("archive entry '${file.relativePath}' resolves to no path")
            }
            validatePath(path)
            SkillArchiveFile(path, file.bytes)
        }
        return SkillArchive(markdown, relative, skipped)
    }

    /** Returns the directory prefix ("" at the root) when [name] is the SKILL.md
     *  the archive should be keyed on, or null when it is an ordinary file. */
    private fun skillPrefixFor(name: String): String? = when {
        name == "SKILL.md" -> ""
        name.endsWith("/SKILL.md") && name.count { it == '/' } == 1 -> name.dropLast("SKILL.md".length)
        else -> null
    }

    private fun isSkippable(name: String): Boolean =
        name.split('/').any { it.startsWith(".") } || name.startsWith("__MACOSX/")

    private fun validatePath(name: String) {
        if (name.isEmpty()) throw SkillArchiveException("archive has an entry with an empty name")
        if (name.startsWith("/") || name.contains(':')) {
            throw SkillArchiveException("archive entry '$name' is not a relative path")
        }
        if (name.contains('\\') || name.contains('\u0000')) {
            throw SkillArchiveException("archive entry '$name' contains an illegal character")
        }
        if (name.length > MAX_PATH_CHARS) {
            throw SkillArchiveException("archive entry '$name' is longer than $MAX_PATH_CHARS characters")
        }
        val components = name.split('/')
        if (components.size > MAX_PATH_COMPONENTS) {
            throw SkillArchiveException("archive entry '$name' nests deeper than $MAX_PATH_COMPONENTS levels")
        }
        for (component in components) {
            if (component.isEmpty() || component == "." || component == "..") {
                throw SkillArchiveException("archive entry '$name' has an unsafe path component")
            }
        }
    }

    private fun ZipInputStream.readBounded(): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        val chunk = ByteArray(CHUNK_BYTES)
        var entryBytes = 0L
        while (true) {
            val read = read(chunk)
            if (read <= 0) break
            entryBytes += read
            if (entryBytes > MAX_ENTRY_BYTES) {
                throw SkillArchiveException("archive entry expands past $MAX_ENTRY_BYTES bytes")
            }
            out.write(chunk, 0, read)
        }
        return out.toByteArray()
    }

    /** Counts bytes pulled from the source so a highly compressible archive
     *  cannot sidestep the budgets by being tiny on disk. */
    private class CountingInputStream(
        private val delegate: InputStream,
    ) : InputStream() {
        private var count = 0L

        override fun read(): Int {
            val value = delegate.read()
            if (value != -1) account(1)
            return value
        }

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            val read = delegate.read(buffer, offset, length)
            if (read > 0) account(read)
            return read
        }

        override fun available(): Int = delegate.available()

        override fun close() = delegate.close()

        private fun account(bytes: Int) {
            count += bytes
            if (count > MAX_ARCHIVE_BYTES) {
                throw SkillArchiveException("archive is larger than $MAX_ARCHIVE_BYTES compressed bytes")
            }
        }
    }
}
