package com.openminis.app.xposed.hyperos

import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import com.openminis.app.xposed.HookRegistrar
import com.openminis.app.xposed.HookSupport
import com.openminis.app.xposed.safeLogType
import java.util.WeakHashMap

/**
 * [T-eta-xposed-groups] The launcher builds whose long press the navigation view keeps to itself.
 *
 * Ported from Eta `hook/hyperos/HyperOsLegacyGesture.kt` (Mangi-11/Eta @ c15de97); attribution in
 * THIRD_PARTY_LICENSES.md. When the launcher already carries its own long-press check with an unknown
 * callback, this bails out instead of competing with it; otherwise the touch stream is watched with
 * [HyperOsLongPressGesture] and only the events of a gesture that was really taken over are turned
 * into a CANCEL, so a cancelled gesture cannot leave the launcher waiting for a stream that ended.
 */
object HyperOsLegacyGesture {

    fun install(hooks: HookRegistrar, classLoader: ClassLoader) {
        val type = HookSupport.findClassOrNull(classLoader, "com.miui.home.recents.NavStubView")
        val touch = type?.let { HookSupport.findMethod(it, "onTouchEvent", MotionEvent::class.java) }
        if (type == null ||
            !View::class.java.isAssignableFrom(type) ||
            touch?.declaringClass != type ||
            touch.returnType != Boolean::class.javaPrimitiveType
        ) {
            hooks.missing(
                "hyperos.legacy-touch",
                "NavStubView.onTouchEvent",
                "HyperOS: no legacy navigation view touch entry on this build",
            )
            return
        }
        if (HookSupport.findField(type, "mCheckLongPress") != null) {
            hooks.skipped(
                "hyperos.legacy-touch",
                "NavStubView.onTouchEvent",
                "HyperOS: the launcher already checks long presses with an unknown callback, " +
                    "keeping its own gesture",
            )
            return
        }
        // View callbacks run serially on their own UI thread; the weak key plus the weak reference
        // inside the gesture keep a destroyed view from being retained.
        val gestures = WeakHashMap<View, HyperOsLongPressGesture>()
        hooks.intercept("hyperos.legacy-touch", touch, "NavStubView.onTouchEvent") { chain ->
            val view = chain.getThisObject() as View
            val event = chain.getArg(0) as MotionEvent
            val gesture = gestures.getOrPut(view) {
                HyperOsLongPressGesture(view, HyperOsSearchTrigger::isEnabled) { current ->
                    val started = HyperOsSearchTrigger.trigger(current.context, hooks.logger)
                    if (started) {
                        try {
                            current.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                        } catch (exception: Exception) {
                            hooks.logger.warnThrottled("hyperos_haptic_failed") {
                                "HyperOS: the long-press feedback failed " +
                                    "(${exception.safeLogType()})"
                            }
                        }
                    }
                    started
                }
            }
            if (gesture.onTouch(event)) {
                val cancel = MotionEvent.obtain(event).apply { action = MotionEvent.ACTION_CANCEL }
                try {
                    // The framework's Chain takes the replacement arguments as an array.
                    chain.proceed(arrayOf<Any>(cancel))
                } finally {
                    cancel.recycle()
                }
            } else {
                val result = chain.proceed()
                if (result != true) gesture.cancel()
                result
            }
        } ?: return

        val prepare = HookSupport.findMethod(type, "startRecentsAnimationPre")
        if (prepare?.returnType == Void.TYPE) {
            hooks.intercept("hyperos.legacy-recents", prepare, "NavStubView.startRecentsAnimationPre") { chain ->
                if (HyperOsSearchTrigger.isEnabled() &&
                    gestures[chain.getThisObject()]?.pending == true
                ) {
                    null
                } else {
                    chain.proceed()
                }
            }
        } else {
            hooks.skipped(
                "hyperos.legacy-recents",
                "NavStubView.startRecentsAnimationPre",
                "HyperOS: no recents pre-start entry on this build",
            )
        }
    }
}
