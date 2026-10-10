package com.openminis.app.tools.android.vscreen

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.ParcelFileDescriptor
import com.openminis.app.offload.RootProcess
import com.openminis.app.tools.android.vscreen.service.VirtualScreenRootService
import com.topjohnwu.superuser.ipc.RootService
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutionException
import java.util.concurrent.Future
import java.util.concurrent.ThreadFactory
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicInteger

class VirtualScreenClient(context: Context) : AutoCloseable {
    private val appContext = context.applicationContext
    private val preferences = VirtualScreenPreferences(appContext)
    private val stateLock = Any()
    private val worker = ThreadPoolExecutor(
        1, 4, 30L, TimeUnit.SECONDS, ArrayBlockingQueue(16),
        ThreadFactory { task -> Thread(task, "Minis-VScreen-RPC-${THREAD_IDS.incrementAndGet()}").apply { isDaemon = true } },
        ThreadPoolExecutor.AbortPolicy(),
    )
    private var remote: IVirtualScreenService? = null
    private var binding = false
    private var connectionLatch = CountDownLatch(1)
    private var activeDisplayId: Int? = null
    private var activeDisplaySize: Pair<Int, Int>? = null
    private var activePackageName: String? = null
    private var displayLostPending = false
    private var reconnectAttempts = 0
    private var reconnectInFlight = false
    private var closed = false
    private var frameSink: IVirtualScreenFrameSink? = null

    /**
     * The root service, in libsu daemon mode: its process, and the virtual display it holds, outlive
     * this app process; libsu ends it when the app is updated or uninstalled.
     */
    private val serviceIntent = Intent(appContext, VirtualScreenRootService::class.java)
        .addCategory(RootService.CATEGORY_DAEMON_MODE)
    private val mainHandler = Handler(Looper.getMainLooper())
    @Volatile private var bindError: Throwable? = null

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, binder: IBinder) {
            synchronized(stateLock) {
                remote = IVirtualScreenService.Stub.asInterface(binder)
                binding = true
                if (!displayLostPending) reconnectAttempts = 0
                connectionLatch.countDown()
            }
        }

        override fun onServiceDisconnected(name: ComponentName) = onServiceLost()
        override fun onBindingDied(name: ComponentName) = onServiceLost()
        override fun onNullBinding(name: ComponentName) = onServiceLost()
    }

    val displayId: Int? get() = synchronized(stateLock) { activeDisplayId }
    val foregroundPackageName: String? get() = synchronized(stateLock) { activePackageName }
    fun displaySize(): Pair<Int, Int>? {
        synchronized(stateLock) { activeDisplaySize }?.let { return it }
        // The display may have been opened before this process started (the service keeps it alive).
        return runCatching { displayInfo() }.getOrNull()?.let { it.width to it.height }
    }

    data class DisplayInfo(val id: Int, val width: Int, val height: Int, val dpi: Int)

    /** The display the service is holding right now, read from the service itself; null when none. */
    fun displayInfo(): DisplayInfo? {
        if (!isEnabled()) return null
        val raw = execute(DEFAULT_TIMEOUT_MS) { requireRemote(DEFAULT_TIMEOUT_MS).getDisplayInfo() }
        if (raw.size < 4 || raw[0] <= 0) return null
        val info = DisplayInfo(raw[0], raw[1], raw[2], raw[3])
        synchronized(stateLock) {
            activeDisplayId = info.id
            activeDisplaySize = info.width to info.height
        }
        return info
    }
    val isConnected: Boolean get() = synchronized(stateLock) { remote != null }
    internal val lastProbe: VirtualScreenProbeSnapshot? get() = preferences.lastProbe()

    fun isEnabled(): Boolean = preferences.isEnabled()

    fun setEnabled(enabled: Boolean) {
        if (!enabled) {
            runCatching { releaseDisplay() }
            runCatching { unbind(remove = true) }
        }
        preferences.setEnabled(enabled)
    }

    /** A probe is allowed while the feature is disabled; only a fresh passing result can enable it. */
    fun runProbe(): String {
        val result = try {
            execute(DEFAULT_TIMEOUT_MS) {
                val rootProblem = RootProcess.unavailableReason()
                if (rootProblem != null) {
                    unavailableProbe("root_not_ready", rootProblem)
                } else {
                    val binder = requireRemote(DEFAULT_TIMEOUT_MS)
                    mergeRemoteProbe(binder.probe())
                }
            }
        } catch (error: Throwable) {
            unavailableProbe(reasonCode(error), error.message ?: "Probe call failed")
        }
        val fingerprint = VirtualScreenPreferences.currentDeviceFingerprint()
        preferences.saveProbe(result, fingerprint)
        return result.toJson()
    }

    fun openDisplay(width: Int? = null, height: Int? = null, dpi: Int? = null): Int {
        if (!preferences.isEnabled()) {
            throw VirtualScreenClientException(VirtualScreenPolicy.UNAVAILABLE, "VScreen is disabled or its device probe is not current and passing")
        }
        val configured = preferences.displaySettings()
        val actualWidth = width ?: configured.width
        val actualHeight = height ?: configured.height
        val actualDpi = dpi ?: configured.dpi
        val id = execute(DEFAULT_TIMEOUT_MS) { requireRemote(DEFAULT_TIMEOUT_MS).createDisplay(actualWidth, actualHeight, actualDpi) }
        if (id == 0) throw VirtualScreenClientException(VirtualScreenPolicy.PHYSICAL_DISPLAY_REFUSED, "Service returned the physical display")
        synchronized(stateLock) {
            activeDisplayId = id
            activeDisplaySize = actualWidth to actualHeight
            displayLostPending = false
            reconnectAttempts = 0
        }
        return id
    }

    /** Returns only the active non-physical display; 0 is never exposed as a VScreen id. */
    fun queryActiveDisplayId(): Int? {
        displayId?.let { return it }
        if (!isEnabled()) return null
        val id = execute(DEFAULT_TIMEOUT_MS) { requireRemote(DEFAULT_TIMEOUT_MS).getActiveDisplayId() }
        if (id <= 0) return null
        synchronized(stateLock) { activeDisplayId = id }
        return id
    }

    fun releaseDisplay() {
        val known = synchronized(stateLock) { activeDisplayId }
        if (known == null) return
        try {
            execute(DEFAULT_TIMEOUT_MS) { requireRemote(DEFAULT_TIMEOUT_MS).releaseDisplay() }
        } finally {
            synchronized(stateLock) { activeDisplayId = null; activeDisplaySize = null; activePackageName = null }
        }
    }

    fun launch(displayId: Int, packageName: String, activity: String? = null): Boolean {
        val launched = execute(DEFAULT_TIMEOUT_MS) { requireRemote(DEFAULT_TIMEOUT_MS).launch(packageName, activity, displayId) }
        if (launched) synchronized(stateLock) { activePackageName = packageName }
        return launched
    }

    fun dump(displayId: Int, mode: String = "SIMPLE"): String =
        execute(DEFAULT_TIMEOUT_MS) { requireRemote(DEFAULT_TIMEOUT_MS).dump(displayId, mode) }

    fun hasPackageWindow(displayId: Int, packageName: String): Boolean =
        execute(DEFAULT_TIMEOUT_MS) { requireRemote(DEFAULT_TIMEOUT_MS).hasPackageWindow(displayId, packageName) }

    fun tap(displayId: Int, x: Int, y: Int): Boolean =
        execute(DEFAULT_TIMEOUT_MS) { requireRemote(DEFAULT_TIMEOUT_MS).tap(displayId, x, y) }

    fun swipe(displayId: Int, startX: Int, startY: Int, endX: Int, endY: Int, durationMs: Int): Boolean =
        execute(DEFAULT_TIMEOUT_MS) { requireRemote(DEFAULT_TIMEOUT_MS).swipe(displayId, startX, startY, endX, endY, durationMs) }

    fun longPress(displayId: Int, x: Int, y: Int, durationMs: Int): Boolean =
        execute(DEFAULT_TIMEOUT_MS) { requireRemote(DEFAULT_TIMEOUT_MS).longPress(displayId, x, y, durationMs) }

    fun key(displayId: Int, keyCode: Int): Boolean =
        execute(DEFAULT_TIMEOUT_MS) { requireRemote(DEFAULT_TIMEOUT_MS).key(displayId, keyCode) }

    fun inputText(displayId: Int, text: String): Boolean =
        execute(DEFAULT_TIMEOUT_MS) { requireRemote(DEFAULT_TIMEOUT_MS).inputText(displayId, text) }

    fun setText(displayId: Int, text: String): Boolean =
        execute(DEFAULT_TIMEOUT_MS) { requireRemote(DEFAULT_TIMEOUT_MS).setText(displayId, text) }

    /**
     * Locate + act + settle + observe in one Binder call. [requestJson] and the result are JSON; the
     * timeout covers the service's own settle deadline, so a slow screen is reported by the service
     * (settledBy=deadline) instead of surfacing as a client timeout.
     */
    fun act(displayId: Int, requestJson: String): String =
        execute(ACT_TIMEOUT_MS) { requireRemote(ACT_TIMEOUT_MS).act(displayId, requestJson) }

    fun back(displayId: Int): Boolean =
        execute(DEFAULT_TIMEOUT_MS) { requireRemote(DEFAULT_TIMEOUT_MS).back(displayId) }

    fun home(displayId: Int): Boolean =
        execute(DEFAULT_TIMEOUT_MS) { requireRemote(DEFAULT_TIMEOUT_MS).home(displayId) }

    fun screenshot(displayId: Int, maxDim: Int = 1280, jpegQuality: Int = 85): ParcelFileDescriptor =
        execute(SCREENSHOT_TIMEOUT_MS) { requireRemote(SCREENSHOT_TIMEOUT_MS).screenshot(displayId, maxDim, jpegQuality) }

    /**
     * Live frames of the open display. [onFrame] runs on a binder thread for every frame (the caller draws
     * it and must close the buffer); [onEnded] runs when the display is released or the service dies.
     */
    fun startFrameStream(onFrame: (android.hardware.HardwareBuffer) -> Unit, onEnded: () -> Unit) {
        val sink = object : IVirtualScreenFrameSink.Stub() {
            override fun onFrame(buffer: android.hardware.HardwareBuffer?) {
                if (buffer != null) onFrame(buffer)
            }

            override fun onEnded() = onEnded()
        }
        synchronized(stateLock) { frameSink = sink }
        execute(DEFAULT_TIMEOUT_MS) { requireRemote(DEFAULT_TIMEOUT_MS).startFrameStream(sink) }
    }

    fun stopFrameStream() {
        synchronized(stateLock) { frameSink = null }
        runCatching { execute(DEFAULT_TIMEOUT_MS) { requireRemote(DEFAULT_TIMEOUT_MS).stopFrameStream() } }
    }

    /** One raw touch event; called from the viewer's own pump thread, not the shared worker. */
    fun touch(displayId: Int, action: Int, x: Int, y: Int, downTimeMs: Long): Boolean =
        requireRemote(DEFAULT_TIMEOUT_MS).touch(displayId, action, x, y, downTimeMs)

    fun unbind(remove: Boolean = true) {
        val shouldUnbind = synchronized(stateLock) {
            val wasBinding = binding
            remote = null
            binding = false
            activeDisplayId = null
            activeDisplaySize = null
            activePackageName = null
            displayLostPending = false
            connectionLatch.countDown()
            wasBinding
        }
        if (shouldUnbind) {
            onMainThread {
                runCatching { RootService.unbind(connection) }
                // Unbinding leaves a daemon running; only stop() ends it and releases its display.
                if (remove) runCatching { RootService.stop(serviceIntent) }
            }
        }
    }

    override fun close() {
        synchronized(stateLock) { if (closed) return; closed = true }
        runCatching { releaseDisplay() }
        runCatching { unbind(remove = true) }
        worker.shutdownNow()
    }

    private fun requireRemote(timeoutMs: Long, fromReconnect: Boolean = false): IVirtualScreenService {
        val lost = synchronized(stateLock) {
            if (!fromReconnect && displayLostPending) {
                displayLostPending = false
                true
            } else false
        }
        if (lost) {
            scheduleReconnectOnce()
            throw VirtualScreenClientException(VirtualScreenPolicy.DISPLAY_GONE, "The root virtual-screen service died; its virtual display was released")
        }

        var shouldBind = false
        val latch = synchronized(stateLock) {
            remote?.let { return it }
            if (closed) throw VirtualScreenClientException("vscreen_client_closed", "VScreen client is closed")
            RootProcess.unavailableReason()?.let { throw VirtualScreenClientException("root_not_ready", it) }
            if (!binding) {
                binding = true
                bindError = null
                connectionLatch = CountDownLatch(1)
                shouldBind = true
            }
            connectionLatch
        }
        if (shouldBind) {
            // libsu binds only on the main thread; the connection callbacks run inline (they only update
            // state under the lock), so they never wait for this worker thread.
            onMainThread {
                try {
                    RootService.bind(serviceIntent, { it.run() }, connection)
                } catch (error: Throwable) {
                    synchronized(stateLock) {
                        binding = false
                        bindError = error
                        connectionLatch.countDown()
                    }
                }
            }
        }
        if (!latch.await(timeoutMs, TimeUnit.MILLISECONDS)) {
            throw VirtualScreenClientException("vscreen_timeout", "Timed out waiting for the root virtual-screen service")
        }
        return synchronized(stateLock) {
            remote ?: bindError?.let {
                throw VirtualScreenClientException("vscreen_bind_failed", it.message ?: it.javaClass.simpleName, it)
            } ?: throw VirtualScreenClientException("vscreen_service_disconnected", "The root virtual-screen service did not connect")
        }
    }

    private fun onMainThread(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) block() else mainHandler.post(block)
    }

    private fun onServiceLost() {
        synchronized(stateLock) {
            if (activeDisplayId != null) displayLostPending = true
            activeDisplayId = null
            activeDisplaySize = null
            activePackageName = null
            remote = null
            binding = false
            connectionLatch.countDown()
        }
        scheduleReconnectOnce()
    }

    private fun scheduleReconnectOnce() {
        synchronized(stateLock) {
            if (closed || reconnectInFlight || reconnectAttempts >= 1) return
            reconnectAttempts++
            reconnectInFlight = true
        }
        Thread({
            try {
                requireRemote(DEFAULT_TIMEOUT_MS, fromReconnect = true)
            } catch (_: Throwable) {
                // A later explicit operation can attempt a fresh bind; the lost display is never recreated implicitly.
            } finally {
                synchronized(stateLock) { reconnectInFlight = false }
            }
        }, "Minis-VScreen-Reconnect").apply { isDaemon = true; start() }
    }

    private fun mergeRemoteProbe(json: String): VirtualScreenProbeReport {
        val root = org.json.JSONObject(json)
        val array = root.optJSONArray("steps") ?: org.json.JSONArray()
        val steps = buildList {
            for (index in 0 until array.length()) {
                val item = array.optJSONObject(index) ?: continue
                add(VirtualScreenProbeStep(
                    item.optString("id", "unknown"), item.optString("status", "fail"),
                    item.optString("code", "probe_step_failed"), item.optString("detail", ""),
                ))
            }
        }
        return VirtualScreenProbeReport(
            root.optLong("timestampMs", System.currentTimeMillis()),
            VirtualScreenPreferences.currentDeviceFingerprint(),
            steps,
        )
    }

    private fun unavailableProbe(code: String, detail: String): VirtualScreenProbeReport {
        val fingerprint = VirtualScreenPreferences.currentDeviceFingerprint()
        val sdk = Build.VERSION.SDK_INT
        val steps = listOf(
            VirtualScreenProbeStep("environment", if (sdk >= 29) "pass" else "fail",
                if (sdk >= 29) "android_supported" else "android_version_unsupported", "API $sdk; ${Build.MANUFACTURER} ${Build.MODEL}"),
            VirtualScreenProbeStep("root", "fail", code, detail.take(512)),
            VirtualScreenProbeStep("context", "skipped", code, SERVICE_NOT_STARTED),
            VirtualScreenProbeStep("virtual_display", "skipped", code, SERVICE_NOT_STARTED),
            VirtualScreenProbeStep("ime", "skipped", code, SERVICE_NOT_STARTED),
            VirtualScreenProbeStep("uiautomation", "skipped", code, SERVICE_NOT_STARTED),
            VirtualScreenProbeStep("input", "skipped", code, SERVICE_NOT_STARTED),
            VirtualScreenProbeStep("launch", "skipped", code, SERVICE_NOT_STARTED),
            VirtualScreenProbeStep("screenshot", "skipped", code, SERVICE_NOT_STARTED),
        )
        return VirtualScreenProbeReport(System.currentTimeMillis(), fingerprint, steps)
    }

    private fun reasonCode(error: Throwable): String = when (error) {
        is VirtualScreenClientException -> error.reasonCode
        is TimeoutException -> "vscreen_timeout"
        else -> remoteReasonCode(error) ?: "vscreen_probe_failed"
    }

    private fun <T> execute(timeoutMs: Long, task: () -> T): T {
        val future: Future<T> = try {
            worker.submit(Callable { task() })
        } catch (error: java.util.concurrent.RejectedExecutionException) {
            throw VirtualScreenClientException("vscreen_busy", "Too many VScreen Binder calls are in flight", error)
        }
        return try {
            future.get(timeoutMs, TimeUnit.MILLISECONDS)
        } catch (error: TimeoutException) {
            future.cancel(true)
            throw VirtualScreenClientException("vscreen_timeout", "VScreen Binder call exceeded ${timeoutMs}ms", error)
        } catch (error: ExecutionException) {
            val cause = error.cause ?: error
            if (cause is VirtualScreenClientException) throw cause
            val remoteCode = remoteReasonCode(cause)
            throw VirtualScreenClientException(remoteCode ?: "vscreen_call_failed", cause.message ?: cause.javaClass.simpleName, cause)
        } catch (error: InterruptedException) {
            Thread.currentThread().interrupt()
            throw VirtualScreenClientException("vscreen_interrupted", "VScreen call was interrupted", error)
        }
    }

    private fun remoteReasonCode(error: Throwable): String? {
        val prefix = error.message?.substringBefore(':') ?: return null
        return prefix.takeIf { it.matches(Regex("[a-z][a-z0-9_]{2,63}")) }
    }

    class VirtualScreenClientException(
        val reasonCode: String,
        message: String,
        cause: Throwable? = null,
    ) : IllegalStateException(message, cause)

    companion object {
        private const val SERVICE_NOT_STARTED = "The root virtual-screen service was not started"
        private const val DEFAULT_TIMEOUT_MS = 8_000L
        private const val ACT_TIMEOUT_MS = 12_000L
        private const val SCREENSHOT_TIMEOUT_MS = 15_000L
        private const val DEFAULT_WIDTH = 720
        private const val DEFAULT_HEIGHT = 1280
        private const val DEFAULT_DPI = 320
        private val THREAD_IDS = AtomicInteger()
    }
}
