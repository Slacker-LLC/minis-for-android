package com.openminis.app.roleplay

import org.json.JSONObject

/**
 * [T-eta-character-cards] The character a session talks to, and the card as it looked when the
 * session was bound.
 *
 * Ported from Eta `agent/roleplay/CharacterCard.kt` (`RoleplayBinding`) (Mangi-11/Eta @ c15de97);
 * attribution in THIRD_PARTY_LICENSES.md. The snapshot is what makes a session survive its
 * character being edited or deleted: the live card wins while it exists, and a session whose card is
 * gone keeps the words it was actually written with instead of silently turning into a plain chat.
 */
data class CharacterBinding(
    val characterId: String,
    val cardSnapshotJson: String,
    val characterName: String,
    val userName: String = DEFAULT_USER_NAME,
    val userDescription: String = "",
) {

    fun toJson(): String = JSONObject()
        .put("characterId", characterId)
        .put("cardSnapshotJson", cardSnapshotJson)
        .put("characterName", characterName)
        .put("userName", userName)
        .put("userDescription", userDescription)
        .toString()

    companion object {
        const val DEFAULT_USER_NAME = "用户"

        fun fromCard(
            card: CharacterCard,
            userName: String = DEFAULT_USER_NAME,
            userDescription: String = "",
        ): CharacterBinding = CharacterBinding(
            characterId = "",
            cardSnapshotJson = card.raw.toString(),
            characterName = card.name,
            userName = userName.ifBlank { DEFAULT_USER_NAME },
            userDescription = userDescription,
        )

        /** Null when there is no binding or the stored payload is not one this app wrote. */
        fun fromJson(raw: String?): CharacterBinding? {
            val source = raw?.takeIf { it.isNotBlank() } ?: return null
            val obj = try {
                JSONObject(source)
            } catch (_: Exception) {
                return null
            }
            val snapshot = obj.optString("cardSnapshotJson")
            val name = obj.optString("characterName")
            if (snapshot.isBlank() || name.isBlank()) return null
            return CharacterBinding(
                characterId = obj.optString("characterId"),
                cardSnapshotJson = snapshot,
                characterName = name,
                userName = obj.optString("userName").ifBlank { DEFAULT_USER_NAME },
                userDescription = obj.optString("userDescription"),
            )
        }
    }
}
