package com.openminis.app.data.model

import org.json.JSONArray
import org.json.JSONObject

/**
 * Provider-agnostic tool definition. Each tool registers with this structure,
 * and providers convert it to their native format (Anthropic input_schema,
 * Gemini function_declarations, OpenAI function calling).
 */
data class AgentToolDefinition(
    val name: String,
    val description: String,
    val parameters: Map<String, AgentToolParam>,
    val required: List<String> = emptyList(),
    val propertyOrdering: List<String>? = null,
    /**
     * Per-tool-call budget in milliseconds (DeepSeek Harness
     * dsh-tool-call-timeout-policy contract). When set, the executor wraps
     * the dispatch in a cooperative deadline and returns a structured
     * TOOL_TIMEOUT result (timedOut=true) instead of hanging the turn.
     * Tools that manage their own timeout (shell_execute, subagent,
     * ask_user_question) leave this null.
     */
    val timeoutMs: Long? = null,
) {
    /**
     * Provider wire name. OpenAI-compatible endpoints (DeepSeek, Kimi, GLM…)
     * validate `tools[].name` against `^[a-zA-Z0-9_-]{1,64}$`, so the local
     * `mcp.<server>.<tool>` naming scheme (dots) is rejected with
     * `400 Invalid 'tools[N].name'`. Local dispatch keeps the canonical dotted
     * name; only the serialized wire form is sanitized.
     */
    val apiName: String
        get() = name
            .replace(Regex("[^a-zA-Z0-9_-]"), "_")
            .take(64)
            .ifEmpty { "tool" }

    /** True when [candidate] is either the canonical local name or the wire name. */
    fun matchesName(candidate: String): Boolean = name == candidate || apiName == candidate

    /** Anthropic format: {name, description, input_schema: {type:object, properties, required}} */
    fun toAnthropicJson(): JSONObject {
        val props = JSONObject()
        for ((key, param) in parameters) {
            props.put(key, param.toJson())
        }
        val schema = JSONObject().apply {
            put("type", "object")
            put("properties", props)
            if (required.isNotEmpty()) put("required", JSONArray(required))
        }
        return JSONObject().apply {
            put("name", apiName)
            put("description", description)
            put("input_schema", schema)
        }
    }

    /** Gemini format: {name, description, parameters: {type:OBJECT, properties, required}} */
    fun toGeminiJson(): JSONObject {
        val props = JSONObject()
        for ((key, param) in parameters) {
            props.put(key, param.toGeminiJson())
        }
        val params = JSONObject().apply {
            put("type", "OBJECT")
            put("properties", props)
            if (required.isNotEmpty()) put("required", JSONArray(required))
            if (propertyOrdering != null) put("propertyOrdering", JSONArray(propertyOrdering))
        }
        return JSONObject().apply {
            put("name", apiName)
            put("description", description)
            put("parameters", params)
        }
    }

    /** OpenAI format: {type:function, function: {name, description, parameters: {type:object, ...}}} */
    fun toOpenAIJson(): JSONObject {
        val props = JSONObject()
        for ((key, param) in parameters) {
            props.put(key, param.toJson())
        }
        val params = JSONObject().apply {
            put("type", "object")
            put("properties", props)
            if (required.isNotEmpty()) put("required", JSONArray(required))
        }
        return JSONObject().apply {
            put("type", "function")
            put("function", JSONObject().apply {
                put("name", apiName)
                put("description", description)
                put("parameters", params)
            })
        }
    }
}

data class AgentToolParam(
    val type: String,
    val description: String,
    val enumValues: List<String>? = null,
    /** Optional JSON-schema item definition for array parameters. */
    val items: AgentToolParam? = null,
    /** Optional JSON-schema properties for object parameters. */
    val properties: Map<String, AgentToolParam>? = null,
    /** Required keys when [type] is object. */
    val requiredProperties: List<String>? = null,
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("type", type)
        put("description", description)
        if (enumValues != null) put("enum", JSONArray(enumValues))
        if (items != null) put("items", items.toJson())
        if (properties != null) {
            val props = JSONObject()
            for ((key, value) in properties) props.put(key, value.toJson())
            put("properties", props)
        }
        if (!requiredProperties.isNullOrEmpty()) put("required", JSONArray(requiredProperties))
    }

    fun toGeminiJson(): JSONObject = JSONObject().apply {
        put("type", type.uppercase())
        put("description", description)
        if (enumValues != null) put("enum", JSONArray(enumValues))
        if (items != null) put("items", items.toGeminiJson())
        if (properties != null) {
            val props = JSONObject()
            for ((key, value) in properties) props.put(key, value.toGeminiJson())
            put("properties", props)
        }
        if (!requiredProperties.isNullOrEmpty()) put("required", JSONArray(requiredProperties))
    }
}
