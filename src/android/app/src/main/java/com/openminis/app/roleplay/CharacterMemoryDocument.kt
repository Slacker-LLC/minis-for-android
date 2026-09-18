package com.openminis.app.roleplay

import java.security.MessageDigest

/** [T-eta-character-cards] A character's story memory as it stands, with the revision that identifies it. */
data class CharacterMemorySnapshot(
    val content: String = "",
    val revision: String = "",
    val byteSize: Int = 0,
    val lineCount: Int = 0,
)

/** [T-eta-character-cards] What a bounded read returned. */
data class CharacterMemoryRead(
    val snapshot: CharacterMemorySnapshot,
    val content: String,
    val startLine: Int?,
    val endLine: Int?,
    val matchedLines: Int,
    val hasMore: Boolean,
)

sealed class CharacterMemoryMutation {
    abstract val revision: String

    /** Adds a block at the end; the common case for "this happened". */
    data class Append(override val revision: String, val content: String) : CharacterMemoryMutation()

    /** Replaces a 1-based inclusive line range; the way a stale fact is corrected. */
    data class ReplaceRange(
        override val revision: String,
        val startLine: Int,
        val endLine: Int,
        val content: String,
    ) : CharacterMemoryMutation()

    data class Clear(override val revision: String) : CharacterMemoryMutation()
}

sealed class CharacterMemoryWrite {
    data class Success(val snapshot: CharacterMemorySnapshot) : CharacterMemoryWrite()

    /** The text moved on since the caller read it; nothing was written. */
    data class Conflict(val snapshot: CharacterMemorySnapshot) : CharacterMemoryWrite()
}

/**
 * [T-eta-character-cards] The rules of a character's story memory: bounded, revisioned, and written
 * one change at a time.
 *
 * Ported from Eta `data/repository/CharacterMemoryRepository.kt` and
 * `agent/roleplay/CharacterMemoryTools.kt` (Mangi-11/Eta @ c15de97); attribution in
 * THIRD_PARTY_LICENSES.md. Eta keeps story memory per character, separate from the real MEMORY.md,
 * identifies a state by a SHA-256 revision, and refuses a write whose revision is stale instead of
 * merging blindly — a roleplay memory that silently overwrites the previous scene is worse than one
 * that asks to look again.
 *
 * The rules live here, apart from any file, so they can be tested: the file handling that surrounds
 * them is [CharacterMemoryRepository]
 */
object CharacterMemoryDocument {

    /** Story memory is a notebook, not a corpus: past this it must be edited, not appended to. */
    const val MAX_CHARS = 64_000

    const val DEFAULT_MAX_READ_CHARS = 12_000

    const val MIN_MAX_READ_CHARS = 256

    fun snapshot(content: String): CharacterMemorySnapshot = CharacterMemorySnapshot(
        content = content,
        revision = revisionOf(content),
        byteSize = content.toByteArray(Charsets.UTF_8).size,
        lineCount = lineCount(content),
    )

    fun revisionOf(content: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(content.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { byte -> "%02x".format(byte) }
    }

    fun clampMaxReadChars(raw: Int?): Int = (raw ?: DEFAULT_MAX_READ_CHARS).coerceIn(MIN_MAX_READ_CHARS, MAX_CHARS)

    /**
     * A bounded read: with a query, the lines that match it (and how many there were); without one, a
     * page from [startLine]. The returned `hasMore` is honest about a cut-off page, and `startLine`
     * and `endLine` are null for a query read so a caller cannot mistake matched lines for a range.
     */
    fun read(
        content: String,
        query: String? = null,
        startLine: Int = 1,
        maxChars: Int = DEFAULT_MAX_READ_CHARS,
    ): CharacterMemoryRead {
        val snapshot = snapshot(content)
        val budget = clampMaxReadChars(maxChars)
        val needle = query?.trim()?.takeIf { it.isNotEmpty() }
        if (needle != null) {
            val matched = mutableListOf<String>()
            var matchedCount = 0
            var used = 0
            content.lineSequence().forEachIndexed { index, line ->
                if (!line.contains(needle, ignoreCase = true)) return@forEachIndexed
                matchedCount += 1
                val rendered = "${index + 1}: $line"
                if (used + rendered.length + 1 <= budget) {
                    matched.add(rendered)
                    used += rendered.length + 1
                }
            }
            return CharacterMemoryRead(
                snapshot = snapshot,
                content = matched.joinToString("\n"),
                startLine = null,
                endLine = null,
                matchedLines = matchedCount,
                hasMore = matched.size < matchedCount,
            )
        }
        val lines = content.split("\n")
        val from = (startLine.coerceAtLeast(1) - 1).coerceAtMost(lines.size)
        val page = mutableListOf<String>()
        var used = 0
        var index = from
        while (index < lines.size) {
            val line = lines[index]
            if (used + line.length + 1 > budget && page.isNotEmpty()) break
            page.add(line)
            used += line.length + 1
            index += 1
        }
        return CharacterMemoryRead(
            snapshot = snapshot,
            content = page.joinToString("\n"),
            startLine = if (page.isEmpty()) null else from + 1,
            endLine = if (page.isEmpty()) null else index,
            matchedLines = page.size,
            hasMore = index < lines.size,
        )
    }

    fun mutate(content: String, mutation: CharacterMemoryMutation): CharacterMemoryWrite {
        val current = snapshot(content)
        if (mutation.revision != current.revision) return CharacterMemoryWrite.Conflict(current)
        val next = when (mutation) {
            is CharacterMemoryMutation.Clear -> ""
            is CharacterMemoryMutation.Append -> append(content, mutation.content)
            is CharacterMemoryMutation.ReplaceRange -> replaceRange(content, mutation)
        }
        if (next.length > MAX_CHARS) {
            throw CharacterCardException(
                "MEMORY_TOO_LARGE",
                "剧情记忆超过 $MAX_CHARS 字符上限，请先精简或替换旧内容",
            )
        }
        return CharacterMemoryWrite.Success(snapshot(next))
    }

    private fun append(content: String, addition: String): String {
        val block = addition.trimEnd()
        if (block.isEmpty()) return content
        if (content.isBlank()) return block
        return content.trimEnd() + "\n\n" + block + "\n"
    }

    private fun replaceRange(content: String, mutation: CharacterMemoryMutation.ReplaceRange): String {
        val lines = content.split("\n").toMutableList()
        val start = mutation.startLine
        val end = mutation.endLine
        require(start >= 1 && end >= start) { "行号范围无效" }
        require(end <= lines.size) { "行号范围超出记忆内容" }
        val replacement = mutation.content.split("\n")
        lines.subList(start - 1, end).clear()
        lines.addAll(start - 1, replacement)
        return lines.joinToString("\n")
    }

    // A file ending with a newline is not "one more line": counting the trailing empty entry
    // would make every append look like it grew the memory by two lines.
    private fun lineCount(content: String): Int =
        if (content.isEmpty()) 0 else content.trimEnd('\n').split("\n").size
}
