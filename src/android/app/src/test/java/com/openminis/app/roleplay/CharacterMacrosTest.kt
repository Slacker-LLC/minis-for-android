package com.openminis.app.roleplay

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime
import java.util.Locale

/**
 * [T-eta-character-cards] Ported from Eta `agent/roleplay/CharacterMacros.kt` (Mangi-11/Eta @
 * c15de97). The clock and the locale are injected here, so the date, time and weekday macros are
 * pinned instead of depending on when the test runs.
 */
class CharacterMacrosTest {

    private val now = LocalDateTime.of(2026, 1, 5, 9, 30)

    private fun card(extra: JSONObject.() -> Unit = {}): CharacterCard {
        val data = JSONObject().put("name", "Ada").apply(extra)
        return CharacterCardCodec.decodeJson(
            JSONObject().put("spec", "chara_card_v2").put("data", data).toString(),
        )
    }

    private fun expand(text: String, card: CharacterCard = card(), locale: Locale = Locale.US) =
        CharacterMacros.expand(text, card, userName = "Bo", now = now, locale = locale)

    @Test
    fun `name macros work in both spellings and any case`() {
        val subject = card()

        assertEquals("Ada and Bo", expand("{{char}} and {{user}}", subject))
        assertEquals("Ada and Bo", expand("<CHAR> and <USER>", subject))
        assertEquals("Ada and Bo", expand("<bot> and <user>", subject))
        assertEquals("Ada", expand("{{ bot }}", subject))
    }

    @Test
    fun `card fields are available as macros`() {
        val subject = card {
            put("description", "a tester")
            put("personality", "curious")
            put("scenario", "a lab")
            put("mes_example", "example")
            put("first_mes", "hello")
            put("system_prompt", "be brief")
            put("post_history_instructions", "stay in character")
            put(
                "extensions",
                JSONObject().put(
                    "depth_prompt",
                    JSONObject().put("prompt", "deep note").put("depth", 2),
                ),
            )
        }

        assertEquals("a tester", expand("{{description}}", subject))
        assertEquals("curious", expand("{{personality}}", subject))
        assertEquals("a lab", expand("{{scenario}}", subject))
        assertEquals("example", expand("{{mesExamples}}", subject))
        assertEquals("hello", expand("{{charFirstMessage}}", subject))
        assertEquals("be brief", expand("{{charPrompt}}", subject))
        assertEquals("stay in character", expand("{{charInstruction}}", subject))
        assertEquals("deep note", expand("{{charDepthPrompt}}", subject))
    }

    @Test
    fun `date time and weekday come from the injected clock and locale`() {
        val subject = card()

        assertEquals("2026-01-05", expand("{{date}}", subject))
        assertEquals("09:30", expand("{{time}}", subject))
        assertEquals("Monday", expand("{{weekday}}", subject, Locale.US))
        assertEquals("星期一", expand("{{weekday}}", subject, Locale.SIMPLIFIED_CHINESE))
    }

    @Test
    fun `newline and noop are the two utility macros`() {
        assertEquals("a\nb", expand("a{{newline}}b"))
        assertEquals("ab", expand("a{{noop}}b"))
    }

    @Test
    fun `an unknown macro stays exactly as written`() {
        assertEquals("{{mystery}}", expand("{{mystery}}"))
        assertEquals("x {{mystery}} y", expand("x {{mystery}} y"))
    }

    @Test
    fun `a comment macro disappears`() {
        assertEquals("ab", expand("a{{// a note}}b"))
    }

    @Test
    fun `nested macros resolve and a cycle stops at the first repetition`() {
        val nested = card { put("description", "d of {{char}}") }
        assertEquals("d of Ada", expand("{{description}}", nested))

        val cyclic = card { put("description", "d {{description}}") }
        assertEquals("d {{description}}", expand("{{description}}", cyclic))
    }

    @Test
    fun `an expansion beyond the ceiling is refused instead of allocating`() {
        val huge = "x".repeat(CharacterMacros.MAX_EXPANDED_CHARS + 1)

        val failure = assertThrows(CharacterCardException::class.java) { expand(huge) }

        assertEquals("CARD_MACRO_EXPANSION_LIMIT", failure.code)
    }

    @Test
    fun `unsupported macros are detected for both closed and unclosed forms`() {
        val subject = card()

        assertTrue(CharacterMacros.hasUnsupportedMacros("{{mystery}}", subject))
        assertTrue("a truncated macro is still one the card meant to use", CharacterMacros.hasUnsupportedMacros("{{mystery", subject))
        assertFalse(CharacterMacros.hasUnsupportedMacros("{{char}} and <USER>", subject))
        assertFalse(CharacterMacros.hasUnsupportedMacros("{{// a comment}}", subject))
        assertFalse(CharacterMacros.hasUnsupportedMacros("plain text", subject))
    }

    @Test
    fun `a blank user name falls back to the default persona name`() {
        val rendered = CharacterMacros.expand("{{user}}", card(), userName = "   ", now = now)

        assertEquals("用户", rendered)
    }
}
