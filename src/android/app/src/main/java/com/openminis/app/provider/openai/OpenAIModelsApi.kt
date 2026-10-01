package com.openminis.app.provider.openai

import android.content.Context
import com.openminis.app.data.model.LLMModel
import com.openminis.app.data.model.normalizeModalities
import com.openminis.app.logging.AppLogger
import com.openminis.app.provider.ModelsDevApi
import com.openminis.app.provider.ProviderModelsCache
import com.openminis.app.provider.applyUserAgentOverride
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject

object OpenAIModelsApi {
    private const val TAG = "OpenAIModelsApi"
    private val cache = ProviderModelsCache("openai")
    private const val CODEX_BASE = "https://chatgpt.com/backend-api/codex"

    /**
     * Codex OAuth fallback catalog is loaded from model-rules.json because these
     * tokens cannot call /v1/models. Order is preserved so the picker keeps its
     * default rank.
     */
    // T119: every GPT-5.x model on Codex OAuth supports reasoning_effort
    // (`/v1/responses` requires the `reasoning` object on this auth path),
    // so set supportsReasoning = true up front. Without it the Thinking
    // pill in chat is disabled and the user can't pick low/medium/high.
    // Keep the current callable-id allow-list unchanged: unsupported ids can
    // return HTTP 400 and render as an empty assistant turn. Re-probe against a
    // live Codex token before adding any model back.
    fun fetchModelsOAuth(): List<LLMModel> {
        val catalog = com.openminis.app.provider.rules.ModelRulesProvider.staticModels("codexOAuth")
        val textModels = catalog.filter(LLMModel::isTextOutput)
        val specialRoutes = catalog.filterNot(LLMModel::isTextOutput)
        AppLogger.info(TAG, "Codex OAuth model list (${textModels.size} text models): ${textModels.joinToString { it.id }}")
        return ModelsDevApi.enrichModels(textModels) + specialRoutes
    }

    /**
     * Codex OAuth catalog: the live list the ChatGPT backend serves for this
     * account and client version, falling back to the bundled allow-list when
     * the request fails or returns nothing. The live list is authoritative for
     * callable ids (the backend rejects ids it did not list), so a model OpenAI
     * ships later appears here without an app update. Special routes the list
     * never carries (the image-only model) are kept from the bundled catalog.
     */
    suspend fun fetchCodexCatalog(token: String, accountId: String?): List<LLMModel> {
        return mergeCodexCatalog(fetchModelsOAuth(), fetchModelsCodexOAuth(token, accountId))
    }

    internal fun mergeCodexCatalog(bundled: List<LLMModel>, live: List<LLMModel>): List<LLMModel> {
        if (live.isEmpty()) return bundled
        val liveIds = live.mapTo(HashSet()) { it.id }
        val specialRoutes = bundled.filterNot(LLMModel::isTextOutput).filter { it.id !in liveIds }
        return live + specialRoutes
    }

    /** Empty on any failure: callers fall back to the bundled list rather than show nothing. */
    suspend fun fetchModelsCodexOAuth(token: String, accountId: String?): List<LLMModel> =
        withContext(Dispatchers.IO) {
            val version = OpenAIProvider.CODEX_CLIENT_VERSION
            val url = "$CODEX_BASE/models?client_version=$version"
            try {
                val builder = Request.Builder()
                    .url(url)
                    .get()
                    .header("Authorization", "Bearer $token")
                    .header("Version", version)
                    .header("User-Agent", "codex_cli_rs/$version (Android; arm64)")
                    .header("Originator", "codex_cli_rs")
                accountId?.let { builder.header("Chatgpt-Account-Id", it) }
                val client = com.openminis.app.provider.ProviderTransportPolicy
                    .protectedHttpsBuilder(OkHttpClient.Builder())
                    .build()
                client.newCall(builder.build()).execute().use { response ->
                    val body = response.body?.string().orEmpty()
                    if (!response.isSuccessful) {
                        AppLogger.warning(TAG, "Codex model list HTTP ${response.code}")
                        return@withContext emptyList()
                    }
                    val parsed = parseCodexModels(body)
                    AppLogger.info(TAG, "Codex live model list (${parsed.size}): ${parsed.joinToString { it.id }}")
                    ModelsDevApi.enrichModels(
                        parsed.map { com.openminis.app.provider.rules.ModelRulesProvider.applyCapabilities(it) },
                    )
                }
            } catch (e: Exception) {
                AppLogger.warning(TAG, "Codex model list failed: ${e.message}")
                emptyList()
            }
        }

    /**
     * Parses the `{"models":[…]}` body of the Codex models endpoint into text
     * models in the backend's own order (ascending `priority`). Entries the
     * Codex picker would not show (`visibility` other than `list`), entries
     * without a slug, and repeated slugs are dropped; a malformed body yields
     * an empty list.
     */
    internal fun parseCodexModels(body: String): List<LLMModel> {
        val data = try {
            JSONObject(body).optJSONArray("models")
        } catch (_: Exception) {
            null
        } ?: return emptyList()
        val seen = HashSet<String>()
        val entries = ArrayList<Pair<Int, LLMModel>>()
        for (i in 0 until data.length()) {
            val obj = data.optJSONObject(i) ?: continue
            val slug = obj.optString("slug", "").trim()
            if (slug.isEmpty() || !seen.add(slug)) continue
            if (obj.optString("visibility", "list") != "list") continue
            val efforts = obj.optJSONArray("supported_reasoning_levels")?.let { levels ->
                (0 until levels.length()).mapNotNull { idx ->
                    levels.optJSONObject(idx)?.optString("effort", "")?.lowercase()?.takeIf(String::isNotEmpty)
                }
            }.orEmpty()
            val model = LLMModel(
                id = slug,
                displayName = obj.optString("display_name", "").ifBlank { slug },
                provider = "OpenAI",
                // Every listed Codex model that declares effort levels reasons; the
                // bundled catalog marks the whole family `supportsReasoning = true`
                // for the same reason (the chat Thinking pill depends on it).
                supportsReasoning = if (efforts.isEmpty()) null else true,
                reasoningEffortValues = efforts.takeIf { it.isNotEmpty() },
                inputModalities = obj.optJSONArray("input_modalities")?.toStringList().normalizeModalities(),
            )
            entries.add(obj.optInt("priority", Int.MAX_VALUE) to model)
        }
        return entries.sortedBy { it.first }.map { it.second }
    }


    suspend fun fetchModels(
        apiKey: String,
        baseURL: String? = null,
        context: Context? = null,
        forceRefresh: Boolean = false,
        // [T-provider-custom-user-agent] Per-provider UA override; null/blank
        // keeps the default UA. Threaded from ProviderRepository.refreshModels.
        customUserAgent: String? = null,
    ): List<LLMModel> = withContext(Dispatchers.IO) {
        val isCustomBase = baseURL != null && !isOfficialOpenAI(baseURL)
        // For third-party endpoints (vLLM, Ollama, etc.), return empty on failure
        // so the caller preserves existing models instead of replacing with built-in GPT list.
        val fallback = if (isCustomBase) emptyList() else com.openminis.app.provider.rules.ModelRulesProvider.staticModels("openAI")

        val cacheKey = (baseURL ?: "") + "|" + apiKey
        if (context != null && !forceRefresh) {
            cache.load(context, cacheKey)?.let { return@withContext it }
        }

        val url = buildURL(baseURL)
        val client = com.openminis.app.provider.ProviderTransportPolicy.clientForBase(url)
        val request = Request.Builder()
            .url(url)
            .header("Authorization", "Bearer $apiKey")
            // [T-provider-custom-user-agent] models-list UA override.
            .applyUserAgentOverride(customUserAgent)
            .build()

        val response = client.newCall(request).execute()
        val body = response.body?.string() ?: return@withContext fallback

        if (!response.isSuccessful) {
            if (context != null && (response.code == 401 || response.code == 403)) {
                cache.invalidate(context, cacheKey)
            }
            return@withContext fallback
        }

        val models = try {
            val json = JSONObject(body)
            val data = json.optJSONArray("data") ?: return@withContext fallback
            val parsed = mutableListOf<LLMModel>()
            for (i in 0 until data.length()) {
                val obj = data.getJSONObject(i)
                val id = obj.getString("id")

                // Only apply the official OpenAI picker policy to official endpoints;
                // custom endpoints (vLLM, Ollama) may serve any model ID.
                if (!isCustomBase && com.openminis.app.provider.rules.ModelRulesProvider
                        .pickerFilter("openAI")?.accepts(id) == false
                ) continue

                val displayName = obj.optString("name", id)
                // Third-party gateways (vLLM, OpenRouter-compat proxies) often
                // report per-model modalities under `architecture.{input,output}_modalities`
                // the same way OpenRouter does — pick them up so vision/audio
                // models are routable without waiting for models.dev enrichment.
                val arch = obj.optJSONObject("architecture")
                // OpenAI / OpenRouter return modalities as `image_input` / `text_output` with
                // suffixes; the rest of the codebase (models.dev, capability fragments,
                // ModelEntryDetailScreen toggles) uses the bare form. Normalize at the parse
                // boundary so persisted overrides round-trip correctly through the toggles.
                val inputModalities = arch?.optJSONArray("input_modalities")?.toStringList().normalizeModalities()
                val outputModalities = arch?.optJSONArray("output_modalities")?.toStringList().normalizeModalities()


                parsed.add(
                    com.openminis.app.provider.rules.ModelRulesProvider.applyCapabilities(LLMModel(
                        id = id,
                        displayName = displayName,
                        provider = if (isCustomBase) "Custom" else "OpenAI",
                        inputModalities = inputModalities,
                        outputModalities = outputModalities,
                    ))
                )
            }
            if (parsed.isEmpty()) return@withContext fallback
            ModelsDevApi.enrichModels(parsed)
        } catch (_: Exception) {
            return@withContext fallback
        }

        if (context != null) cache.save(context, cacheKey, models)
        models
    }

    private fun JSONArray.toStringList(): List<String> {
        val out = ArrayList<String>(length())
        for (i in 0 until length()) {
            val s = optString(i, "")
            if (s.isNotEmpty()) out.add(s)
        }
        return out
    }


    /** Check if a base URL points to official OpenAI endpoints. */
    private fun isOfficialOpenAI(baseURL: String): Boolean {
        val lower = baseURL.lowercase()
        return lower.contains("api.openai.com") || lower.contains("chatgpt.com")
    }

    private fun buildURL(baseURL: String?): String {
        if (baseURL == null) return "https://api.openai.com/v1/models"
        // baseURL is ProviderConfig.effectiveBaseURL — it already has /v1 iff the
        // appendV1Suffix toggle is on. Only append /models; never force /v1, or the
        // toggle is overridden and services without /v1 return 404.
        val base = baseURL.trimEnd('/')
        return "$base/models"
    }
}
