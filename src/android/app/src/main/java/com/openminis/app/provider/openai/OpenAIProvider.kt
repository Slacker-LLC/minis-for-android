package com.openminis.app.provider.openai

import com.openminis.app.data.model.AgentToolDefinition
import com.openminis.app.data.model.LLMMediaAttachment
import com.openminis.app.data.model.LLMMessage
import com.openminis.app.data.model.LLMModel
import com.openminis.app.data.model.LLMResponse
import com.openminis.app.data.model.LLMStreamChunk
import com.openminis.app.data.model.LLMUsage
import com.openminis.app.data.model.ThinkingLevel
import com.openminis.app.provider.thinking.ThinkingRuleResolver
import com.openminis.app.provider.LLMProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Connection
import okhttp3.EventListener
import okhttp3.Handshake
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Proxy
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import com.openminis.app.provider.failOnSilentEmptyCompletion

class OpenAIProvider private constructor(
    internal val apiKey: String?,
    internal val oauthTokenProvider: (suspend () -> String)?,
    override var model: LLMModel = com.openminis.app.provider.rules.ModelRulesProvider.staticModelOrFallback("openAI", "gpt-4o-mini", "GPT-4o Mini", "OpenAI"),
    internal val basePath: String = "https://api.openai.com/v1",
    internal val extraHeaders: Map<String, String> = emptyMap(),
    /** Codex account ID for OAuth mode (extracted from JWT). */
    var codexAccountId: String? = null,
    /** When true, route through /v1/responses even on API-key providers. */
    internal val useResponsesAPI: Boolean = false,
    /**
     * When true, force Chat Completions even when the bearer is provided
     * via OAuth. Set by OAuth-but-not-Codex callers (xAI Grok) — without
     * it the default `usesChatCompletionsAPI = !isOAuth && !useResponsesAPI`
     * heuristic incorrectly drags an OAuth bearer onto the Codex
     * Responses backend at chatgpt.com, where it 404s.
     */
    internal val forceChatCompletions: Boolean = false,
    /**
     * [T-provider-custom-user-agent] Per-provider User-Agent override.
     * null/blank → default UA; non-blank → replaces User-Agent on every
     * outbound request (chat + responses). Only set for custom-base
     * OpenAI-compat instances.
     */
    internal val customUserAgent: String? = null,
    /**
     * [T-android-azure-openai] Azure OpenAI mode. When true, requests auth with
     * the `api-key:` header (not `Authorization: Bearer`) and the URL is built
     * as {azureBase}/openai/deployments/{model.id}/{path}?api-version=… from
     * [azureBase] (the raw user endpoint, which carries the ?api-version query).
     * Defaults false so every non-Azure path is byte-for-byte unchanged.
     */
    internal val isAzure: Boolean = false,
    /**
     * Raw Azure endpoint the user pasted (with any ?api-version query). Only
     * used when [isAzure]; the factory passes instance.customBaseURL verbatim
     * here because [basePath] has been normalized (/v1 appended, query dropped)
     * which is wrong for Azure's deployments-path routing.
     */
    internal val azureBase: String? = null,
) : LLMProvider {
    override val name = "OpenAI"

    /**
     * Reasoning models reject temperature on both supported OpenAI request
     * shapes; Codex OAuth also uses a fingerprint-sensitive Responses body.
     * Non-reasoning Responses relays do honor the per-session field.
     */
    override val supportsTemperatureOverride: Boolean
        get() = com.openminis.app.provider.rules.ModelRulesProvider.capabilitiesFor(model.id).rejectsTemperature != true && model.supportsReasoning != true && (usesChatCompletionsAPI || !isOAuth)

    /**
     * [T-android-thinking-rules-phase2] Owning provider-instance id, set by
     * ProviderFactory after construction (mirrors how [codexAccountId] is a
     * post-construction var). Lets the thinking resolver look up this instance's
     * user-authored custom rules from [com.openminis.app.provider.thinking.ThinkingRuleResolver]'s
     * cache. Null → no custom rules (identical to Phase-1 built-in-only behaviour).
     */
    var thinkingRuleInstanceId: String? = null

    /** Whether this instance is allowed to request xAI Priority Processing. */
    var supportsPriorityProcessing: Boolean = false


    /** API Key constructor (Chat Completions API by default; set useResponsesAPI=true for /v1/responses). */
    constructor(
        apiKey: String,
        model: LLMModel = com.openminis.app.provider.rules.ModelRulesProvider.staticModelOrFallback("openAI", "gpt-4o-mini", "GPT-4o Mini", "OpenAI"),
        basePath: String = "https://api.openai.com/v1",
        extraHeaders: Map<String, String> = emptyMap(),
        useResponsesAPI: Boolean = false,
        customUserAgent: String? = null,
        isAzure: Boolean = false,
        azureBase: String? = null,
    ) : this(
        apiKey = apiKey,
        oauthTokenProvider = null,
        model = model,
        basePath = basePath,
        extraHeaders = extraHeaders,
        useResponsesAPI = useResponsesAPI,
        customUserAgent = customUserAgent,
        isAzure = isAzure,
        azureBase = azureBase,
    )

    /** OAuth constructor (Codex Responses API). */
    constructor(
        oauthTokenProvider: suspend () -> String,
        model: LLMModel = com.openminis.app.provider.rules.ModelRulesProvider.staticModelOrFallback("openAI", "codex-mini-latest", "Codex Mini", "OpenAI"),
        codexAccountId: String? = null,
    ) : this(apiKey = null, oauthTokenProvider = oauthTokenProvider, model = model, codexAccountId = codexAccountId)

    companion object {
        /**
         * [T-android-thinking-level-arch] Codex OAuth client version advertised
         * in the Version / User-Agent headers, and the `client_version` of the
         * live model list ([OpenAIModelsApi.fetchModelsCodexOAuth]) — discovery and
         * inference must advertise the same number, because the backend gates
         * which models a version may list and call.
         *
         * 0.159.0 is the `client_version` that CLIProxyAPI (the project OpenMinis
         * tracks for this) fetches the Codex catalog with as of 2026-10-02; its
         * registry lists gpt-6.1-sol for the team / plus / pro plans, and
         * OpenMinis 1.15-beta lists GPT-6.1 Sol on the ChatGPT sign-in. It
         * replaces 0.155.0 (OpenMinis 1.14, which validated the gpt-6 / gpt-5.6
         * models on live accounts, but cannot list models gated higher). It is
         * deliberately a release a reference client already uses rather than the
         * newest Codex CLI: raise it only on evidence — a model that is listed
         * and then refused, or a wanted model the list leaves out — and verify
         * against a live account, since this number is not verified here.
         */
        internal const val CODEX_CLIENT_VERSION = "0.159.0"

        /**
         * GPT Image 2.5 (released 2026-09-08). Unlike gpt-image-2, which the
         * backend picks by default, these name themselves inside the
         * image_generation tool object (aligned with OpenMinis 1.14,
         * [T-codex-gpt-image25-android]; same shape as CLIProxyAPI PR #5642).
         */
        internal val CODEX_IMAGE_25_MODEL_IDS = setOf("gpt-image-2.5-sunburst", "gpt-image-2.5-flare")

        /** Every model driven through the Codex backend's hosted image tool. */
        internal val CODEX_IMAGE_MODELS = setOf("gpt-image-2") + CODEX_IMAGE_25_MODEL_IDS

        /**
         * [T-android-stale-conn-retry-hang] Streaming time-to-first-byte
         * budget: response HEADERS must arrive within this window. Does NOT
         * bound the SSE body — a flowing stream stays unlimited.
         *
         * [T-android-ttfb-upload-split / #188] This window now starts at
         * `requestBodyEnd` (upload complete), NOT at call start — a large
         * multimodal body over a slow proxy could burn the whole 120s just
         * uploading, so a healthy-but-slow server looked like a dead
         * connection. See [STREAM_UPLOAD_CAP_MS] for the upload-phase bound.
         */
        internal const val STREAM_TTFB_TIMEOUT_MS = 120_000L

        /**
         * [T-android-ttfb-upload-split / #188] Overall ceiling for the UPLOAD
         * phase (call start → requestBodyEnd). Keeps the watchdog effective if
         * the body upload itself wedges (writeTimeout is 30s per write op, but a
         * trickling proxy can dribble bytes forever without tripping it). Chosen
         * generous so a legitimately large body over a slow link isn't cut off:
         * the writeTimeout(30s) already bounds a fully-stalled socket; this only
         * catches the slow-but-never-idle case. Once upload completes the tighter
         * [STREAM_TTFB_TIMEOUT_MS] takes over.
         */
        internal const val STREAM_UPLOAD_CAP_MS = 120_000L

        /**
         * Factory for OAuth-bearer OpenAI-compatible providers that aren't
         * Codex (e.g. xAI Grok). Same dynamic bearer plumbing, but the
         * wire format stays Chat Completions and the endpoint is the
         * caller-supplied base URL — not chatgpt.com's Responses API.
         *
         * Implemented as a factory (not a secondary ctor) because the
         * JVM erases the signature down to
         * `(Function1, LLMModel, String)` which collides with the Codex
         * ctor's `(oauthTokenProvider, model, codexAccountId)` overload.
         */
        fun oauthOpenAICompat(
            oauthTokenProvider: suspend () -> String,
            model: LLMModel,
            basePath: String,
        ): OpenAIProvider = OpenAIProvider(
            apiKey = null,
            oauthTokenProvider = oauthTokenProvider,
            model = model,
            basePath = basePath,
            forceChatCompletions = true,
        )
    }

    internal val isOAuth: Boolean get() = oauthTokenProvider != null

    // MARK: - Image passthrough [T-android-model-use-image-passthrough GH#62]

    /**
     * Arbitrary extra fields merged into the /images/generations JSON body, so
     * `minis-model-use` can pass provider-specific params our fixed schema never
     * modeled (e.g. Volcengine Seedream's `image` for image-to-image,
     * `watermark`, `tools`). User keys WIN over our defaults (response_format)
     * but never replace the resolved `model`. Empty = no passthrough. Set
     * per-call by ModelUseOffloadHandler on a freshly-built provider; never
     * persisted. Values are raw JSON (String/Number/Boolean/JSONObject/JSONArray).
     */
    var imageExtraBody: Map<String, Any?> = emptyMap()

    /**
     * Extra HTTP headers merged into the /images/generations request (added, not
     * replacing the ctor extraHeaders). Per-call, never persisted.
     */
    var imageExtraHeaders: Map<String, String> = emptyMap()

    /**
     * Optional endpoint-path override for the image request (e.g. a non-standard
     * `/api/v3/images/generations`). When set, replaces the hardcoded
     * `/images/generations` path (base URL + this verbatim). null = default path.
     */
    var imagePathOverride: String? = null

    // MARK: - Chat passthrough [T-android-model-use-passthrough-mode / GH#72]

    /**
     * Arbitrary extra fields merged into the chat/completions AND responses
     * request bodies, mirroring [imageExtraBody] on the image path. Populated
     * per-call by ModelUseOffloadHandler from the input JSON's explicit
     * `extra_body` / `passthrough.body` envelope (never from implicit top-level
     * keys — the chat schema owns its top level). User keys WIN over our
     * defaults (e.g. `plugins`, `web_search_options`, provider-specific knobs)
     * but `model` is force-restored after the merge. Empty = no passthrough.
     * Mirrors iOS OpenAIProvider.chatExtraBody.
     */
    var chatExtraBody: Map<String, Any?> = emptyMap()

    /**
     * Extra HTTP headers merged into chat/completions and /responses requests,
     * applied AFTER the default set → same-name REPLACE semantics over every
     * default (including Authorization/Content-Type). Per-call, never persisted.
     * Mirrors iOS OpenAIProvider.extraHeaders (promoted to all endpoints).
     */
    var chatExtraHeaders: Map<String, String> = emptyMap()

    /**
     * Absolute-path endpoint override. When set (must start with "/"), it
     * replaces the ENTIRE URL path after scheme+host — unlike [imagePathOverride],
     * which is joined after `basePath` and therefore can never escape a base-URL
     * prefix like `/compatible-mode/v1` (proven by iOS device baseline p03).
     * Applies to chat/completions, responses, and images/generations builders.
     * Never applies to Codex OAuth (hardcoded backend). Per-call, never
     * persisted. Mirrors iOS OpenAIProvider.absoluteEndpointOverride.
     */
    var absoluteEndpointOverride: String? = null



    // MARK: - Azure helpers [T-android-azure-openai]

    /**
     * Set the API-key auth header on a request builder. Azure uses the `api-key`
     * header; every other OpenAI-compatible endpoint uses `Authorization:
     * Bearer`. Centralized so the Azure branch can't accidentally set the wrong
     * one. Mirrors iOS OpenAIProvider.applyKeyAuth.
     */
    internal fun Request.Builder.applyKeyAuth(token: String): Request.Builder =
        // [T-empty-key-compat-endpoints] A keyless third-party endpoint
        // (ollama, LM Studio, LiteLLM, private relays) is a supported
        // configuration. Send NO auth header rather than a malformed
        // `Authorization: Bearer ` / empty `api-key:` — strict gateways
        // reject the empty form, an absent header is universally fine.
        if (token.isEmpty()) this
        else if (isAzure) header("api-key", token)
        else header("Authorization", "Bearer $token")


    /**
     * Whether this provider uses Chat Completions API (vs Responses API).
     * Responses API is used when OAuth (Codex) OR when the user explicitly
     * flipped the per-instance `useResponsesAPI` switch.
     */
    // Astra function calling requires Responses. Namespaced relay ids retain
    // their configured transport; the exact OpenAI id uses Responses by default.
    internal val usesChatCompletionsAPI: Boolean get() = forceChatCompletions ||
        (!isOAuth && !useResponsesAPI && (isAzure || model.id != LLMModel.gpt6Astra.id))

    /**
     * [T-android-tool-splits-reply-fix] Chat Completions streams ONE
     * monolithic `content` string per assistant response — qwen endpoints
     * flush trailing content chunks AFTER tool_calls deltas (chunking
     * artifact), and those must merge back into the single pre-tool text
     * block instead of becoming a post-tool block (which split sentences
     * mid-word in the chat UI). The Responses API streams genuinely ordered
     * output items, so it keeps chronological reconstruction.
     */
    override val streamTextIsMonolithic: Boolean get() = usesChatCompletionsAPI

    /**
     * [T-codex-gpt-image2-oauth-android] gpt-image-2 is a special image-
     * generation model driven through the Codex OAuth backend's built-in
     * image_generation tool (wire model gpt-5.5, tools=[{type:image_generation}]).
     * Only meaningful on the Codex OAuth path; everything else (the GPT-5.x
     * Codex models and their existing OAuth flow) is untouched by this gate.
     */
    internal val isCodexImageModel: Boolean get() = isOAuth && model.id in CODEX_IMAGE_MODELS


    // T-android-openai-codex-timeout: bump readTimeout 180s → 600s to
    // match iOS. T171 had cut it to 180s on the theory that GPT-5.x
    // thinking warm-up tops out around 60-90s, but the Codex Responses
    // OAuth path on gpt-5.5 with a real-world agent body (440KB, 20
    // messages, 8 tools) routinely sits silent on the SSE stream for
    // 2:50-3:10 between the reasoning `response.output_item.added`
    // event and the burst of text deltas after the reasoning step
    // completes — server-side it's still working, no keep-alive bytes
    // arrive in between, and OkHttp's idle-data-read counter trips.
    // The 180s cap turned that normal reasoning silence into a hard
    // SocketTimeoutException (observed in 0.10-preview, log file
    // minis-2026-05-27.log around 13:28 — 3:00 of silence then trip).
    // Going back to 600s leaves room for the longest realistic
    // reasoning bursts; the cancel-race concern T171 hedged against
    // (OkHttp call.cancel() racing a thread inside execute()) is
    // covered by the outer coroutine cancellation chain — Job.cancel
    // propagates down through the agent loop and the socket gets
    // closed via Call.cancel() from the coroutine's invokeOnCancellation,
    // so a stuck OAuth read never lingers past the agent turn.
    //
    // T-android-openai-codex-timeout: also attach an OkHttp EventListener
    // so future timeout reports show WHICH leg of the network path
    // stalled — DNS, proxy connect, TLS handshake, idle-after-headers,
    // or mid-stream silence. Previous OAuth-streaming logs only printed
    // request/response envelopes; when a SocketTimeoutException fired
    // we had no way to tell whether the upstream proxy went away
    // (idle-close after 3min, common with clash/v2ray), TLS renegotiated,
    // or the server itself stopped emitting bytes. Each milestone goes
    // through AppLogger.info at the OkHttpEvents tag with the call's
    // identity hash so concurrent streams can be disambiguated.
    internal val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(600, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        // [T-android-stale-conn-retry-hang] Shared pool so NetworkMonitor's
        // network-transition eviction reaches THIS client's connections —
        // a per-client pool was never evicted, and a dead h2 tunnel through
        // a local proxy got reused on every retry (silent infinite hang).
        .connectionPool(com.openminis.app.network.NetworkMonitor.sharedLLMConnectionPool)
        .eventListenerFactory { OkHttpNetTraceListener() }
        .let { com.openminis.app.provider.ProviderTransportPolicy.configureClient(it, basePath) }
        .build()

    /** Detect OpenRouter base URL. */
    internal val isOpenRouter: Boolean = basePath.contains("openrouter.ai")

    /**
     * [OpenMinis#191] OpenRouter does NOT enable Anthropic prompt caching
     * automatically — unlike the OpenAI / Grok / Moonshot / Groq models it
     * hosts, which cache with no opt-in. Claude requests must carry an explicit
     * `cache_control` breakpoint or nothing is cached at all, which is why the
     * reporter measured `cache_read_input_tokens` / `cache_write_tokens` pinned
     * at 0 across every turn and a 3-6x cost overrun.
     *
     * Matched on the `anthropic/` model-id prefix, OpenRouter's namespace for
     * the Claude family (`anthropic/claude-sonnet-4.5`, `anthropic/claude-opus-4.1`,
     * …). Scoped to OpenRouter AND that prefix so every other model on the
     * gateway keeps a byte-identical request body.
     *
     * Note the gate is the HOST-matched [isOpenRouter], never a compat flag:
     * on iOS the equivalent `useOpenRouterCompat` only selects the legacy
     * `max_tokens` / no-`stream_options` body shape and Mistral sets it too, so
     * keying on it would have leaked the field into Mistral requests. Android's
     * [isOpenRouter] is already host-matched, the same way [isDashScope] is.
     *
     * Carries the same caveat as [isMistral]: a relay or vanity domain without
     * `openrouter.ai` in its URL is not recognised, which fails safe — the
     * request simply goes out unchanged, i.e. today's behaviour.
     */
    internal val needsOpenRouterAnthropicCacheControl: Boolean
        get() = isOpenRouter && model.id.lowercase().startsWith("anthropic/")

    /** Detect DashScope (Alibaba Qwen) base URL. */
    internal val isDashScope: Boolean = basePath.contains("dashscope")

    /**
     * [T-android-mistral-reasoning-422] (GH OpenMinis#87, iOS 29065ca0)
     * Detect Mistral's OpenAI-compatible endpoint.
     *
     * Mistral's AssistantMessage is a CLOSED schema
     * (`additionalProperties: false`; only role/content/tool_calls/prefix), so
     * `reasoning_content` on a prior assistant turn is rejected outright with
     * HTTP 422 `extra_forbidden`. Their native reasoning representation is a
     * different, Mistral-signed mechanism (content ThinkChunks), not this
     * field. Note the REQUEST schema has no additionalProperties:false, which
     * is why only multi-turn history carrying reasoning_content ever 422'd
     * while spec-external top-level params went through fine.
     *
     * Case-insensitive to match iOS (LLMProviderFactory lowercases before the
     * same `contains("mistral.ai")` test) — hosts are case-insensitive, so a
     * user typing `API.Mistral.AI` must still be recognised.
     *
     * Known limits, both inherited from iOS's identical predicate: a relay that
     * proxies Mistral models under its own hostname is not detected (still
     * 422s), and a URL that merely mentions mistral.ai in a query string would
     * over-suppress (harmless — the field is optional for everyone else).
     */
    internal val isMistral: Boolean = basePath.lowercase().contains("mistral.ai")

    /**
     * [OpenMinis#163] Talking to xAI's own API (api.x.ai), as opposed to a relay
     * that merely serves grok-named models. Mirrors iOS OpenAIProvider.isXAI.
     *
     * Scopes the "catalog declares no effort tiers → omit reasoning_effort" skip
     * to first-party xAI. The bundled catalog marks 2090 entries across many
     * vendors with the same empty-tier shape (relay-hosted Claude, GPT-5, Qwen,
     * and grok itself behind poe / fastrouter / anyapi); while omitting the
     * field is arguably more correct for some of those too, none of those routes
     * has been verified, so the skip stays where the 400 was actually observed.
     *
     * URL matching alone is sufficient: ProviderFactory always populates a base
     * for xAI, defaulting to https://api.x.ai/v1 when the user set no override.
     */
    internal val isXAI: Boolean = basePath.lowercase().let {
        it.contains("api.x.ai") || it.contains("//x.ai")
    }

    /**
     * [T-unified-reasoning-effort] Whether this endpoint applies OpenAI's
     * `reasoning_effort` (Chat) / `reasoning.effort` (Responses) uniformly to
     * EVERY model it hosts — including third-party families (GLM / Kimi /
     * DeepSeek / MiniMax) that, at their vendor-native endpoint, would instead
     * use a `thinking:{}` object or self-reason with no toggle.
     *
     * Three known such gateways (mirrors iOS OpenAIProvider.usesUnifiedReasoningEffort):
     *   • Volcengine Ark (`ark.` / `volces` in the base URL) — re-exposes
     *     doubao/deepseek/glm/kimi through a single OpenAI-compatible surface
     *     where thinking is controlled ONLY by `reasoning_effort` (min tier
     *     `minimal`); the vendor-native `thinking:{}` shape is not honored.
     *   • Azure OpenAI ([isAzure]) — reasoning is `reasoning_effort` for every
     *     model surfaced through the deployment.
     *   • Venice.ai (`api.venice.ai`) — [OpenMinis#86] resells deepseek / claude /
     *     aion behind one OpenAI-compatible surface. Its ChatCompletionRequest
     *     schema is `additionalProperties: false`, so an unknown root key is
     *     rejected at validation time — BEFORE model dispatch — with
     *     `400 Unrecognized key(s) in object: 'thinking'`. That is why every
     *     model failed and why turning thinking OFF did not help: the
     *     `{"type":"disabled"}` branch still sends the key. Venice natively
     *     accepts root `reasoning_effort`, a superset of the tiers the generic
     *     path emits, so no value mapping is needed.
     *
     * Gated tightly so official direct endpoints (DeepSeek/GLM/Kimi native,
     * which DO want their own thinking shape) are never mis-routed. Caveat (same
     * class as [isMistral]): a relay or vanity domain that does not carry these
     * hosts in its URL is still exposed.
     */
    internal val usesUnifiedReasoningEffort: Boolean =
        isAzure || basePath.lowercase().let {
            it.contains("volces") || it.contains("ark.") || it.contains("api.venice.ai")
        }


    /**
     * Non-streaming entry point. Some providers (e.g. GPT-5.x via certain
     * gateways, Codex Responses backend) reject `stream=false` outright with
     * `[400] Stream must be set to true`. To keep this method usable across
     * all providers we always issue a streaming request internally and
     * concatenate the deltas back into a single [LLMResponse]. Callers that
     * actually want incremental delivery should use [streamMessage] instead.
     */
    override suspend fun sendMessageClamped(
        messages: List<LLMMessage>,
        systemPrompt: String?,
        maxTokens: Int,
        temperature: Double?,
        imageParts: List<LLMMessage.ImagePart>,
        tools: List<AgentToolDefinition>,
        thinkingLevel: ThinkingLevel,
    ): LLMResponse = withContext(Dispatchers.IO) {
        val textBuf = StringBuilder()
        var stopReason: String? = null
        var usage: LLMUsage? = null
        // [T-codex-gpt-image2-oauth-android] Collect model-generated media
        // (gpt-image-2 images) so non-streaming callers — notably
        // minis-model-use (ModelUseOffloadHandler) — get them on
        // LLMResponse.mediaAttachments and can write the image to --output.
        val media = mutableListOf<LLMMediaAttachment>()
        streamMessage(
            messages = messages,
            systemPrompt = systemPrompt,
            maxTokens = maxTokens,
            temperature = temperature,
            imageParts = imageParts,
            tools = tools,
            thinkingLevel = thinkingLevel,
        ).collect { chunk ->
            when (chunk) {
                is LLMStreamChunk.Text -> textBuf.append(chunk.text)
                is LLMStreamChunk.Usage -> usage = chunk.usage
                is LLMStreamChunk.Finished -> stopReason = chunk.stopReason
                is LLMStreamChunk.MediaAttachment -> media.add(chunk.attachment)
                else -> Unit
            }
        }
        LLMResponse(textBuf.toString(), stopReason, usage, media)
    }

    override fun streamMessageClamped(
        messages: List<LLMMessage>,
        systemPrompt: String?,
        maxTokens: Int,
        temperature: Double?,
        imageParts: List<LLMMessage.ImagePart>,
        tools: List<AgentToolDefinition>,
        thinkingLevel: ThinkingLevel,
    ): Flow<LLMStreamChunk> = rawStreamMessage(
        messages, systemPrompt, maxTokens, temperature, imageParts, tools, thinkingLevel,
    ).failOnSilentEmptyCompletion(name)


    // MARK: - Raw Passthrough [T-android-model-use-passthrough-mode]

    /**
     * Result of a raw passthrough call: unparsed response bytes + HTTP status +
     * the fully-assembled URL that was actually hit (surfaced to the caller per
     * the passthrough-mode contract). Mirrors iOS RawPassthroughResult.
     */
    class RawPassthroughResult(
        val data: ByteArray,
        val status: Int,
        val contentType: String?,
        val url: String,
    )

























    /**
     * Responses-API tool shape — flat {type, name, description, parameters},
     * NOT the Chat Completions wrapper {type, function:{...}}. Mirrors iOS
     * convertToolsResponsesAPI (OpenAIAgentProvider.swift:977).
     */
    internal fun AgentToolDefinition.toResponsesAPIJson(): JSONObject {
        val props = JSONObject()
        for ((key, param) in parameters) {
            props.put(key, param.toJson())
        }
        val params = JSONObject().apply {
            put("type", "object")
            put("properties", props)
            if (required.isNotEmpty()) put("required", JSONArray(required))
        }
        return JSONObject().apply {
            put("type", "function")
            put("name", apiName)
            put("description", description)
            put("parameters", params)
        }
    }



}

/**
 * [T-android-ttfb-upload-split / #188] Per-call state the streaming TTFB
 * watchdog shares with [OkHttpNetTraceListener]. Attached to the streaming
 * request as an OkHttp request tag; the listener fills it in from its
 * event callbacks (which run on OkHttp's I/O thread during `execute()`),
 * and the watchdog coroutine reads it.
 *
 * Why: the watchdog must NOT count request-body UPLOAD time against the
 * 30s time-to-first-byte budget (a 1.3MB body over a slow proxy took 24-27s,
 * leaving almost nothing for the server, so a healthy server looked like a
 * dead connection — #188). The listener signals [uploadDoneAtNanos] on
 * `requestBodyEnd`; only then does the real TTFB clock start. [connection]
 * is captured so the watchdog can evict THIS ONE physical connection on
 * timeout (never the whole pool — a sibling session's healthy connection
 * must survive).
 */
internal class CallWatchState {
    /** Set by requestBodyEnd — the moment upload finished (monotonic nanos). */
    val uploadDoneAtNanos = java.util.concurrent.atomic.AtomicLong(0L)
    /** Physical connection serving this call, captured at connectionAcquired. */
    val connection = java.util.concurrent.atomic.AtomicReference<okhttp3.Connection?>(null)
}

/**
 * [T-android-openai-codex-timeout]
 * Network-leg trace listener for OpenAIProvider's OkHttpClient. Logs every
 * OkHttp call lifecycle event with timestamps so a future SocketTimeout
 * report can be triaged to a specific leg:
 *
 *   - dnsStart / dnsEnd          : was the host resolvable, how long
 *   - proxySelect{Start,End}     : which proxy (or DIRECT) routed this
 *   - connectStart / -End / -Failed : TCP connect to proxy or origin
 *   - secureConnect{Start,End}   : TLS handshake duration + cipher / alpn
 *   - connectionAcquired/Released: which physical connection served the
 *                                  call — repeated calls reusing the
 *                                  same Connection identityHash mean
 *                                  the OkHttp pool is recycling, useful
 *                                  for spotting "stale-proxy-mid-stream"
 *   - requestHeaders/BodyEnd     : when the request was fully sent
 *   - responseHeadersStart/End   : time to first server byte (the TFB
 *                                  number tells us whether the proxy
 *                                  was slow vs. the origin)
 *   - responseBodyStart/End      : SSE stream lifecycle — `End` firing
 *                                  with a SocketTimeout root cause is
 *                                  the classic "mid-stream silence" case
 *   - callFailed                 : terminal — pairs the failure to the
 *                                  earliest leg that completed cleanly
 *
 * One instance per call (the factory in OpenAIProvider). Holds a
 * monotonic start timestamp so all log lines carry a relative offset
 * from callStart.
 */
internal class OkHttpNetTraceListener : EventListener() {
    internal val tag = "OkHttpNetTrace"
    internal val t0 = System.nanoTime()
    internal fun ms(): Long = (System.nanoTime() - t0) / 1_000_000L
    internal fun callTag(call: Call): String {
        val id = System.identityHashCode(call).toString(16)
        return "call#$id"
    }

    override fun callStart(call: Call) {
        com.openminis.app.logging.AppLogger.info(
            tag,
            "[${callTag(call)}] +${ms()}ms callStart url=${call.request().url}"
        )
    }

    override fun proxySelectStart(call: Call, url: HttpUrl) {
        com.openminis.app.logging.AppLogger.info(
            tag,
            "[${callTag(call)}] +${ms()}ms proxySelectStart host=${url.host}"
        )
    }

    override fun proxySelectEnd(call: Call, url: HttpUrl, proxies: List<Proxy>) {
        com.openminis.app.logging.AppLogger.info(
            tag,
            "[${callTag(call)}] +${ms()}ms proxySelectEnd host=${url.host} chain=${proxies.joinToString(",") { it.toString() }}"
        )
    }

    override fun dnsStart(call: Call, domainName: String) {
        com.openminis.app.logging.AppLogger.info(
            tag,
            "[${callTag(call)}] +${ms()}ms dnsStart host=$domainName"
        )
    }

    override fun dnsEnd(call: Call, domainName: String, inetAddressList: List<InetAddress>) {
        com.openminis.app.logging.AppLogger.info(
            tag,
            "[${callTag(call)}] +${ms()}ms dnsEnd host=$domainName resolved=${inetAddressList.size} addrs=${inetAddressList.take(3).joinToString(",") { it.hostAddress ?: "?" }}"
        )
    }

    override fun connectStart(call: Call, inetSocketAddress: InetSocketAddress, proxy: Proxy) {
        com.openminis.app.logging.AppLogger.info(
            tag,
            "[${callTag(call)}] +${ms()}ms connectStart target=$inetSocketAddress proxy=$proxy"
        )
    }

    override fun secureConnectStart(call: Call) {
        com.openminis.app.logging.AppLogger.info(
            tag,
            "[${callTag(call)}] +${ms()}ms tlsStart"
        )
    }

    override fun secureConnectEnd(call: Call, handshake: Handshake?) {
        com.openminis.app.logging.AppLogger.info(
            tag,
            "[${callTag(call)}] +${ms()}ms tlsEnd version=${handshake?.tlsVersion} cipher=${handshake?.cipherSuite}"
        )
    }

    override fun connectEnd(
        call: Call,
        inetSocketAddress: InetSocketAddress,
        proxy: Proxy,
        protocol: Protocol?,
    ) {
        com.openminis.app.logging.AppLogger.info(
            tag,
            "[${callTag(call)}] +${ms()}ms connectEnd target=$inetSocketAddress proxy=$proxy proto=$protocol"
        )
    }

    override fun connectFailed(
        call: Call,
        inetSocketAddress: InetSocketAddress,
        proxy: Proxy,
        protocol: Protocol?,
        ioe: IOException,
    ) {
        com.openminis.app.logging.AppLogger.warning(
            tag,
            "[${callTag(call)}] +${ms()}ms connectFailed target=$inetSocketAddress proxy=$proxy proto=$protocol err=${ioe.javaClass.simpleName}:${ioe.message}"
        )
    }

    override fun connectionAcquired(call: Call, connection: Connection) {
        val conn = System.identityHashCode(connection).toString(16)
        // [T-android-ttfb-upload-split / #188] Capture the physical connection so
        // the TTFB watchdog can evict THIS ONE on timeout (targeted, never the pool).
        call.request().tag(CallWatchState::class.java)?.connection?.set(connection)
        com.openminis.app.logging.AppLogger.info(
            tag,
            "[${callTag(call)}] +${ms()}ms connectionAcquired conn#$conn route=${connection.route()} proto=${connection.protocol()}"
        )
    }

    override fun connectionReleased(call: Call, connection: Connection) {
        val conn = System.identityHashCode(connection).toString(16)
        com.openminis.app.logging.AppLogger.info(
            tag,
            "[${callTag(call)}] +${ms()}ms connectionReleased conn#$conn"
        )
    }

    override fun requestHeadersStart(call: Call) {
        com.openminis.app.logging.AppLogger.info(
            tag,
            "[${callTag(call)}] +${ms()}ms requestHeadersStart"
        )
    }

    override fun requestHeadersEnd(call: Call, request: Request) {
        com.openminis.app.logging.AppLogger.info(
            tag,
            "[${callTag(call)}] +${ms()}ms requestHeadersEnd"
        )
    }

    override fun requestBodyStart(call: Call) {
        com.openminis.app.logging.AppLogger.info(
            tag,
            "[${callTag(call)}] +${ms()}ms requestBodyStart"
        )
    }

    override fun requestBodyEnd(call: Call, byteCount: Long) {
        // [T-android-ttfb-upload-split / #188] Upload finished — from here the
        // real time-to-first-byte clock starts (the watchdog polls this).
        call.request().tag(CallWatchState::class.java)?.uploadDoneAtNanos?.set(System.nanoTime())
        com.openminis.app.logging.AppLogger.info(
            tag,
            "[${callTag(call)}] +${ms()}ms requestBodyEnd bytes=$byteCount"
        )
    }

    override fun responseHeadersStart(call: Call) {
        com.openminis.app.logging.AppLogger.info(
            tag,
            "[${callTag(call)}] +${ms()}ms responseHeadersStart (server first byte)"
        )
    }

    override fun responseHeadersEnd(call: Call, response: Response) {
        com.openminis.app.logging.AppLogger.info(
            tag,
            "[${callTag(call)}] +${ms()}ms responseHeadersEnd status=${response.code} proto=${response.protocol}"
        )
    }

    override fun responseBodyStart(call: Call) {
        com.openminis.app.logging.AppLogger.info(
            tag,
            "[${callTag(call)}] +${ms()}ms responseBodyStart"
        )
    }

    override fun responseBodyEnd(call: Call, byteCount: Long) {
        com.openminis.app.logging.AppLogger.info(
            tag,
            "[${callTag(call)}] +${ms()}ms responseBodyEnd bytes=$byteCount"
        )
    }

    override fun callEnd(call: Call) {
        com.openminis.app.logging.AppLogger.info(
            tag,
            "[${callTag(call)}] +${ms()}ms callEnd"
        )
    }

    override fun callFailed(call: Call, ioe: IOException) {
        // The most diagnostic of all: pairs the failure with whatever
        // milestone WAS reached before it. Read alongside the listener's
        // earlier lines to localize the stall.
        com.openminis.app.logging.AppLogger.warning(
            tag,
            "[${callTag(call)}] +${ms()}ms callFailed err=${ioe.javaClass.simpleName}:${ioe.message}"
        )
    }

    override fun canceled(call: Call) {
        com.openminis.app.logging.AppLogger.info(
            tag,
            "[${callTag(call)}] +${ms()}ms canceled"
        )
    }
}
