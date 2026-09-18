package com.openminis.app.data.model

import org.json.JSONArray
import org.json.JSONObject

/** The widest wire name OpenAI-compatible endpoints accept for `tools[].name`. */
private const val WIRE_NAME_MAX_CHARS = 64

/** Characters in the digest a cut (or unreadable) wire name ends with. */
private const val DIGEST_CHARS = 8

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
     *
     * [WIRE_NAME_MAX_CHARS] is a hard cap, so a name longer than that is cut — and a
     * cut alone would let two different tools end up with the SAME wire name (their
     * first 64 characters agreeing, e.g. two long remote tool names on one server).
     * The local registry maps wire names to canonical ones, so the collision would
     * silently dispatch the model's call to the wrong tool. Long names therefore end
     * in a short digest of the canonical name, which is the scheme Eta uses for the
     * same reason (`agent/mcp/McpRunContext.kt`, Mangi-11/Eta @ c15de97).
     */
    val apiName: String
        get() {
            val sanitized = name.replace(Regex("[^a-zA-Z0-9_-]"), "_")
            if (sanitized.none { it.isLetterOrDigit() }) return "tool_" + nameDigest()
            if (sanitized.length <= WIRE_NAME_MAX_CHARS) return sanitized
            return sanitized.take(WIRE_NAME_MAX_CHARS - DIGEST_CHARS - 1) + "_" + nameDigest()
        }

    /**
     * Names that sanitize to punctuation only (`。。。` → `___`) are legal but carry
     * nothing to tell two tools apart, so they get the digest instead of the underscores.
     */
    private fun nameDigest(): String = java.security.MessageDigest.getInstance("SHA-256")
        .digest(name.toByteArray(Charsets.UTF_8))
        .take(4)
        .joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }

    /** True when [candidate] matches canonical name, wire apiName, or normalized alphanumeric name. */
    fun matchesName(candidate: String): Boolean {
        if (name.equals(candidate, ignoreCase = true) || apiName.equals(candidate, ignoreCase = true)) {
            return true
        }
        val normCandidate = candidate.lowercase().filter { it.isLetterOrDigit() }
        if (normCandidate.isEmpty()) return false
        val normName = name.lowercase().filter { it.isLetterOrDigit() }
        val normApi = apiName.lowercase().filter { it.isLetterOrDigit() }
        return normCandidate == normName || normCandidate == normApi
    }

    /** Anthropic format: {name, description, input_schema: {type:object, properties, required}} */
    fun toAnthropicJson(): JSONObject {
        return JSONObject().apply {
            put("name", apiName)
            put("description", description)
            put("input_schema", inputSchemaJson())
        }
    }

    /**
     * MCP `tools/list` shape: same name/description/schema, but the schema field
     * is `inputSchema` — the spelling the MCP spec uses. The Anthropic shape
     * above spells it `input_schema`; a spec client that reads only `inputSchema`
     * saw every tool with no parameters and had to guess argument names
     * (measured on device: all 58 exposed tools came back with the snake key
     * alone, and `linux_file_copy` answered a call that used the wrong names).
     */
    fun toMcpJson(): JSONObject = JSONObject().apply {
        put("name", apiName)
        put("description", description)
        put("inputSchema", inputSchemaJson())
    }

    /** The JSON-Schema object the Anthropic and MCP shapes share. */
    private fun inputSchemaJson(): JSONObject {
        val props = JSONObject()
        for ((key, param) in parameters) {
            props.put(key, param.toJson())
        }
        return JSONObject().apply {
            put("type", "object")
            put("properties", props)
            if (required.isNotEmpty()) put("required", JSONArray(required))
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
