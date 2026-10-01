package com.openminis.app.agent.subagents

import com.openminis.app.data.model.ModelBinding
import com.openminis.app.data.model.SubAgentDefinition
import com.openminis.app.data.model.ThinkingLevel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SubAgentStoreTest {
    @Test
    fun `a roster survives an encode-decode round trip with every field`() {
        val custom = SubAgentDefinition(
            id = "r1", name = "researcher", description = "digs", instructions = "cite sources",
            modelBinding = ModelBinding.encodeEntry("entry-1"), thinkingLevelOverride = ThinkingLevel.HIGH,
            sortOrder = 1, updatedAt = 42L,
        )
        val roster = com.openminis.app.data.model.SubAgentRoster.normalize(listOf(custom))
        val back = SubAgentStore.decode(SubAgentStore.encode(roster))
        assertEquals(roster, back)
        assertEquals(ThinkingLevel.HIGH, back[1].thinkingLevelOverride)
        assertEquals("entry-1", back[1].pinnedEntryId)
    }

    // ── Negative cases: stored data can be missing, damaged or from a newer build ──

    @Test
    fun `missing, empty and malformed payloads give the default roster`() {
        for (raw in listOf(null, "", "   ", "not json", "{}", """[{"name":1}]""")) {
            val roster = SubAgentStore.decode(raw)
            assertEquals(raw, listOf(SubAgentDefinition.BUILT_IN_ID), roster.map { it.id })
        }
    }

    @Test
    fun `unknown fields from a newer build are ignored`() {
        val raw = """[{"id":"a","name":"alpha","description":"d","futureField":{"x":1}}]"""
        val roster = SubAgentStore.decode(raw)
        assertEquals(listOf("General Sub Agent", "alpha"), roster.map { it.name })
    }

    @Test
    fun `a decoded roster is normalized (built-in restored, duplicates dropped)`() {
        val raw = """[{"id":"a","name":"alpha","description":"d"},{"id":"b","name":"ALPHA","description":"d"}]"""
        val roster = SubAgentStore.decode(raw)
        assertTrue(roster.first().isBuiltIn)
        assertEquals(2, roster.size)
    }
}
