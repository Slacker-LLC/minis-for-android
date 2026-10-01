package com.openminis.app.tools.android.vscreen.service.internal

import android.util.Log

/**
 * Keeps the virtual display from holding the system's top focus. Launching an activity on it, or a touch
 * injected into it, makes that display the focused one, and the physical screen's windows then stop receiving
 * key events (the keyboard, Back, volume) until it is touched. Handing focus back to the physical display's
 * top task after each such action leaves the two screens independent.
 */
internal object FocusBridge {
    private const val TAG = "VScreenFocus"
    @Volatile private var lastRestoreMs = 0L

    private val handler by lazy {
        val thread = android.os.HandlerThread("minis-vscreen-focus").apply { start() }
        android.os.Handler(thread.looper)
    }

    /**
     * What an action starts (an app, a permission dialog) takes focus a moment AFTER the action returns, so
     * the focus is also handed back a little later, a few times.
     */
    fun restorePhysicalFocusSoon() {
        restorePhysicalFocus()
        handler.removeCallbacksAndMessages(null)
        for (delay in longArrayOf(350L, 900L, 2000L)) handler.postDelayed({ restorePhysicalFocus(force = true) }, delay)
    }

    /** Gives focus back to the top task of display 0. Cheap enough to call after every injected gesture. */
    fun restorePhysicalFocus(force: Boolean = false) {
        val now = android.os.SystemClock.uptimeMillis()
        if (!force && now - lastRestoreMs < MIN_INTERVAL_MS) return
        lastRestoreMs = now
        runCatching {
            val atm = Class.forName("android.app.ActivityTaskManager").getMethod("getService").invoke(null)
                ?: return
            val taskId = topTaskIdOnDisplay(atm, 0) ?: return
            val setter = atm.javaClass.methods.firstOrNull {
                (it.name == "setFocusedRootTask" || it.name == "setFocusedTask") &&
                    it.parameterTypes.size == 1 && it.parameterTypes[0] == Int::class.javaPrimitiveType
            } ?: run {
                Log.w(TAG, "no setFocusedTask on this system")
                return
            }
            setter.invoke(atm, taskId)
        }.onFailure { Log.w(TAG, "restore focus failed", it) }
    }

    private fun topTaskIdOnDisplay(atm: Any, displayId: Int): Int? {
        val methods = atm.javaClass.methods.filter { it.name == "getTasks" }
        val tasks: List<*> = methods.firstOrNull { it.parameterTypes.size == 4 }?.let {
            // getTasks(maxNum, filterOnlyVisibleRecents, keepIntentExtra, displayId)
            it.invoke(atm, 20, false, false, displayId) as? List<*>
        } ?: methods.firstOrNull { it.parameterTypes.size == 1 }?.let {
            it.invoke(atm, 50) as? List<*>
        } ?: return null
        for (task in tasks) {
            if (task == null) continue
            val onDisplay = runCatching { task.javaClass.getField("displayId").getInt(task) }.getOrDefault(displayId)
            if (onDisplay != displayId) continue
            return runCatching { task.javaClass.getField("taskId").getInt(task) }.getOrNull()
        }
        return null
    }

    private const val MIN_INTERVAL_MS = 120L
}
