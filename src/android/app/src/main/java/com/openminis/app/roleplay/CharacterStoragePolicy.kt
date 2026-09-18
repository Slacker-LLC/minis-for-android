package com.openminis.app.roleplay

/**
 * [T-eta-character-cards] Where a character avatar lives and what an export becomes.
 *
 * Ported from Eta `data/repository/CharacterRepository.kt` (Mangi-11/Eta @ c15de97); attribution
 * in THIRD_PARTY_LICENSES.md. The rules kept: an avatar is a file under this app's own storage
 * keyed by the character id (never a blob in the database), and an export is a PNG when the
 * character has artwork — so the card travels inside the image the way Tavern cards do — and plain
 * JSON when it does not.
 */
object CharacterStoragePolicy {

    /** Avatars bigger than this are refused: the bytes are a card image, not a photo library. */
    const val MAX_AVATAR_BYTES = 4 * 1024 * 1024

    private val unsafeNameChars = Regex("[\\\\/:*?\"<>|\\p{C}]")
    private val whitespaceRuns = Regex("\\s+")

    fun avatarRelativePath(id: String): String = "characters/avatars/$id.png"

    /** Null when the avatar is acceptable, otherwise why it is not. */
    fun avatarRejection(sizeBytes: Int): String? = when {
        sizeBytes <= 0 -> "avatar is empty"
        sizeBytes > MAX_AVATAR_BYTES ->
            "avatar is larger than ${MAX_AVATAR_BYTES / (1024 * 1024)} MiB"
        else -> null
    }

    fun exportExtension(hasAvatar: Boolean): String = if (hasAvatar) "png" else "json"

    fun exportFileName(name: String, fallback: String = "character", maxChars: Int = 40): String {
        val sanitized = name
            .replace(whitespaceRuns, " ")
            .replace(unsafeNameChars, "")
            .trim()
            .take(maxChars)
            .trim()
            .ifBlank { fallback }
        return sanitized
    }
}
