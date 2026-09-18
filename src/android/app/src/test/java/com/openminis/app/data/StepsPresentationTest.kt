package com.openminis.app.data

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * [T-android-work-process] `stepsPresentation` decode rules. The preference is
 * user-visible (Appearance → Work process) and read on the chat flatten path,
 * so an unknown / corrupted / future value must degrade to the default instead
 * of failing open.
 */
class StepsPresentationTest {

    @Test
    fun `missing value defaults to grouped`() {
        assertEquals(StepsPresentation.GROUPED, StepsPresentation.fromStoredValue(null))
    }

    @Test
    fun `unknown value defaults to grouped`() {
        assertEquals(StepsPresentation.GROUPED, StepsPresentation.fromStoredValue(""))
        assertEquals(StepsPresentation.GROUPED, StepsPresentation.fromStoredValue("Grouped"))
        assertEquals(StepsPresentation.GROUPED, StepsPresentation.fromStoredValue("sideways"))
    }

    @Test
    fun `stored spellings round-trip`() {
        assertEquals("grouped", StepsPresentation.GROUPED.storedValue)
        assertEquals("perTool", StepsPresentation.PER_TOOL.storedValue)
        for (value in StepsPresentation.entries) {
            assertEquals(value, StepsPresentation.fromStoredValue(value.storedValue))
        }
    }

    @Test
    fun `key name matches the roadmap contract`() {
        assertEquals("stepsPresentation", StepsPresentationPrefs.KEY_STEPS_PRESENTATION)
    }
}
