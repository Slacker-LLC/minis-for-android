package com.openminis.app.xposed.hyperos

import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import java.lang.ref.WeakReference
import kotlin.math.abs

/**
 * [T-eta-xposed-groups] A long-press detector for one navigation view, for the launcher builds whose
 * own detection cannot be hooked.
 *
 * Ported from Eta `hook/hyperos/HyperOsLongPressGesture.kt` (Mangi-11/Eta @ c15de97); attribution
 * in THIRD_PARTY_LICENSES.md. The view holds one gesture at a time; the pending runnable keeps only a
 * weak reference to the view and never captures a hook chain, so a destroyed view is not kept alive
 * and no delayed task can run inside somebody else's call stack.
 */
class HyperOsLongPressGesture(
    view: View,
    private val enabled: () -> Boolean,
    private val trigger: (View) -> Boolean,
) : View.OnAttachStateChangeListener {

    private val owner = WeakReference(view)
    private val slop = ViewConfiguration.get(view.context).scaledTouchSlop
    private var startX = 0f
    private var startY = 0f

    var pending = false
        private set

    private var triggered = false

    private val longPress = Runnable {
        val current = owner.get()
        if (pending && current != null && current.isAttachedToWindow && enabled()) {
            pending = false
            triggered = trigger(current)
        } else {
            cancel()
        }
    }

    init {
        view.addOnAttachStateChangeListener(this)
    }

    /** True asks the caller to turn this event into a CANCEL, ending the launcher's own gesture. */
    fun onTouch(event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_DOWN) {
            cancel()
            triggered = false
            if (enabled() && event.pointerCount == 1) {
                startX = event.rawX
                startY = event.rawY
                pending = true
                val posted = owner.get()
                    ?.postDelayed(longPress, ViewConfiguration.getLongPressTimeout().toLong())
                if (posted != true) cancel()
            }
        } else if (triggered) {
            triggered = false
            cancel()
            return true
        } else if (!enabled() ||
            event.pointerCount != 1 ||
            event.actionMasked != MotionEvent.ACTION_MOVE ||
            abs(event.rawX - startX) > slop ||
            abs(event.rawY - startY) > slop
        ) {
            cancel()
        }
        return false
    }

    fun cancel() {
        pending = false
        owner.get()?.removeCallbacks(longPress)
    }

    override fun onViewAttachedToWindow(view: View) = Unit

    override fun onViewDetachedFromWindow(view: View) {
        cancel()
        triggered = false
    }
}
