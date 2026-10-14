package com.openminis.app.tools.runtime

import com.openminis.app.data.model.AgentToolDefinition
import com.openminis.app.data.model.AgentToolParam
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.floor

/** A schema-conformance failure for one tool call. [message] is safe to hand
 *  back to the model: it names the offending path and never echoes more than a
 *  short snippet of the caller's value. */
internal data class ToolArgumentRejection(val code: String, val message: String)

/**
 * [T-android-tool-arg-schema] Conformance check for tool arguments against the
 * schema the model was actually given this turn.
 *
 * Preflight already rejects absent / null / empty-string required fields but
 * never looked at the declared types, so a model could send any shape for any
 * parameter and the failure surfaced deep inside a tool helper — or not at all.
 * This validator closes that gap for the parts [AgentToolParam] can express:
 * scalar type, `enum` membership, array element type, and nested object
 * properties plus their required keys.
 *
 * Deliberate limits:
 *
 * - It is coercion-aware. Numeric/boolean strings from providers remain accepted;
 *   `ToolJsonRepair` only coerces fields whose schema declares a string.
 * - It does not reject undeclared keys. Tool helpers read their own optional
 *   fields, and `tool_title` is injected uniformly across tools, so key
 *   strictness would reject calls that work today.
 * - `AgentToolParam` carries no numeric bounds or `additionalProperties`, so
 *   those schema features cannot be enforced here.
 *
 * Pure function: no I/O, no mutation of [args], no permission decisions.
 */
internal object ToolCallValidator {

    val NON_BLOCKING_FIELDS = setOf("tool_title")
    val EMPTY_STRING_ALLOWED_FIELDS = mapOf(
        "file_edit" to setOf("new_string"),
        "linux.file.edit" to setOf("new_string"),
        "update_goal" to setOf("goal"),
        "agent.goal" to setOf("goal"),
    )
    fun preflightEmptyStringAllowed(tool: String, field: String): Boolean =
        EMPTY_STRING_ALLOWED_FIELDS[tool]?.contains(field) == true

        fun preflight(
            name: String,
            args: JSONObject,
            tools: List<AgentToolDefinition>,
        ): String? {
            // Unknown tool names go through to the existing `else` branch in
            // executeTool() which returns "Unknown tool: …". Preflight stays
            // silent so we don't double-fail.
            val toolDef = tools.firstOrNull { it.matchesName(name) } ?: return null
            // Required fields that actually gate execution (everything except the
            // non-blocking ones like tool_title — see NON_BLOCKING_FIELDS).
            val legacyEdit = toolDef.name in setOf("file_edit", "linux.file.edit") && !args.has("edits") &&
                args.has("old_string") && !args.isNull("old_string") && args.has("new_string") && !args.isNull("new_string")
            val enforced = toolDef.required.filter { it !in NON_BLOCKING_FIELDS && !(legacyEdit && it == "edits") }
            // Empty args on a tool that requires anything → block. Gate on
            // `enforced` so a tool whose only required field is non-blocking isn't
            // rejected for empty args, and the message lists only real blockers.
            if (args.length() == 0 && enforced.isNotEmpty()) {
                return "Tool '$name' was called with empty arguments {} but requires: ${enforced.joinToString(", ")}."
            }
            val missing = mutableListOf<String>()
            for (field in enforced) {
                // Absent — or present as an explicit JSON null. org.json reports
                // has() == true for `{"x": null}` and opt() hands back
                // JSONObject.NULL, which is not a String, so a null previously
                // slipped through BOTH checks and reached the tool as a non-String
                // value. Both spellings are genuinely missing.
                if (!args.has(field) || args.isNull(field)) {
                    missing.add(field)
                    continue
                }
                val raw = args.opt(field)
                // Only the truly-empty literal "" is rejected — NOT whitespace.
                // The earlier `.trim().isEmpty()` over-rejected legitimate payloads,
                // most notably file_edit with `new_string: "\n"` (replace a block
                // with a newline) or `old_string: "  "` (match consecutive spaces).
                // Both are valid edits, neither is stream corruption.
                //
                // And even "" is legal for whitelisted (tool, field) pairs:
                // file_edit.new_string == "" is the documented "delete old_string"
                // form, not a missing value. [T-preflight-empty-string-allowed]
                if (raw is String && raw.isEmpty() &&
                    !preflightEmptyStringAllowed(name, field)
                ) {
                    missing.add(field)
                }
            }
            if (missing.isNotEmpty()) {
                return "Tool '$name' is missing required parameter(s): ${missing.joinToString(", ")}."
            }
            // [T-android-tool-arg-schema] Presence is not conformance: check the
            // declared types, enums and nested structure too. Runs last so the
            // missing/empty messages above stay byte-for-byte what they were.
            // Returns a model-facing reason; the caller still synthesizes the
            // paired tool_result, so a rejected call never reaches a helper.
            return validate(toolDef, args)?.message
        }

    /** Serialized-argument budget. A tool call larger than this is refused
     *  before any helper sees it. */
    const val MAX_ARGUMENT_BYTES = 256 * 1024

    /** Recursion bound for nested arrays/objects. */
    const val MAX_NESTING_DEPTH = 32

    private const val MAX_MESSAGE_CHARS = 512
    private const val MAX_ECHOED_VALUE_CHARS = 60

    fun validate(tool: AgentToolDefinition, args: JSONObject): ToolArgumentRejection? {
        val serializedBytes = args.toString().toByteArray(Charsets.UTF_8).size
        if (serializedBytes > MAX_ARGUMENT_BYTES) {
            return reject(
                "TOOL_ARGUMENTS_TOO_LARGE",
                "Tool '${tool.name}' was called with $serializedBytes bytes of arguments, over the " +
                    "$MAX_ARGUMENT_BYTES byte limit.",
            )
        }
        for (key in args.keys()) {
            val param = tool.parameters[key] ?: continue
            // Absent / explicit null is preflight's decision, not this one.
            if (args.isNull(key)) continue
            // browser_use documents both JSON arrays and string forms for these inputs.
            // Preserve the existing BrowserActionInput/guest CLI contract when validating it.
            val actualParam = if (tool.name == "browser_use" && args.opt(key) is JSONArray) {
                when (key) {
                    "cookies" -> param.copy(type = "array", items = AgentToolParam("object", "Cookie"))
                    "keywords" -> param.copy(type = "array", items = AgentToolParam("string", "Cookie name"))
                    else -> param
                }
            } else param
            val rejection = checkValue(tool.name, key, actualParam, args.opt(key), depth = 0)
            if (rejection != null) return rejection
        }
        return null
    }

    private fun checkValue(
        toolName: String,
        path: String,
        param: AgentToolParam,
        value: Any?,
        depth: Int,
    ): ToolArgumentRejection? {
        if (depth > MAX_NESTING_DEPTH) {
            return reject(
                "TOOL_ARGUMENTS_TOO_DEEP",
                "Tool '$toolName' argument '$path' nests deeper than the supported $MAX_NESTING_DEPTH levels.",
            )
        }
        when (param.type.lowercase()) {
            "string" -> if (!isStringLike(value)) {
                return mismatch(toolName, path, "a string", value)
            }

            "integer" -> if (!isIntegral(value)) {
                return mismatch(toolName, path, "an integer", value)
            }

            "number" -> if (!isNumeric(value)) {
                return mismatch(toolName, path, "a number", value)
            }

            "boolean" -> if (!isBooleanLike(value)) {
                return mismatch(toolName, path, "a boolean", value)
            }

            "array" -> {
                val array = asArray(value) ?: return mismatch(toolName, path, "an array", value)
                val items = param.items
                if (items != null) {
                    for (index in 0 until array.length()) {
                        if (array.isNull(index)) continue
                        val rejection = checkValue(
                            toolName, "$path[$index]", items, array.opt(index), depth + 1,
                        )
                        if (rejection != null) return rejection
                    }
                }
            }

            "object" -> {
                val obj = asObject(value) ?: return mismatch(toolName, path, "an object", value)
                val properties = param.properties
                if (properties != null) {
                    for (key in properties.keys) {
                        if (!obj.has(key) || obj.isNull(key)) continue
                        val rejection = checkValue(
                            toolName, "$path.$key", properties.getValue(key), obj.opt(key), depth + 1,
                        )
                        if (rejection != null) return rejection
                    }
                    val missing = param.requiredProperties.orEmpty()
                        .filter { !obj.has(it) || obj.isNull(it) }
                    if (missing.isNotEmpty()) {
                        return reject(
                            "TOOL_ARGUMENT_OBJECT_INCOMPLETE",
                            "Tool '$toolName' argument '$path' is missing required field(s): " +
                                missing.joinToString(", ") + ".",
                        )
                    }
                }
            }

            // A schema type this validator does not understand is treated as
            // unusable rather than silently permissive.
            else -> return reject(
                "TOOL_ARGUMENT_SCHEMA_UNUSABLE",
                "Tool '$toolName' declares unsupported type '${param.type}' for argument '$path'.",
            )
        }

        val allowed = param.enumValues
        if (!allowed.isNullOrEmpty()) {
            val text = value?.toString()
            if (text == null || text !in allowed) {
                return reject(
                    "TOOL_ARGUMENT_ENUM",
                    "Tool '$toolName' argument '$path' must be one of " +
                        allowed.joinToString(", ") + "; got '${bounded(text)}'.",
                )
            }
        }
        return null
    }

    private fun mismatch(
        toolName: String,
        path: String,
        expected: String,
        value: Any?,
    ) = reject(
        "TOOL_ARGUMENT_TYPE",
        "Tool '$toolName' argument '$path' must be $expected; got '${bounded(value?.toString())}'.",
    )

    private fun reject(code: String, message: String) =
        ToolArgumentRejection(code, message.take(MAX_MESSAGE_CHARS))

    private fun bounded(value: String?): String {
        val text = value.orEmpty().replace('\n', ' ')
        return if (text.length <= MAX_ECHOED_VALUE_CHARS) text
        else text.take(MAX_ECHOED_VALUE_CHARS) + "…"
    }

    private fun isStringLike(value: Any?): Boolean =
        value is String || value is Number || value is Boolean

    /** Accepts a JSON integer, an integral float/decimal, or the string form
     *  that `ToolJsonRepair` produces for a non-string value. */
    private fun isIntegral(value: Any?): Boolean = when (value) {
        is Int, is Long, is Short, is Byte -> true
        is java.math.BigInteger -> true
        is java.math.BigDecimal -> value.stripTrailingZeros().scale() <= 0
        is Double -> value.isFinite() && value == floor(value)
        is Float -> value.isFinite() && value.toDouble() == floor(value.toDouble())
        is String -> {
            val trimmed = value.trim()
            trimmed.toLongOrNull() != null ||
                trimmed.toDoubleOrNull()?.let { it.isFinite() && it == floor(it) } == true
        }
        else -> false
    }

    private fun isNumeric(value: Any?): Boolean = when (value) {
        is Number -> true
        is String -> value.trim().toDoubleOrNull()?.isFinite() == true
        else -> false
    }

    private fun isBooleanLike(value: Any?): Boolean = when (value) {
        is Boolean -> true
        is String -> value.trim().equals("true", true) || value.trim().equals("false", true)
        else -> false
    }

    private fun asArray(value: Any?): JSONArray? = when (value) {
        is JSONArray -> value
        // Repair stringifies a non-string required value, so an array arrives
        // as its own JSON text. Re-parse it rather than rejecting the call.
        is String -> runCatching { JSONArray(value.trim()) }.getOrNull()
        else -> null
    }

    private fun asObject(value: Any?): JSONObject? = when (value) {
        is JSONObject -> value
        is String -> runCatching { JSONObject(value.trim()) }.getOrNull()
        else -> null
    }
}
