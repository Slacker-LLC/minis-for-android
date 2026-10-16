package com.openminis.app.voicecall.realtime

enum class RealtimeProtocol { OPENAI_STYLE, GEMINI_LIVE }

enum class RealtimeAuth { BEARER_HEADER, KEY_IN_QUERY }

/**
 * 一家全双工实时语音厂商。接入新厂商 = 加一份档案；用户只需要填 API key
 * （以及档案要求的额外项，如千问的业务空间 ID）。
 *
 * [urlTemplate] 的占位符：`{model}`、`{key}`（[RealtimeAuth.KEY_IN_QUERY] 时）、`{workspace}`。
 * [docsChecked] 为 true 表示协议字段已对照官方文档核对过；false 的档案只是按同族协议推定，
 * 界面上要标明「未验证」。
 */
data class RealtimeProfile(
    val id: String,
    val label: String,
    val protocol: RealtimeProtocol,
    val urlTemplate: String,
    val auth: RealtimeAuth,
    val defaultModel: String,
    val defaultVoice: String?,
    val inputSampleRate: Int,
    val outputSampleRate: Int,
    val dialect: OpenAiStyleCodec.Dialect? = null,
    /** 模型是否支持函数调用；不支持时由我们接管回合（见 [RealtimeSessionConfig.delegateByTool]）。 */
    val supportsTools: Boolean = true,
    val needsWorkspace: Boolean = false,
    /** URL 完全由用户填写（自定义 OpenAI 兼容端点）。 */
    val customUrl: Boolean = false,
    val docsChecked: Boolean = true,
) {
    fun newCodec(): RealtimeCodec = when (protocol) {
        RealtimeProtocol.OPENAI_STYLE ->
            OpenAiStyleCodec(requireNotNull(dialect) { "OpenAI-style profile $id needs a dialect" }, inputSampleRate, outputSampleRate)
        RealtimeProtocol.GEMINI_LIVE -> GeminiLiveCodec(inputSampleRate, outputSampleRate)
    }
}

/** 用户为某个档案填的设置。 */
data class RealtimeUserConfig(
    val profileId: String,
    val apiKey: String,
    val model: String = "",
    val voice: String = "",
    val workspace: String = "",
    /** 仅自定义档案：完整的 wss 地址（可含 `{model}`）。 */
    val customUrl: String = "",
)

/** 连接所需的全部信息：地址、请求头、协议编解码器。 */
data class RealtimeConnection(
    val url: String,
    val headers: Map<String, String>,
    val model: String,
    val voice: String?,
    val profile: RealtimeProfile,
)

object RealtimeProfiles {
    val OPENAI = RealtimeProfile(
        id = "openai", label = "OpenAI Realtime",
        protocol = RealtimeProtocol.OPENAI_STYLE,
        urlTemplate = "wss://api.openai.com/v1/realtime?model={model}",
        auth = RealtimeAuth.BEARER_HEADER,
        defaultModel = "gpt-realtime-2.1", defaultVoice = "marin",
        inputSampleRate = 24_000, outputSampleRate = 24_000,
        dialect = OpenAiStyleCodec.Dialect(gaSessionShape = true, transcriptionModel = "gpt-4o-transcribe"),
    )

    val GEMINI = RealtimeProfile(
        id = "gemini", label = "Google Gemini Live",
        protocol = RealtimeProtocol.GEMINI_LIVE,
        urlTemplate = "wss://generativelanguage.googleapis.com/ws/google.ai.generativelanguage.v1beta.GenerativeService.BidiGenerateContent?key={key}",
        auth = RealtimeAuth.KEY_IN_QUERY,
        defaultModel = "gemini-3.8-live", defaultVoice = null,
        inputSampleRate = 16_000, outputSampleRate = 24_000,
    )

    val QWEN = RealtimeProfile(
        id = "qwen", label = "Alibaba Qwen Omni-Realtime",
        protocol = RealtimeProtocol.OPENAI_STYLE,
        urlTemplate = "wss://{workspace}.cn-beijing.maas.aliyuncs.com/api-ws/v1/realtime?model={model}",
        auth = RealtimeAuth.BEARER_HEADER,
        defaultModel = "qwen3.8-omni-flash-realtime", defaultVoice = "longanlingxin",
        inputSampleRate = 16_000, outputSampleRate = 24_000,
        dialect = OpenAiStyleCodec.Dialect(gaSessionShape = false, transcriptionModel = "qwen3-asr-flash-realtime"),
        needsWorkspace = true,
    )

    /** 任何遵循 OpenAI Realtime 协议的端点：填地址、key、模型即可。 */
    val CUSTOM_OPENAI = RealtimeProfile(
        id = "custom_openai", label = "Custom (OpenAI Realtime protocol)",
        protocol = RealtimeProtocol.OPENAI_STYLE,
        urlTemplate = "",
        auth = RealtimeAuth.BEARER_HEADER,
        defaultModel = "", defaultVoice = null,
        inputSampleRate = 24_000, outputSampleRate = 24_000,
        dialect = OpenAiStyleCodec.Dialect(gaSessionShape = true, transcriptionModel = null),
        customUrl = true, docsChecked = false,
    )

    val ALL: List<RealtimeProfile> = listOf(OPENAI, GEMINI, QWEN, CUSTOM_OPENAI)

    fun byId(id: String?): RealtimeProfile? = ALL.firstOrNull { it.id == id }

    /** 缺什么就返回什么（给界面提示用）；齐了返回 null。 */
    fun missing(config: RealtimeUserConfig): String? {
        val profile = byId(config.profileId) ?: return "profile"
        if (config.apiKey.isBlank()) return "apiKey"
        if (profile.needsWorkspace && config.workspace.isBlank()) return "workspace"
        if (profile.customUrl && config.customUrl.isBlank()) return "customUrl"
        if (profile.customUrl && config.model.isBlank()) return "model"
        return null
    }

    /** 由用户设置拼出连接信息；设置不全返回 null。 */
    fun connection(config: RealtimeUserConfig): RealtimeConnection? {
        if (missing(config) != null) return null
        val profile = byId(config.profileId) ?: return null
        val model = config.model.ifBlank { profile.defaultModel }
        val template = if (profile.customUrl) config.customUrl.trim() else profile.urlTemplate
        val url = template
            .replace("{model}", java.net.URLEncoder.encode(model, "UTF-8"))
            .replace("{key}", java.net.URLEncoder.encode(config.apiKey.trim(), "UTF-8"))
            .replace("{workspace}", config.workspace.trim())
        // API key 会随连接发出，只接受加密连接。
        if (!url.startsWith("wss://")) return null
        val headers = if (profile.auth == RealtimeAuth.BEARER_HEADER) mapOf("Authorization" to "Bearer ${config.apiKey.trim()}") else emptyMap()
        return RealtimeConnection(url, headers, model, config.voice.ifBlank { profile.defaultVoice.orEmpty() }.ifBlank { null }, profile)
    }
}
