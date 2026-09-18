package com.openminis.app.xposed.hyperos

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

import com.openminis.app.xposed.hyperos.ScreenSearchRequest.Companion.FUNCTION_KEY
import com.openminis.app.xposed.hyperos.ScreenSearchRequest.Companion.NAV_LONG_PRESS
import com.openminis.app.xposed.hyperos.ScreenSearchRequest.Companion.SCREEN_RECOGNITION
import com.openminis.app.xposed.hyperos.ScreenSearchRequest.Companion.START_FROM_KEY
import com.openminis.app.xposed.hyperos.ScreenSearchRequest.Companion.TRIGGER_TYPE_KEY

/**
 * [T-eta-xposed-groups] Ported from Eta `hook/hyperos/HyperOsScreenSearchRequest.kt`
 * (Mangi-11/Eta @ c15de97). The same assist action carries every assistant request on these ROMs,
 * so all four fields have to agree before the gesture is taken over.
 */
class ScreenSearchRequestTest {

    private fun request(
        functionKey: String? = SCREEN_RECOGNITION,
        triggerType: String? = NAV_LONG_PRESS,
        startFrom: String? = "long_press_fullscreen_gesture_line",
    ) = ScreenSearchRequest(
        action = "android.intent.action.ASSIST",
        functionKey = functionKey,
        triggerType = triggerType,
        startFrom = startFrom,
    )

    @Test
    fun `a navigation long press screen recognition request matches`() {
        assertTrue(request().matchesScreenSearch())
    }

    @Test
    fun `every navigation source the OEM uses is accepted`() {
        ScreenSearchRequest.NAVIGATION_SOURCES.forEach { source ->
            assertTrue(source, request(startFrom = source).matchesScreenSearch())
        }
    }

    @Test
    fun `an ordinary assistant request is not taken over`() {
        assertFalse("not screen recognition", request(functionKey = "start_assistant").matchesScreenSearch())
        assertFalse("not a navigation long press", request(triggerType = "VoiceButton").matchesScreenSearch())
        assertFalse("some other gesture", request(startFrom = "long_press_power_key").matchesScreenSearch())
    }

    @Test
    fun `a missing field means no match`() {
        assertFalse(request(functionKey = null).matchesScreenSearch())
        assertFalse(request(triggerType = null).matchesScreenSearch())
        assertFalse(request(startFrom = null).matchesScreenSearch())
        assertFalse(
            ScreenSearchRequest(action = null, functionKey = SCREEN_RECOGNITION, triggerType = NAV_LONG_PRESS, startFrom = "two_gesture_long_press")
                .matchesScreenSearch(),
        )
    }

    @Test
    fun `the field keys are the ones the ROM writes`() {
        assertTrue(FUNCTION_KEY == "voice_assist_function_key")
        assertTrue(TRIGGER_TYPE_KEY == "triggerType")
        assertTrue(START_FROM_KEY == "voice_assist_start_from_key")
    }
}
