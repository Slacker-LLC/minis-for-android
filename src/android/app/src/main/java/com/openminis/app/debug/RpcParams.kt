package com.openminis.app.debug

import org.json.JSONArray
import org.json.JSONObject

/**
 * Strict readers for RPC parameters. `optString`/`optJSONObject` turn a wrong type into "", `"null"`
 * or an empty collection, so a malformed `{apiKey: null}` or `{headers: "x"}` used to be saved as if
 * the caller had meant it and overwrote a working credential or configuration. Here a present value
 * of the wrong type is a -32602 error and an absent one means "leave it alone".
 */
internal object RpcParams {
    private fun invalid(name: String, expected: String): Nothing =
        throw RPCException(-32602, "'$name' must be $expected")

    /** The string at [name], or null when the param is absent. A present non-string (including null) is an error. */
    fun string(params: JSONObject, name: String): String? {
        if (!params.has(name)) return null
        return params.get(name) as? String ?: invalid(name, "a string")
    }

    fun boolean(params: JSONObject, name: String): Boolean? {
        if (!params.has(name)) return null
        return params.get(name) as? Boolean ?: invalid(name, "a boolean")
    }

    /** A JSON array of strings, or null when absent. */
    fun stringList(params: JSONObject, name: String): List<String>? {
        if (!params.has(name)) return null
        val array = params.get(name) as? JSONArray ?: invalid(name, "an array of strings")
        return (0 until array.length()).map { array.get(it) as? String ?: invalid(name, "an array of strings") }
    }

    /** A JSON object whose values are all strings, or null when absent. */
    fun stringMap(params: JSONObject, name: String): Map<String, String>? {
        if (!params.has(name)) return null
        val obj = params.get(name) as? JSONObject ?: invalid(name, "an object with string values")
        val out = linkedMapOf<String, String>()
        for (key in obj.keys()) out[key] = obj.get(key) as? String ?: invalid(name, "an object with string values")
        return out
    }
}
