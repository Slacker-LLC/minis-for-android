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
