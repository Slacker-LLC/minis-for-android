package com.openminis.app.roleplay

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-eta-character-cards] Ported from Eta `agent/roleplay/CharacterWorldbookDraft.kt`
 * (Mangi-11/Eta @ c15de97). The rule under test is the one that protects somebody's lore library:
 * only the fields the editor actually changed are rewritten, and everything it does not model
 * survives.
 */
class CharacterWorldbookDraftTest {

    private fun entry(
        extra: JSONObject.() -> Unit = {},
    ): JSONObject = JSONObject()
        .put("keys", JSONArray().put("magic"))
        .put("content", "the sky is green")
        .apply(extra)

    private fun card(book: JSONObject? = null): CharacterCard {
        val data = JSONObject().put("name", "Ada").put("description", "a tester")
        book?.let { data.put("character_book", it) }
        return CharacterCardCodec.decodeJson(
            JSONObject().put("spec", "chara_card_v2").put("data", data).toString(),
        )
    }

    @Test
    fun `a card without a book reads as an empty draft`() {
        val draft = CharacterWorldbookDraftCodec.read(card())

        assertEquals("", draft.name)
        assertEquals(null, draft.scanDepth)
        assertTrue(draft.entries.isEmpty())
    }

    @Test
    fun `an existing book reads into the draft field by field`() {
        val book = JSONObject()
            .put("name", "the world")
            .put("scan_depth", 3)
            .put("token_budget", 500)
            .put("recursive_scanning", true)
            .put(
                "entries",
                JSONArray()
                    .put(
                        entry {
                            put("comment", "lore one")
                            put("enabled", false)
                            put("insertion_order", 7)
                            put("extensions", JSONObject().put("position", 0))
                        },
                    )
                    .put(entry { put("position", "after_char") }),
            )

        val draft = CharacterWorldbookDraftCodec.read(card(book))

        assertEquals("the world", draft.name)
        assertEquals(3, draft.scanDepth)
        assertEquals(500, draft.tokenBudget)
        assertEquals(true, draft.recursiveScanning)
        assertEquals(2, draft.entries.size)
        assertEquals("the extension number wins over the text field", "before_char", draft.entries[0].position)
        assertEquals("a v1 comment is the entry name", "lore one", draft.entries[0].name)
        assertFalse(draft.entries[0].enabled)
        assertEquals(7, draft.entries[0].insertionOrder)
        assertEquals("the second entry keeps its text position", "after_char", draft.entries[1].position)
        assertEquals("a missing order falls back to the index", 1, draft.entries[1].insertionOrder)
    }

    @Test
    fun `editing one field leaves every other field alone`() {
        val book = JSONObject()
            .put("scan_depth", 3)
            .put(
                "entries",
                JSONArray().put(
                    entry {
                        put("probability", 50)
                        put("x_vendor", JSONObject().put("kept", true))
                    },
                ),
            )
        val original = card(book)
        val draft = CharacterWorldbookDraftCodec.read(original)

        val edited = CharacterWorldbookDraftCodec.write(
            original,
            draft.copy(entries = listOf(draft.entries[0].copy(content = "the sky is blue"))),
        )

        val entry = edited.characterBook!!.getJSONArray("entries").getJSONObject(0)
        assertEquals("the sky is blue", entry.getString("content"))
        assertEquals("an unmodelled flag survives", 50, entry.getInt("probability"))
        assertTrue("a vendor object survives", entry.getJSONObject("x_vendor").getBoolean("kept"))
        assertEquals("the book setting is untouched", 3, edited.characterBook!!.getInt("scan_depth"))
        assertEquals("the rest of the card is untouched", "a tester", edited.description)
    }

    @Test
    fun `clearing a book setting removes rather than zeroes it`() {
        val book = JSONObject().put("scan_depth", 4).put("token_budget", 100).put("recursive_scanning", true)
        val draft = CharacterWorldbookDraftCodec.read(card(book))

        val edited = CharacterWorldbookDraftCodec.write(
            card(book),
            draft.copy(scanDepth = null, tokenBudget = null, recursiveScanning = null),
        )

        val written = edited.characterBook!!
        assertFalse(written.has("scan_depth"))
        assertFalse(written.has("token_budget"))
        assertFalse(written.has("recursive_scanning"))
    }

    @Test
    fun `a new entry is written with the fields the editor knows`() {
        val draft = CharacterWorldbookDraftCodec.read(card())
        val edited = CharacterWorldbookDraftCodec.write(
            card(),
            draft.copy(
                entries = listOf(
                    CharacterBookEntryDraft(
                        name = "lore",
                        content = "the sea is dry",
                        keys = listOf("sea"),
                        position = "before_char",
                        insertionOrder = 2,
                    ),
                ),
            ),
        )

        val entry = edited.characterBook!!.getJSONArray("entries").getJSONObject(0)
        assertEquals("lore", entry.getString("name"))
        assertEquals("the sea is dry", entry.getString("content"))
        assertEquals("sea", entry.getJSONArray("keys").getString(0))
        assertEquals("before_char", entry.getString("position"))
        assertEquals(2, entry.getInt("insertion_order"))
        assertTrue("a fresh book gets its extensions object", entry.has("extensions"))
    }

    @Test
    fun `a position change updates both spellings when both were present`() {
        val book = JSONObject().put(
            "entries",
            JSONArray().put(
                entry {
                    put("position", "after_char")
                    put("extensions", JSONObject().put("position", 1))
                },
            ),
        )
        val draft = CharacterWorldbookDraftCodec.read(card(book))

        val edited = CharacterWorldbookDraftCodec.write(
            card(book),
            draft.copy(entries = listOf(draft.entries[0].copy(position = "before_char"))),
        )

        val entry = edited.characterBook!!.getJSONArray("entries").getJSONObject(0)
        assertEquals("before_char", entry.getString("position"))
        assertEquals("the extension number follows", 0, entry.getJSONObject("extensions").getInt("position"))
    }

    @Test
    fun `negative settings are refused`() {
        val draft = CharacterWorldbookDraftCodec.read(card())

        assertThrows(IllegalArgumentException::class.java) {
            CharacterWorldbookDraftCodec.write(card(), draft.copy(scanDepth = -1))
        }
        assertThrows(IllegalArgumentException::class.java) {
            CharacterWorldbookDraftCodec.write(card(), draft.copy(tokenBudget = -5))
        }
    }

    @Test
    fun `an entry whose position this app cannot express is refused`() {
        val entry = CharacterBookEntryDraft(content = "x", position = "at_depth")

        assertThrows(IllegalArgumentException::class.java) {
            CharacterWorldbookDraftCodec.write(
                card(),
                CharacterWorldbookDraftCodec.read(card()).copy(entries = listOf(entry)),
            )
        }
    }
}
