package com.openminis.app.data.repository

import android.content.Context
import android.content.SharedPreferences
import com.openminis.app.data.db.ProviderConfigDao
import com.openminis.app.data.db.ProviderDatabase
import com.openminis.app.data.model.ModelEntry
import com.openminis.app.data.model.ProviderConfig
import com.openminis.app.data.model.ProviderInstance
import com.openminis.app.provider.ModelReleaseIndex
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json

internal fun normalizeStoredCredential(value: String): String {
    var normalized = value.trim()
    if (normalized.length >= 2 &&
        ((normalized.first() == '"' && normalized.last() == '"') ||
            (normalized.first() == '\'' && normalized.last() == '\''))
    ) {
        normalized = normalized.substring(1, normalized.length - 1).trim()
    }
    if (normalized.startsWith("Bearer ", ignoreCase = true)) {
        normalized = normalized.substring("Bearer ".length).trim()
    }
    return normalized
}

// Modality bit layout — must match src/ios/Providers/LLMTypes.swift
// ModelModality OptionSet rawValue exactly. Used by export/import to
// transmit modality info as a single Int that iOS can decode.
internal const val MODALITY_BIT_TEXT_IN = 1 shl 0
internal const val MODALITY_BIT_TEXT_OUT = 1 shl 1
internal const val MODALITY_BIT_IMG_IN = 1 shl 2
internal const val MODALITY_BIT_PDF_IN = 1 shl 3
internal const val MODALITY_BIT_AUD_IN = 1 shl 4
internal const val MODALITY_BIT_VID_IN = 1 shl 5
internal const val MODALITY_BIT_IMG_OUT = 1 shl 6
internal const val MODALITY_BIT_AUD_OUT = 1 shl 7
internal const val MODALITY_BIT_VID_OUT = 1 shl 8

internal fun reorderSlotEntries(current: List<String>, newOrder: List<String>): List<String> {
    val requested = newOrder.filter { it in current }.distinct()
    return requested + current.filterNot { it in requested }
}

class ProviderRepository(internal val context: Context) {

    // [T-android-thinking-level-arch] coerceInputValues makes kotlinx.serialization
    // fall back to a property's DEFAULT when it can't decode the wire value —
    // crucially, this covers an unknown ENUM value (e.g. a ThinkingLevel a newer
    // build wrote, like "MAX"/"ULTRA", read by an older build). Without it the
    // JSON-mirror decode throws SerializationException on that enum, and since
    // that mirror is the fallback for a failed Room DB load, the whole config
    // would come back empty (all configured providers vanish from the UI).
    // `ignoreUnknownKeys` only skips unknown object keys, NOT unknown enum
    // values — coerceInputValues is the piece that handles those. Fields carrying
    // ThinkingLevel are nullable with a null default, so an unknown value coerces
    // cleanly to null.
    internal val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        coerceInputValues = true
    }

    internal val prefs: SharedPreferences = context.getSharedPreferences("provider_config", Context.MODE_PRIVATE)

    // [T-android-provider-room-store] Per-row provider config DB. Lives in
    // its own provider.db file so a downgrade to a build that doesn't know
    // about provider tables can't crash the main minis.db open. The legacy
    // SharedPreferences JSON mirror under "provider_config / config" is
    // still written on every save so older builds keep reading current
    // config — losing nothing on downgrade. See [loadConfig] for the
    // downgrade-and-back-up reconciliation logic.
    internal val providerDao: ProviderConfigDao =
        ProviderDatabase.getInstance(context).providerConfigDao()

    companion object {
        /**
         * Per-instance model-cache TTL. Matches iOS's daily calendar-day
         * refresh window (24h rolling here — simpler than calendar-day math
         * and the behavioral difference at midnight is negligible). Used by
         * [triggerBackgroundRefreshIfStale] to decide whether a UI-triggered
         * stale-while-revalidate refresh should fire.
         */
        internal const val MODEL_CACHE_TTL_MS = 24 * 60 * 60 * 1000L

        /** Minimum gap between stale-triggered retries of one instance (per process). */
        internal const val STALE_RETRY_INTERVAL_MS = 30 * 60 * 1000L

        /** Per-instance `lastFetchAt` pref key. */
        internal fun lastFetchKey(instanceId: String) = "modelsLastFetchAt_$instanceId"

        /** [T-newchat-default-model-fallback-android] Global last-used model entry id. */
        internal const val KEY_LAST_USED_ENTRY = "lastUsedModelEntryId"

        /**
         * [T-android-provider-voice] Normalize a base URL for shadow-voice
         * cross-instance de-dup: lowercased, trailing "/" and "/v1" stripped.
         * Mirrors iOS ProviderConfigStore.normalizedShadowKey.
         */
        fun normalizedShadowKey(baseURL: String?): String {
            var s = baseURL?.trim()?.lowercase() ?: return ""
            while (s.endsWith("/")) s = s.dropLast(1)
            if (s.endsWith("/v1")) s = s.dropLast(3)
            while (s.endsWith("/")) s = s.dropLast(1)
            return s
        }
    }




    internal val encryptedPrefs: SharedPreferences by lazy {
        // T-android-keystore-aead-fail: route through the self-healing
        // factory so a corrupted master key on Samsung One UI / Android
        // 16 doesn't crash the app at first read.
        com.openminis.app.util.EncryptedPrefsFactory.safeCreate(context, "provider_secrets")
    }

    // [T-android-startup-config-stall] #753: loadConfig() does a synchronous
    // SharedPreferences read (~3s first-touch as the XML is parsed) + a
    // Json.decodeFromString<ProviderConfig> (~8s on a large config). It used to
    // run INLINE in this field initializer, i.e. inside ProviderRepository's
    // constructor, which MinisApp.onCreate() invokes on the MAIN THREAD — so
    // cold start hung >11s. Now we start with an empty placeholder (instant, no
    // I/O) and load the real config on Dispatchers.IO, emitting it when ready.
    // Reactive consumers (config.collectAsState) update automatically on emit;
    // action-time `config.value` reads tolerate the brief empty window (a send
    // before load just has no model entry, exactly like a fresh install).
    internal val _config = MutableStateFlow(ProviderConfig())
    val config: StateFlow<ProviderConfig> = _config.asStateFlow()

    /**
     * [T-android-startup-config-stall] False until the persisted config has
     * been read off-thread and emitted. First-screen code that branches on
     * `instances.isEmpty()` (the onboarding gate) reads this to avoid flashing
     * a "no providers" / onboarding state for an existing user during the load
     * window. Distinguishes "empty because not loaded yet" from "empty because
     * the user genuinely has no providers".
     */
    internal val _configLoaded = MutableStateFlow(false)
    val configLoaded: StateFlow<Boolean> = _configLoaded.asStateFlow()

    /** Internal scope for the one-shot async config load. */
    internal val loadScope = kotlinx.coroutines.CoroutineScope(
        kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.IO,
    )

    /**
     * Provider config persistence is serialized off the caller's dispatcher.
     * Public mutators remain synchronous for API compatibility, but they only
     * publish a memory snapshot and enqueue the DB + JSON write here.
     */
    internal val persistenceScope = kotlinx.coroutines.CoroutineScope(
        kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.IO,
    )

    internal val persistenceQueue = ProviderConfigWriteQueue(
        scope = persistenceScope,
        persist = { config -> persistToDbAndMirror(config) },
        onFailure = { error ->
            android.util.Log.e(
                "ProviderRepo",
                "[ProviderStore] async persistence failed; the next successful save will resync: ${error.message}",
                error,
            )
        },
    )

    /**
     * Completes once the initial off-thread load has emitted (or determined
     * there's nothing persisted). Lets startup consumers that genuinely need
     * the config (e.g. the daily model refresh) wait instead of racing the
     * empty placeholder.
     */
    internal val configLoadComplete = kotlinx.coroutines.CompletableDeferred<Unit>()


    init {
        loadScope.launch {
            // [T-android-provider-empty-load-wipe] loadConfig() THROWS when the
            // store is unreadable (rather than returning an empty config that a
            // later save would write over real data). Contain it here: an
            // uncaught throw in this coroutine reaches the default uncaught
            // handler and crashes cold start — in a LOOP, for as long as the DB
            // stays unreadable, which is precisely the post-crash state the
            // refusal exists for. Swallowing it leaves _configLoaded false, so
            // ensureConfigLoaded retries on the next access.
            try {
                val loaded = loadConfig()
                synchronized(configLock) {
                    // Only adopt the disk config if nothing has written in the
                    // meantime. A write during the load window (rare — writes come
                    // from user/refresh actions that themselves need config) flips
                    // _configLoaded true and takes precedence; we must not clobber
                    // it with the stale on-disk snapshot.
                    if (!_configLoaded.value) {
                        _config.value = loaded
                        _configLoaded.value = true
                        android.util.Log.i(
                            "ProviderRepo",
                            "[ProviderStore] async loader adopted ${loaded.instances.size} instances",
                        )
                    }
                }
            } catch (e: Exception) {
                android.util.Log.e(
                    "ProviderRepo",
                    "[ProviderStore] initial load failed; leaving config unloaded for retry: ${e.message}",
                )
            }
            // Completed in every path, including the failure one: awaitConfigLoaded()
            // callers (refreshAllModelsIfNeeded) would otherwise suspend forever.
            if (!configLoadComplete.isCompleted) configLoadComplete.complete(Unit)
            // [T-android-provider-voice] Reconcile voice-template seeds after
            // the initial load (add new template models, drop retired seeds,
            // heal corrupted modality). Mirrors iOS ProviderConfigStore.init.
            try {
                ensureVoiceTemplateModels()
            } catch (e: Exception) {
                android.util.Log.w("ProviderRepo", "[Voice] ensureVoiceTemplateModels failed: ${e.message}")
            }
        }
    }

    /**
     * Lock guarding all mutate-and-save sequences against concurrent writers
     * (notably the `for instance in enabled { scope.launch { autoRefreshModels(...) } }`
     * fan-out in [refreshAllModelsIfNeeded] / [triggerBackgroundRefreshIfStale]).
     *
     * Without this lock, two parallel `replaceEntries(instanceA, …)` and
     * `replaceEntries(instanceB, …)` calls share the same `_config.value`
     * reference (its inner ArrayLists are the same objects across reads),
     * so one's `removeAll` / `addAll` runs while the other's `saveConfig`
     * is mid-`Json.encodeToString` — that's the
     * `ConcurrentModificationException → ArrayList.next` crash captured on
     * Pixel 6 / 4a.
     */
    internal val configLock = Any()













    // [T-android-provider-mutator-lock] Snapshot, not the live list. The
    // declared type is List, but the backing object is the MutableList that
    // mutators append to in place — so a caller iterating this (e.g.
    // ProvidersCollection.childIds) could take a ConcurrentModificationException
    // from a concurrent addInstance. Copying under the lock costs a few objects
    // and removes the hazard for every current and future caller.
    val instances: List<ProviderInstance>
        get() = synchronized(configLock) { _config.value.instances.toList() }













    /**
     * [T-model-release-ranking] Newest / most capable model first, so a picker
     * never opens on a stale (or, as in OpenMinis#83, an uncallable) model.
     * These lists previously came back in raw config order, which is insertion
     * order from the provider's /models response — effectively arbitrary.
     * Falls back to the model id so the ordering is total and stable when two
     * entries rank identically; otherwise the list could visibly reshuffle
     * between reads. Mirrors iOS `ProviderConfigStore.releaseRankOrder`.
     */
    internal val releaseRankOrder = Comparator<ModelEntry> { a, b ->
        val ra = ModelReleaseIndex.rank(
            a.baseModel.id, a.baseModel.displayName, a.baseModel.contextWindow
        )
        val rb = ModelReleaseIndex.rank(
            b.baseModel.id, b.baseModel.displayName, b.baseModel.contextWindow
        )
        val byRank = ModelReleaseIndex.comparator.compare(ra, rb)
        if (byRank != 0) byRank else a.baseModel.id.compareTo(b.baseModel.id)
    }

    // ── [T-newchat-default-model-fallback-android] last-used + newest-model ──

    /**
     * The model entry id the user last actively selected (model picker tap) or
     * sent a message with. Persisted globally (not per-session) so a brand-new
     * chat can fall back to "the model I was just using" when no default group
     * is configured. Null until the user has picked / used a model at least
     * once. Mirrors iOS #636 lastUsedModelEntryId.
     */
    var lastUsedEntryId: String?
        get() = prefs.getString(KEY_LAST_USED_ENTRY, null)
        set(value) {
            prefs.edit().apply {
                if (value == null) remove(KEY_LAST_USED_ENTRY) else putString(KEY_LAST_USED_ENTRY, value)
            }.apply()
        }


































    /**
     * [T-android-voice-panel] Explicit voice-input engine override picked in
     * the panel's model selector (mirrors iOS VoiceSelectionStore.inputEntryId).
     * Values: a System sentinel composite id ("<sentinel>/system-asr-online" /
     * "-offline"), a provider entry composite id, or null = follow the Voice
     * Input group binding. Per-device (prefs), not part of the synced config.
     */
    var voiceInputOverrideEntryId: String?
        get() = prefs.getString("voice.input.overrideEntryId", null)
        set(value) {
            prefs.edit().putString("voice.input.overrideEntryId", value).apply()
            synchronized(configLock) {
                val config = _config.value
                _config.value = config.copy(revision = ProviderConfig.nextRevision())
            }
        }

    /**
     * The resolved ACTIVE voice-input choice for the panel: either the on-device
     * System engine (with an online/offline preference) or a provider entry.
     * Resolution order mirrors iOS VoiceProviderResolver: explicit override
     * first, then the Voice Input slot entries in declared order (a System
     * sentinel member selects the System engine), then the System default.
     */
    data class VoiceInputChoice(
        /** null → provider-backed; non-null → System engine (true = prefer offline). */
        val systemPreferOffline: Boolean?,
        val entry: Pair<ProviderInstance, ModelEntry>?,
    ) {
        val isSystem: Boolean get() = entry == null
    }





    // --- Voice OUTPUT resolution [T-android-voice-output-resolver] ---
    //
    // Mirror of the voice-INPUT resolution above, resolving the Voice Output slot.

    /**
     * Explicit voice-output voice override picked in a selector (mirrors iOS
     * VoiceSelectionStore.outputEntryId, and [voiceInputOverrideEntryId] on the
     * input side). Values: a System sentinel composite id
     * ("<sentinel>/system-tts"), a provider entry composite id, or null =
     * follow the Voice Output slot. Per-device (prefs), not part of
     * the synced config.
     */
    var voiceOutputOverrideEntryId: String?
        get() = prefs.getString("voice.output.overrideEntryId", null)
        set(value) {
            prefs.edit().putString("voice.output.overrideEntryId", value).apply()
            synchronized(configLock) {
                val config = _config.value
                _config.value = config.copy(revision = ProviderConfig.nextRevision())
            }
        }

    /**
     * The resolved ACTIVE voice-output choice for read-aloud: either the
     * on-device System TextToSpeech or a provider entry. Same shape and
     * resolution order as [VoiceInputChoice].
     */
    data class VoiceOutputChoice(
        /** null → provider-backed; true → System TextToSpeech. */
        val isSystemEngine: Boolean,
        val entry: Pair<ProviderInstance, ModelEntry>?,
    ) {
        val isSystem: Boolean get() = entry == null
    }








    /**
     * A read-only mirror of an instance's voice capability, surfaced in Voice
     * Services. Shares the underlying instance's credential + endpoint.
     */
    data class ShadowVoiceProvider(
        val instanceId: String,
        val displayName: String,
        val inputModels: List<ModelEntry>,   // audio-in entries (ASR)
        val outputModels: List<ModelEntry>,  // audio-out entries (TTS)
    )






    internal val refreshesInFlight = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
    internal val lastStaleAttemptAt = java.util.concurrent.ConcurrentHashMap<String, Long>()


















}
