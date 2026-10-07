package com.openminis.app.provider.openai

import android.util.Base64
import com.openminis.app.data.model.AgentContentPart
import com.openminis.app.data.model.AgentToolDefinition
import com.openminis.app.data.model.LLMError
import com.openminis.app.data.model.LLMMediaAttachment
import com.openminis.app.data.model.LLMMessage
import com.openminis.app.data.model.LLMModel
import com.openminis.app.data.model.LLMResponse
import com.openminis.app.data.model.LLMStreamChunk
import com.openminis.app.data.model.LLMUsage
import com.openminis.app.data.model.ThinkingLevel
import com.openminis.app.data.model.hasImageInput
import com.openminis.app.provider.LLMProvider
import com.openminis.app.provider.RequestBodyMerge
import com.openminis.app.provider.applyUserAgentOverride
import com.openminis.app.provider.openai.OpenAIProvider.Companion.CODEX_CLIENT_VERSION
import com.openminis.app.provider.openai.OpenAIProvider.Companion.CODEX_IMAGE_25_MODEL_IDS
import com.openminis.app.provider.safeOptString
import com.openminis.app.provider.thinking.ThinkingResolveContext
import com.openminis.app.provider.thinking.ThinkingRuleResolver
import java.io.BufferedReader
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONObject

/**
 * [T-android-image-endpoint-mode] Generate an image via the OpenAI Images
 * API (`POST $basePath/images/generations`). Mirrors iOS
 * OpenAIProvider.generateImage. Used only by ModelUseOffloadHandler's
 * image-output routing for API-key OpenAI-compat instances — the Codex
 * OAuth gpt-image-2 path goes through the existing isCodexImageModel branch
 * and never reaches here.
 *
 * Request body: `{ model, prompt, n, size?, quality?, response_format:
 * "b64_json" }`. Some gateways (e.g. xAI) reject `response_format` — on a
 * 400 mentioning it, we retry once without the field (iOS parity).
 *
 * On a non-2xx response throws [mapHttpError]'s result. A route-missing
 * error (404 / "got chat completions response") surfaces as
 * LLMError.ProviderError whose message the handler matches with
 * looksLikeEndpointMissing() to drive the auto-mode fallback.
 */
suspend fun OpenAIProvider.generateImage(
    prompt: String,
    n: Int = 1,
    size: String? = null,
    quality: String? = null,
): LLMResponse = withContext(Dispatchers.IO) {
    if (isCodexImageModel) {
        return@withContext sendMessage(
            messages = listOf(LLMMessage(role = LLMMessage.Role.USER, content = prompt)),
            systemPrompt = null,
            maxTokens = 1024,
            temperature = null,
        )
    }
    val token = getToken()
    // [T-android-model-use-image-passthrough GH#62] Honor an explicit
    // endpoint-path override (non-standard providers); default otherwise.
    val imagePath = imagePathOverride?.takeIf { it.isNotBlank() } ?: "/images/generations"
    // [T-android-model-use-passthrough-mode] The absolute-path override wins
    // over the legacy relative imagePathOverride (which is joined after
    // basePath and can't escape base prefixes — iOS baseline p03).
    // [T-android-azure-openai] Azure image generation routes via the
    // deployments path + api-key header; falls back to basePath otherwise.
    val abs = absoluteEndpointOverride
    val url = when {
        abs != null && abs.startsWith("/") -> hostRootURL(abs) ?: "$basePath$imagePath"
        isAzure -> azureUrl(imagePath) ?: "$basePath$imagePath"
        else -> "$basePath$imagePath"
    }

    // [T-android-model-use-image-passthrough GH#62] When the user explicitly
    // supplies response_format, respect it and skip the b64_json auto-probe.
    val userSetResponseFormat = imageExtraBody.containsKey("response_format")
    var triedWithoutFormat = userSetResponseFormat
    while (true) {
        val body = JSONObject()
            .put("model", model.id)
            .put("prompt", prompt)
            .put("n", n)
        if (size != null) body.put("size", size)
        if (quality != null) body.put("quality", quality)
        if (!triedWithoutFormat) body.put("response_format", "b64_json")
        // [T-android-model-use-image-passthrough GH#62] Merge user-supplied
        // passthrough fields. User keys WIN over our defaults (they can
        // override prompt/size or add Seedream's `image`/`watermark`), but
        // `model` is force-kept to the resolved id afterward so a stray
        // override can't misroute the request.
        // [T-eta-provider-passthrough] Recursive merge (Eta RequestBodyMerge
        // rules) so nested overrides keep their siblings.
        RequestBodyMerge.mergeInto(body, imageExtraBody)
        body.put("model", model.id)

        val bodyStr = body.toString()
        val jsonMediaType = "application/json".toMediaType()
        val bodyBytes = bodyStr.toByteArray(Charsets.UTF_8)
        val requestBody = object : okhttp3.RequestBody() {
            override fun contentType() = jsonMediaType
            override fun contentLength() = bodyBytes.size.toLong()
            override fun writeTo(sink: okio.BufferedSink) { sink.write(bodyBytes) }
        }
        val builder = Request.Builder()
            .url(url)
            .post(requestBody)
            .applyKeyAuth(token)
            .header("Content-Type", "application/json")
        for ((key, value) in extraHeaders) {
            builder.header(key, value)
        }
        // [T-android-model-use-image-passthrough GH#62] Per-call passthrough
        // headers, merged after the ctor extraHeaders so they can add/override.
        for ((key, value) in imageExtraHeaders) {
            builder.header(key, value)
        }
        builder.applyUserAgentOverride(customUserAgent)
        val request = builder.build()

        com.openminis.app.logging.AppLogger.info(
            "OpenAIProvider",
            "[ModelUseRoute] → images/generations url=$url model=${model.id} n=$n " +
                "size=$size quality=$quality respFormat=${if (triedWithoutFormat) "<none>" else "b64_json"}",
        )

        val response = client.newCall(request).execute()
        val statusCode = response.code
        val responseBody = response.body?.string() ?: ""
        response.close()

        // Some providers (xAI) don't support b64_json — retry without it once.
        if (!triedWithoutFormat && statusCode == 400 &&
            (responseBody.lowercase().contains("response_format") || responseBody.contains("b64_json"))
        ) {
            com.openminis.app.logging.AppLogger.info(
                "OpenAIProvider",
                "[ModelUseRoute] images/generations rejected b64_json — retrying without response_format",
            )
            triedWithoutFormat = true
            continue
        }

        if (statusCode !in 200..299) {
            com.openminis.app.logging.AppLogger.warning(
                "OpenAIProvider",
                "[ModelUseRoute] images/generations HTTP $statusCode body=${responseBody.take(300)}",
            )
            throw mapHttpError(statusCode, responseBody)
        }

        val json = try {
            JSONObject(responseBody)
        } catch (e: Exception) {
            throw LLMError.ProviderError("images/generations returned non-JSON body: ${e.message}")
        }
        return@withContext parseImageGenerationsResult(json)
    }
    @Suppress("UNREACHABLE_CODE")
    throw LLMError.ProviderError("images/generations: unreachable")
}

/**
 * [T-android-image-edit-endpoint] Call `/images/edits` for image-to-image
 * (reference-image) generation. Android previously had no such endpoint, so
 * minis-model-use returned `image_edit_not_supported` for every
 * input-image + pure-image-generator call — the gap this closes. Mirrors
 * iOS `OpenAIProvider.editImage`.
 *
 * Request: multipart/form-data with `image` (file), `prompt`, `model`, `n`,
 * plus optional `size` / `quality`.
 * Response: identical shape to `/images/generations`
 * (`{ data: [{ b64_json?, url? }] }`), so [parseImageGenerationsResult] is
 * reused verbatim.
 *
 * Multi-image: the first attachment goes in as `image`, any extras as
 * `image[]` — same field naming as iOS. Providers that only accept a single
 * reference image reject the extras themselves; nothing is silently dropped
 * on our side.
 */
suspend fun OpenAIProvider.editImage(
    prompt: String,
    images: List<LLMMessage.ImagePart>,
    n: Int = 1,
    size: String? = null,
    quality: String? = null,
): LLMResponse = withContext(Dispatchers.IO) {
    if (images.isEmpty()) {
        throw LLMError.ProviderError("images/edits requires at least one input image")
    }
    val token = getToken()
    // Same override precedence as generateImage: explicit path override →
    // Azure deployments path → basePath. Only the default differs.
    val imagePath = imagePathOverride?.takeIf { it.isNotBlank() } ?: "/images/edits"
    val abs = absoluteEndpointOverride
    val url = when {
        abs != null && abs.startsWith("/") -> hostRootURL(abs) ?: "$basePath$imagePath"
        isAzure -> azureUrl(imagePath) ?: "$basePath$imagePath"
        else -> "$basePath$imagePath"
    }

    // b64_json auto-probe, mirroring generateImage: some providers reject
    // response_format on the edits route, so retry once without it.
    val userSetResponseFormat = imageExtraBody.containsKey("response_format")
    var triedWithoutFormat = userSetResponseFormat
    while (true) {
        val multipart = MultipartBody.Builder().setType(MultipartBody.FORM)
        multipart.addFormDataPart("model", model.id)
        multipart.addFormDataPart("prompt", prompt)
        multipart.addFormDataPart("n", n.toString())
        if (size != null) multipart.addFormDataPart("size", size)
        if (quality != null) multipart.addFormDataPart("quality", quality)
        if (!triedWithoutFormat) multipart.addFormDataPart("response_format", "b64_json")
        // Passthrough body fields arrive as JSON scalars; multipart carries
        // text only, so stringify. `model` is re-pinned below so a stray
        // override can't misroute the request (same rule as generateImage).
        for ((k, v) in imageExtraBody) {
            if (k == "model") continue
            multipart.addFormDataPart(k, v?.toString() ?: "")
        }

        for ((idx, img) in images.withIndex()) {
            val ext = img.mimeType.substringAfterLast('/', "").ifEmpty { "png" }
            val fieldName = if (idx == 0) "image" else "image[]"
            multipart.addFormDataPart(
                fieldName,
                "image$idx.$ext",
                img.data.toRequestBody(img.mimeType.toMediaType()),
            )
        }

        val builder = Request.Builder()
            .url(url)
            .post(multipart.build())
            .applyKeyAuth(token)
        for ((key, value) in extraHeaders) {
            builder.header(key, value)
        }
        for ((key, value) in imageExtraHeaders) {
            builder.header(key, value)
        }
        builder.applyUserAgentOverride(customUserAgent)
        val request = builder.build()

        com.openminis.app.logging.AppLogger.info(
            "OpenAIProvider",
            "[ModelUseRoute] → images/edits url=$url model=${model.id} n=$n " +
                "size=$size quality=$quality images=${images.size} " +
                "respFormat=${if (triedWithoutFormat) "<none>" else "b64_json"}",
        )

        val response = client.newCall(request).execute()
        val statusCode = response.code
        val responseBody = response.body?.string() ?: ""
        response.close()

        if (!triedWithoutFormat && statusCode == 400 &&
            (responseBody.lowercase().contains("response_format") || responseBody.contains("b64_json"))
        ) {
            com.openminis.app.logging.AppLogger.info(
                "OpenAIProvider",
                "[ModelUseRoute] images/edits rejected b64_json — retrying without response_format",
            )
            triedWithoutFormat = true
            continue
        }

        if (statusCode !in 200..299) {
            com.openminis.app.logging.AppLogger.warning(
                "OpenAIProvider",
                "[ModelUseRoute] images/edits HTTP $statusCode body=${responseBody.take(300)}",
            )
            throw mapHttpError(statusCode, responseBody)
        }

        val json = try {
            JSONObject(responseBody)
        } catch (e: Exception) {
            throw LLMError.ProviderError("images/edits returned non-JSON body: ${e.message}")
        }
        return@withContext parseImageGenerationsResult(json)
    }
    @Suppress("UNREACHABLE_CODE")
    throw LLMError.ProviderError("images/edits: unreachable")
}

/**
 * Parse the `/images/generations` response into an [LLMResponse] carrying
 * the decoded image bytes as [LLMMediaAttachment]s. Supports `b64_json`
 * (inline) and `url` (downloaded) item shapes. Mirrors iOS
 * parseImageGenerationsResult. When the body has no `data` array but DOES
 * carry `choices`, a proxy silently rerouted us to chat completions — throw
 * a route-missing error so auto-mode falls back instead of caching the
 * wrong endpoint.
 */
internal fun OpenAIProvider.parseImageGenerationsResult(json: JSONObject): LLMResponse {
    val dataArray = json.optJSONArray("data")
    if (dataArray == null) {
        if (json.has("choices")) {
            throw LLMError.ProviderError(
                "[404] /images/generations not supported (got chat completions response)",
            )
        }
        return LLMResponse("", "end_turn", null, emptyList())
    }

    val attachments = mutableListOf<LLMMediaAttachment>()
    val revisedPrompts = mutableListOf<String>()
    for (i in 0 until dataArray.length()) {
        val item = dataArray.optJSONObject(i) ?: continue
        val hintMime = item.safeOptString("mime_type", "").ifEmpty { null } // xAI extension
        val b64 = item.safeOptString("b64_json", "")
        if (b64.isNotEmpty()) {
            val bytes = try {
                Base64.decode(b64, Base64.DEFAULT)
            } catch (e: IllegalArgumentException) {
                com.openminis.app.logging.AppLogger.warning(
                    "OpenAIProvider",
                    "[ModelUseRoute] images/generations b64 decode failed: ${e.message}",
                )
                continue
            }
            val mime = hintMime ?: detectImageMime(bytes)
            attachments.add(LLMMediaAttachment(LLMMediaAttachment.MediaType.IMAGE, mime, bytes))
        } else {
            val urlStr = item.safeOptString("url", "")
            if (urlStr.isNotEmpty()) {
                try {
                    val safeUrl = com.openminis.app.provider.ProviderTransportPolicy
                        .requireAllowedSecondaryUrl(basePath, urlStr)
                    val dlReq = Request.Builder().url(safeUrl).get().build()
                    val dlResp = client.newCall(dlReq).execute()
                    val dlBytes = dlResp.body?.bytes()
                    val ctMime = dlResp.header("Content-Type")
                    dlResp.close()
                    if (dlBytes != null && dlBytes.isNotEmpty()) {
                        val mime = hintMime ?: ctMime ?: detectImageMime(dlBytes)
                        attachments.add(LLMMediaAttachment(LLMMediaAttachment.MediaType.IMAGE, mime, dlBytes))
                    }
                } catch (e: Exception) {
                    com.openminis.app.logging.AppLogger.warning(
                        "OpenAIProvider",
                        "[ModelUseRoute] failed to download image from $urlStr: ${e.message}",
                    )
                }
            }
        }
        val revised = item.safeOptString("revised_prompt", "")
        if (revised.isNotEmpty()) revisedPrompts.add(revised)
    }

    val text = revisedPrompts.joinToString("\n")
    return LLMResponse(text, "end_turn", null, attachments)
}

internal fun OpenAIProvider.buildRequestBody(
    messages: List<LLMMessage>,
    systemPrompt: String?,
    maxTokens: Int,
    stream: Boolean,
    temperature: Double?,
    imageParts: List<LLMMessage.ImagePart>,
    tools: List<AgentToolDefinition> = emptyList(),
    thinkingLevel: ThinkingLevel = ThinkingLevel.OFF,
): JSONObject {
    // T264: cross-provider image sanitization, mirrors iOS
    // OpenAIAgentProvider.swift:744-768 / 900-918. When the target model
    // doesn't declare "image" in inputModalities (e.g. DeepSeek V4 after
    // user sent image to GPT-5.5 then switched provider), serialize a
    // text placeholder instead of an image_url block — otherwise the
    // server returns "400 unknown variant `image_url`". Decided once
    // here so the structured-contentParts loop and the legacy
    // imageParts loop below stay consistent.
    val supportsImages = model.hasImageInput
    val body = JSONObject()
    body.put("model", model.id)
    if (isOpenRouter) {
        body.put("max_tokens", maxTokens)
    } else {
        body.put("max_completion_tokens", maxTokens)
    }
    body.put("stream", stream)

    // xAI's extension is emitted only for an xAI-capable instance; other
    // OpenAI-compatible providers keep their request shape unchanged.
    resolvedServiceTier()?.let { body.put("service_tier", it) }

    if (temperature != null && supportsTemperatureOverride) {
        body.put("temperature", temperature)
    }

    if (stream && !isOpenRouter) {
        body.put("stream_options", JSONObject().put("include_usage", true))
    }

    // Provider-specific thinking params. We always call this — some
    // models (e.g. DeepSeek V4) reason by default and need an explicit
    // `disabled` signal when the user toggles thinking off.
    //
    // [T-android-mistral-reasoning-422] …EXCEPT on Mistral, which rejects
    // the thinking request parameters outright with
    // `422 extra_forbidden body.reasoning`. Mirrors iOS
    // OpenAIAgentProvider.swift's `if !provider.isMistral` gate around this
    // same call (4592ca9b). Until now [isMistral] only suppressed the
    // message-level echo ([forbidReasoningField], 0839f019 / GH
    // OpenMinis#87) — the request-parameter half of that fix was never
    // ported, so an enabled thinking level still put `reasoning_effort` on
    // the wire to api.mistral.ai.
    if (!isMistral) {
        injectThinkingParams(body, thinkingLevel, maxTokens)
    }

    // [OpenMinis#191] Opt this request into Anthropic prompt caching.
    // OpenRouter passes the field through to Anthropic but never injects it
    // for us, so without it Claude requests cache nothing at all.
    //
    // Top-level "automatic" form: the breakpoint advances to the last
    // cacheable block on its own as the conversation grows, which is what an
    // agent loop wants — the per-block form would need us to hand-manage a
    // 4-breakpoint budget across a mutating history.
    //
    // Gated on host + `anthropic/` prefix, so no other model's body changes.
    if (needsOpenRouterAnthropicCacheControl) {
        body.put("cache_control", JSONObject().put("type", "ephemeral"))
    }

    // Tools
    if (tools.isNotEmpty()) {
        val toolsArray = JSONArray()
        for (tool in tools) {
            toolsArray.put(tool.toOpenAIJson())
        }
        body.put("tools", toolsArray)
        body.put("tool_choice", "auto")
    }

    val messagesArray = JSONArray()
    if (systemPrompt != null) {
        messagesArray.put(JSONObject().apply {
            put("role", "system")
            put("content", systemPrompt)
        })
    }

    // Mirror iOS OpenAIAgentProvider.flattenChatCompletionsMessages —
    // echo reasoning_content on prior assistant turns when:
    //   - user requested thinking this turn, OR the model always reasons (forced); AND
    //   - the model isn't explicitly known to reject reasoning.
    // Prevents 400s from Kimi / DeepSeek / GLM / QwQ that reject
    // multi-turn history missing reasoning_content once thinking is on.
    val modelAlwaysReasons = model.supportsReasoning == true
    val modelMayReason = model.supportsReasoning ?: true
    // [T-android-mistral-reasoning-422] Mistral forbids reasoning_content on
    // assistant messages entirely (closed schema → HTTP 422
    // extra_forbidden), so suppress BOTH the captured echo and the ""
    // placeholder for that endpoint. This cannot be driven by capability
    // metadata: MiMo/DeepSeek require the field's PRESENCE on multi-turn
    // history while Mistral forbids it, and neither advertises
    // supportsReasoning via /v1/models — opposite requirements on the same
    // generic openAI provider path. Hence a spec-driven vendor flag.
    val forbidReasoningField = isMistral
    val includeReasoning =
        (thinkingLevel.isEnabled || modelAlwaysReasons) && modelMayReason && !forbidReasoningField
    val echoReasoning = includeReasoning
    // T-mimo-reasoning-echo-34671: Mimo V2.5 returns 400 Param Incorrect on
    // multi-turn tool-call history when any prior assistant turn (especially
    // a tool_calls-bearing one) omits `reasoning_content`. Mimo's docs say
    // the field MUST be present (empty string OK) whenever thinking is on
    // and a tool call is in history. We drop the previous interleaved-only
    // gate and always emit reasoning_content (possibly "") whenever the
    // echo gate (includeReasoning) is true. OpenAI o-series ignores
    // unknown message-level `reasoning_content` so this stays harmless
    // there; non-reasoning models gate this off via includeReasoning=false.
    val placeholderAllowed = includeReasoning

    val lastUserIndex = messages.indexOfLast { it.role == LLMMessage.Role.USER }
    for ((index, msg) in messages.withIndex()) {
        if (msg.contentParts.isNotEmpty()) {
            // Structured content parts
            when {
                // Assistant with tool_use → emit assistant message with tool_calls
                msg.role == LLMMessage.Role.ASSISTANT -> {
                    val obj = JSONObject()
                    obj.put("role", "assistant")
                    if (echoReasoning) {
                        val rc = msg.reasoningContent
                        if (thinkingLevel.isEnabled && model.id.contains("deepseek", ignoreCase = true) &&
                            rc == null && msg.contentParts.any { it is AgentContentPart.ToolUse }) {
                            throw LLMError.ProviderError(
                                "Incomplete tool history: DeepSeek reasoning is missing. Restore a complete turn before retrying.",
                            )
                        }
                        if (rc != null) {
                            // Round-trip exactly what the server emitted,
                            // including empty strings. DeepSeek V4 emits
                            // `reasoning_content: ""` on non-thinking turns
                            // and accepts the same shape on input — the empty
                            // value is the field-presence guarantee that
                            // prevents 400s once thinking is on.
                            obj.put("reasoning_content", rc)
                        } else if (placeholderAllowed) {
                            // No captured reasoning for this turn (e.g. fallback
                            // to a non-thinking model, or message persisted before
                            // thinking was enabled). Send "" rather than a synthetic
                            // marker: prior placeholders ("[no prior reasoning]",
                            // T249; single space, T257) were in-context-learned by
                            // DeepSeek V4 and echoed back as the model's own
                            // reasoning. Empty string satisfies the field-presence
                            // check with no learnable pattern.
                            obj.put("reasoning_content", "")
                        }
                    }
                    val textParts = msg.contentParts.filterIsInstance<AgentContentPart.Text>()
                    if (textParts.isNotEmpty()) {
                        obj.put("content", textParts.joinToString("") { it.text })
                    }
                    val toolUseParts = msg.contentParts.filterIsInstance<AgentContentPart.ToolUse>()
                    if (toolUseParts.isNotEmpty()) {
                        val toolCallsArr = JSONArray()
                        for (tu in toolUseParts) {
                            toolCallsArr.put(JSONObject().apply {
                                put("id", capChatToolCallId(tu.id))
                                put("type", "function")
                                put("function", JSONObject().apply {
                                    put("name", tu.name)
                                    put("arguments", tu.input.toString())
                                })
                            })
                        }
                        obj.put("tool_calls", toolCallsArr)
                    }
                    messagesArray.put(obj)
                }
                // User with tool_results → emit separate tool messages
                msg.role == LLMMessage.Role.USER -> {
                    val toolResults = msg.contentParts.filterIsInstance<AgentContentPart.ToolResult>()
                    val textParts = msg.contentParts.filterIsInstance<AgentContentPart.Text>()
                    val imageParts = msg.contentParts.filterIsInstance<AgentContentPart.ImageData>()

                    for (tr in toolResults) {
                        messagesArray.put(JSONObject().apply {
                            put("role", "tool")
                            put("tool_call_id", capChatToolCallId(tr.id))
                            put("content", tr.content)
                        })
                        // Chat Completions accepts plain text only on a
                        // tool message. Carry read_image pixels on a
                        // following user turn so vision models receive
                        // the bytes instead of only the metadata text.
                        val trBytes = tr.imageData
                        if (trBytes != null && trBytes.isNotEmpty() && supportsImages) {
                            val safeBytes = com.openminis.app.provider.ImageBudget
                                .compressUnderBudget(trBytes)
                            val safeMime = if (safeBytes === trBytes) {
                                tr.imageMimeType ?: "image/jpeg"
                            } else "image/jpeg"
                            val b64 = Base64.encodeToString(safeBytes, Base64.NO_WRAP)
                            messagesArray.put(JSONObject().apply {
                                put("role", "user")
                                put("content", JSONArray().apply {
                                    put(JSONObject().apply {
                                        put("type", "text")
                                        put("text", "[Image returned by ${tr.name}]")
                                    })
                                    put(JSONObject().apply {
                                        put("type", "image_url")
                                        put("image_url", JSONObject().apply {
                                            put("url", "data:$safeMime;base64,$b64")
                                        })
                                    })
                                })
                            })
                        }
                    }
                    // T132: emit text + image_url parts as a structured user
                    // message. The previous structured-contentParts branch
                    // dropped AgentContentPart.ImageData entirely — only the
                    // legacy non-contentParts path knew how to encode images,
                    // and that path is unreachable once contentParts is
                    // populated (which is always now). Mirrors iOS
                    // OpenAIAgentProvider.swift L732-738.
                    val hasImages = imageParts.isNotEmpty()
                    if (hasImages || textParts.isNotEmpty()) {
                        if (hasImages) {
                            val contentArray = JSONArray()
                            // Walk contentParts in original order so the
                            // [attached image: …] text caption that
                            // precedes each ImageData part stays adjacent
                            // to the right image, matching iOS.
                            for (part in msg.contentParts) {
                                when (part) {
                                    is AgentContentPart.Text -> {
                                        if (part.text.isNotEmpty()) {
                                            contentArray.put(JSONObject().apply {
                                                put("type", "text")
                                                put("text", part.text)
                                            })
                                        }
                                    }
                                    is AgentContentPart.ImageData -> {
                                        if (supportsImages) {
                                            // T-imgsize: backstop — re-encode oversize
                                            // history image bytes before base64-inlining.
                                            val safeBytes = com.openminis.app.provider.ImageBudget.compressUnderBudget(part.data)
                                            val safeMime = if (safeBytes === part.data) part.mimeType else "image/jpeg"
                                            val b64 = Base64.encodeToString(safeBytes, Base64.NO_WRAP)
                                            contentArray.put(JSONObject().apply {
                                                put("type", "image_url")
                                                put("image_url", JSONObject().apply {
                                                    put("url", "data:$safeMime;base64,$b64")
                                                })
                                            })
                                        } else {
                                            // T264: target model has no vision modality —
                                            // emit a text placeholder in place of the pixels.
                                            // [T-android-vision-group / GH#182] Vision-Group
                                            // read_image hint when seeded (carries the path);
                                            // else the historical literal.
                                            contentArray.put(JSONObject().apply {
                                                put("type", "text")
                                                put("text", part.noVisionPlaceholder
                                                    ?: "[Image attached but this model does not support vision input]")
                                            })
                                        }
                                    }
                                    else -> Unit  // ToolUse/ToolResult never appear on user role here
                                }
                            }
                            messagesArray.put(JSONObject().apply {
                                put("role", "user")
                                put("content", contentArray)
                            })
                        } else {
                            messagesArray.put(JSONObject().apply {
                                put("role", "user")
                                put("content", textParts.joinToString("") { it.text })
                            })
                        }
                    }
                }
            }
        } else {
            // Legacy: plain text messages
            val obj = JSONObject()
            obj.put("role", msg.role.value)

            if (echoReasoning && msg.role == LLMMessage.Role.ASSISTANT) {
                val rc = msg.reasoningContent
                if (rc != null) {
                    // Round-trip exactly what the server emitted (including "").
                    // See structured-content branch above for the full rationale.
                    obj.put("reasoning_content", rc)
                } else if (placeholderAllowed) {
                    // No captured reasoning — empty string satisfies the field-
                    // presence check without giving DeepSeek V4 a learnable
                    // marker to imitate (T249 / T257 history).
                    obj.put("reasoning_content", "")
                }
            }

            val attachTopLevelImages =
                index == lastUserIndex && msg.role == LLMMessage.Role.USER && imageParts.isNotEmpty()
            if (attachTopLevelImages || msg.audioParts.isNotEmpty()) {
                val contentArray = JSONArray()
                if (attachTopLevelImages) {
                    for (part in imageParts) {
                        if (supportsImages) {
                            // T-imgsize: provider-boundary backstop.
                            val safeBytes = com.openminis.app.provider.ImageBudget.compressUnderBudget(part.data)
                            val safeMime = if (safeBytes === part.data) part.mimeType else "image/jpeg"
                            val b64 = Base64.encodeToString(safeBytes, Base64.NO_WRAP)
                            val imageUrl = JSONObject()
                            imageUrl.put("url", "data:$safeMime;base64,$b64")
                            contentArray.put(JSONObject().apply {
                                put("type", "image_url")
                                put("image_url", imageUrl)
                            })
                        } else {
                            // T264: target model has no vision modality — emit
                            // a text placeholder in place of the pixels.
                            // [T-android-vision-group / GH#182] When a Vision
                            // Group is configured, ChatViewModel seeds
                            // part.noVisionPlaceholder with a read_image call
                            // hint (carrying the image path) so the model
                            // routes the image through the group instead of
                            // being told it can't see it. Null → the historical
                            // iOS-parity literal (no Vision Group configured).
                            contentArray.put(JSONObject().apply {
                                put("type", "text")
                                put("text", part.noVisionPlaceholder
                                    ?: "[Image attached but this model does not support vision input]")
                            })
                        }
                    }
                }
                // [GH#67] Official Chat Completions audio-input shape,
                // forwarded verbatim (modality is gated at the call site).
                for (audio in msg.audioParts) {
                    contentArray.put(JSONObject().apply {
                        put("type", "input_audio")
                        put("input_audio", JSONObject().apply {
                            put("data", audio.base64Data)
                            put("format", audio.format)
                        })
                    })
                }
                // Preserve the pre-GH#67 image-path behavior (text part
                // always present); for audio-only messages skip an empty
                // text block some servers reject.
                if (attachTopLevelImages || msg.content.isNotEmpty()) {
                    contentArray.put(JSONObject().apply {
                        put("type", "text")
                        put("text", msg.content)
                    })
                }
                obj.put("content", contentArray)
            } else {
                obj.put("content", msg.content)
            }

            messagesArray.put(obj)
        }
    }
    // [T-dedupe-toolcallid follow-up] Cross-message defense-in-depth:
    // rename any tool_call_id that collides with one already seen
    // elsewhere in this request. 9421990 covers the stream-time
    // collision; historical messages reloaded from the DB — or
    // messages produced by a different provider before the user
    // switched — bypass that pass, and DeepSeek (plus several
    // OpenAI-compat gateways) reject the assembled request with
    // "Duplicate value for tool_call_id ... in message[N]" whenever
    // any id repeats across the full messages array.
    globallyDedupeToolCallIds(messagesArray)
    body.put("messages", messagesArray)

    // [T-android-model-use-passthrough-mode GH#72] Merge user-supplied extra
    // body fields verbatim (no OpenAI→native conversion — callers own the
    // shape). User keys win over our defaults, but `model` is force-kept so a
    // stray override can't misroute. Mirrors generateImage's merge + iOS.
    mergeChatExtraBody(body)

    return body
}

/**
 * [T-android-model-use-passthrough-mode GH#72] Shared verbatim merge of
 * [chatExtraBody] into a request body, applied by BOTH the chat/completions
 * and responses builders so no endpoint can forget the passthrough. User
 * keys overwrite; `model` is force-restored last. Skipped for Codex OAuth
 * (its body is part of the client fingerprint and must stay untouched).
 */
internal fun OpenAIProvider.applyAstraRequestContract(body: JSONObject) {
    if (!model.isGpt6Astra) return
    // Enforce after passthrough merging so stale stored parameters cannot
    // reintroduce fields rejected by Astra. OFF/minimal map to its lowest tier.
    for (key in listOf("temperature", "top_p", "top_logprobs", "logprobs")) body.remove(key)
    val allowed = LLMModel.gpt6Astra.reasoningEffortValues
    if (usesChatCompletionsAPI) {
        body.put("reasoning_effort", clampEffort(body.optString("reasoning_effort", "low"), allowed))
    } else {
        val reasoning = body.optJSONObject("reasoning") ?: JSONObject()
        reasoning.put("effort", clampEffort(reasoning.optString("effort", "low"), allowed))
        body.put("reasoning", reasoning)
        body.optJSONArray("include")?.let { include ->
            body.put("include", JSONArray().apply {
                for (i in 0 until include.length()) {
                    if (include.optString(i) != "message.output_text.logprobs") put(include.get(i))
                }
            })
        }
        if (body.has("prompt_cache_retention")) {
            body.remove("prompt_cache_retention")
            if (!body.has("prompt_cache_options")) body.put("prompt_cache_options", JSONObject().put("ttl", "30m"))
        }
    }
}

internal fun OpenAIProvider.mergeChatExtraBody(body: JSONObject) {
    if (chatExtraBody.isEmpty()) return
    if (isOAuth && !forceChatCompletions) return  // Codex OAuth exemption
    // [T-eta-provider-passthrough] Recursive merge (Eta RequestBodyMerge
    // rules): objects merge field-by-field, arrays and scalars replace.
    RequestBodyMerge.mergeInto(body, chatExtraBody)
    body.put("model", model.id)
}

/**
 * Walk every assistant.tool_calls entry and every role:"tool"
 * tool_call_id in order, renaming any duplicate id to `{id}-{N}`.
 * The first occurrence keeps the raw id; subsequent collisions get
 * a numeric suffix starting at 2. Renames propagate to each pair's
 * matching role:"tool" reply by remembering the latest rename per
 * raw id (the reply is required to immediately follow its claiming
 * assistant tool_calls on this provider).
 */
internal fun OpenAIProvider.globallyDedupeToolCallIds(messagesArray: JSONArray) {
    // raw id → max suffix already issued (0 = unused, 1 = raw kept,
    // 2+ = renamed copies).
    val seen = HashMap<String, Int>()
    // raw id → latest renamed id, so the next role:"tool" reply
    // claiming this raw id can pick up the same rewrite.
    val renameForPendingResults = HashMap<String, String>()
    var renamedCount = 0
    val n = messagesArray.length()
    for (i in 0 until n) {
        val msg = messagesArray.optJSONObject(i) ?: continue
        val role = msg.optString("role")
        when (role) {
            "assistant" -> {
                val toolCalls = msg.optJSONArray("tool_calls") ?: continue
                for (j in 0 until toolCalls.length()) {
                    val call = toolCalls.optJSONObject(j) ?: continue
                    val rawId = call.optString("id", "")
                    if (rawId.isEmpty()) continue
                    val used = seen[rawId] ?: 0
                    val renamedId: String
                    if (used == 0) {
                        renamedId = rawId
                        seen[rawId] = 1
                    } else {
                        val next = used + 1
                        renamedId = "$rawId-$next"
                        seen[rawId] = next
                        renamedCount += 1
                        call.put("id", renamedId)
                    }
                    renameForPendingResults[rawId] = renamedId
                }
            }
            "tool" -> {
                val rawId = msg.optString("tool_call_id", "")
                if (rawId.isEmpty()) continue
                val renamedId = renameForPendingResults[rawId] ?: continue
                if (renamedId != rawId) {
                    msg.put("tool_call_id", renamedId)
                }
            }
        }
    }
    if (renamedCount > 0) {
        android.util.Log.w("OpenAIProvider", "[dedupe-tool-call-id] renamed $renamedCount duplicate tool_call_id(s) across messages — likely DB-loaded history or cross-provider switch")
    }
}

/**
 * T302: takes the pre-serialized body string instead of the JSONObject so
 * the caller can serialize once and reuse the result for the debug log,
 * the OAuth byte build, and the OkHttp RequestBody. Per-call peak heap
 * dropped by ~2× the body size (often tens of MB on long agent loops).
 */
internal suspend fun OpenAIProvider.buildRequest(bodyStr: String): Request {
    val token = getToken()

    if (isOAuth && !forceChatCompletions) {
        // Codex OAuth — use ChatGPT backend Responses API
        // Use MediaType without charset — ChatGPT backend rejects "application/json; charset=utf-8"
        val jsonMediaType = "application/json".toMediaType()
        val bodyBytes = bodyStr.toByteArray(Charsets.UTF_8)
        val requestBody = object : okhttp3.RequestBody() {
            override fun contentType() = jsonMediaType
            override fun contentLength() = bodyBytes.size.toLong()
            override fun writeTo(sink: okio.BufferedSink) { sink.write(bodyBytes) }
        }
        val builder = Request.Builder()
            .url("https://chatgpt.com/backend-api/codex/responses")
            .post(requestBody)
            .header("Authorization", "Bearer $token")
            .header("Version", CODEX_CLIENT_VERSION)
            .header("Openai-Beta", "responses=experimental")
            .header("User-Agent", "codex_cli_rs/$CODEX_CLIENT_VERSION (Android; arm64)")
            .header("Originator", "codex_cli_rs")
        codexAccountId?.let { builder.header("Chatgpt-Account-Id", it) }
        // [T-provider-custom-user-agent] Applied last so a non-blank
        // override wins over the Codex default UA. In practice null on
        // this OAuth path (the UI only exposes it for custom-base
        // instances), so this is a no-op there.
        // [T-android-default-ua] `defaultUserAgent = null` — keep the
        // codex_cli_rs fingerprint set above when no per-provider
        // override is configured. We must NOT fall back to the branded
        // Minis UA here: the ChatGPT OAuth backend validates the
        // client identity against this header.
        builder.applyUserAgentOverride(customUserAgent, defaultUserAgent = null)
        return builder.build()
    }

    val endpointPath = if (usesChatCompletionsAPI) "/chat/completions" else "/responses"
    // [T-android-azure-openai] Azure routes via the deployments path and
    // auths with the api-key header. azureUrl() returns null when not in
    // Azure mode / no base, so the standard basePath join stays the default.
    // [T-android-model-use-passthrough-mode] endpointURL() honors an
    // absolute-path override ("/...") that replaces the whole path on the
    // provider host; otherwise Azure/basePath as before.
    val requestUrl = when {
        absoluteEndpointOverride?.startsWith("/") == true -> endpointURL(endpointPath)
        isAzure -> azureUrl(endpointPath) ?: "$basePath$endpointPath"
        else -> "$basePath$endpointPath"
    }
    // T-responses-include: match iOS bare `application/json` Content-Type
    // by using a custom RequestBody (same workaround the OAuth branch
    // above already does). OkHttp's `String.toRequestBody(MediaType)`
    // helper attaches the MediaType to the body and several proxies/
    // backends end up seeing `application/json; charset=utf-8`. iOS sends
    // bare `application/json`; some third-party Responses-API proxies are
    // stricter and reject the charset suffix.
    val jsonMediaType = "application/json".toMediaType()
    val bodyBytes = bodyStr.toByteArray(Charsets.UTF_8)
    val requestBody = object : okhttp3.RequestBody() {
        override fun contentType() = jsonMediaType
        override fun contentLength() = bodyBytes.size.toLong()
        override fun writeTo(sink: okio.BufferedSink) { sink.write(bodyBytes) }
    }
    val builder = Request.Builder()
        .url(requestUrl)
        .post(requestBody)
        .applyKeyAuth(token)
        .header("Content-Type", "application/json")
    for ((key, value) in extraHeaders) {
        builder.header(key, value)
    }
    // [T-android-model-use-passthrough-mode] Per-call chat header overrides,
    // applied AFTER the ctor extraHeaders → same-name REPLACE over any
    // default (incl. Authorization/Content-Type). Empty on normal calls.
    for ((key, value) in chatExtraHeaders) {
        builder.header(key, value)
    }
    // [T-provider-custom-user-agent] Covers both chat/completions and
    // /responses (this builder serves both). Applied after extraHeaders
    // so the per-provider override wins. null/blank → default UA.
    builder.applyUserAgentOverride(customUserAgent)
    return builder.build()
}

internal fun OpenAIProvider.parseChatCompletionsUsage(usage: JSONObject): LLMUsage {
    val promptTokens = usage.optInt("prompt_tokens", 0)
    // DeepSeek reports cache hits at `usage.prompt_cache_hit_tokens` instead
    // of the OpenAI-native `prompt_tokens_details.cached_tokens`. Mirrors
    // iOS OpenAIProvider.swift:629-630. Without this fallback, DeepSeek V4
    // looked like it never cached even when it did, masking T122's win.
    val cacheRead = usage.optJSONObject("prompt_tokens_details")
        ?.optInt("cached_tokens")?.takeIf { it > 0 }
        ?: usage.optInt("prompt_cache_hit_tokens", 0).takeIf { it > 0 }
    // OpenAI/DeepSeek `prompt_tokens` is the FULL input (cached + fresh), so
    // subtract the cached portion to keep `inputTokens` meaning fresh-only —
    // matching the Anthropic convention. Otherwise the cached tokens are
    // counted twice in `input + cacheRead` (deflates the cache-hit rate;
    // DeepSeek 99% hit showed as ~48%). Guard: only subtract when it stays
    // non-negative; no cache field (cacheRead == null) → unchanged.
    // latestContextTokens stays the full prompt (that IS the context size).
    val freshInput = cacheRead?.let { (promptTokens - it).takeIf { d -> d >= 0 } } ?: promptTokens
    return LLMUsage(
        inputTokens = freshInput,
        outputTokens = usage.optInt("completion_tokens", 0),
        cacheReadInputTokens = cacheRead,
        latestContextTokens = promptTokens,
    )
}

/**
 * Inject provider-specific thinking parameters into the request body.
 * - OpenRouter: `reasoning: {effort: ...}` (omitted when off so
 *   forced-reasoning models keep their default)
 * - OpenAI o-series / GPT-5.x: `reasoning_effort: ...` (off → skip)
 * - Qwen3 (DashScope): `enable_thinking: true/false, thinking_budget: N`
 *   — Qwen3 thinks by default, so OFF needs an explicit disable.
 * - DeepSeek V4 (deepseek-v4-flash / deepseek-v4-pro): `thinking` object —
 *   V4 thinks by default and rejects requests without an explicit toggle
 *   when reasoning_content is missing. Distinct from deepseek-reasoner /
 *   deepseek-chat which keep the no-params path below.
 * - DeepSeek (pre-V4) / GLM / Kimi / MiniMax: no params (model decides).
 */
/**
 * [T-android-xhigh-effort-clamp] Clamp the reasoning-effort string for
 * model families whose backend only accepts low/medium/high and 400/422 on
 * our `xhigh` tier: MiMo-2.5/Pro and Agnes. For these, `xhigh` → `high`;
 * every other value passes through untouched, and every other model is
 * unaffected. Applied at the single point where each branch would emit an
 * effort string (Chat Completions reasoning_effort / reasoning.effort AND
 * the Responses API reasoning.effort) so no branch can leak a raw xhigh.
 * lowercase-contains match, mirroring the T-reasoning-effort-fallback keys.
 */
internal fun OpenAIProvider.clampEffortForModel(effort: String): String {
    val lid = model.id.lowercase()
    // [T-fallback-thinking-preclamp] Match the FAMILY substring, not one
    // spelling: catalog docs say "MiMo-2.5" but the live API returns
    // "mimo-v2.5" / "mimo-v2.5-pro", which the old "mimo-2.5" match missed
    // (mirrors iOS 72968c4f).
    return if (effort == "xhigh" && (lid.contains("mimo") || lid.contains("agnes"))) "high" else effort
}

/**
 * Inject the provider-specific thinking parameters for one request.
 *
 * [T-thinking-rules-phase1] The body of this function used to be an if-return chain
 * keyed on model-id substrings. That logic now lives in [ThinkingRuleResolver] as a
 * data-driven rule registry (design §4/§5); this remains as the call-site-compatible
 * entry point so every caller — and the golden snapshot that pins this exact
 * behaviour — is unchanged.
 *
 * Behaviour is byte-for-byte identical to the pre-refactor chain, enforced by
 * ThinkingWireGoldenSnapshotTest (119 rows generated against the old code and
 * committed before the refactor, fdc28e2b).
 *
 * PHASE 1 SCOPE: OpenAI-compatible endpoints only. Gemini and Anthropic keep their
 * own emitters and are not routed through the resolver yet.
 */
internal fun OpenAIProvider.injectThinkingParams(body: JSONObject, level: ThinkingLevel, maxTokens: Int) {
    // [T-android-thinking-level-arch] `level` is already clamped to the model ceiling
    // by LLMProvider.streamMessage/sendMessage — do NOT re-clamp here.
    val ctx = ThinkingResolveContext(
        modelId = model.id,
        instanceId = thinkingRuleInstanceId,
        supportsReasoning = model.supportsReasoning,
        declaredEffortValues = model.reasoningEffortValues,
        // [OpenMinis#163] null (catalog silent) must read as false here —
        // only an affirmative declaration may suppress the field.
        declaresNoEffortTiers = model.declaresNoEffortTiers == true,
        level = level,
        maxTokens = maxTokens,
        isOpenRouter = isOpenRouter,
        usesUnifiedReasoningEffort = usesUnifiedReasoningEffort,
        isMistral = isMistral,
        isDashScope = isDashScope,
        isXAI = isXAI,
        offEffort = explicitOffEffort(),
    )
    val trace = ThinkingRuleResolver.apply(body, ctx)
    // [T-thinking-rules-observability] Design §8 / GH OpenMinis#100: which rule
    // actually won must be inspectable, or a rule layer just replaces one hidden
    // variable with a more complicated one. minis-config exposure is Phase 2.
    com.openminis.app.logging.AppLogger.info(
        "Thinking",
        "[resolve] model=${model.id} level=${level.name} ${trace.logLine}",
    )
}

/**
 * [T-reasoning-effort-data-driven] Snap an effort string onto the tiers the
 * model actually declares. Mirrors iOS
 * `OpenAIAgentProvider.clampEffort(_:to:)` — keep both in sync.
 *
 * Necessary because the catalog's effort sets are far from uniform
 * (["low","medium","high"], ["high","max"], ["high","xhigh"], …). Sending an
 * undeclared tier is the same class of failure the MiMo/Agnes xhigh clamp
 * already guards against ("Invalid reasoning_effort: xhigh" 400s).
 *
 * Nearest-tier semantics: step down to the closest declared tier at or below
 * the request; only if none exists step up to the lowest declared one.
 * Downgrading is preferred because overshooting costs money and latency the
 * user did not ask for. Null/empty values pass the string through unchanged.
 */
internal fun OpenAIProvider.clampEffort(effort: String, values: List<String>?): String {
    if (values.isNullOrEmpty()) return effort
    if (values.contains(effort)) return effort
    val ladder = listOf("none", "minimal", "low", "medium", "high", "xhigh", "max")
    val want = ladder.indexOf(effort)
    if (want < 0) return effort
    val declared = values.mapNotNull { v ->
        val i = ladder.indexOf(v)
        if (i >= 0) i to v else null
    }.sortedBy { it.first }
    if (declared.isEmpty()) return effort
    return declared.lastOrNull { it.first <= want }?.second ?: declared.first().second
}

// MARK: - Codex image generation (gpt-image-2)

/**
 * [T-codex-gpt-image2-oauth-android] Build the Codex image_generation
 * request body. The wire model is gpt-5.5 (the Codex backend invokes the
 * underlying gpt-image-2 via the built-in image_generation tool); the user
 * turn is the fixed "Use the image generation tool to create: <prompt>"
 * instruction. The <prompt> is the latest user text — plain string content
 * or the concatenated text parts of the last user message.
 */
internal fun OpenAIProvider.buildCodexImageBody(messages: List<LLMMessage>): JSONObject {
    val lastUser = messages.lastOrNull { it.role == LLMMessage.Role.USER }
    val prompt = lastUser?.let { m ->
        m.content.takeIf { it.isNotBlank() }
            ?: m.contentParts.filterIsInstance<AgentContentPart.Text>()
                .joinToString(" ") { it.text }.trim()
    }.orEmpty()
    return JSONObject().apply {
        put("model", "gpt-5.5")
        put("instructions", "You are a helpful assistant. Use tools when available.")
        put("input", JSONArray().put(JSONObject().apply {
            put("role", "user")
            put("content", "Use the image generation tool to create: $prompt")
        }))
        put("store", false)
        // A bare {type:image_generation} lets the backend choose its default image
        // model (what gpt-image-2 has always relied on); only the 2.5 variants
        // are named explicitly.
        val imageTool = JSONObject().put("type", "image_generation")
        if (model.id in CODEX_IMAGE_25_MODEL_IDS) imageTool.put("model", model.id)
        put("tools", JSONArray().put(imageTool))
        put("reasoning", JSONObject().put("effort", "low"))
        put("include", JSONArray())
        put("tool_choice", "auto")
        put("parallel_tool_calls", true)
        put("stream", true)
    }
}

/**
 * [T-android-codex-image-stream-parse-fix #617] Consume the Codex Responses
 * SSE stream and extract the generated image, emitting it as a
 * MediaAttachment chunk followed by Finished.
 *
 * Structurally aligned with iOS `consumeCodexImageStream` (1225ec0b /
 * 2dd35a14): parse each `data:` SSE line as JSON and pull the base64 from
 * the `image_generation_call` output item's `result` field — NOT a blind
 * regex over the raw body. The previous regex `iVBOR[A-Za-z0-9+/=]{1000,}`
 * only matched PNG base64 (iVBOR is the base64 of the PNG \x89PNG header),
 * so a WebP (UklGR…) or JPEG (/9j/…) image — which gpt-image-2 routinely
 * returns — never matched and the method threw "no image data" even though
 * the Codex backend had returned a full ~8 MB valid image (#615 diagnosis).
 *
 * Failure modes, each a distinct LLMError (mirrors iOS):
 *   - auth (401/403): surfaced earlier by the non-2xx branch → mapHttpError,
 *     never reaches here.
 *   - safety refusal: `image_generation_call` status=failed and/or a refusal
 *     message instead of an image → ProviderError("rejected by safety…").
 *   - no image: stream completed with neither image nor refusal →
 *     ProviderError("No image data…"). Only reported in this genuine case —
 *     not on a successfully-decoded non-PNG image.
 *   - network/interface: read throws (IOException) → caller maps to
 *     NetworkError.
 *
 * Streams line-by-line (no 8 MB StringBuilder + regex backtracking): only
 * the one `result` base64 string is retained, decoded once at the end.
 * Never logs the token (the SSE body carries no Authorization).
 */
internal suspend fun OpenAIProvider.handleCodexImageStream(
    reader: BufferedReader,
    emit: (LLMStreamChunk) -> Unit,
) {
    var b64Result: String? = null
    var revisedPrompt: String? = null
    var imageCallFailed = false
    var refusalText: String? = null

    // Pull the base64 result / failure / revised prompt out of one output
    // item. Used both for streamed `response.output_item.done` items and,
    // as a fallback, for every item in the final `response.completed`
    // payload (matches iOS scanItem).
    fun scanItem(item: JSONObject) {
        when (item.optString("type")) {
            "image_generation_call" -> {
                if (item.optString("status") == "failed") imageCallFailed = true
                item.optString("result").takeIf { it.isNotEmpty() }?.let { b64Result = it }
                item.optString("revised_prompt").takeIf { it.isNotEmpty() }?.let { revisedPrompt = it }
            }
            "message", "output_text" -> {
                // Refusal / explanation text the model emits when it declines.
                val content = item.optJSONArray("content")
                if (content != null) {
                    for (i in 0 until content.length()) {
                        val c = content.optJSONObject(i) ?: continue
                        if (c.optString("type").contains("text")) {
                            c.optString("text").takeIf { it.isNotEmpty() }?.let { refusalText = it }
                        }
                    }
                } else {
                    item.optString("text").takeIf { it.isNotEmpty() }?.let { refusalText = it }
                }
            }
        }
    }

    var line: String?
    while (reader.readLine().also { line = it } != null) {
        val l = line ?: continue
        // Tolerate both `data: {…}` and `data:{…}` (same as the chat path).
        if (!l.startsWith("data:")) continue
        val payload = l.removePrefix("data:").let { if (it.startsWith(" ")) it.removePrefix(" ") else it }
        if (payload == "[DONE]") break

        val event = try { JSONObject(payload) } catch (e: Exception) { continue }
        when (event.optString("type")) {
            "response.output_item.done" -> {
                event.optJSONObject("item")?.let { scanItem(it) }
            }
            "response.output_text.done", "response.output_text.delta" -> {
                event.optString("text").takeIf { it.isNotEmpty() }?.let { refusalText = it }
                    ?: event.optString("delta").takeIf { it.isNotEmpty() }
                        ?.let { refusalText = (refusalText ?: "") + it }
            }
            "response.completed" -> {
                if (b64Result == null) {
                    val output = event.optJSONObject("response")?.optJSONArray("output")
                    if (output != null) {
                        for (i in 0 until output.length()) {
                            output.optJSONObject(i)?.let { scanItem(it) }
                        }
                    }
                }
            }
            "response.failed", "error" -> {
                val msg = event.optJSONObject("response")?.optJSONObject("error")?.optString("message")
                    ?.takeIf { it.isNotEmpty() }
                    ?: event.optJSONObject("error")?.optString("message")?.takeIf { it.isNotEmpty() }
                    ?: "Codex image generation failed"
                throw LLMError.ProviderError(msg)
            }
        }
    }

    // Success: base64 image extracted. Detect the real format from the
    // decoded bytes (PNG / JPEG / WebP / GIF) instead of assuming PNG.
    val b64 = b64Result
    if (b64 != null) {
        val bytes = try {
            // JVM MIME decoder: same result as Base64.DEFAULT (tolerates line breaks) and runs under unit tests.
            java.util.Base64.getMimeDecoder().decode(b64)
        } catch (e: Exception) {
            throw LLMError.ProviderError("Failed to decode generated image: ${e.message}")
        }
        if (bytes.isNotEmpty()) {
            emit(
                LLMStreamChunk.MediaAttachment(
                    LLMMediaAttachment(
                        type = LLMMediaAttachment.MediaType.IMAGE,
                        mimeType = detectImageMime(bytes),
                        data = bytes,
                    ),
                ),
            )
            emit(LLMStreamChunk.Finished("end_turn"))
            return
        }
    }

    // Safety refusal: the image call explicitly failed and/or the model
    // returned a refusal message instead of an image.
    if (imageCallFailed || refusalText != null) {
        val reason = refusalText?.trim()
        throw LLMError.ProviderError(
            "Image generation was rejected by the safety system" +
                (reason?.takeIf { it.isNotBlank() }?.let { ": $it" } ?: "."),
        )
    }

    // Stream completed with neither an image nor a refusal.
    throw LLMError.ProviderError("No image data in Codex response")
}

/**
 * [T-android-codex-image-stream-parse-fix] Detect an image's MIME type from
 * its magic bytes. Mirrors iOS `detectImageMime`. gpt-image-2 can return
 * PNG, JPEG, or WebP, so the previous hardcoded "image/png" mislabeled
 * non-PNG output. Falls back to image/png when too short / unrecognized.
 */
internal fun OpenAIProvider.detectImageMime(data: ByteArray): String {
    if (data.size < 4) return "image/png"
    val b = data.map { it.toInt() and 0xFF }
    return when {
        b[0] == 0x89 && b[1] == 0x50 && b[2] == 0x4E && b[3] == 0x47 -> "image/png"
        b[0] == 0xFF && b[1] == 0xD8 -> "image/jpeg"
        b[0] == 0x52 && b[1] == 0x49 && b[2] == 0x46 && b[3] == 0x46 -> "image/webp" // RIFF (WebP)
        b[0] == 0x47 && b[1] == 0x49 && b[2] == 0x46 -> "image/gif"
        else -> "image/png"
    }
}
