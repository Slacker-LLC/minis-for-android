from pathlib import Path
import re


def patch(path: str, old: str, new: str, label: str) -> None:
    p = Path(path)
    text = p.read_text()
    count = text.count(old)
    if count != 1:
        raise SystemExit(f"{label}: expected exactly one match in {path}, found {count}")
    p.write_text(text.replace(old, new, 1))


def replace_string(path: str, key: str, value: str) -> None:
    p = Path(path)
    text = p.read_text()
    pattern = re.compile(rf'(<string name="{re.escape(key)}">)(.*?)(</string>)')
    text2, count = pattern.subn(rf'\1{value}\3', text, count=1)
    if count != 1:
        raise SystemExit(f"string {key}: expected one match in {path}, found {count}")
    p.write_text(text2)


# 1) Preserve first-party Gemini /models sampling metadata on LLMModel.
patch(
    "src/android/app/src/main/java/com/openminis/app/data/model/LLMModel.kt",
    '''    val outputModalities: List<String>? = null,\n) {''',
    '''    val outputModalities: List<String>? = null,\n    // First-party Gemini Model resource sampling metadata. Nullable values keep\n    // non-Gemini providers and old cached model JSON backward-compatible. When\n    // samplingMetadataKnown=true, a null samplingDefaultTopK means Google's\n    // Model.topK was empty, which explicitly forbids sending topK.\n    val samplingDefaultTemperature: Double? = null,\n    val samplingMaxTemperature: Double? = null,\n    val samplingDefaultTopP: Double? = null,\n    val samplingDefaultTopK: Int? = null,\n    val samplingMetadataKnown: Boolean = false,\n) {''',
    "LLMModel sampling metadata",
)

# 2) Read the official Gemini Model resource instead of dropping its sampling fields.
patch(
    "src/android/app/src/main/java/com/openminis/app/provider/gemini/GeminiModelsApi.kt",
    '''                if (supportsGen) {\n                    result.add(LLMModel(name, displayName, "Google"))\n                }''',
    '''                if (supportsGen) {\n                    fun finiteDouble(key: String): Double? =\n                        if (obj.has(key) && !obj.isNull(key)) {\n                            obj.optDouble(key, Double.NaN).takeIf { it.isFinite() }\n                        } else null\n                    val topK = if (obj.has("topK") && !obj.isNull("topK")) {\n                        obj.optInt("topK", -1).takeIf { it >= 0 }\n                    } else null\n                    result.add(\n                        LLMModel(\n                            id = name,\n                            displayName = displayName,\n                            provider = "Google",\n                            samplingDefaultTemperature = finiteDouble("temperature"),\n                            samplingMaxTemperature = finiteDouble("maxTemperature"),\n                            samplingDefaultTopP = finiteDouble("topP"),\n                            samplingDefaultTopK = topK,\n                            samplingMetadataKnown = true,\n                        ),\n                    )\n                }''',
    "Gemini official sampling metadata",
)

# 3) Anthropic: sanitize all three sampling knobs against the official Claude contract.
patch(
    "src/android/app/src/main/java/com/openminis/app/provider/anthropic/AnthropicProvider.kt",
    '''import com.openminis.app.provider.LLMProvider\n''',
    '''import com.openminis.app.provider.LLMProvider\nimport com.openminis.app.provider.SamplingPolicy\n''',
    "Anthropic SamplingPolicy import",
)
patch(
    "src/android/app/src/main/java/com/openminis/app/provider/anthropic/AnthropicProvider.kt",
    '''        if (temperature != null && !thinkingLevel.isEnabled && !modelRejectsTemperature(model.id)) {\n            body.put("temperature", temperature)\n        }\n        // GH#32: keep extended-thinking requests conservative; sampling\n        // overrides are emitted only when thinking is off.\n        if (!thinkingLevel.isEnabled) {\n            topP?.let { body.put("top_p", it) }\n            topK?.let { body.put("top_k", it) }\n        }''',
    '''        val sampling = SamplingPolicy.anthropic(\n            modelId = model.id,\n            thinkingEnabled = thinkingLevel.isEnabled,\n            temperature = temperature,\n            topP = topP,\n            topK = topK,\n        )\n        sampling.temperature?.let { body.put("temperature", it) }\n        sampling.topP?.let { body.put("top_p", it) }\n        sampling.topK?.let { body.put("top_k", it) }''',
    "Anthropic official sampling gate",
)

# 4) Gemini: all sampling emission goes through model metadata + Gemini 3.x policy.
patch(
    "src/android/app/src/main/java/com/openminis/app/provider/gemini/GeminiProvider.kt",
    '''import com.openminis.app.provider.LLMProvider\n''',
    '''import com.openminis.app.provider.LLMProvider\nimport com.openminis.app.provider.SamplingPolicy\n''',
    "Gemini SamplingPolicy import",
)
patch(
    "src/android/app/src/main/java/com/openminis/app/provider/gemini/GeminiProvider.kt",
    '''        val config = JSONObject()\n        config.put("maxOutputTokens", maxTokens)\n        if (temperature != null) {\n            config.put("temperature", temperature)\n        }\n        topP?.let { config.put("topP", it) }\n        topK?.let { config.put("topK", it) }''',
    '''        val config = JSONObject()\n        config.put("maxOutputTokens", maxTokens)\n        val sampling = SamplingPolicy.gemini(model, temperature, topP, topK)\n        sampling.temperature?.let { config.put("temperature", it) }\n        sampling.topP?.let { config.put("topP", it) }\n        sampling.topK?.let { config.put("topK", it) }''',
    "Gemini official sampling gate",
)

# 5) OpenAI: first-party-only session sampling + wire it to both Chat and Responses.
patch(
    "src/android/app/src/main/java/com/openminis/app/provider/openai/OpenAIProvider.kt",
    '''import com.openminis.app.provider.LLMProvider\n''',
    '''import com.openminis.app.provider.LLMProvider\nimport com.openminis.app.provider.SamplingPolicy\n''',
    "OpenAI SamplingPolicy import",
)
patch(
    "src/android/app/src/main/java/com/openminis/app/provider/openai/OpenAIProvider.kt",
    '''    private val isXAI: Boolean = basePath.lowercase().let {\n        it.contains("api.x.ai") || it.contains("//x.ai")\n    }''',
    '''    private val isXAI: Boolean = basePath.lowercase().let {\n        it.contains("api.x.ai") || it.contains("//x.ai")\n    }\n\n    /** GH#32: only api.openai.com gets OpenAI's official sampling contract. */\n    private val isOfficialOpenAIEndpoint: Boolean\n        get() = !isOAuth && basePath.lowercase().trimEnd('/').let {\n            it == "https://api.openai.com" || it == "https://api.openai.com/v1"\n        }''',
    "OpenAI first-party endpoint predicate",
)
patch(
    "src/android/app/src/main/java/com/openminis/app/provider/openai/OpenAIProvider.kt",
    '''    override fun streamMessageSamplingClamped(\n        messages: List<LLMMessage>,\n        systemPrompt: String?,\n        maxTokens: Int,\n        temperature: Double?,\n        imageParts: List<LLMMessage.ImagePart>,\n        tools: List<AgentToolDefinition>,\n        thinkingLevel: ThinkingLevel,\n        topP: Double?,\n        topK: Int?,\n    ): Flow<LLMStreamChunk> = rawStreamMessage(\n        messages, systemPrompt, maxTokens, temperature, imageParts, tools, thinkingLevel,\n        topP = topP,\n    ).failOnSilentEmptyCompletion(name)''',
    '''    override fun streamMessageSamplingClamped(\n        messages: List<LLMMessage>,\n        systemPrompt: String?,\n        maxTokens: Int,\n        temperature: Double?,\n        imageParts: List<LLMMessage.ImagePart>,\n        tools: List<AgentToolDefinition>,\n        thinkingLevel: ThinkingLevel,\n        topP: Double?,\n        topK: Int?,\n    ): Flow<LLMStreamChunk> {\n        val sampling = SamplingPolicy.openAI(\n            modelId = model.id,\n            firstParty = isOfficialOpenAIEndpoint,\n            thinkingLevel = thinkingLevel,\n            temperature = temperature,\n            topP = topP,\n        )\n        return rawStreamMessage(\n            messages, systemPrompt, maxTokens, sampling.temperature, imageParts, tools, thinkingLevel,\n            topP = sampling.topP,\n        ).failOnSilentEmptyCompletion(name)\n    }''',
    "OpenAI official sampling gate",
)
patch(
    "src/android/app/src/main/java/com/openminis/app/provider/openai/OpenAIProvider.kt",
    '''            buildResponsesAPIBody(messages, systemPrompt, maxTokens, stream = true, imageParts = imageParts, tools = tools, thinkingLevel = thinkingLevel)''',
    '''            buildResponsesAPIBody(\n                messages, systemPrompt, maxTokens, stream = true,\n                temperature = temperature, topP = topP,\n                imageParts = imageParts, tools = tools, thinkingLevel = thinkingLevel,\n            )''',
    "OpenAI Responses sampling call",
)
patch(
    "src/android/app/src/main/java/com/openminis/app/provider/openai/OpenAIProvider.kt",
    '''        maxTokens: Int,\n        stream: Boolean,\n        /**\n         * [T-android-responses-toplevel-images] Images passed as the top-level''',
    '''        maxTokens: Int,\n        stream: Boolean,\n        temperature: Double? = null,\n        topP: Double? = null,\n        /**\n         * [T-android-responses-toplevel-images] Images passed as the top-level''',
    "OpenAI Responses builder sampling params",
)
patch(
    "src/android/app/src/main/java/com/openminis/app/provider/openai/OpenAIProvider.kt",
    '''        body.put("store", false)\n        body.put("parallel_tool_calls", true)\n        // Stable per-conversation cache key''',
    '''        body.put("store", false)\n        body.put("parallel_tool_calls", true)\n        // OpenAI Responses API supports the same sampling fields as Chat. Keep\n        // the Codex OAuth fingerprint unchanged; session sampling is first-party\n        // public API only and reaches this builder already capability-filtered.\n        if (!isOAuth) {\n            temperature?.let { body.put("temperature", it) }\n            topP?.let { body.put("top_p", it) }\n        }\n        // Stable per-conversation cache key''',
    "OpenAI Responses sampling body",
)

# 6) Correct the UI explanation: capability is model-specific, not provider-wide.
localized_help = {
    "values": "Sampling overrides follow each provider/model's official API. Unsupported or deprecated parameters are omitted; Gemini 3.x keeps model defaults.",
    "values-zh": "采样覆盖严格按各 Provider 和具体模型的官方 API 能力应用；不支持或已弃用的参数不会发送，Gemini 3.x 保持模型默认值。",
    "values-zh-rTW": "取樣覆蓋嚴格依各 Provider 與具體模型的官方 API 能力套用；不支援或已棄用的參數不會送出，Gemini 3.x 保持模型預設值。",
    "values-de": "Sampling-Overrides folgen der offiziellen API des jeweiligen Providers und Modells. Nicht unterstützte oder veraltete Parameter werden weggelassen; Gemini 3.x behält die Modellstandardwerte.",
    "values-fr": "Les réglages d’échantillonnage suivent l’API officielle de chaque fournisseur et modèle. Les paramètres non pris en charge ou obsolètes sont omis ; Gemini 3.x conserve les valeurs par défaut du modèle.",
    "values-ja": "サンプリングの上書きは各 Provider／モデルの公式 API に従います。未対応または非推奨のパラメータは送信せず、Gemini 3.x はモデル既定値を維持します。",
    "values-ko": "샘플링 재정의는 각 Provider와 모델의 공식 API를 따릅니다. 지원되지 않거나 더 이상 권장되지 않는 매개변수는 보내지 않으며 Gemini 3.x는 모델 기본값을 유지합니다.",
    "values-ru": "Переопределения сэмплирования применяются по официальному API конкретного провайдера и модели. Неподдерживаемые или устаревшие параметры не отправляются; Gemini 3.x использует значения модели по умолчанию.",
}
for folder, value in localized_help.items():
    replace_string(
        f"src/android/app/src/main/res/{folder}/strings_session_advanced.xml",
        "session_advanced_sampling_help",
        value,
    )

print("GH32 official sampling patch applied")
