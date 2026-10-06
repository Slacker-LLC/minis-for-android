package com.openminis.app.debug

import android.content.Context
import com.openminis.app.MinisApp
import com.openminis.app.data.model.FallbackStrategy
import com.openminis.app.data.model.ModelSlot
import com.openminis.app.data.model.hasAudioInput
import com.openminis.app.data.model.hasAudioOutput
import com.openminis.app.data.model.hasImageInput
import com.openminis.app.data.model.SystemVoiceEntries
import com.openminis.app.data.model.ImageEndpointMode
import com.openminis.app.data.model.LLMModel
import com.openminis.app.data.model.ModelEntry
import com.openminis.app.data.model.ModelOverrides
import com.openminis.app.data.model.ProviderCredential
import com.openminis.app.data.model.ProviderInstance
import com.openminis.app.data.model.ProviderType
import com.openminis.app.data.repository.ProviderRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/**
 * Mutation handlers for the `provider.*` RPC methods —
 * Phase 2.
 *
 * Each method routes through the existing [ProviderRepository] surface, which
 * is the same code path the in-app Settings screens use. Mutations are written
 * synchronously to disk; Keychain-equivalent (EncryptedSharedPreferences)
 * writes happen alongside JSON config writes — a failed credential write is
 * surfaced as -32000.
 */
internal object ProviderMutationMethods {

    private fun repo(context: Context): ProviderRepository =
        (context.applicationContext as? MinisApp
            ?: throw RPCException(-32000, "MinisApp not initialized")).providerRepository

    // ─── Instances ──────────────────────────────────────────────────────────

    fun instancesCreate(context: Context, params: JSONObject): JSONObject {
        val repo = repo(context)
        val typeRaw = params.optString("providerType", "").ifEmpty {
            throw RPCException(-32602, "Missing 'providerType' param")
        }
        val type = try { ProviderType.valueOf(typeRaw) } catch (_: Exception) {
            throw RPCException(-32602, "Unknown providerType: $typeRaw")
        }
        val label = params.optString("label", "").ifEmpty {
            throw RPCException(-32602, "Missing 'label' param")
        }
        val credTypeStr = params.optString("credentialType", "apiKey").ifEmpty { "apiKey" }
        val credentialType = try { ProviderCredential.valueOf(credTypeStr) } catch (_: Exception) {
            throw RPCException(-32602, "Unknown credentialType: $credTypeStr")
        }
        val customBaseURL = params.optString("customBaseURL", "").ifEmpty { null }
        val appendV1Suffix = params.optBoolean("appendV1Suffix", true)
        val isEnabled = params.optBoolean("isEnabled", true)
        val useResponsesAPI = params.optBoolean("useResponsesAPI", false)
        val seedBuiltInModels = params.optBoolean("seedBuiltInModels", true)

        // OpenAI-compatible and Anthropic-compatible endpoints both support a
        // custom origin. Keep this in sync with provider.types so Web clients
        // are not told Anthropic supports a field that creation then rejects.
        if (customBaseURL != null && type != ProviderType.openAI && type != ProviderType.anthropic) {
            throw RPCException(-32602, "customBaseURL only supported for providerType=openAI or anthropic")
        }

        // [T-ua-block-oauth-provider] customUserAgent only for apiKey providers.
        val customUserAgent = params.optString("customUserAgent", "").ifEmpty { null }
        if (customUserAgent != null && credentialType != ProviderCredential.apiKey) {
            throw RPCException(-32602, "Custom User-Agent is only supported for third-party API-key providers. OAuth providers (Anthropic/Codex) use their own authentication UA and cannot be overridden.")
        }

        // Credentials are validated with the rest of the request, before anything is created.
        val apiKey = RpcParams.string(params, "apiKey")?.ifEmpty { null }
        val oauthToken = RpcParams.string(params, "oauthToken")?.ifEmpty { null }

        val instance = ProviderInstance(
            id = UUID.randomUUID().toString(),
            label = label,
            providerType = type,
            credentialType = credentialType,
            isEnabled = isEnabled,
            customBaseURL = customBaseURL,
            appendV1Suffix = appendV1Suffix,
            useResponsesAPI = useResponsesAPI,
            customUserAgent = customUserAgent,
        )

        // [T-android-provider-mutator-lock] Delegate straight to
        // repo.addInstance. This used to add the instance and its seeds to
        // `repo.config.value`'s lists FIRST, then remove them again, then call
        // addInstance anyway — a no-op round trip whose only lasting effect was
        // mutating the PUBLISHED lists, unlocked, from the debug server's HTTP
        // thread. That is the one writer left outside the repository's
        // copy-on-write discipline, and it crashed a reader on device:
        //
        //   ConcurrentModificationException
        //     at ChatViewModel$showFastModeToggle$1.invokeSuspend  (combine over
        //     providerRepository.config, iterating config.modelEntries)
        //
        // addInstance already applies the built-in seeding rule internally, so
        // nothing is lost by dropping the inline copy; the seedBuiltInModels
        // opt-out is still honoured by the sweep below.
        repo.addInstance(instance)
        if (!seedBuiltInModels) {
            // addInstance always seeds — if user opted out, sweep the seeds.
            for (entry in repo.entriesFor(instance.id).toList()) repo.removeEntry(entry.id)
        }

        // Credential write (write-only — never returned).
        val secret = apiKey ?: oauthToken
        if (secret != null) repo.saveApiKey(instance.id, secret)

        return JSONObject().put("instance", instanceToJson(repo, instance))
    }

    fun instancesUpdate(context: Context, params: JSONObject): JSONObject {
        val repo = repo(context)
        val id = params.optString("instanceId", "").ifEmpty {
            throw RPCException(-32602, "Missing 'instanceId' param")
        }
        val current = repo.instance(id)
            ?: throw RPCException(-32602, "Instance not found: $id")

        // [T-ua-block-oauth-provider] customUserAgent only applies to third-party
        // API-key providers. OAuth providers (Anthropic/Codex) ride their own
        // authentication request chain with a fixed client UA — overriding it can
        // break the handshake or be rejected upstream. Reject any write (including
        // the empty-string clear) for non-apiKey instances, mirroring the
        // minis-config writer guard in ProvidersCollection.kt.
        val customUserAgent: String? = if (params.has("customUserAgent")) {
            if (current.credentialType != ProviderCredential.apiKey) {
                throw RPCException(-32602, "Custom User-Agent is only supported for third-party API-key providers. OAuth providers (Anthropic/Codex) use their own authentication UA and cannot be overridden.")
            }
            if (params.isNull("customUserAgent")) null
            else params.optString("customUserAgent", "").ifEmpty { null }
        } else current.customUserAgent

        // [GH#68] Optional imageEndpointMode patch. Accepts the Kotlin enum
        // name or the wire SerialName (images_generations / chat_completions).
        // Forcing a non-auto mode clears the cached probe result, mirroring the
        // picker UI, so a stale auto-probe can't shadow the user's choice.
        val imageEndpointMode = if (params.has("imageEndpointMode")) {
            when (val raw = params.optString("imageEndpointMode", "")) {
                "auto" -> ImageEndpointMode.auto
                "imagesGenerations", "images_generations" -> ImageEndpointMode.imagesGenerations
                "chatCompletions", "chat_completions" -> ImageEndpointMode.chatCompletions
                else -> throw RPCException(-32602, "imageEndpointMode must be auto | images_generations | chat_completions (got '$raw')")
            }
        } else current.imageEndpointMode

        // Read both credential fields up front: a malformed one must fail the request before the
        // instance or the other credential has changed.
        val apiKey = RpcParams.string(params, "apiKey")
        val oauthToken = RpcParams.string(params, "oauthToken")

        val updated = current.copy(
            label = if (params.has("label")) params.optString("label", current.label) else current.label,
            customBaseURL = if (params.has("customBaseURL")) {
                if (params.isNull("customBaseURL")) null
                else params.optString("customBaseURL", "").ifEmpty { null }
            } else current.customBaseURL,
            appendV1Suffix = if (params.has("appendV1Suffix")) params.optBoolean("appendV1Suffix", current.appendV1Suffix) else current.appendV1Suffix,
            useResponsesAPI = if (params.has("useResponsesAPI")) params.optBoolean("useResponsesAPI", current.useResponsesAPI) else current.useResponsesAPI,
            isEnabled = if (params.has("isEnabled")) params.optBoolean("isEnabled", current.isEnabled) else current.isEnabled,
            customUserAgent = customUserAgent,
            imageEndpointMode = imageEndpointMode,
            imageEndpointResolved = if (params.has("imageEndpointMode") && imageEndpointMode != ImageEndpointMode.auto) {
                null
            } else current.imageEndpointResolved,
        )
        repo.updateInstance(updated)

        // Credential refresh — pass `""` to clear, missing to leave alone.
        for (v in listOfNotNull(apiKey, oauthToken)) {
            if (v.isEmpty()) repo.deleteApiKey(id) else repo.saveApiKey(id, v)
        }

        return JSONObject().put("instance", instanceToJson(repo, updated))
    }

    fun instancesDelete(context: Context, params: JSONObject): JSONObject {
        val repo = repo(context)
        val id = params.optString("instanceId", "").ifEmpty {
            throw RPCException(-32602, "Missing 'instanceId' param")
        }
        if (!params.optBoolean("confirm", false)) {
            throw RPCException(-32602, "Pass confirm=true to delete an instance")
        }
        val current = repo.instance(id)
            ?: throw RPCException(-32602, "Instance not found: $id")
        val priorEntryCount = repo.entriesFor(id).size
        repo.removeInstance(id)
        return JSONObject().apply {
            put("instanceId", id)
            put("deletedModelEntries", priorEntryCount)
            put("deleted", true)
        }
    }

    suspend fun instancesTest(context: Context, params: JSONObject): JSONObject = withContext(Dispatchers.IO) {
        val repo = repo(context)
        val id = params.optString("instanceId", "").ifEmpty {
            throw RPCException(-32602, "Missing 'instanceId' param")
        }
        val instance = repo.instance(id)
            ?: throw RPCException(-32602, "Instance not found: $id")
        val timeoutMs = params.optInt("timeoutMs", 10_000).coerceIn(1_000, 30_000)

        val baseURL = instance.effectiveBaseURL ?: when (instance.providerType) {
            ProviderType.anthropic -> "https://api.anthropic.com"
            ProviderType.gemini -> "https://generativelanguage.googleapis.com"
            // [T-android-provider-type-parity] Responses API shares the host.
            ProviderType.openAI, ProviderType.openAIResponses -> "https://api.openai.com/v1"
            ProviderType.openRouter -> "https://openrouter.ai/api/v1"
            ProviderType.xAI -> "https://api.x.ai/v1"
            ProviderType.kimiCode -> "https://api.kimi.com/coding/v1"
            ProviderType.antigravity, ProviderType.unsupported -> ""
        }
        val probeURL = when (instance.providerType) {
            ProviderType.anthropic -> "$baseURL/v1/models"
            ProviderType.gemini -> "$baseURL/v1beta/models"
            ProviderType.openAI, ProviderType.openAIResponses ->
                if (baseURL.endsWith("/v1")) "$baseURL/models" else "$baseURL/v1/models"
            ProviderType.openRouter -> "$baseURL/models"
            // xAI exposes an OpenAI-compatible /models endpoint at the same base.
            ProviderType.xAI -> if (baseURL.endsWith("/v1")) "$baseURL/models" else "$baseURL/v1/models"
            // Kimi Coding: OpenAI-compatible /models under /coding/v1.
            ProviderType.kimiCode -> if (baseURL.endsWith("/v1")) "$baseURL/models" else "$baseURL/v1/models"
            // No probe endpoint for a type this build cannot drive.
            ProviderType.antigravity, ProviderType.unsupported -> baseURL
        }
        val client = okhttp3.OkHttpClient.Builder()
            .connectTimeout(timeoutMs.toLong(), java.util.concurrent.TimeUnit.MILLISECONDS)
            .readTimeout(timeoutMs.toLong(), java.util.concurrent.TimeUnit.MILLISECONDS)
            .build()

        val builder = okhttp3.Request.Builder().url(probeURL).get()
        val key = repo.loadApiKey(id)
        when (instance.providerType) {
            ProviderType.anthropic -> if (!key.isNullOrEmpty()) builder.header("x-api-key", key).header("anthropic-version", "2023-06-01")
            ProviderType.openAI, ProviderType.openAIResponses ->
                if (!key.isNullOrEmpty()) builder.header("Authorization", "Bearer $key")
            ProviderType.openRouter -> if (!key.isNullOrEmpty()) builder.header("Authorization", "Bearer $key")
            ProviderType.gemini -> if (!key.isNullOrEmpty()) builder.header("x-goog-api-key", key)
            // xAI: OpenAI-compat bearer header. Manual API key OR OAuth
            // access token can fill this slot.
            ProviderType.xAI -> if (!key.isNullOrEmpty()) builder.header("Authorization", "Bearer $key")
            // Kimi Coding: OpenAI-compat bearer (OAuth access token or key).
            ProviderType.kimiCode -> if (!key.isNullOrEmpty()) builder.header("Authorization", "Bearer $key")
            // [T-android-provider-type-parity] No auth scheme known for a type
            // this build cannot drive; the probe will simply fail.
            ProviderType.antigravity, ProviderType.unsupported -> { /* no auth */ }
        }
        val start = System.currentTimeMillis()
        try {
            val resp = client.newCall(builder.build()).execute()
            val ok = resp.isSuccessful
            val latency = System.currentTimeMillis() - start
            val bodyStr = resp.body?.string() ?: ""
            resp.close()
            // Best-effort model count parse — Anthropic/OpenAI/OpenRouter all
            // wrap models in a top-level `data` array; Gemini uses `models`.
            val modelCount: Int = try {
                val obj = JSONObject(bodyStr)
                val arr = obj.optJSONArray("data") ?: obj.optJSONArray("models")
                arr?.length() ?: -1
            } catch (_: Exception) { -1 }
            JSONObject().apply {
                put("ok", ok)
                put("httpStatus", resp.code)
                put("latencyMs", latency)
                if (modelCount >= 0) put("reachableModelCount", modelCount)
                put("error", JSONObject.NULL)
            }
        } catch (e: Exception) {
            JSONObject().apply {
                put("ok", false)
                put("httpStatus", JSONObject.NULL)
                put("latencyMs", System.currentTimeMillis() - start)
                put("error", JSONObject().apply {
                    put("code", "network")
                    put("message", "Connection failed (${e.javaClass.simpleName})")
                })
            }
        }
    }

    private fun instanceToJson(repo: ProviderRepository, inst: ProviderInstance): JSONObject {
        val cfg = repo.config.value
        val entryCount = cfg.modelEntries.count { it.providerInstanceId == inst.id }
        return JSONObject().apply {
            put("id", inst.id)
            put("label", inst.label)
            put("providerType", inst.providerType.name)
            put("credentialType", inst.credentialType.name)
            put("isEnabled", inst.isEnabled)
            put("customBaseURL", inst.customBaseURL ?: JSONObject.NULL)
            put("appendV1Suffix", inst.appendV1Suffix)
            put("useResponsesAPI", inst.useResponsesAPI)
            put("customUserAgent", inst.customUserAgent ?: JSONObject.NULL)
            put("createdAt", inst.createdAt)
            put("hasCredential", repo.loadApiKey(inst.id)?.isNotEmpty() == true)
            put("modelEntryCount", entryCount)
        }
    }

    // ─── Models ─────────────────────────────────────────────────────────────

    fun modelsAdd(context: Context, params: JSONObject): JSONObject {
        val repo = repo(context)
        val instanceId = params.optString("instanceId", "").ifEmpty {
            throw RPCException(-32602, "Missing 'instanceId' param")
        }
        val instance = repo.instance(instanceId)
            ?: throw RPCException(-32602, "Instance not found: $instanceId")
        val modelId = params.optString("modelId", "").ifEmpty {
            throw RPCException(-32602, "Missing 'modelId' param")
        }
        val displayName = params.optString("displayName", "").ifEmpty {
            // Prettify: "deepseek-v4-pro" → "Deepseek V4 Pro" — same heuristic
            // the iOS doc describes; cheap inline impl matches the result for
            // typical hyphenated model ids without dragging in iOS-specific
            // formatting helpers.
            modelId.split('-', '_', '/').joinToString(" ") { piece ->
                if (piece.isEmpty()) "" else piece.replaceFirstChar { it.uppercase() }
            }.trim()
        }
        val contextWindow = if (params.has("contextWindow") && !params.isNull("contextWindow")) params.optInt("contextWindow") else null
        val maxOut = if (params.has("maxOutputTokens") && !params.isNull("maxOutputTokens")) params.optInt("maxOutputTokens") else null
        val supportsReasoning = if (params.has("supportsReasoning") && !params.isNull("supportsReasoning")) params.optBoolean("supportsReasoning") else null
        val inputModalities = mutableListOf<String>()
        if (params.optBoolean("supportsImageInput", false)) inputModalities.add("image")
        if (params.optBoolean("supportsAudioInput", false)) inputModalities.add("audio")
        if (params.optBoolean("supportsVideoInput", false)) inputModalities.add("video")
        if (params.optBoolean("supportsPDFInput", false)) inputModalities.add("pdf")
        val outputModalities = mutableListOf<String>()
        if (params.optBoolean("supportsImageOutput", false)) outputModalities.add("image")

        val model = LLMModel(
            id = modelId,
            displayName = displayName,
            provider = instance.providerType.displayName,
            contextWindow = contextWindow,
            maxOutputTokens = maxOut,
            supportsReasoning = supportsReasoning,
            inputModalities = inputModalities.takeIf { it.isNotEmpty() },
            outputModalities = outputModalities.takeIf { it.isNotEmpty() },
        )
        val entry = ModelEntry(providerInstanceId = instanceId, baseModel = model, isCustom = true)
        repo.addEntry(entry)
        return entryToJson(entry)
    }

    fun modelsUpdate(context: Context, params: JSONObject): JSONObject {
        val repo = repo(context)
        val entryId = params.optString("entryId", "").ifEmpty {
            throw RPCException(-32602, "Missing 'entryId' param")
        }
        val cfg = repo.config.value
        val current = cfg.modelEntries.firstOrNull { it.id == entryId }
            ?: throw RPCException(-32602, "Entry not found: $entryId")

        // modelId only mutable on custom entries.
        if (params.has("modelId")) {
            if (!current.isCustom) {
                throw RPCException(-32602, "built-in model ID is immutable")
            }
        }
        val newModel = if (params.has("modelId")) {
            val mid = params.optString("modelId", current.baseModel.id)
            current.baseModel.copy(id = mid)
        } else current.baseModel

        val newOverrides = current.overrides.copy(
            displayName = if (params.has("displayName")) {
                if (params.isNull("displayName")) null else params.optString("displayName", "").ifEmpty { null }
            } else current.overrides.displayName,
            maxOutputTokens = if (params.has("maxOutputTokens")) {
                if (params.isNull("maxOutputTokens")) null else params.optInt("maxOutputTokens").takeIf { it > 0 }
            } else current.overrides.maxOutputTokens,
            contextWindow = if (params.has("contextWindow")) {
                if (params.isNull("contextWindow")) null else params.optInt("contextWindow").takeIf { it > 0 }
            } else current.overrides.contextWindow,
        )
        val newHidden = if (params.has("isHidden")) params.optBoolean("isHidden", current.isHidden) else current.isHidden

        val updated = current.copy(baseModel = newModel, overrides = newOverrides, isHidden = newHidden)
        repo.updateEntry(updated)
        return entryToJson(updated)
    }

    fun modelsDelete(context: Context, params: JSONObject): JSONObject {
        val repo = repo(context)
        val entryId = params.optString("entryId", "").ifEmpty {
            throw RPCException(-32602, "Missing 'entryId' param")
        }
        if (!params.optBoolean("confirm", false)) {
            throw RPCException(-32602, "Pass confirm=true to delete an entry")
        }
        val current = repo.config.value.modelEntries.firstOrNull { it.id == entryId }
            ?: throw RPCException(-32602, "Entry not found: $entryId")
        if (!current.isCustom) {
            throw RPCException(-32602, "Built-in entries can't be deleted — set isHidden=true via provider.models.update instead")
        }
        repo.removeEntry(entryId)
        return JSONObject().apply {
            put("entryId", entryId)
            put("deleted", true)
        }
    }

    suspend fun modelsRefresh(context: Context, params: JSONObject): JSONObject {
        val repo = repo(context)
        val instanceId = params.optString("instanceId", "").ifEmpty {
            throw RPCException(-32602, "Missing 'instanceId' param")
        }
        val instance = repo.instance(instanceId)
            ?: throw RPCException(-32602, "Instance not found: $instanceId")

        val before = repo.entriesFor(instanceId).map { it.baseModel.id }.toSet()
        val start = System.currentTimeMillis()
        val refreshed = try {
            repo.refreshModels(instance)
        } catch (e: Exception) {
            throw RPCException(-32000, "refreshModels failed: ${e.message}")
        }
        val after = repo.entriesFor(instanceId).map { it.baseModel.id }.toSet()
        val added = (after - before).size
        val disappeared = (before - after).size
        return JSONObject().apply {
            put("instanceId", instanceId)
            // False when no catalog source returned models and the existing list was kept.
            put("refreshed", refreshed)
            put("added", added)
            put("disappeared", disappeared)
            put("total", after.size)
            put("durationMs", System.currentTimeMillis() - start)
        }
    }

    fun modelsSetAgentLoop(context: Context, params: JSONObject): JSONObject {
        val repo = repo(context)
        val entryId = params.optString("entryId", "").ifEmpty {
            throw RPCException(-32602, "Missing 'entryId' param")
        }
        if (!params.has("inLoop")) throw RPCException(-32602, "Missing 'inLoop' param")
        val want = params.optBoolean("inLoop", false)
        val cfg = repo.config.value
        if (cfg.modelEntries.none { it.id == entryId }) {
            throw RPCException(-32602, "Entry not found: $entryId")
        }
        val current = cfg.agentLoopModelEntryIds.toMutableList()
        current.removeAll { it == entryId }
        if (want) current.add(entryId)
        repo.setAgentLoopEntryIds(current)
        return JSONObject().apply {
            put("entryId", entryId)
            put("inLoop", want)
        }
    }

    private fun entryToJson(entry: ModelEntry): JSONObject {
        val effective = entry.model
        return JSONObject().apply {
            put("id", entry.id)
            put("modelId", effective.id)
            put("displayName", effective.displayName)
            put("baseModelDisplayName", entry.baseModel.displayName)
            put("isCustom", entry.isCustom)
            put("isHidden", entry.isHidden)
            put("supportsReasoning", effective.supportsReasoning ?: JSONObject.NULL)
            put("contextWindow", effective.contextWindow ?: JSONObject.NULL)
            put("maxOutputTokens", effective.maxOutputTokens ?: JSONObject.NULL)
            put("defaultThinkingLevel", entry.overrides.defaultThinkingLevel?.name ?: JSONObject.NULL)
            put("contextLimitTokens", entry.overrides.contextLimitTokens ?: JSONObject.NULL)
            val ov = JSONObject()
            ov.put("displayName", entry.overrides.displayName ?: JSONObject.NULL)
            ov.put("maxOutputTokens", entry.overrides.maxOutputTokens ?: JSONObject.NULL)
            ov.put("contextWindow", entry.overrides.contextWindow ?: JSONObject.NULL)
            ov.put("defaultThinkingLevel", entry.overrides.defaultThinkingLevel?.name ?: JSONObject.NULL)
            ov.put("contextLimitTokens", entry.overrides.contextLimitTokens ?: JSONObject.NULL)
            put("overrides", ov)
            put("userModifiedAt", entry.userModifiedAt ?: JSONObject.NULL)
        }
    }

    // ─── Fixed model slots ───────────────────────────────────────────────────

    fun slotsSet(context: Context, params: JSONObject): JSONObject {
        val repo = repo(context)
        val result = JSONObject()
        // Validate the whole request first; the repository saves as soon as it is called, so a
        // fallback trigger applied before a bad slot would stay applied behind the error.
        val trigger: FallbackStrategy? = if (params.has("fallbackTrigger")) {
            val raw = RpcParams.string(params, "fallbackTrigger") ?: ""
            runCatching { FallbackStrategy.valueOf(raw) }.getOrNull()
                ?: throw RPCException(-32602, "Unknown fallback trigger: $raw")
        } else null
        var slotChange: Pair<ModelSlot, List<String>>? = null
        if (params.has("slot") || params.has("entryIds")) {
            val rawSlot = (RpcParams.string(params, "slot") ?: "").ifEmpty {
                throw RPCException(-32602, "Missing 'slot' param")
            }
            val slot = ModelSlot.entries.firstOrNull { it.name == rawSlot }
                ?: throw RPCException(-32602, "Unknown model slot: $rawSlot")
            val entryIds = RpcParams.stringList(params, "entryIds")
                ?: throw RPCException(-32602, "Missing 'entryIds' array")
            val cfg = repo.config.value
            val ids = buildList {
                for (id in entryIds) {
                    if (id.isBlank()) continue
                    val virtual = SystemVoiceEntries.resolve(id)
                    val compatible = if (virtual != null) {
                        when (slot) {
                            ModelSlot.voiceInput -> virtual.model.hasAudioInput
                            ModelSlot.voiceOutput -> virtual.model.hasAudioOutput
                            else -> false
                        }
                    } else {
                        val entry = cfg.modelEntries.firstOrNull { it.id == id }
                        entry != null && !entry.isHidden && when (slot) {
                            ModelSlot.main, ModelSlot.light -> entry.model.isTextOutput
                            ModelSlot.vision -> entry.model.hasImageInput
                            ModelSlot.voiceInput -> entry.model.hasAudioInput
                            ModelSlot.voiceOutput -> entry.model.hasAudioOutput
                        }
                    }
                    if (!compatible) throw RPCException(-32602, "Entry $id is not compatible with slot ${slot.name}")
                    if (id !in this) add(id)
                }
            }
            slotChange = slot to ids
        }
        if (trigger == null && slotChange == null) throw RPCException(-32602, "Pass slot+entryIds or fallbackTrigger")
        if (trigger != null) {
            repo.setFallbackTrigger(trigger)
            result.put("fallbackTrigger", trigger.name)
        }
        slotChange?.let { (slot, ids) ->
            repo.setSlotEntries(slot, ids)
            result.put("slot", slot.name).put("entryIds", JSONArray(ids))
        }
        return result
    }

    fun modelsSetDefaults(context: Context, params: JSONObject): JSONObject {
        val repo = repo(context)
        val id = params.optString("entryId", "").ifEmpty {
            throw RPCException(-32602, "Missing 'entryId' param")
        }
        val entry = repo.config.value.modelEntries.firstOrNull { it.id == id }
            ?: throw RPCException(-32602, "Entry not found: $id")
        var defaults = entry.overrides
        if (params.has("defaultThinkingLevel")) {
            val raw = if (params.isNull("defaultThinkingLevel")) null
                else params.optString("defaultThinkingLevel").uppercase().takeIf { it.isNotBlank() }
            val level = raw?.let { runCatching { com.openminis.app.data.model.ThinkingLevel.valueOf(it) }.getOrNull() }
            if (raw != null && level == null) throw RPCException(-32602, "Unknown thinking level: $raw")
            defaults = defaults.copy(defaultThinkingLevel = level)
        }
        if (params.has("contextLimitTokens")) {
            val tokens = if (params.isNull("contextLimitTokens")) null else params.optInt("contextLimitTokens", -1)
            if (tokens != null && (tokens < 0 || tokens > Int.MAX_VALUE)) {
                throw RPCException(-32602, "contextLimitTokens must be null or 0..2147483647")
            }
            val cap = tokens?.takeIf { it > 0 }
            defaults = defaults.copy(
                contextLimitTokens = cap,
                lastContextLimitTokens = cap ?: defaults.lastContextLimitTokens,
            )
        }
        if (!params.has("defaultThinkingLevel") && !params.has("contextLimitTokens")) {
            throw RPCException(-32602, "Pass defaultThinkingLevel and/or contextLimitTokens")
        }
        repo.updateEntry(entry.copy(overrides = defaults))
        return JSONObject().apply {
            put("entryId", id)
            put("defaultThinkingLevel", defaults.defaultThinkingLevel?.name ?: JSONObject.NULL)
            put("contextLimitTokens", defaults.contextLimitTokens ?: JSONObject.NULL)
        }
    }

}
