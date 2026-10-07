package com.openminis.app.data.repository

import android.content.SharedPreferences
import com.openminis.app.data.db.AppDatabase
import com.openminis.app.data.db.ProviderConfigMetaKeys
import com.openminis.app.data.db.ProviderConfigSnapshot
import com.openminis.app.data.db.SessionModelBindingMigration
import com.openminis.app.data.db.compositeEntryKey
import com.openminis.app.data.db.toProviderConfig
import com.openminis.app.data.db.toSnapshot
import com.openminis.app.data.migration.LegacyBindingRecord
import com.openminis.app.data.migration.LegacyGroupMigrator
import com.openminis.app.data.migration.LegacyGroupStateParser
import com.openminis.app.data.migration.LegacyState
import com.openminis.app.data.model.FallbackStrategy
import com.openminis.app.data.model.ImageEndpointMode
import com.openminis.app.data.model.LLMModel
import com.openminis.app.data.model.ModelEntry
import com.openminis.app.data.model.ModelOverrides
import com.openminis.app.data.model.ModelSlot
import com.openminis.app.data.model.ProviderConfig
import com.openminis.app.data.model.ProviderCredential
import com.openminis.app.data.model.ProviderInstance
import com.openminis.app.data.model.ProviderType
import com.openminis.app.data.model.SystemVoiceEntries
import com.openminis.app.data.model.SystemVoiceIds
import com.openminis.app.data.model.VoiceProviderTemplate
import com.openminis.app.data.model.hasImageInput
import com.openminis.app.data.model.hasVoiceModality
import com.openminis.app.data.model.isVoiceTemplateSeedShape
import com.openminis.app.data.model.withInferredVoiceModality
import com.openminis.app.data.repository.ProviderRepository.Companion.KEY_LAST_USED_ENTRY
import com.openminis.app.data.repository.ProviderRepository.Companion.MODEL_CACHE_TTL_MS
import com.openminis.app.data.repository.ProviderRepository.Companion.lastFetchKey
import com.openminis.app.provider.thinking.ThinkingRule
import com.openminis.app.provider.thinking.ThinkingRuleCoding
import com.openminis.app.provider.thinking.ThinkingRuleResolver
import com.openminis.app.scheduled.ScheduledTaskBindingUpdate
import com.openminis.app.scheduled.ScheduledTaskManager
import com.openminis.app.util.Sha256
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

/** Record the time of a successful model fetch for [instanceId]. */
internal fun ProviderRepository.markInstanceFetched(instanceId: String) {
    prefs.edit().putLong(lastFetchKey(instanceId), System.currentTimeMillis()).apply()
}

/** Whether the cached model list for [instanceId] is older than the TTL. */
internal fun ProviderRepository.isInstanceStale(instanceId: String): Boolean {
    val last = prefs.getLong(lastFetchKey(instanceId), 0L)
    return last == 0L || (System.currentTimeMillis() - last) > MODEL_CACHE_TTL_MS
}

/**
 * Clear the stored `lastFetchAt` for [instanceId] so the next
 * [triggerBackgroundRefreshIfStale] / [refreshAllModelsIfNeeded] call
 * refreshes it immediately. Called when instance config changes in ways
 * that could alter the set of available models (e.g. base URL rewrite,
 * credential swap). Mirrors iOS's implicit invalidation on
 * `updateInstance` / `removeInstance`.
 */
fun ProviderRepository.invalidateModelCache(instanceId: String) {
    prefs.edit().remove(lastFetchKey(instanceId)).apply()
}

suspend fun ProviderRepository.awaitConfigLoaded() = configLoadComplete.await()

internal fun ProviderRepository.loadConfig(): ProviderConfig = runBlocking { loadConfigSuspending() }

/**
 * [T-android-provider-room-store] DB-first load with three-way
 * reconciliation between provider.db and the legacy JSON mirror:
 *
 *   - DB has rows AND meta.json_sync_hash matches the live mirror's
 *     hash → DB is in sync with what we last wrote. Use DB.
 *   - DB has rows but the hash mismatches → an older app build was
 *     installed at some point, wrote through the JSON path, and
 *     bypassed our DB. The JSON is fresher. Re-import JSON → rewrite
 *     DB → resync hash.
 *   - DB is empty but JSON exists → first launch on a build that
 *     knows about the DB. One-shot import from JSON → DB.
 *   - Both empty → empty config (fresh install).
 *
 * The JSON mirror is the durable downgrade safety net: we keep
 * writing it on every save so the old build always sees current
 * config; if the user round-trips through an old build, the
 * hash check above re-syncs DB to whatever JSON looks like now.
 */
internal suspend fun ProviderRepository.loadConfigSuspending(): ProviderConfig {
    val rawJson = prefs.getString("config", null)
    // [T-android-provider-empty-load-wipe] Distinguish "the DB says zero"
    // from "we could not ask the DB". Swallowing the exception as 0 made a
    // transient DAO failure (locked/mid-write file after a crash) look
    // exactly like a fresh install: the loader fell through to an empty
    // ProviderConfig(), and the next mutation's persistToDbAndMirror wrote
    // that emptiness over a fully populated store — wiping every provider.
    // Observed on Pixel 6 after a ConcurrentModificationException crash
    // left provider.db mid-write: 18 instances became 5.
    var daoReadFailed = false
    val instanceCount = try {
        providerDao.instanceCount()
    } catch (e: Exception) {
        android.util.Log.w("ProviderRepo", "[ProviderStore] DAO instanceCount failed: ${e.message}")
        daoReadFailed = true
        0
    }
    android.util.Log.i(
        "ProviderRepo",
        "[ProviderStore] load: dbInstances=$instanceCount daoFailed=$daoReadFailed " +
            "mirrorBytes=${rawJson?.length ?: -1}",
    )

    val (dbConfig, dbHashStored, dbSnapshot) = if (instanceCount > 0) {
        try {
            val snapshot = ProviderConfigSnapshot(
                instances = providerDao.loadInstances(),
                entries = providerDao.loadEntries(),
                groups = providerDao.loadGroups(),
                loopIds = providerDao.loadAgentLoopIds(),
                meta = providerDao.loadMeta(),
            )
            val cfg = snapshot.toProviderConfig(json)
            val storedHash = snapshot.meta.firstOrNull {
                it.key == ProviderConfigMetaKeys.JSON_SYNC_HASH
            }?.value
            Triple(cfg, storedHash, snapshot)
        } catch (e: Exception) {
            android.util.Log.w("ProviderRepo", "[ProviderStore] DB load failed, falling back to JSON: ${e.message}")
            daoReadFailed = true
            Triple(null, null, null)
        }
    } else {
        Triple(null, null, null)
    }

    if (dbConfig != null) {
        val liveHash = rawJson?.let(::hashJsonMirror)
        val migrationComplete = dbSnapshot?.meta?.firstOrNull {
            it.key == ProviderConfigMetaKeys.LEGACY_GROUPS_MIGRATED_V1
        }?.value == "true"
        if (liveHash == dbHashStored) {
            return if (migrationComplete || dbSnapshot == null) dbConfig
            else migrateLegacyGroupsFromDatabase(dbConfig, dbSnapshot, rawJson)
        }
        if (migrationComplete) {
            android.util.Log.w(
                "ProviderRepo",
                "[ProviderStore] migrated DB is authoritative but mirror hash differs; repairing the mirror",
            )
            return runCatching { persistToDbAndMirror(dbConfig) }.getOrDefault(dbConfig)
        }
        // Hash mismatch: JSON has been written by an older build during
        // a downgrade window. Re-import JSON → reseed DB so DB catches
        // up to the user's actual current config.
        android.util.Log.i(
            "ProviderRepo",
            "[ProviderStore] hash mismatch (stored=${dbHashStored?.take(8)} live=${liveHash?.take(8)}) — re-importing JSON mirror",
        )
    }

    if (rawJson != null) {
        val parsed = try {
            json.decodeFromString<ProviderConfig>(rawJson)
        } catch (e: Exception) {
            android.util.Log.w("ProviderRepo", "[ProviderStore] JSON decode failed: ${e.message}")
            null
        }
        if (parsed != null) {
            val mirrored = try {
                migrateLegacyGroupsFromJson(parsed, rawJson)
            } catch (e: Exception) {
                android.util.Log.w("ProviderRepo", "[ProviderStore] JSON→DB import failed: ${e.message}")
                throw e
            }
            // Migrate the per-user lastUsedEntryId SharedPreferences key
            // from the legacy random-uuid entry id form to the new
            // composite "{instanceId}/{modelId}" shape, using the
            // pre-canonicalization `parsed` entries as the uuid→composite
            // dictionary. Without this, the user's last-picked model on
            // the upgrade-first-launch isn't recognized by
            // lastUsedVisibleEntry() and the next new chat falls through
            // to the newest-provider fallback — i.e. it looks like the
            // upgrade "forgot" the user's recent selection.
            val legacyLastUsed = prefs.getString(KEY_LAST_USED_ENTRY, null)
            if (legacyLastUsed != null && !legacyLastUsed.contains('/')) {
                val rewritten = parsed.modelEntries
                    .firstOrNull { it.uuid == legacyLastUsed }
                    ?.let { compositeEntryKey(it.providerInstanceId, it.baseModel.id) }
                if (rewritten != null) {
                    prefs.edit().putString(KEY_LAST_USED_ENTRY, rewritten).apply()
                    android.util.Log.i(
                        "ProviderRepo",
                        "[ProviderStore] lastUsedEntryId rewritten ${legacyLastUsed.take(8)} → $rewritten",
                    )
                }
            }
            android.util.Log.i(
                "ProviderRepo",
                "[ProviderStore] migrated/synced ${mirrored.instances.size} instances " +
                    "${mirrored.modelEntries.size} entries from JSON → Room",
            )
            return mirrored
        }
    }

    // [T-android-provider-room-store] Last-resort fallback. If DB had
    // rows but the live JSON mirror is unparseable (disk corruption,
    // interrupted write, etc.) AND we couldn't re-import, KEEP THE DB
    // — losing user config is worse than running with a stale mirror.
    // The next successful save will rewrite the mirror and resync the
    // hash. Returning ProviderConfig() here would let the very next
    // mutator's persistToDbAndMirror overwrite the populated DB with
    // an empty config, silently wiping the user's providers.
    if (dbConfig != null) {
        android.util.Log.w(
            "ProviderRepo",
            "[ProviderStore] mirror unreadable + re-import failed; keeping " +
                "${dbConfig.instances.size} DB instances as authoritative",
        )
        val migrationComplete = dbSnapshot?.meta?.firstOrNull {
            it.key == ProviderConfigMetaKeys.LEGACY_GROUPS_MIGRATED_V1
        }?.value == "true"
        return if (migrationComplete || dbSnapshot == null) dbConfig
        else migrateLegacyGroupsFromDatabase(dbConfig, dbSnapshot, rawJson)
    }

    // [T-android-provider-empty-load-wipe] Reaching here means BOTH stores
    // came back empty. That is legitimate on a fresh install — but if the
    // DB read actually FAILED (rather than honestly reporting zero rows),
    // an empty config is a lie we are about to persist over real data.
    // Refuse: throwing keeps _configLoaded false, so ensureConfigLoaded
    // retries on the next access instead of caching the empty value, and
    // no mutation can run against a phantom-empty config.
    if (daoReadFailed) {
        android.util.Log.e(
            "ProviderRepo",
            "[ProviderStore] REFUSING empty config — DB read failed and JSON mirror " +
                "unusable; not overwriting a possibly-populated store",
        )
        throw IllegalStateException(
            "Provider store unreadable (DB read failed, JSON mirror unusable) — " +
                "refusing to load an empty config that would overwrite existing providers",
        )
    }
    return ProviderConfig()
}

internal suspend fun ProviderRepository.migrateLegacyGroupsFromDatabase(
    config: ProviderConfig,
    snapshot: ProviderConfigSnapshot,
    rawJson: String?,
): ProviderConfig {
    val appDatabase = AppDatabase.getInstance(context)
    val sessionRows = appDatabase.chatDao().listSessionsWithModelBinding()
    val botRows = appDatabase.botDao().listBots()
    val taskManager = ScheduledTaskManager(context)
    val tasks = taskManager.list()
    val state = LegacyGroupStateParser.fromDatabase(
        config = config,
        rows = snapshot.groups,
        metaRows = snapshot.meta,
        availableEntryIds = availableEntryIdsForMigration(config),
        sessionBindings = sessionRows.map {
            LegacyBindingRecord(it.id, it.modelBinding, it.modelId)
        },
        scheduledBindings = tasks.map {
            LegacyBindingRecord(it.id, it.modelBinding, it.modelId)
        },
        botBindings = botRows.map { LegacyBindingRecord(it.id, it.modelBinding) },
        legacyAgentLoopGroupIds = snapshot.loopIds
            .filter { it.kind == "group" }
            .sortedBy { it.sortOrder }
            .map { it.targetId },
        legacyJsonConfig = rawJson?.let { raw ->
            runCatching { json.decodeFromString<ProviderConfig>(raw) }.getOrNull()
        },
        json = json,
    )
    return persistLegacyMigration(state, taskManager)
}

internal suspend fun ProviderRepository.migrateLegacyGroupsFromJson(config: ProviderConfig, rawJson: String): ProviderConfig {
    val appDatabase = AppDatabase.getInstance(context)
    val sessionRows = appDatabase.chatDao().listSessionsWithModelBinding()
    val botRows = appDatabase.botDao().listBots()
    val taskManager = ScheduledTaskManager(context)
    val tasks = taskManager.list()
    val state = LegacyGroupStateParser.fromJson(
        config = config,
        rawJson = rawJson,
        availableEntryIds = availableEntryIdsForMigration(config),
        sessionBindings = sessionRows.map {
            LegacyBindingRecord(it.id, it.modelBinding, it.modelId)
        },
        scheduledBindings = tasks.map {
            LegacyBindingRecord(it.id, it.modelBinding, it.modelId)
        },
        botBindings = botRows.map { LegacyBindingRecord(it.id, it.modelBinding) },
    )
    return persistLegacyMigration(state, taskManager)
}

internal fun ProviderRepository.availableEntryIdsForMigration(config: ProviderConfig): Set<String> = buildSet {
    val instances = config.instances.associateBy { it.id }
    for (entry in config.modelEntries) {
        if (entry.isHidden) continue
        val instance = instances[entry.providerInstanceId] ?: continue
        if (!instance.isEnabled || !hasAnyCredential(instance)) continue
        add(compositeEntryKey(entry.providerInstanceId, entry.baseModel.id))
    }
    // These are virtual entries, deliberately outside provider config rows.
    SystemVoiceEntries.all.forEach { add(it.id) }
}

internal suspend fun ProviderRepository.persistLegacyMigration(
    state: LegacyState,
    taskManager: ScheduledTaskManager,
): ProviderConfig {
    val result = LegacyGroupMigrator.migrate(state)
    val oldSessions = state.sessionBindings.associateBy { it.id }
    val sessionUpdates = result.sessionBindings.mapNotNull { migrated ->
        val old = oldSessions[migrated.id] ?: return@mapNotNull null
        if (old.binding == migrated.binding && old.modelId == migrated.modelId) return@mapNotNull null
        SessionModelBindingMigration(migrated.id, migrated.binding, migrated.modelId)
    }
    if (sessionUpdates.isNotEmpty()) {
        AppDatabase.getInstance(context).chatDao().migrateModelBindings(sessionUpdates)
    }

    val botDao = AppDatabase.getInstance(context).botDao()
    val oldBots = state.botBindings.associateBy { it.id }
    for (migrated in result.botBindings) {
        val old = oldBots[migrated.id] ?: continue
        if (old.binding != migrated.binding) {
            botDao.updateModelBinding(migrated.id, migrated.binding)
        }
    }
    result.warnings.forEach { warning ->
        android.util.Log.w("ProviderRepo", "[ProviderStore] $warning")
    }

    // ScheduledTaskStore is SharedPreferences-backed. Rewrite binding fields
    // directly without calling ScheduledTaskManager.update(), which would
    // cancel and re-register alarms despite unchanged trigger times.
    val scheduledUpdates = result.scheduledBindings.associate { migrated ->
        migrated.id to ScheduledTaskBindingUpdate(migrated.binding, migrated.modelId)
    }
    val taskUpdates = taskManager.store().migrateModelBindings(scheduledUpdates)
    if (taskUpdates > 0) {
        android.util.Log.i("ProviderRepo", "[ProviderStore] migrated $taskUpdates scheduled-task model bindings without rescheduling")
    }

    // The migration flag is inserted by replaceAll in the same Room
    // transaction as the new slots. Bindings are already converted; if
    // this write fails, the legacy rows/meta remain and the next load retries.
    return persistToDbAndMirror(result.config, migrationComplete = true)
}

/**
 * Stable hash of the JSON mirror string, used as the in-DB synced-state
 * marker. SHA-256 hex so collisions are negligible. Returns null only
 * if [str] is null (caller normalizes).
 */
internal fun ProviderRepository.hashJsonMirror(str: String): String = Sha256.hex(str)

/**
 * Atomically persist [config] to (DB) + (legacy JSON mirror), with
 * the meta.json_sync_hash kept in lockstep with the mirror we just
 * wrote. Returns the canonicalized config (entry uuids rewritten to
 * the composite "{instanceId}/{modelId}" shape via the snapshot
 * round-trip). Callers should treat the return value as the new
 * authoritative state — using the in-memory pre-call object would
 * leak the legacy uuid form into [_config.value].
 */
internal suspend fun ProviderRepository.persistToDbAndMirror(
    config: ProviderConfig,
    migrationComplete: Boolean = false,
): ProviderConfig {
    // First serialize without the hash so the meta row reflects the
    // exact string we put into prefs (the hash sees the mirror that
    // older builds will read, not a hash-of-itself).
    val mirrorStr = json.encodeToString(ProviderConfig.serializer(), config)
    val mirrorHash = hashJsonMirror(mirrorStr)
    val snapshot = config.toSnapshot(json, jsonSyncHash = mirrorHash)
    providerDao.replaceAll(
        instances = snapshot.instances,
        entries = snapshot.entries,
        loopIds = snapshot.loopIds,
        meta = snapshot.meta,
        migrationComplete = migrationComplete,
    )
    // commit() not apply(): the json_sync_hash we just stored to DB is
    // a hash of THIS mirror string. If apply() queues the disk write
    // and the process dies before it flushes, the next launch sees
    // DB(hash=new) + JSON-on-disk(content=old) → the hash-mismatch
    // path interprets it as "old build wrote during downgrade" and
    // re-imports the stale JSON, blowing away the write that the DB
    // already persisted synchronously. commit() blocks the writer
    // (~5–30ms) but guarantees DB and JSON land together.
    //
    // commit() returns false (no exception) on disk-full / permission
    // failure / corrupted prefs XML. We log so the situation is
    // observable; the DB already holds the new state authoritatively,
    // and a subsequent successful save will resync the mirror + hash.
    // The downside if the next save never comes: a hash mismatch on
    // next cold-start would try to re-import the stale mirror — but
    // the dbConfig-fallback at the end of loadConfigSuspending keeps
    // the DB rows when re-import fails or is rejected, so the user's
    // data is not lost.
    val mirrorWritten = prefs.edit().putString("config", mirrorStr).commit()
    if (!mirrorWritten) {
        android.util.Log.w(
            "ProviderRepo",
            "[ProviderStore] mirror commit() returned false — DB updated " +
                "but JSON write rejected (disk full? prefs corruption?); " +
                "DB remains authoritative",
        )
    }
    // Return canonicalized form so the caller's _config.value reflects
    // entry uuids in composite-key shape from this write forward.
    return snapshot.toProviderConfig(json)
}

/**
 * [T-android-startup-config-stall] Guard for read-modify-write mutators.
 * If the async load hasn't applied yet, load synchronously NOW (on the
 * caller's thread) before the mutator reads `_config.value`. Without this a
 * write that lands during the startup load window would read the empty
 * placeholder, mutate it, and persist — WIPING the user's real config.
 * Idempotent and cheap once loaded (just a volatile read). Synchronized on
 * [configLock] so it can't race the async loader's emit.
 */
// internal (was private): the debug server's read-only provider.* handlers
// read `config.value` directly and must be able to force the lazy load —
// on a cold process they otherwise report an EMPTY config, which reads as
// "all your providers are gone" rather than "not loaded yet".
internal fun ProviderRepository.ensureConfigLoaded() {
    if (_configLoaded.value) return
    synchronized(configLock) {
        if (_configLoaded.value) return
        // [T-android-provider-empty-load-wipe] loadConfig() throws when the
        // store is unreadable. Do NOT let that escape into the caller: this
        // runs from UI handlers (every read-modify-write mutator calls it),
        // and a throw there crashes whichever gesture triggered it. Leaving
        // _configLoaded false means the next access retries, and callers
        // see the empty placeholder — which is safe, because the mutators'
        // saves cannot overwrite a store the loader refused to read.
        val loaded = try {
            loadConfig()
        } catch (e: Exception) {
            android.util.Log.e(
                "ProviderRepo",
                "[ProviderStore] ensureConfigLoaded failed; staying unloaded for retry: ${e.message}",
            )
            return
        }
        _config.value = loaded
        _configLoaded.value = true
    }
    // [T-android-thinking-rules-phase2] Warm the resolver's custom-rule cache once
    // config is available, so the (sync) request builder can read user rules.
    loadAllThinkingRulesIntoCache()
    if (!configLoadComplete.isCompleted) configLoadComplete.complete(Unit)
}

/**
 * Copy all mutable containers and mutable provider objects so a
 * published StateFlow value can never be changed by the queued writer or
 * by a later read-modify-write operation.
 */
internal fun ProviderRepository.copyConfig(config: ProviderConfig): ProviderConfig = config.copy(
    instances = config.instances.map { it.copy() }.toMutableList(),
    modelEntries = config.modelEntries.map { it.copy() }.toMutableList(),
    slots = config.slots.copy(
        main = config.slots.main.toList(),
        light = config.slots.light.toList(),
        vision = config.slots.vision.toList(),
        voiceInput = config.slots.voiceInput.toList(),
        voiceOutput = config.slots.voiceOutput.toList(),
    ),
    agentLoopModelEntryIds = config.agentLoopModelEntryIds.toMutableList(),
)

/**
 * Keep the existing in-memory entry-id contract without doing JSON/Room
 * work on the caller's thread. The full snapshot mapping still runs in the
 * background writer; this small pure transformation only rewrites the
 * legacy random UUID references to their durable composite form.
 */
internal fun ProviderRepository.canonicalizeConfig(config: ProviderConfig): ProviderConfig {
    val canonical = copyConfig(config)
    val idMap = canonical.modelEntries.associate { entry ->
        entry.uuid to compositeEntryKey(entry.providerInstanceId, entry.baseModel.id)
    }
    for (index in canonical.modelEntries.indices) {
        val entry = canonical.modelEntries[index]
        canonical.modelEntries[index] = entry.copy(uuid = idMap[entry.uuid] ?: entry.uuid)
    }
    canonical.slots = canonical.slots.copy(
        main = canonical.slots.main.map { idMap[it] ?: it },
        light = canonical.slots.light.map { idMap[it] ?: it },
        vision = canonical.slots.vision.map { idMap[it] ?: it },
        voiceInput = canonical.slots.voiceInput.map { idMap[it] ?: it },
        voiceOutput = canonical.slots.voiceOutput.map { idMap[it] ?: it },
    )
    for (index in canonical.agentLoopModelEntryIds.indices) {
        val entryId = canonical.agentLoopModelEntryIds[index]
        canonical.agentLoopModelEntryIds[index] = idMap[entryId] ?: entryId
    }
    return canonical
}

internal fun ProviderRepository.saveConfig(config: ProviderConfig) {
    // [T-android-provider-room-store] Double-write: DB + legacy JSON
    // mirror, now handled by the FIFO IO writer. The mirror keeps older
    // app builds able to read current config on downgrade; the DB is the
    // new authoritative store on this build.
    //
    // Snapshot + emit under [configLock] so the writer never observes a
    // list that another mutator is changing. The fresh deep copy also
    // prevents the data-class structural equality trap where a published
    // mutable list is changed in place before StateFlow can compare it.
    // T273 bumps `revision` so every mutation is emitted.
    synchronized(configLock) {
        // The loader refused to read the stored config (see ensureConfigLoaded), so what the
        // mutator edited is the empty placeholder, not the user's providers. Saving it would
        // replace them; drop the change and keep the stored data for the retry.
        if (!_configLoaded.value) {
            android.util.Log.e(
                "ProviderRepo",
                "[ProviderStore] not saving: the stored config has not been loaded successfully",
            )
            return
        }
        // [T-android-provider-empty-load-wipe] No "block empty saves" guard
        // here on purpose: an empty config can be a legitimate "user
        // deleted their last provider" mutation. The wipe is prevented at
        // the source instead (loadConfigSuspending refuses to return a
        // blank config when the DB read failed rather than honestly
        // reporting zero rows).
        // Keep the in-memory entry ids in the same canonical composite
        // shape that persistToDbAndMirror writes. Persistence failures are
        // handled by the worker so callers remain fire-and-forget (the
        // legacy SharedPreferences `apply()` contract). The next
        // successful save resynchronizes both stores.
        val canonical = canonicalizeConfig(config)
        canonical.revision = ProviderConfig.nextRevision()
        _config.value = copyConfig(canonical)

        // Queue a separate deep snapshot. The published value is now
        // immediately available to Compose, while the worker performs
        // JSON serialization, Room replaceAll, and SharedPreferences
        // commit on Dispatchers.IO in FIFO order.
        if (!persistenceQueue.enqueue(copyConfig(canonical))) {
            android.util.Log.e(
                "ProviderRepo",
                "[ProviderStore] async persistence queue is closed; in-memory config was updated only",
            )
        }
    }
}

/**
 * [T-android-provider-emitted-list-cow] A PRIVATE working copy of the
 * current config for a mutator to modify.
 *
 * The lock alone does not make mutation safe, because Compose readers do
 * not take it: `_config.value` is the object collectors are iterating, and
 * mutating its lists in place throws ConcurrentModificationException *in
 * the reader's* frame — reproduced on Pixel 6 as a crash inside
 * UnifiedModelPickerSheet's LazyColumn while three imports ran
 * concurrently:
 *
 *   ArrayList$Itr.checkForComodification → UnifiedModelPickerSheet
 *     → LazyListIntervalContent.<init> → Snapshot.observe
 *
 * saveConfig already EMITS defensive copies; the gap was that the next
 * mutator read that emitted object back and mutated it. Copy-on-write here
 * closes the loop: a mutator never touches a published object, so whatever
 * a reader is iterating stays frozen for the life of that iteration.
 */
internal fun ProviderRepository.workingCopy(): ProviderConfig {
    val live = _config.value
    return live.copy(
        instances = live.instances.toMutableList(),
        modelEntries = live.modelEntries.toMutableList(),
        slots = live.slots.copy(
            main = live.slots.main.toList(),
            light = live.slots.light.toList(),
            vision = live.slots.vision.toList(),
            voiceInput = live.slots.voiceInput.toList(),
            voiceOutput = live.slots.voiceOutput.toList(),
        ),
        agentLoopModelEntryIds = live.agentLoopModelEntryIds.toMutableList(),
    )
}

fun ProviderRepository.addInstance(instance: ProviderInstance): Unit = synchronized(configLock) {
    ensureConfigLoaded()
    val config = workingCopy()
    config.instances.add(instance)
    // Seed built-in model entries ONLY when the seed is appropriate for this
    // instance. Mirrors iOS ProviderConfigStore.addInstance:
    //   - OAuth instances always get seeded (their /v1/models often requires
    //     a manual token or isn't reachable, so the static list is the
    //     baseline UX).
    //   - API-key instances on a third-party OpenAI-compatible base URL
    //     (e.g. xAI Grok https://api.x.ai, vLLM, Ollama, LiteLLM, DeepSeek
    //     via OpenAI shim) MUST NOT inherit `the built-in OpenAI catalog` — that's
    //     where the "Refresh on Grok returns GPT-5.5/5.3-codex" bug came
    //     from. For these, leave entries empty and let refreshModels()
    //     populate from the upstream /v1/models call.
    //   - API-key instances on an official endpoint (no customBaseURL, or
    //     a customBase that points at the canonical host) keep the seed so
    //     UI isn't blank during the first refresh round-trip.
    val shouldSeed = instance.credentialType == ProviderCredential.oauth ||
        !isThirdPartyOpenAICompat(instance)
    if (shouldSeed) {
        val entries = instance.providerType.builtInModels.map { model ->
            ModelEntry(providerInstanceId = instance.id, baseModel = model)
        }
        config.modelEntries.addAll(entries)
    } else {
        android.util.Log.i(
            "ProviderRepo",
            "[ModelList] addInstance: skip built-in seed for third-party OpenAI-compat base " +
                "(label=${instance.label} base=${instance.effectiveBaseURL}) — " +
                "models will populate from upstream /v1/models on refresh",
        )
    }
    // [T-android-provider-voice] Seed voice-template mock models when the
    // base URL matches a voice vendor (MiMo / MiniMax / Doubao …). These
    // vendors have no /v1/models for their voices, so the template list is
    // the only source. Mirrors iOS addInstance + VoiceProviderTemplate.
    val voiceSeeds = VoiceProviderTemplate.mockEntries(instance)
        .filter { seed ->
            config.modelEntries.none {
                it.providerInstanceId == instance.id && it.baseModel.id == seed.baseModel.id
            }
        }
    if (voiceSeeds.isNotEmpty()) {
        config.modelEntries.addAll(voiceSeeds)
        android.util.Log.i(
            "ProviderRepo",
            "[Voice] addInstance seeded ${voiceSeeds.size} voice-template models for ${instance.label}",
        )
    }
    saveConfig(config)
    // Stale from the start so the next background sweep (or an explicit
    // triggerBackgroundRefreshIfStale call) will fetch it.
    invalidateModelCache(instance.id)
}

/**
 * [T-android-provider-voice] Reconcile voice-template seed entries on every
 * launch: add template models a previous build didn't know, remove RETIRED
 * seeds, and heal corrupted modality. Straight port of iOS
 * ProviderConfigStore.ensureVoiceTemplateModels with its two hard-won
 * guards:
 *
 *  - [T-voice-seed-shape-exact] Stale-seed removal matches ONLY the exact
 *    single-flag seed shape (isVoiceTemplateSeedShape). API-fetched models
 *    always carry the paired text side (≥2 flags), so a future API ASR/TTS
 *    model can never be mistaken for a seed and wiped every launch
 *    (regression class of cffbec0e: MiMo launch model count 15→11).
 *  - Heal: a models refresh can overwrite a template entry with text-only
 *    modality from /v1/models; restore the template's authoritative
 *    modality when it diverged.
 */
fun ProviderRepository.ensureVoiceTemplateModels() = synchronized(configLock) {
    ensureConfigLoaded()
    val config = workingCopy()
    var changed = false
    for (instance in config.instances) {
        val tpl = VoiceProviderTemplate.template(instance.customBaseURL) ?: continue
        val templateById = tpl.mockModels.associateBy { it.id }
        val instanceEntries = config.modelEntries.filter { it.providerInstanceId == instance.id }
        val existingIds = instanceEntries.map { it.baseModel.id }.toSet()

        // Remove stale template seeds (exact seed shape only) not in the
        // current template version.
        val staleSeedIds = instanceEntries
            .filter { it.baseModel.isVoiceTemplateSeedShape }
            .map { it.baseModel.id }
            .filter { it !in templateById }
        if (staleSeedIds.isNotEmpty()) {
            config.modelEntries.removeAll {
                it.providerInstanceId == instance.id && it.baseModel.id in staleSeedIds
            }
            changed = true
            android.util.Log.i("ProviderRepo", "[VoiceTemplateMigrate] ${instance.label}: removed stale voice entries: ${staleSeedIds.sorted()}")
        }

        // Add template models missing from this instance.
        val toAdd = templateById.keys - existingIds
        if (toAdd.isNotEmpty()) {
            val newEntries = tpl.mockModels
                .filter { it.id in toAdd }
                .map { ModelEntry(providerInstanceId = instance.id, baseModel = it) }
            config.modelEntries.addAll(newEntries)
            changed = true
            android.util.Log.i("ProviderRepo", "[VoiceTemplateMigrate] ${instance.label}: added ${newEntries.size} new entries: ${toAdd.sorted()}")
        }

        // Heal corrupted modality on surviving template entries.
        for (i in config.modelEntries.indices) {
            val e = config.modelEntries[i]
            if (e.providerInstanceId != instance.id) continue
            val tplModel = templateById[e.baseModel.id] ?: continue
            if (e.baseModel.inputModalities == tplModel.inputModalities &&
                e.baseModel.outputModalities == tplModel.outputModalities
            ) continue
            android.util.Log.i("ProviderRepo", "[VoiceTemplateMigrate] ${instance.label}: healing modality for ${e.baseModel.id}")
            config.modelEntries[i] = e.copy(baseModel = tplModel)
            changed = true
        }
    }
    if (changed) saveConfig(config)
}

/**
 * Whether [instance] points at a third-party OpenAI-compatible host
 * (xAI Grok, vLLM, Ollama, LiteLLM, DeepSeek via OpenAI shim, etc.).
 * For these instances we must never substitute `the built-in OpenAI catalog` as a
 * fallback / seed — those are GPT-only IDs that don't exist upstream.
 * Mirrors iOS `ProviderConfigStore.isThirdPartyOpenAICompat`.
 */
internal fun ProviderRepository.isThirdPartyOpenAICompat(instance: ProviderInstance): Boolean {
    if (instance.providerType != ProviderType.openAI) return false
    val custom = instance.customBaseURL?.lowercase() ?: return false
    val officialHosts = listOf("api.openai.com", "chatgpt.com")
    return officialHosts.none { custom.contains(it) }
}

fun ProviderRepository.updateInstance(instance: ProviderInstance): Unit = synchronized(configLock) {
    ensureConfigLoaded()
    val config = workingCopy()
    val idx = config.instances.indexOfFirst { it.id == instance.id }
    if (idx >= 0) {
        val prior = config.instances[idx]
        // [T-android-image-endpoint-mode] If the base URL or v1-suffix
        // changed, the previously probed image endpoint may not exist on the
        // new upstream — drop the cached resolution so auto mode re-probes.
        // Mirrors iOS ProviderConfigStore.updateInstance. Only touch it when
        // the caller didn't already set it (e.g. the UI clears it itself when
        // the user forces a non-auto mode).
        if ((prior.customBaseURL != instance.customBaseURL ||
                prior.appendV1Suffix != instance.appendV1Suffix) &&
            instance.imageEndpointResolved != null
        ) {
            instance.imageEndpointResolved = null
        }
        config.instances[idx] = instance
        saveConfig(config)
        // Any change that could move the model list (base URL, credential
        // swap, enabled flag, API format) invalidates the cache. Cheap to
        // over-invalidate.
        if (prior.effectiveBaseURL != instance.effectiveBaseURL ||
            prior.credentialType != instance.credentialType ||
            prior.isEnabled != instance.isEnabled ||
            prior.useResponsesAPI != instance.useResponsesAPI
        ) {
            invalidateModelCache(instance.id)
        }
    }
}

fun ProviderRepository.removeInstance(instanceId: String): Unit = synchronized(configLock) {
    ensureConfigLoaded()
    invalidateModelCache(instanceId)
    val config = workingCopy()
    val removedEntryIds = config.modelEntries
        .filter { it.providerInstanceId == instanceId }
        .map { it.id }
        .toSet()
    config.instances.removeAll { it.id == instanceId }
    config.modelEntries.removeAll { it.providerInstanceId == instanceId }

    if (removedEntryIds.isNotEmpty()) {
        config.slots = config.slots.copy(
            main = config.slots.main.filterNot { it in removedEntryIds },
            light = config.slots.light.filterNot { it in removedEntryIds },
            vision = config.slots.vision.filterNot { it in removedEntryIds },
            voiceInput = config.slots.voiceInput.filterNot { it in removedEntryIds },
            voiceOutput = config.slots.voiceOutput.filterNot { it in removedEntryIds },
        )
        config.agentLoopModelEntryIds.removeAll { it in removedEntryIds }
    }
    saveConfig(config)
    deleteApiKey(instanceId)
    // [T-android-thinking-rules-phase2] The instance is gone — drop its custom
    // rules from Room and the resolver cache (they can never fire again).
    runCatching {
        runBlocking { providerDao.deleteThinkingRulesForInstance(instanceId) }
        ThinkingRuleResolver.setCustomRules(instanceId, emptyList())
    }
}

/**
 * [T-android-image-endpoint-mode] Persist the endpoint that last worked for
 * `auto`-mode image generation on [instanceId]. Called by
 * ModelUseOffloadHandler after a successful /images/generations call (cache
 * `imagesGenerations`) or after a route-missing fallback (cache
 * `chatCompletions`). No-op when unchanged so we don't churn the config /
 * iCloud sync on every image call. Does not invalidate the model cache —
 * the endpoint choice never moves the model list.
 */
// [T-android-provider-mutator-lock] `ProviderInstance` has mutable `var`
// fields, so `workingCopy()`'s list copy is not enough on its own — the
// instance objects inside it are still the published ones. Replace the
// element with a `.copy()` rather than writing through to the shared
// object. Called from ModelUseOffloadHandler on an offload worker thread
// during image generation, so the unsynchronized write had no
// happens-before with main-thread readers.
fun ProviderRepository.setImageEndpointResolved(instanceId: String, endpoint: ImageEndpointMode): Unit =
    synchronized(configLock) {
        ensureConfigLoaded()
        val config = workingCopy()
        val idx = config.instances.indexOfFirst { it.id == instanceId }
        if (idx < 0) return@synchronized
        if (config.instances[idx].imageEndpointResolved == endpoint) return@synchronized
        config.instances[idx] = config.instances[idx].copy(imageEndpointResolved = endpoint)
        saveConfig(config)
    }

fun ProviderRepository.instance(id: String): ProviderInstance? =
    _config.value.instances.find { it.id == id }

fun ProviderRepository.enabledInstances(providerType: ProviderType): List<ProviderInstance> =
    _config.value.instances.filter { it.providerType == providerType && it.isEnabled }

fun ProviderRepository.entriesFor(instanceId: String): List<ModelEntry> =
    _config.value.modelEntries
        .filter { it.providerInstanceId == instanceId }
        .sortedWith(releaseRankOrder)

fun ProviderRepository.visibleEntries(instanceId: String): List<ModelEntry> =
    _config.value.modelEntries
        .filter { it.providerInstanceId == instanceId && !it.isHidden }
        .sortedWith(releaseRankOrder)

fun ProviderRepository.allVisibleEntries(): List<ModelEntry> =
    _config.value.let { config ->
        val enabledIds = config.instances.filter { it.isEnabled }.map { it.id }.toSet()
        config.modelEntries
            .filter { it.providerInstanceId in enabledIds && !it.isHidden }
            .sortedWith(releaseRankOrder)
    }

/**
 * Resolve [lastUsedEntryId] to a still-valid, visible, enabled-provider
 * entry — or null when the recorded id was deleted / hidden / its provider
 * disabled. Callers treat null as "fall through to the next tier".
 */
fun ProviderRepository.lastUsedVisibleEntry(): ModelEntry? {
    val id = lastUsedEntryId ?: return null
    return allVisibleEntries().firstOrNull { it.id == id }
}

/**
 * [T-newchat-default-model-fallback-android] Final-tier default for a new
 * chat: the newest text-output model from the newest-added enabled
 * provider. "Newest provider" = max [ProviderInstance.createdAt]; "newest
 * model" = last text-capable entry in add order (modelEntries is appended
 * in add order, so the instance's last matching entry is the most recently
 * added). Image/audio-only models are excluded — a fresh chat must default
 * to something that can produce a text reply.
 *
 * Walks providers newest→oldest so that if the newest provider somehow has
 * no text model (all image/audio), we still land on the newest text model
 * from the next provider rather than returning null.
 */
fun ProviderRepository.newestProviderNewestTextEntry(): ModelEntry? {
    val config = _config.value
    val enabledProviders = config.instances
        .filter { it.isEnabled }
        .sortedByDescending { it.createdAt }
    for (instance in enabledProviders) {
        val textEntry = config.modelEntries
            .filter { it.providerInstanceId == instance.id && !it.isHidden && it.model.isTextOutput }
            .lastOrNull()
        if (textEntry != null) return textEntry
    }
    return null
}

/**
 * [T-android-group-resolve-skip-uncredentialed] Whether [instance] has ANY
 * usable credential — API key, an intentionally keyless compatible endpoint,
 * a manual bearer, or a stored OAuth token.
 */
fun ProviderRepository.hasAnyCredential(instance: ProviderInstance): Boolean {
    if (usableApiKey(instance) != null) return true
    return com.openminis.app.auth.OAuthManager.hasStoredCredential(context, instance.id)
}

/** Ordered, currently usable entries declared by one fixed model slot. */
fun ProviderRepository.availableEntries(slot: ModelSlot): List<ModelEntry> {
    ensureConfigLoaded()
    val config = _config.value
    val declared = config.slots.entries(slot).distinct()
    val entriesById = LinkedHashMap<String, MemberAvailability<ModelEntry>>()
    for (entryId in declared) {
        val systemEntry = if (slot == ModelSlot.voiceInput || slot == ModelSlot.voiceOutput) {
            SystemVoiceEntries.resolve(entryId)
        } else {
            null
        }
        if (systemEntry != null) {
            entriesById[entryId] = MemberAvailability(
                value = systemEntry,
                hidden = false,
                providerEnabled = true,
                credentialed = true,
            )
            continue
        }
        val entry = config.modelEntries.find { it.id == entryId } ?: continue
        val instance = config.instances.find { it.id == entry.providerInstanceId } ?: continue
        entriesById[entryId] = MemberAvailability(
            value = entry,
            hidden = entry.isHidden,
            providerEnabled = instance.isEnabled,
            credentialed = hasAnyCredential(instance),
        )
    }
    return availableMembersInDeclarationOrder(declared, entriesById)
}

fun ProviderRepository.primaryEntry(slot: ModelSlot): ModelEntry? = availableEntries(slot).firstOrNull()

/**
 * [T-android-regenerate-title-submodel] The dedicated title-generation
 * sub-model entry: the first available member of `slots.light`. Returns
 * null when no light-slot entry is available (caller then falls back to
 * the primary model). Single source of truth for both the auto-title path
 * (ChatViewModel.resolveTitleProvider) and the manual Regenerate path
 * (SessionListViewModel.regenerateTitle), mirroring iOS resolveSubEntry.
 */
fun ProviderRepository.resolveTitleLightEntry(): ModelEntry? {
    return primaryEntry(ModelSlot.light)
}

/**
 * [T-disabled-provider-via-slot-android] True when [entryId] resolves
 * to an entry whose provider instance is currently enabled. Used by the
 * settings UI to dim members that won't be reachable at runtime.
 */
fun ProviderRepository.isEntryProviderEnabled(entryId: String): Boolean {
    val config = _config.value
    val entry = config.modelEntries.find { it.id == entryId } ?: return false
    val instance = config.instances.find { it.id == entry.providerInstanceId } ?: return false
    return instance.isEnabled
}

fun ProviderRepository.replaceEntries(instanceId: String, models: List<LLMModel>) = synchronized(configLock) {
    ensureConfigLoaded()
    // Hot path for concurrent autoRefreshModels coroutines (one per
    // enabled instance) — without this lock, two replaceEntries() calls
    // race on the shared config.modelEntries ArrayList. The working copy
    // additionally keeps this refresh off the list Compose is iterating.
    val config = workingCopy()
    // A refresh that was already in flight when the provider was deleted must not bring its
    // models back: the model table has a foreign key to the instance, so every later full
    // save would carry rows the database rejects.
    if (config.instances.none { it.id == instanceId }) {
        android.util.Log.w("ProviderRepo", "replaceEntries: instance $instanceId no longer exists, dropping refresh result")
        return@synchronized
    }
    val existing = config.modelEntries.filter { it.providerInstanceId == instanceId }
    val existingEntryIds = existing.map { it.id }.toSet()

    // Build lookup: baseModel.id → existing entry (prefer non-custom if duplicates exist)
    val existingByModelId = mutableMapOf<String, ModelEntry>()
    for (entry in existing) {
        val key = entry.baseModel.id
        val current = existingByModelId[key]
        if (current == null || current.isCustom) {
            existingByModelId[key] = entry
        }
    }

    // Build new entries, carrying forward uuid / overrides / isHidden from prior entries
    val refreshedModelIds = models.map { it.id }.toSet()
    // [T-android-provider-voice] Template-sourced voice models carry an
    // authoritative modality that API-inferred modality (typically no audio
    // bits) must never overwrite. Mirrors iOS replaceEntries
    // templateModalityById guard (d55cd821).
    val instanceBaseURL = config.instances.firstOrNull { it.id == instanceId }?.customBaseURL
    val voiceTemplate = VoiceProviderTemplate.template(instanceBaseURL)
    val templateVoiceModelById = voiceTemplate?.mockModels
        ?.filter { it.hasVoiceModality }
        ?.associateBy { it.id }
        ?: emptyMap()
    val newEntries = models.map { model ->
        val prior = existingByModelId[model.id]
        // Dedicated ASR/TTS id/name patterns fill the exact voice shape when
        // the API returned no modality info; the template's shape wins last.
        var resolved = model.withInferredVoiceModality()
        templateVoiceModelById[model.id]?.let { tplModel ->
            resolved = resolved.copy(
                inputModalities = tplModel.inputModalities,
                outputModalities = tplModel.outputModalities,
            )
        }
        ModelEntry(
            providerInstanceId = instanceId,
            baseModel = resolved,
            overrides = prior?.overrides ?: ModelOverrides(),
            isCustom = false,
            isHidden = prior?.isHidden ?: false,
            uuid = prior?.id ?: java.util.UUID.randomUUID().toString(),
            userModifiedAt = prior?.userModifiedAt,
        )
    }

    // Keep custom entries that weren't in the refreshed list
    val remainingCustom = existing.filter { it.isCustom && it.baseModel.id !in refreshedModelIds }

    // [T-android-provider-voice] Preserve voice-template SEED entries the
    // /models list didn't return. Vendors like MiMo never expose their
    // ASR/TTS voices via /v1/models, so without this every refresh wipes
    // the seeds (iOS regression cffbec0e: MiMo launch model count 15→11).
    // Only genuine template members are protected — never stray audio
    // entries. Mirrors iOS replaceEntries preservedVoice.
    val preservedVoice = existing.filter { e ->
        !e.isCustom &&
            e.baseModel.id !in refreshedModelIds &&
            e.baseModel.id in templateVoiceModelById
    }

    config.modelEntries.removeAll { it.providerInstanceId == instanceId }
    config.modelEntries.addAll(newEntries)
    config.modelEntries.addAll(remainingCustom)
    if (preservedVoice.isNotEmpty()) {
        config.modelEntries.addAll(preservedVoice)
        android.util.Log.i("ProviderRepo", "[ModelList] replaceEntries preserved ${preservedVoice.size} voice-template seed entries: ${preservedVoice.map { it.baseModel.id }.take(10)}")
    }

    // Prune stale slot member references
    val survivingEntryIds = config.modelEntries.map { it.id }.toSet()
    val prunedEntryIds = existingEntryIds - survivingEntryIds
    if (prunedEntryIds.isNotEmpty()) {
        val suspiciousShrink = existing.size >= 4 && models.size * 2 < existing.size
        if (suspiciousShrink) {
            android.util.Log.w("ProviderRepo", "[ModelList] replaceEntries SUSPICIOUS SHRINK before=${existing.size} after=${models.size} — slot references PRESERVED as stale")
        } else {
            config.slots = config.slots.copy(
                main = config.slots.main.filterNot { it in prunedEntryIds },
                light = config.slots.light.filterNot { it in prunedEntryIds },
                vision = config.slots.vision.filterNot { it in prunedEntryIds },
                voiceInput = config.slots.voiceInput.filterNot { it in prunedEntryIds },
                voiceOutput = config.slots.voiceOutput.filterNot { it in prunedEntryIds },
            )
            // T171: same cascade for agent-loop direct-entry pins —
            // mirrors iOS ProviderConfigStore.replaceEntries (L716).
            // Skipped under the suspicious-shrink branch alongside the
            // slot-member preservation, so a transient API hiccup
            // never silently nukes the user's curated agent-loop set.
            val agentBefore = config.agentLoopModelEntryIds.size
            config.agentLoopModelEntryIds.removeAll { it in prunedEntryIds }
            val agentRemoved = agentBefore - config.agentLoopModelEntryIds.size
            if (agentRemoved > 0) {
                android.util.Log.i("ProviderRepo", "[ModelList] replaceEntries pruned $agentRemoved stale agent-loop entry pins")
            }
        }
    }

    saveConfig(config)
    // Stamp so staleness checks know this instance just refreshed.
    markInstanceFetched(instanceId)
}

// --- Model Entry management ---

fun ProviderRepository.setSlotEntries(slot: ModelSlot, entryIds: List<String>): Unit = synchronized(configLock) {
    ensureConfigLoaded()
    val config = workingCopy()
    val normalized = entryIds.distinct()
    if (config.slots.entries(slot) == normalized) return@synchronized
    config.slots = config.slots.withEntries(slot, normalized)
    saveConfig(config)
}

/** Reorders existing members without silently adding/removing slot entries. */
fun ProviderRepository.reorderSlot(slot: ModelSlot, newOrder: List<String>): Unit = synchronized(configLock) {
    ensureConfigLoaded()
    val config = workingCopy()
    val existing = config.slots.entries(slot)
    val reordered = reorderSlotEntries(existing, newOrder)
    if (existing == reordered) return@synchronized
    config.slots = config.slots.withEntries(slot, reordered)
    saveConfig(config)
}

fun ProviderRepository.setFallbackTrigger(strategy: FallbackStrategy): Unit = synchronized(configLock) {
    ensureConfigLoaded()
    if (_config.value.fallbackTrigger == strategy) return@synchronized
    val config = workingCopy()
    config.fallbackTrigger = strategy
    saveConfig(config)
}

//
// [T-android-provider-mutator-lock] Every read-modify-write mutator below
// holds configLock for its WHOLE body, not just the saveConfig at the end.
//
// These mutate `_config.value`'s inner MutableLists IN PLACE — the very
// lists already handed to collectors (the pickers filter over
// config.modelEntries on the main thread). Mutating them from an IO thread
// while a composition iterates throws ConcurrentModificationException in
// the READER. 5399fe270 removed one such walk (ProviderConfig.equals inside
// StateFlow emission) but left every consumer exposed; the shared mutable
// list is the actual defect, and the lock is what serialises it against
// saveConfig's defensive copy (which only snapshots AFTER the mutation).
// configLock is a plain JVM monitor, so the nested saveConfig re-entry is
// fine — replaceEntries / ensureVoiceTemplateModels already rely on that.

fun ProviderRepository.addEntry(entry: ModelEntry): Unit = synchronized(configLock) {
    ensureConfigLoaded()
    val config = workingCopy()
    if (!entry.isCustom) {
        val exists = config.modelEntries.any {
            it.providerInstanceId == entry.providerInstanceId && it.baseModel.id == entry.baseModel.id
        }
        // Explicit label: a bare `return` in an expression body reads as if
        // it might only leave the synchronized lambda.
        if (exists) return@addEntry
    }
    config.modelEntries.add(entry)
    saveConfig(config)
}

fun ProviderRepository.updateEntry(entry: ModelEntry): Unit = synchronized(configLock) {
    ensureConfigLoaded()
    val config = workingCopy()
    val idx = config.modelEntries.indexOfFirst { it.id == entry.id }
    if (idx >= 0) {
        config.modelEntries[idx] = entry.copy(userModifiedAt = System.currentTimeMillis())
        saveConfig(config)
    }
}

fun ProviderRepository.removeEntry(entryId: String): Unit = synchronized(configLock) {
    ensureConfigLoaded()
    val config = workingCopy()
    config.modelEntries.removeAll { it.id == entryId }
    config.slots = config.slots.copy(
        main = config.slots.main.filterNot { it == entryId },
        light = config.slots.light.filterNot { it == entryId },
        vision = config.slots.vision.filterNot { it == entryId },
        voiceInput = config.slots.voiceInput.filterNot { it == entryId },
        voiceOutput = config.slots.voiceOutput.filterNot { it == entryId },
    )
    // T171: cascade-clean the agent-loop direct-entry pin so the
    // AgentLoopModelsScreen never surfaces a checkmark on a model that
    // no longer exists. Mirrors iOS ProviderConfigStore.removeEntry
    // (Providers/ProviderConfigStore.swift L268).
    config.agentLoopModelEntryIds.removeAll { it == entryId }
    saveConfig(config)
}

/** Set the agent-loop-visible entry ID list (individual model entries). */
fun ProviderRepository.setAgentLoopEntryIds(ids: List<String>): Unit = synchronized(configLock) {
    ensureConfigLoaded()
    val config = workingCopy()
    config.agentLoopModelEntryIds.clear()
    // [T-android-agentloop-dup-key-crash] Dedup at the sink (preserving
    // first-seen order). addAgentLoopEntry guards against dups, but any
    // path writing straight through this sink (cross-device sync merge,
    // reorder write-back, data migration) could otherwise persist a
    // duplicate id — which surfaces downstream as two pinnedEntries with
    // the same id, a duplicate LazyColumn key, and a crash on scroll.
    config.agentLoopModelEntryIds.addAll(ids.distinct())
    saveConfig(config)
}

// T182: thin add/remove helpers used by AgentLoopModelsSection +
// AddAgentLoopModelsSheet / AddAgentLoopGroupsSheet. These wrap the
// set* functions so caller doesn't have to round-trip through the
// existing list (mutating in place would race with a parallel
// saveConfig). Keep insertion order — preserves the order the user
// added items in the picker, mirrors iOS appendIfNeeded behaviour.

/** Append [entryId] to the agent-loop direct-pin list if not already there. */
fun ProviderRepository.addAgentLoopEntry(entryId: String) {
    val cur = _config.value.agentLoopModelEntryIds.toList()
    if (entryId in cur) return
    setAgentLoopEntryIds(cur + entryId)
}

/** Remove [entryId] from the agent-loop direct-pin list. No-op if absent. */
fun ProviderRepository.removeAgentLoopEntry(entryId: String) {
    val cur = _config.value.agentLoopModelEntryIds.toList()
    if (entryId !in cur) return
    setAgentLoopEntryIds(cur.filterNot { it == entryId })
}

// T186: reorder helpers — UI keeps a local mutable copy during
// drag and pushes the final order through here on drop. Validates
// that [newOrder] is a permutation of the current list to defend
// against a stale dragged-from-different-snapshot reorder slipping
// in mid-write (e.g. a cascade-cleanup raced with the drag).
fun ProviderRepository.reorderAgentLoopEntries(newOrder: List<String>) {
    val cur = _config.value.agentLoopModelEntryIds.toSet()
    if (newOrder.toSet() != cur) return
    setAgentLoopEntryIds(newOrder)
}

/**
 * [T-android-provider-reorder] Reorder provider instances (drag-to-sort in
 * the Providers list). Mirrors iOS `ProviderConfigStore.reorderInstances`.
 *
 * [newOrder] is a list of instance ids. Unknown ids are dropped and any
 * instance missing from it is appended in its existing relative order, so a
 * caller that only knows about ONE provider-type section can pass just that
 * section's ids and leave the rest untouched — which is exactly what the
 * per-section drag in ProviderListScreen does.
 *
 * Persistence: `sort_order` is derived from list position at save time
 * (ProviderConfigMapping writes `sortOrder = idx`) and the DAO reads back
 * `ORDER BY sort_order ASC`, so permuting the list IS the persistence — no
 * per-row column write is needed.
 *
 * Unlike iOS there is no dirty-marking step here: Android keeps provider
 * config local-only (Room + the JSON mirror), with no CloudKit upload, so
 * the "pure reorder doesn't mark dirty" bug iOS had to fix has no analogue.
 * `saveConfig` bumps `revision`, which is the Android-side equivalent
 * concern — it guarantees the StateFlow re-emits even though a pure
 * permutation compares equal under data-class structural equality.
 */
fun ProviderRepository.reorderInstances(newOrder: List<String>): Unit = synchronized(configLock) {
    ensureConfigLoaded()
    val config = workingCopy()
    val current = config.instances.toList()
    if (current.isEmpty()) return

    val byId = current.associateBy { it.id }
    val seen = LinkedHashSet<String>()
    val reordered = ArrayList<ProviderInstance>(current.size)
    for (id in newOrder) {
        val inst = byId[id] ?: continue      // drop unknown ids
        if (!seen.add(id)) continue          // drop duplicates
        reordered.add(inst)
    }
    // Anything the caller didn't mention keeps its existing relative order.
    for (inst in current) {
        if (seen.add(inst.id)) reordered.add(inst)
    }

    // No-op guard: skip the DB write + StateFlow churn when nothing moved.
    if (reordered.map { it.id } == current.map { it.id }) return

    // [T-android-reorder-unlocked-mutation] Mutate under configLock.
    //
    // `config.instances` is the shared MutableList inside _config.value,
    // and clear()/addAll() ran unprotected here — exactly the window the
    // configLock KDoc above was written to close (it cites the
    // "ConcurrentModificationException → ArrayList.next" crash seen on
    // Pixel 6 / 4a). Concurrent readers iterate that same list with no
    // lock: hasFoldedShadowDuplicates() and shadowVoiceProviders() are
    // called from ProviderListScreen during composition — on the main
    // thread, and recomposition fires precisely BECAUSE this reorder just
    // emitted — while the background model-refresh fan-out reads it too.
    //
    // The empty window was the worse half: a saveConfig snapshotting
    // between clear() and addAll() would persist ZERO instances to Room
    // and the JSON mirror, wiping the user's providers.
    //
    // synchronized is reentrant, so the nested saveConfig (which takes the
    // same lock) is fine. Matches ensureVoiceTemplateModels / replaceEntries.
    synchronized(configLock) {
        config.instances.clear()
        config.instances.addAll(reordered)
        saveConfig(config)
    }
}

/**
 * Resolve the effective model entries visible to the agent loop (minis-model-use).
 * Expands groups to their members, unions with individual entries, dedupes by ID,
 * and filters to entries of enabled provider instances. Mirrors iOS
 * ProviderConfigStore.resolvedAgentLoopEntries.
 */
fun ProviderRepository.resolvedAgentLoopEntries(): List<ModelEntry> {
    val config = _config.value
    val enabledIds = config.instances.filter { it.isEnabled }.map { it.id }.toSet()
    val seen = mutableSetOf<String>()
    val out = mutableListOf<ModelEntry>()

    fun consider(entry: ModelEntry) {
        if (entry.providerInstanceId !in enabledIds) return
        if (!seen.add(entry.id)) return
        out.add(entry)
    }

    // Individual entries first (preserves the order the user arranged in UI)
    for (id in config.agentLoopModelEntryIds) {
        config.modelEntries.find { it.id == id }?.let(::consider)
    }
    return out
}

/** True when the vision slot can provide an enabled image-capable entry. */
fun ProviderRepository.hasVisionSlotConfigured(): Boolean {
    return resolveVisionCandidates().isNotEmpty()
}

/** Display name of the first available vision entry, or null. */
fun ProviderRepository.visionSlotName(): String? {
    return primaryEntry(ModelSlot.vision)?.model?.displayName
}

/**
 * Ordered vision-capable fail-over candidates from the vision slot.
 * The slot's declared order is stable; load balancing is intentionally not
 * used. Returns [] when no member is usable — ReadImageTool then returns a
 * clear failure text. As before, credentials are checked at request time so
 * read_image remains exposed and can report a useful provider error.
 */
fun ProviderRepository.resolveVisionCandidates(): List<Pair<ProviderInstance, ModelEntry>> {
    ensureConfigLoaded()
    val config = _config.value

    fun providerEntry(memberId: String): Pair<ProviderInstance, ModelEntry>? {
        val entry = config.modelEntries.find { it.id == memberId } ?: return null
        if (entry.isHidden) return null
        val inst = config.instances.find { it.id == entry.providerInstanceId } ?: return null
        if (!inst.isEnabled || !entry.model.hasImageInput) return null
        return inst to entry
    }

    val members = config.slots.vision.distinct().mapNotNull { providerEntry(it) }
    val out = mutableListOf<Pair<ProviderInstance, ModelEntry>>()
    for (m in members) {
        if (out.none { it.second.id == m.second.id }) out.add(m)
    }
    return out
}

// --- Thinking rules (custom) [T-android-thinking-rules-phase2] ---
//
// User-authored rules live in provider.db (provider_thinking_rules), keyed by
// provider-instance id, in sort_order priority order. Built-in vendor rules are
// never stored. On every mutation we publish the instance's rules into the
// ThinkingRuleResolver cache so the (sync) request-builder can read them.

/** Load one instance's custom rules from Room, in stored order. */
fun ProviderRepository.thinkingRules(instanceId: String): List<ThinkingRule> = runBlocking {
    runCatching { providerDao.loadThinkingRules(instanceId).map { ThinkingRuleCoding.toRule(it) } }
        .getOrDefault(emptyList())
}

/** The persisted ids for one instance's custom rules, parallel to [thinkingRules]. */
fun ProviderRepository.thinkingRuleIds(instanceId: String): List<String> = runBlocking {
    runCatching { providerDao.loadThinkingRules(instanceId).map { it.id } }.getOrDefault(emptyList())
}

/** First model id served by [instanceId], for the resolution-trace sample. Null if none. */
fun ProviderRepository.firstModelId(instanceId: String): String? {
    ensureConfigLoaded()
    return _config.value.modelEntries.firstOrNull { it.providerInstanceId == instanceId }?.model?.id
}

/** Warm the resolver cache with every instance's custom rules (called on config load). */
fun ProviderRepository.loadAllThinkingRulesIntoCache() {
    runCatching {
        val rows = runBlocking { providerDao.loadAllThinkingRules() }
        val byInstance = rows.groupBy { it.providerInstanceId }
            .mapValues { (_, rs) -> rs.sortedBy { it.sortOrder }.map { ThinkingRuleCoding.toRule(it) } }
        ThinkingRuleResolver.setAllCustomRules(byInstance)
    }
}

internal fun ProviderRepository.republishThinkingCache(instanceId: String) {
    ThinkingRuleResolver.setCustomRules(instanceId, thinkingRules(instanceId))
}

/**
 * Insert or update a custom rule. [id] null ⇒ new rule minted at the TOP of the
 * list (position 0) — a rule overriding a built-in is useless below it; existing
 * rules shift down. A non-null [id] updates in place, preserving position.
 * Returns the rule id.
 */
fun ProviderRepository.saveThinkingRule(instanceId: String, rule: ThinkingRule, id: String? = null): String = runBlocking {
    val existing = providerDao.loadThinkingRules(instanceId).toMutableList()
    val ruleId = id ?: java.util.UUID.randomUUID().toString()
    val idx = existing.indexOfFirst { it.id == ruleId }
    if (idx >= 0) {
        // Update in place at its current sort_order.
        existing[idx] = ThinkingRuleCoding.toEntity(rule, ruleId, instanceId, existing[idx].sortOrder)
    } else {
        // New rule at the top; everything else shifts down.
        existing.add(0, ThinkingRuleCoding.toEntity(rule, ruleId, instanceId, 0))
    }
    val renumbered = existing.mapIndexed { i, e -> e.copy(sortOrder = i) }
    providerDao.replaceThinkingRules(instanceId, renumbered)
    republishThinkingCache(instanceId)
    ruleId
}

/** Delete a custom rule by id. Hard delete — Android provider config is local-only,
 *  so there is no sync channel that could resurrect it (no tombstone needed). */
fun ProviderRepository.deleteThinkingRule(instanceId: String, id: String) = runBlocking {
    providerDao.deleteThinkingRule(id)
    // Renumber survivors so sort_order stays dense.
    val survivors = providerDao.loadThinkingRules(instanceId)
        .sortedBy { it.sortOrder }
        .mapIndexed { i, e -> e.copy(sortOrder = i) }
    providerDao.replaceThinkingRules(instanceId, survivors)
    republishThinkingCache(instanceId)
}

/** Reorder an instance's custom rules to match [orderedIds] (a permutation). */
fun ProviderRepository.reorderThinkingRules(instanceId: String, orderedIds: List<String>) = runBlocking {
    val byId = providerDao.loadThinkingRules(instanceId).associateBy { it.id }
    val reordered = orderedIds.mapNotNull { byId[it] }
        .mapIndexed { i, e -> e.copy(sortOrder = i) }
    // Keep any id the caller omitted (defensive against a partial list) appended.
    val omitted = byId.values.filter { it.id !in orderedIds }.map { it }
    providerDao.replaceThinkingRules(instanceId, reordered + omitted)
    republishThinkingCache(instanceId)
}

/**
 * Built-in rules relevant to THIS instance, for the Provider-detail UI. Mirrors iOS
 * builtInRulesForDisplay: resolve the vendor context from the instance's base URL,
 * then keep every AllModels-scoped rule (endpoint/provider-type defaults) plus any
 * ModelPattern rule the provider actually serves a matching model for. An empty
 * catalog keeps everything (list must not be mysteriously empty before first fetch).
 */
fun ProviderRepository.builtInThinkingRulesForDisplay(instanceId: String): List<ThinkingRule> {
    ensureConfigLoaded()
    val config = _config.value
    val inst = config.instances.find { it.id == instanceId } ?: return emptyList()
    val base = (inst.effectiveBaseURL ?: "").lowercase()
    val ctx = com.openminis.app.provider.thinking.ThinkingResolveContext(
        modelId = "",
        supportsReasoning = null,
        declaredEffortValues = null,
        level = com.openminis.app.data.model.ThinkingLevel.OFF,
        maxTokens = 0,
        isOpenRouter = base.contains("openrouter.ai"),
        usesUnifiedReasoningEffort = base.contains("volces") || base.contains("ark.") || base.contains("venice.ai"),
        isMistral = base.contains("mistral.ai"),
        isDashScope = base.contains("dashscope"),
        offEffort = null,
    )
    val modelIds = config.modelEntries.filter { it.providerInstanceId == instanceId }.map { it.model.id }
    return ThinkingRuleResolver.builtInRules(ctx).filter { rule ->
        when (rule.scope) {
            is ThinkingRule.Scope.AllModels -> true
            is ThinkingRule.Scope.ModelPattern ->
                modelIds.isEmpty() || modelIds.any { rule.scope.matches(it) }
        }
    }
}

/**
 * Ensure the Voice Input slot has its system defaults when the user
 * hasn't configured one. Seeds "Voice Input" with the System ASR sentinel
 * entries (device SpeechRecognizer online/offline). Sentinel composite ids
 * match iOS so a config moved cross-platform keeps its selection. No-op
 * when a valid binding already exists. Mirrors iOS
 * ProviderConfigStore.ensureDefaultVoiceInputSlot.
 */
fun ProviderRepository.ensureDefaultVoiceInputSlot(): String? = synchronized(configLock) {
    ensureConfigLoaded()
    val config = workingCopy()
    if (config.slots.voiceInput.isNotEmpty()) return config.slots.voiceInput.first()
    val sentinel = SystemVoiceIds.BUILTIN_PROVIDER_ID
    val ids = listOf(
        "$sentinel/${SystemVoiceIds.SYSTEM_ASR_ONLINE}",
        "$sentinel/${SystemVoiceIds.SYSTEM_ASR_OFFLINE}",
    )
    config.slots = config.slots.copy(voiceInput = ids)
    saveConfig(config)
    return ids.first()
}

/**
 * Ensure the Voice Output slot has its system default. Seeds
 * "Voice Output" with the System TTS auto sentinel (device TextToSpeech,
 * best voice per reply language). Mirrors iOS ensureDefaultVoiceOutputSlot.
 */
fun ProviderRepository.ensureDefaultVoiceOutputSlot(): String? = synchronized(configLock) {
    ensureConfigLoaded()
    val config = workingCopy()
    if (config.slots.voiceOutput.isNotEmpty()) return config.slots.voiceOutput.first()
    val id = "${SystemVoiceIds.BUILTIN_PROVIDER_ID}/${SystemVoiceIds.SYSTEM_TTS}"
    config.slots = config.slots.copy(voiceOutput = listOf(id))
    saveConfig(config)
    return id
}
