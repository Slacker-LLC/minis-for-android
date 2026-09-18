package com.openminis.app.roleplay

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-eta-character-cards] Ported from Eta `agent/roleplay/CharacterWorldbook.kt` and
 * `CharacterWorldbookSupport.kt` (Mangi-11/Eta @ c15de97). Two things are being pinned: an
 * unsupported condition is reported rather than silently triggered, and the scan window, the
 * budget and the recursion bound behave the way the card author expects.
 */
class CharacterWorldbookTest {

    private fun entry(
        keys: List<String>,
        content: String,
        extra: JSONObject.() -> Unit = {},
    ): JSONObject = JSONObject()
        .put("keys", JSONArray(keys))
        .put("content", content)
        .apply(extra)

    private fun cardWith(vararg entries: JSONObject, bookExtra: JSONObject.() -> Unit = {}): CharacterCard {
        val book = JSONObject()
            .put("entries", JSONArray().also { array -> entries.forEach(array::put) })
            .apply(bookExtra)
        return CharacterCardCodec.decodeJson(
            JSONObject()
                .put("spec", "chara_card_v2")
                .put("data", JSONObject().put("name", "Ada").put("character_book", book))
                .toString(),
        )
    }

    private fun resolve(card: CharacterCard, vararg messages: String, budget: Int = 1_000) =
        CharacterWorldbook.resolve(card, messages.toList(), inputTokenBudget = budget) { it.length }

    @Test
    fun `a card without a book projects nothing`() {
        val card = CharacterCardCodec.decodeJson(
            JSONObject().put("spec", "chara_card_v2").put("data", JSONObject().put("name", "Ada")).toString(),
        )

        val projection = resolve(card, "anything")

        assertEquals("", projection.beforeCharacter)
        assertEquals("", projection.afterCharacter)
        assertEquals(0, projection.usedTokens)
    }

    @Test
    fun `a constant entry always lands and its position decides the side`() {
        val card = cardWith(
            entry(listOf("never"), "before lore", extra = {
                put("constant", true)
                put("extensions", JSONObject().put("position", 0))
            }),
            entry(listOf("never"), "after lore", extra = {
                put("constant", true)
                put("extensions", JSONObject().put("position", 1))
            }),
        )

        val projection = resolve(card, "no keywords here")

        assertEquals("before lore", projection.beforeCharacter)
        assertEquals("after lore", projection.afterCharacter)
    }

    @Test
    fun `the scan window is the last scan depth messages`() {
        val card = cardWith(
            entry(listOf("magic"), "the lore"),
            bookExtra = { put("scan_depth", 2) },
        )

        // No position on the entry: it belongs after the character block, which is the default
        // Eta and this port share.
        assertEquals("", resolve(card, "magic happens", "unrelated", "unrelated").afterCharacter)
        assertEquals("the lore", resolve(card, "unrelated", "magic happens").afterCharacter)
    }

    @Test
    fun `case sensitivity is honoured per entry`() {
        val card = cardWith(
            entry(listOf("Magic"), "sensitive lore", extra = { put("case_sensitive", true) }),
        )

        assertEquals("", resolve(card, "magic happens").afterCharacter)
        assertEquals("sensitive lore", resolve(card, "Magic happens").afterCharacter)
    }

    @Test
    fun `a selective entry needs a secondary key`() {
        val card = cardWith(
            entry(
                listOf("magic"), "selective lore",
                extra = {
                    put("selective", true)
                    put("secondary_keys", JSONArray().put("dragon"))
                },
            ),
        )

        assertEquals("", resolve(card, "magic without the other word").afterCharacter)
        assertEquals("selective lore", resolve(card, "magic and a dragon").afterCharacter)
    }

    @Test
    fun `disabled and unsupported entries never trigger`() {
        val card = cardWith(
            entry(listOf("magic"), "disabled lore", extra = { put("enabled", false) }),
            entry(listOf("/magic/i"), "regex lore"),
            entry(listOf("magic"), "@@depth 2 decorated lore"),
        )

        val projection = resolve(card, "magic magic magic")

        assertEquals("", projection.beforeCharacter)
        val unsupported = CharacterWorldbook.unsupportedEntries(card)
        assertEquals(listOf(1, 2), unsupported.map { it.index })
        assertTrue(unsupported.all { it.reasons.isNotEmpty() })
    }

    @Test
    fun `insertion order decides the order of the projected text`() {
        val card = cardWith(
            entry(listOf("magic"), "second", extra = { put("insertion_order", 5) }),
            entry(listOf("magic"), "first", extra = { put("insertion_order", 1) }),
        )

        assertEquals("first\n\nsecond", resolve(card, "magic").afterCharacter)
    }

    @Test
    fun `the budget drops what does not fit instead of overflowing`() {
        val card = cardWith(
            entry(listOf("magic"), "x".repeat(500)),
            bookExtra = { put("token_budget", 50) },
        )

        val projection = resolve(card, "magic")

        assertEquals("", projection.beforeCharacter)
        assertEquals(0, projection.usedTokens)
    }

    @Test
    fun `recursion lets a matched entry reveal another when it is enabled`() {
        val entries = arrayOf(
            entry(listOf("magic"), "the dragon sleeps"),
            entry(listOf("dragon"), "second layer lore"),
        )

        val off = resolve(cardWith(*entries, bookExtra = { put("recursive_scanning", false) }), "magic")
        assertEquals("the dragon sleeps", off.afterCharacter)

        val on = resolve(cardWith(*entries, bookExtra = { put("recursive_scanning", true) }), "magic")
        assertTrue(on.afterCharacter.contains("the dragon sleeps"))
        assertTrue("the second entry is reached through the first", on.afterCharacter.contains("second layer lore"))
    }

    @Test
    fun `support reasons name every unsupported condition`() {
        val entry = entry(listOf("magic"), "lore", extra = {
            put("use_regex", false)
            put(
                "extensions",
                JSONObject()
                    .put("position", 5)
                    .put("selectiveLogic", 1)
                    .put("useProbability", true)
                    .put("probability", 50)
                    .put("group", 2)
                    .put("sticky", 3)
                    .put("exclude_recursion", true)
                    .put("triggers", JSONArray().put("normal"))
                    .put("automation_id", "script-1"),
            )
        })

        val reasons = CharacterWorldbookSupport.reasons(entry)

        assertTrue(reasons.contains("特殊插入位置"))
        assertTrue(reasons.contains("高级次级匹配"))
        assertTrue(reasons.contains("概率触发"))
        assertTrue(reasons.contains("条目分组"))
        assertTrue(reasons.contains("时序触发"))
        assertTrue(reasons.contains("扩展匹配条件"))
        assertTrue(reasons.contains("指定生成类型"))
        assertTrue(reasons.contains("脚本自动化"))
        assertTrue("use_regex=false disables the regex complaint", !reasons.contains("正则关键字"))
    }

    @Test
    fun `a plain entry has no reasons`() {
        assertEquals(emptyList<String>(), CharacterWorldbookSupport.reasons(entry(listOf("magic"), "lore")))
        assertEquals(
            emptyList<String>(),
            CharacterWorldbookSupport.reasons(
                entry(listOf("magic"), "lore", extra = {
                    put(
                        "extensions",
                        JSONObject().put("position", 1).put("probability", 100),
                    )
                }),
            ),
        )
    }
}
