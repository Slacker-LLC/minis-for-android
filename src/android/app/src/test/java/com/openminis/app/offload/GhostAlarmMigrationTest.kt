package com.openminis.app.offload

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GhostAlarmMigrationTest {
    private val now = 1_000_000L

    private fun alarm(label: String, repeat: String = "ONCE", triggerAt: Long = now + 5_000) =
        JSONObject().put("type", "alarm").put("label", label).put("repeatMode", repeat).put("triggerAtMs", triggerAt)

    private fun timer(label: String, triggerAt: Long) =
        JSONObject().put("type", "timer").put("label", label).put("triggerAtMs", triggerAt)

    private fun list(vararg e: JSONObject) = JSONArray().apply { e.forEach { put(it) } }

    @Test
    fun `an expired entry no longer keeps the batch alive and a handed-over one is not resubmitted`() {
        val submitted = mutableListOf<String>()
        val out = GhostAlarmMigration.run(
            list(alarm("expired once", triggerAt = now - 1), alarm("daily", repeat = "DAILY")),
            now,
        ) { submitted += it.getString("label"); true }
        assertEquals(listOf("daily"), submitted)
        assertEquals(0, out.remaining.length())
        assertEquals(1, out.migrated)
        assertEquals(1, out.expired)
    }

    @Test
    fun `only the entries that failed are kept for the retry`() {
        val out = GhostAlarmMigration.run(
            list(alarm("ok-1"), alarm("blocked"), alarm("ok-2"), alarm("throws")),
            now,
        ) { entry ->
            when (entry.getString("label")) {
                "blocked" -> false
                "throws" -> throw IllegalStateException("background start refused")
                else -> true
            }
        }
        assertEquals(2, out.migrated)
        assertEquals(2, out.failed)
        assertEquals(listOf("blocked", "throws"), (0 until out.remaining.length()).map { out.remaining.getJSONObject(it).getString("label") })
    }

    @Test
    fun `expiry rules match the old migration`() {
        assertTrue(GhostAlarmMigration.isExpired(timer("t", now - 1), now))
        assertTrue(GhostAlarmMigration.isExpired(alarm("a", triggerAt = now - 1), now))
        assertFalse("a repeating alarm in the past is still live", GhostAlarmMigration.isExpired(alarm("d", "DAILY", now - 1), now))
        assertFalse(GhostAlarmMigration.isExpired(alarm("future", triggerAt = now + 1), now))
        assertFalse("no trigger time recorded", GhostAlarmMigration.isExpired(alarm("none", triggerAt = 0), now))
    }

    @Test
    fun `a second run over the kept entries sees only those`() {
        val first = GhostAlarmMigration.run(list(alarm("a"), alarm("b")), now) { it.getString("label") == "a" }
        val seen = mutableListOf<String>()
        val second = GhostAlarmMigration.run(first.remaining, now) { seen += it.getString("label"); true }
        assertEquals(listOf("b"), seen)
        assertEquals(0, second.remaining.length())
    }
}
