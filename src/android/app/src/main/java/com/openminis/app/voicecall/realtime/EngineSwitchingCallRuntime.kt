package com.openminis.app.voicecall.realtime

import com.openminis.app.voicecall.runtime.CallEvent
import com.openminis.app.voicecall.runtime.CallRuntime
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.launch

/**
 * 每次开始通话时选引擎：用户选了实时模型且设置完整就用 [realtime]，否则用经典流水线 [classic]。
 * 默认永远是经典流水线——实时路径没有设置完整（没提供商/没 key/提供商不适配）时安静回退，不会让通话打不起来。
 */
class EngineSwitchingCallRuntime(
    private val classic: CallRuntime,
    /** 设置完整返回新的实时运行时，否则返回 null。每次通话调用一次。 */
    private val realtime: () -> CallRuntime?,
    private val scope: CoroutineScope,
) : CallRuntime {
    private val _events = MutableSharedFlow<CallEvent>(extraBufferCapacity = 64)
    override val events: SharedFlow<CallEvent> = _events

    private var current: CallRuntime = classic
    private var forwarding: Job? = null

    override fun start() {
        // 上一次通话还没停（异常路径）才需要先停；已经 stop 过的不重复停。
        if (forwarding != null) stop()
        current = realtime() ?: classic
        // 必须先订阅再启动：start() 里同步发出的第一批事件不能丢。
        forwarding = scope.launch(start = CoroutineStart.UNDISPATCHED) { current.events.collect { _events.emit(it) } }
        current.start()
    }

    override fun stop() {
        current.stop()
        forwarding?.cancel()
        forwarding = null
    }

    override fun setMuted(muted: Boolean) = current.setMuted(muted)
    override fun interrupt() = current.interrupt()
    override fun listenForFollowUp() = current.listenForFollowUp()
    override fun sendText(text: String) = current.sendText(text)
}
