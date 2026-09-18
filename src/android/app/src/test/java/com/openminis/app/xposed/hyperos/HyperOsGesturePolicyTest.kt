package com.openminis.app.xposed.hyperos

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * [T-eta-xposed-groups] Ported from Eta `hook/hyperos/HyperOsLauncherHooks.kt` (Mangi-11/Eta @
 * c15de97). One launcher version answers its gesture with void and the next with a boolean, and an
 * answer of the wrong shape is a dropped gesture rather than a visible error.
 */
class HyperOsGesturePolicyTest {

    @Test
    fun `a void entry point is answered with null`() {
        assertNull(HyperOsGesturePolicy.takeoverResult(Void.TYPE))
    }

    @Test
    fun `a boolean entry point is answered as handled`() {
        assertEquals(
            true,
            HyperOsGesturePolicy.takeoverResult(Boolean::class.javaPrimitiveType!!),
        )
    }

    @Test
    fun `anything that is not void counts as handled`() {
        assertEquals(true, HyperOsGesturePolicy.takeoverResult(Any::class.java))
        assertEquals(true, HyperOsGesturePolicy.takeoverResult(Int::class.javaPrimitiveType!!))
    }
}
