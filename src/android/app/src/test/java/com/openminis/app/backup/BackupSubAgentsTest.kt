package com.openminis.app.backup

import com.openminis.app.data.model.ModelBinding
import com.openminis.app.data.model.SubAgentDefinition
import com.openminis.app.data.model.SubAgentRoster
import com.openminis.app.data.model.ThinkingLevel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BackupSubAgentsTest {
    private fun def(name: String, id: String = "id-$name", updated: Long = 1_000L, order: Int = 1) =
        SubAgentDefinition(id = id, name = name, description = "d-$name", instructions = "", sortOrder = order, updatedAt = updated)

    @Test
    fun `a custom agent round-trips through its record`() {
        val d = def("researcher", updated = 1_790_000_123_456L).copy(
            instructions = "cite sources", modelBinding = ModelBinding.encodeEntry("entry-7"),
            thinkingLevelOverride = ThinkingLevel.XHIGH,
        )
        val back = BackupSubAgentMapping.fromRecord(BackupSubAgentMapping.toRecord(d))!!
        assertEquals(d.copy(updatedAt = 1_790_000_123_000L), back)
    }

    @Test
    fun `the record is JSON with the whole-second ISO date`() {
        val json = BackupFormat.json.encodeToString(
            BackupSubAgentRecord.serializer(), BackupSubAgentMapping.toRecord(def("a", updated = 1_790_000_123_456L)),
        )
        assertTrue(json, json.contains("\"updatedAt\":\"2026-"))
        assertTrue(json, !json.contains(".456"))
        assertTrue("empty instructions are still written", json.contains("\"instructions\":\"\""))
    }

    // ── Negative cases ──────────────────────────────────────────────────────

    @Test
    fun `the built-in is never exported and never restored`() {
        assertTrue(BackupSubAgentMapping.exportable(listOf(SubAgentDefinition.makeBuiltIn())).isEmpty())
        val record = BackupSubAgentMapping.toRecord(SubAgentDefinition.makeBuiltIn())
        assertNull(BackupSubAgentMapping.fromRecord(record))
        assertNull(BackupSubAgentMapping.fromRecord(record.copy(id = "x", name = "   ")))
        assertNull(BackupSubAgentMapping.fromRecord(record.copy(id = "")))
    }

    @Test
    fun `an unreadable date is older than anything local and an unknown level means not set`() {
        val r = BackupSubAgentMapping.toRecord(def("a")).copy(updatedAt = "yesterday", thinkingLevelOverride = "galaxy-brain")
        val d = BackupSubAgentMapping.fromRecord(r)!!
        assertEquals(0L, d.updatedAt)
        assertNull(d.thinkingLevelOverride)
        assertEquals(ThinkingLevel.HIGH, BackupSubAgentMapping.fromRecord(r.copy(thinkingLevelOverride = "HIGH"))!!.thinkingLevelOverride)
    }

    // ── Merge rules ─────────────────────────────────────────────────────────

    private fun local(vararg d: SubAgentDefinition) = SubAgentRoster.normalize(d.toList())

    @Test
    fun `a new id is added, a newer copy replaces a known one, an older copy does not`() {
        val have = local(def("alpha", updated = 5_000L))
        val merged = SubAgentRoster.mergeBackup(
            have,
            listOf(def("beta"), def("alpha-edited", id = "id-alpha", updated = 9_000L)),
        )
        assertEquals(2, merged.written)
        assertEquals(listOf("General Sub Agent", "alpha-edited", "beta"), merged.roster.map { it.name })

        val older = SubAgentRoster.mergeBackup(merged.roster, listOf(def("alpha-old", id = "id-alpha", updated = 1L)))
        assertEquals(0, older.written)
        assertEquals(1, older.skipped)
        assertEquals("alpha-edited", older.roster[1].name)
    }

    @Test
    fun `an agent whose name clashes with a local one is skipped and the built-in is left alone`() {
        val have = local(def("alpha"))
        val merged = SubAgentRoster.mergeBackup(
            have,
            listOf(def("ALPHA", id = "other-id"), SubAgentDefinition.makeBuiltIn().copy(instructions = "tampered")),
        )
        assertEquals(0, merged.written)
        assertEquals(2, merged.skipped)
        assertEquals("", merged.roster.first().instructions)
        assertEquals(2, merged.roster.size)
    }
}
