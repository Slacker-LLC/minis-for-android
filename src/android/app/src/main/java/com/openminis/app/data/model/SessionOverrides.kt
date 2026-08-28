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
     * Serialize only explicit overrides. Returning null for an empty object
     * preserves the database-level "inherit everything" representation.
     */
    fun toJsonOrNull(): String? {
        if (isEmpty()) return null
        val json = JSONObject()
        systemPrompt?.let { json.put(KEY_SYSTEM_PROMPT, it) }
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
    fun filterTools(tools: List<AgentToolDefinition>): List<AgentToolDefinition> {
        val allow = enabledTools ?: return tools
        return tools.filter { it.name in allow }
    }

    /** Bound an explicit output budget by the model/global budget chosen upstream. */
    fun effectiveMaxTokens(defaultValue: Int): Int =
        maxTokens?.coerceAtMost(defaultValue) ?: defaultValue

    companion object {
        private const val KEY_SYSTEM_PROMPT = "systemPrompt"
        private const val KEY_TEMPERATURE = "temperature"
        private const val KEY_MAX_TOKENS = "maxTokens"
        private const val KEY_ENABLED_TOOLS = "enabledTools"

        /**
         * Parse persisted JSON defensively. A malformed/legacy value must never
         * make a session impossible to open; invalid fields simply inherit their
         * defaults while valid siblings are still honored.
         */
        fun fromJson(raw: String?): SessionOverrides {
            if (raw.isNullOrBlank()) return SessionOverrides()
            val json = runCatching { JSONObject(raw) }.getOrNull() ?: return SessionOverrides()

            val prompt = json.optString(KEY_SYSTEM_PROMPT, "")
                .trim()
                .takeIf { it.isNotEmpty() }

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
