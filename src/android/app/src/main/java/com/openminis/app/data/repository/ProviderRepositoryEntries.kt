package com.openminis.app.data.repository

import android.util.Base64
import com.openminis.app.data.db.ProviderThinkingRuleEntity
import com.openminis.app.data.db.compositeEntryKey
import com.openminis.app.data.migration.LegacyGroupMigrator
import com.openminis.app.data.migration.LegacyGroupStateParser
import com.openminis.app.data.model.LLMModel
import com.openminis.app.data.model.ModelEntry
import com.openminis.app.data.model.ModelOverrides
import com.openminis.app.data.model.ModelSlot
import com.openminis.app.data.model.ModelSlots
import com.openminis.app.data.model.ProviderConfig
import com.openminis.app.data.model.ProviderCredential
import com.openminis.app.data.model.ProviderInstance
import com.openminis.app.data.model.ProviderType
import com.openminis.app.data.model.SystemVoiceEntries
import com.openminis.app.data.model.SystemVoiceIds
import com.openminis.app.data.model.hasAudioInput
import com.openminis.app.data.model.hasAudioOutput
import com.openminis.app.data.model.hasVoiceModality
import com.openminis.app.data.repository.ProviderRepository.Companion.MODEL_CACHE_TTL_MS
import com.openminis.app.data.repository.ProviderRepository.Companion.STALE_RETRY_INTERVAL_MS
import com.openminis.app.data.repository.ProviderRepository.Companion.normalizedShadowKey
import com.openminis.app.data.repository.ProviderRepository.ShadowVoiceProvider
import com.openminis.app.data.repository.ProviderRepository.VoiceInputChoice
import com.openminis.app.data.repository.ProviderRepository.VoiceOutputChoice
import com.openminis.app.provider.ModelsDevApi
import com.openminis.app.provider.anthropic.AnthropicModelsApi
import com.openminis.app.provider.gemini.GeminiModelsApi
import com.openminis.app.provider.openai.OpenAIModelsApi
import com.openminis.app.provider.openrouter.OpenRouterModelsApi
import com.openminis.app.provider.thinking.ThinkingRuleCoding
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

fun ProviderRepository.resolveVoiceInputChoice(): VoiceInputChoice {
    ensureConfigLoaded()
    val config = _config.value

    fun systemChoice(memberId: String): VoiceInputChoice = VoiceInputChoice(
        systemPreferOffline = memberId.endsWith("/${SystemVoiceIds.SYSTEM_ASR_OFFLINE}"),
        entry = null,
    )

    fun providerEntry(memberId: String): Pair<ProviderInstance, ModelEntry>? {
        val entry = config.modelEntries.find { it.id == memberId } ?: return null
        val inst = config.instances.find { it.id == entry.providerInstanceId } ?: return null
        if (!inst.isEnabled || !entry.model.hasAudioInput) return null
        return inst to entry
    }

    voiceInputOverrideEntryId?.let { override ->
        if (override.startsWith(SystemVoiceIds.BUILTIN_PROVIDER_ID)) return systemChoice(override)
        providerEntry(override)?.let { return VoiceInputChoice(null, it) }
        // Stale override (entry removed) — fall through to the slot.
    }
    for (entry in availableEntries(ModelSlot.voiceInput)) {
        if (SystemVoiceEntries.isSystemEntryId(entry.id)) return systemChoice(entry.id)
        if (entry.model.hasAudioInput) {
            val instance = config.instances.find { it.id == entry.providerInstanceId } ?: continue
            return VoiceInputChoice(null, instance to entry)
        }
    }
    return VoiceInputChoice(systemPreferOffline = null, entry = null)
}

/** Voice Input slot leading model name, or null. */
fun ProviderRepository.voiceInputSlotName(): String? {
    return primaryEntry(ModelSlot.voiceInput)?.model?.displayName
}

/**
 * [T-android-provider-voice] Resolve the current provider-backed voice
 * INPUT selection: honours the explicit override first, then walks the
 * Voice Input slot entries in order. System
 * sentinel members are skipped — they are served by
 * SystemSpeechRecognitionEngine, not a cloud provider.
 */
fun ProviderRepository.resolveVoiceInputEntry(): Pair<ProviderInstance, ModelEntry>? =
    resolveVoiceInputChoice().entry

/**
 * Ordered ASR fail-over candidates: the explicit override first (if usable
 * and provider-backed), then usable members of the voice-input slot in
 * declaration order. An explicit System override returns [] — the caller
 * uses the System engine directly. System sentinel members are skipped:
 * they never serve cloud ASR.
 */
fun ProviderRepository.resolveVoiceInputCandidates(): List<Pair<ProviderInstance, ModelEntry>> {
    ensureConfigLoaded()
    val config = _config.value

    fun providerEntry(memberId: String): Pair<ProviderInstance, ModelEntry>? {
        val entry = config.modelEntries.find { it.id == memberId } ?: return null
        val inst = config.instances.find { it.id == entry.providerInstanceId } ?: return null
        if (!inst.isEnabled || !entry.model.hasAudioInput) return null
        return inst to entry
    }

    val out = mutableListOf<Pair<ProviderInstance, ModelEntry>>()
    voiceInputOverrideEntryId?.let { override ->
        // Explicit System override → System engine, no cloud candidates.
        if (override.startsWith(SystemVoiceIds.BUILTIN_PROVIDER_ID)) return emptyList()
        providerEntry(override)?.let { out.add(it) }
        // Stale override (entry removed) — fall through to the slot.
    }
    for (entry in availableEntries(ModelSlot.voiceInput)) {
        if (SystemVoiceEntries.isSystemEntryId(entry.id) || !entry.model.hasAudioInput) continue
        val instance = config.instances.find { it.id == entry.providerInstanceId } ?: continue
        if (out.none { it.second.id == entry.id }) out.add(instance to entry)
    }
    return out
}

/**
 * Resolution order mirrors [resolveVoiceInputChoice] exactly: explicit
 * override first, then the Voice Output slot entries in declared order
 * (a System sentinel member selects the device engine), then the System
 * default as the terminal fallback.
 *
 * The capability gate is `hasAudioOutput` (vs `hasAudioInput` on the input
 * side) — a model that only *accepts* audio must never be picked to
 * *produce* it.
 */
fun ProviderRepository.resolveVoiceOutputChoice(): VoiceOutputChoice {
    ensureConfigLoaded()
    val config = _config.value

    fun providerEntry(memberId: String): Pair<ProviderInstance, ModelEntry>? {
        val entry = config.modelEntries.find { it.id == memberId } ?: return null
        val inst = config.instances.find { it.id == entry.providerInstanceId } ?: return null
        if (!inst.isEnabled || !entry.model.hasAudioOutput) return null
        return inst to entry
    }

    voiceOutputOverrideEntryId?.let { override ->
        if (override.startsWith(SystemVoiceIds.BUILTIN_PROVIDER_ID)) {
            return VoiceOutputChoice(isSystemEngine = true, entry = null)
        }
        providerEntry(override)?.let { return VoiceOutputChoice(false, it) }
        // Stale override (entry removed) — fall through to the slot.
    }
    for (entry in availableEntries(ModelSlot.voiceOutput)) {
        if (SystemVoiceEntries.isSystemEntryId(entry.id)) {
            return VoiceOutputChoice(isSystemEngine = true, entry = null)
        }
        if (entry.model.hasAudioOutput) {
            val instance = config.instances.find { it.id == entry.providerInstanceId } ?: continue
            return VoiceOutputChoice(false, instance to entry)
        }
    }
    return VoiceOutputChoice(isSystemEngine = true, entry = null)
}

/**
 * [T-android-voice-picker-active] Which ENTRY in the voice slot is
 * the one that would actually serve the next request — the id the picker
 * marks "Active" when no explicit override is set.
 *
 * Deliberately derived here rather than recomputed in the picker: this is
 * the same first-usable-entry-in-slot-order walk that
 * [resolveVoiceInputChoice] / [resolveVoiceOutputChoice] perform, and a
 * second copy in the UI would silently drift from the runtime rule the
 * moment either resolver's usability test changes. Returns null when no
 * member is usable (the slot can serve nothing).
 *
 * Note this reports the slot's *static* first choice. Under
 * after a fail-over, the request may land on a later
 * member; the badge answers "which one leads", which is what the row's
 * fallback-strategy chip already promises.
 */
fun ProviderRepository.activeVoiceSlotEntryId(output: Boolean): String? {
    val slot = if (output) ModelSlot.voiceOutput else ModelSlot.voiceInput
    return availableEntries(slot).firstOrNull { entry ->
        SystemVoiceEntries.isSystemEntryId(entry.id) ||
            if (output) entry.model.hasAudioOutput else entry.model.hasAudioInput
    }?.id
}

/** Voice Output slot leading model name, or null. */
fun ProviderRepository.voiceOutputSlotName(): String? {
    return primaryEntry(ModelSlot.voiceOutput)?.model?.displayName
}

/**
 * Resolve the current provider-backed voice OUTPUT selection, or null when
 * read-aloud should use the on-device engine. Counterpart of
 * [resolveVoiceInputEntry].
 */
fun ProviderRepository.resolveVoiceOutputEntry(): Pair<ProviderInstance, ModelEntry>? =
    resolveVoiceOutputChoice().entry

// --- Shadow Voice Providers [T-android-provider-voice] ---
// Port of iOS [T-mimo-shadow-voice]: a "Voice Service" is NOT a stored
// entity — it's a runtime read-only MIRROR of any ordinary instance that
// owns audio-modality model entries (dual-purpose vendors like MiMo serve
// text + voice on one host). Voice ability is a per-MODEL concern; no
// base-URL "voice-only" whitelist.

/** True if [instanceId] has ANY model entry with an audio modality. */
fun ProviderRepository.hasVoiceModels(instanceId: String): Boolean =
    _config.value.modelEntries.any {
        it.providerInstanceId == instanceId && it.model.hasVoiceModality
    }

/**
 * Per-instance "hide from Voice Services" flag ("I only want the text
 * models"). Absent = shadow shown by default. Stored in prefs (mirrors iOS
 * UserDefaults voiceShadowDisabled.<id>), not the synced config.
 */
fun ProviderRepository.isVoiceShadowDisabled(instanceId: String): Boolean =
    prefs.getBoolean("voiceShadowDisabled.$instanceId", false)

fun ProviderRepository.setVoiceShadowDisabled(instanceId: String, disabled: Boolean) {
    prefs.edit().putBoolean("voiceShadowDisabled.$instanceId", disabled).apply()
    // Bump revision so Compose collectors re-read the shadow list.
    // The read must be INSIDE the lock: reading _config.value outside it
    // and re-publishing that snapshot afterwards would silently revert any
    // save that landed in between (lost update). The other two
    // revision-bump sites already read inside their block.
    synchronized(configLock) {
        _config.value = _config.value.copy(revision = ProviderConfig.nextRevision())
    }
}

/**
 * All shadow voice providers: one per enabled instance that has audio
 * models and isn't shadow-disabled, FOLDED by normalized base URL so two
 * instances on one host show a single deterministic representative row.
 */
fun ProviderRepository.shadowVoiceProviders(): List<ShadowVoiceProvider> {
    ensureConfigLoaded()
    val config = _config.value
    // [T-android-reorder-unlocked-mutation] Snapshot first — same
    // main-thread composition reader as hasFoldedShadowDuplicates.
    val candidates = config.instances.toList().filter { inst ->
        inst.isEnabled && hasVoiceModels(inst.id) && !isVoiceShadowDisabled(inst.id) &&
            com.openminis.app.provider.voice.VoiceProviderFactory.supports(inst, loadApiKey(inst.id))
    }
    val byKey = candidates.groupBy { inst ->
        normalizedShadowKey(inst.customBaseURL).ifEmpty { "id:${inst.id}" }
    }

    fun mostRecentModified(instanceId: String): Long =
        config.modelEntries
            .filter { it.providerInstanceId == instanceId }
            .mapNotNull { it.userModifiedAt }
            .maxOrNull() ?: Long.MIN_VALUE

    return byKey.values.map { insts ->
        // Representative selection — deterministic across devices: enabled
        // first, then most-recently-modified entries, then oldest createdAt,
        // then id.
        val rep = insts.sortedWith(
            compareByDescending<ProviderInstance> { it.isEnabled }
                .thenByDescending { mostRecentModified(it.id) }
                .thenBy { it.createdAt }
                .thenBy { it.id },
        ).first()
        val entries = config.modelEntries.filter { it.providerInstanceId == rep.id }
        ShadowVoiceProvider(
            instanceId = rep.id,
            displayName = rep.label,
            inputModels = entries.filter { it.model.hasAudioInput },
            outputModels = entries.filter { it.model.hasAudioOutput },
        )
    }.sortedWith(compareBy({ it.displayName }, { it.instanceId }))
}

/**
 * True when ≥2 enabled instances share a normalized base URL AND have voice
 * models — the folded-duplicate case; UI shows a non-destructive hint.
 */
fun ProviderRepository.hasFoldedShadowDuplicates(): Boolean {
    val seen = mutableSetOf<String>()
    // [T-android-reorder-unlocked-mutation] Snapshot before iterating.
    // This runs on the MAIN thread from ProviderListScreen's composition,
    // so taking configLock here would block the UI behind a DB write.
    // toList() copies under no contention and removes the CME risk
    // outright — the writer's structural edits can no longer be observed
    // mid-iteration.
    for (inst in _config.value.instances.toList()) {
        if (!inst.isEnabled || !hasVoiceModels(inst.id)) continue
        val key = normalizedShadowKey(inst.customBaseURL)
        if (key.isEmpty()) continue
        if (!seen.add(key)) return true
    }
    return false
}

/**
 * Fetches the instance's model list and replaces its entries. Returns whether
 * entries were actually replaced: `false` means nothing usable came back
 * (no credential and no models.dev match, or a failed call against a private
 * host whose existing list is deliberately kept), so callers can tell the
 * user instead of showing an unchanged list as if it had been refreshed.
 */
suspend fun ProviderRepository.refreshModels(instance: ProviderInstance): Boolean {
    com.openminis.app.provider.ProviderTransportPolicy
        .requireAllowedInstanceBase(instance, instance.effectiveBaseURL)
    var apiKey = loadApiKey(instance.id)

    // For OAuth providers, try to refresh the token before using it (mirrors iOS validAccessToken)
    if (instance.credentialType == com.openminis.app.data.model.ProviderCredential.oauth && apiKey != null) {
        try {
            val manager = com.openminis.app.auth.OAuthManager.forInstance(context, instance)
            val freshToken = manager?.validAccessToken()
            if (freshToken != null && freshToken != apiKey) {
                saveApiKey(instance.id, freshToken)
                apiKey = freshToken
                android.util.Log.i("ProviderRepo", "refreshModels: OAuth token refreshed")
            }
        } catch (e: Exception) {
            android.util.Log.w("ProviderRepo", "OAuth token refresh failed: ${e.message}")
        }
    }

    android.util.Log.i("ProviderRepo", "refreshModels: id=${instance.id} type=${instance.providerType} credential=${instance.credentialType} hasKey=${apiKey != null} keyLen=${apiKey?.length ?: 0} baseURL=${instance.effectiveBaseURL}")

    // OpenAI Codex OAuth: the token cannot call /v1/models, but the ChatGPT
    // backend serves its own per-account list. Ask it first; the bundled
    // allow-list is only the fallback. A manual bearer is not a ChatGPT
    // session token, so it never goes to the Codex backend.
    if (instance.providerType == ProviderType.openAI
        && instance.credentialType == ProviderCredential.oauth
    ) {
        val manualBearer = com.openminis.app.auth.OAuthManager.forInstance(context, instance)
            ?.loadManualBearerToken()
        val models = if (apiKey != null && manualBearer.isNullOrEmpty()) {
            OpenAIModelsApi.fetchCodexCatalog(
                apiKey,
                com.openminis.app.auth.OpenAIOAuthManager(context, instance.id).accountId,
            )
        } else {
            OpenAIModelsApi.fetchModelsOAuth()
        }
        if (models.isNotEmpty()) {
            replaceEntries(instance.id, models)
            return true
        }
    }

    // Step 1: Try provider API (requires API key)
    val customBase = instance.customBaseURL
    val isThirdParty = customBase != null
        && !customBase.lowercase().let {
            it.contains("api.openai.com") || it.contains("chatgpt.com") || it.contains("openrouter.ai")
        }

    if (apiKey != null) {
        val baseURL = instance.effectiveBaseURL
        val models = try {
            when (instance.providerType) {
                ProviderType.anthropic -> AnthropicModelsApi.fetchModels(
                    apiKey, baseURL,
                    isOAuth = instance.credentialType == com.openminis.app.data.model.ProviderCredential.oauth,
                    // [T-provider-custom-user-agent] models-list UA override.
                    customUserAgent = instance.customUserAgent,
                )
                // An OAuth token goes in the Authorization header; sent as `?key=`
                // it is rejected and the list silently becomes the bundled one.
                ProviderType.gemini -> GeminiModelsApi.fetchModels(
                    apiKey,
                    isOAuth = instance.credentialType == ProviderCredential.oauth,
                )
                // [T-provider-custom-user-agent] models-list UA override.
                // [T-android-provider-type-parity] openAIResponses lists
                // models from the same /v1/models endpoint — only the
                // completion endpoint differs.
                ProviderType.openAI, ProviderType.openAIResponses ->
                    OpenAIModelsApi.fetchModels(apiKey, baseURL, customUserAgent = instance.customUserAgent)
                ProviderType.openRouter -> OpenRouterModelsApi.fetchModels(apiKey)
                // xAI exposes an OpenAI-compatible live catalog. Keep the
                // built-in list as a vendor-specific fallback so a failed
                // refresh never empties the model picker or substitutes
                // models from the wrong provider.
                ProviderType.xAI -> OpenAIModelsApi.fetchModels(
                    apiKey,
                    baseURL ?: "https://api.x.ai/v1",
                    customUserAgent = instance.customUserAgent,
                ).ifEmpty { com.openminis.app.provider.xai.XAIModelsApi.fetchModelsOAuth() }
                // [T-kimi-oauth] Kimi Code: unlike Codex OAuth, the Kimi
                // OAuth token CAN call the models endpoint — real fetch
                // from GET /coding/v1/models (OpenAI-compatible shape).
                // The upstream lineup shifts across generations, so the
                // live list replaces the minimal built-in fallback.
                ProviderType.kimiCode -> OpenAIModelsApi.fetchModels(
                    apiKey,
                    baseURL ?: "${com.openminis.app.auth.KimiDeviceFlow.CODING_API_BASE}/v1",
                    customUserAgent = instance.customUserAgent,
                )
                // [T-android-provider-type-parity] No models endpoint to
                // query for a type this build cannot drive; the instance
                // keeps whatever entries the restore brought with it.
                ProviderType.antigravity, ProviderType.unsupported -> emptyList()
            }
        } catch (e: Exception) {
            android.util.Log.e("ProviderRepo", "refreshModels fetch error: ${e.message}", e)
            emptyList()
        }
        android.util.Log.i("ProviderRepo", "refreshModels: got ${models.size} models")

        // Step 2: If API returned results, use them
        if (models.isNotEmpty()) {
            replaceEntries(instance.id, models)
            return true
        }
    }

    // Step 3: Fallback to models.dev by base URL.
    // We try this even for third-party hosts (DashScope/Bailian, etc.) — the
    // models.dev registry covers many "Anthropic-compatible" or "OpenAI-compatible"
    // gateways by hostname, and when there's no match `fetchModels` returns
    // empty so the existing list (vLLM/Ollama on a private host) is preserved.
    val fallbackBaseURL = modelsDevBaseURL(instance)
    val fallbackModels = ModelsDevApi.fetchModels(fallbackBaseURL)
    if (fallbackModels.isNotEmpty()) {
        android.util.Log.i("ProviderRepo", "models.dev fallback returned ${fallbackModels.size} models for ${instance.label}")
        replaceEntries(instance.id, fallbackModels)
        return true
    } else if (isThirdParty) {
        android.util.Log.i("ProviderRepo", "Third-party endpoint, no models.dev match — preserving existing models for ${instance.label}")
    }
    return false
}

/**
 * Auto-refresh variant: skips instances where the user has added custom models,
 * so we never overwrite hand-edited entries. Mirrors iOS `autoRefreshModels(for:)`.
 */
internal suspend fun ProviderRepository.autoRefreshModels(instance: ProviderInstance): Boolean {
    val hasCustom = _config.value.modelEntries.any {
        it.providerInstanceId == instance.id && it.isCustom
    }
    if (hasCustom) {
        android.util.Log.i("ProviderRepo", "[ModelList] autoRefresh SKIP ${instance.label} — has custom models")
        return true
    }
    // The cold-start fan-out and the foreground check can both reach the same
    // instance; one fetch is enough.
    if (!refreshesInFlight.add(instance.id)) return true
    return try {
        refreshModels(instance)
    } catch (e: kotlinx.coroutines.CancellationException) {
        throw e
    } catch (e: Exception) {
        android.util.Log.w("ProviderRepo", "[ModelList] autoRefresh failed for ${instance.label}: ${e.message}")
        false
    } finally {
        refreshesInFlight.remove(instance.id)
    }
}

/**
 * Stale-while-revalidate helper for UI code. Callers (e.g. the model
 * picker sheet) invoke this on open: the currently-persisted
 * `config.modelEntries` is returned immediately via the StateFlow
 * (no waiting), and if any instance's cache is older than
 * [MODEL_CACHE_TTL_MS] we kick a background refresh that updates the
 * StateFlow when the network fetch completes.
 *
 * Concurrent-call safe: per-instance `autoRefreshModels` is idempotent
 * and the global daily-refresh flag prevents cold-start double fetch.
 * This helper bypasses the daily flag because it runs per-instance — it's
 * meant for targeted "user is looking at this picker now" revalidation.
 */
fun ProviderRepository.triggerBackgroundRefreshIfStale(scope: kotlinx.coroutines.CoroutineScope) {
    val now = System.currentTimeMillis()
    // An instance that cannot be refreshed (private host down, no credential)
    // stays stale forever, so without this every foreground would retry it.
    val stale = _config.value.instances.filter {
        it.isEnabled && isInstanceStale(it.id) &&
            now - (lastStaleAttemptAt[it.id] ?: 0L) >= STALE_RETRY_INTERVAL_MS
    }
    if (stale.isEmpty()) return
    stale.forEach { lastStaleAttemptAt[it.id] = now }
    android.util.Log.i("ProviderRepo", "[ModelList] SWR refresh — ${stale.size} stale instance(s)")
    for (instance in stale) {
        scope.launch { autoRefreshModels(instance) }
    }
}

/**
 * Refresh model lists for all enabled instances, at most once per calendar day.
 * Mirrors iOS `refreshAllModelsIfNeeded()` — called from Application.onCreate.
 * Refreshes run in parallel; failures are logged but don't block other instances.
 */
fun ProviderRepository.refreshAllModelsIfNeeded(scope: kotlinx.coroutines.CoroutineScope) {
    val key = "lastModelsRefreshDate"
    val lastMs = prefs.getLong(key, 0L)
    val now = System.currentTimeMillis()
    if (lastMs > 0L && isSameCalendarDay(lastMs, now)) {
        android.util.Log.i("ProviderRepo", "[ModelList] refreshAllModelsIfNeeded SKIP — already refreshed today")
        return
    }

    // [T-android-startup-config-stall] Config now loads asynchronously, so
    // at cold start `_config.value` may still be the empty placeholder when
    // this fires from MinisApp.onCreate. Wait for the load before reading
    // the enabled-instance set, otherwise the daily refresh would no-op on
    // "no enabled instances" and skip this launch entirely. Runs on the
    // caller's (IO) scope — does not touch the main thread.
    scope.launch {
        awaitConfigLoaded()
        val enabled = _config.value.instances.filter { it.isEnabled }
        if (enabled.isEmpty()) {
            android.util.Log.i("ProviderRepo", "[ModelList] refreshAllModelsIfNeeded SKIP — no enabled instances")
            return@launch
        }

        android.util.Log.i("ProviderRepo", "[ModelList] refreshAllModelsIfNeeded FIRE — ${enabled.size} instances")

        // Stamp the day only after every instance refreshed: a cold start
        // with no network used to spend the whole day's attempt, so the
        // lists stayed stale until tomorrow. Instances that failed stay
        // stale (no per-instance stamp) and the foreground check retries them.
        val results = enabled.map { instance -> async { autoRefreshModels(instance) } }.awaitAll()
        if (results.all { it }) prefs.edit().putLong(key, now).apply()
    }
}

internal fun ProviderRepository.isSameCalendarDay(aMs: Long, bMs: Long): Boolean {
    val cal = java.util.Calendar.getInstance()
    cal.timeInMillis = aMs
    val aYear = cal.get(java.util.Calendar.YEAR)
    val aDay = cal.get(java.util.Calendar.DAY_OF_YEAR)
    cal.timeInMillis = bMs
    return aYear == cal.get(java.util.Calendar.YEAR)
        && aDay == cal.get(java.util.Calendar.DAY_OF_YEAR)
}

/** Resolve the models.dev lookup base URL for an instance. */
internal fun ProviderRepository.modelsDevBaseURL(instance: ProviderInstance): String {
    instance.effectiveBaseURL?.let { return it }
    return when (instance.providerType) {
        ProviderType.anthropic -> "https://api.anthropic.com/v1"
        ProviderType.gemini -> "https://generativelanguage.googleapis.com"
        // [T-android-provider-type-parity] Responses API instances point at
        // the same OpenAI host; only the completion path differs.
        ProviderType.openAI, ProviderType.openAIResponses -> "https://api.openai.com/v1"
        ProviderType.openRouter -> "https://openrouter.ai/api/v1"
        ProviderType.xAI -> "https://api.x.ai/v1"
        ProviderType.kimiCode -> "${com.openminis.app.auth.KimiDeviceFlow.CODING_API_BASE}/v1"
        // No canonical host for a type this build cannot drive. Callers
        // reaching here have already exhausted effectiveBaseURL.
        ProviderType.antigravity, ProviderType.unsupported -> "https://api.openai.com/v1"
    }
}

// API Key management
fun ProviderRepository.saveApiKey(instanceId: String, key: String) {
    // Credentials can also come from imported/debug configuration paths,
    // where a copied newline or surrounding space must not become part of
    // the Authorization header sent to an API provider.
    encryptedPrefs.edit().putString("apikey_$instanceId", normalizeStoredCredential(key)).apply()
}

fun ProviderRepository.loadApiKey(instanceId: String): String? {
    // Trim on read as well so credentials saved by older/import paths are
    // repaired without requiring the user to enter them again.
    return encryptedPrefs.getString("apikey_$instanceId", null)?.let(::normalizeStoredCredential)
}

/**
 * [T-empty-key-compat-endpoints] The credential the ROUTING layers should
 * use: the stored key, or "" for instances where an empty key is a valid
 * configuration (third-party OpenAI/Anthropic-compatible endpoint — see
 * ProviderInstance.allowsEmptyAPIKey). Returns null only when the instance
 * genuinely has no usable credential, so existing `?: return/continue`
 * call sites keep their skip semantics for everything else (notably OAuth
 * instances without a token, which must stay unauthenticated).
 */
fun ProviderRepository.usableApiKey(instance: ProviderInstance): String? =
    loadApiKey(instance.id) ?: if (instance.allowsEmptyAPIKey) "" else null

fun ProviderRepository.deleteApiKey(instanceId: String) {
    encryptedPrefs.edit().remove("apikey_$instanceId").apply()
}

// -- Import / Export --

/**
 * [T-android-provider-export-oauth-token] OAuth manager for [instance],
 * covering EVERY OAuth provider type — including gemini / antigravity, which
 * OAuthManager.forInstance deliberately omits (it's tuned for the
 * login/logout/manual-bearer UI paths). Used only by the export/import
 * round-trip so we don't widen forInstance's shared behavior.
 */
internal fun ProviderRepository.oauthManagerFor(
    instance: ProviderInstance,
): com.openminis.app.auth.OAuthManager? = when (instance.providerType) {
    ProviderType.anthropic -> com.openminis.app.auth.ClaudeOAuthManager(context, instance.id)
    ProviderType.openAI -> com.openminis.app.auth.OpenAIOAuthManager(context, instance.id)
    ProviderType.xAI -> com.openminis.app.auth.XAIOAuthManager(context, instance.id)
    ProviderType.gemini -> com.openminis.app.auth.GeminiOAuthManager(context, instance.id)
    ProviderType.kimiCode -> com.openminis.app.auth.KimiOAuthManager(context, instance.id)
    else -> null
}

/** Export an instance as shareable JSON (includes base64-encoded API key). */
fun ProviderRepository.exportInstanceJSON(instanceId: String): String? {
    ensureConfigLoaded()
    val instance = instance(instanceId) ?: return null
    val entries = visibleEntries(instanceId) + _config.value.modelEntries.filter {
        it.providerInstanceId == instanceId && it.isHidden
    }

    val obj = JSONObject().apply {
        put("providerType", instance.providerType.name)
        put("label", instance.label)
        put("credentialType", instance.credentialType.name)
        val modelsArr = JSONArray()
        for (entry in entries) {
            modelsArr.put(JSONObject().apply {
                put("modelId", entry.baseModel.id)
                put("displayName", entry.baseModel.displayName)
                put("isHidden", entry.isHidden)
                if (entry.isCustom) put("isCustom", true)
                entry.baseModel.contextWindow?.let { put("contextWindow", it) }
                entry.baseModel.maxOutputTokens?.let { put("maxOutputTokens", it) }
                entry.baseModel.supportsReasoning?.let { put("supportsReasoning", it) }
                entry.baseModel.interleavedReasoningField?.let { put("interleavedReasoningField", it) }
                // [T-provider-export-model-overrides] Serialize the FULL
                // ModelOverrides layer, not just displayName/maxOutputTokens.
                // contextWindow / supportsReasoning / modality (input+output)
                // were previously dropped — a hand-corrected proxied model
                // lost those edits on round-trip. Each key is additive +
                // optional: older builds ignore unknown keys, and import
                // below reads each independently so a partial override
                // object restores exactly the fields present.
                //
                // For cross-platform interop with iOS we ALSO write a
                // `modalityOverride` bitfield (Int, matching
                // ios/Providers/LLMTypes.swift ModelModality OptionSet):
                // textInput=1, textOutput=2, imageInput=4, pdfInput=8,
                // audioInput=16, videoInput=32, imageOutput=64,
                // audioOutput=128, videoOutput=256. Android natively
                // carries inputModalities/outputModalities as string lists;
                // the bitfield is purely an interop wire-format that iOS
                // can consume directly. On import, the native list fields
                // win when present (Android↔Android lossless), bitfield is
                // the iOS→Android fallback.
                if (!entry.overrides.isEmpty) {
                    val o = JSONObject()
                    entry.overrides.displayName?.let { o.put("displayName", it) }
                    entry.overrides.maxOutputTokens?.let { o.put("maxOutputTokens", it) }
                    entry.overrides.contextWindow?.let { o.put("contextWindow", it) }
                    entry.overrides.supportsReasoning?.let { o.put("supportsReasoning", it) }
                    entry.overrides.hostedWebSearch?.let {
                        o.put("hostedWebSearch", it)
                    }
                    entry.overrides.inputModalities?.let {
                        o.put("inputModalities", JSONArray(it))
                    }
                    entry.overrides.outputModalities?.let {
                        o.put("outputModalities", JSONArray(it))
                    }
                    val bitfield = modalityBitfieldFromLists(
                        entry.overrides.inputModalities,
                        entry.overrides.outputModalities,
                    )
                    if (bitfield != 0) o.put("modalityOverride", bitfield)
                    put("overrides", o)
                }
                // Mirror iOS export of baseModel.modalityOverride — when
                // the base model itself carries explicit modality info,
                // serialize an interop bitfield so iOS can faithfully
                // restore it. Android's native baseModel uses string
                // lists too; this is purely additive for iOS readers.
                run {
                    val bf = modalityBitfieldFromLists(
                        entry.baseModel.inputModalities,
                        entry.baseModel.outputModalities,
                    )
                    if (bf != 0) put("modalityOverride", bf)
                }
                entry.baseModel.inputModalities?.let {
                    put("inputModalities", JSONArray(it))
                }
                entry.baseModel.outputModalities?.let {
                    put("outputModalities", JSONArray(it))
                }
            })
        }
        put("models", modelsArr)
        loadApiKey(instanceId)?.let { key ->
            put("apiKey", Base64.encodeToString(key.toByteArray(), Base64.NO_WRAP))
        }
        // Export manual OAuth bearer token (mirrors iOS `manualOAuthToken` key).
        // Stored per-instance via OAuthManager; only present for OAuth providers
        // where the user pasted a static token via the Manual Bearer Token UI.
        run {
            val mgr = com.openminis.app.auth.OAuthManager.forInstance(context, instance)
            val manual = mgr?.loadManualBearerToken()
            if (!manual.isNullOrEmpty()) {
                put("manualOAuthToken", Base64.encodeToString(manual.toByteArray(), Base64.NO_WRAP))
            }
        }
        // [T-android-provider-export-oauth-token] (XIN 38955) Export the
        // STRUCTURED OAuth-login credential (access_token / refresh_token /
        // expire_at) saved by the OAuth login flow under a separate pref than
        // apiKey / manualOAuthToken. Previously omitted, so an OAuth-logged-in
        // Claude / OpenAI / Gemini / xAI provider exported with no usable
        // credential and imported as not-authenticated. The whole token is one
        // JSON blob on Android (OAuthManager.loadStoredTokens); JSON-encode +
        // base64 it under "oauthToken", matching iOS 703ff4bc's field name and
        // the existing apiKey / manualOAuthToken base64 encoding. Covers every
        // OAuth provider type via oauthManagerFor (not just the forInstance set).
        run {
            val mgr = oauthManagerFor(instance)
            mgr?.exportStoredTokensJson()?.let { tokenJson ->
                put("oauthToken", Base64.encodeToString(tokenJson.toByteArray(), Base64.NO_WRAP))
            }
            // Gemini also stores the resolved account email + GCP project as
            // separate OAuth strings (mirrors iOS oauthEmail / oauthGcpProject);
            // carry them so the imported instance can call the API.
            if (instance.providerType == ProviderType.gemini && mgr != null) {
                mgr.exportOAuthString("email")?.takeIf { it.isNotEmpty() }?.let {
                    put("oauthEmail", Base64.encodeToString(it.toByteArray(), Base64.NO_WRAP))
                }
                mgr.exportOAuthString("gcp_project")?.takeIf { it.isNotEmpty() }?.let {
                    put("oauthGcpProject", Base64.encodeToString(it.toByteArray(), Base64.NO_WRAP))
                }
            }
        }
        instance.customBaseURL?.let { put("customBaseURL", it) }
        if (!instance.appendV1Suffix) put("appendV1Suffix", false)
        if (instance.useResponsesAPI) put("useResponsesAPI", true)
        // [T-provider-custom-user-agent] Additive, optional. Only written
        // when set; old/new readers without the key decode to null →
        // default UA. Field name matches iOS for cross-platform interop.
        instance.customUserAgent?.takeIf { it.isNotBlank() }?.let { put("customUserAgent", it) }
    }
    return obj.toString(2)
}

/**
 * Import a provider from exported JSON. Returns the new instance label on success.
 * - Auto-renames on label conflict
 * - Decodes base64-encoded API key (falls back to plain text)
 */
fun ProviderRepository.importInstanceJSON(jsonStr: String): String? {
    ensureConfigLoaded()
    val dict = try { JSONObject(jsonStr) } catch (_: Exception) { return null }
    val providerTypeRaw = dict.optString("providerType", "").ifEmpty { return null }
    val providerType = try { ProviderType.valueOf(providerTypeRaw) } catch (_: Exception) { return null }
    val label = dict.optString("label", "").ifEmpty { return null }

    val credentialType = try {
        ProviderCredential.valueOf(dict.optString("credentialType", "apiKey"))
    } catch (_: Exception) { ProviderCredential.apiKey }

    // Resolve label conflict
    val existingLabels = _config.value.instances.map { it.label }.toSet()
    var resolvedLabel = label
    if (resolvedLabel in existingLabels) {
        var suffix = 2
        while ("$label ($suffix)" in existingLabels) suffix++
        resolvedLabel = "$label ($suffix)"
    }

    val customBaseURL = dict.optString("customBaseURL", "").ifEmpty { null }
    val appendV1 = dict.optBoolean("appendV1Suffix", true)
    val useResponsesAPI = dict.optBoolean("useResponsesAPI", false)
    // [T-provider-custom-user-agent] Additive: old exports lack the key →
    // empty → null → default UA. Field name matches iOS.
    val customUserAgent = dict.optString("customUserAgent", "").ifEmpty { null }

    val instance = ProviderInstance(
        id = java.util.UUID.randomUUID().toString(),
        label = resolvedLabel,
        providerType = providerType,
        credentialType = credentialType,
        customBaseURL = customBaseURL,
        appendV1Suffix = appendV1,
        useResponsesAPI = useResponsesAPI,
        customUserAgent = customUserAgent,
    )
    addInstance(instance)

    // Decode API key (base64 or plain text)
    val keyValue = dict.optString("apiKey", "").ifEmpty { null }
    if (keyValue != null) {
        val apiKey = try {
            String(Base64.decode(keyValue, Base64.NO_WRAP))
        } catch (_: Exception) {
            keyValue // plain text fallback
        }
        saveApiKey(instance.id, apiKey)
    }

    // Decode manual OAuth bearer token (mirrors iOS `manualOAuthToken`).
    // base64-encoded UTF-8 string, with plain-text fallback for older exports.
    val manualTokenValue = dict.optString("manualOAuthToken", "").ifEmpty { null }
    if (manualTokenValue != null) {
        val manualToken = try {
            String(Base64.decode(manualTokenValue, Base64.NO_WRAP))
        } catch (_: Exception) {
            manualTokenValue
        }
        val mgr = com.openminis.app.auth.OAuthManager.forInstance(context, instance)
        mgr?.saveManualBearerToken(manualToken)
    }

    // [T-android-provider-export-oauth-token] (XIN 38955) Restore the
    // structured OAuth-login credential so the imported instance is
    // authenticated. Decode base64 → JSON → write back via the OAuth
    // manager. Mirrors iOS 703ff4bc; purely additive alongside the
    // apiKey / manualOAuthToken restore above.
    val oauthTokenValue = dict.optString("oauthToken", "").ifEmpty { null }
    if (oauthTokenValue != null) {
        val tokenJson = try {
            String(Base64.decode(oauthTokenValue, Base64.NO_WRAP))
        } catch (_: Exception) {
            oauthTokenValue // plain-text fallback for hand-edited exports
        }
        oauthManagerFor(instance)?.importStoredTokensJson(tokenJson)
    }
    // Gemini account email + GCP project (base64-encoded OAuth strings).
    if (instance.providerType == ProviderType.gemini) {
        val mgr = oauthManagerFor(instance)
        dict.optString("oauthEmail", "").ifEmpty { null }?.let { b64 ->
            val email = try { String(Base64.decode(b64, Base64.NO_WRAP)) } catch (_: Exception) { b64 }
            mgr?.importOAuthString("email", email)
        }
        dict.optString("oauthGcpProject", "").ifEmpty { null }?.let { b64 ->
            val project = try { String(Base64.decode(b64, Base64.NO_WRAP)) } catch (_: Exception) { b64 }
            mgr?.importOAuthString("gcp_project", project)
        }
    }

    // Import models (replace built-in defaults)
    val models = dict.optJSONArray("models")
    if (models != null && models.length() > 0) {
        val entries = mutableListOf<ModelEntry>()
        for (i in 0 until models.length()) {
            val m = models.getJSONObject(i)
            val modelId = m.optString("modelId", "")
            if (modelId.isEmpty()) continue
            val displayName = m.optString("displayName", modelId)
            val isCustom = m.optBoolean("isCustom", false)
            val isHidden = m.optBoolean("isHidden", false)
            val contextWindow = if (m.has("contextWindow")) m.optInt("contextWindow").takeIf { it > 0 } else null
            val maxOutputTokens = if (m.has("maxOutputTokens")) m.optInt("maxOutputTokens").takeIf { it > 0 } else null
            val supportsReasoning = if (m.has("supportsReasoning")) m.optBoolean("supportsReasoning") else null
            val interleavedReasoningField = m.optString("interleavedReasoningField", "").ifEmpty { null }
            // [T-provider-export-model-overrides] Restore baseModel
            // modalities. Android-native list fields win when present;
            // otherwise fall back to iOS's `modalityOverride` bitfield so
            // a provider exported on iOS retains its capability info.
            val (baseIn, baseOut) = readModalitiesWithBitfieldFallback(m)
            val model = LLMModel(
                id = modelId,
                displayName = displayName,
                provider = providerType.displayName,
                contextWindow = contextWindow,
                maxOutputTokens = maxOutputTokens,
                supportsReasoning = supportsReasoning,
                interleavedReasoningField = interleavedReasoningField,
                inputModalities = baseIn,
                outputModalities = baseOut,
            )
            val overridesObj = m.optJSONObject("overrides")
            val overrides = if (overridesObj != null) {
                // [T-provider-export-model-overrides] Read the full
                // overrides layer. Each key is read independently — a
                // missing key (old export, partial object) simply stays
                // null → the field falls back to baseModel / defaults.
                val (ovIn, ovOut) = readModalitiesWithBitfieldFallback(overridesObj)
                ModelOverrides(
                    displayName = overridesObj.optString("displayName", "").ifEmpty { null },
                    maxOutputTokens = if (overridesObj.has("maxOutputTokens")) overridesObj.optInt("maxOutputTokens").takeIf { it > 0 } else null,
                    contextWindow = if (overridesObj.has("contextWindow")) overridesObj.optInt("contextWindow").takeIf { it > 0 } else null,
                    supportsReasoning = if (overridesObj.has("supportsReasoning")) overridesObj.optBoolean("supportsReasoning") else null,
                    inputModalities = ovIn,
                    outputModalities = ovOut,
                )
            } else {
                ModelOverrides()
            }
            entries.add(ModelEntry(
                providerInstanceId = instance.id,
                baseModel = model,
                overrides = overrides,
                isCustom = isCustom,
                isHidden = isHidden,
            ))
        }
        // Import replaces built-in entries directly (not via replaceEntries which takes LLMModel list).
        // [T-android-provider-mutator-lock] Lock + working copy, like every
        // other mutator. This function is the actual provider.import /
        // Share-import path, and it read `_config.value` back — the object
        // addInstance had just PUBLISHED — and mutated its live list. That
        // is the exact race behind both device CMEs; the earlier sweep
        // missed it because the audit went by function name and this one
        // isn't called add*/update*/remove*.
        synchronized(configLock) {
            val cfg = workingCopy()
            cfg.modelEntries.removeAll { it.providerInstanceId == instance.id }
            cfg.modelEntries.addAll(entries)
            saveConfig(cfg)
        }
    }

    return resolvedLabel
}

// -- Modality interop with iOS ----------------------------------------
//
// iOS encodes ModelModality as a single Int bitfield (OptionSet rawValue);
// Android carries inputModalities / outputModalities as bare string lists
// ("text" / "image" / "pdf" / "audio" / "video"). The export/import path
// writes both encodings so the wire format is portable in either
// direction without losing fidelity:
//   - Android → Android: the native string lists round-trip exactly.
//   - Android → iOS:    iOS reads `modalityOverride` Int and ignores
//                       the unknown list keys (forward-compatible).
//   - iOS → Android:    Android prefers the native list keys when
//                       present (Android-original export); otherwise
//                       decodes `modalityOverride` Int back into lists.
//
// Bit layout constants live at file scope above the class (Kotlin
// forbids a second companion object, and ProviderRepository already
// has one).

internal fun ProviderRepository.modalityBitfieldFromLists(
    inputs: List<String>?,
    outputs: List<String>?,
): Int {
    var bits = 0
    inputs?.forEach { raw ->
        when (raw.lowercase()) {
            "text" -> bits = bits or MODALITY_BIT_TEXT_IN
            "image" -> bits = bits or MODALITY_BIT_IMG_IN
            "pdf" -> bits = bits or MODALITY_BIT_PDF_IN
            "audio" -> bits = bits or MODALITY_BIT_AUD_IN
            "video" -> bits = bits or MODALITY_BIT_VID_IN
        }
    }
    outputs?.forEach { raw ->
        when (raw.lowercase()) {
            "text" -> bits = bits or MODALITY_BIT_TEXT_OUT
            "image" -> bits = bits or MODALITY_BIT_IMG_OUT
            "audio" -> bits = bits or MODALITY_BIT_AUD_OUT
            "video" -> bits = bits or MODALITY_BIT_VID_OUT
        }
    }
    return bits
}

internal fun ProviderRepository.modalityListsFromBitfield(bits: Int): Pair<List<String>?, List<String>?> {
    if (bits == 0) return null to null
    val inputs = buildList {
        if (bits and MODALITY_BIT_TEXT_IN != 0) add("text")
        if (bits and MODALITY_BIT_IMG_IN != 0) add("image")
        if (bits and MODALITY_BIT_PDF_IN != 0) add("pdf")
        if (bits and MODALITY_BIT_AUD_IN != 0) add("audio")
        if (bits and MODALITY_BIT_VID_IN != 0) add("video")
    }
    val outputs = buildList {
        if (bits and MODALITY_BIT_TEXT_OUT != 0) add("text")
        if (bits and MODALITY_BIT_IMG_OUT != 0) add("image")
        if (bits and MODALITY_BIT_AUD_OUT != 0) add("audio")
        if (bits and MODALITY_BIT_VID_OUT != 0) add("video")
    }
    return inputs.ifEmpty { null } to outputs.ifEmpty { null }
}

/**
 * Read modality info from a JSON object. Returns (inputs, outputs):
 *   - native `inputModalities` / `outputModalities` list keys take
 *     precedence (Android-original export — lossless).
 *   - if neither list is present, decode iOS's `modalityOverride`
 *     bitfield as the fallback.
 *   - if neither shape is present, returns null pair (caller treats
 *     as "no modality info" → baseModel defaults apply).
 */
internal fun ProviderRepository.readModalitiesWithBitfieldFallback(
    obj: JSONObject,
): Pair<List<String>?, List<String>?> {
    val nativeIn = obj.optJSONArray("inputModalities")?.let { arr ->
        (0 until arr.length()).mapNotNull { arr.optString(it).takeIf { s -> s.isNotEmpty() } }.takeIf { it.isNotEmpty() }
    }
    val nativeOut = obj.optJSONArray("outputModalities")?.let { arr ->
        (0 until arr.length()).mapNotNull { arr.optString(it).takeIf { s -> s.isNotEmpty() } }.takeIf { it.isNotEmpty() }
    }
    if (nativeIn != null || nativeOut != null) return nativeIn to nativeOut
    if (!obj.has("modalityOverride")) return null to null
    val bits = obj.optInt("modalityOverride", 0)
    return modalityListsFromBitfield(bits)
}

fun ProviderRepository.collectBackupProviderSecret(instance: ProviderInstance): com.openminis.app.backup.BackupSecrets.ProviderSecret? {
    val apiKey = loadApiKey(instance.id)
    val mgr = oauthManagerFor(instance) ?: com.openminis.app.auth.OAuthManager.forInstance(context, instance)
    val manualToken = mgr?.loadManualBearerToken()
    val tokenJson = mgr?.exportStoredTokensJson()
    val email = if (instance.providerType == ProviderType.gemini && mgr != null) mgr.exportOAuthString("email") else null
    val gcpProject = if (instance.providerType == ProviderType.gemini && mgr != null) mgr.exportOAuthString("gcp_project") else null
    val sec = com.openminis.app.backup.BackupSecrets.ProviderSecret(
        instanceId = instance.id,
        label = instance.label,
        providerType = instance.providerType.name,
        apiKey = if (apiKey != null) Base64.encodeToString(apiKey.toByteArray(Charsets.UTF_8), Base64.NO_WRAP) else null,
        manualOAuthToken = if (manualToken != null) Base64.encodeToString(manualToken.toByteArray(Charsets.UTF_8), Base64.NO_WRAP) else null,
        oauthToken = if (tokenJson != null) Base64.encodeToString(tokenJson.toByteArray(Charsets.UTF_8), Base64.NO_WRAP) else null,
        oauthEmail = if (email != null) Base64.encodeToString(email.toByteArray(Charsets.UTF_8), Base64.NO_WRAP) else null,
        oauthGcpProject = if (gcpProject != null) Base64.encodeToString(gcpProject.toByteArray(Charsets.UTF_8), Base64.NO_WRAP) else null,
    )
    return if (sec.isEmpty) null else sec
}

/**
 * Restore provider configuration without destroying local-only state.
 * Package order wins for objects carried by the backup; existing local
 * object contents win on id collision; local-only objects are appended.
 */
fun ProviderRepository.mergeBackupProviderConfig(
    remote: ProviderConfig,
    legacyRawJson: String? = null,
): Pair<Int, Int> {
    ensureConfigLoaded()
    val restored = legacyRawJson?.let { raw ->
        val availableIds = remote.modelEntries.mapTo(mutableSetOf()) { entry ->
            compositeEntryKey(entry.providerInstanceId, entry.baseModel.id)
        }
        val legacyState = LegacyGroupStateParser.fromJson(
            config = remote,
            rawJson = raw,
            availableEntryIds = availableIds,
        )
        LegacyGroupMigrator.migrate(legacyState).config
    } ?: remote
    return synchronized(configLock) {
        val local = _config.value
        val before = local.instances.size

        val orderedInstances = mutableListOf<ProviderInstance>()
        val placedInstances = mutableSetOf<String>()
        for (ri in restored.instances) {
            val existing = local.instances.firstOrNull { it.id == ri.id }
            orderedInstances.add(existing ?: ri)
            placedInstances.add(ri.id)
        }
        for (li in local.instances) {
            if (li.id !in placedInstances) orderedInstances.add(li)
        }

        val mergedEntries = local.modelEntries.toMutableList()
        for (entry in restored.modelEntries) {
            val durableId = compositeEntryKey(entry.providerInstanceId, entry.baseModel.id)
            if (mergedEntries.none { compositeEntryKey(it.providerInstanceId, it.baseModel.id) == durableId }) {
                mergedEntries.add(entry.copy(uuid = durableId))
            }
        }

        val mergedAgentEntries =
            (local.agentLoopModelEntryIds + restored.agentLoopModelEntryIds).distinct()
        val merged = local.copy(
            instances = orderedInstances,
            modelEntries = mergedEntries,
            slots = ModelSlots(
                main = local.slots.main.ifEmpty { restored.slots.main },
                light = local.slots.light.ifEmpty { restored.slots.light },
                vision = local.slots.vision.ifEmpty { restored.slots.vision },
                voiceInput = local.slots.voiceInput.ifEmpty { restored.slots.voiceInput },
                voiceOutput = local.slots.voiceOutput.ifEmpty { restored.slots.voiceOutput },
            ),
            fallbackTrigger = if (local.slots.main.isEmpty()) restored.fallbackTrigger else local.fallbackTrigger,
            agentLoopModelEntryIds = mergedAgentEntries.toMutableList(),
        )
        saveConfig(merged)
        val after = orderedInstances.size
        android.util.Log.i(
            "ProviderRepo",
            "[Restore] provider merge: instances $before→$after entries=${mergedEntries.size} slots=${merged.slots}",
        )
        before to after
    }
}

fun ProviderRepository.restoreBackupThinkingRules(rules: List<com.openminis.app.backup.BackupThinkingRuleRecord>): Pair<Int, Int> {
    var written = 0
    var skipped = 0
    for (r in rules) {
        val existing = thinkingRules(r.instanceId).any { it.id == r.id }
        if (existing) {
            skipped++
        } else {
            val entity = ProviderThinkingRuleEntity(
                id = r.id,
                providerInstanceId = r.instanceId,
                label = r.label,
                scopeKind = r.scopeKind,
                scopePattern = r.scopePattern,
                wireFormatJson = r.wireFormatJson,
                reasoningEchoJson = r.echoField,
                sortOrder = r.sortOrder,
            )
            val rule = ThinkingRuleCoding.toRule(entity)
            saveThinkingRule(r.instanceId, rule, r.id)
            written++
        }
    }
    return Pair(written, skipped)
}

/**
 * Restore missing credentials only. A backup must never roll a device's
 * current API key / bearer / OAuth state back to an older secret.
 */
fun ProviderRepository.restoreBackupProviderSecret(secret: com.openminis.app.backup.BackupSecrets.ProviderSecret): Boolean {
    fun deb64(value: String?): String? = value?.let {
        runCatching { String(Base64.decode(it, Base64.NO_WRAP), Charsets.UTF_8) }.getOrNull()
    }

    val instance = instance(secret.instanceId) ?: return false
    var wrote = false

    deb64(secret.apiKey)?.let { key ->
        if (loadApiKey(instance.id).isNullOrBlank()) {
            saveApiKey(instance.id, key)
            wrote = true
        }
    }

    deb64(secret.manualOAuthToken)?.let { token ->
        val manualManager = oauthManagerFor(instance)
            ?: com.openminis.app.auth.OAuthManager.forInstance(context, instance)
        if (manualManager != null && manualManager.loadManualBearerToken().isNullOrBlank()) {
            manualManager.saveManualBearerToken(token)
            wrote = true
        }
    }

    val oauthManager = oauthManagerFor(instance)
    if (oauthManager != null) {
        deb64(secret.oauthToken)?.let { tokenJson ->
            if (oauthManager.exportStoredTokensJson().isNullOrBlank()) {
                oauthManager.importStoredTokensJson(tokenJson)
                wrote = true
            }
        }
        if (instance.providerType == ProviderType.gemini) {
            deb64(secret.oauthEmail)?.let { email ->
                if (oauthManager.exportOAuthString("email").isNullOrBlank()) {
                    oauthManager.importOAuthString("email", email)
                    wrote = true
                }
            }
            deb64(secret.oauthGcpProject)?.let { project ->
                if (oauthManager.exportOAuthString("gcp_project").isNullOrBlank()) {
                    oauthManager.importOAuthString("gcp_project", project)
                    wrote = true
                }
            }
        }
    }
    return wrote
}
