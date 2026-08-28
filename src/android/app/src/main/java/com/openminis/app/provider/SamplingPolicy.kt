package com.openminis.app.provider

import com.openminis.app.data.model.LLMModel
import com.openminis.app.data.model.ThinkingLevel

/**
 * Provider/model-specific sampling rules sourced from first-party API contracts.
 *
 * Session overrides are intentionally sparse and provider-agnostic. This policy
 * is the last gate before values reach a provider request body, so unsupported
 * parameters are omitted rather than relying on a vendor to reject them.
 */
data class SamplingWireParams(
    val temperature: Double? = null,
    val topP: Double? = null,
    val topK: Int? = null,
)

object SamplingPolicy {
    /**
     * Anthropic Messages API:
     * - legacy sampling ranges: temperature 0..1, top_p 0..1, top_k >= 0;
     * - models released after Claude Opus 4.6 deprecate custom sampling values.
     *
     * Opus 4.6 is the documented cutoff itself. Other Claude 4.6 family models
     * were released after that cutoff, so only Opus 4.6 keeps the legacy path.
     * Thinking requests leave sampling to Anthropic's thinking contract.
     */
    fun anthropic(
        modelId: String,
        thinkingEnabled: Boolean,
        temperature: Double?,
        topP: Double?,
        topK: Int?,
    ): SamplingWireParams {
        if (thinkingEnabled || isClaudeReleasedAfterOpus46(modelId)) {
            return SamplingWireParams()
        }
        return SamplingWireParams(
            temperature = temperature?.takeIf { it.isFinite() && it in 0.0..1.0 },
            topP = topP?.takeIf { it.isFinite() && it in 0.0..1.0 },
            topK = topK?.takeIf { it >= 0 },
        )
    }

    /**
     * Gemini GenerateContent API:
     * - Gemini 3.x official guidance says to remove temperature/top_p/top_k and
     *   use model defaults; 3.6+ makes that deprecation part of the API contract.
     * - older models use the official Model resource for maxTemperature and
     *   whether topK is legal. An empty Model.topK means topK MUST NOT be sent.
     */
    fun gemini(
        model: LLMModel,
        temperature: Double?,
        topP: Double?,
        topK: Int?,
    ): SamplingWireParams {
        if (isGemini3x(model.id)) return SamplingWireParams()

        val maxTemperature = model.samplingMaxTemperature ?: 2.0
        val safeTemperature = temperature?.takeIf {
            it.isFinite() && maxTemperature.isFinite() && it >= 0.0 && it <= maxTemperature
        }
        val safeTopP = topP?.takeIf { it.isFinite() && it in 0.0..1.0 }
        val safeTopK = topK?.takeIf { value ->
            value >= 0 &&
                model.samplingMetadataKnown &&
                model.samplingDefaultTopK != null
        }
        return SamplingWireParams(safeTemperature, safeTopP, safeTopK)
    }

    /**
     * OpenAI public API supports temperature 0..2 and top_p 0..1, but not top_k.
     * We only apply these first-party rules to api.openai.com. Generic
     * OpenAI-compatible relays must opt into their own vendor policy instead of
     * inheriting OpenAI parameters by accident.
     *
     * GPT-5.1/5.2 only accept temperature/top_p when reasoning effort is none;
     * older GPT-5 and o-series/Codex families are fail-closed here because their
     * official model contracts do not generally accept these sampling knobs.
     */
    fun openAI(
        modelId: String,
        firstParty: Boolean,
        thinkingLevel: ThinkingLevel,
        temperature: Double?,
        topP: Double?,
    ): SamplingWireParams {
        if (!firstParty || !openAIModelAllowsSampling(modelId, thinkingLevel)) {
            return SamplingWireParams()
        }
        return SamplingWireParams(
            temperature = temperature?.takeIf { it.isFinite() && it in 0.0..2.0 },
            topP = topP?.takeIf { it.isFinite() && it in 0.0..1.0 },
            topK = null,
        )
    }

    internal fun isGemini3x(modelId: String): Boolean =
        modelId.lowercase().substringAfterLast('/').startsWith("gemini-3")

    internal fun isClaudeReleasedAfterOpus46(modelId: String): Boolean {
        val lower = modelId.lowercase()
        if (!lower.contains("claude")) return false
        val match = Regex("""[-/]?(\d+)(?:[-.](\d+))?(?:$|[^0-9])""").find(lower)
            ?: return false
        val major = match.groupValues[1].toIntOrNull() ?: return false
        val minor = match.groupValues[2].toIntOrNull() ?: 0
        return when {
            major > 4 -> true
            major < 4 -> false
            minor > 6 -> true
            minor < 6 -> false
            else -> !lower.contains("opus") // Opus 4.6 is the documented cutoff.
        }
    }

    internal fun openAIModelAllowsSampling(modelId: String, thinkingLevel: ThinkingLevel): Boolean {
        val id = modelId.lowercase().substringAfterLast('/')
        if (id.startsWith("gpt-5.1") || id.startsWith("gpt-5.2")) {
            return !thinkingLevel.isEnabled
        }
        if (
            id == "gpt-5" ||
            id.startsWith("gpt-5-") && !id.startsWith("gpt-5.1") && !id.startsWith("gpt-5.2") &&
                !id.startsWith("gpt-5.3") && !id.startsWith("gpt-5.4") &&
                !id.startsWith("gpt-5.5") && !id.startsWith("gpt-5.6") ||
            id.startsWith("gpt-5-mini") ||
            id.startsWith("gpt-5-nano") ||
            id.startsWith("o1") || id.startsWith("o3") || id.startsWith("o4") ||
            id.contains("codex")
        ) {
            return false
        }
        return true
    }
}
