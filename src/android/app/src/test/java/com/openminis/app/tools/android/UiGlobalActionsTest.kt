package com.openminis.app.tools.android

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-eta-ui-system-panel] Ported from Eta's `open_system_panel` family
 * (Mangi-11/Eta @ c15de97). The schema list, the executor's dispatch and the name in the
 * tool result all read this enum, so the wire names are the contract worth pinning.
 */
class UiGlobalActionsTest {

    @Test
    fun `every action the schema advertises is dispatched`() {
        assertEquals(
            listOf("back", "home", "recents", "notifications", "quick_settings"),
            UiGlobalAction.wireValues,
        )
    }

    @Test
    fun `parsing is case insensitive and trims`() {
        assertEquals(UiGlobalAction.RECENTS, UiGlobalAction.parse("recents"))
        assertEquals(UiGlobalAction.RECENTS, UiGlobalAction.parse(" RECENTS "))
        assertEquals(UiGlobalAction.QUICK_SETTINGS, UiGlobalAction.parse("Quick_Settings"))
    }

    @Test
    fun `an unknown or missing action is refused instead of guessed`() {
        assertNull(UiGlobalAction.parse("power"))
        assertNull(UiGlobalAction.parse(""))
        assertNull(UiGlobalAction.parse(null))
        assertNull(UiGlobalAction.parse("back home"))
    }

    @Test
    fun `eta spellings for the panels still resolve`() {
        assertEquals(UiGlobalAction.NOTIFICATIONS, UiGlobalAction.parse("notification"))
        assertEquals(UiGlobalAction.QUICK_SETTINGS, UiGlobalAction.parse("quicksettings"))
        assertEquals(UiGlobalAction.QUICK_SETTINGS, UiGlobalAction.parse("Settings"))
    }

    @Test
    fun `labels are present and unique for the trace`() {
        val labels = UiGlobalAction.entries.map { it.label }

        assertTrue(labels.all { it.isNotBlank() })
        assertEquals(labels.size, labels.toSet().size)
        assertEquals(UiGlobalAction.entries.size, UiGlobalAction.wireValues.toSet().size)
    }
}
