/*
 * Adapted for Minis from ShadowAuto (`android-notes/ShadowAuto`, commit `611e5eb0e1c94befda3c17c97438912dfd30e8a8`); modified.
 * Original source: android-shell/src/main/java/com/silentauto/shell/VirtualDisplaySession.java.
 * Licensed under Apache-2.0; see third_party/shadowauto/LICENSE.
 */
package com.openminis.app.tools.android.vscreen.service.internal

import android.content.Context
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.os.SystemClock
import android.view.Surface
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

    @Synchronized
    fun captureBitmap(retries: Int = 3, delayMs: Long = 120L): Bitmap? {
        if (released.get()) return null
        var image: Image? = null
        try {
            repeat(retries + 1) {
                image = captureReader.acquireLatestImage()
                if (image != null) return bitmapFromImage(image!!)
                SystemClock.sleep(delayMs)
            }
            return null
        } finally {
            image?.close()
        }
    }

    fun release() {
        if (!released.compareAndSet(false, true)) return
        if (previousImePolicy >= 0) runCatching { imeBridge?.setDisplayImePolicy(displayId, previousImePolicy) }
        runCatching { display.release() }
        runCatching { captureReader.surface.release() }
        runCatching { captureReader.close() }
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
        private const val MAX_DIMENSION = 2400
        private const val MAX_PIXELS = 4_194_304L

        fun create(width: Int, height: Int, dpi: Int): VirtualDisplaySession {
            require(width in 320..MAX_DIMENSION && height in 320..MAX_DIMENSION && dpi in 80..800) {
                "invalid_display_spec"
            }
            require(width.toLong() * height.toLong() <= MAX_PIXELS) { "display_too_large" }
            val shellContext = ShellContext.get()
            val manager = displayManager(shellContext)
            val reader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2)
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
