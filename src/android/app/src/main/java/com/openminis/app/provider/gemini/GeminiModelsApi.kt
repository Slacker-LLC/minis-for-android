package com.openminis.app.provider.gemini

import android.content.Context
import com.openminis.app.data.model.LLMModel
import com.openminis.app.provider.ModelsDevApi
import com.openminis.app.provider.ProviderModelsCache
import com.openminis.app.provider.applyUserAgentOverride
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject

object GeminiModelsApi {
    private val client = com.openminis.app.provider.ProviderTransportPolicy.protectedHttpsClient()
    private val cache = ProviderModelsCache("gemini")

    /**
     * Fetch the Gemini model catalog. Three auth modes matching iOS
     * `GeminiModelsAPI`:
     *   - API key via `?key=<k>` query param
     *   - OAuth via `Authorization: Bearer <token>` (no key param)
     *   - Cloud Code Assist: no public list endpoint — caller passes
     *     `cloudCodeFallback=true` to short-circuit straight to the built-in
     *     `com.openminis.app.provider.rules.ModelRulesProvider.staticModels("gemini")` list.
     *
     * The spec also requires a **403 fallback** on OAuth: when the OAuth
     * token lacks the `generative-language` scope (common for Cloud Code
     * Assist tokens) the endpoint returns 403 — we fall back to the built-in
     * list instead of surfacing an error, matching iOS.
     *
     * @param context When provided, enables the 7-day disk cache at
     *   `cacheDir/models-cache/gemini/<sha256>.json`.
     */
    suspend fun fetchModels(
        apiKey: String,
        isOAuth: Boolean = false,
        cloudCodeFallback: Boolean = false,
        context: Context? = null,
        forceRefresh: Boolean = false,
    ): List<LLMModel> = withContext(Dispatchers.IO) {
        if (cloudCodeFallback) return@withContext com.openminis.app.provider.rules.ModelRulesProvider.staticModels("gemini")

        val cacheKey = (if (isOAuth) "oauth|" else "key|") + apiKey
        if (context != null && !forceRefresh) {
            cache.load(context, cacheKey)?.let { return@withContext it }
        }

        val staticModels = com.openminis.app.provider.rules.ModelRulesProvider.staticModels("gemini")
        val collected = mutableListOf<LLMModel>()
        var pageToken: String? = null
        // The endpoint pages (50 by default), so asking for one page can leave
        // the newest models off the list. A later page that fails keeps what
        // the earlier pages returned; only a failed first page falls back.
        for (page in 0 until MAX_PAGES) {
            val builder = Request.Builder()
            val tokenParam = pageToken?.let { "&pageToken=" + java.net.URLEncoder.encode(it, "UTF-8") }.orEmpty()
            if (isOAuth) {
                builder.url("$MODELS_URL?pageSize=$PAGE_SIZE$tokenParam")
                builder.header("Authorization", "Bearer $apiKey")
            } else {
                builder.url("$MODELS_URL?key=$apiKey&pageSize=$PAGE_SIZE$tokenParam")
            }

            // [T-android-default-ua] brand outbound /v1beta/models request.
            builder.applyUserAgentOverride(null)
            val response = try {
                client.newCall(builder.build()).execute()
            } catch (e: Exception) {
                if (page == 0) throw e
                break
            }
            val body = response.use { it.body?.string() }
            if (!response.isSuccessful || body == null) {
                if (page > 0) break
                // 403 on OAuth almost always means the token lacks the
                // generative-language scope. Falling back to the built-in list
                // matches iOS and keeps Cloud Code Assist users functional.
                if (context != null && !(isOAuth && response.code == 403) &&
                    (response.code == 401 || response.code == 403)
                ) {
                    cache.invalidate(context, cacheKey)
                }
                return@withContext staticModels
            }
            val parsed = parseModelsPage(body)
            if (parsed == null) {
                if (page > 0) break
                return@withContext staticModels
            }
            collected += parsed.models
            pageToken = parsed.nextPageToken
            if (pageToken == null) break
        }
        if (collected.isEmpty()) return@withContext staticModels
        val models = ModelsDevApi.enrichModels(collected.distinctBy { it.id })

        if (context != null) cache.save(context, cacheKey, models)
        models
    }

    internal class ModelsPage(val models: List<LLMModel>, val nextPageToken: String?)

    /** One `models.list` page; null when the body is not a models page at all. */
    internal fun parseModelsPage(body: String): ModelsPage? = try {
        val json = JSONObject(body)
        val arr = json.optJSONArray("models")
        if (arr == null) {
            null
        } else {
            val result = mutableListOf<LLMModel>()
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                val name = obj.getString("name").removePrefix("models/")
                val displayName = obj.optString("displayName", name)
                // Filter to chat-capable models (matching iOS).
                val supportsGen = obj.optJSONArray("supportedGenerationMethods")
                    ?.let { methods ->
                        (0 until methods.length()).any {
                            methods.getString(it).contains("generateContent")
                        }
                    } == true
                if (supportsGen) {
                    result.add(LLMModel(name, displayName, "Google"))
                }
            }
            ModelsPage(result, json.optString("nextPageToken", "").ifEmpty { null })
        }
    } catch (_: Exception) {
        null
    }

    private const val MODELS_URL = "https://generativelanguage.googleapis.com/v1beta/models"
    private const val PAGE_SIZE = 1000
    private const val MAX_PAGES = 5
}
