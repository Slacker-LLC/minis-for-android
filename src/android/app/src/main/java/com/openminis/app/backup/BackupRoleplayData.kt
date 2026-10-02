package com.openminis.app.backup

import com.openminis.app.data.db.BotEntity
import com.openminis.app.data.db.CharacterEntity
import com.openminis.app.roleplay.CharacterCardCodec
import com.openminis.app.roleplay.CharacterStoragePolicy
import kotlinx.serialization.Serializable
import java.util.Base64

/**
 * A character (card, artwork, story memory) in `data/characters.jsonl` and a bot in `data/bots.jsonl`, carried
 * inside the CHATS category: a conversation can be bound to either, and a restored chat is of little use
 * without the character it was written with. Timestamps are epoch milliseconds; these two files exist only
 * for this app, a reader that does not know them ignores them.
 */
@Serializable
data class BackupCharacterRecord(
    val id: String,
    val name: String,
    /** The card as stored (the codec's JSON); it is validated again on restore. */
    val cardJson: String,
    /** Base64 of the artwork file, when there is one within the size limit. */
    val avatarBase64: String? = null,
    /** The character's story memory (`memory.md`). */
    val memory: String? = null,
    val createdAt: Long,
    val updatedAt: Long,
)

@Serializable
data class BackupBotRecord(
    val id: String,
    val name: String,
    val systemPrompt: String? = null,
    val modelBinding: String? = null,
    val enabled: Boolean = true,
    val createdAt: Long,
    val updatedAt: Long,
)

internal object BackupRoleplayMapping {
    const val CHARACTER_FILE = "characters"
    const val CHARACTER_TYPE = "CharacterV1"
    const val BOT_FILE = "bots"
    const val BOT_TYPE = "BotV1"

    /** Same shape the character memory store accepts; anything else could name a path outside its folder. */
    private val idPattern = Regex("[A-Za-z0-9_-]{1,128}")
    const val MAX_MEMORY_CHARS = 1024 * 1024
    const val MAX_ITEMS = 500

    fun validId(id: String): Boolean = idPattern.matches(id)

    fun characterRecord(entity: CharacterEntity, avatar: ByteArray?, memory: String?): BackupCharacterRecord =
        BackupCharacterRecord(
            id = entity.id,
            name = entity.name,
            cardJson = entity.cardJson,
            avatarBase64 = avatar?.takeIf { it.isNotEmpty() && it.size <= CharacterStoragePolicy.MAX_AVATAR_BYTES }
                ?.let { Base64.getEncoder().encodeToString(it) },
            memory = memory?.takeIf { it.isNotEmpty() && it.length <= MAX_MEMORY_CHARS },
            createdAt = entity.createdAt,
            updatedAt = entity.updatedAt,
        )

    fun botRecord(bot: BotEntity): BackupBotRecord = BackupBotRecord(
        id = bot.id,
        name = bot.name,
        systemPrompt = bot.systemPrompt,
        modelBinding = bot.modelBinding,
        enabled = bot.enabled,
        createdAt = bot.createdAt,
        updatedAt = bot.updatedAt,
    )

    /** A character that is safe to store, with its decoded artwork (null when absent or not acceptable). */
    class RestorableCharacter(val entity: CharacterEntity, val avatar: ByteArray?, val memory: String?)

    /**
     * Null for a record that must not be restored: an unsafe id, no name, or a card this build cannot read.
     * A bad artwork or memory does not sink the character; it is restored without that part.
     */
    fun restorableCharacter(record: BackupCharacterRecord): RestorableCharacter? {
        if (!validId(record.id) || record.name.isBlank()) return null
        if (runCatching { CharacterCardCodec.decodeJson(record.cardJson) }.isFailure) return null
        val avatar = record.avatarBase64?.let { runCatching { Base64.getDecoder().decode(it) }.getOrNull() }
            ?.takeIf { CharacterStoragePolicy.avatarRejection(it.size) == null }
        val memory = record.memory?.takeIf { it.isNotEmpty() && it.length <= MAX_MEMORY_CHARS }
        return RestorableCharacter(
            entity = CharacterEntity(
                id = record.id,
                name = record.name,
                cardJson = record.cardJson,
                avatarPath = null, // set by the writer to where the artwork lands on this device
                createdAt = record.createdAt,
                updatedAt = record.updatedAt,
            ),
            avatar = avatar,
            memory = memory,
        )
    }

    fun restorableBot(record: BackupBotRecord): BotEntity? {
        if (!validId(record.id) || record.name.isBlank()) return null
        return BotEntity(
            id = record.id,
            name = record.name,
            systemPrompt = record.systemPrompt,
            modelBinding = record.modelBinding,
            enabled = record.enabled,
            createdAt = record.createdAt,
            updatedAt = record.updatedAt,
        )
    }

    /** Whether a record replaces what is already stored: only when it is strictly newer. */
    fun isNewer(incomingUpdatedAt: Long, localUpdatedAt: Long?): Boolean =
        localUpdatedAt == null || incomingUpdatedAt > localUpdatedAt
}
