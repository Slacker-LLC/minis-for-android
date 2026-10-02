package com.openminis.app.backup

import com.openminis.app.data.db.BotEntity
import com.openminis.app.data.db.CharacterEntity
import com.openminis.app.roleplay.CharacterStoragePolicy
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64

class BackupRoleplayMappingTest {

    private val card = JSONObject()
        .put("spec", "chara_card_v2").put("spec_version", "2.0")
        .put("data", JSONObject().put("name", "Ada")).toString()

    private fun record(
        id: String = "char-1",
        name: String = "Ada",
        cardJson: String = card,
        avatar: String? = null,
        memory: String? = null,
    ) = BackupCharacterRecord(id, name, cardJson, avatar, memory, createdAt = 10, updatedAt = 20)

    @Test
    fun `a character round trips with its artwork and memory`() {
        val avatar = ByteArray(2000) { it.toByte() }
        val entity = CharacterEntity("char-1", "Ada", card, "/old/install/path.png", 10, 20)
        val written = BackupRoleplayMapping.characterRecord(entity, avatar, "she likes tea")
        val back = BackupRoleplayMapping.restorableCharacter(written)!!
        assertEquals("char-1", back.entity.id)
        assertEquals(20L, back.entity.updatedAt)
        assertNull("the old install's path is not carried", back.entity.avatarPath)
        assertTrue(avatar.contentEquals(back.avatar))
        assertEquals("she likes tea", back.memory)
    }

    @Test
    fun `an id that could name a path outside the folder is refused`() {
        for (id in listOf("../x", "a/b", "", "a b", "x".repeat(129), "..", "a\\b")) {
            assertFalse(id, BackupRoleplayMapping.validId(id))
            assertNull(id, BackupRoleplayMapping.restorableCharacter(record(id = id)))
            assertNull(id, BackupRoleplayMapping.restorableBot(BackupBotRecord(id, "Bot", null, null, true, 1, 2)))
        }
        assertTrue(BackupRoleplayMapping.validId("3f2c-AB_9"))
    }

    @Test
    fun `a card this build cannot read is refused`() {
        assertNull(BackupRoleplayMapping.restorableCharacter(record(cardJson = "not json")))
        assertNull(BackupRoleplayMapping.restorableCharacter(record(cardJson = """{"spec":"chara_card_v9","data":{}}""")))
        assertNull(BackupRoleplayMapping.restorableCharacter(record(name = "  ")))
    }

    @Test
    fun `bad artwork or memory costs only that part`() {
        val bad = BackupRoleplayMapping.restorableCharacter(record(avatar = "!!!not base64!!!", memory = "m"))
        assertNotNull(bad)
        assertNull(bad!!.avatar)
        assertEquals("m", bad.memory)

        val tooBig = Base64.getEncoder().encodeToString(ByteArray(CharacterStoragePolicy.MAX_AVATAR_BYTES + 1))
        assertNull(BackupRoleplayMapping.restorableCharacter(record(avatar = tooBig))!!.avatar)

        val hugeMemory = "x".repeat(BackupRoleplayMapping.MAX_MEMORY_CHARS + 1)
        assertNull(BackupRoleplayMapping.restorableCharacter(record(memory = hugeMemory))!!.memory)
    }

    @Test
    fun `an artwork over the limit is not written into the package`() {
        val entity = CharacterEntity("c", "Ada", card, null, 1, 2)
        val record = BackupRoleplayMapping.characterRecord(entity, ByteArray(CharacterStoragePolicy.MAX_AVATAR_BYTES + 1), null)
        assertNull(record.avatarBase64)
    }

    @Test
    fun `only a strictly newer record replaces what is on the device`() {
        assertTrue(BackupRoleplayMapping.isNewer(5, null))
        assertTrue(BackupRoleplayMapping.isNewer(6, 5))
        assertFalse(BackupRoleplayMapping.isNewer(5, 5))
        assertFalse(BackupRoleplayMapping.isNewer(4, 5))
    }

    @Test
    fun `a bot round trips and a nameless one is refused`() {
        val bot = BotEntity("bot-1", "Helper", "be brief", """{"type":"entry","entryId":"e1"}""", false, 1, 2)
        assertEquals(bot, BackupRoleplayMapping.restorableBot(BackupRoleplayMapping.botRecord(bot)))
        assertNull(BackupRoleplayMapping.restorableBot(BackupBotRecord("bot-2", " ", null, null, true, 1, 2)))
    }
}
