package com.openminis.app.tools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-eta-xposed-groups] Ported from Eta `agent/tool/AgentPersonalDataTools.kt` (Mangi-11/Eta @
 * c15de97). A keyword becomes SQL inside another app's provider, so the escaping and the bounds are
 * the cases that matter.
 */
class PersonalDataQueryPolicyTest {

    @Test
    fun `a keyword is escaped before it becomes a pattern`() {
        val clause = PersonalDataQueryPolicy.likeClause("50%_a'b", listOf("raw_title"))

        assertTrue(clause.startsWith("("))
        assertTrue(clause.endsWith(")"))
        assertTrue("the wildcards are escaped, not left to match anything", clause.contains("\\%"))
        assertTrue(clause.contains("\\_"))
        assertTrue("the quote is doubled", clause.contains("''"))
        assertTrue(clause.contains("LOWER(raw_title) LIKE LOWER("))
        assertTrue(clause.contains("ESCAPE '\\'"))
    }

    @Test
    fun `several columns are one OR group`() {
        val clause = PersonalDataQueryPolicy.likeClause("快递", listOf("raw_title", "raw_text"))

        assertTrue(clause.contains("LOWER(raw_title) LIKE LOWER("))
        assertTrue(clause.contains(" OR "))
        assertTrue(clause.contains("LOWER(raw_text) LIKE LOWER("))
    }

    @Test
    fun `a fixed filter and a keyword filter are both kept`() {
        assertEquals("only", PersonalDataQueryPolicy.combineWhere("only", null))
        assertEquals("only", PersonalDataQueryPolicy.combineWhere(null, "only"))
        assertEquals(null, PersonalDataQueryPolicy.combineWhere(null, null))
        assertEquals(
            "(deleted=0) AND (keyword)",
            PersonalDataQueryPolicy.combineWhere("deleted=0", "keyword"),
        )
    }

    @Test
    fun `the row count is clamped and an over-long keyword is refused`() {
        assertEquals(PersonalDataQueryPolicy.DEFAULT_LIMIT, PersonalDataQueryPolicy.clampLimit(null))
        assertEquals(1, PersonalDataQueryPolicy.clampLimit(0))
        assertEquals(PersonalDataQueryPolicy.MAX_LIMIT, PersonalDataQueryPolicy.clampLimit(500))
        assertFalse(
            PersonalDataQueryPolicy.isKeywordTooLong(
                "a".repeat(PersonalDataQueryPolicy.MAX_KEYWORD_CHARS),
            ),
        )
        assertTrue(
            PersonalDataQueryPolicy.isKeywordTooLong(
                "a".repeat(PersonalDataQueryPolicy.MAX_KEYWORD_CHARS + 1),
            ),
        )
    }
}
