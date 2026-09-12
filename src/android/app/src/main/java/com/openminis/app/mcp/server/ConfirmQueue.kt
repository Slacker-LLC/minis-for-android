package com.openminis.app.mcp.server

import org.json.JSONArray
import org.json.JSONObject
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.UUID

/**
 * [T-android-mcp-server] In-memory queue for CONFIRM-level tool calls (07 §6).
 *
 * Flow: issue → Pending → user approves/rejects → client re-sends with
 * confirm_id → [consume] gates execution. Pure Kotlin, no Android deps.
 *
 * Approval is a two-step protocol: [approve]/[reject] flip the pending entry
 * (rejected ones move to [rejected] so [consume] reads REJECTED and replays
 * stay blocked), and [consume] only executes approved entries. An un-answered
 * entry consumes as PENDING, telling the client to wait for the user.
 */
class ConfirmQueue(
    private val ttlMillis: Long = DEFAULT_TTL_MILLIS,
    private val capacity: Int = DEFAULT_CAPACITY,
    private val clock: () -> Long = System::currentTimeMillis,
) {

    enum class Result {
        OK, PENDING, REJECTED, EXPIRED, WRONG_METHOD, WRONG_CALLER, WRONG_REQUEST, UNKNOWN, REUSED,
    }

    data class Pending(
        val id: String,
        val method: String,
        val summary: String,
        val expiresAt: Long,
        val approved: Boolean = false,
        /** Stable MCP caller identity; never the bearer secret itself. */
        val caller: String = "",
        /** Digest of the complete canonical method + arguments request. */
        val requestKey: String = "",
    )

    private val pending = LinkedHashMap<String, Pending>()
    /** Consumed ids → their expiresAt, so replays read REUSED until sweep evicts them. */
    private val used = HashMap<String, Long>()
    /** Rejected ids → their expiresAt, so replays read REJECTED until sweep evicts them. */
    private val rejected = HashMap<String, Long>()

    /**
     * Creates a pending confirm. Returns its id, or null when the queue is
     * full (capacity exhausted, including not-yet-swept expired entries).
     */
    @Synchronized
    fun issue(
        method: String,
        summary: String,
        caller: String = "",
        requestKey: String = "",
    ): String? {
        // A3: evict expired entries first so unanswered confirms cannot
        // permanently exhaust capacity.
        sweep(now = clock())
        if (pending.size >= capacity) return null
        val id = "c-" + UUID.randomUUID().toString()
        pending[id] = Pending(
            id = id,
            method = method,
            summary = summary,
            expiresAt = clock() + ttlMillis,
            caller = caller,
            requestKey = requestKey,
        )
        return id
    }

    /**
     * Consumes one pending confirm. A confirm can only be consumed once
     * (REUSED afterwards), and only for the method it was issued for.
     * Un-answered entries consume as PENDING: the client must wait until the
     * user approves (or gives up on a reject, which reads REJECTED).
     */
    @Synchronized
    fun consume(
        id: String,
        method: String,
        now: Long = System.currentTimeMillis(),
        caller: String = "",
        requestKey: String = "",
    ): Result {
        if (id in rejected) return Result.REJECTED
        if (id in used) return Result.REUSED
        val entry = pending[id] ?: return Result.UNKNOWN
        if (entry.method != method) return Result.WRONG_METHOD
        if (entry.caller.isNotEmpty() && entry.caller != caller) return Result.WRONG_CALLER
        if (entry.requestKey.isNotEmpty() && entry.requestKey != requestKey) return Result.WRONG_REQUEST
        if (now > entry.expiresAt) {
            pending.remove(id)
            used[id] = entry.expiresAt
            return Result.EXPIRED
        }
        if (!entry.approved) return Result.PENDING
        pending.remove(id)
        used[id] = entry.expiresAt
        return Result.OK
    }

    /**
     * Approves a pending confirm (the notification's 批准 action). Idempotent
     * on already-approved entries: OK again. Rejected/consumed ids read REUSED.
     */
    @Synchronized
    fun approve(id: String, method: String, now: Long = System.currentTimeMillis()): Result {
        if (id in rejected || id in used) return Result.REUSED
        val entry = pending[id] ?: return Result.UNKNOWN
        if (entry.method != method) return Result.WRONG_METHOD
        if (now > entry.expiresAt) {
            pending.remove(id)
            used[id] = entry.expiresAt
            return Result.EXPIRED
        }
        pending[id] = entry.copy(approved = true)
        return Result.OK
    }

    /**
     * Rejects a pending confirm (the notification's 拒绝 action). The entry
     * leaves [pending] and enters [rejected], so the id can never be reused
     * and [consume] reads REJECTED until sweep evicts it.
     */
    @Synchronized
    fun reject(id: String, method: String, now: Long = System.currentTimeMillis()): Result {
        if (id in rejected || id in used) return Result.REUSED
        val entry = pending[id] ?: return Result.UNKNOWN
        if (entry.method != method) return Result.WRONG_METHOD
        pending.remove(id)
        rejected[id] = entry.expiresAt
        return if (now > entry.expiresAt) Result.EXPIRED else Result.OK
    }

    /** Removes expired entries (pending, consumed and rejected); returns how many were cleared. */
    @Synchronized
    fun sweep(now: Long = System.currentTimeMillis()): Int {
        val before = pending.size + used.size + rejected.size
        pending.entries.removeAll { now > it.value.expiresAt }
        used.entries.removeAll { now > it.value }
        rejected.entries.removeAll { now > it.value }
        return before - (pending.size + used.size + rejected.size)
    }

    companion object {
        const val DEFAULT_TTL_MILLIS = 120_000L
        const val DEFAULT_CAPACITY = 64

        /**
         * The live server queue, so McpConfirmReceiver (instantiated by the
         * system, no handle on MCPServer) can answer from the notification.
         * Constructor-registered: the server creates exactly one queue in
         * prod; test queues overwriting it is harmless.
         */
        @Volatile
        var shared: ConfirmQueue? = null

        /**
         * Binds a ticket to the authenticated MCP caller, method, and every
         * argument. The bearer secret itself is never stored or logged.
         */
        fun requestKey(caller: String, method: String, params: JSONObject): String {
            val canonical = canonicalJson(params)
            val input = "$caller\u0000$method\u0000$canonical"
                .toByteArray(StandardCharsets.UTF_8)
            val digest = MessageDigest.getInstance("SHA-256").digest(input)
            val hex = "0123456789abcdef"
            return buildString(digest.size * 2) {
                digest.forEach { byte ->
                    val value = byte.toInt() and 0xff
                    append(hex[value ushr 4])
                    append(hex[value and 0x0f])
                }
            }
        }

        /** Deterministic JSON encoding so object key order cannot alter a ticket. */
        private fun canonicalJson(value: Any?): String = when (value) {
            null, JSONObject.NULL -> "null"
            is JSONObject -> {
                val keys = mutableListOf<String>()
                value.keys().forEach { keys += it }
                keys.sort()
                keys.joinToString(prefix = "{", postfix = "}") { key ->
                    JSONObject.quote(key) + ":" + canonicalJson(value.opt(key))
                }
            }
            is JSONArray -> (0 until value.length())
                .joinToString(prefix = "[", postfix = "]") { index -> canonicalJson(value.opt(index)) }
            is String -> JSONObject.quote(value)
            is Number, is Boolean -> value.toString()
            else -> JSONObject.quote(value.toString())
        }
    }

    init {
        shared = this
    }
}
