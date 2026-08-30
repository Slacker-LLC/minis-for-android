package io.github.slackerllc.minis.voicecall.ports

/**
 * 语音通话三层端口（阶段 A 契约，见 验收与设计/语音功能子项目总览.md §6）。
 *
 * UI/CallController 不直接访问 Android 音频、网络或 Provider；生产实现包装
 * 主工程现有能力（阶段 B/C），测试使用 Fake：
 *
 *  - [CallSpeechInput]  → SpeechRecognitionManager（系统/Provider ASR）
 *  - [CallModelGateway] → PetChatEngine（MVP）/ Agent Gateway（后续）
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

    /** 取消当前请求；ask 的挂起应尽快以 failure(CancelledException) 返回。 */
    fun cancel()
}

interface CallSpeechOutput {
    /** 播放回答文本；返回时表示播放完成或已失败（由内部回退系统 TTS）。 */
    suspend fun speak(text: String)

    /** 停止当前播放（静音/打断/挂断）。 */
    fun stop()
}
