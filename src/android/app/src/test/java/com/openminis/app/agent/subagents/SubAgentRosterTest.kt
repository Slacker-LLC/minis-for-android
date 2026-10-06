package com.openminis.app.agent.subagents

import com.openminis.app.data.model.ModelBinding
import com.openminis.app.data.model.SubAgentDefinition
import com.openminis.app.data.model.SubAgentLimits
import com.openminis.app.data.model.SubAgentRoster
import com.openminis.app.data.model.ThinkingLevel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SubAgentRosterTest {
    private fun def(name: String, order: Int = 0, id: String = "id-$name") =
        SubAgentDefinition(id = id, name = name, description = "does $name", sortOrder = order)

    @Test
    fun `an empty roster gets the built-in at the front`() {
        val out = SubAgentRoster.normalize(emptyList())
        assertEquals(listOf(SubAgentDefinition.BUILT_IN_ID), out.map { it.id })
        assertTrue(out.single().isBuiltIn)
    }

    @Test
    fun `custom agents keep their order and sortOrder is renumbered densely`() {
        val out = SubAgentRoster.normalize(listOf(def("zeta", order = 9), def("alpha", order = 3)))
        assertEquals(listOf("General Sub Agent", "alpha", "zeta"), out.map { it.name })
        assertEquals(listOf(0, 1, 2), out.map { it.sortOrder })
    }

    @Test
    fun `the built-in's name and description are restored, its user fields are kept`() {
        val tampered = SubAgentDefinition.makeBuiltIn().copy(
            name = "Renamed", description = "other", instructions = "keep me",
            modelBinding = ModelBinding.encodeEntry("entry-1"), thinkingLevelOverride = ThinkingLevel.HIGH,
        )
        val built = SubAgentRoster.normalize(listOf(tampered)).single()
        assertEquals(SubAgentDefinition.BUILT_IN_NAME, built.name)
        assertEquals(SubAgentDefinition.BUILT_IN_DESCRIPTION, built.description)
        assertEquals("keep me", built.instructions)
        assertEquals("entry-1", built.pinnedEntryId)
        assertEquals(ThinkingLevel.HIGH, built.thinkingLevelOverride)
    }

    @Test
    fun `resolve matches names case-, space- and accent-insensitively`() {
        val roster = SubAgentRoster.normalize(listOf(def("Résumé Writer")))
        assertEquals("Résumé Writer", SubAgentRoster.resolve("  resume WRITER ", roster)!!.name)
    }

    @Test
    fun `a blank name resolves to the built-in`() {
        val roster = SubAgentRoster.normalize(listOf(def("alpha")))
        assertEquals(SubAgentDefinition.BUILT_IN_ID, SubAgentRoster.resolve(null, roster)!!.id)
        assertEquals(SubAgentDefinition.BUILT_IN_ID, SubAgentRoster.resolve("   ", roster)!!.id)
    }

    @Test
    fun `the pinned entry id is read from an entry binding`() {
        assertEquals("e9", def("a").copy(modelBinding = ModelBinding.encodeEntry("e9")).pinnedEntryId)
        assertNull(def("a").pinnedEntryId)
    }

    // ── Negative cases: stored data can be damaged or hand-edited ───────────

    @Test
    fun `nameless, duplicate-named and built-in-claiming entries are dropped`() {
        val out = SubAgentRoster.normalize(
            listOf(
                def("   ", id = "blank"),
                def("alpha", id = "a1"),
                def("ALPHA", id = "a2"),
                def("general sub agent", id = "dup-of-builtin"),
                def("sneaky", id = SubAgentDefinition.BUILT_IN_ID),
                def("fake", id = "f").copy(isBuiltIn = true),
            ),
        )
        assertEquals(listOf("General Sub Agent", "alpha"), out.map { it.name })
        assertEquals(1, out.count { it.isBuiltIn })
    }

    @Test
    fun `the roster is bounded and fields are clamped`() {
        val many = (1..30).map { def("agent$it", order = it) }
        val out = SubAgentRoster.normalize(many)
        assertEquals(SubAgentLimits.MAX_COUNT, out.size)
        assertEquals("agent1", out[1].name)

        val long = def("n".repeat(100)).copy(description = "d".repeat(500), instructions = "i".repeat(9000))
        val clamped = SubAgentRoster.normalize(listOf(long))[1]
        assertEquals(SubAgentLimits.NAME_MAX_LENGTH, clamped.name.length)
        assertEquals(SubAgentLimits.DESCRIPTION_MAX_LENGTH, clamped.description.length)
        assertEquals(SubAgentLimits.INSTRUCTIONS_MAX_LENGTH, clamped.instructions.length)
    }

    @Test
    fun `an unknown name does not resolve`() {
        assertNull(SubAgentRoster.resolve("ghost", SubAgentRoster.normalize(emptyList())))
        assertFalse(SubAgentRoster.normalize(emptyList()).any { it.name == "ghost" })
    }

    @Test
    fun `a binding this build cannot read pins nothing`() {
        assertNull(def("a").copy(modelBinding = "not json").pinnedEntryId)
        assertNull(def("a").copy(modelBinding = """{"type":"group","groupId":"g"}""").pinnedEntryId)
    }
}

class SubAgentRosterClampCollisionTest {
    private fun custom(id: String, name: String, updatedAt: Long = 1) = SubAgentDefinition(id = id, name = name, description = "d", instructions = "x", updatedAt = updatedAt)

    @org.junit.Test
    fun `two long names that only differ after the clamp are one name`() {
        val a = "a".repeat(SubAgentLimits.NAME_MAX_LENGTH) + "1"
        val b = "a".repeat(SubAgentLimits.NAME_MAX_LENGTH) + "2"
        val roster = SubAgentRoster.normalize(listOf(custom("1", a), custom("2", b)))
        val customs = roster.filter { !it.isBuiltIn }
        org.junit.Assert.assertEquals("the second collides with the first after clamping", 1, customs.size)
        org.junit.Assert.assertEquals("a".repeat(SubAgentLimits.NAME_MAX_LENGTH), customs.single().name)
    }

    @org.junit.Test
    fun `normalize stays idempotent`() {
        val a = "a".repeat(SubAgentLimits.NAME_MAX_LENGTH) + "1"
        val b = "b".repeat(SubAgentLimits.NAME_MAX_LENGTH + 5)
        val once = SubAgentRoster.normalize(listOf(custom("1", a), custom("2", b)))
        org.junit.Assert.assertEquals(once, SubAgentRoster.normalize(once))
    }

    @org.junit.Test
    fun `a backup merge counts only what survives and skips a clamp collision`() {
        val a = "a".repeat(SubAgentLimits.NAME_MAX_LENGTH) + "1"
        val b = "a".repeat(SubAgentLimits.NAME_MAX_LENGTH) + "2"
        val merge = SubAgentRoster.mergeBackup(
            local = SubAgentRoster.normalize(emptyList()),
            incoming = listOf(custom("1", a), custom("2", b)),
        )
        org.junit.Assert.assertEquals(1, merge.written)
        org.junit.Assert.assertEquals(1, merge.skipped)
        org.junit.Assert.assertEquals(1, merge.roster.count { !it.isBuiltIn })
        org.junit.Assert.assertEquals("what is reported saved is what saving keeps", merge.roster, SubAgentRoster.normalize(merge.roster))
    }
}
