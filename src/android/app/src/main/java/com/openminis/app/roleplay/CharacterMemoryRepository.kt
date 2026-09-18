package com.openminis.app.roleplay

import android.content.Context
import java.io.File

/**
 * [T-eta-character-cards] Where a character's story memory lives: one file per character, under the
 * app's own storage, never the real memory notes.
 *
 * Ported from Eta `data/repository/CharacterMemoryRepository.kt` (Mangi-11/Eta @ c15de97);
 * attribution in THIRD_PARTY_LICENSES.md. Kept from Eta: the character id is validated before it
 * becomes a path, the memory is a plain Markdown file a user can read, and deleting a character
 * deletes its story memory with it. The rules for reading and writing that file are
 * [CharacterMemoryDocument], which is pure; this object only moves bytes.
 */
object CharacterMemoryRepository {

    private val idPattern = Regex("[A-Za-z0-9_-]{1,128}")

    fun directory(context: Context, characterId: String): File {
        require(idPattern.matches(characterId)) { "角色记忆标识无效" }
        return File(File(context.applicationContext.filesDir, "roleplay"), characterId)
    }

    fun file(context: Context, characterId: String): File = File(directory(context, characterId), "memory.md")

    fun snapshot(context: Context, characterId: String): CharacterMemorySnapshot =
        CharacterMemoryDocument.snapshot(readText(context, characterId))

    fun read(
        context: Context,
        characterId: String,
        query: String? = null,
        startLine: Int = 1,
        maxChars: Int = CharacterMemoryDocument.DEFAULT_MAX_READ_CHARS,
    ): CharacterMemoryRead = CharacterMemoryDocument.read(readText(context, characterId), query, startLine, maxChars)

    fun mutate(
        context: Context,
        characterId: String,
        mutation: CharacterMemoryMutation,
    ): CharacterMemoryWrite {
        val result = CharacterMemoryDocument.mutate(readText(context, characterId), mutation)
        if (result is CharacterMemoryWrite.Success) writeText(context, characterId, result.snapshot.content)
        return result
    }

    /** Deleting a character takes its story memory with it; the conversations keep their own copies. */
    fun discard(context: Context, characterId: String) {
        runCatching { directory(context, characterId).deleteRecursively() }
    }

    private fun readText(context: Context, characterId: String): String {
        val file = file(context, characterId)
        if (!file.isFile) return ""
        return runCatching { file.readText() }.getOrDefault("")
    }

    /**
     * Written through a temporary file and a rename, so a process death mid-write leaves the previous
     * memory rather than half of a new one. The content was already validated by the document rules.
     */
    private fun writeText(context: Context, characterId: String, content: String) {
        val target = file(context, characterId)
        target.parentFile?.mkdirs()
        val temporary = File(target.parentFile, "memory.md.tmp")
        temporary.writeText(content)
        if (!temporary.renameTo(target)) {
            target.writeText(content)
            temporary.delete()
        }
    }
}
