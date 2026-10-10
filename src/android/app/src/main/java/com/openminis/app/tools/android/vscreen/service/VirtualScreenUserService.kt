package com.openminis.app.tools.android.vscreen.service

import android.app.ActivityOptions
import android.content.ComponentName
import android.content.Intent
import android.os.Build
import android.os.ParcelFileDescriptor
import android.os.Process
import android.os.SystemClock
import android.util.Log
import android.view.KeyEvent
import androidx.annotation.Keep
import com.openminis.app.tools.android.vscreen.IVirtualScreenFrameSink
import com.openminis.app.tools.android.vscreen.IVirtualScreenService
import com.openminis.app.tools.android.vscreen.VirtualScreenPolicy
import com.openminis.app.tools.android.vscreen.VirtualScreenProbeFailure
import com.openminis.app.tools.android.vscreen.VirtualScreenProbeRecorder
import com.openminis.app.tools.android.vscreen.SettleTracker
import com.openminis.app.tools.android.vscreen.service.internal.ActRunner
import com.openminis.app.tools.android.vscreen.service.internal.DisplaySpec
import com.openminis.app.tools.android.vscreen.service.internal.FocusBridge
import com.openminis.app.tools.android.vscreen.service.internal.InputBridge
import com.openminis.app.tools.android.vscreen.service.internal.ScreenCapture
import com.openminis.app.tools.android.vscreen.service.internal.ShellContext
import com.openminis.app.tools.android.vscreen.service.internal.UiBridge
import com.openminis.app.tools.android.vscreen.service.internal.VirtualDisplaySession
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.min
import kotlin.math.sqrt

/**
 * The virtual-screen service. It runs as root inside the process libsu starts for
 * [com.openminis.app.tools.android.vscreen.service.VirtualScreenRootService], and is reached only
 * through that service's binder: it has no network or local-socket listener.
 */
@Keep
class VirtualScreenUserService : IVirtualScreenService.Stub() {
    private val lock = Any()
    // libsu starts this process through su, so it must be root. Anything else means su handed out a
    // different identity, and the service refuses to act rather than half-work.
    private val identityError: String? =
        if (Process.myUid() == 0) null else "unexpected_user_service_uid"
    private val contextError: String? = if (identityError == null) ShellContext.initialize() else identityError
    private var session: VirtualDisplaySession? = null
    private var uiBridge: UiBridge? = null
    private val settle = SettleTracker()
    private var inputBridge: InputBridge? = null
    private val destroyed = AtomicBoolean(false)

    override fun probe(): String = synchronized(lock) {
        val recorder = VirtualScreenProbeRecorder()
        val fingerprint = deviceFingerprint()
        recorder.check("environment", "android_supported") {
            val sdk = Build.VERSION.SDK_INT
            if (sdk < MIN_SUPPORTED_SDK) {
                throw VirtualScreenProbeFailure("android_version_unsupported", "Android API $sdk; minimum is $MIN_SUPPORTED_SDK")
            }
            "Android ${Build.VERSION.RELEASE} API $sdk; ${Build.MANUFACTURER} ${Build.MODEL}; supported range Android 10+"
        }
        recorder.check("root", "root_service_ready") {
            if (identityError != null) throw VirtualScreenProbeFailure(identityError, "VScreen requires a root (0) service; actual UID=${Process.myUid()}")
            "Root service active as UID ${Process.myUid()}"
        }
        recorder.check("context", "shell_context_ready") {
            if (contextError != null) throw VirtualScreenProbeFailure("shell_context_unavailable", contextError)
            ShellContext.get().packageName
        }

        val hadDisplay = session != null
        var probeSession: VirtualDisplaySession? = null
        if (identityError != null) {
            recorder.skipped("virtual_display", identityError, "Only a root service identity is permitted")
        } else if (hadDisplay) {
            recorder.record("virtual_display", "fail", "probe_display_busy", "Close the current VScreen before running a disruptive probe")
        } else {
            recorder.check("virtual_display", "virtual_display_created") {
                if (contextError != null) throw VirtualScreenProbeFailure("shell_context_unavailable", contextError)
                val spec = probeDisplaySpec()
                val created = VirtualDisplaySession.create(spec.width, spec.height, spec.dpi)
                session = created
                probeSession = created
                "displayId=${created.displayId}; ${created.width}x${created.height}@${created.dpi}; flags=${created.flagLabel} (${created.flags})"
            }
        }

        val active = probeSession
        if (active == null) {
            val code = if (identityError != null) identityError else "display_unavailable"
            val detail = if (identityError != null) "Only a root service identity is permitted" else "Virtual display could not be created"
            recorder.skipped("ime", code, detail)
            recorder.skipped("uiautomation", code, detail)
            recorder.skipped("input", code, detail)
            recorder.skipped("launch", code, detail)
            recorder.skipped("screenshot", code, detail)
        } else {
            if (active.localImeEnabled) recorder.record("ime", "pass", "display_ime_local", "Display-level IME LOCAL policy was set")
            else recorder.warning("ime", "ime_policy_unavailable", "Could not set display-level IME LOCAL policy")

            val ui = UiBridge(settle)
            uiBridge = ui
            recorder.check("uiautomation", "uiautomation_connected") {
                if (!ui.connect()) throw VirtualScreenProbeFailure("uiautomation_unavailable", "UiAutomation connect returned false")
                "UiAutomation connected in the root service"
            }
            recorder.check("launch", "settings_window_on_virtual_display") {
                if (!ui.connect()) throw VirtualScreenProbeFailure("uiautomation_unavailable", "UiAutomation is not connected")
                if (!launchInternal("com.android.settings", null, active.displayId)) {
                    throw VirtualScreenProbeFailure("app_launch_failed", "Settings launch request was rejected")
                }
                val deadline = System.currentTimeMillis() + PROBE_LAUNCH_WAIT_MS
                var found = false
                while (System.currentTimeMillis() < deadline) {
                    if (ui.hasWindowOnDisplay(active.displayId, "com.android.settings")) {
                        found = true
                        break
                    }
                    Thread.sleep(150)
                }
                if (!found) throw VirtualScreenProbeFailure("app_left_virtual_display", "Settings window was not observed on display ${active.displayId}")
                "com.android.settings window observed on display ${active.displayId}"
            }
            recorder.check("input", "input_injected") {
                // Sent after the launch step: an input event needs a window on the display to be delivered to.
                val ok = InputBridge().key(active.displayId, KeyEvent.KEYCODE_UNKNOWN)
                if (!ok) {
                    val hint = if (isXiaomi()) {
                        "; Xiaomi/HyperOS: enable Developer options > USB debugging (Security settings) / 小米或 HyperOS 请在开发者选项开启“USB 调试（安全设置）”"
                    } else ""
                    throw VirtualScreenProbeFailure("input_injection_failed", "KEYCODE_UNKNOWN injection was rejected$hint")
                }
                "KEYCODE_UNKNOWN delivered to display ${active.displayId}"
            }
            recorder.check("screenshot", "non_black_screenshot") {
                val bitmap = active.captureBitmap(retries = 4, delayMs = 150L)
                    ?: throw VirtualScreenProbeFailure("screenshot_unavailable", "No image was produced by the virtual display")
                try {
                    if (!ScreenCapture.isNonBlack(bitmap)) throw VirtualScreenProbeFailure("screenshot_black", "Captured image was black or empty")
                    "${bitmap.width}x${bitmap.height} non-black image captured"
                } finally {
                    bitmap.recycle()
                }
            }
        }

        if (probeSession != null) {
            recorder.check("cleanup", "probe_display_released") {
                releaseSession()
                probeSession = null
                "Temporary probe display released"
            }
        } else {
            recorder.skipped("cleanup", "no_probe_display", "No temporary probe display was created")
        }

        try {
            val report = recorder.report(System.currentTimeMillis(), fingerprint)
            JSONObject().put("schemaVersion", 1).put("passed", report.passed)
                .put("timestampMs", report.timestampMs).put("fingerprint", report.fingerprint)
                .put("steps", report.steps.toJsonArray()).toString()
        } finally {
            try {
                if (probeSession != null) runCatching { releaseSession() }
            } finally {
                uiBridge?.disconnect()
                uiBridge = null
            }
        }
    }

    override fun createDisplay(width: Int, height: Int, dpi: Int): Int = synchronized(lock) {
        checkNotDestroyed()
        requireShellIdentity()
        val existing = session
        if (existing != null) return@synchronized existing.displayId
        if (contextError != null) fail("shell_context_unavailable", contextError)
        val created = try {
            VirtualDisplaySession.create(width, height, dpi)
        } catch (error: Throwable) {
            fail("virtual_display_create_failed", error.message ?: error.javaClass.simpleName)
        }
        if (created.displayId <= 0) {
            created.release()
            fail("virtual_display_invalid_id", "Virtual display id must be non-zero")
        }
        session = created
        // A new display starts on its home screen, not on nothing.
        runCatching { showHome(created.displayId) }
        created.displayId
    }

    override fun releaseDisplay() = synchronized(lock) { releaseSession() }

    override fun getActiveDisplayId(): Int = synchronized(lock) {
        checkNotDestroyed()
        requireShellIdentity()
        session?.displayId ?: 0
    }

    override fun launch(packageName: String, activityOrNull: String?, displayId: Int): Boolean = synchronized(lock) {
        checkDisplay(displayId)
        launchInternal(packageName, activityOrNull, displayId)
    }

    override fun dump(displayId: Int, mode: String): String = synchronized(lock) {
        val active = checkDisplay(displayId)
        ensureUi().dump(displayId, mode, active.width, active.height, active.rotation())
    }

    /** Locate + act + settle + observe in one call; see [ActRunner]. */
    override fun act(displayId: Int, requestJson: String): String = synchronized(lock) {
        val active = checkDisplay(displayId)
        if (requestJson.length > MAX_ACT_REQUEST_CHARS) fail("act_request_too_large", "act request exceeds $MAX_ACT_REQUEST_CHARS characters")
        val request = try {
            JSONObject(requestJson)
        } catch (error: org.json.JSONException) {
            fail("invalid_act_request", "act request is not valid JSON")
        }
        val ui = ensureUi()
        active.onFrame = { settle.onFrame(SystemClock.uptimeMillis()) }
        ActRunner(ui, ::input, settle).run(active, request).toString().also { FocusBridge.restorePhysicalFocusSoon() }
    }

    override fun hasPackageWindow(displayId: Int, packageName: String): Boolean = synchronized(lock) {
        checkDisplay(displayId)
        if (!PACKAGE_PATTERN.matches(packageName)) fail("invalid_package", "Invalid package name")
        ensureUi().hasWindowOnDisplay(displayId, packageName)
    }

    override fun tap(displayId: Int, x: Int, y: Int): Boolean = synchronized(lock) {
        val active = checkDisplay(displayId)
        checkCoordinates(active, x, y)
        input().tap(displayId, x, y).also { FocusBridge.restorePhysicalFocusSoon() }
    }

    override fun swipe(displayId: Int, startX: Int, startY: Int, endX: Int, endY: Int, durationMs: Int): Boolean = synchronized(lock) {
        val active = checkDisplay(displayId)
        checkCoordinates(active, startX, startY)
        checkCoordinates(active, endX, endY)
        input().swipe(displayId, startX, startY, endX, endY, durationMs).also { FocusBridge.restorePhysicalFocusSoon() }
    }

    override fun longPress(displayId: Int, x: Int, y: Int, durationMs: Int): Boolean = synchronized(lock) {
        val active = checkDisplay(displayId)
        checkCoordinates(active, x, y)
        input().longPress(displayId, x, y, durationMs).also { FocusBridge.restorePhysicalFocusSoon() }
    }

    override fun key(displayId: Int, keyCode: Int): Boolean = synchronized(lock) {
        checkDisplay(displayId)
        if (keyCode !in 0..KeyEvent.getMaxKeyCode()) fail("invalid_key_code", "keyCode is outside Android's key range")
        input().key(displayId, keyCode).also { FocusBridge.restorePhysicalFocusSoon() }
    }

    override fun inputText(displayId: Int, text: String): Boolean = synchronized(lock) {
        checkDisplay(displayId)
        if (text.length > MAX_TEXT_LENGTH) fail("text_too_long", "Input text exceeds $MAX_TEXT_LENGTH characters")
        input().text(displayId, text)
    }

    override fun setText(displayId: Int, text: String): Boolean = synchronized(lock) {
        checkDisplay(displayId)
        if (text.length > MAX_TEXT_LENGTH) fail("text_too_long", "Input text exceeds $MAX_TEXT_LENGTH characters")
        ensureUi().setText(displayId, text)
    }

    override fun getDisplayInfo(): IntArray = synchronized(lock) {
        checkNotDestroyed()
        requireShellIdentity()
        session?.let { intArrayOf(it.displayId, it.width, it.height, it.dpi) } ?: IntArray(0)
    }

    override fun startFrameStream(sink: IVirtualScreenFrameSink?) = synchronized(lock) {
        checkNotDestroyed()
        requireShellIdentity()
        val active = session ?: fail(VirtualScreenPolicy.DISPLAY_GONE, "No virtual display is open")
        active.setFrameSink(sink)
    }

    override fun stopFrameStream() = synchronized(lock) {
        session?.setFrameSink(null)
        Unit
    }

    /** Touch events arrive at drag rate; the bridge is built once and the call does not take the big lock. */
    private val touchBridge by lazy { InputBridge() }

    override fun touch(displayId: Int, action: Int, x: Int, y: Int, downTimeMs: Long): Boolean {
        val active: VirtualDisplaySession
        synchronized(lock) {
            active = checkDisplay(displayId)
            checkCoordinates(active, x, y)
        }
        val ok = touchBridge.touch(displayId, action, x, y, downTimeMs)
        // A finger going down on the virtual display focuses it; hand focus back once the gesture is over.
        if (action == android.view.MotionEvent.ACTION_DOWN || action == android.view.MotionEvent.ACTION_UP) {
            FocusBridge.restorePhysicalFocusSoon()
        }
        return ok
    }

    override fun back(displayId: Int): Boolean = key(displayId, KeyEvent.KEYCODE_BACK)
    /**
     * Home on the virtual display is Minis's own desktop: the system's secondary launcher draws nothing there
     * and a HOME key only brings some task forward. Starting it again while it runs just raises it.
     */
    override fun home(displayId: Int): Boolean = synchronized(lock) {
        checkDisplay(displayId)
        showHome(displayId)
    }

    private fun showHome(displayId: Int): Boolean =
        startOnDisplay(ComponentName(com.openminis.app.BuildConfig.APPLICATION_ID, HOME_ACTIVITY), displayId, HOME_FLAGS)

    override fun screenshot(displayId: Int, maxDim: Int, jpegQuality: Int): ParcelFileDescriptor = synchronized(lock) {
        val active = checkDisplay(displayId)
        val bitmap = active.captureBitmap(retries = 4, delayMs = 150L)
            ?: fail("screenshot_unavailable", "Virtual display did not produce a frame")
        val bytes = try {
            if (!ScreenCapture.isNonBlack(bitmap)) {
                fail(
                    VirtualScreenPolicy.SCREENSHOT_BLOCKED_SECURE,
                    "The frame is black; FLAG_SECURE or an unsupported capture policy may block pixels. Use the UI node tree or ask the user to take over; do not try OCR or another capture path.",
                )
            }
            ScreenCapture.jpeg(
                bitmap,
                if (maxDim <= 0) DEFAULT_MAX_DIM else maxDim,
                if (jpegQuality <= 0) DEFAULT_JPEG_QUALITY else jpegQuality,
                VirtualScreenPolicy.MAX_SCREENSHOT_BYTES,
            )
        } finally {
            bitmap.recycle()
        }
        val pipe = ParcelFileDescriptor.createPipe()
        val readEnd = pipe[0]
        val writeEnd = pipe[1]
        Thread({
            try {
                ParcelFileDescriptor.AutoCloseOutputStream(writeEnd).use { output -> output.write(bytes); output.flush() }
            } catch (error: IOException) {
                Log.d(TAG, "screenshot reader closed before write completed", error)
            }
        }, "minis-vscreen-screenshot").apply { isDaemon = true; start() }
        readEnd
    }

    /**
     * Called by [VirtualScreenRootService.onDestroy] when the root service is stopped. It releases the
     * virtual display; libsu ends the process once no service is left in it.
     */
    fun shutdown() {
        if (!destroyed.compareAndSet(false, true)) return
        synchronized(lock) { releaseSession() }
    }

    private fun ensureUi(): UiBridge {
        requireShellIdentity()
        if (contextError != null) fail("shell_context_unavailable", contextError)
        val ui = uiBridge ?: UiBridge(settle).also { uiBridge = it }
        if (!ui.isConnected()) ui.connect()
        return ui
    }

    /** One bridge per service: it resolves the input manager and two reflective methods, which used to happen on every tap. */
    private fun input(): InputBridge = inputBridge ?: InputBridge().also { inputBridge = it }

    private fun launchInternal(packageName: String, activityOrNull: String?, displayId: Int): Boolean {
        if (!PACKAGE_PATTERN.matches(packageName)) fail("invalid_package", "Invalid package name")
        val context = ShellContext.get()
        val component: ComponentName = if (activityOrNull.isNullOrBlank()) {
            context.packageManager.getLaunchIntentForPackage(packageName)?.component ?: return false
        } else {
            val className = if (activityOrNull.startsWith('.')) packageName + activityOrNull else activityOrNull
            if (!className.startsWith("$packageName.")) fail("invalid_activity", "Activity must belong to the requested package")
            ComponentName(packageName, className)
        }
        return startOnDisplay(component, displayId)
    }

    /**
     * Context.startActivity needs a caller process the system knows; this service process is not one
     * (the system answers "Not allowed to start activity"). `cmd activity start-activity` is the
     * platform's own shell entry point for the same call and works for shell and root alike. The
     * argument vector is built here from a validated package and a component name, with no shell.
     */
    private fun startOnDisplay(component: ComponentName, displayId: Int, flags: String = NEW_TASK_FLAG): Boolean {
        val command = listOf(
            "cmd", "activity", "start-activity", "--user", "0", "--display", displayId.toString(),
            "-f", flags, "-n", component.flattenToShortString(),
        )
        val process = ProcessBuilder(command).redirectErrorStream(true).start()
        val output = process.inputStream.bufferedReader().use { it.readText() }
        if (!process.waitFor(LAUNCH_TIMEOUT_SECONDS, java.util.concurrent.TimeUnit.SECONDS)) {
            process.destroyForcibly()
            fail("app_launch_failed", "Launch request timed out")
        }
        if (process.exitValue() != 0 || output.contains("Error", ignoreCase = true)) {
            fail("app_launch_failed", output.trim().take(300).ifEmpty { "Launch request was rejected" })
        }
        // The new window takes the system's top focus; give it back to the physical screen.
        Thread.sleep(250)
        FocusBridge.restorePhysicalFocusSoon()
        return true
    }

    private fun checkDisplay(displayId: Int): VirtualDisplaySession {
        requireShellIdentity()
        val active = session
        val error = VirtualScreenPolicy.displayError(displayId, active?.displayId)
        if (error != null) fail(error, error)
        return active!!
    }

    private fun checkCoordinates(active: VirtualDisplaySession, x: Int, y: Int) {
        if (x !in 0 until active.width || y !in 0 until active.height) fail("coordinates_out_of_bounds", "Coordinates are outside the virtual display")
    }

    private fun requireShellIdentity() {
        if (identityError != null) fail(identityError, "VScreen requires a root (0) service; actual UID=${Process.myUid()}")
    }

    private fun releaseSession() {
        val active = session
        try {
            active?.release()
            session = null
        } finally {
            uiBridge?.disconnect()
            uiBridge = null
        }
    }

    private fun checkNotDestroyed() {
        if (destroyed.get()) fail("vscreen_service_destroyed", "The virtual-screen service is shutting down")
    }

    private fun probeDisplaySpec(): DisplaySpec {
        val raw = DisplaySpec.current()
        val areaScale = min(1f, sqrt(MAX_PROBE_PIXELS.toFloat() / (raw.width.toLong() * raw.height).toFloat()))
        val maxScale = min(1f, MAX_PROBE_DIM.toFloat() / maxOf(raw.width, raw.height))
        val scale = min(areaScale, maxScale)
        val width = ((raw.width * scale).toInt() / 2 * 2).coerceAtLeast(320)
        val height = ((raw.height * scale).toInt() / 2 * 2).coerceAtLeast(320)
        return DisplaySpec(width, height, raw.dpi.coerceIn(80, 800))
    }

    private fun isXiaomi(): Boolean = listOf(Build.MANUFACTURER, Build.BRAND, Build.MODEL)
        .any { it.contains("xiaomi", ignoreCase = true) || it.contains("redmi", ignoreCase = true) || it.contains("poco", ignoreCase = true) }

    private fun deviceFingerprint(): String = listOf(
        Build.FINGERPRINT.orEmpty(), Build.VERSION.SDK_INT.toString(), Build.MANUFACTURER.orEmpty(), Build.DEVICE.orEmpty(),
    ).joinToString("|")

    private fun fail(code: String, detail: String): Nothing =
        throw IllegalStateException("$code: $detail")

    companion object {
        private const val TAG = "VScreenUserService"
        private const val MIN_SUPPORTED_SDK = 29
        private const val DEFAULT_MAX_DIM = 1280
        private const val DEFAULT_JPEG_QUALITY = 85
        private const val MAX_TEXT_LENGTH = 4096
        private const val MAX_ACT_REQUEST_CHARS = 16_384
        private const val MAX_PROBE_DIM = 2400
        private const val MAX_PROBE_PIXELS = 4_194_304L
        private const val PROBE_LAUNCH_WAIT_MS = 4_000L
        private const val LAUNCH_TIMEOUT_SECONDS = 8L
        // The desktop is a single task: raising it again must not pile up copies.
        private const val HOME_FLAGS = "268435456" // FLAG_ACTIVITY_NEW_TASK
        private const val HOME_ACTIVITY = "com.openminis.app.ui.vscreen.VirtualScreenHomeActivity"
        // NEW_TASK | MULTIPLE_TASK: always a task of its own on the virtual display. Without MULTIPLE_TASK an app that
        // already runs on the physical screen is MOVED here, taking it away from the user.
        private const val NEW_TASK_FLAG = "402653184" // FLAG_ACTIVITY_NEW_TASK (0x10000000) | FLAG_ACTIVITY_MULTIPLE_TASK (0x08000000)
        private val PACKAGE_PATTERN = Regex("[A-Za-z0-9_]+(?:\\.[A-Za-z0-9_]+)+")
    }
}

private fun List<com.openminis.app.tools.android.vscreen.VirtualScreenProbeStep>.toJsonArray(): JSONArray = JSONArray().apply {
    forEach { step ->
        put(JSONObject().put("id", step.id).put("status", step.status).put("code", step.code).put("detail", step.detail))
    }
}
