package com.openminis.app.voicecall.realtime

/**
 * 全双工实时语音模型的协议层（纯逻辑，不碰网络和音频）。
 *
 * 结构对齐 Codex 语音模式（openai/codex，codex-rs）：实时模型只当「对话界面」，需要干活时
 * 交接给后端 Agent，后端输出按预算截断后回传，由实时模型简短转述
 * （`core/src/realtime_conversation.rs`、`prompts/templates/realtime/backend_prompt.md`）。
 * 各家协议的差异（事件名、字段、采样率）都收在 [RealtimeCodec] 的实现和 [RealtimeProfile] 里，
 * 接入新厂商 = 加一份档案，用户只填 API key。
 */

/** 交接给后端 Agent 的函数名。实时模型遇到要执行的事就调用它。 */
const val DELEGATE_TOOL_NAME = "delegate_to_agent"

/** 后端输出回传给实时模型前的字符预算（对应 Codex 的 1000 token 输出预算）。 */
const val BACKEND_OUTPUT_MAX_CHARS = 2_000

/** 后端文本进入实时对话时的前缀，和 Codex 的 `[BACKEND] ` 一致，方便模型区分用户话和后端话。 */
const val BACKEND_PREFIX = "[BACKEND] "

data class RealtimeSessionConfig(
    val model: String,
    val instructions: String,
    val voice: String?,
    /**
     * true：模型通过 [DELEGATE_TOOL_NAME] 函数调用交接（模型自己决定何时交接）。
     * false：模型不支持函数调用，由我们接管回合——不让它自动回复，用户说完后把转写交给 Agent，
     * 结果作为 [BACKEND_PREFIX] 条目注入再让模型念（对应 Codex 的 `codex_responses_as_items`）。
     */
    val delegateByTool: Boolean,
)

sealed interface RealtimeEvent {
    /** 服务端已接受会话配置，可以开始送音频。 */
    data object Ready : RealtimeEvent

    class AudioOut(val pcm: ByteArray) : RealtimeEvent

    /** 用户说的话（识别完成的一整句）。 */
    data class UserTranscript(val text: String) : RealtimeEvent

    /** 模型说的话。[final] 为 true 时是这一轮完整的转写。 */
    data class AssistantText(val text: String, val final: Boolean) : RealtimeEvent

    /** 用户开口了（打断）：应立刻停掉正在播放的音频。 */
    data object UserSpeechStarted : RealtimeEvent

    /** 模型这一轮说完了。 */
    data object ResponseDone : RealtimeEvent

    data class ToolCall(val callId: String, val name: String, val argsJson: String) : RealtimeEvent

    data class Failure(val message: String) : RealtimeEvent
}

interface RealtimeCodec {
    /** 送给服务端的音频采样率（16 位单声道小端 PCM）。 */
    val inputSampleRate: Int

    /** 服务端返回音频的采样率。 */
    val outputSampleRate: Int

    /** 连上之后立即要发的消息（会话配置）。 */
    fun open(config: RealtimeSessionConfig): List<String>

    fun audioIn(pcm: ByteArray): String

    fun parse(frame: String): List<RealtimeEvent>

    /** 把函数调用结果交回去，并让模型继续说话。 */
    fun toolResult(callId: String, name: String, output: String): List<String>

    /** 不支持函数调用时：把后端文本作为条目注入并让模型念。 */
    fun backendItem(text: String): List<String>

    /** 取消正在进行的回复（用户明确打断/挂断前）。 */
    fun cancelResponse(): List<String>

    /**
     * 让模型直接念出一句面向用户的话（权限询问、任务播报）。返回 null 表示该协议没有这种事件，
     * 调用方改用 [backendItem] 注入带说明的条目。
     */
    fun speak(text: String): List<String>? = null

    /** 麦克风停送/恢复音频时要发的保活事件（豆包要求，其余协议不需要）。 */
    fun muteSignal(muted: Boolean): List<String> = emptyList()

    /** 挂断前要发的收尾事件（豆包要求先 `session.close` 再断开）。 */
    fun closeSession(): List<String> = emptyList()
}

/** 回传给实时模型的后端输出：加前缀并按预算截断。 */
fun backendOutput(text: String): String =
    (BACKEND_PREFIX + text.trim()).let { if (it.length <= BACKEND_OUTPUT_MAX_CHARS) it else it.take(BACKEND_OUTPUT_MAX_CHARS - 1) + "…" }

/** 交接函数的 JSON Schema（两种协议共用）。 */
internal const val DELEGATE_TOOL_DESCRIPTION =
    "Hand the user's request to the assistant backend that can actually do the work (use tools, read or change files, run tasks, look things up). " +
        "Call it for any request that needs action. Pass the user's request in their own words."

internal const val DELEGATE_TOOL_PARAM_DESCRIPTION = "What the user asked for, in their own words, with any needed context."
