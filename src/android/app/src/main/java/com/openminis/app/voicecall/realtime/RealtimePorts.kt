package com.openminis.app.voicecall.realtime

/** WebSocket 传输。回调可能来自任意线程，运行时负责切回自己的协程作用域。 */
interface RealtimeTransport {
    interface Listener {
        fun onOpen()
        fun onMessage(text: String)
        fun onClosed(reason: String?)
        fun onFailure(message: String)
    }

    fun connect(url: String, headers: Map<String, String>, listener: Listener)

    /** 入队发送；连接已断开返回 false。 */
    fun send(text: String): Boolean

    fun close()
}

/**
 * 全双工音频：麦克风持续采集（带系统回声消除），模型音频流式播放。
 * [onFrame] 在采集线程上调用，收到的是 16 位单声道小端 PCM。
 */
interface RealtimeAudio {
    /** 开始采集和播放；麦克风不可用（无权限/被占用）返回 false。 */
    fun start(inputSampleRate: Int, outputSampleRate: Int, onFrame: (ByteArray) -> Unit): Boolean

    fun play(pcm: ByteArray)

    /** 丢弃所有还没播出去的音频（用户插话/打断/静音）。 */
    fun flush()

    fun stop()
}

object RealtimeLimits {
    const val CONNECT_TIMEOUT_MS = 15_000L

    /** 交给后端的请求上限，对应 Codex 的 4 KB 委托输入上限（`core/src/context/realtime_delegation.rs`）。 */
    const val DELEGATION_INPUT_MAX_CHARS = 4_000

    /** 会话建立后连续收到这么多次服务端错误就挂断。 */
    const val MAX_SERVER_ERRORS = 3
}
