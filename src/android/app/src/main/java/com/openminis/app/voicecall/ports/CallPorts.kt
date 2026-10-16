package com.openminis.app.voicecall.ports

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * 语音对话端口。生产实现复用主工程的识别、Agent 回合与朗读。
 *
 * UI/CallController 不直接访问 Android 音频、网络或 Provider；生产实现包装
 * 主工程现有能力，端到端验收可替换音频边界：
 *
 *  - [CallSpeechInput]  → SpeechRecognitionManager（系统/Provider ASR）
 *  - [CallModelGateway] → 当前会话 AgentSession / AgentTurnHandle
 *  - [CallSpeechOutput] → ReadAloudPlayer（在线 VoiceProvider / 系统 TTS 回退）
 */

/** ASR 错误类别（与 SpeechRecognitionEngine 的错误语义对齐的子集）。 */
enum class RecognitionError { NO_MATCH, PERMISSION_DENIED, NETWORK, ENGINE, CANCELLED }

interface CallSpeechInput {
    /** 开始一轮聆听。partial 供 UI 预览，final 才触发模型请求。 */
    fun start(onPartial: (String) -> Unit, onFinal: (String) -> Unit, onError: (RecognitionError, String?) -> Unit)

    /** 停止当前聆听；已获得的 final 结果应立即回调（半双工切换用）。 */
    fun stop()

    /** 取消当前聆听，不产生任何回调。 */
    fun cancel()
}

interface CallModelGateway {
    /** 同步等待回答；返回前不应返回空文本（失败走 Result.failure）。 */
    suspend fun ask(text: String): Result<String>

    /** 用户明确打断：只取消该通话拥有的 Agent 回合。结束等待本身不取消任务。 */
    fun cancel()
}

interface CallSpeechOutput {
    /** 播放回答文本；返回时表示播放完成或已失败（由内部回退系统 TTS）。 */
    suspend fun speak(text: String)

    /** 停止当前播放（静音/打断/挂断）。 */
    fun stop()
}

/** 口头回答权限请求的结果。拒绝一律按「本次会话拒绝」处理，和屏幕上的对话框一致。 */
enum class ApprovalDecision { ALLOW_ONCE, ALLOW_SESSION, DENY }

/** 一条待回答的权限请求：[question] 通话里念出来，[unclear] 是没听懂回答时的追问。 */
data class ApprovalPrompt(val question: String, val unclear: String)

interface CallApprovals {
    /** 当前通话所属会话里正在等用户回答的权限请求；没有则为 null。 */
    val pending: StateFlow<ApprovalPrompt?>

    fun respond(decision: ApprovalDecision)
}

object NoCallApprovals : CallApprovals {
    override val pending: StateFlow<ApprovalPrompt?> = MutableStateFlow(null)
    override fun respond(decision: ApprovalDecision) = Unit
}
