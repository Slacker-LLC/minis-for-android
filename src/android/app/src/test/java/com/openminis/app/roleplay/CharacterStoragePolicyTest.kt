package com.openminis.app.roleplay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-eta-character-cards] Ported from Eta `data/repository/CharacterRepository.kt`
 * (Mangi-11/Eta @ c15de97). The database half of storage is compile-verified only in this repo;
 * these are the decisions that can be pinned without a device.
 */
class CharacterStoragePolicyTest {

    @Test
    fun `an avatar lives under the app storage keyed by the character id`() {
        assertEquals("characters/avatars/abc-123.png", CharacterStoragePolicy.avatarRelativePath("abc-123"))
    }

    @Test
    fun `an empty or oversized avatar is refused with a reason`() {
        assertEquals("avatar is empty", CharacterStoragePolicy.avatarRejection(0))
        assertTrue(
            CharacterStoragePolicy.avatarRejection(CharacterStoragePolicy.MAX_AVATAR_BYTES + 1)!!
                .contains("larger than"),
        )
        assertNull(CharacterStoragePolicy.avatarRejection(1_024))
        assertNull(
            "the ceiling itself is allowed",
            CharacterStoragePolicy.avatarRejection(CharacterStoragePolicy.MAX_AVATAR_BYTES),
        )
    }

    @Test
    fun `an export is a png with artwork and json without it`() {
        assertEquals("png", CharacterStoragePolicy.exportExtension(hasAvatar = true))
        assertEquals("json", CharacterStoragePolicy.exportExtension(hasAvatar = false))
    }

    @Test
    fun `export file names are stripped of anything a filesystem dislikes`() {
        assertEquals("Ada Lovelace", CharacterStoragePolicy.exportFileName("  Ada\nLovelace  "))
        assertEquals("Etcpasswd", CharacterStoragePolicy.exportFileName("Etc/passwd"))
        assertEquals("ab", CharacterStoragePolicy.exportFileName("a\\b"))
        assertEquals("character", CharacterStoragePolicy.exportFileName("///"))
        assertEquals("character", CharacterStoragePolicy.exportFileName("   "))
        assertEquals(40, CharacterStoragePolicy.exportFileName("x".repeat(100)).length)
    }
}
