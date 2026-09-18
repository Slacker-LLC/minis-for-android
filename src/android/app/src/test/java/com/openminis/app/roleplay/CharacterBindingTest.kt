package com.openminis.app.roleplay

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-eta-character-cards] Ported from Eta `agent/roleplay/CharacterCard.kt` (`RoleplayBinding`)
 * (Mangi-11/Eta @ c15de97). The snapshot is the point of this type: a session has to keep working
 * after its character is edited or deleted.
 */
class CharacterBindingTest {

    private fun card(name: String = "Ada"): CharacterCard = CharacterCardCodec.decodeJson(
        JSONObject()
            .put("spec", "chara_card_v2")
            .put("data", JSONObject().put("name", name).put("description", "a tester"))
            .toString(),
    )

    @Test
    fun `a binding round-trips through its json payload`() {
        val binding = CharacterBinding.fromCard(card(), userName = "Bo", userDescription = "a visitor")

        val restored = CharacterBinding.fromJson(binding.toJson())!!

        assertEquals(binding, restored)
        assertEquals("Ada", restored.characterName)
        assertEquals("Bo", restored.userName)
        assertTrue("the card travels with the binding", restored.cardSnapshotJson.contains("a tester"))
    }

    @Test
    fun `a blank persona name falls back to the default`() {
        assertEquals(
            CharacterBinding.DEFAULT_USER_NAME,
            CharacterBinding.fromCard(card(), userName = "  ").userName,
        )

        val patched = JSONObject(CharacterBinding.fromCard(card()).toJson()).put("userName", "")
        assertEquals(
            CharacterBinding.DEFAULT_USER_NAME,
            CharacterBinding.fromJson(patched.toString())!!.userName,
        )
    }

    @Test
    fun `an unusable payload is treated as no binding`() {
        assertNull(CharacterBinding.fromJson(null))
        assertNull(CharacterBinding.fromJson(""))
        assertNull(CharacterBinding.fromJson("   "))
        assertNull(CharacterBinding.fromJson("not json"))
        assertNull(
            "a payload without a card snapshot is not a binding",
            CharacterBinding.fromJson(JSONObject().put("characterName", "Ada").toString()),
        )
        assertNull(
            "a payload without a name is not a binding",
            CharacterBinding.fromJson(
                JSONObject().put("characterName", "  ").put("cardSnapshotJson", "{}").toString(),
            ),
        )
    }

    @Test
    fun `an empty character id still produces a usable binding`() {
        val binding = CharacterBinding.fromCard(card())

        assertEquals("", binding.characterId)
        assertEquals("Ada", CharacterBinding.fromJson(binding.toJson())!!.characterName)
    }
}
