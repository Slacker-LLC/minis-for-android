package com.openminis.app.tools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-eta-xposed-groups] Ported from Eta `agent/tool/AgentPrivateDatabaseTools.kt` (Mangi-11/Eta @
 * c15de97). These are the decisions that stand between a caller and somebody else's database file:
 * what may be copied, what may be queried, and how much may come back.
 */
class PrivateDatabaseRulesTest {

    @Test
    fun `the row count is clamped into the declared range`() {
        assertEquals(PrivateDatabaseRules.DEFAULT_LIMIT, PrivateDatabaseRules.clampLimit(null))
        assertEquals(1, PrivateDatabaseRules.clampLimit(0))
        assertEquals(PrivateDatabaseRules.MAX_LIMIT, PrivateDatabaseRules.clampLimit(1_000))
    }

    @Test
    fun `a file past the cap is refused, one at the cap is not`() {
        val cap = 32L * 1024 * 1024

        assertFalse(PrivateDatabaseRules.exceedsSizeCap(cap, cap))
        assertTrue(PrivateDatabaseRules.exceedsSizeCap(cap + 1, cap))
    }

    @Test
    fun `a path that readlink prints is a link and is not copied`() {
        assertTrue(PrivateDatabaseRules.isSymlink("/data/user_de/0/com.example/databases/db\n"))
        assertTrue(PrivateDatabaseRules.isSymlink("/storage/emulated/0/x"))
        assertFalse("readlink prints nothing for a real file", PrivateDatabaseRules.isSymlink(""))
        assertFalse(PrivateDatabaseRules.isSymlink("   "))
    }

    @Test
    fun `a table must carry every column the query needs`() {
        assertTrue(
            PrivateDatabaseRules.hasColumns(
                available = setOf("_id", "hour", "minutes", "enabled", "message"),
                required = setOf("_id", "hour", "minutes", "enabled"),
            ),
        )
        assertFalse(
            "a build without the enabled column is a different schema",
            PrivateDatabaseRules.hasColumns(
                available = setOf("_id", "hour", "minutes"),
                required = setOf("_id", "hour", "minutes", "enabled"),
            ),
        )
        assertFalse(
            PrivateDatabaseRules.hasColumns(emptySet(), setOf("_id")),
        )
    }

    @Test
    fun `a keyword is escaped before it becomes a pattern`() {
        assertEquals("50\\%", PrivateDatabaseRules.escapeLike("50%"))
        assertEquals("a\\_b", PrivateDatabaseRules.escapeLike("a_b"))
        assertEquals("c:\\\\path", PrivateDatabaseRules.escapeLike("c:\\path"))
        assertEquals("plain", PrivateDatabaseRules.escapeLike("plain"))
    }
}
