package com.openminis.app.tools.android.vscreen

import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.os.Build
import android.os.IBinder
import android.os.ParcelFileDescriptor
import com.openminis.app.BuildConfig
import com.openminis.app.offload.ShizukuManager
import com.openminis.app.tools.android.vscreen.service.VirtualScreenUserService
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
import rikka.shizuku.Shizuku

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

    private val args = Shizuku.UserServiceArgs(
        ComponentName(appContext, VirtualScreenUserService::class.java),
    ).daemon(true)
        .processNameSuffix("vscreen")
        .tag("minis-vscreen")
        .version(USER_SERVICE_VERSION)
        .debuggable(BuildConfig.DEBUG)

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
                ShizukuManager.refresh()
                if (!ShizukuManager.isReady()) {
                    unavailableProbe("shizuku_not_ready", "Shizuku is not running and authorized")
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
        if (shouldUnbind) runCatching { Shizuku.unbindUserService(args, connection, remove) }
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
            throw VirtualScreenClientException(VirtualScreenPolicy.DISPLAY_GONE, "The UserService died; its virtual display was released")
        }

        var shouldBind = false
        val latch = synchronized(stateLock) {
            remote?.let { return it }
            if (closed) throw VirtualScreenClientException("vscreen_client_closed", "VScreen client is closed")
            if (!ShizukuManager.isReady()) throw VirtualScreenClientException("shizuku_not_ready", "Shizuku is not running and authorized")
            if (!binding) {
                binding = true
                connectionLatch = CountDownLatch(1)
                shouldBind = true
            }
            connectionLatch
        }
        if (shouldBind) {
            try {
                Shizuku.bindUserService(args, connection)
            } catch (error: Throwable) {
                synchronized(stateLock) {
                    binding = false
                    connectionLatch.countDown()
                }
                throw VirtualScreenClientException("vscreen_bind_failed", error.message ?: error.javaClass.simpleName, error)
            }
        }
        if (!latch.await(timeoutMs, TimeUnit.MILLISECONDS)) {
            throw VirtualScreenClientException("vscreen_timeout", "Timed out waiting for Shizuku UserService")
        }
        return synchronized(stateLock) {
            remote ?: throw VirtualScreenClientException("vscreen_service_disconnected", "UserService did not connect")
        }
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
            VirtualScreenProbeStep("shizuku", "fail", code, detail.take(512)),
            VirtualScreenProbeStep("context", "skipped", "shizuku_unavailable", "UserService was not started"),
            VirtualScreenProbeStep("virtual_display", "skipped", "shizuku_unavailable", "UserService was not started"),
            VirtualScreenProbeStep("ime", "skipped", "shizuku_unavailable", "UserService was not started"),
            VirtualScreenProbeStep("uiautomation", "skipped", "shizuku_unavailable", "UserService was not started"),
            VirtualScreenProbeStep("input", "skipped", "shizuku_unavailable", "UserService was not started"),
            VirtualScreenProbeStep("launch", "skipped", "shizuku_unavailable", "UserService was not started"),
            VirtualScreenProbeStep("screenshot", "skipped", "shizuku_unavailable", "UserService was not started"),
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
        // Bumped whenever the UserService code or its AIDL changes: a daemon service outlives the app, so
        // Shizuku only replaces it when this number differs. 2 = frame stream, raw touch, display info; 3 = Home starts Minis's own desktop; 4 = UiAutomation keeps other accessibility services, launches never move a task; 5 = focus is handed back to the physical screen (and again a moment later); 7 = UiAutomation really connects with DONT_SUPPRESS; 8 = act() replaces the index-based target calls.
        private const val USER_SERVICE_VERSION = 8
        private const val DEFAULT_TIMEOUT_MS = 8_000L
        private const val ACT_TIMEOUT_MS = 12_000L
        private const val SCREENSHOT_TIMEOUT_MS = 15_000L
        private const val DEFAULT_WIDTH = 720
        private const val DEFAULT_HEIGHT = 1280
        private const val DEFAULT_DPI = 320
        private val THREAD_IDS = AtomicInteger()
    }
}
