package com.openminis.app.mcp.server

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Attack cases for the CONFIRM gate (T-android-mcp-server 07 §6): forged
 * ids, replays, and cross-tool theft attempts must never execute.
 *
 * A ticket is bound to the authenticated caller and complete request digest in
 * addition to the method. This prevents a leaked id from being replayed by
 * another MCP token or with changed arguments.
 */
class ConfirmQueueSecurityTest {

    private val t0 = 1_000_000L
    private val ttl = ConfirmQueue.DEFAULT_TTL_MILLIS

    private fun queueAt(t: Long = t0) = ConfirmQueue(clock = { t })

    @Test
    fun `forged confirm ids are UNKNOWN on every gate`() {
        val q = queueAt()
        for (id in listOf(
            "c-00000000-0000-0000-0000-000000000000",
            "c-" + "a".repeat(36),
            "",
            "..",
            "c-00000000-0000-0000-0000-000000000000\u0000",
        )) {
            assertEquals(ConfirmQueue.Result.UNKNOWN, q.consume(id, "pet.feed", now = t0))
            assertEquals(ConfirmQueue.Result.UNKNOWN, q.approve(id, "pet.feed", now = t0))
            assertEquals(ConfirmQueue.Result.UNKNOWN, q.reject(id, "pet.feed", now = t0))
        }
    }

    @Test
    fun `replaying a consumed confirm id never executes again`() {
        val q = queueAt()
        val id = q.issue("pet.feed", "feed")!!
        q.approve(id, "pet.feed", now = t0 + 1)
        assertEquals(ConfirmQueue.Result.OK, q.consume(id, "pet.feed", now = t0 + 1))
        // replay with the right method, before expiry, still refused
        for (i in 1..3) {
            assertEquals(ConfirmQueue.Result.REUSED, q.consume(id, "pet.feed", now = t0 + 1))
        }
        assertEquals(ConfirmQueue.Result.REUSED, q.approve(id, "pet.feed", now = t0 + 1))
        assertEquals(ConfirmQueue.Result.REUSED, q.reject(id, "pet.feed", now = t0 + 1))
    }

    @Test
    fun `a confirm issued for one tool cannot be used by another`() {
        val q = queueAt()
        val id = q.issue("pet.feed", "feed")!!
        q.approve(id, "pet.feed", now = t0 + 1)
        // attacker tries to spend the approved confirm on a different tool
        assertEquals(ConfirmQueue.Result.WRONG_METHOD, q.consume(id, "pet.play", now = t0 + 1))
        assertEquals(
            ConfirmQueue.Result.WRONG_METHOD,
            q.consume(id, "android.input.text", now = t0 + 1),
        )
        // and can neither approve nor burn it on their behalf
        assertEquals(ConfirmQueue.Result.WRONG_METHOD, q.approve(id, "pet.play", now = t0 + 1))
        assertEquals(ConfirmQueue.Result.WRONG_METHOD, q.reject(id, "pet.play", now = t0 + 1))
        // the real owner can still use it exactly once
        assertEquals(ConfirmQueue.Result.OK, q.consume(id, "pet.feed", now = t0 + 1))
    }

    @Test
    fun `a confirm is bound to caller and complete request`() {
        val q = queueAt()
        val id = q.issue(
            method = "android.deploy",
            summary = "install",
            caller = "mcp:owner",
            requestKey = "request-a",
        )!!
        q.approve(id, "android.deploy", now = t0 + 1)

        assertEquals(
            ConfirmQueue.Result.WRONG_CALLER,
            q.consume(id, "android.deploy", now = t0 + 1, caller = "mcp:other", requestKey = "request-a"),
        )
        assertEquals(
            ConfirmQueue.Result.WRONG_REQUEST,
            q.consume(id, "android.deploy", now = t0 + 1, caller = "mcp:owner", requestKey = "request-b"),
        )
        assertEquals(
            ConfirmQueue.Result.OK,
            q.consume(id, "android.deploy", now = t0 + 1, caller = "mcp:owner", requestKey = "request-a"),
        )
    }

    @Test
    fun `request key is independent of JSON object insertion order`() {
        val first = org.json.JSONObject().put("action", "install").put("artifactPath", "/tmp/a.apk")
        val second = org.json.JSONObject().put("artifactPath", "/tmp/a.apk").put("action", "install")

        assertEquals(
            ConfirmQueue.requestKey("mcp:t1", "android.deploy", first),
            ConfirmQueue.requestKey("mcp:t1", "android.deploy", second),
        )
        assertTrue(
            ConfirmQueue.requestKey("mcp:t1", "android.deploy", first) !=
                ConfirmQueue.requestKey(
                    "mcp:t1",
                    "android.deploy",
                    org.json.JSONObject().put("action", "launch").put("artifactPath", "/tmp/a.apk"),
                ),
        )
    }

    @Test
    fun `consumed confirm stays REUSED until sweep evicts it`() {
        val q = queueAt()
        val id = q.issue("pet.feed", "feed")!!
        q.approve(id, "pet.feed", now = t0 + 1)
        assertEquals(ConfirmQueue.Result.OK, q.consume(id, "pet.feed", now = t0 + 1))
        assertEquals(ConfirmQueue.Result.REUSED, q.consume(id, "pet.feed", now = t0 + ttl))
        q.sweep(now = t0 + ttl + 1)
        assertEquals(ConfirmQueue.Result.UNKNOWN, q.consume(id, "pet.feed", now = t0))
    }
}
