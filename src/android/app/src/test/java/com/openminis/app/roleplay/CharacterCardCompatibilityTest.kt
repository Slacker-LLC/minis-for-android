package com.openminis.app.roleplay

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-eta-character-cards] Ported from Eta `agent/roleplay/CharacterCardCompatibility.kt`
 * (Mangi-11/Eta @ c15de97). Every case here is a card asking for something this app keeps but does
 * not run — the warning has to say so instead of the behaviour quietly differing.
 */
class CharacterCardCompatibilityTest {

    private fun card(extra: JSONObject.() -> Unit = {}): CharacterCard = CharacterCardCodec.decodeJson(
        JSONObject().put("spec", "chara_card_v2").put("data", JSONObject().put("name", "Ada").apply(extra)).toString(),
    )

    private fun codes(card: CharacterCard) = CharacterCardCompatibility.warnings(card).map { it.code }

    @Test
    fun `a plain card has nothing to report`() {
        assertEquals(emptyList<String>(), codes(card { put("description", "a tester") }))
    }

    @Test
    fun `an unimplemented macro is reported`() {
        assertTrue(codes(card { put("description", "uses {{mystery}}") }).contains("MACRO_UNSUPPORTED"))
        assertEquals(
            emptyList<String>(),
            codes(card { put("description", "uses {{char}} and {{user}}") }),
        )
    }

    @Test
    fun `html or script markup is reported`() {
        assertTrue(
            codes(card { put("first_mes", "<div class=x>hello</div>") }).contains("HTML_MARKUP"),
        )
        assertTrue(
            codes(card { put("first_mes", "plain <script>alert(1)</script> text") }).contains("HTML_MARKUP"),
        )
    }

    @Test
    fun `regex and script extensions are reported from nested extension keys`() {
        val regex = card {
            put(
                "extensions",
                JSONObject().put("regex_scripts", JSONArray().put(JSONObject().put("find", "a"))),
            )
        }
        assertTrue(codes(regex).contains("REGEX_EXTENSION"))

        val script = card {
            put("extensions", JSONObject().put("script_state", JSONObject().put("enabled", true)))
        }
        assertTrue(codes(script).contains("SCRIPT_EXTENSION"))

        assertEquals(
            emptyList<String>(),
            codes(card { put("extensions", JSONObject().put("regex_scripts", JSONArray())) }),
        )
    }

    @Test
    fun `an unusable depth note is reported and a usable one is not`() {
        val broken = card {
            put(
                "extensions",
                JSONObject().put(
                    "depth_prompt",
                    JSONObject().put("prompt", "note").put("depth", "deep"),
                ),
            )
        }
        assertTrue(codes(broken).contains("DEPTH_PROMPT_IGNORED"))

        val notAnObject = card { put("extensions", JSONObject().put("depth_prompt", "just text")) }
        assertTrue(codes(notAnObject).contains("DEPTH_PROMPT_IGNORED"))

        val good = card {
            put(
                "extensions",
                JSONObject().put(
                    "depth_prompt",
                    JSONObject().put("prompt", "note").put("depth", 2).put("role", "system"),
                ),
            )
        }
        assertEquals(emptyList<String>(), codes(good))
    }

    @Test
    fun `skipped world book entries are counted`() {
        val subject = card {
            put(
                "character_book",
                JSONObject().put(
                    "entries",
                    JSONArray()
                        .put(JSONObject().put("keys", JSONArray().put("/magic/i")).put("content", "lore"))
                        .put(JSONObject().put("keys", JSONArray().put("plain")).put("content", "fine")),
                ),
            )
        }

        val warning = CharacterCardCompatibility.warnings(subject)
            .single { it.code == "WORLD_BOOK_ENTRIES_SKIPPED" }
        assertTrue("the count is in the detail", warning.detail.contains("1 "))
    }

    @Test
    fun `assets and group greetings are reported and preserved`() {
        val subject = card {
            put("assets", JSONArray().put(JSONObject().put("type", "icon")))
            put("group_only_greetings", JSONArray().put("hi all"))
        }

        val codes = codes(subject)
        assertTrue(codes.contains("ASSETS_PRESERVED"))
        assertTrue(codes.contains("GROUP_GREETINGS_IGNORED"))
        assertTrue("the data itself is untouched", subject.data.getJSONArray("assets").length() == 1)
    }
}
