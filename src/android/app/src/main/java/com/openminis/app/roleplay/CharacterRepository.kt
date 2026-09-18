package com.openminis.app.roleplay

import android.content.Context
import com.openminis.app.MinisApp
import com.openminis.app.data.db.AppDatabase
import com.openminis.app.data.db.CharacterDao
import com.openminis.app.data.db.CharacterEntity
import com.openminis.app.logging.AppLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

/** [T-eta-character-cards] One stored character: the card, its artwork and its timestamps. */
data class CharacterProfile(
    val id: String,
    val card: CharacterCard,
    val avatarPath: String? = null,
    val createdAt: Long,
    val updatedAt: Long,
)

/**
 * [T-eta-character-cards] Storing character cards.
 *
 * Ported from Eta `data/repository/CharacterRepository.kt` (Mangi-11/Eta @ c15de97); attribution
 * in THIRD_PARTY_LICENSES.md. Kept from Eta: the card is validated through the codec before it is
 * stored (a stored card is always a card this app can read back), the avatar is a file under the
 * app's own storage keyed by the character id, an import keeps the original PNG so a later export
 * can rewrite the card chunks inside it, and a delete removes the artwork too.
 *
 * Not ported: Eta's one-time default-character seeding and its plot-memory directory, which belong
 * to features this app does not have yet.
 */
object CharacterRepository {

    private const val TAG = "CharacterRepository"

    @Volatile
    private var context: Context? = null

    fun initialize(context: Context) {
        this.context = context.applicationContext
    }

    private fun appContext(): Context = checkNotNull(context) { "CharacterRepository is not initialized" }

    private fun database(): AppDatabase = (appContext() as MinisApp).database

    private fun dao(): CharacterDao = database().characterDao()

    private fun chatDao() = database().chatDao()

    fun avatarFile(id: String): File =
        File(appContext().filesDir, CharacterStoragePolicy.avatarRelativePath(id))

    suspend fun list(): List<CharacterProfile> = withContext(Dispatchers.IO) {
        dao().characters().mapNotNull { entity -> entity.toProfileOrNull() }
    }

    fun observe(): Flow<List<CharacterProfile>> =
        dao().observeCharacters().map { rows -> rows.mapNotNull { entity -> entity.toProfileOrNull() } }

    suspend fun get(id: String): CharacterProfile? = withContext(Dispatchers.IO) {
        dao().character(id)?.toProfileOrNull()
    }

    suspend fun create(card: CharacterCard, avatarBytes: ByteArray? = null): CharacterProfile =
        withContext(Dispatchers.IO) {
            val validated = validated(card)
            val now = System.currentTimeMillis()
            val id = UUID.randomUUID().toString()
            val avatar = avatarBytes?.let { writeAvatar(id, it) }
            val profile = CharacterProfile(id, validated, avatar, createdAt = now, updatedAt = now)
            try {
                dao().upsertCharacter(profile.toEntity())
            } catch (failure: Throwable) {
                avatar?.let { runCatching { File(it).delete() } }
                throw failure
            }
            profile
        }

    suspend fun save(profile: CharacterProfile, avatarBytes: ByteArray? = null): CharacterProfile =
        withContext(Dispatchers.IO) {
            val existing = dao().character(profile.id)
                ?: throw IllegalArgumentException("character no longer exists")
            val validated = validated(profile.card)
            val avatar = avatarBytes?.let { writeAvatar(profile.id, it) } ?: profile.avatarPath
            val saved = profile.copy(
                card = validated,
                avatarPath = avatar,
                createdAt = existing.createdAt,
                updatedAt = System.currentTimeMillis(),
            )
            dao().upsertCharacter(saved.toEntity())
            saved
        }

    suspend fun delete(id: String) = withContext(Dispatchers.IO) {
        dao().character(id) ?: throw IllegalArgumentException("character no longer exists")
        dao().deleteCharacter(id)
        runCatching { avatarFile(id).delete() }
        Unit
    }

    /**
     * A PNG keeps its artwork and gains the card chunks; a JSON body is a card without artwork.
     * Both paths end in [create], so an import is validated the same way a hand-made card is.
     */
    suspend fun import(bytes: ByteArray): CharacterProfile {
        if (CharacterCardPng.isPng(bytes)) {
            val card = CharacterCardPng.read(bytes)
            return create(card, avatarBytes = bytes)
        }
        return create(CharacterCardCodec.decodeBytes(bytes))
    }

    /**
     * The bytes a share sheet or a file picker gets: the character image with a fresh card inside
     * it when there is artwork, otherwise the card as JSON.
     */
    suspend fun export(profile: CharacterProfile): ByteArray = withContext(Dispatchers.IO) {
        val avatar = profile.avatarPath?.let { File(it) }
        if (avatar != null && avatar.isFile) {
            val bytes = avatar.readBytes()
            if (CharacterCardPng.isPng(bytes)) return@withContext CharacterCardPng.write(bytes, profile.card)
        }
        CharacterCardCodec.exportView(profile.card, 2).toByteArray(Charsets.UTF_8)
    }

    /** A stored card is always one the codec accepts; anything else is refused before it is written. */
    internal fun validated(card: CharacterCard): CharacterCard =
        CharacterCardCodec.decodeJson(card.raw.toString())

    /**
     * Binds a character to a session. The card snapshot travels with the binding, so a session
     * whose character is later edited or deleted keeps the words it was written with.
     */
    suspend fun bindToSession(
        sessionId: String,
        profile: CharacterProfile,
        userName: String = CharacterBinding.DEFAULT_USER_NAME,
        userDescription: String = "",
    ): CharacterBinding = withContext(Dispatchers.IO) {
        val binding = CharacterBinding.fromCard(profile.card, userName, userDescription).copy(
            characterId = profile.id,
        )
        chatDao().setRoleplayBinding(sessionId, binding.toJson(), System.currentTimeMillis())
        binding
    }

    suspend fun unbindSession(sessionId: String) = withContext(Dispatchers.IO) {
        chatDao().setRoleplayBinding(sessionId, null, System.currentTimeMillis())
        Unit
    }

    suspend fun bindingFor(sessionId: String): CharacterBinding? = withContext(Dispatchers.IO) {
        CharacterBinding.fromJson(chatDao().roleplayBinding(sessionId))
    }

    /**
     * What a turn should use: the live card while the character still exists, otherwise the
     * snapshot the binding carries. Eta resolves it in that order, and so does this port.
     */
    suspend fun resolveForSession(sessionId: String): Pair<CharacterBinding, CharacterCard>? =
        withContext(Dispatchers.IO) {
            val binding = CharacterBinding.fromJson(chatDao().roleplayBinding(sessionId)) ?: return@withContext null
            val live = dao().character(binding.characterId)?.toProfileOrNull()?.card
            val card = live ?: runCatching { CharacterCardCodec.decodeJson(binding.cardSnapshotJson) }.getOrNull()
            card?.let { binding to it }
        }

    private fun writeAvatar(id: String, bytes: ByteArray): String {
        CharacterStoragePolicy.avatarRejection(bytes.size)?.let { reason ->
            throw CharacterCardException("CARD_AVATAR_REJECTED", reason)
        }
        val target = avatarFile(id)
        target.parentFile?.mkdirs()
        target.writeBytes(bytes)
        return target.absolutePath
    }

    private fun CharacterProfile.toEntity(): CharacterEntity = CharacterEntity(
        id = id,
        name = card.name,
        cardJson = card.raw.toString(),
        avatarPath = avatarPath,
        createdAt = createdAt,
        updatedAt = updatedAt,
    )

    /** A row whose card cannot be parsed is skipped rather than crashing the list. */
    private fun CharacterEntity.toProfileOrNull(): CharacterProfile? = try {
        CharacterProfile(
            id = id,
            card = CharacterCardCodec.decodeJson(cardJson),
            avatarPath = avatarPath,
            createdAt = createdAt,
            updatedAt = updatedAt,
        )
    } catch (failure: Throwable) {
        AppLogger.warning(TAG, "skipping unreadable character row $id: ${failure.message}")
        null
    }
}
