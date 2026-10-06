package com.openminis.app.config

import com.openminis.app.data.model.ThinkingLevel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ConfigThinkingTokensTest {
    @Test
    fun everyLevelRoundTripsThroughItsToken() {
        for (level in ThinkingLevel.values()) {
            val token = ConfigBuiltins.thinkingLevelToToken(level)
            assertEquals(level, ConfigBuiltins.thinkingLevelFromToken(token))
        }
    }

    @Test
    fun theWriteSchemaAcceptsEveryTokenTheReaderCanReturn() {
        val accepted = ConfigBuiltins.thinkingTokens()
        for (level in ThinkingLevel.values()) {
            assertTrue("${level.name} must be writable", ConfigBuiltins.thinkingLevelToToken(level) in accepted)
        }
        assertTrue("max" in accepted && "ultra" in accepted)
    }

    @Test
    fun anUnknownTokenIsNotALevel() {
        assertEquals(null, ConfigBuiltins.thinkingLevelFromToken("extreme"))
        assertEquals(null, ConfigBuiltins.thinkingLevelFromToken("HIGH"))
    }

    @Test
    fun aBindingBuiltWithJsonSurvivesQuotesAndBackslashesInTheId() {
        val id = "inst/we\"ird\\id"
        val json = org.json.JSONObject().put("type", "entry").put("entryId", id).toString()
        assertEquals(id, org.json.JSONObject(json).getString("entryId"))
    }
}
