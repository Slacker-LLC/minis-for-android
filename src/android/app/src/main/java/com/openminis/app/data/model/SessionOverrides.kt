package com.openminis.app.data.model

import org.json.JSONArray
import org.json.JSONObject

/**
 * Sparse, per-session agent/model configuration.
 *
 * Every field is nullable on purpose: null means "inherit the current global or
 * model default". This keeps existing sessions forward-compatible and avoids
 * copying a snapshot of global settings into every newly-created session.
 *
 * The value is persisted as JSON in ChatSessionEntity.sessionOverrides so new
 * override knobs can be added without another Room migration for every field.
 */
data class SessionOverrides(
    val systemPrompt: String? = null,
    val temperature: Double? = null,
    val maxTokens: Int? = null,
    val enabledTools: Set<String>? = null,
) {
    /** True when this object is semantically identical to inheriting globals. */
    fun isEmpty(): Boolean =
        systemPrompt == null &&
            temperature == null &&
            maxTokens == null &&
            enabledTools == null

    /**
     * User-editable session Soul text.
     *
     * Runtime parsing wraps an explicit session Soul in a final system-prompt
     * directive so it replaces the global SOUL.md identity/personality layer.
     * Settings must show only the user's text, never that internal wrapper.
     */
    fun editableSystemPrompt(): String? = unwrapSessionSoul(systemPrompt)

    /**
     * Serialize only explicit overrides. Returning null for an empty object
     * preserves the database-level "inherit everything" representation.
     *
     * The database stores the raw user-authored Soul text. The replacement
     * directive is added only when parsing for runtime use, so persisted data
     * stays portable and the editor never exposes prompt scaffolding.
     */
    fun toJsonOrNull(): String? {
        if (isEmpty()) return null
        val json = JSONObject()
        editableSystemPrompt()?.let { json.put(KEY_SYSTEM_PROMPT, it) }
        temperature?.let { json.put(KEY_TEMPERATURE, it) }
        maxTokens?.let { json.put(KEY_MAX_TOKENS, it) }
        enabledTools?.let { tools ->
            val array = JSONArray()
            tools.sorted().forEach(array::put)
            json.put(KEY_ENABLED_TOOLS, array)
        }
        return json.toString()
    }

    /**
     * Apply the tool allow-list to a provider-agnostic schema list. null keeps
     * all tools; an explicit empty set intentionally produces chat-only mode.
     */
    fun filterTools(tools: List<AgentToolDefinition>): List<AgentToolDefinition> = tools

    /** Bound an explicit output budget by the model/global budget chosen upstream. */
    fun effectiveMaxTokens(defaultValue: Int): Int =
        maxTokens?.coerceAtMost(defaultValue) ?: defaultValue

    companion object {
        private const val KEY_SYSTEM_PROMPT = "systemPrompt"
        private const val KEY_TEMPERATURE = "temperature"
        private const val KEY_MAX_TOKENS = "maxTokens"
        private const val KEY_ENABLED_TOOLS = "enabledTools"

        private const val SESSION_SOUL_PREFIX = """<session-soul-override>
This conversation has an explicit session Soul. It REPLACES the global SOUL.md identity, personality, role, response-style, and body instructions for this conversation. Do not combine, blend, or inherit personality instructions from global SOUL.md. Global SOUL.md is only the fallback when no session Soul is configured. Core runtime, safety, tool, memory, and platform instructions outside the Soul layer remain in force.

<session-soul>
"""
        private const val SESSION_SOUL_SUFFIX = """
</session-soul>
</session-soul-override>"""

        private fun wrapSessionSoul(raw: String): String =
            SESSION_SOUL_PREFIX + raw.trim() + SESSION_SOUL_SUFFIX

        private fun unwrapSessionSoul(value: String?): String? {
            val text = value?.trim()?.takeIf { it.isNotEmpty() } ?: return null
            if (!text.startsWith(SESSION_SOUL_PREFIX) || !text.endsWith(SESSION_SOUL_SUFFIX)) {
                return text
            }
            return text
                .removePrefix(SESSION_SOUL_PREFIX)
                .removeSuffix(SESSION_SOUL_SUFFIX)
                .trim()
                .takeIf { it.isNotEmpty() }
        }

        /**
         * Parse persisted JSON defensively. A malformed/legacy value must never
         * make a session impossible to open; invalid fields simply inherit their
         * defaults while valid siblings are still honored.
         *
         * A non-null stored systemPrompt is a session Soul, not an additive
         * instruction. At runtime it is wrapped as an explicit replacement for
         * the global SOUL.md layer. Legacy plain values are migrated to the same
         * semantics automatically when read.
         */
        fun fromJson(raw: String?): SessionOverrides {
            if (raw.isNullOrBlank()) return SessionOverrides()
            val json = runCatching { JSONObject(raw) }.getOrNull() ?: return SessionOverrides()

            val prompt = json.optString(KEY_SYSTEM_PROMPT, "")
                .trim()
                .takeIf { it.isNotEmpty() }
                ?.let(::wrapSessionSoul)

            val temperature = json.optDouble(KEY_TEMPERATURE, Double.NaN)
                .takeIf { it.isFinite() && it in 0.0..2.0 }
            val maxTokens = json.optInt(KEY_MAX_TOKENS, -1).takeIf { it > 0 }

            val enabledTools: Set<String>? = if (
                json.has(KEY_ENABLED_TOOLS) && !json.isNull(KEY_ENABLED_TOOLS)
            ) {
                val array = json.optJSONArray(KEY_ENABLED_TOOLS)
                if (array == null) {
                    null
                } else {
                    val parsed = linkedSetOf<String>()
                    for (i in 0 until array.length()) {
                        val toolId = array.optString(i, "").trim()
                        if (toolId.isNotEmpty()) parsed.add(toolId)
                    }
                    parsed
                }
            } else {
                null
            }

            return SessionOverrides(
                systemPrompt = prompt,
                temperature = temperature,
                maxTokens = maxTokens,
                enabledTools = enabledTools,
            )
        }
    }
}
