package com.openminis.app.roleplay

/**
 * [T-eta-character-cards] Why a character card was refused, with a stable code so the UI and
 * the tools can say something specific instead of "invalid file".
 *
 * Ported from Eta `agent/roleplay/CharacterCardException.kt` (Mangi-11/Eta @ c15de97);
 * attribution in THIRD_PARTY_LICENSES.md.
 */
class CharacterCardException(
    val code: String,
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause)
