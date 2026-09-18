package com.openminis.app.accessibility

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-eta-text-insert] Ported from Eta `restoreClipboardIfStillOwned` (Mangi-11/Eta @ c15de97).
 * The decision is small and the failure it prevents is not: clobbering what the user copied while
 * the app was pasting into a field.
 */
class ClipboardRestorePolicyTest {

    @Test
    fun `a temporary clip carries its own label`() {
        assertEquals(
            ClipboardRestorePolicy.TEMPORARY_PREFIX + ":7",
            ClipboardRestorePolicy.temporaryLabel(7),
        )
    }

    @Test
    fun `the clipboard is only restored while it still holds our clip`() {
        val label = ClipboardRestorePolicy.temporaryLabel(1)

        assertTrue(ClipboardRestorePolicy.shouldRestore(label, label))
        assertFalse(
            "the user copied something else in the meantime",
            ClipboardRestorePolicy.shouldRestore("some other clip", label),
        )
        assertFalse(
            ClipboardRestorePolicy.shouldRestore(null, label),
        )
        assertFalse(
            "another paste of ours is a different clip",
            ClipboardRestorePolicy.shouldRestore(ClipboardRestorePolicy.temporaryLabel(2), label),
        )
    }

    @Test
    fun `the sensitive marker is the platform key`() {
        assertEquals(
            "android.content.extra.IS_SENSITIVE",
            ClipboardRestorePolicy.EXTRA_IS_SENSITIVE,
        )
    }
}
