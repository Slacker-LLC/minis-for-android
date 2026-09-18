package com.openminis.app.xposed.system

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-eta-xposed-groups] Ported from Eta `hook/system/SystemUiHooks.kt` (Mangi-11/Eta @ c15de97).
 * Both facts here are lookup tables, and a lookup table that no longer matches its target is a
 * takeover that silently never fires.
 */
class SystemUiOcrPolicyTest {

    @Test
    fun `the ROM's own long-press haptic effect is replayed`() {
        assertEquals(1, SystemUiOcrPolicy.OCR_LONG_PRESS_HAPTIC_EFFECT_ID)
    }

    @Test
    fun `the accessor is tried before the members, and the members keep their order`() {
        assertEquals(listOf("getContext"), SystemUiOcrPolicy.CONTEXT_METHOD_NAMES)
        assertEquals(
            listOf("context", "mContext", "mOcrContext"),
            SystemUiOcrPolicy.CONTEXT_FIELD_NAMES,
        )
        assertTrue(
            "nothing is tried twice",
            SystemUiOcrPolicy.CONTEXT_FIELD_NAMES.distinct().size ==
                SystemUiOcrPolicy.CONTEXT_FIELD_NAMES.size,
        )
    }
}
