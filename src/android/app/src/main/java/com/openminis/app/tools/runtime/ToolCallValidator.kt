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
 * - It is coercion-aware. `ToolJsonRepair` stringifies non-string required
 *   values before preflight runs, so `30` reaches a call as `"30"`. Accepting
 *   the string form of an integer, number or boolean keeps that repair path
 *   working instead of turning it into a rejection.
 * - It does not reject undeclared keys. Tool helpers read their own optional
 *   fields, and `tool_title` is injected uniformly across tools, so key
 *   strictness would reject calls that work today.
 * - `AgentToolParam` carries no numeric bounds or `additionalProperties`, so
 *   those schema features cannot be enforced here.
 *
 * Pure function: no I/O, no mutation of [args], no permission decisions.
 */
internal object ToolCallValidator {

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
            val rejection = checkValue(tool.name, key, param, args.opt(key), depth = 0)
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
