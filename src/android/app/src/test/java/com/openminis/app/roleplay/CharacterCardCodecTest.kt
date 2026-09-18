package com.openminis.app.roleplay

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-eta-character-cards] Ported from Eta `agent/roleplay/CharacterCardCodec.kt`
 * (Mangi-11/Eta @ c15de97). The refusals are the contract: an unsupported spec, a broken
 * world book or a UTF-8 failure must be named, not guessed around.
 */
class CharacterCardCodecTest {

    private fun v2(name: String = "Ada", extra: JSONObject.() -> Unit = {}): String {
        val data = JSONObject().put("name", name).apply(extra)
        return JSONObject().put("spec", "chara_card_v2").put("spec_version", "2.0").put("data", data).toString()
    }

    @Test
    fun `a v2 card keeps its fields and reports them`() {
        val card = CharacterCardCodec.decodeJson(
            v2 {
                put("description", "a test character")
                put("first_mes", "hello")
                put("tags", JSONArray().put("test"))
                put("alternate_greetings", JSONArray().put("hi there"))
            },
        )

        assertEquals("chara_card_v2", card.spec)
        assertEquals("Ada", card.name)
        assertEquals("a test character", card.description)
        assertEquals("hello", card.firstMessage)
        assertEquals(listOf("test"), card.tags)
        assertEquals(listOf("hi there"), card.alternateGreetings)
        assertEquals("Ada", card.nickname)
    }

    @Test
    fun `a legacy card is normalised into the v2 shape`() {
        val legacy = JSONObject()
            .put("name", "Old")
            .put("description", "v1 layout")
            .put("creatorcomment", "from the v1 field")
            .toString()

        val card = CharacterCardCodec.decodeJson(legacy)

        assertEquals("chara_card_v2", card.spec)
        assertEquals("Old", card.name)
        assertEquals("from the v1 field", card.creatorNotes)
        assertTrue("the v2 skeleton is filled in", card.data.has("extensions"))
        assertEquals("2.0", card.raw.getString("spec_version"))
    }

    @Test
    fun `an explicit null counts as absent`() {
        val card = CharacterCardCodec.decodeJson(
            v2 {
                put("creator", JSONObject.NULL)
                put("description", JSONObject.NULL)
            },
        )

        assertEquals("", card.creator)
        assertEquals("", card.description)
    }

    @Test
    fun `an unsupported spec is refused by name`() {
        val failure = assertThrows(CharacterCardException::class.java) {
            CharacterCardCodec.decodeJson(
                JSONObject().put("spec", "chara_card_v9").put("data", JSONObject().put("name", "x")).toString(),
            )
        }

        assertEquals("CARD_UNSUPPORTED_SPEC", failure.code)
    }

    @Test
    fun `a card without a name or with the wrong field types is refused`() {
        assertThrows(IllegalArgumentException::class.java) {
            CharacterCardCodec.decodeJson(JSONObject().put("spec", "chara_card_v2").put("data", JSONObject()).toString())
        }
        assertThrows(IllegalArgumentException::class.java) {
            CharacterCardCodec.decodeJson(v2 { put("description", 42) })
        }
        assertThrows(IllegalArgumentException::class.java) {
            CharacterCardCodec.decodeJson(v2 { put("tags", JSONArray().put(1)) })
        }
    }

    @Test
    fun `broken json is reported as such`() {
        val failure = assertThrows(CharacterCardException::class.java) {
            CharacterCardCodec.decodeJson("{ not json")
        }

        assertEquals("CARD_INVALID_JSON", failure.code)
    }

    @Test
    fun `bytes must be valid utf8 and within the size ceiling`() {
        val invalidUtf8 = byteArrayOf(0x7B, 0x22, 0x61.toByte(), 0x22, 0x3A, 0xFF.toByte(), 0x7D)
        assertEquals(
            "CARD_INVALID_UTF8",
            assertThrows(CharacterCardException::class.java) { CharacterCardCodec.decodeBytes(invalidUtf8) }.code,
        )

        val huge = ByteArray(CharacterCardCodec.MAX_CARD_BYTES + 1) { 0x20 }
        assertEquals(
            "CARD_TOO_LARGE",
            assertThrows(CharacterCardException::class.java) { CharacterCardCodec.decodeBytes(huge) }.code,
        )
    }

    @Test
    fun `world book numbers flags and entries are validated`() {
        assertThrows(IllegalArgumentException::class.java) {
            CharacterCardCodec.decodeJson(
                v2 { put("character_book", JSONObject().put("scan_depth", -1)) },
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            CharacterCardCodec.decodeJson(
                v2 { put("character_book", JSONObject().put("entries", JSONArray().put("nope"))) },
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            CharacterCardCodec.decodeJson(
                v2 {
                    put(
                        "character_book",
                        JSONObject().put(
                            "entries",
                            JSONArray().put(JSONObject().put("keys", JSONArray().put(1))),
                        ),
                    )
                },
            )
        }
        // A well-formed book passes and keeps its content.
        val card = CharacterCardCodec.decodeJson(
            v2 {
                put(
                    "character_book",
                    JSONObject()
                        .put("scan_depth", 4)
                        .put("recursive_scanning", true)
                        .put(
                            "entries",
                            JSONArray().put(
                                JSONObject()
                                    .put("keys", JSONArray().put("magic"))
                                    .put("content", "the world is round")
                                    .put("enabled", true)
                                    .put("insertion_order", 10),
                            ),
                        ),
                )
            },
        )
        assertEquals(1, card.characterBook!!.getJSONArray("entries").length())
    }

    @Test
    fun `export fills the v2 or v3 skeleton and keeps unknown fields`() {
        val card = CharacterCardCodec.decodeJson(
            v2 {
                put("creator", "someone")
                put("x_custom", JSONObject().put("kept", true))
            },
        )

        val v2 = JSONObject(CharacterCardCodec.exportView(card, 2))
        assertEquals("chara_card_v2", v2.getString("spec"))
        assertTrue("unknown fields survive an export", v2.getJSONObject("data").has("x_custom"))
        assertEquals("someone", v2.getJSONObject("data").getString("creator"))

        val v3 = JSONObject(CharacterCardCodec.exportView(card, 3))
        assertEquals("chara_card_v3", v3.getString("spec"))
        assertEquals("3.0", v3.getString("spec_version"))
        assertTrue(v3.getJSONObject("data").has("group_only_greetings"))
    }

    @Test
    fun `a legacy mirror field stays in step with its data field`() {
        val legacy = JSONObject()
            .put("name", "Mirror")
            .put("description", "top level")
            .toString()
        val card = CharacterCardCodec.decodeJson(legacy)

        val exported = JSONObject(CharacterCardCodec.exportView(card, 2))
        assertEquals("Mirror", exported.getString("name"))
        assertEquals("Mirror", exported.getJSONObject("data").getString("name"))
    }
}
