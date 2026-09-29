package com.openminis.app.data.db

import com.openminis.app.data.model.FallbackStrategy
import com.openminis.app.data.model.ImageEndpointMode
import com.openminis.app.data.model.LLMModel
import com.openminis.app.data.model.ModelEntry
import com.openminis.app.data.model.ModelGroup
import com.openminis.app.data.model.ModelOverrides
import com.openminis.app.data.model.ModelSlot
import com.openminis.app.data.model.ModelSlots
import com.openminis.app.data.model.ProviderConfig
import com.openminis.app.data.model.ProviderCredential
import com.openminis.app.data.model.ProviderInstance
import com.openminis.app.data.model.ProviderType
import com.openminis.app.data.model.RoutingStrategy
import com.openminis.app.data.model.ThinkingLevel
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json

/**
 * Bidirectional ProviderConfig ↔ Room-entity mapping. Bundles all
 * five tables into one Snapshot so saveConfig writes them atomically
 * via [ProviderConfigDao.replaceAll].
 *
 * Entry IDs are rewritten to the composite "{instanceId}/{modelId}"
 * shape in [toSnapshot] so DB rows and the legacy JSON mirror share
 * the same id semantics post-migration. Slot memberships and
 * agentLoopModelEntryIds use that same durable key. Legacy group rows
 * remain read-only migration input and are never written by new snapshots.
 */
data class ProviderConfigSnapshot(
    val instances: List<ProviderInstanceEntity>,
    val entries: List<ProviderModelEntryEntity>,
    val groups: List<ProviderModelGroupEntity>,
    val loopIds: List<ProviderAgentLoopIdEntity>,
    val meta: List<ProviderConfigMetaEntity>,
)

object ProviderConfigMetaKeys {
    const val SLOT_MAIN = "slots.main"
    const val SLOT_LIGHT = "slots.light"
    const val SLOT_VISION = "slots.vision"
    const val SLOT_VOICE_INPUT = "slots.voiceInput"
    const val SLOT_VOICE_OUTPUT = "slots.voiceOutput"
    const val FALLBACK_TRIGGER = "slots.fallbackTrigger"
    const val LEGACY_GROUPS_MIGRATED_V1 = "groups_migrated_v1"
    const val DEFAULT_PRIMARY_GROUP_ID = "default_primary_group_id"
    const val DEFAULT_SUB_GROUP_ID = "default_sub_group_id"
    // [T-android-provider-voice] Voice Input / Voice Output group bindings
    // (mirrors iOS provider_local_kv voiceInputGroupId / voiceOutputGroupId).
    // Meta KV rows are additive — no Room schema migration needed.
    const val VOICE_INPUT_GROUP_ID = "voice_input_group_id"
    const val VOICE_OUTPUT_GROUP_ID = "voice_output_group_id"
    // [T-android-vision-group / GH#182] Vision Group pointer (per-device meta KV).
    const val VISION_GROUP_ID = "vision_group_id"
    const val JSON_SYNC_HASH = "json_sync_hash"
    val LEGACY_GROUP_META_KEYS = setOf(
        DEFAULT_PRIMARY_GROUP_ID,
        DEFAULT_SUB_GROUP_ID,
        VOICE_INPUT_GROUP_ID,
        VOICE_OUTPUT_GROUP_ID,
        VISION_GROUP_ID,
    )
    private const val CLEARTEXT_HTTP_APPROVED_ORIGIN_PREFIX = "cleartext_http_approved_origin:"
    fun cleartextHttpApprovedOrigin(instanceId: String): String =
        CLEARTEXT_HTTP_APPROVED_ORIGIN_PREFIX + instanceId
}

/**
 * Build a [ProviderConfigSnapshot] from [config]. The provided
 * [jsonForBlobs] must be the same kotlinx.serialization Json instance
 * the repository uses elsewhere — its `ignoreUnknownKeys` /
 * `encodeDefaults` settings shape every baseModel / overrides /
 * memberEntryIds blob on disk.
 *
 * [jsonSyncHash] is the hash of the legacy mirror JSON we just wrote
 * (or about to write) for downgrade-detection on next load. Pass null
 * when called from the load path's first-time JSON→DB import (we'll
 * compute and store it immediately after).
 */
fun ProviderConfig.toSnapshot(
    jsonForBlobs: Json,
    jsonSyncHash: String? = null,
): ProviderConfigSnapshot {
    // Map every legacy entry uuid → composite "{instanceId}/{modelId}".
    // Duplicate composite keys are theoretically impossible (one entry
    // per (instance, model)) — if we see one, last-write-wins on the
    // map, but both legacy uuids point at the same composite, so group /
    // agent-loop references converge harmlessly.
    val idMap = HashMap<String, String>(modelEntries.size)
    for (entry in modelEntries) {
        idMap[entry.uuid] = compositeEntryKey(entry.providerInstanceId, entry.baseModel.id)
    }

    val instanceRows = instances.mapIndexed { idx, inst ->
        ProviderInstanceEntity(
            id = inst.id,
            label = inst.label,
            providerType = inst.providerType.name,
            credentialType = inst.credentialType.name,
            customBaseURL = inst.customBaseURL,
            appendV1Suffix = if (inst.appendV1Suffix) 1 else 0,
            useResponsesAPI = if (inst.useResponsesAPI) 1 else 0,
            azureMode = if (inst.azureMode) 1 else 0,
            // [GH#68] Persist the picker choice + probe cache; auto is stored
            // explicitly (not null) so a legit "auto" survives round-trips too.
            imageEndpointMode = inst.imageEndpointMode.name,
            imageEndpointResolved = inst.imageEndpointResolved?.name,
            customUserAgent = inst.customUserAgent,
            isEnabled = if (inst.isEnabled) 1 else 0,
            sortOrder = idx,
            createdAt = inst.createdAt,
        )
    }

    // Bucket entries by instance so sort_order is per-instance contiguous,
    // matching the loadEntries() ORDER BY clause.
    val entryRows = ArrayList<ProviderModelEntryEntity>(modelEntries.size)
    val grouped = modelEntries.groupBy { it.providerInstanceId }
    for ((instanceId, entries) in grouped) {
        entries.forEachIndexed { idx, e ->
            val composite = idMap[e.uuid] ?: compositeEntryKey(instanceId, e.baseModel.id)
            entryRows.add(
                ProviderModelEntryEntity(
                    id = composite,
                    providerInstanceId = instanceId,
                    baseModelJson = jsonForBlobs.encodeToString(LLMModel.serializer(), e.baseModel),
                    overridesJson = if (e.overrides.isEmpty) null
                        else jsonForBlobs.encodeToString(ModelOverrides.serializer(), e.overrides),
                    isCustom = if (e.isCustom) 1 else 0,
                    isHidden = if (e.isHidden) 1 else 0,
                    sortOrder = idx,
                    userModifiedAt = e.userModifiedAt,
                )
            )
        }
    }

    // provider_model_groups is retained as a read-only migration source. New
    // snapshots deliberately never write rows back to that table.
    val groupRows = emptyList<ProviderModelGroupEntity>()

    val loopRows = ArrayList<ProviderAgentLoopIdEntity>(
        agentLoopModelEntryIds.size + agentLoopGroupIds.size,
    )
    agentLoopModelEntryIds.forEachIndexed { idx, id ->
        loopRows.add(
            ProviderAgentLoopIdEntity(
                kind = "entry",
                targetId = idMap[id] ?: id,
                sortOrder = idx,
            )
        )
    }

    val metaRows = mutableListOf<ProviderConfigMetaEntity>()
    instances.forEach { inst ->
        inst.cleartextHttpApprovedOrigin?.let { origin ->
            metaRows.add(
                ProviderConfigMetaEntity(
                    ProviderConfigMetaKeys.cleartextHttpApprovedOrigin(inst.id),
                    origin,
                )
            )
        }
    }
    defaultPrimaryGroupId?.let {
        metaRows.add(ProviderConfigMetaEntity(ProviderConfigMetaKeys.DEFAULT_PRIMARY_GROUP_ID, it))
    }
    defaultSubGroupId?.let {
        metaRows.add(ProviderConfigMetaEntity(ProviderConfigMetaKeys.DEFAULT_SUB_GROUP_ID, it))
    }
    voiceInputGroupId?.let {
        metaRows.add(ProviderConfigMetaEntity(ProviderConfigMetaKeys.VOICE_INPUT_GROUP_ID, it))
    }
    voiceOutputGroupId?.let {
        metaRows.add(ProviderConfigMetaEntity(ProviderConfigMetaKeys.VOICE_OUTPUT_GROUP_ID, it))
    }
    visionGroupId?.let {
        metaRows.add(ProviderConfigMetaEntity(ProviderConfigMetaKeys.VISION_GROUP_ID, it))
    }
    val stringListSerializer = ListSerializer(String.serializer())
    val slotKeys = mapOf(
        ModelSlot.main to ProviderConfigMetaKeys.SLOT_MAIN,
        ModelSlot.light to ProviderConfigMetaKeys.SLOT_LIGHT,
        ModelSlot.vision to ProviderConfigMetaKeys.SLOT_VISION,
        ModelSlot.voiceInput to ProviderConfigMetaKeys.SLOT_VOICE_INPUT,
        ModelSlot.voiceOutput to ProviderConfigMetaKeys.SLOT_VOICE_OUTPUT,
    )
    slotKeys.forEach { (slot, key) ->
        metaRows.add(
            ProviderConfigMetaEntity(
                key,
                jsonForBlobs.encodeToString(stringListSerializer, slots.entries(slot)),
            ),
        )
    }
    metaRows.add(ProviderConfigMetaEntity(ProviderConfigMetaKeys.FALLBACK_TRIGGER, fallbackTrigger.name))
    jsonSyncHash?.let {
        metaRows.add(ProviderConfigMetaEntity(ProviderConfigMetaKeys.JSON_SYNC_HASH, it))
    }

    return ProviderConfigSnapshot(instanceRows, entryRows, groupRows, loopRows, metaRows)
}

/**
 * Reverse-map a snapshot back to [ProviderConfig]. Entries' uuid
 * field is set to the composite-key id stored in DB — so once we round-trip,
 * group/agentLoop refs in the mirror JSON also point at the composite shape.
 */
fun ProviderConfigSnapshot.toProviderConfig(jsonForBlobs: Json): ProviderConfig {
    val metaMap = this.meta.associate { it.key to it.value }
    val instances = this.instances.map { row ->
        ProviderInstance(
            id = row.id,
            label = row.label,
            providerType = ProviderType.valueOf(row.providerType),
            credentialType = ProviderCredential.valueOf(row.credentialType),
            isEnabled = row.isEnabled != 0,
            createdAt = row.createdAt,
            customBaseURL = row.customBaseURL,
            cleartextHttpApprovedOrigin = metaMap[
                ProviderConfigMetaKeys.cleartextHttpApprovedOrigin(row.id)
            ],
            appendV1Suffix = row.appendV1Suffix != 0,
            customUserAgent = row.customUserAgent,
            useResponsesAPI = row.useResponsesAPI != 0,
            azureMode = row.azureMode != 0,
            // [GH#68] Safe parse: null (pre-migration rows) or an unknown
            // name from a future build falls back to auto / no cache rather
            // than throwing and wiping the whole provider load.
            imageEndpointMode = row.imageEndpointMode?.let { m ->
                runCatching { ImageEndpointMode.valueOf(m) }.getOrNull()
            } ?: ImageEndpointMode.auto,
            imageEndpointResolved = row.imageEndpointResolved?.let { m ->
                runCatching { ImageEndpointMode.valueOf(m) }.getOrNull()
            },
        )
    }.toMutableList()

    val entries = this.entries.map { row ->
        val baseModel = jsonForBlobs.decodeFromString(LLMModel.serializer(), row.baseModelJson)
        val overrides = row.overridesJson?.let {
            jsonForBlobs.decodeFromString(ModelOverrides.serializer(), it)
        } ?: ModelOverrides()
        ModelEntry(
            providerInstanceId = row.providerInstanceId,
            baseModel = baseModel,
            overrides = overrides,
            isCustom = row.isCustom != 0,
            isHidden = row.isHidden != 0,
            uuid = row.id,
            userModifiedAt = row.userModifiedAt,
        )
    }.toMutableList()

    val stringListSerializer = ListSerializer(String.serializer())
    val migrationComplete = metaMap[ProviderConfigMetaKeys.LEGACY_GROUPS_MIGRATED_V1] == "true"
    val groups = if (migrationComplete) mutableListOf() else this.groups.map { row ->
        ModelGroup(
            id = row.id,
            name = row.name,
            memberEntryIds = jsonForBlobs
                .decodeFromString(stringListSerializer, row.memberEntryIdsJson)
                .toMutableList(),
            strategy = RoutingStrategy.valueOf(row.strategy),
            fallbackStrategy = FallbackStrategy.valueOf(row.fallbackStrategy),
            // [T-android-thinking-level-arch] decoded() (not valueOf()) so a
            // level string a NEWER build persisted (e.g. "MAX"/"ULTRA") can't
            // throw and blow up the whole DB load — which would fall back to the
            // JSON mirror, which fails identically on the same enum value,
            // wiping all providers/groups from the UI. Unknown → XHIGH.
            defaultThinkingLevel = row.defaultThinkingLevel?.let { ThinkingLevel.decoded(it) },
            contextLimitTokens = row.contextLimitTokens,
            lastContextLimitTokens = row.lastContextLimitTokens,
        )
    }.toMutableList()

    val entryLoopIds = this.loopIds.filter { it.kind == "entry" }
        .sortedBy { it.sortOrder }
        .map { it.targetId }
        .toMutableList()
    val groupLoopIds = if (migrationComplete) mutableListOf() else this.loopIds.filter { it.kind == "group" }
        .sortedBy { it.sortOrder }
        .map { it.targetId }
        .toMutableList()

    fun slotEntries(key: String): List<String> = metaMap[key]?.let { raw ->
        runCatching { jsonForBlobs.decodeFromString(stringListSerializer, raw) }.getOrNull()
    } ?: emptyList()
    val slots = ModelSlots(
        main = slotEntries(ProviderConfigMetaKeys.SLOT_MAIN),
        light = slotEntries(ProviderConfigMetaKeys.SLOT_LIGHT),
        vision = slotEntries(ProviderConfigMetaKeys.SLOT_VISION),
        voiceInput = slotEntries(ProviderConfigMetaKeys.SLOT_VOICE_INPUT),
        voiceOutput = slotEntries(ProviderConfigMetaKeys.SLOT_VOICE_OUTPUT),
    )
    val fallbackTrigger = metaMap[ProviderConfigMetaKeys.FALLBACK_TRIGGER]
        ?.let { runCatching { FallbackStrategy.valueOf(it) }.getOrNull() }
        ?: FallbackStrategy.default

    return ProviderConfig(
        instances = instances,
        modelEntries = entries,
        slots = slots,
        fallbackTrigger = fallbackTrigger,
        modelGroups = groups,
        defaultPrimaryGroupId = metaMap[ProviderConfigMetaKeys.DEFAULT_PRIMARY_GROUP_ID].takeUnless { migrationComplete },
        defaultSubGroupId = metaMap[ProviderConfigMetaKeys.DEFAULT_SUB_GROUP_ID].takeUnless { migrationComplete },
        voiceInputGroupId = metaMap[ProviderConfigMetaKeys.VOICE_INPUT_GROUP_ID].takeUnless { migrationComplete },
        voiceOutputGroupId = metaMap[ProviderConfigMetaKeys.VOICE_OUTPUT_GROUP_ID].takeUnless { migrationComplete },
        visionGroupId = metaMap[ProviderConfigMetaKeys.VISION_GROUP_ID].takeUnless { migrationComplete },
        agentLoopModelEntryIds = entryLoopIds,
        agentLoopGroupIds = groupLoopIds,
    )
}

/** Composite key shape used as ModelEntry id in DB and forward in JSON mirror. */
fun compositeEntryKey(instanceId: String, modelId: String): String = "$instanceId/$modelId"
