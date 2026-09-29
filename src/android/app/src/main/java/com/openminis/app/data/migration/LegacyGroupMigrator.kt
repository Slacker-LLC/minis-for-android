package com.openminis.app.data.migration

import com.openminis.app.data.model.FallbackStrategy
import com.openminis.app.data.model.ModelBinding
import com.openminis.app.data.model.ModelEntry
import com.openminis.app.data.model.ModelSlots
import com.openminis.app.data.model.ProviderConfig
import com.openminis.app.data.model.ThinkingLevel
import com.openminis.app.data.db.compositeEntryKey

/** Migration-only representation of a row in the retained legacy group table. */
internal data class LegacyModelGroup(
    val id: String,
    val memberEntryIds: List<String>,
    val fallbackStrategy: FallbackStrategy = FallbackStrategy.default,
    val defaultThinkingLevel: ThinkingLevel? = null,
    val contextLimitTokens: Int? = null,
    val lastContextLimitTokens: Int? = null,
    val sortOrder: Int = 0,
)

internal data class LegacyGroupPointers(
    val main: String? = null,
    val light: String? = null,
    val vision: String? = null,
    val voiceInput: String? = null,
    val voiceOutput: String? = null,
)

internal data class LegacyBindingRecord(
    val id: String,
    val binding: String?,
    val modelId: String? = null,
)

internal data class LegacyState(
    val config: ProviderConfig,
    val groups: List<LegacyModelGroup>,
    val pointers: LegacyGroupPointers,
    val agentLoopGroupIds: List<String>,
    /** Legacy random UUID → durable entry id aliases, including identity aliases. */
    val entryIdAliases: Map<String, String>,
    /** Entry ids that pass the old hidden/provider-enabled/credential filter. */
    val availableEntryIds: Set<String>,
    val sessionBindings: List<LegacyBindingRecord> = emptyList(),
    val scheduledBindings: List<LegacyBindingRecord> = emptyList(),
    val botBindings: List<LegacyBindingRecord> = emptyList(),
)

internal data class MigratedBinding(
    val id: String,
    val binding: String?,
    /** Non-null only when a former group binding resolved to a concrete entry. */
    val modelId: String? = null,
)

internal data class MigrationResult(
    val config: ProviderConfig,
    val sessionBindings: List<MigratedBinding>,
    val scheduledBindings: List<MigratedBinding>,
    val botBindings: List<MigratedBinding>,
    val warnings: List<String>,
)

/** Pure, deterministic, repeatable conversion from legacy groups to entry slots. */
internal object LegacyGroupMigrator {
    fun migrate(legacy: LegacyState): MigrationResult {
        val aliases = legacy.entryIdAliases
        fun canonical(id: String): String = aliases[id] ?: id
        val byId = legacy.groups.associateBy { it.id }
        val warnings = mutableListOf<String>()

        fun members(pointer: String?): List<String> = pointer
            ?.let(byId::get)
            ?.memberEntryIds
            ?.map(::canonical)
            .orEmpty()

        val mainGroup = legacy.pointers.main?.let(byId::get)
        val slots = ModelSlots(
            main = legacy.config.slots.main.takeIf { it.isNotEmpty() }?.map(::canonical)
                ?: members(legacy.pointers.main),
            light = legacy.config.slots.light.takeIf { it.isNotEmpty() }?.map(::canonical)
                ?: members(legacy.pointers.light),
            vision = legacy.config.slots.vision.takeIf { it.isNotEmpty() }?.map(::canonical)
                ?: members(legacy.pointers.vision),
            voiceInput = legacy.config.slots.voiceInput.takeIf { it.isNotEmpty() }?.map(::canonical)
                ?: members(legacy.pointers.voiceInput),
            voiceOutput = legacy.config.slots.voiceOutput.takeIf { it.isNotEmpty() }?.map(::canonical)
                ?: members(legacy.pointers.voiceOutput),
        )

        val orderedGroups = legacy.groups.sortedWith(
            compareBy<LegacyModelGroup> { if (it.id == legacy.pointers.main) 0 else 1 }
                .thenBy { it.sortOrder },
        )
        val entriesById = legacy.config.modelEntries.associateBy { canonical(it.id) }
        val updatedEntries = legacy.config.modelEntries.map { it.copy() }.toMutableList()
        val entryIndexes = updatedEntries.mapIndexed { index, entry -> canonical(entry.id) to index }.toMap()
        updatedEntries.indices.forEach { index ->
            val entry = updatedEntries[index]
            val durableId = canonical(entry.id)
            if (durableId != entry.id) updatedEntries[index] = entry.copy(uuid = durableId)
        }
        for (group in orderedGroups) {
            for (rawId in group.memberEntryIds) {
                val entryId = canonical(rawId)
                val index = entryIndexes[entryId] ?: continue
                val entry = updatedEntries[index]
                val overrides = entry.overrides
                val migrated = overrides.copy(
                    defaultThinkingLevel = overrides.defaultThinkingLevel ?: group.defaultThinkingLevel,
                    contextLimitTokens = overrides.contextLimitTokens ?: group.contextLimitTokens,
                    lastContextLimitTokens = overrides.lastContextLimitTokens ?: group.lastContextLimitTokens,
                )
                updatedEntries[index] = entry.copy(overrides = migrated)
            }
        }

        val agentIds = legacy.config.agentLoopModelEntryIds.map(::canonical).toMutableList()
        val seenAgentIds = agentIds.toMutableSet()
        for (groupId in legacy.agentLoopGroupIds) {
            val group = byId[groupId] ?: continue
            for (rawId in group.memberEntryIds) {
                val entryId = canonical(rawId)
                if (seenAgentIds.add(entryId)) agentIds.add(entryId)
            }
        }

        val migratedConfig = legacy.config.copy(
            slots = slots,
            fallbackTrigger = if (legacy.config.slots.main.isEmpty()) {
                mainGroup?.fallbackStrategy ?: legacy.config.fallbackTrigger
            } else {
                legacy.config.fallbackTrigger
            },
            modelEntries = updatedEntries,
            modelGroups = mutableListOf(),
            defaultPrimaryGroupId = null,
            defaultSubGroupId = null,
            voiceInputGroupId = null,
            voiceOutputGroupId = null,
            visionGroupId = null,
            agentLoopModelEntryIds = agentIds,
            agentLoopGroupIds = mutableListOf(),
        )

        return MigrationResult(
            config = migratedConfig,
            sessionBindings = legacy.sessionBindings.map { migrateBinding(it, byId, legacy, ::canonical, entriesById) },
            scheduledBindings = legacy.scheduledBindings.map {
                migrateBotBinding(it, byId, legacy, ::canonical, entriesById, warnings, "ScheduledTask")
            },
            botBindings = legacy.botBindings.map {
                migrateBotBinding(it, byId, legacy, ::canonical, entriesById, warnings)
            },
            warnings = warnings,
        )
    }

    /** Convert a decoded provider backup using its own UUID→durable-id map. */
    fun migrateBackupConfig(config: ProviderConfig): ProviderConfig {
        val aliases = buildMap {
            config.modelEntries.forEach { entry ->
                val durableId = compositeEntryKey(entry.providerInstanceId, entry.baseModel.id)
                put(entry.id, durableId)
                put(durableId, durableId)
            }
        }
        val availableIds = config.modelEntries.mapTo(mutableSetOf()) { entry ->
            compositeEntryKey(entry.providerInstanceId, entry.baseModel.id)
        }
        val groups = config.modelGroups.mapIndexed { index, group ->
            LegacyModelGroup(
                id = group.id,
                memberEntryIds = group.memberEntryIds.toList(),
                fallbackStrategy = group.fallbackStrategy,
                defaultThinkingLevel = group.defaultThinkingLevel,
                contextLimitTokens = group.contextLimitTokens,
                lastContextLimitTokens = group.lastContextLimitTokens,
                sortOrder = index,
            )
        }
        return migrate(
            LegacyState(
                config = config,
                groups = groups,
                pointers = LegacyGroupPointers(
                    main = config.defaultPrimaryGroupId,
                    light = config.defaultSubGroupId,
                    vision = config.visionGroupId,
                    voiceInput = config.voiceInputGroupId,
                    voiceOutput = config.voiceOutputGroupId,
                ),
                agentLoopGroupIds = config.agentLoopGroupIds.toList(),
                entryIdAliases = aliases,
                availableEntryIds = availableIds,
            ),
        ).config
    }

    private fun migrateBotBinding(
        record: LegacyBindingRecord,
        groups: Map<String, LegacyModelGroup>,
        legacy: LegacyState,
        canonical: (String) -> String,
        entriesById: Map<String, ModelEntry>,
        warnings: MutableList<String>,
        sourceName: String = "Bot",
    ): MigratedBinding {
        val raw = record.binding ?: return MigratedBinding(record.id, null, record.modelId)
        if (raw.isBlank()) {
            warnings += "$sourceName ${record.id} has a blank model binding; cleared during slot migration"
            return MigratedBinding(record.id, null)
        }
        when (val parsed = ModelBinding.parse(raw)) {
            is ModelBinding.Entry -> return MigratedBinding(record.id, raw, record.modelId)
            is ModelBinding.Group -> {
                val group = groups[parsed.groupId] ?: return MigratedBinding(record.id, null, record.modelId)
                val members = group.memberEntryIds.map(canonical)
                val selected = members.firstOrNull { it in legacy.availableEntryIds }
                    ?: members.firstOrNull()
                    ?: return MigratedBinding(record.id, null, record.modelId)
                val modelId = entriesById[selected]?.baseModel?.id ?: record.modelId
                return MigratedBinding(record.id, entryBinding(selected), modelId)
            }
            null -> {
                // The shipped Bot editor historically persisted a bare entry id,
                // despite the JSON-only format described by the migration spec.
                // Preserve a known selection by upgrading it to the new JSON form.
                val legacyEntryId = raw.trim()
                if (entriesById.containsKey(canonical(legacyEntryId))) {
                    return MigratedBinding(record.id, entryBinding(canonical(legacyEntryId)))
                }
                warnings += "$sourceName ${record.id} has a malformed model binding; cleared during slot migration"
                return MigratedBinding(record.id, null, record.modelId)
            }
        }
    }

    private fun migrateBinding(
        record: LegacyBindingRecord,
        groups: Map<String, LegacyModelGroup>,
        legacy: LegacyState,
        canonical: (String) -> String,
        entriesById: Map<String, ModelEntry>,
    ): MigratedBinding {
        val parsed = ModelBinding.parse(record.binding)
        if (parsed !is ModelBinding.Group) {
            // Already-entry bindings and null/unknown payloads are intentionally preserved.
            return MigratedBinding(record.id, record.binding, record.modelId)
        }
        val group = groups[parsed.groupId]
            ?: return MigratedBinding(record.id, null)
        val last = parsed.lastEntryId?.let(canonical)
        val selected = last?.takeIf { it in legacy.availableEntryIds }
            ?: group.memberEntryIds.asSequence().map(canonical)
                .firstOrNull { it in legacy.availableEntryIds }
            ?: return MigratedBinding(record.id, null)
        val modelId = entriesById[selected]?.baseModel?.id
        return MigratedBinding(
            id = record.id,
            binding = entryBinding(selected),
            modelId = modelId ?: record.modelId,
        )
    }

    private fun entryBinding(entryId: String): String = ModelBinding.encodeEntry(entryId)
}
