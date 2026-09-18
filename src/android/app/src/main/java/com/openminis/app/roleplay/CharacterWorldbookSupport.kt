package com.openminis.app.roleplay

import org.json.JSONArray
import org.json.JSONObject

/** [T-eta-character-cards] One world-book entry this app will not trigger, and why. */
data class UnsupportedWorldbookEntry(val index: Int, val reasons: List<String>)

/**
 * [T-eta-character-cards] Which world-book entries rely on conditions this app does not
 * implement.
 *
 * Ported from Eta `agent/roleplay/CharacterWorldbookSupport.kt` (Mangi-11/Eta @ c15de97);
 * attribution in THIRD_PARTY_LICENSES.md. The rule Eta states is the important one: an entry
 * with an unsupported condition is reported and skipped, never degraded into an unconditional
 * trigger — a regex key or a probability gate that quietly became "always" would inject lore the
 * card author never asked for. The same judgement feeds the import-time capability note and every
 * turn's projection.
 */
object CharacterWorldbookSupport {

    fun reasons(entry: JSONObject): List<String> {
        val extensions = entry.optJSONObject("extensions") ?: JSONObject()
        val reasons = mutableListOf<String>()

        val regexAllowed = boolOrNull(entry.opt("use_regex")) != false
        if (regexAllowed && (entry.strings("keys") + entry.strings("secondary_keys")).any(::regexKey)) {
            reasons += "正则关键字"
        }
        if (entry.text("content").lineSequence().any { it.trimStart().startsWith("@@") }) {
            reasons += "世界书装饰器"
        }
        val positionValue = extensions.opt("position")
        val hasPosition = positionValue != null && positionValue !== JSONObject.NULL
        val position = intOrNull(positionValue)
        if (hasPosition && (position == null || position !in 0..1)) reasons += "特殊插入位置"
        val textPosition = entry.text("position")
        if (textPosition.isNotEmpty() && textPosition !in setOf("before_char", "after_char")) {
            reasons += "特殊插入位置"
        }
        if (nonzero(extensions.opt("selectiveLogic"))) reasons += "高级次级匹配"
        val probabilityEnabled = boolOrNull(extensions.opt("useProbability")) != false
        val probability = doubleOrNull(extensions.opt("probability"))
        if (probabilityEnabled && probability != null && probability != 100.0) reasons += "概率触发"
        if (nonzero(extensions.opt("group"))) reasons += "条目分组"
        if (listOf("sticky", "cooldown", "delay", "delay_until_recursion").any { nonzero(extensions.opt(it)) }) {
            reasons += "时序触发"
        }
        val extendedMatchKeys = listOf(
            "exclude_recursion", "prevent_recursion", "ignore_budget", "match_whole_words", "vectorized",
            "match_persona_description", "match_character_description", "match_character_personality",
            "match_character_depth_prompt", "match_scenario", "match_creator_notes",
        )
        if (extendedMatchKeys.any { boolOrNull(extensions.opt(it)) == true }) reasons += "扩展匹配条件"
        if ((extensions.opt("triggers") as? JSONArray)?.let { it.length() > 0 } == true) {
            reasons += "指定生成类型"
        }
        if (extensions.text("automation_id").isNotBlank()) reasons += "脚本自动化"
        return reasons.distinct()
    }

    private fun regexKey(key: String): Boolean {
        val slash = key.lastIndexOf('/')
        return key.startsWith("/") && slash > 0 && key.substring(slash + 1).all { it.isLetter() }
    }

    /** Eta's "present and not zero-ish" test, over org.json values. */
    private fun nonzero(value: Any?): Boolean {
        if (value == null || value === JSONObject.NULL) return false
        return when (value) {
            is Boolean -> value
            is Int -> value != 0
            is Long -> value != 0L
            is Double -> value != 0.0
            is String -> value.isNotBlank()
            is JSONArray -> value.length() > 0
            else -> true
        }
    }
}

/**
 * Boolean, or null. A quoted "true" is not a boolean here — Eta reads JSON types strictly, and
 * a card that wrote the flag as a string is a card whose intent nobody can prove.
 */
internal fun boolOrNull(value: Any?): Boolean? = value as? Boolean

internal fun intOrNull(value: Any?): Int? = when (value) {
    is Int -> value
    is Long -> if (value in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong()) value.toInt() else null
    is Double -> if (!value.isNaN() && !value.isInfinite() && value == Math.floor(value) &&
        value >= Int.MIN_VALUE.toDouble() && value <= Int.MAX_VALUE.toDouble()
    ) value.toInt() else null
    else -> null
}

internal fun doubleOrNull(value: Any?): Double? = when (value) {
    is Int -> value.toDouble()
    is Long -> value.toDouble()
    is Double -> value
    else -> null
}
