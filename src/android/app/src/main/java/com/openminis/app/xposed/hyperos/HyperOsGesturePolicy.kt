package com.openminis.app.xposed.hyperos

/**
 * [T-eta-xposed-groups] The rule every HyperOS gesture entry point shares.
 *
 * Ported from Eta `hook/hyperos/HyperOsLauncherHooks.kt` (Mangi-11/Eta @ c15de97); attribution in
 * THIRD_PARTY_LICENSES.md. The launcher publishes the same gesture through several helper classes
 * whose methods disagree about their return type, and answering a boolean-returning entry point with
 * null (or a void one with true) is the kind of mistake that only shows up as a dropped gesture.
 */
object HyperOsGesturePolicy {

    /**
     * What the ROM's own method must be answered with when its gesture was taken over: null for a
     * void method, true for anything that expects a boolean, because a claimed gesture counts as
     * handled.
     */
    fun takeoverResult(returnType: Class<*>): Any? = if (returnType == Void.TYPE) null else true
}
