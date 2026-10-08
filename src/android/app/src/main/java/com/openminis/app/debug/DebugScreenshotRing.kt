package com.openminis.app.debug

import android.app.Activity
import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.view.PixelCopy
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.resume

/**
 * Capacity-bounded ring buffer of labelled screenshots, mirroring iOS
 * `debug.screenshot.{capture,list,get,clear}`. Stores PNG bytes in memory —
 * 16 entries max, oldest evicted. Each entry is keyed by a monotonically
 * increasing id so callers can reference snapshots by `{id}` after a tap.
 */
object DebugScreenshotRing {

    private const val CAPACITY = 16

    data class Entry(
        val id: Int,
        val label: String,
        val timestamp: Long,
        val pngBytes: ByteArray,
    )

    private val nextId = AtomicInteger(1)
    private val entries = ArrayDeque<Entry>()

    @Synchronized
    private fun add(entry: Entry) {
        entries.addLast(entry)
        while (entries.size > CAPACITY) entries.removeFirst()
    }

    @Synchronized
    fun list(): List<Entry> = entries.toList()

    @Synchronized
    fun get(id: Int?): Entry? = if (id == null) entries.lastOrNull() else entries.find { it.id == id }

    @Synchronized
    fun clearAll(): Int {
        val n = entries.size
        entries.clear()
        return n
    }

    suspend fun capture(activity: Activity, label: String, scale: Float): Entry {
        val bitmap = withContext(Dispatchers.Main) { captureBitmap(activity, scale) }
        val baos = ByteArrayOutputStream()
        try {
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, baos)
        } finally {
            bitmap.recycle()
        }
        val entry = Entry(
            id = nextId.getAndIncrement(),
            label = label,
            timestamp = System.currentTimeMillis(),
            pngBytes = baos.toByteArray(),
        )
        add(entry)
        return entry
    }

    private suspend fun captureBitmap(activity: Activity, scale: Float): Bitmap =
        suspendCancellableCoroutine { cont ->
            val rootView = activity.window.decorView.rootView
            val width = (rootView.width * scale).toInt().coerceAtLeast(1)
            val height = (rootView.height * scale).toInt().coerceAtLeast(1)
            val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            // The copy target belongs to this call until it is handed to the caller: a failed copy hands over the
            // canvas fallback instead and frees the target, and a cancelled caller never receives either.
            PixelCopy.request(
                activity.window, bitmap,
                { result ->
                    if (result == PixelCopy.SUCCESS) {
                        if (cont.isActive) cont.resume(bitmap) else bitmap.recycle()
                    } else {
                        bitmap.recycle()
                        if (cont.isActive) {
                            val fallback = canvasCapture(rootView, scale)
                            cont.resume(fallback)
                        }
                    }
                },
                Handler(Looper.getMainLooper()),
            )
        }

    private fun canvasCapture(view: android.view.View, scale: Float): Bitmap {
        val width = (view.width * scale).toInt().coerceAtLeast(1)
        val height = (view.height * scale).toInt().coerceAtLeast(1)
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = android.graphics.Canvas(bitmap)
        if (scale != 1f) canvas.scale(scale, scale)
        view.draw(canvas)
        return bitmap
    }

    fun summary(entry: Entry): JSONObject = JSONObject().apply {
        put("id", entry.id)
        put("label", entry.label)
        put("timestamp", entry.timestamp)
        put("size", entry.pngBytes.size)
    }

    fun listJson(): JSONObject {
        val arr = JSONArray()
        for (e in list()) arr.put(summary(e))
        return JSONObject().put("count", arr.length()).put("entries", arr)
    }
}
