package com.openminis.app.provider.rules

import com.openminis.app.data.model.LLMModel
import com.openminis.app.data.model.ThinkingLevel
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

internal const val MODEL_RULES_SCHEMA_VERSION = 1
internal const val MODEL_RULES_MAX_BYTES = 256 * 1024

internal data class RuleMatch(
    val idExact: List<String> = emptyList(),
    val idPrefix: List<String> = emptyList(),
    val idSuffix: List<String> = emptyList(),
    val idContains: List<String> = emptyList(),
    val normalizeDots: Boolean = false,
    val stripPath: Boolean = false,
) {
    fun matches(modelId: String): Boolean {
        var candidate = modelId.lowercase()
        if (stripPath) candidate = candidate.substringAfterLast('/')
        if (normalizeDots) candidate = candidate.replace('.', '-')
        fun normalized(values: List<String>) = values.map {
            val lower = it.lowercase()
            if (normalizeDots) lower.replace('.', '-') else lower
        }
        return (idExact.isEmpty() || candidate in normalized(idExact)) &&
            (idPrefix.isEmpty() || normalized(idPrefix).any(candidate::startsWith)) &&
            (idSuffix.isEmpty() || normalized(idSuffix).any(candidate::endsWith)) &&
            (idContains.isEmpty() || normalized(idContains).any(candidate::contains))
    }

    val hasPredicate: Boolean
        get() = idExact.isNotEmpty() || idPrefix.isNotEmpty() || idSuffix.isNotEmpty() || idContains.isNotEmpty()
}

internal data class ModelRuleSet(
    val maxThinkingLevel: ThinkingLevel? = null,
    val reasoningEffortValues: List<String>? = null,
    val supportsReasoning: Boolean? = null,
    val contextWindow: Int? = null,
    val maxOutputTokens: Int? = null,
    val inputModalities: List<String>? = null,
    val outputModalities: List<String>? = null,
    val rejectsTemperature: Boolean? = null,
    val adaptiveThinking: Boolean? = null,
    val requiresThoughtSignature: Boolean? = null,
)

internal data class ModelRule(val id: String, val match: RuleMatch, val set: ModelRuleSet)

internal data class PickerFilter(
    val includePrefixes: List<String> = emptyList(),
    val excludeSuffixes: List<String> = emptyList(),
    /** Retained for the existing OpenAI fine-tune exclusion (`:ft-` is internal, not a suffix). */
    val excludeContains: List<String> = emptyList(),
) {
    fun accepts(id: String): Boolean {
        val value = id.lowercase()
        return (includePrefixes.isEmpty() || includePrefixes.any { value.startsWith(it.lowercase()) }) &&
            excludeSuffixes.none { value.endsWith(it.lowercase()) } &&
            excludeContains.none { value.contains(it.lowercase()) }
    }
}

internal data class ModelRulesDocument(
    val schemaVersion: Int,
    val rules: List<ModelRule>,
    val staticModels: Map<String, List<LLMModel>>,
    val pickerFilters: Map<String, PickerFilter>,
) {
    fun capabilitiesFor(modelId: String): ModelRuleSet {
        fun <T> first(select: (ModelRuleSet) -> T?): T? = rules.firstNotNullOfOrNull { rule ->
            if (rule.match.matches(modelId)) select(rule.set) else null
        }
        return ModelRuleSet(
            maxThinkingLevel = first { it.maxThinkingLevel },
            reasoningEffortValues = first { it.reasoningEffortValues },
            supportsReasoning = first { it.supportsReasoning },
            contextWindow = first { it.contextWindow },
            maxOutputTokens = first { it.maxOutputTokens },
            inputModalities = first { it.inputModalities },
            outputModalities = first { it.outputModalities },
            rejectsTemperature = first { it.rejectsTemperature },
            adaptiveThinking = first { it.adaptiveThinking },
            requiresThoughtSignature = first { it.requiresThoughtSignature },
        )
    }

    fun applyCapabilities(model: LLMModel): LLMModel {
        val metadata = capabilitiesFor(model.id)
        return model.copy(
            contextWindow = metadata.contextWindow ?: model.contextWindow,
            maxOutputTokens = metadata.maxOutputTokens ?: model.maxOutputTokens,
            supportsReasoning = metadata.supportsReasoning ?: model.supportsReasoning,
            reasoningEffortValues = metadata.reasoningEffortValues ?: model.reasoningEffortValues,
            inputModalities = metadata.inputModalities ?: model.inputModalities,
            outputModalities = metadata.outputModalities ?: model.outputModalities,
        )
    }

    companion object {
        val EMPTY = ModelRulesDocument(MODEL_RULES_SCHEMA_VERSION, emptyList(), emptyMap(), emptyMap())
    }
}

/** Strict and bounded parser. Invalid documents never replace the active document. */
internal object ModelRulesParser {
    private val json = Json { ignoreUnknownKeys = false }
    private val topKeys = setOf("schemaVersion", "rules", "staticModels", "pickerFilters")
    private val matchKeys = setOf("idExact", "idPrefix", "idSuffix", "idContains", "normalizeDots", "stripPath")
    private val setKeys = setOf(
        "maxThinkingLevel", "reasoningEffortValues", "supportsReasoning", "contextWindow",
        "maxOutputTokens", "inputModalities", "outputModalities", "rejectsTemperature",
        "adaptiveThinking", "requiresThoughtSignature",
    )
    private val filterKeys = setOf("includePrefixes", "excludeSuffixes", "excludeContains")
    private val staticModelGroups = setOf("anthropic", "gemini", "openAI", "openRouter", "xAI", "kimi", "codexOAuth")

    fun parse(text: String, onInvalidRule: (String) -> Unit = {}): ModelRulesDocument? {
        if (text.toByteArray(Charsets.UTF_8).size > MODEL_RULES_MAX_BYTES) return null
        return runCatching {
            val root = json.parseToJsonElement(text) as? JsonObject ?: return null
            if (root.keys.any { it !in topKeys }) return null
            val version = root.int("schemaVersion") ?: return null
            if (version != MODEL_RULES_SCHEMA_VERSION) return null
            val rawRules = root["rules"] as? JsonArray ?: return null
            val rawModels = root["staticModels"] as? JsonObject ?: return null
            val rawFilters = root["pickerFilters"] as? JsonObject ?: return null
            if (rawModels.keys.any { it !in staticModelGroups }) return null

            val parsedRules = mutableListOf<ModelRule>()
            val seenRuleIds = mutableSetOf<String>()
            rawRules.forEachIndexed { index, raw ->
                val rule = parseRule(raw as? JsonObject, index, onInvalidRule) ?: return@forEachIndexed
                if (!seenRuleIds.add(rule.id)) {
                    onInvalidRule("duplicate rule id '${rule.id}'")
                } else parsedRules += rule
            }

            val models = rawModels.mapValues { (_, value) ->
                val array = value as? JsonArray ?: error("staticModels entries must be arrays")
                json.decodeFromJsonElement(ListSerializer(LLMModel.serializer()), array)
            }
            val filters = rawFilters.mapValues { (provider, value) ->
                val filter = value as? JsonObject ?: error("pickerFilters.$provider must be an object")
                if (filter.keys.any { it !in filterKeys }) error("unknown picker filter property")
                for (key in filterKeys) {
                    if (key in filter && filter.strings(key) == null) error("pickerFilters.$provider.$key must be a string array")
                }
                PickerFilter(
                    includePrefixes = filter.strings("includePrefixes") ?: emptyList(),
                    excludeSuffixes = filter.strings("excludeSuffixes") ?: emptyList(),
                    excludeContains = filter.strings("excludeContains") ?: emptyList(),
                )
            }
            ModelRulesDocument(version, parsedRules, models, filters)
        }.getOrNull()
    }

    private fun parseRule(obj: JsonObject?, index: Int, warn: (String) -> Unit): ModelRule? {
        fun reject(reason: String): ModelRule? {
            warn("rule[$index] ignored: $reason")
            return null
        }
        obj ?: return reject("expected object")
        if (obj.keys.any { it !in setOf("id", "match", "set") }) return reject("unknown property")
        val id = obj.string("id")?.takeIf { it.isNotBlank() } ?: return reject("missing id")
        val matchObject = obj["match"] as? JsonObject ?: return reject("missing match")
        val setObject = obj["set"] as? JsonObject ?: return reject("missing set")
        if (matchObject.keys.any { it !in matchKeys }) return reject("unknown match property")
        if (setObject.keys.any { it !in setKeys }) return reject("unknown set property")
        for (key in listOf("idExact", "idPrefix", "idSuffix", "idContains")) {
            if (key in matchObject && matchObject.strings(key) == null) return reject("$key must be a string array")
        }
        for (key in listOf("normalizeDots", "stripPath")) {
            if (key in matchObject && matchObject.boolean(key) == null) return reject("$key must be boolean")
        }
        val match = RuleMatch(
            idExact = matchObject.strings("idExact") ?: emptyList(),
            idPrefix = matchObject.strings("idPrefix") ?: emptyList(),
            idSuffix = matchObject.strings("idSuffix") ?: emptyList(),
            idContains = matchObject.strings("idContains") ?: emptyList(),
            normalizeDots = matchObject.boolean("normalizeDots") ?: false,
            stripPath = matchObject.boolean("stripPath") ?: false,
        )
        if (!match.hasPredicate) return reject("match has no predicate")
        if ("maxThinkingLevel" in setObject && setObject.string("maxThinkingLevel") == null) {
            return reject("maxThinkingLevel must be a string enum")
        }
        val maxLevelRaw = setObject.string("maxThinkingLevel")
        val maxLevel = maxLevelRaw?.let { raw ->
            runCatching { ThinkingLevel.valueOf(raw.uppercase()) }.getOrNull()
                ?: return reject("unknown maxThinkingLevel enum '$raw'")
        }
        fun positiveInt(name: String): Int? {
            val value = setObject.int(name) ?: return null
            if (value <= 0) return null
            return value
        }
        if (("contextWindow" in setObject && positiveInt("contextWindow") == null) ||
            ("maxOutputTokens" in setObject && positiveInt("maxOutputTokens") == null)
        ) return reject("invalid positive integer")
        fun requiredStrings(name: String): List<String>? = setObject.strings(name)
        val effortValues = requiredStrings("reasoningEffortValues")
        val input = requiredStrings("inputModalities")
        val output = requiredStrings("outputModalities")
        if (("reasoningEffortValues" in setObject && effortValues == null) ||
            ("inputModalities" in setObject && input == null) ||
            ("outputModalities" in setObject && output == null)
        ) return reject("expected string array")
        fun optionalBoolean(name: String): Boolean? = if (name in setObject) setObject.boolean(name) else null
        val supportsReasoning = optionalBoolean("supportsReasoning")
        val rejectsTemperature = optionalBoolean("rejectsTemperature")
        val adaptiveThinking = optionalBoolean("adaptiveThinking")
        val requiresThoughtSignature = optionalBoolean("requiresThoughtSignature")
        if (("supportsReasoning" in setObject && supportsReasoning == null) ||
            ("rejectsTemperature" in setObject && rejectsTemperature == null) ||
            ("adaptiveThinking" in setObject && adaptiveThinking == null) ||
            ("requiresThoughtSignature" in setObject && requiresThoughtSignature == null)
        ) return reject("expected boolean")
        return ModelRule(
            id,
            match,
            ModelRuleSet(
                maxThinkingLevel = maxLevel,
                reasoningEffortValues = effortValues,
                supportsReasoning = supportsReasoning,
                contextWindow = positiveInt("contextWindow"),
                maxOutputTokens = positiveInt("maxOutputTokens"),
                inputModalities = input,
                outputModalities = output,
                rejectsTemperature = rejectsTemperature,
                adaptiveThinking = adaptiveThinking,
                requiresThoughtSignature = requiresThoughtSignature,
            ),
        )
    }

    private fun JsonObject.string(name: String): String? = (this[name] as? JsonPrimitive)
        ?.takeIf { it.isString }?.contentOrNull
    private fun JsonObject.int(name: String): Int? = (this[name] as? JsonPrimitive)?.intOrNull
    private fun JsonObject.boolean(name: String): Boolean? = (this[name] as? JsonPrimitive)?.booleanOrNull
    private fun JsonObject.strings(name: String): List<String>? {
        val value = this[name] ?: return null
        val array = value as? JsonArray ?: return null
        return array.map { element ->
            val primitive = element as? JsonPrimitive ?: return null
            if (!primitive.isString) return null
            primitive.contentOrNull ?: return null
        }
    }
}
