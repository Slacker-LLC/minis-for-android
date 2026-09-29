package com.openminis.app.data.migration

import com.openminis.app.data.db.ProviderConfigMetaEntity
import com.openminis.app.data.db.ProviderConfigMetaKeys
import com.openminis.app.data.db.ProviderModelGroupEntity
import com.openminis.app.data.db.compositeEntryKey
import com.openminis.app.data.model.FallbackStrategy
import com.openminis.app.data.model.ProviderConfig
import com.openminis.app.data.model.ThinkingLevel
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** Boundary adapters only; the migration algorithm itself remains pure. */
internal object LegacyGroupStateParser {
    fun fromDatabase(
        config: ProviderConfig,
        rows: List<ProviderModelGroupEntity>,
        metaRows: List<ProviderConfigMetaEntity>,
        availableEntryIds: Set<String>,
        sessionBindings: List<LegacyBindingRecord>,
        scheduledBindings: List<LegacyBindingRecord>,
        botBindings: List<LegacyBindingRecord>,
        legacyAgentLoopGroupIds: List<String> = emptyList(),
        legacyJsonConfig: ProviderConfig? = null,
        json: Json,
    ): LegacyState {
        val meta = metaRows.associate { it.key to it.value }
        val groups = rows.mapNotNull { row ->
            val members = runCatching {
                json.decodeFromString(ListSerializer(String.serializer()), row.memberEntryIdsJson)
            }.getOrNull() ?: return@mapNotNull null
            LegacyModelGroup(
                id = row.id,
                memberEntryIds = members,
                fallbackStrategy = safeFallback(row.fallbackStrategy),
                defaultThinkingLevel = row.defaultThinkingLevel?.let { ThinkingLevel.decoded(it) },
                contextLimitTokens = row.contextLimitTokens,
                lastContextLimitTokens = row.lastContextLimitTokens,
                sortOrder = row.sortOrder,
            )
        }
        return LegacyState(
            config = config,
            groups = groups,
            pointers = LegacyGroupPointers(
                main = meta[ProviderConfigMetaKeys.DEFAULT_PRIMARY_GROUP_ID],
                light = meta[ProviderConfigMetaKeys.DEFAULT_SUB_GROUP_ID],
                vision = meta[ProviderConfigMetaKeys.VISION_GROUP_ID],
                voiceInput = meta[ProviderConfigMetaKeys.VOICE_INPUT_GROUP_ID],
                voiceOutput = meta[ProviderConfigMetaKeys.VOICE_OUTPUT_GROUP_ID],
            ),
            agentLoopGroupIds = legacyAgentLoopGroupIds,
            entryIdAliases = aliases(config) + (legacyJsonConfig?.let(::aliases) ?: emptyMap()),
            availableEntryIds = availableEntryIds,
            sessionBindings = sessionBindings,
            scheduledBindings = scheduledBindings,
            botBindings = botBindings,
        )
    }

    /** Reads legacy-only properties from a provider JSON or backup document. */
    fun fromJson(
        config: ProviderConfig,
        rawJson: String,
        availableEntryIds: Set<String>,
        sessionBindings: List<LegacyBindingRecord> = emptyList(),
        scheduledBindings: List<LegacyBindingRecord> = emptyList(),
        botBindings: List<LegacyBindingRecord> = emptyList(),
    ): LegacyState {
        val root = runCatching { Json.parseToJsonElement(rawJson).jsonObject }.getOrNull() ?: JsonObject(emptyMap())
        val groups = root["modelGroups"]?.let { raw ->
            runCatching { raw.jsonArray }.getOrNull()?.mapIndexedNotNull { index, element ->
                val obj = runCatching { element.jsonObject }.getOrNull() ?: return@mapIndexedNotNull null
                val id = obj.string("id") ?: return@mapIndexedNotNull null
                val members = obj["memberEntryIds"]?.let { value ->
                    runCatching { value.jsonArray.mapNotNull { it.jsonPrimitive.contentOrNull } }.getOrNull()
                }.orEmpty()
                LegacyModelGroup(
                    id = id,
                    memberEntryIds = members,
                    fallbackStrategy = obj.string("fallbackStrategy")?.let(::safeFallback) ?: FallbackStrategy.default,
                    defaultThinkingLevel = obj.string("defaultThinkingLevel")?.let { ThinkingLevel.decoded(it) },
                    contextLimitTokens = obj.int("contextLimitTokens"),
                    lastContextLimitTokens = obj.int("lastContextLimitTokens"),
                    sortOrder = obj.int("sortOrder") ?: index,
                )
            }.orEmpty()
        }.orEmpty()
        return LegacyState(
            config = config,
            groups = groups,
            pointers = LegacyGroupPointers(
                main = root.string("defaultPrimaryGroupId"),
                light = root.string("defaultSubGroupId"),
                vision = root.string("visionGroupId"),
                voiceInput = root.string("voiceInputGroupId"),
                voiceOutput = root.string("voiceOutputGroupId"),
            ),
            agentLoopGroupIds = root["agentLoopGroupIds"]?.let { value ->
                runCatching { value.jsonArray.mapNotNull { it.jsonPrimitive.contentOrNull } }.getOrNull()
            }.orEmpty(),
            entryIdAliases = aliases(config),
            availableEntryIds = availableEntryIds,
            sessionBindings = sessionBindings,
            scheduledBindings = scheduledBindings,
            botBindings = botBindings,
        )
    }

    private fun aliases(config: ProviderConfig): Map<String, String> = buildMap {
        for (entry in config.modelEntries) {
            val durable = compositeEntryKey(entry.providerInstanceId, entry.baseModel.id)
            put(entry.id, durable)
            put(durable, durable)
        }
    }

    private fun safeFallback(value: String): FallbackStrategy =
        runCatching { FallbackStrategy.valueOf(value) }.getOrDefault(FallbackStrategy.default)

    private fun JsonObject.string(key: String): String? =
        this[key]?.let { runCatching { it.jsonPrimitive.contentOrNull }.getOrNull() }

    private fun JsonObject.int(key: String): Int? =
        this[key]?.let { runCatching { it.jsonPrimitive.intOrNull }.getOrNull() }
}
