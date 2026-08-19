package com.openminis.app.pet

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.os.Handler
import android.os.Looper
import android.view.View

class PetSpriteView(context: Context) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val main = Handler(Looper.getMainLooper())
    private var bitmap: Bitmap? = null
    private var manifest: PetManifest? = null
    private var state = PetState.IDLE
    private var frame = 0
    /** Screen-off freeze: nothing drawn is visible, so stop drawing entirely. */
    private var paused = false
    // Reused every frame. Allocating a Rect + RectF per draw at ~9 fps is a
    // steady trickle of garbage for something whose values barely change.
    private val srcRect = Rect()
    private val dstRect = RectF()

    var animationSpeed: Float = 1.0f
        set(value) { field = value.coerceIn(0.5f, 2.0f) }

    private val tick = object : Runnable {
        override fun run() {
            if (!isAttachedToWindow || paused) return
            val animation = manifest?.animation(state) ?: state.defaultAnimation
            // coerceAtLeast(1): a malformed pack that reached us with 0 frames
            // would otherwise divide by zero and take the service down.
            frame = (frame + 1) % animation.frameCount.coerceAtLeast(1)
            invalidate()
            val delay = (animation.frameDurationMs / animationSpeed).toLong().coerceAtLeast(40L)
            main.postDelayed(this, delay)
        }
    }

    fun loadPet(pet: InstalledPet) {
        bitmap?.recycle()
        // A 1536x1872 sheet is ~11.5 MB at ARGB_8888. The pet draws at roughly
        // 144dp, so on a low-density screen — or at the smallest size setting —
        // half resolution is still more pixels than the display can show.
        // ARGB_8888 is kept regardless: the sprite needs its alpha channel.
        val opts = BitmapFactory.Options().apply {
            inSampleSize = spriteSampleSize(pet.manifest)
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        bitmap = BitmapFactory.decodeFile(pet.spritesheet.absolutePath, opts)
            ?: error("Failed to decode spritesheet")
        manifest = pet.manifest
        frame = 0
        invalidate()
    }

    fun setState(next: PetState) {
        if (state == next) return
        state = next
        frame = 0
        invalidate()
        // Re-arm immediately so a slow previous state's delay doesn't make a
        // newly selected reaction feel unresponsive.
        if (isAttachedToWindow) {
            main.removeCallbacks(tick)
            main.post(tick)
        }
    }

    fun currentState(): PetState = state

    /** Stop animating while the screen is off; frames drawn now are never seen. */
    fun pauseAnimation() {
        if (paused) return
        paused = true
        main.removeCallbacks(tick)
    }

    fun resumeAnimation() {
        if (!paused) return
        paused = false
        if (isAttachedToWindow) main.post(tick)
    }

    /**
     * Halve the atlas only when even half its cells still exceed what the pet
     * is drawn at, so the common case keeps full resolution.
     */
    private fun spriteSampleSize(m: PetManifest): Int {
        val drawnPx = 144f * resources.displayMetrics.density
        return if (m.cellWidth >= drawnPx * 2) 2 else 1
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        main.removeCallbacks(tick)
        if (!paused) main.post(tick)
    }

    override fun onDetachedFromWindow() {
        main.removeCallbacks(tick)
        super.onDetachedFromWindow()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val b = bitmap ?: return
        val m = manifest ?: return
        val animation = m.animation(state)
        if (animation.row >= m.rows || frame >= animation.frameCount || frame >= m.columns) return
        // Sample-size shrinks the decoded atlas, so cell geometry has to be
        // read off the bitmap rather than the manifest's original pixel values.
        val cellW = b.width / m.columns
        val cellH = b.height / m.rows
        srcRect.set(frame * cellW, animation.row * cellH, (frame + 1) * cellW, (animation.row + 1) * cellH)
        dstRect.set(0f, 0f, width.toFloat(), height.toFloat())
        canvas.drawBitmap(b, srcRect, dstRect, paint)
    }
}
