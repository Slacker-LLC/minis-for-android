package com.openminis.app.data.model

import org.json.JSONObject

sealed class LLMStreamChunk {
    data object Started : LLMStreamChunk()
    data class Text(val text: String) : LLMStreamChunk()
    data class Usage(val usage: LLMUsage) : LLMStreamChunk()
    data class Finished(val stopReason: String?) : LLMStreamChunk()

    /** Thinking/reasoning streaming event */
    data class ThinkingDelta(val text: String) : LLMStreamChunk()

    /** Opaque accumulated reasoning content (DeepSeek/Kimi/QwQ `reasoning_content` field).
     *  Unlike ThinkingDelta (real-time increments), this is the full accumulated blob
     *  echoed back on subsequent turns to preserve the model's chain of thought. */
    data class ReasoningContent(val content: String) : LLMStreamChunk()

    /** Tool use streaming events */
    data class ToolUseStart(val id: String, val name: String) : LLMStreamChunk()
    data class ToolInputDelta(val id: String, val accumulated: String) : LLMStreamChunk()
    data class ToolCallComplete(
        val id: String,
        val name: String,
        val args: JSONObject,
        // [T-android-gemini3-thoughtsig / #179] Gemini 3.x returns a
        // `thoughtSignature` on each functionCall part; it MUST be replayed on
        // the historical functionCall or the next request 400s. Null for every
        // other provider (only Gemini populates it).
        val thoughtSignature: String? = null,
    ) : LLMStreamChunk()

    /**
     * [T-codex-gpt-image2-oauth-android] A model-generated media attachment
     * (e.g. an image from the Codex image_generation tool). Carried through the
     * stream so non-streaming callers (sendMessage → minis-model-use) can
     * collect it into LLMResponse.mediaAttachments. Image-output models are
     * one-shot, so this typically arrives once near the end of the stream.
     */
    data class MediaAttachment(val attachment: LLMMediaAttachment) : LLMStreamChunk()

    /**
     * [T-eta-responses-opaque-items] One COMPLETED Responses-API output item, kept
     * verbatim because the app cannot rebuild it — a `reasoning` item carries the
     * `encrypted_content` the ChatGPT backend needs to continue the same chain of
     * thought on the next request of a `store:false` conversation.
     *
     * The agent loop attaches these to the in-memory assistant message so the next
     * turn of the same run replays them; nothing persisted ever carries them (see
     * [LLMMessage.providerOutputItems]). Ported from Eta
     * `agent/model/ResponsesEphemeralState.kt` (Mangi-11/Eta @ c15de97);
     * attribution in THIRD_PARTY_LICENSES.md.
     */
    data class ProviderOutputItem(val json: String) : LLMStreamChunk()

    /**
     * [T-eta-hosted-web-search] A tool the **provider** ran on its own: the Responses API reports
     * these as `<kind>_call.<phase>` events and this app never executes them. The kind is a token
     * (`web_search`, `file_search`, …) rather than a sentence, because the row is labelled where the
     * language is known.
     *
     * Ported from Eta `agent/model/OpenAiResponsesProvider.kt` and its `ProviderEvent.HostedTool*`
     * (Mangi-11/Eta @ c15de97); attribution in THIRD_PARTY_LICENSES.md.
     */
    data class HostedToolActivity(
        val id: String,
        val kind: String,
        val finished: Boolean,
        val success: Boolean = true,
    ) : LLMStreamChunk()
}
