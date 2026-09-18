package com.openminis.app.mcp.server

import android.content.Context

/**
 * [T-android-mcp-server] Token store for the on-device MCP server (07 §4).
 *
 * Each token: `id` (scoped), optional subset of allowed tools. Stored in
 * SharedPreferences. No token configured → [isConfigured] false → the
 * manager refuses to start (fail-closed).
 */
object TokenStore {

    private const val PREFS = "minis_mcp_prefs"
    private const val KEY_TOKENS = "tokens_json"

    data class Token(
        val id: String,
        val token: String,
        /** Empty = legacy/unrestricted subset: all MCP-visible tools. */
        val scope: Set<String> = emptySet(),
    )

    @Volatile
    private var appContext: Context? = null

    /** In-memory fallback (tests + before init). */
    @Volatile
    private var inMemory: List<Token> = emptyList()

    fun setInMemoryForTest(tokens: List<Token>) {
        inMemory = tokens
    }

    fun init(context: Context) {
        appContext = context.applicationContext
    }

    fun all(): List<Token> {
        val ctx = appContext
        if (ctx == null) return inMemory
        val raw = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_TOKENS, null)
            ?: return inMemory
        return runCatching {
            val arr = org.json.JSONArray(raw)
            (0 until arr.length()).mapNotNull { i ->
                val o = arr.optJSONObject(i) ?: return@mapNotNull null
                val scopeArr = o.optJSONArray("scope")
                val scope = if (scopeArr == null) emptySet()
                else (0 until scopeArr.length()).map { j -> scopeArr.getString(j) }.toSet()
                Token(
                    id = o.optString("id"),
                    token = o.optString("token"),
                    scope = scope,
                )
            }
        }.getOrDefault(inMemory)
    }

    fun save(tokens: List<Token>) {
        // Keep the fallback authoritative too. Besides making the pure JVM tests
        // realistic, this avoids stale reads if persistence is temporarily
        // unavailable before the application Context has been wired.
        inMemory = tokens.toList()
        val ctx = appContext ?: return
        val arr = org.json.JSONArray()
        for (t in tokens) {
            val o = org.json.JSONObject()
            o.put("id", t.id)
            o.put("token", t.token)
            val scopeArr = org.json.JSONArray()
            t.scope.forEach { scopeArr.put(it) }
            o.put("scope", scopeArr)
            arr.put(o)
        }
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY_TOKENS, arr.toString()).apply()
    }

    fun find(tokenValue: String): Token? = all().firstOrNull { it.token == tokenValue }

    fun findById(id: String): Token? = all().firstOrNull { it.id == id }

    /** Replace only the token with the same id; unrelated credentials survive. */
    fun upsert(token: Token) {
        val next = all().filterNot { it.id == token.id } + token
        save(next)
    }

    /** Revoke one credential by id. Returns true only when something changed. */
    fun remove(id: String): Boolean {
        val current = all()
        val next = current.filterNot { it.id == id }
        if (next.size == current.size) return false
        save(next)
        return true
    }

    /** Fail-closed gate: no tokens → server must not start. */
    val isConfigured: Boolean get() = all().isNotEmpty()
}
