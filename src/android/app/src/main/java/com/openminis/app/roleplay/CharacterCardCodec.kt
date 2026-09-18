package com.openminis.app.roleplay

import org.json.JSONArray
import org.json.JSONObject
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction

/**
 * [T-eta-character-cards] Reading and writing Tavern character cards.
 *
 * Ported from Eta `agent/roleplay/CharacterCardCodec.kt` (Mangi-11/Eta @ c15de97); attribution
 * in THIRD_PARTY_LICENSES.md. The rules are Eta's: 16 MiB ceiling, strict UTF-8, only
 * chara_card_v2 / chara_card_v3 are claimed as supported, a legacy card is normalised into the
 * V2 shape instead of being refused, and the world book is validated before anything stores the
 * card. One deliberate difference: Eta rejects a text field whose value is JSON null; a real
 * card in the wild often writes empty fields that way, so this port treats an explicit null as
 * absent.
 */
object CharacterCardCodec {

    const val MAX_CARD_BYTES = 16 * 1024 * 1024

    val textFields = listOf(
        "name", "description", "personality", "scenario", "first_mes", "mes_example",
        "creator_notes", "system_prompt", "post_history_instructions", "creator", "character_version",
    )

    private val stringArrayFields = listOf("tags", "alternate_greetings", "group_only_greetings")

    private val supportedSpecs = setOf("chara_card_v2", "chara_card_v3")

    fun create(name: String): CharacterCard = decodeJson(JSONObject().put("name", name).toString())

    fun decodeBytes(bytes: ByteArray): CharacterCard {
        if (bytes.size > MAX_CARD_BYTES) throw CharacterCardException("CARD_TOO_LARGE", "角色卡超过 16 MiB 限制")
        val source = try {
            Charsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes))
                .toString()
        } catch (failure: CharacterCodingException) {
            throw CharacterCardException("CARD_INVALID_UTF8", "角色卡必须使用有效的 UTF-8 编码", failure)
        }
        return decodeJson(source)
    }

    fun decodeJson(source: String): CharacterCard {
        val cleaned = source.removePrefix("\uFEFF").trim()
        if (cleaned.toByteArray(Charsets.UTF_8).size > MAX_CARD_BYTES) {
            throw CharacterCardException("CARD_TOO_LARGE", "角色卡超过 16 MiB 限制")
        }
        val root = try {
            JSONObject(cleaned)
        } catch (failure: Exception) {
            throw CharacterCardException("CARD_INVALID_JSON", "角色卡必须是有效的 JSON 对象", failure)
        }
        val spec = root.text("spec")
        if (spec.isNotEmpty() && spec !in supportedSpecs) {
            throw CharacterCardException("CARD_UNSUPPORTED_SPEC", "不支持的角色卡规范：$spec")
        }
        val original = if (spec.isEmpty()) {
            root
        } else {
            root.optJSONObject("data")
                ?: throw CharacterCardException("CARD_INVALID_JSON", "角色卡缺少 data 对象")
        }
        require(original.text("name").isNotBlank()) { "角色卡缺少名称" }
        textFields.forEach { field ->
            val value = original.opt(field)
            require(!present(value) || value is String) { "角色卡字段 $field 必须是文本" }
        }
        stringArrayFields.forEach { field ->
            val value = original.opt(field)
            require(!present(value) || isStringArray(value)) { "角色卡字段 $field 必须是文本数组" }
        }
        require(!present(original.opt("extensions")) || original.opt("extensions") is JSONObject) {
            "角色扩展格式无效"
        }
        require(!present(original.opt("character_book")) || original.opt("character_book") is JSONObject) {
            "角色世界书格式无效"
        }
        original.optJSONObject("character_book")?.let(::validateBook)
        if (spec.isNotEmpty()) return CharacterCard(root)

        // Legacy v1 card: give it the V2 shape, keeping every field it already had.
        val data = original.deepCopy()
        textFields.forEach { field -> if (!data.has(field)) data.put(field, "") }
        if (!data.has("creator_notes")) data.put("creator_notes", original.text("creatorcomment"))
        if (!data.has("tags")) data.put("tags", JSONArray())
        if (!data.has("alternate_greetings")) data.put("alternate_greetings", JSONArray())
        if (!data.has("extensions")) data.put("extensions", JSONObject())
        val migrated = root.deepCopy().apply {
            put("spec", "chara_card_v2")
            if (!has("spec_version")) put("spec_version", "2.0")
            put("data", data)
        }
        return CharacterCard(migrated)
    }

    fun encodeJson(card: CharacterCard): String = card.raw.toString(2)

    /**
     * The JSON written next to a card image (or exported on its own): the V2/V3 skeleton filled
     * in, with the fields this app does not model left exactly as they came in.
     */
    fun exportView(card: CharacterCard, version: Int): String {
        require(version == 2 || version == 3) { "unsupported export version: $version" }
        val fields = card.data.deepCopy()
        textFields.forEach { field -> if (!fields.has(field)) fields.put(field, "") }
        if (!fields.has("tags")) fields.put("tags", JSONArray())
        if (!fields.has("alternate_greetings")) fields.put("alternate_greetings", JSONArray())
        if (!fields.has("extensions")) fields.put("extensions", JSONObject())
        if (version == 3 && !fields.has("group_only_greetings")) fields.put("group_only_greetings", JSONArray())
        val root = card.raw.deepCopy().apply {
            put("spec", "chara_card_v$version")
            put("spec_version", "$version.0")
            put("data", fields)
            // A legacy card mirrors these at the top level; keep the mirror in step.
            textFields.forEach { key -> if (has(key)) put(key, fields.opt(key) ?: "") }
        }
        return root.toString(2)
    }

    /** JSON null means "absent" here: real cards write empty fields that way. */
    private fun present(value: Any?): Boolean = value != null && value !== JSONObject.NULL

    private fun isStringArray(value: Any?): Boolean {
        val array = value as? JSONArray ?: return false
        return (0 until array.length()).all { index -> array.opt(index) is String }
    }

    private fun isNonNegativeInt(value: Any?): Boolean = when (value) {
        is Int -> value >= 0
        is Long -> value >= 0
        is Double -> value >= 0 && !value.isNaN() && value == Math.floor(value)
        else -> false
    }

    private fun isFiniteNumber(value: Any?): Boolean = when (value) {
        is Int, is Long -> true
        is Double -> value.isFinite()
        else -> false
    }

    private fun validateBook(book: JSONObject) {
        listOf("scan_depth", "token_budget").forEach { field ->
            val value = book.opt(field)
            require(!present(value) || isNonNegativeInt(value)) { "世界书 $field 必须为非负整数" }
        }
        val recursive = book.opt("recursive_scanning")
        require(!present(recursive) || recursive is Boolean) { "世界书递归设置无效" }
        require(!present(book.opt("extensions")) || book.opt("extensions") is JSONObject) { "世界书扩展格式无效" }
        val entries = book.opt("entries")
        if (!present(entries)) return
        require(entries is JSONArray) { "世界书条目必须为数组" }
        entries.let { array ->
            for (index in 0 until array.length()) {
                val entry = array.opt(index)
                require(entry is JSONObject) { "世界书第 ${index + 1} 项不是对象" }
                listOf("keys", "secondary_keys").forEach { field ->
                    val keys = entry.opt(field)
                    require(!present(keys) || isStringArray(keys)) { "世界书第 ${index + 1} 项关键字格式无效" }
                }
                val content = entry.opt("content")
                require(!present(content) || content is String) { "世界书正文必须为文本" }
                listOf("enabled", "constant", "selective", "case_sensitive", "use_regex").forEach { field ->
                    val flag = entry.opt(field)
                    require(!present(flag) || flag is Boolean) { "世界书第 ${index + 1} 项开关格式无效" }
                }
                listOf("insertion_order", "priority").forEach { field ->
                    val number = entry.opt(field)
                    require(!present(number) || isFiniteNumber(number)) { "世界书第 ${index + 1} 项顺序或优先级无效" }
                }
                require(!present(entry.opt("extensions")) || entry.opt("extensions") is JSONObject) {
                    "世界书条目扩展格式无效"
                }
            }
        }
    }
}
