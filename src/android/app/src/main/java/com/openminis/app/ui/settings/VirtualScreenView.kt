package com.openminis.app.ui.settings

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.hardware.HardwareBuffer
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.atomic.AtomicInteger

/** Where the display picture sits inside a view, and how a touch on the view maps back onto the display. */
internal object VirtualScreenGeometry {
    data class Fit(val left: Float, val top: Float, val scale: Float)

    /** Aspect-fit, centred. A zero-sized view or display gives scale 0 (nothing to draw or touch). */
    fun fit(viewWidth: Int, viewHeight: Int, displayWidth: Int, displayHeight: Int): Fit {
        if (viewWidth <= 0 || viewHeight <= 0 || displayWidth <= 0 || displayHeight <= 0) return Fit(0f, 0f, 0f)
        val scale = minOf(viewWidth.toFloat() / displayWidth, viewHeight.toFloat() / displayHeight)
        return Fit((viewWidth - displayWidth * scale) / 2f, (viewHeight - displayHeight * scale) / 2f, scale)
    }

    /** True when the view point is on the picture (a touch that starts in the letterbox is ignored). */
    fun inside(x: Float, y: Float, fit: Fit, displayWidth: Int, displayHeight: Int): Boolean =
        fit.scale > 0f &&
            x >= fit.left && x < fit.left + displayWidth * fit.scale &&
            y >= fit.top && y < fit.top + displayHeight * fit.scale

    /** Display pixel under a view point, clamped onto the display (a drag may leave the picture). */
    fun toDisplay(x: Float, y: Float, fit: Fit, displayWidth: Int, displayHeight: Int): Pair<Int, Int> {
        if (fit.scale <= 0f) return 0 to 0
        val dx = ((x - fit.left) / fit.scale).toInt().coerceIn(0, displayWidth - 1)
        val dy = ((y - fit.top) / fit.scale).toInt().coerceIn(0, displayHeight - 1)
        return dx to dy
    }
}

/** One touch event on its way to the virtual display. */
internal data class VirtualScreenTouch(val action: Int, val x: Int, val y: Int, val downTimeMs: Long)

/**
 * Sends touches in order on its own thread. A drag produces move events faster than the display can
 * inject them, so when several moves are waiting only the newest is sent; down and up are never dropped.
 */
internal class VirtualScreenTouchPump(private val send: (VirtualScreenTouch) -> Unit) {
    private val queue = LinkedBlockingQueue<VirtualScreenTouch>()
    @Volatile private var running = true
    private val thread = Thread({
        while (running) {
            val first = try { queue.take() } catch (_: InterruptedException) { break }
            var event = first
            if (event.action == MotionEvent.ACTION_MOVE) {
                while (true) {
                    val next = queue.peek() ?: break
                    if (next.action != MotionEvent.ACTION_MOVE) break
                    event = queue.poll() ?: break
                }
            }
            runCatching { send(event) }
        }
    }, "Minis-VScreen-Touch").apply { isDaemon = true; start() }

    fun post(event: VirtualScreenTouch) { queue.offer(event) }

    fun stop() {
        running = false
        thread.interrupt()
    }
}

/**
 * Draws the virtual display's frames and turns touches into display-local touch events. Frames arrive as
 * HardwareBuffers from the service and are drawn as they come (no decode, no copy); the view counts them so
 * the viewer can show the real frame rate.
 */
@SuppressLint("ViewConstructor")
internal class VirtualScreenView(
    context: Context,
    private val onTouch: (VirtualScreenTouch) -> Unit,
) : View(context) {
    private val paint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val border = Paint().apply { color = Color.argb(40, 128, 128, 128); style = Paint.Style.STROKE; strokeWidth = 2f }
    private val frames = AtomicInteger()
    @Volatile private var bitmap: Bitmap? = null
    private var displayWidth = 0
    private var displayHeight = 0
    private var fit = VirtualScreenGeometry.Fit(0f, 0f, 0f)
    private var downTime = 0L
    private var tracking = false
    private val dest = RectF()

    /** Called from the binder thread for every frame; the buffer stays owned by the caller. */
    fun submit(buffer: HardwareBuffer) {
        // Frames only come from the UserService, which needs API 29; HardwareBuffer bitmaps do too.
        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.Q) return
        val next = runCatching { Bitmap.wrapHardwareBuffer(buffer, null) }.getOrNull() ?: return
        val previous = bitmap
        bitmap = next
        frames.incrementAndGet()
        if (displayWidth != next.width || displayHeight != next.height) {
            displayWidth = next.width
            displayHeight = next.height
            fit = VirtualScreenGeometry.fit(width, height, displayWidth, displayHeight)
        }
        postInvalidateOnAnimation()
        // The UI thread may still be drawing the previous frame; recycling on it keeps that safe.
        if (previous != null) post { if (previous !== bitmap) previous.recycle() }
    }

    /** Frames since the last call. */
    fun takeFrameCount(): Int = frames.getAndSet(0)

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        fit = VirtualScreenGeometry.fit(w, h, displayWidth, displayHeight)
    }

    override fun onDraw(canvas: Canvas) {
        val frame = bitmap ?: return
        if (frame.isRecycled) return
        dest.set(fit.left, fit.top, fit.left + displayWidth * fit.scale, fit.top + displayHeight * fit.scale)
        canvas.drawBitmap(frame, null, dest, paint)
        canvas.drawRect(dest, border)
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        bitmap?.let { if (!it.isRecycled) it.recycle() }
        bitmap = null
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (displayWidth <= 0 || displayHeight <= 0) return false
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                if (!VirtualScreenGeometry.inside(event.x, event.y, fit, displayWidth, displayHeight)) return false
                parent?.requestDisallowInterceptTouchEvent(true)
                tracking = true
                downTime = SystemClock.uptimeMillis()
                send(MotionEvent.ACTION_DOWN, event)
            }
            MotionEvent.ACTION_MOVE -> if (tracking) send(MotionEvent.ACTION_MOVE, event)
            MotionEvent.ACTION_UP -> if (tracking) { send(MotionEvent.ACTION_UP, event); tracking = false }
            MotionEvent.ACTION_CANCEL -> if (tracking) { send(MotionEvent.ACTION_CANCEL, event); tracking = false }
        }
        return true
    }

    private fun send(action: Int, event: MotionEvent) {
        val (x, y) = VirtualScreenGeometry.toDisplay(event.x, event.y, fit, displayWidth, displayHeight)
        onTouch(VirtualScreenTouch(action, x, y, downTime))
    }
}
