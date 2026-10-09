package com.openminis.app.provider.openai

import android.util.Base64
import com.openminis.app.data.model.AgentContentPart
import com.openminis.app.data.model.AgentToolDefinition
import com.openminis.app.data.model.LLMError
import com.openminis.app.data.model.LLMMessage
import com.openminis.app.data.model.LLMUsage
import com.openminis.app.data.model.ThinkingLevel
import com.openminis.app.data.model.hasImageInput
import com.openminis.app.provider.HostedWebSearchPolicy
import com.openminis.app.provider.LLMProvider
import com.openminis.app.provider.safeOptString
import com.openminis.app.util.Sha256
import java.io.IOException
import org.json.JSONArray
import org.json.JSONObject

// MARK: - Responses API (Codex OAuth)

/**
 * Build request body for the Responses API format (used by Codex OAuth).
 * Uses `input` instead of `messages`, `instructions` instead of system prompt.
 */
/**
 * [T-android-responses-toplevel-images] Encode one image as a Responses-API
 * content block, or as the no-vision text placeholder when the target model
 * cannot see pixels.
 *
 * Extracted so the two Responses call sites (structured `contentParts`
 * messages and legacy top-level `imageParts` messages) cannot drift apart —
 * the drift is exactly what produced the silent drop this fixes. Note the
 * Responses shape differs from Chat Completions: `image_url` is a bare
 * STRING here, not a `{"url": …}` object.
 */
internal fun OpenAIProvider.responsesImageBlock(
    data: ByteArray,
    mimeType: String,
    supportsImages: Boolean,
    noVisionPlaceholder: String?,
): JSONObject = if (supportsImages) {
    // T-imgsize: provider-boundary backstop.
    val safeBytes = com.openminis.app.provider.ImageBudget.compressUnderBudget(data)
    val safeMime = if (safeBytes === data) mimeType else "image/jpeg"
    val b64 = Base64.encodeToString(safeBytes, Base64.NO_WRAP)
    JSONObject().apply {
        put("type", "input_image")
        put("image_url", "data:$safeMime;base64,$b64")
    }
} else {
    JSONObject().apply {
        put("type", "input_text")
        put(
            "text",
            noVisionPlaceholder
                ?: "[Image attached but this model does not support vision input]",
        )
    }
}

internal fun OpenAIProvider.buildResponsesAPIBody(
    messages: List<LLMMessage>,
    systemPrompt: String?,
    maxTokens: Int,
    stream: Boolean,
    temperature: Double? = null,
    /**
     * [T-android-responses-toplevel-images] Images passed as the top-level
     * argument rather than on `msg.contentParts`, attached to the LAST user
     * message — the same contract [buildRequestBody] implements.
     *
     * This parameter did not exist, and that was a silent data loss: every
     * caller that supplies images this way (minis-model-use's `image_url`
     * blocks, VisionGroupResolver.describeOnce, any direct
     * sendMessage(imageParts=…)) had its pixels dropped on the floor the
     * moment the provider was on the Responses path, with no error. The
     * user-visible symptom was a vision model replying "no image was
     * provided" — reported against a Vision Group whose describing model
     * ran on Responses.
     */
    imageParts: List<LLMMessage.ImagePart> = emptyList(),
    tools: List<AgentToolDefinition> = emptyList(),
    thinkingLevel: ThinkingLevel = ThinkingLevel.OFF,
): JSONObject {
    // T264: same vision-capability gate as buildRequestBody. Responses API
    // path (Codex OAuth) is currently always wired to a vision-capable
    // GPT-5.x so this branch is defensive rather than load-bearing, but
    // keeping the two paths symmetric prevents future regressions when
    // a non-vision model gets routed through Responses (e.g. via
    // forceResponsesAPI on a custom provider).
    val supportsImages = model.hasImageInput
    val body = JSONObject()
    body.put("model", model.id)
    body.put("stream", stream)
    body.put("store", false)
    body.put("parallel_tool_calls", true)
    // Stable per-conversation cache key so the Responses API can hit prompt
    // cache across turns. Codex CLI sets this to its conversation_id; at
    // this layer we don't have one, so we hash the first user message —
    // re-sent verbatim every turn of the same chat → stable across turns,
    // distinct between chats. iOS does the same in
    // OpenAIAgentProvider.swift:325 + derivePromptCacheKey() at line 515.
    // Without this, each turn was treated as a separate prompt by the
    // Responses-API cache regardless of how byte-stable the prefix was —
    // that's the missing piece between Android (~70%) and iOS (90%+) on
    // the Codex OAuth / forceResponsesAPI path.
    body.put("prompt_cache_key", derivePromptCacheKey(messages))
    // T-responses-include: `include: ["reasoning.encrypted_content"]` is a
    // ChatGPT-backend-only field. Third-party Responses-API-compatible
    // proxies (non-OpenAI) don't recognize it and reject the request with
    // 400. Mirrors iOS OpenAIAgentProvider.swift:404 which gates this
    // strictly behind isCodexOAuth. OpenAI's first-party Responses API
    // also accepts the field, so we keep it on for OAuth (Codex) only —
    // the encrypted reasoning content is what lets the ChatGPT backend
    // re-attach prior reasoning across turns without store=true.
    if (isOAuth) {
        body.put("include", JSONArray().put("reasoning.encrypted_content"))
    }
    // Thinking level → Responses API `reasoning.effort`. Mirrors iOS
    // OpenAIAgentProvider.swift:327-338. Pre-T119 this was hardcoded to
    // "low" regardless of the user's setting, so toggling Thinking
    // High/Medium/Off had no effect on GPT-5.x via the Responses path.
    // - When the user has thinking enabled → map their level to the
    //   matching effort string.
    // - When off but using Codex OAuth → fall back to "low" because the
    //   ChatGPT backend rejects requests without a `reasoning` object.
    // - Else → omit the field so the upstream applies its own default.
    // [T-android-codex-thinking-summary] `summary: "auto"` opts in to
    // streaming the human-readable reasoning SUMMARY (delivered as
    // `response.reasoning_summary_text.delta` SSE events with non-empty
    // `delta`). Without it the Responses API / Codex backend returns ONLY
    // `encrypted_content` — the reasoning deltas arrive empty, so the
    // Thinking region never renders even though the model reasoned (token
    // usage shows it did). This was the Codex-OAuth "thinking on but UI
    // shows nothing" bug (XIN). Mirrors iOS OpenAIAgentProvider.swift:415
    // (`["effort": effort, "summary": "auto"]`). OpenAI ignores the variant
    // it doesn't support and falls back to an auto-equivalent, so it's safe
    // on every Responses-flavor endpoint.
    // [T-android-xhigh-effort-clamp] Also clamp on the Responses API path:
    // the reasoning.effort field is the same name/values as Chat Completions
    // and would send xhigh too. MiMo-2.5/Agnes normally use Chat Completions
    // (the reported 400/422), but a user could flip useResponsesAPI on, so
    // guard it here as well — only xhigh for those two families is affected.
    // [T-android-thinking-level-arch] `thinkingLevel` is already clamped by
    // LLMProvider.streamMessage/sendMessage before reaching here.
    val effort = if (thinkingLevel.isEnabled) {
        mapThinkingLevelToResponsesEffort(thinkingLevel)?.let { clampEffortForModel(it) }
    } else null
    when {
        // [T-android-mistral-reasoning-422] Mistral rejects the reasoning
        // request parameter outright (`422 extra_forbidden body.reasoning`,
        // GH OpenMinis#87). The gate added alongside injectThinkingParams
        // covers only the Chat Completions path; this builder is a SECOND,
        // independent injection site that a Mistral instance with
        // useResponsesAPI enabled reaches ungated. For Mistral the answer to
        // "should any thinking field be sent" is NEVER, on every request
        // path — so suppress the whole block. Must stay FIRST so it wins over
        // the isOAuth fallback below.
        isMistral -> {}
        effort != null -> body.put(
            "reasoning",
            JSONObject().put("effort", effort).put("summary", "auto"),
        )
        isOAuth -> body.put(
            "reasoning",
            JSONObject().put("effort", "low").put("summary", "auto"),
        )
        // [T-thinking-off-explicit] Thinking OFF on a reasoning-capable
        // model: send the explicit off tier instead of omitting `reasoning`
        // — omission lets the vendor default kick in. Same ALLOWLIST as the
        // Chat path (official OpenAI → "none", Volcano Ark → "minimal");
        // vendors with undocumented off semantics keep the historical
        // omission. No summary/include: nothing should stream back.
        // Mirrors iOS OpenAIAgentProvider ff60c818's Responses off branch.
        !thinkingLevel.isEnabled && model.supportsReasoning == true &&
            !model.id.lowercase().let { it.contains("mimo") || it.contains("agnes") } -> {
            explicitOffEffort()?.let { offEffort ->
                body.put("reasoning", JSONObject().put("effort", offEffort))
            }
        }
    }

    // [T-responses-max-output-tokens] The builder received maxTokens but
    // never wrote it into the body, so Responses-flavor vendors fell back
    // to their (often tiny) defaults and truncated. Same guard as iOS
    // 637cd890/5f148144: maxTokens > 0, and Codex OAuth excluded — the
    // codex_cli_rs body shape is a client fingerprint and must not carry
    // fields the real CLI doesn't send.
    if (maxTokens > 0 && !isOAuth) {
        body.put("max_output_tokens", maxTokens)
    }

    // [T-android-session-temperature-responses / #35] The session
    // override was passed to the Chat Completions builder only, so custom
    // Responses API instances silently ignored it. Reasoning models and
    // Codex OAuth reject/forbid temperature, hence the same capability
    // gate used by the settings UI and the OAuth fingerprint guard.
    if (temperature != null && supportsTemperatureOverride) {
        body.put("temperature", temperature)
    }

    // [T-codex-fast-mode] Fast tier injection (mirrors iOS fb671083 +
    // 838ba929). Wire value verified against openai/codex source
    // (codex-rs/protocol config_types.rs): ServiceTier::Fast sends
    // service_tier="priority" — "fast" is only the UI name, so this stays
    // inside the codex_cli_rs client fingerprint on the OAuth route. Gate
    // is toggle + gpt-family model only: this builder IS the Responses
    // path, and Responses relays (e.g. sub2api) normalize/pass the tier
    // through, so no isOAuth narrowing. Ineligible upstreams ignore the
    // field or silently downgrade (receipt visible via the
    // response.completed service_tier log).
    resolvedServiceTier()?.let { body.put("service_tier", it) }

    if (systemPrompt != null) {
        body.put("instructions", systemPrompt)
    }

    // Tools — flat shape required by Responses API ({type, name, description,
    // parameters}), distinct from Chat Completions' wrapped {type, function:{...}}.
    // Until this branch existed, Responses-API requests went out with no `tools`
    // field at all, so the model invented its own <tool_call>{...} text format.
    val toolsArray = JSONArray()
    for (tool in tools) {
        toolsArray.put(tool.toResponsesAPIJson())
    }
    // [T-eta-hosted-web-search] The provider's own web search rides in the same array; with the
    // entry's opt-in off (the default) this is exactly the array the managed tools produce.
    val requestTools = HostedWebSearchPolicy.apply(toolsArray, model.hostedWebSearch)
    if (requestTools.length() > 0) {
        body.put("tools", requestTools)
        body.put("tool_choice", "auto")
    }

    // Mirrors iOS convertMessagesResponsesAPI (OpenAIAgentProvider.swift:895):
    // structured content parts become typed input items — function_call /
    // function_call_output — instead of free-text role/content pairs.
    val input = JSONArray()
    // [T-android-responses-toplevel-images] Same contract as
    // buildRequestBody: top-level images ride on the LAST user message.
    val lastUserIdx = messages.indexOfLast { it.role == LLMMessage.Role.USER }
    for ((msgIndex, msg) in messages.withIndex()) {
        val attachTopLevelImages =
            msgIndex == lastUserIdx && msg.role == LLMMessage.Role.USER && imageParts.isNotEmpty()
        if (msg.contentParts.isNotEmpty()) {
            when (msg.role) {
                LLMMessage.Role.ASSISTANT -> {
                    // [T-eta-responses-opaque-items] Replay the items captured
                    // from this turn's stream verbatim, in their original
                    // position (a reasoning item precedes the message and the
                    // function calls it belongs to). Only items the transcript
                    // cannot rebuild are captured, so nothing is duplicated:
                    // `function_call` items are rebuilt below with their exact
                    // ids and the text follows as a message item.
                    for (raw in msg.providerOutputItems) {
                        val item = try { JSONObject(raw) } catch (_: Exception) { null }
                        if (item != null) input.put(item)
                    }
                    val textParts = msg.contentParts.filterIsInstance<AgentContentPart.Text>()
                    if (textParts.isNotEmpty()) {
                        val text = textParts.joinToString("") { it.text }
                        if (text.isNotEmpty()) {
                            input.put(JSONObject().apply {
                                put("role", "assistant")
                                put("content", text)
                            })
                        }
                    }
                    for (tu in msg.contentParts.filterIsInstance<AgentContentPart.ToolUse>()) {
                        val (callId, fcId) = splitResponsesAPIIds(tu.id)
                        val safeCallId = capResponsesId(callId)
                        // Responses API requires both `id` (fc_…) and `call_id` (call_…).
                        // When the message was synthesized outside a Responses round-trip
                        // (e.g. injected from Chat Completions history) the fcId is null —
                        // generate a deterministic synthetic so the API still accepts it.
                        val safeFcId = fcId?.let { capResponsesId(it) }
                            ?: "fc_syn_${safeCallId.takeLast(24)}"
                        input.put(JSONObject().apply {
                            put("type", "function_call")
                            put("id", safeFcId)
                            put("call_id", safeCallId)
                            put("name", tu.name)
                            put("arguments", tu.input.toString())
                        })
                    }
                }
                LLMMessage.Role.USER -> {
                    // Same rule as Chat Completions: all outputs of one assistant turn first, the pixels after.
                    val imageTurns = ArrayList<JSONObject>()
                    for (tr in msg.contentParts.filterIsInstance<AgentContentPart.ToolResult>()) {
                        val (callId, _) = splitResponsesAPIIds(tr.id)
                        input.put(JSONObject().apply {
                            put("type", "function_call_output")
                            put("call_id", capResponsesId(callId))
                            put("output", tr.content)
                        })
                        // Responses function_call_output is also textual;
                        // attach tool-returned pixels as a following user
                        // content item carrying input_image.
                        val trBytes = tr.imageData
                        if (trBytes != null && trBytes.isNotEmpty() && supportsImages) {
                            imageTurns.add(JSONObject().apply {
                                put("role", "user")
                                put("content", JSONArray().apply {
                                    put(JSONObject().apply {
                                        put("type", "input_text")
                                        put("text", "[Image returned by ${tr.name}]")
                                    })
                                    put(
                                        responsesImageBlock(
                                            trBytes,
                                            tr.imageMimeType ?: "image/jpeg",
                                            supportsImages,
                                            null,
                                        ),
                                    )
                                })
                            })
                        }
                    }
                    imageTurns.forEach { input.put(it) }
                    // T132: emit text + input_image content for the user
                    // turn so vision-capable Responses-API models actually
                    // see the bytes. Without the input_image branch the
                    // outer `content` was a flat concatenated string and
                    // image bytes never reached the wire (the textual
                    // [attached image: …] caption was the only hint, and
                    // the model fell back to read_image / shell_execute
                    // groping for a path it could see). Mirrors iOS
                    // convertMessagesResponsesAPI's image handling.
                    val textParts = msg.contentParts.filterIsInstance<AgentContentPart.Text>()
                    val imgParts = msg.contentParts.filterIsInstance<AgentContentPart.ImageData>()
                    if (imgParts.isNotEmpty()) {
                        val contentArray = JSONArray()
                        for (part in msg.contentParts) {
                            when (part) {
                                is AgentContentPart.Text -> {
                                    if (part.text.isNotEmpty()) {
                                        contentArray.put(JSONObject().apply {
                                            put("type", "input_text")
                                            put("text", part.text)
                                        })
                                    }
                                }
                                is AgentContentPart.ImageData -> {
                                    if (supportsImages) {
                                        // T-imgsize: backstop for Responses API path.
                                        val safeBytes = com.openminis.app.provider.ImageBudget.compressUnderBudget(part.data)
                                        val safeMime = if (safeBytes === part.data) part.mimeType else "image/jpeg"
                                        val b64 = Base64.encodeToString(safeBytes, Base64.NO_WRAP)
                                        contentArray.put(JSONObject().apply {
                                            put("type", "input_image")
                                            // Responses API takes image_url
                                            // as a *string*, not the
                                            // {"url":...} object shape used
                                            // by Chat Completions.
                                            put("image_url", "data:$safeMime;base64,$b64")
                                        })
                                    } else {
                                        // T264: target model has no vision modality —
                                        // emit a text placeholder. [T-android-vision-group
                                        // / GH#182] Vision-Group read_image hint when
                                        // seeded (carries the path); else the historical
                                        // literal. Note: Responses API uses "input_text"
                                        // type (vs "text" on Chat Completions).
                                        contentArray.put(JSONObject().apply {
                                            put("type", "input_text")
                                            put("text", part.noVisionPlaceholder
                                                ?: "[Image attached but this model does not support vision input]")
                                        })
                                    }
                                }
                                // [T-android-responses-toplevel-images]
                                // ToolUse/ToolResult are handled above for
                                // this role and legitimately don't belong
                                // in the content array. Anything else is a
                                // part type this converter has never been
                                // taught to encode — the failure mode being
                                // fixed here (content dropped with no error
                                // and no log) is exactly what that produces,
                                // so make it visible rather than silent.
                                is AgentContentPart.ToolUse,
                                is AgentContentPart.ToolResult -> Unit
                                else -> com.openminis.app.logging.AppLogger.error(
                                    "OpenAIProvider",
                                    "[responses] DROPPED unconvertible content part " +
                                        "${part.javaClass.simpleName} on role=user — it will NOT " +
                                        "reach the model. Add an encoding branch for it.",
                                )
                            }
                        }
                        // [T-android-responses-toplevel-images] Top-level
                        // images belong on this same turn.
                        if (attachTopLevelImages) {
                            for (p in imageParts) {
                                contentArray.put(
                                    responsesImageBlock(p.data, p.mimeType, supportsImages, p.noVisionPlaceholder),
                                )
                            }
                        }
                        input.put(JSONObject().apply {
                            put("role", "user")
                            put("content", contentArray)
                        })
                    } else if (attachTopLevelImages) {
                        // [T-android-responses-toplevel-images] Structured
                        // message with no ImageData parts, but images were
                        // supplied top-level (minis-model-use / Vision
                        // Group). Previously this fell into the text-only
                        // branch below and the pixels vanished.
                        val contentArray = JSONArray()
                        val text = textParts.joinToString("") { it.text }
                        if (text.isNotEmpty()) {
                            contentArray.put(JSONObject().apply {
                                put("type", "input_text")
                                put("text", text)
                            })
                        }
                        for (p in imageParts) {
                            contentArray.put(
                                responsesImageBlock(p.data, p.mimeType, supportsImages, p.noVisionPlaceholder),
                            )
                        }
                        input.put(JSONObject().apply {
                            put("role", "user")
                            put("content", contentArray)
                        })
                    } else if (textParts.isNotEmpty()) {
                        input.put(JSONObject().apply {
                            put("role", "user")
                            put("content", textParts.joinToString("") { it.text })
                        })
                    }
                }
                else -> {
                    input.put(JSONObject().apply {
                        put("role", msg.role.value)
                        put("content", msg.content)
                    })
                }
            }
        } else if (msg.audioParts.isNotEmpty()) {
            // [GH#67] Legacy (non-contentParts) message carrying audio —
            // the minis-model-use path. The Responses API keeps the SAME
            // nested input_audio shape as Chat Completions ({data,
            // format}), unlike input_image which flattens image_url to a
            // string. Text rides along as input_text.
            val contentArray = JSONArray()
            for (audio in msg.audioParts) {
                contentArray.put(JSONObject().apply {
                    put("type", "input_audio")
                    put("input_audio", JSONObject().apply {
                        put("data", audio.base64Data)
                        put("format", audio.format)
                    })
                })
            }
            if (msg.content.isNotEmpty()) {
                contentArray.put(JSONObject().apply {
                    put("type", "input_text")
                    put("text", msg.content)
                })
            }
            // [T-android-responses-toplevel-images] An audio-carrying turn
            // can also carry images.
            if (attachTopLevelImages) {
                for (p in imageParts) {
                    contentArray.put(
                        responsesImageBlock(p.data, p.mimeType, supportsImages, p.noVisionPlaceholder),
                    )
                }
            }
            input.put(JSONObject().apply {
                put("role", msg.role.value)
                put("content", contentArray)
            })
        } else if (attachTopLevelImages) {
            // [T-android-responses-toplevel-images] THE reported bug's path.
            // A plain (contentParts-free) user message plus top-level
            // images — what VisionGroupResolver.describeOnce and
            // minis-model-use's image_url blocks produce. This builder had
            // no imageParts parameter at all, so the message was emitted as
            // a bare text string and the pixels never reached the wire. The
            // vision model then answered "no image was provided", with no
            // error anywhere to explain it.
            val contentArray = JSONArray()
            if (msg.content.isNotEmpty()) {
                contentArray.put(JSONObject().apply {
                    put("type", "input_text")
                    put("text", msg.content)
                })
            }
            for (p in imageParts) {
                contentArray.put(
                    responsesImageBlock(p.data, p.mimeType, supportsImages, p.noVisionPlaceholder),
                )
            }
            input.put(JSONObject().apply {
                put("role", msg.role.value)
                put("content", contentArray)
            })
        } else {
            input.put(JSONObject().apply {
                put("role", msg.role.value)
                put("content", msg.content)
            })
        }
    }
    body.put("input", input)

    // [T-android-model-use-passthrough-mode GH#72] Same verbatim merge as the
    // chat-completions builder. Skipped for Codex OAuth inside mergeChatExtraBody.
    mergeChatExtraBody(body)

    return body
}

/**
 * Combine Responses-API call_id + item_id into a single string the agent loop
 * can carry through tool_use/tool_result blocks. The next request splits it
 * back apart so the API sees the original ids verbatim.
 */
internal fun OpenAIProvider.combineResponsesAPIIds(callId: String, fcId: String): String =
    if (fcId.isEmpty()) callId else "$callId|$fcId"

internal fun OpenAIProvider.splitResponsesAPIIds(combined: String): Pair<String, String?> {
    val sep = combined.indexOf('|')
    return if (sep < 0) combined to null
    else combined.substring(0, sep) to combined.substring(sep + 1)
}

/** Responses-API ids must be ≤64 chars; truncate defensively to avoid 400s. */
internal fun OpenAIProvider.capResponsesId(id: String): String =
    if (id.length <= 64) id else id.substring(0, 64)

/**
 * [T-android-tool-call-id-too-long] Chat-Completions `tool_calls[].id` /
 * `tool_call_id` must be ≤64 chars — OpenAI-compatible endpoints reject longer
 * ids with a 400 ("string too long. Expected a string with maximum length 64").
 * Two ways an id gets over 64 here:
 *   - a Responses-origin history turn stores the combined "call_…|fc_…" id
 *     (splitResponsesAPIIds' form) which is replayed verbatim on a Chat
 *     Completions request;
 *   - memory-tool / synthetic ids that are long by construction.
 * We keep only the call_-id half (before any '|') and, if still >64, replace
 * it with a deterministic SHA-256-derived id. Determinism matters: the SAME
 * raw id must map to the SAME capped id so the assistant tool_call and its
 * matching tool result still pair up (a mismatch is its own 400). The
 * downstream dedupe pass then guarantees uniqueness within the request.
 */
internal fun OpenAIProvider.capChatToolCallId(id: String): String {
    val callHalf = id.substringBefore('|')
    if (callHalf.length <= 64) return callHalf
    val hex = Sha256.hex(callHalf)
    // "call_" + 56 hex chars = 61 chars, safely under 64 and clearly a call id.
    return "call_${hex.take(56)}"
}

/**
 * Derives a stable per-conversation `prompt_cache_key` for the Responses
 * API. Mirrors iOS derivePromptCacheKey (OpenAIAgentProvider.swift:515).
 * The first user message is re-sent verbatim on every turn → its hash is
 * stable across turns within the same chat, distinct between chats.
 * Falls back to a random UUID when there is no user text yet (first
 * turn with attachments-only input, etc.).
 */
internal fun OpenAIProvider.derivePromptCacheKey(messages: List<LLMMessage>): String {
    for (msg in messages) {
        if (msg.role != LLMMessage.Role.USER) continue
        val text = msg.contentParts
            .filterIsInstance<AgentContentPart.Text>()
            .joinToString("") { it.text }
            .ifEmpty { msg.content }
        if (text.isNotEmpty()) {
            return "minis-${Sha256.hex(text).take(32)}"
        }
    }
    return "minis-${java.util.UUID.randomUUID().toString().lowercase()}"
}

/**
 * Map ThinkingLevel → Responses API `reasoning.effort` string. Mirrors
 * iOS reasoningEffort(for:level:) (OpenAIAgentProvider.swift:531).
 * Returns null when the level is OFF — caller decides whether to omit
 * the `reasoning` field entirely or fall back to "low" (Codex requires it).
 */
internal fun OpenAIProvider.mapThinkingLevelToResponsesEffort(level: ThinkingLevel): String? = when (level) {
    ThinkingLevel.OFF -> null
    ThinkingLevel.LOW -> "low"
    ThinkingLevel.MEDIUM -> "medium"
    ThinkingLevel.HIGH -> "high"
    ThinkingLevel.XHIGH -> "xhigh"
    // [T-android-thinking-level-arch] MAX → "max"; ULTRA also → "max" —
    // the Responses/Codex endpoint rejects a literal "ultra"; ultra is a
    // client-side "Max + orchestration" concept only (mirrors iOS).
    ThinkingLevel.MAX, ThinkingLevel.ULTRA -> "max"
}

/** Parse usage from Responses API format. */
internal fun OpenAIProvider.parseResponsesAPIUsage(usage: JSONObject): LLMUsage {
    val inputTokens = usage.optInt("input_tokens", 0)
    // Responses API reports cache hits at `input_tokens_details.cached_tokens`
    // (distinct from Chat Completions' `prompt_tokens_details.cached_tokens`).
    // Mirrors iOS OpenAIProvider.swift:640. Without this, even a perfectly
    // cached Responses-API request showed cacheRead=0 in usage stats —
    // making T126's prompt_cache_key wiring look like it had no effect.
    val cacheRead = usage.optJSONObject("input_tokens_details")
        ?.optInt("cached_tokens")?.takeIf { it > 0 }
    // `input_tokens` is the FULL input (cached subset included); subtract the
    // cached portion so `inputTokens` is fresh-only, matching Anthropic — else
    // the cache is counted twice in `input + cacheRead` (deflates hit rate).
    // Guard: only subtract when non-negative; no cache field → unchanged.
    // latestContextTokens stays the full input (that IS the context size).
    val freshInput = cacheRead?.let { (inputTokens - it).takeIf { d -> d >= 0 } } ?: inputTokens
    return LLMUsage(
        inputTokens = freshInput,
        outputTokens = usage.optInt("output_tokens", 0),
        cacheReadInputTokens = cacheRead,
        latestContextTokens = inputTokens,
    )
}

internal fun OpenAIProvider.mapHttpError(statusCode: Int, body: String): LLMError {
    if (statusCode == 401 || statusCode == 403) return LLMError.InvalidApiKey()
    if (statusCode == 429) return LLMError.RateLimited()

    val message = try {
        val json = JSONObject(body)
        val error = json.optJSONObject("error")
        val errorMessage = error?.safeOptString("message", "") ?: body
        "[$statusCode] $errorMessage"
    } catch (_: Exception) {
        "HTTP $statusCode: ${body.take(500)}"
    }

    val transientCodes = setOf(500, 502, 503, 504, 529)
    if (statusCode in transientCodes) {
        // 503 with permanent failure indicators → ProviderError (trigger slot fallback)
        if (statusCode == 503 && (body.contains("no_available_providers") || body.contains("model_not_found"))) {
            return LLMError.ProviderError(message)
        }
        return LLMError.TransientError(message)
    }
    return LLMError.ProviderError(message)
}

internal fun OpenAIProvider.mapError(error: Throwable): LLMError {
    if (error is LLMError) return error
    if (error is java.io.IOException) return LLMError.NetworkError(error)
    return LLMError.Unknown(error)
}
