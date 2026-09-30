package com.openminis.app.tools.android.vscreen.service

import android.app.ActivityOptions
import android.content.ComponentName
import android.content.Intent
import android.os.Build
import android.os.ParcelFileDescriptor
import android.os.Process
import android.util.Log
import android.view.KeyEvent
import androidx.annotation.Keep
import com.openminis.app.tools.android.vscreen.IVirtualScreenService
import com.openminis.app.tools.android.vscreen.VirtualScreenPolicy
import com.openminis.app.tools.android.vscreen.VirtualScreenProbeFailure
import com.openminis.app.tools.android.vscreen.VirtualScreenProbeRecorder
import com.openminis.app.tools.android.vscreen.service.internal.DisplaySpec
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

/** Shizuku-protocol UserService (shell or root). It intentionally has no network or local-socket listener. */
@Keep
class VirtualScreenUserService : IVirtualScreenService.Stub() {
    private val lock = Any()
    // The Shizuku-protocol server decides the uid: shell (adb-started Shizuku) or root (a root-started
    // Shizuku, or Sui). Both can create a virtual display and drive it; anything else is not a
    // Shizuku-protocol service and is refused. (Binder identity is per process, so a root service
    // cannot be narrowed to shell from the inside.)
    private val identityError: String? =
        if (Process.myUid() == Process.SHELL_UID || Process.myUid() == 0) null else "unexpected_user_service_uid"
    private val contextError: String? = if (identityError == null) ShellContext.initialize() else identityError
    private var session: VirtualDisplaySession? = null
    private var uiBridge: UiBridge? = null
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
        recorder.check("shizuku", "shizuku_shell_user_service") {
            if (identityError != null) throw VirtualScreenProbeFailure(identityError, "VScreen requires a Shizuku-protocol shell (2000) or root (0) service; actual UID=${Process.myUid()}")
            "UserService active as ${if (Process.myUid() == 0) "root" else "shell"} UID ${Process.myUid()} (app-side READY state was checked before binding)"
        }
        recorder.check("context", "shell_context_ready") {
            if (contextError != null) throw VirtualScreenProbeFailure("shell_context_unavailable", contextError)
            ShellContext.get().packageName
        }

        val hadDisplay = session != null
        var probeSession: VirtualDisplaySession? = null
        if (identityError != null) {
            recorder.skipped("virtual_display", identityError, "Non-shell service identity is not permitted")
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
            val detail = if (identityError != null) "Non-shell service identity is not permitted" else "Virtual display could not be created"
            recorder.skipped("ime", code, detail)
            recorder.skipped("uiautomation", code, detail)
            recorder.skipped("input", code, detail)
            recorder.skipped("launch", code, detail)
            recorder.skipped("screenshot", code, detail)
        } else {
            if (active.localImeEnabled) recorder.record("ime", "pass", "display_ime_local", "Display-level IME LOCAL policy was set")
            else recorder.warning("ime", "ime_policy_unavailable", "Could not set display-level IME LOCAL policy")

            val ui = UiBridge()
            uiBridge = ui
            recorder.check("uiautomation", "uiautomation_connected") {
                if (!ui.connect()) throw VirtualScreenProbeFailure("uiautomation_unavailable", "UiAutomation connect returned false")
                "UiAutomation connected in Shizuku UserService"
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
        checkDisplay(displayId)
        ensureUi().dump(displayId, mode)
    }

    override fun hasPackageWindow(displayId: Int, packageName: String): Boolean = synchronized(lock) {
        checkDisplay(displayId)
        if (!PACKAGE_PATTERN.matches(packageName)) fail("invalid_package", "Invalid package name")
        ensureUi().hasWindowOnDisplay(displayId, packageName)
    }

    override fun clickTarget(displayId: Int, targetIndex: Int): Boolean = synchronized(lock) {
        checkDisplay(displayId)
        ensureUi().clickTarget(displayId, targetIndex)
    }

    override fun tap(displayId: Int, x: Int, y: Int): Boolean = synchronized(lock) {
        val active = checkDisplay(displayId)
        checkCoordinates(active, x, y)
        InputBridge().tap(displayId, x, y)
    }

    override fun swipe(displayId: Int, startX: Int, startY: Int, endX: Int, endY: Int, durationMs: Int): Boolean = synchronized(lock) {
        val active = checkDisplay(displayId)
        checkCoordinates(active, startX, startY)
        checkCoordinates(active, endX, endY)
        InputBridge().swipe(displayId, startX, startY, endX, endY, durationMs)
    }

    override fun longPress(displayId: Int, x: Int, y: Int, durationMs: Int): Boolean = synchronized(lock) {
        val active = checkDisplay(displayId)
        checkCoordinates(active, x, y)
        InputBridge().longPress(displayId, x, y, durationMs)
    }

    override fun key(displayId: Int, keyCode: Int): Boolean = synchronized(lock) {
        checkDisplay(displayId)
        if (keyCode !in 0..KeyEvent.getMaxKeyCode()) fail("invalid_key_code", "keyCode is outside Android's key range")
        InputBridge().key(displayId, keyCode)
    }

    override fun inputText(displayId: Int, text: String): Boolean = synchronized(lock) {
        checkDisplay(displayId)
        if (text.length > MAX_TEXT_LENGTH) fail("text_too_long", "Input text exceeds $MAX_TEXT_LENGTH characters")
        InputBridge().text(displayId, text)
    }

    override fun setText(displayId: Int, text: String): Boolean = synchronized(lock) {
        checkDisplay(displayId)
        if (text.length > MAX_TEXT_LENGTH) fail("text_too_long", "Input text exceeds $MAX_TEXT_LENGTH characters")
        ensureUi().setText(displayId, text)
    }

    override fun setTextTarget(displayId: Int, targetIndex: Int, text: String): Boolean = synchronized(lock) {
        checkDisplay(displayId)
        if (targetIndex < 1) fail("invalid_target", "targetIndex must be positive")
        if (text.length > MAX_TEXT_LENGTH) fail("text_too_long", "Input text exceeds $MAX_TEXT_LENGTH characters")
        ensureUi().setTextTarget(displayId, targetIndex, text)
    }

    override fun focusTarget(displayId: Int, targetIndex: Int): Boolean = synchronized(lock) {
        checkDisplay(displayId)
        if (targetIndex < 1) fail("invalid_target", "targetIndex must be positive")
        ensureUi().focusTarget(displayId, targetIndex)
    }

    override fun back(displayId: Int): Boolean = key(displayId, KeyEvent.KEYCODE_BACK)
    override fun home(displayId: Int): Boolean = key(displayId, KeyEvent.KEYCODE_HOME)

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

    /** Reserved Shizuku UserService transaction. Cleanup precedes process exit. */
    @Keep
    override fun destroy() {
        if (!destroyed.compareAndSet(false, true)) return
        try {
            synchronized(lock) { releaseSession() }
        } finally {
            System.exit(0)
        }
    }

    private fun ensureUi(): UiBridge {
        requireShellIdentity()
        if (contextError != null) fail("shell_context_unavailable", contextError)
        val ui = uiBridge ?: UiBridge().also { uiBridge = it }
        if (!ui.isConnected()) ui.connect()
        return ui
    }

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
    private fun startOnDisplay(component: ComponentName, displayId: Int): Boolean {
        val command = listOf(
            "cmd", "activity", "start-activity", "--user", "0", "--display", displayId.toString(),
            "-f", NEW_TASK_FLAG, "-n", component.flattenToShortString(),
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
        if (identityError != null) fail(identityError, "VScreen requires a Shizuku-protocol shell (2000) or root (0) service; actual UID=${Process.myUid()}")
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
        if (destroyed.get()) fail("vscreen_service_destroyed", "UserService is shutting down")
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
        private const val MAX_PROBE_DIM = 2400
        private const val MAX_PROBE_PIXELS = 4_194_304L
        private const val PROBE_LAUNCH_WAIT_MS = 4_000L
        private const val LAUNCH_TIMEOUT_SECONDS = 8L
        private const val NEW_TASK_FLAG = "268435456" // Intent.FLAG_ACTIVITY_NEW_TASK
        private val PACKAGE_PATTERN = Regex("[A-Za-z0-9_]+(?:\\.[A-Za-z0-9_]+)+")
    }
}

private fun List<com.openminis.app.tools.android.vscreen.VirtualScreenProbeStep>.toJsonArray(): JSONArray = JSONArray().apply {
    forEach { step ->
        put(JSONObject().put("id", step.id).put("status", step.status).put("code", step.code).put("detail", step.detail))
    }
}
