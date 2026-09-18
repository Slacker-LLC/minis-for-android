package com.openminis.app.xposed.aimemory

import java.nio.charset.StandardCharsets
import java.util.Base64
import org.json.JSONObject

/**
 * [T-eta-xposed-groups] The bounded contract between this app and the memory hook that lives inside
 * the ColorOS memory app's process.
 *
 * Ported from Eta `core/ColorOsMemoryBridgeProtocol.kt` (Mangi-11/Eta @ c15de97); attribution in
 * THIRD_PARTY_LICENSES.md. The request is a base64url JSON envelope with a version and a closed set
 * of operations, both directions are size-capped, and the root command that carries it is built
 * here rather than by the caller: the only things that can reach the provider are a fixed URI, a
 * fixed method name and an alphabet-restricted argument.
 */
object ColorOsMemoryBridgeProtocol {
    const val PACKAGE_NAME = "com.oplus.aimemory"
    const val PROVIDER_CLASS = "com.oplus.aimemory.provider.DataShareProvider"
    const val PROVIDER_URI = "content://com.oplus.aimemory.provider.DataShareProvider"
    const val METHOD = "llc.slacker.minis.coloros_memory.query.v1"
    const val RESULT_KEY = "minis_memory_bridge"
    const val DATABASE_NAME = "ai_memory"

    const val OPERATION_SEARCH = "search"
    const val OPERATION_ORDERS = "orders"
    const val OPERATION_PLACES = "places"

    private const val VERSION = 1
    private const val MAX_REQUEST_BYTES = 8 * 1024
    private const val MAX_RESPONSE_BYTES = 320 * 1024

    fun encodeRequest(operation: String, args: JSONObject): String {
        require(operation in OPERATIONS) { "unsupported ColorOS memory operation" }
        val raw = JSONObject()
            .put("version", VERSION)
            .put("operation", operation)
            .put("args", JSONObject(args.toString()))
            .toString()
            .toByteArray(StandardCharsets.UTF_8)
        require(raw.size <= MAX_REQUEST_BYTES) { "ColorOS memory request is too large" }
        return encode(raw)
    }

    fun decodeRequest(encoded: String): Request? {
        val raw = decode(encoded, MAX_REQUEST_BYTES) ?: return null
        val json = runCatching { JSONObject(String(raw, StandardCharsets.UTF_8)) }.getOrNull()
            ?: return null
        if (json.optInt("version") != VERSION) return null
        val operation = json.optString("operation")
        if (operation !in OPERATIONS) return null
        val args = json.optJSONObject("args") ?: return null
        return Request(operation, args)
    }

    fun encodeResponse(content: String): String {
        val raw = content.toByteArray(StandardCharsets.UTF_8)
        require(raw.size <= MAX_RESPONSE_BYTES) { "ColorOS memory response is too large" }
        return "$VERSION:${encode(raw)}"
    }

    /**
     * Pulls the envelope out of the `content call` shell output; anything that is not shaped like
     * this module's own answer is null rather than a best guess.
     */
    fun decodeShellResponse(stdout: String): String? {
        val marker = "$RESULT_KEY="
        val start = stdout.indexOf(marker)
        if (start < 0) return null
        val valueStart = start + marker.length
        val valueEnd = stdout.indexOf("}]", valueStart).takeIf { it >= 0 } ?: return null
        val envelope = stdout.substring(valueStart, valueEnd).trim()
        val separator = envelope.indexOf(':')
        if (separator <= 0 || envelope.substring(0, separator).toIntOrNull() != VERSION) return null
        val raw = decode(envelope.substring(separator + 1), MAX_RESPONSE_BYTES) ?: return null
        return String(raw, StandardCharsets.UTF_8)
    }

    fun buildRootCommand(encodedRequest: String): String {
        require(encodedRequest.length <= MAX_REQUEST_BYTES * 2) { "ColorOS memory request is too large" }
        require(encodedRequest.all { it.isLetterOrDigit() || it == '-' || it == '_' }) {
            "ColorOS memory request encoding is invalid"
        }
        return "content call --uri ${shellQuote(PROVIDER_URI)} " +
            "--method ${shellQuote(METHOD)} --arg ${shellQuote(encodedRequest)}"
    }

    data class Request(
        val operation: String,
        val args: JSONObject,
    )

    private fun encode(bytes: ByteArray): String =
        Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)

    private fun decode(encoded: String, maxBytes: Int): ByteArray? {
        if (encoded.isBlank() || encoded.length > ((maxBytes + 2) / 3) * 4) return null
        return runCatching { Base64.getUrlDecoder().decode(encoded) }
            .getOrNull()
            ?.takeIf { it.size <= maxBytes }
    }

    private fun shellQuote(value: String): String = "'" + value.replace("'", "'\\''") + "'"

    private val OPERATIONS = setOf(OPERATION_SEARCH, OPERATION_ORDERS, OPERATION_PLACES)
}
