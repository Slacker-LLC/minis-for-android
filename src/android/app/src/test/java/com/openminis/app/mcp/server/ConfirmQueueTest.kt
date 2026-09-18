package com.openminis.app.mcp.server

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-android-mcp-server] Pure-logic coverage for the CONFIRM queue (07 §6):
 * TTL, single-use, method binding, sweep, and capacity.
 */
class ConfirmQueueTest {

    private val t0 = 1_000_000L
    private val ttl = ConfirmQueue.DEFAULT_TTL_MILLIS

    private fun queueAt(t: Long = t0) = ConfirmQueue(clock = { t })

    @Test
    fun `issue returns non-null id`() {
        val id = queueAt().issue("pet.feed", "Feed 猫娘")!!
        assertTrue(id.startsWith("c-"))
    }

    @Test
    fun `consume before expiry is OK after approval`() {
        val q = queueAt()
        val id = q.issue("pet.feed", "feed")!!
        assertEquals(ConfirmQueue.Result.OK, q.approve(id, "pet.feed", now = t0 + 1))
        assertEquals(ConfirmQueue.Result.OK, q.consume(id, "pet.feed", now = t0 + 1))
    }

    @Test
    fun `consume after expiry is EXPIRED`() {
        val q = queueAt()
        val id = q.issue("pet.feed", "feed")!!
        assertEquals(
            ConfirmQueue.Result.EXPIRED,
            q.consume(id, "pet.feed", now = t0 + ttl + 1),
        )
    }

    @Test
    fun `second consume is REUSED`() {
        val q = queueAt()
        val id = q.issue("pet.feed", "feed")!!
        assertEquals(ConfirmQueue.Result.OK, q.approve(id, "pet.feed", now = t0 + 1))
        assertEquals(ConfirmQueue.Result.OK, q.consume(id, "pet.feed", now = t0 + 1))
        assertEquals(ConfirmQueue.Result.REUSED, q.consume(id, "pet.feed", now = t0 + 1))
    }

    @Test
    fun `consume with wrong method is WRONG_METHOD and still consumable`() {
        val q = queueAt()
        val id = q.issue("pet.feed", "feed")!!
        assertEquals(
            ConfirmQueue.Result.WRONG_METHOD,
            q.consume(id, "pet.play", now = t0 + 1),
        )
        assertEquals(ConfirmQueue.Result.OK, q.approve(id, "pet.feed", now = t0 + 1))
        assertEquals(ConfirmQueue.Result.OK, q.consume(id, "pet.feed", now = t0 + 1))
    }

    @Test
    fun `consume unknown id is UNKNOWN`() {
        assertEquals(ConfirmQueue.Result.UNKNOWN, queueAt().consume("c-nope", "pet.feed", now = t0))
    }

    @Test
    fun `sweep removes only expired and returns count`() {
        var now = t0
        val q = ConfirmQueue(clock = { now })
        val expired = q.issue("pet.feed", "old")!!
        now = t0 + 10
        val fresh = q.issue("pet.feed", "new")!!
        val cleared = q.sweep(now = t0 + ttl + 1)
        assertEquals(1, cleared)
        assertEquals(ConfirmQueue.Result.UNKNOWN, q.consume(expired, "pet.feed", now = t0))
        assertEquals(ConfirmQueue.Result.OK, q.approve(fresh, "pet.feed", now = t0))
        assertEquals(ConfirmQueue.Result.OK, q.consume(fresh, "pet.feed", now = t0))
    }

    @Test
    fun `issue returns null at capacity`() {
        val q = queueAt()
        repeat(ConfirmQueue.DEFAULT_CAPACITY) { i ->
            assertNotNull(q.issue("pet.feed", "feed $i"))
        }
        assertNull(q.issue("pet.feed", "overflow"))
    }

    @Test
    fun `issue succeeds again after sweep frees capacity`() {
        val q = queueAt()
        repeat(ConfirmQueue.DEFAULT_CAPACITY) { i -> q.issue("pet.feed", "feed $i") }
        q.sweep(now = t0 + ttl + 1)
        assertNotNull(q.issue("pet.feed", "after sweep"))
    }

    @Test
    fun `issue auto-sweeps expired entries before capacity check`() {
        var now = t0
        val q = ConfirmQueue(clock = { now })
        repeat(ConfirmQueue.DEFAULT_CAPACITY) { i -> q.issue("pet.feed", "stale $i") }
        now = t0 + ttl + 1
        assertNotNull(q.issue("pet.feed", "after auto sweep"))
    }

    // --- approval state machine: PENDING / approve / reject (07 §6) ---

    @Test
    fun `consume without approval is PENDING and stays pending`() {
        val q = queueAt()
        val id = q.issue("pet.feed", "feed")!!
        assertEquals(ConfirmQueue.Result.PENDING, q.consume(id, "pet.feed", now = t0 + 1))
        assertEquals(ConfirmQueue.Result.PENDING, q.consume(id, "pet.feed", now = t0 + 1))
        assertEquals(ConfirmQueue.Result.OK, q.approve(id, "pet.feed", now = t0 + 1))
        assertEquals(ConfirmQueue.Result.OK, q.consume(id, "pet.feed", now = t0 + 1))
    }

    @Test
    fun `reject marks entry REJECTED and blocks reuse`() {
        val q = queueAt()
        val id = q.issue("pet.feed", "feed")!!
        assertEquals(ConfirmQueue.Result.OK, q.reject(id, "pet.feed", now = t0 + 1))
        assertEquals(ConfirmQueue.Result.REJECTED, q.consume(id, "pet.feed", now = t0 + 1))
        assertEquals(ConfirmQueue.Result.REJECTED, q.consume(id, "pet.feed", now = t0 + 1))
        assertEquals(ConfirmQueue.Result.REUSED, q.approve(id, "pet.feed", now = t0 + 1))
    }

    @Test
    fun `approve after reject is REUSED`() {
        val q = queueAt()
        val id = q.issue("pet.feed", "feed")!!
        q.reject(id, "pet.feed", now = t0 + 1)
        assertEquals(ConfirmQueue.Result.REUSED, q.approve(id, "pet.feed", now = t0 + 1))
        assertEquals(ConfirmQueue.Result.REUSED, q.reject(id, "pet.feed", now = t0 + 1))
    }

    @Test
    fun `approve with wrong method is WRONG_METHOD and approval survives`() {
        val q = queueAt()
        val id = q.issue("pet.feed", "feed")!!
        assertEquals(ConfirmQueue.Result.WRONG_METHOD, q.approve(id, "pet.play", now = t0 + 1))
        assertEquals(ConfirmQueue.Result.OK, q.approve(id, "pet.feed", now = t0 + 1))
        assertEquals(ConfirmQueue.Result.OK, q.consume(id, "pet.feed", now = t0 + 1))
    }

    @Test
    fun `approve after expiry is EXPIRED and consumed replay is REUSED`() {
        val q = queueAt()
        val id = q.issue("pet.feed", "feed")!!
        assertEquals(ConfirmQueue.Result.EXPIRED, q.approve(id, "pet.feed", now = t0 + ttl + 1))
        assertEquals(ConfirmQueue.Result.REUSED, q.consume(id, "pet.feed", now = t0 + ttl + 1))
    }

    @Test
    fun `reject unknown id is UNKNOWN`() {
        assertEquals(ConfirmQueue.Result.UNKNOWN, queueAt().reject("c-nope", "pet.feed", now = t0))
        assertEquals(ConfirmQueue.Result.UNKNOWN, queueAt().approve("c-nope", "pet.feed", now = t0))
    }

    @Test
    fun `sweep clears rejected entries back to UNKNOWN`() {
        val q = queueAt()
        val id = q.issue("pet.feed", "feed")!!
        q.reject(id, "pet.feed", now = t0 + 1)
        q.sweep(now = t0 + ttl + 1)
        assertEquals(ConfirmQueue.Result.UNKNOWN, q.consume(id, "pet.feed", now = t0))
    }
}
