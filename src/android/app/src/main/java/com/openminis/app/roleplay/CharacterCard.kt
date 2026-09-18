package com.openminis.app.roleplay

import org.json.JSONArray
import org.json.JSONObject

/**
 * [T-eta-character-cards] A Tavern character card V2/V3, kept as the tree it was parsed from
 * so fields this app does not implement survive an import/export round trip.
 *
 * Ported from Eta `agent/roleplay/CharacterCard.kt` (Mangi-11/Eta @ c15de97); attribution in
 * THIRD_PARTY_LICENSES.md. Eta builds on kotlinx JSON objects, which are immutable; this port
 * uses org.json, so anything that hands a nested object to another tree copies it first
 * ([deepCopy]) — otherwise editing an export would mutate the card it was taken from.
 */
data class CharacterCard(val raw: JSONObject) {

    enum class Format { PNG, JSON }

    data class DepthPrompt(val prompt: String, val depth: Int, val role: String)

    /** V2/V3 keep the fields under `data`; a legacy card has them at the root. */
    val data: JSONObject get() = raw.optJSONObject("data") ?: raw

    val spec: String get() = raw.text("spec")

    val name: String get() = data.text("name")
    val description: String get() = data.text("description")
    val personality: String get() = data.text("personality")
    val scenario: String get() = data.text("scenario")
    val firstMessage: String get() = data.text("first_mes")
    val alternateGreetings: List<String> get() = data.strings("alternate_greetings")
    val exampleMessages: String get() = data.text("mes_example")
    val systemPrompt: String get() = data.text("system_prompt")
    val postHistoryInstructions: String get() = data.text("post_history_instructions")
    val creatorNotes: String get() = data.text("creator_notes").ifEmpty { data.text("creatorcomment") }
    val tags: List<String> get() = data.strings("tags")
    val creator: String get() = data.text("creator")
    val version: String get() = data.text("character_version")
    val nickname: String get() = data.text("nickname").ifBlank { name }
    val characterBook: JSONObject? get() = data.optJSONObject("character_book")
    val extensions: JSONObject get() = data.optJSONObject("extensions") ?: JSONObject()

    /**
     * The optional depth note. Eta refuses a note whose depth or role is unusable instead of
     * guessing a position for it, and so does this port.
     */
    val depthPrompt: DepthPrompt?
        get() {
            val value = extensions.optJSONObject("depth_prompt") ?: return null
            val prompt = value.text("prompt").takeIf { it.isNotBlank() } ?: return null
            if (!value.has("depth")) return null
            val depth = value.optIntOrNull("depth") ?: return null
            val role = value.text("role").ifEmpty { "system" }
            if (depth < 0 || role !in setOf("system", "user", "assistant")) return null
            return DepthPrompt(prompt, depth, role)
        }

    override fun equals(other: Any?): Boolean = other is CharacterCard && raw.toString() == other.raw.toString()

    override fun hashCode(): Int = raw.toString().hashCode()
}

internal fun JSONObject.text(key: String): String {
    val value = opt(key)
    return if (value is String) value else ""
}

internal fun JSONObject.strings(key: String): List<String> {
    val array = optJSONArray(key) ?: return emptyList()
    return (0 until array.length()).mapNotNull { index ->
        (array.opt(index) as? String)?.takeIf { it.isNotEmpty() }
    }
}

/** Integer-valued field, or null when it is absent or not an integer. */
internal fun JSONObject.optIntOrNull(key: String): Int? {
    val value = opt(key) ?: return null
    return when (value) {
        is Int -> value
        is Long -> if (value in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong()) value.toInt() else null
        is Double -> if (value == Math.floor(value) && !value.isInfinite()) value.toInt() else null
        is String -> value.toIntOrNull()
        else -> null
    }
}

/** Deep copy through the serialized form: org.json objects are mutable and shared otherwise. */
internal fun JSONObject.deepCopy(): JSONObject = JSONObject(toString())

internal fun JSONArray.deepCopy(): JSONArray = JSONArray(toString())
