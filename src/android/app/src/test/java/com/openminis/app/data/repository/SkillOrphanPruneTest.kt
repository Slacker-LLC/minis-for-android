package com.openminis.app.data.repository

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SkillOrphanPruneTest {
    private fun prune(
        bundled: Boolean = false,
        migrated: Boolean = true,
        onDisk: List<String>? = listOf("other"),
        hasSkillMd: Boolean = false,
    ) = SkillRepository.shouldPruneOrphan(bundled, migrated, onDisk, "mine") { hasSkillMd }

    @Test
    fun `a missing skill is pruned only once the legacy data has been migrated`() {
        assertTrue(prune())
        assertFalse("before the migration its files may still be in the legacy tree", prune(migrated = false))
    }

    @Test
    fun `bundled skills, an unreadable tree and a present skill are never pruned`() {
        assertFalse(prune(bundled = true))
        assertFalse("a failed listing is not an empty tree", prune(onDisk = null))
        assertFalse(prune(onDisk = listOf("mine"), hasSkillMd = true))
        assertTrue("a directory without SKILL.md is an orphan", prune(onDisk = listOf("mine"), hasSkillMd = false))
    }
}
