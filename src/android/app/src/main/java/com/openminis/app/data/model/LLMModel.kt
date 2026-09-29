package com.openminis.app.data.model

import kotlinx.serialization.Serializable

@Serializable
data class LLMModel(
    val id: String,
    val displayName: String,
    val provider: String,
    val contextWindow: Int? = null,
    val maxOutputTokens: Int? = null,
    val supportsReasoning: Boolean? = null,
    /**
     * [T-eta-hosted-web-search] Whether this entry may use the provider's own web search, which the
     * Responses request expresses as a `web_search` tool. Off unless the user asked for it: it
     * spends the provider's quota and lets the model search without going through this app's own
     * tools.
     */
    val hostedWebSearch: Boolean = false,
    val interleavedReasoningField: String? = null,
    // [T-reasoning-effort-data-driven] Effort tiers this model accepts, from the
    // models.dev `reasoning_options` entry of type `effort` (e.g. ["high","max"]
    // for zhipuai glm-5.2). Mirrors iOS LLMModel.reasoningEffortValues.
    //
    // Presence (non-null, non-empty) means "controlled by reasoning_effort" and
    // replaces the old hardcoded deepseek/glm/kimi/minimax skip list; the
    // contents are the ALLOWED tiers, which the request builder clamps onto
    // (the catalog's sets vary: ["low","medium","high"], ["high","max"], …).
    val reasoningEffortValues: List<String>? = null,
    // [OpenMinis#163] The catalog affirmatively declares NO effort tiers for
    // this model — it reasons, but takes no `reasoning_effort` parameter.
    // Mirrors iOS LLMModel.declaresNoEffortTiers.
    //
    // Distinct from `reasoningEffortValues == null`, which also covers "the
    // catalog has never heard of this model". Only the affirmative case may
    // suppress the field; the unknown case stays permissive so third-party
    // relays keep working.
    //
    // Nullable (not a plain Boolean) so decoding a model persisted before this
    // field existed yields null — "unknown", the pre-existing behaviour —
    // rather than a synthesized `false` that would read as a real answer.
    val declaresNoEffortTiers: Boolean? = null,
    // Input/output modalities from models.dev (e.g. "text", "image", "audio", "video", "pdf").
    // Mirrors iOS ModelModality flags. When null, treat as text-in/text-out only.
    val inputModalities: List<String>? = null,
    val outputModalities: List<String>? = null,
) {
    companion object {
        /**
         * Heuristic display-name formatter for API model ids.
         * Mirrors iOS `modelDisplayName(from:)`: splits on `/` and `-`, preserves a
         * small set of uppercase acronyms, applies brand-name capitalization for
         * well-known vendors (OpenAI, DeepSeek, etc.), and title-cases the rest.
         */
        fun modelDisplayName(fromId: String): String {
            if (fromId.isBlank()) return fromId
            val upperTokens = setOf(
                "gpt", "glm", "oss", "ai", "xl", "vl", "llm", "moe", "api",
                "hd", "sd", "rp", "sft", "rl", "dpo", "gguf", "fp16", "bf16", "int4", "int8",
            )
            val brandRewrites = mapOf(
                "openai" to "OpenAI",
                "deepseek" to "DeepSeek",
                "chatgpt" to "ChatGPT",
                "llama" to "Llama",
                "gemma" to "Gemma",
                "phi" to "Phi",
                "mistral" to "Mistral",
                "mixtral" to "Mixtral",
                "qwen" to "Qwen",
                "yi" to "Yi",
            )
            return fromId.split('/').joinToString(" / ") { segment ->
                segment.split('-').joinToString("-") { token ->
                    val lower = token.lowercase()
                    when {
                        brandRewrites.containsKey(lower) -> brandRewrites[lower]!!
                        upperTokens.contains(lower) -> lower.uppercase()
                        token.isEmpty() -> token
                        else -> token.replaceFirstChar { it.titlecase() }
                    }
                }
            }
        }
    }

    /**
     * [T-newchat-default-model-fallback-android] True when this model can
     * produce a TEXT reply — the only kind a fresh chat should default to.
     * Per the field's documented convention (outputModalities null ⇒ "text
     * out only"), a null/empty list counts as text. A non-empty list must
     * contain "text" (normalized) to qualify — this excludes pure
     * image/audio/video generators (e.g. an image-only model whose
     * outputModalities is ["image"]). Mirrors iOS #636 isTextOutput.
     */
    val isTextOutput: Boolean
        get() {
            val out = outputModalities.normalizeModalities() ?: return true
            return "text" in out
        }

    /**
     * Effective context window in tokens. When `contextWindow` is set (from
     * models.dev enrichment or the built-in catalog) use it; otherwise fall
     * back to a model-id heuristic. Mirrors iOS LLMModel.contextWindowTokens
     * (T-anthropic-context-window): the old "Claude → 200K" default wrongly
     * capped Sonnet 4.6 / Sonnet 5 / Opus 4.x / Fable 5, whose real window is
     * 1M — only Haiku and the legacy 2.x/3.x line are 200K.
     */
    val contextWindowTokens: Int
        get() {
            contextWindow?.let { if (it > 0) return it }
            com.openminis.app.provider.rules.ModelRulesProvider.capabilitiesFor(id).contextWindow?.takeIf { it > 0 }?.let { return it }
            val lid = id.lowercase()
            // Anthropic Claude — modern Opus/Sonnet 4.x & 5 and Fable/Mythos 5
            // ship 1M; Haiku and legacy 2.x/3.x are 200K.
            if (lid.contains("claude")) {
                if (lid.contains("haiku")) return 200_000
                if (lid.contains("claude-2") || lid.contains("claude-3")) return 200_000
                return 1_000_000
            }
            // Google Gemini — modern Gemini advertises 1M+; only 1.0 was 32K.
            if (lid.contains("gemini")) {
                if (lid.contains("1.0")) return 32_000
                return 1_000_000
            }
            // OpenAI family
            if (lid.contains("gpt-3.5")) return 16_000
            if (lid.contains("gpt-4o") || lid.contains("gpt-4-turbo")) return 128_000
            if (lid.contains("gpt-5")) return 400_000
            if (lid.contains("gpt-4")) return 8_000
            if (lid.contains("o3") || lid.contains("o4")) return 200_000
            if (lid.contains("codex")) return 200_000
            if (lid.contains("deepseek")) return 128_000
            // xAI Grok. [T-android-grok-context-underestimate] Without this
            // branch a Grok id missing from the models.dev catalog fell through
            // to the 128K default, and ContextPolicy turned that into
            // compactThreshold = 128K - 20K = 108K — so a model with a 256K-2M
            // window auto-compacted every ~20-30 tool calls. iOS field report
            // 2026-08-13: `grok-4.6` (still absent from the bundled catalog,
            // verified) compacted 6 times in 47 minutes.
            //
            // Grok 2/3 are the only 131K generation; Grok 4 and later are 256K
            // at minimum and the fast / 4.20 lines advertise 2M. 256K is the
            // conservative floor for an unknown Grok 4+. A handful of
            // relay-hosted grok-4 entries do declare 128K-200K, but every one
            // of them IS in the catalog, so the explicit `contextWindow` check
            // above wins and this heuristic never runs for them — it only ever
            // sees ids models.dev has not shipped yet, which is the whole
            // failure mode. Port of iOS d63e9b9c9.
            if (lid.contains("grok")) {
                if (lid.contains("grok-2") || lid.contains("grok-3")) return 131_072
                return 256_000
            }
            // Default: assume a modern long-context model rather than 64K so the
            // group context-limit slider doesn't collapse to a single stop.
            return 128_000
        }

    /**
     * Capability hint appended to the system prompt so the model knows exactly
     * what it can natively consume vs what it must route through shell tools.
     * Returns `null` for fully-multimodal models (no hint needed). Matches the
     * iOS `capabilityPromptFragment` wording so Android/iOS chats are identical
     * when routed through the same model.
     */
    fun capabilityPromptFragment(): String? {
        val inputs = inputModalities?.map { it.lowercase() } ?: emptyList()
        val hasImage = "image" in inputs
        val hasPdf = "pdf" in inputs
        val hasAudio = "audio" in inputs
        val hasVideo = "video" in inputs
        if (hasImage && hasPdf && hasAudio && hasVideo) return null

        val natives = buildList {
            if (hasImage) add("images")
            if (hasPdf) add("PDFs")
            if (hasAudio) add("audio")
            if (hasVideo) add("video")
        }
        val missing = buildList {
            if (!hasImage) add("images")
            if (!hasPdf) add("PDFs")
            if (!hasAudio) add("audio")
            if (!hasVideo) add("video")
        }

        val sb = StringBuilder()
        if (natives.isNotEmpty()) {
            sb.append("You can natively process ").append(natives.joinToString(", ")).append(". ")
        }
        if (missing.isNotEmpty()) {
            sb.append("You cannot natively process ").append(missing.joinToString(", "))
            sb.append(" — for those formats, call shell_execute with ffmpeg or similar tools to extract text/metadata first.")
        }
        return sb.toString().trim().ifEmpty { null }
    }

    /**
     * Per-model-family agent-loop behavior hint. Gemini needs a reminder to
     * actually invoke tools via function calling; OpenAI Codex family needs a
     * push toward autonomous persistence ("don't stop at analysis").
     */
    fun agentBehaviorPromptFragment(): String? {
        val idLower = id.lowercase()
        val providerLower = provider.lowercase()

        if (providerLower == "google" || idLower.contains("gemini")) {
            return "When you need to use a tool, invoke it via the function-calling mechanism directly. Do not emit tool invocations as plain text — they will not be executed."
        }

        val isCodex = idLower.contains("codex") ||
            Regex("gpt-5(?:\\.\\d+)?-codex").containsMatchIn(idLower)
        if (isCodex) {
            return "Act autonomously: don't stop at analysis, don't ask for permission on reversible local actions, and never announce \"I will use tool X\" without actually calling X. Keep iterating until the task is fully complete."
        }
        return null
    }
}

/**
 * Normalize a modality string to its bare form. Provider APIs vary —
 * OpenAI / OpenRouter return "image_input" / "text_output" with _input/_output
 * suffixes; models.dev returns bare "image" / "text". Internally we always
 * use the bare form ("image", "pdf", "audio", "video", "text") so toggles,
 * `"image" in modalities` checks, and capability fragments work uniformly.
 */
fun String.normalizeModalityName(): String =
    lowercase().removeSuffix("_input").removeSuffix("_output")

fun List<String>?.normalizeModalities(): List<String>? =
    this?.map { it.normalizeModalityName() }?.distinct()?.takeIf { it.isNotEmpty() }
