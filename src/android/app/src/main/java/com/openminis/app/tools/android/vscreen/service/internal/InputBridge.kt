/*
 * Adapted for Minis from ShadowAuto (`android-notes/ShadowAuto`, commit `611e5eb0e1c94befda3c17c97438912dfd30e8a8`); modified.
 * Original source: android-shell/src/main/java/com/silentauto/shell/InputBridge.java.
 * Licensed under Apache-2.0; see third_party/shadowauto/LICENSE.
 */
package com.openminis.app.tools.android.vscreen.service.internal

import android.content.Context
import android.os.SystemClock
import android.view.InputDevice
import android.view.InputEvent
import android.view.KeyCharacterMap
import android.view.KeyEvent
import android.view.MotionEvent
import java.lang.reflect.Method

internal class InputBridge {
    private val inputManager = ShellContext.get().getSystemService(Context.INPUT_SERVICE)
        ?: throw IllegalStateException("input_manager_unavailable")
    private val inject: Method = inputManager.javaClass.getMethod("injectInputEvent", InputEvent::class.java, Int::class.javaPrimitiveType)
    @Volatile private var gestureDownTime = 0L
    private val setDisplayId: Method = InputEvent::class.java.getMethod("setDisplayId", Int::class.javaPrimitiveType)

    fun tap(displayId: Int, x: Int, y: Int): Boolean {
        val now = SystemClock.uptimeMillis()
        return send(displayId, motion(now, now, MotionEvent.ACTION_DOWN, x, y)) &&
            send(displayId, motion(now, now + 60, MotionEvent.ACTION_UP, x, y))
    }

    fun longPress(displayId: Int, x: Int, y: Int, durationMs: Int): Boolean {
        val duration = durationMs.coerceIn(450, 5_000)
        val down = SystemClock.uptimeMillis()
        val downOk = send(displayId, motion(down, down, MotionEvent.ACTION_DOWN, x, y))
        SystemClock.sleep(duration.toLong())
        val upOk = send(displayId, motion(down, down + duration, MotionEvent.ACTION_UP, x, y))
        return downOk && upOk
    }

    fun swipe(displayId: Int, startX: Int, startY: Int, endX: Int, endY: Int, durationMs: Int): Boolean {
        val duration = durationMs.coerceIn(120, 2_000)
        val steps = (duration / 24).coerceAtLeast(6)
        val down = SystemClock.uptimeMillis()
        var ok = send(displayId, motion(down, down, MotionEvent.ACTION_DOWN, startX, startY))
        for (index in 1 until steps) {
            val fraction = index.toFloat() / steps
            val x = (startX + (endX - startX) * fraction).toInt()
            val y = (startY + (endY - startY) * fraction).toInt()
            ok = send(displayId, motion(down, down + (duration * fraction).toLong(), MotionEvent.ACTION_MOVE, x, y)) && ok
            SystemClock.sleep(8)
        }
        return send(displayId, motion(down, down + duration, MotionEvent.ACTION_UP, endX, endY)) && ok
    }

    /** One raw touch event, for a finger that is still down (the viewer forwards a drag as it happens). */
    fun touch(displayId: Int, action: Int, x: Int, y: Int, downTimeMs: Long): Boolean {
        if (action != MotionEvent.ACTION_DOWN && action != MotionEvent.ACTION_MOVE && action != MotionEvent.ACTION_UP &&
            action != MotionEvent.ACTION_CANCEL
        ) return false
        val now = SystemClock.uptimeMillis()
        // The gesture's down time is taken here, on the clock the events are stamped with, so every event
        // of one drag carries the same value whatever the caller's clock said.
        if (action == MotionEvent.ACTION_DOWN) gestureDownTime = now
        return send(displayId, motion(gestureDownTime, now, action, x, y))
    }

    fun key(displayId: Int, keyCode: Int): Boolean {
        val now = SystemClock.uptimeMillis()
        return send(displayId, KeyEvent(now, now, KeyEvent.ACTION_DOWN, keyCode, 0)) &&
            send(displayId, KeyEvent(now, now + 20, KeyEvent.ACTION_UP, keyCode, 0))
    }

    /** False for characters with no key-event mapping (CJK and most non-ASCII): those need a node-level write. */
    fun canType(value: String): Boolean =
        value.isEmpty() || KeyCharacterMap.load(KeyCharacterMap.VIRTUAL_KEYBOARD).getEvents(value.toCharArray()) != null

    fun text(displayId: Int, value: String): Boolean {
        if (value.isEmpty()) return true
        val events = KeyCharacterMap.load(KeyCharacterMap.VIRTUAL_KEYBOARD).getEvents(value.toCharArray())
            ?: return false
        var ok = true
        events.forEach { ok = send(displayId, it) && ok }
        return ok
    }

    private fun motion(down: Long, eventTime: Long, action: Int, x: Int, y: Int): MotionEvent =
        MotionEvent.obtain(down, eventTime, action, x.toFloat(), y.toFloat(), 0).apply {
            source = InputDevice.SOURCE_TOUCHSCREEN
        }

    private fun send(displayId: Int, event: InputEvent): Boolean {
        if (displayId == 0) throw IllegalArgumentException("physical_display_refused")
        return try {
            setDisplayId.invoke(event, displayId)
            inject.invoke(inputManager, event, INJECT_WAIT_FOR_FINISH) as? Boolean ?: true
        } finally {
            if (event is MotionEvent) event.recycle()
        }
    }

    companion object {
        private const val INJECT_WAIT_FOR_FINISH = 2
    }
}
