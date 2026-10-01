/*
 * Adapted for Minis from ShadowAuto (`android-notes/ShadowAuto`, commit `611e5eb0e1c94befda3c17c97438912dfd30e8a8`); modified.
 * Original source: android-shell/src/main/java/com/silentauto/shell/VirtualDisplaySession.java.
 * Licensed under Apache-2.0; see third_party/shadowauto/LICENSE.
 */
package com.openminis.app.tools.android.vscreen.service.internal

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.HardwareBuffer
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.util.Log
import com.openminis.app.tools.android.vscreen.IVirtualScreenFrameSink
import com.openminis.app.tools.android.vscreen.VirtualScreenPolicy
import java.lang.reflect.Field
import java.util.concurrent.atomic.AtomicBoolean

internal class VirtualDisplaySession private constructor(
    val width: Int,
    val height: Int,
    val dpi: Int,
    val displayId: Int,
    val flags: Int,
    val flagLabel: String,
    private val display: VirtualDisplay,
    private val captureReader: ImageReader,
    private val imeBridge: WindowManagerBridge?,
    private val previousImePolicy: Int,
    val localImeEnabled: Boolean,
) {
    private val released = AtomicBoolean(false)

    // The display draws into this reader for as long as it lives. Every frame is taken by a listener
    // and the newest few are kept open: a screenshot reads the newest one (so it works even when the
    // screen has not changed since the last capture), and the live viewer is sent each frame as a
    // HardwareBuffer with no copy. Older frames stay open a moment so the viewer is not drawing a
    // buffer the display is already rewriting.
    private val frameLock = Any()
    private val recent = ArrayDeque<Image>()
    @Volatile private var sink: IVirtualScreenFrameSink? = null
    private val frameThread = HandlerThread("minis-vscreen-frames").apply { start() }

    /** Called (on the frame thread) for every new frame; the settle tracker uses it to extend the quiet window. */
    @Volatile var onFrame: (() -> Unit)? = null

    /** Rotation as the system reports it; -1 when unavailable. A letterboxed or rotated app is diagnosed with this. */
    fun rotation(): Int = runCatching { display.display.rotation }.getOrDefault(-1)

    init {
        captureReader.setOnImageAvailableListener({ reader -> onFrameAvailable(reader) }, Handler(frameThread.looper))
    }

    // The service only runs on API 29+ (the probe refuses older systems), so Image.hardwareBuffer (API 28) is safe.
    @SuppressLint("NewApi")
    private fun onFrameAvailable(reader: ImageReader) {
        val image = try {
            reader.acquireLatestImage()
        } catch (_: IllegalStateException) {
            null
        } ?: return
        var buffer: HardwareBuffer? = null
        synchronized(frameLock) {
            if (released.get()) {
                image.close()
                return
            }
            recent.addLast(image)
            while (recent.size > KEEP_FRAMES) recent.removeFirst().close()
            if (sink != null) buffer = runCatching { image.hardwareBuffer }.getOrNull()
        }
        deliver(buffer)
        onFrame?.invoke()
    }

    private fun deliver(buffer: HardwareBuffer?) {
        val target = sink
        if (buffer == null) return
        try {
            target?.onFrame(buffer)
        } catch (error: Throwable) {
            // The viewer is gone (process died or binder full): stop pushing instead of retrying forever.
            Log.d(TAG, "frame sink dropped", error)
            sink = null
        } finally {
            runCatching { buffer.close() }
        }
    }

    /** Start (or stop, with null) pushing frames; the current frame goes out at once. */
    @SuppressLint("NewApi")
    fun setFrameSink(next: IVirtualScreenFrameSink?) {
        val previous = sink
        sink = next
        if (previous != null && previous !== next) runCatching { previous.onEnded() }
        if (next != null) {
            val buffer = synchronized(frameLock) {
                recent.lastOrNull()?.let { runCatching { it.hardwareBuffer }.getOrNull() }
            }
            deliver(buffer)
        }
    }

    @Synchronized
    fun captureBitmap(retries: Int = 3, delayMs: Long = 120L): Bitmap? {
        if (released.get()) return null
        repeat(retries + 1) {
            synchronized(frameLock) {
                recent.lastOrNull()?.let { return bitmapFromImage(it) }
            }
            SystemClock.sleep(delayMs)
        }
        return null
    }

    fun release() {
        if (!released.compareAndSet(false, true)) return
        onFrame = null
        runCatching { sink?.onEnded() }
        sink = null
        if (previousImePolicy >= 0) runCatching { imeBridge?.setDisplayImePolicy(displayId, previousImePolicy) }
        runCatching { display.release() }
        runCatching { captureReader.setOnImageAvailableListener(null, null) }
        synchronized(frameLock) {
            while (recent.isNotEmpty()) runCatching { recent.removeFirst().close() }
        }
        runCatching { captureReader.surface.release() }
        runCatching { captureReader.close() }
        frameThread.quitSafely()
    }

    private fun bitmapFromImage(image: Image): Bitmap {
        val plane = image.planes.firstOrNull() ?: throw IllegalStateException("image_plane_unavailable")
        val widthPixels = image.width
        val heightPixels = image.height
        val paddedWidth = plane.rowStride / plane.pixelStride
        val padded = Bitmap.createBitmap(paddedWidth, heightPixels, Bitmap.Config.ARGB_8888)
        val buffer = plane.buffer.duplicate()
        buffer.rewind()
        padded.copyPixelsFromBuffer(buffer)
        val cropped = Bitmap.createBitmap(padded, 0, 0, widthPixels, heightPixels)
        padded.recycle()
        return cropped
    }

    companion object {
        private const val TAG = "VScreenSession"
        private const val MAX_IMAGES = 6
        private const val KEEP_FRAMES = 3
        private const val MAX_DIMENSION = 2400
        private const val MAX_PIXELS = 4_194_304L

        fun create(width: Int, height: Int, dpi: Int): VirtualDisplaySession {
            require(width in 320..MAX_DIMENSION && height in 320..MAX_DIMENSION && dpi in 80..800) {
                "invalid_display_spec"
            }
            require(width.toLong() * height.toLong() <= MAX_PIXELS) { "display_too_large" }
            val shellContext = ShellContext.get()
            val manager = displayManager(shellContext)
            val reader = newReader(width, height)
            val base = DisplayManager.VIRTUAL_DISPLAY_FLAG_PUBLIC or
                DisplayManager.VIRTUAL_DISPLAY_FLAG_PRESENTATION or
                DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY
            val touch = hiddenFlag("VIRTUAL_DISPLAY_FLAG_SUPPORTS_TOUCH")
            val focus = hiddenFlag("VIRTUAL_DISPLAY_FLAG_OWN_FOCUS")
            val trusted = hiddenFlag("VIRTUAL_DISPLAY_FLAG_TRUSTED")
            var lastError: Throwable? = null
            try {
                for (candidate in VirtualScreenPolicy.flagCandidates(base, touch, focus, trusted)) {
                    try {
                        val display = manager.createVirtualDisplay(
                            "Minis-VScreen", width, height, dpi, reader.surface, candidate.flags,
                        )
                        val androidDisplay = display?.display
                        val id = androidDisplay?.displayId ?: -1
                        if (display != null && id > 0) {
                            val ime = runCatching { WindowManagerBridge() }.getOrNull()
                            val previous = runCatching { ime?.getDisplayImePolicy(id) ?: -1 }.getOrDefault(-1)
                            val imeEnabled = runCatching {
                                ime?.setDisplayImePolicy(id, WindowManagerBridge.DISPLAY_IME_POLICY_LOCAL) == true
                            }.getOrDefault(false)
                            return VirtualDisplaySession(
                                width, height, dpi, id, candidate.flags, candidate.label,
                                display, reader, ime, previous, imeEnabled,
                            )
                        }
                        runCatching { display?.release() }
                        lastError = IllegalStateException("virtual_display_id_invalid")
                    } catch (error: Throwable) {
                        lastError = error
                    }
                }
            } catch (error: Throwable) {
                lastError = error
            }
            runCatching { reader.close() }
            throw IllegalStateException("create_virtual_display_failed", lastError)
        }

        /** GPU-sampled buffers so the viewer can draw them directly; plain buffers if the device refuses. */
        @SuppressLint("WrongConstant", "NewApi")
        private fun newReader(width: Int, height: Int): ImageReader = try {
            ImageReader.newInstance(
                width, height, PixelFormat.RGBA_8888, MAX_IMAGES,
                HardwareBuffer.USAGE_GPU_SAMPLED_IMAGE or HardwareBuffer.USAGE_CPU_READ_OFTEN,
            )
        } catch (error: Throwable) {
            Log.w(TAG, "GPU-sampled reader unavailable, using a plain one", error)
            ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, MAX_IMAGES)
        }

        private fun displayManager(context: Context): DisplayManager {
            val fromContext = runCatching {
                context.getSystemService(Context.DISPLAY_SERVICE) as? DisplayManager
            }.getOrNull()
            if (fromContext != null) return fromContext
            val ctor = DisplayManager::class.java.getDeclaredConstructor(Context::class.java).apply { isAccessible = true }
            return ctor.newInstance(context)
        }

        private fun hiddenFlag(name: String): Int = runCatching {
            val field: Field = DisplayManager::class.java.getDeclaredField(name).apply { isAccessible = true }
            field.getInt(null)
        }.getOrDefault(0)
    }
}
