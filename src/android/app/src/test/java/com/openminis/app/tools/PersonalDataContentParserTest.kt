package com.openminis.app.tools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-eta-xposed-groups] Ported from Eta `agent/tool/PersonalDataContentParserTest.kt`
 * (Mangi-11/Eta @ c15de97).
 */
class PersonalDataContentParserTest {

    @Test
    fun `a provider exception is not treated as an empty result`() {
        assertTrue(
            PersonalDataContentParser.hasProviderFailure(
                stdout = "",
                stderr = "Error while accessing provider:media\n" +
                    "java.lang.IllegalArgumentException: Invalid column display_name",
            ),
        )
        assertFalse(
            PersonalDataContentParser.hasProviderFailure(
                stdout = "No result found.",
                stderr = "",
            ),
        )
    }

    @Test
    fun `rows are split by the declared columns, not by every comma`() {
        val rows = PersonalDataContentParser.parseRows(
            "Row: 0 _id=7, address=1069, body=会议地点改到 A, B 两区, date=123",
            listOf("_id", "address", "body", "date"),
        )

        assertEquals(1, rows.size)
        assertEquals("会议地点改到 A, B 两区", rows.single().getString("body"))
        assertEquals("123", rows.single().getString("date"))
        assertFalse(rows.single().has("unknown"))
    }
}
