package com.openminis.app.speech

/**
 * 原生对象的“调用中不释放”闸门。
 *
 * VAD 的回调在原生调用栈内、同一线程上触发，回调里引擎会 stop() 检测器。
 * `synchronized` 可重入，拦不住同线程重入：若此时立刻 release()，原生对象在
 * 自己的方法还没返回时被释放，返回后再读已清空的成员 → SIGSEGV
 * （RealTimeCutVAD::algorithm，fault addr 0x38）。
 *
 * 用法：调用原生方法前后包 [enter]/[exit]；任何地方要释放就走 [release]。
 * 整个类须在同一把锁内使用（调用方负责），自身不加锁。
 */
internal class NativeReleaseGate<T : Any>(private val doRelease: (T) -> Unit) {
    private var nativeThread: Thread? = null
    private var deferred: T? = null

    fun enter() {
        nativeThread = Thread.currentThread()
    }

    /** 原生调用返回：把调用期间登记的释放补上。 */
    fun exit() {
        nativeThread = null
        deferred?.let { runCatching { doRelease(it) } }
        deferred = null
    }

    /** 当前线程正在原生调用里则延后，否则立即释放。 */
    fun release(target: T) {
        if (nativeThread === Thread.currentThread()) deferred = target
        else runCatching { doRelease(target) }
    }
}
