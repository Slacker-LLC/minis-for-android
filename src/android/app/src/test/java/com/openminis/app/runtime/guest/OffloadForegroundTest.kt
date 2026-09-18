package com.openminis.app.runtime.guest

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-android-background-launch] The body for "Android refused the launch
 * because Minis is in the background".
 *
 * A background app cannot start activities, and the platform reports it with
 * the same ActivityNotFoundException a genuinely missing handler throws. The
 * device pass saw `android-alarm list` answer `no_clock_app … install a Clock
 * app` on a phone whose Clock was installed and which opened fine with Minis in
 * front. These pin the wording that separates the two.
 */
class OffloadForegroundTest {

    @Test
    fun `the background body names the action and the reason`() {
        val body = OffloadForeground.backgroundLaunchBody("ACTION_VIEW")
        assertEquals("background_launch_blocked", body.getString("error"))
        assertEquals("ACTION_VIEW", body.getString("action"))
        val message = body.getString("message")
        assertTrue(message, message.contains("not on screen"))
        assertTrue(message, message.contains("background activity starts"))
        assertFalse(body.has("subject"))
    }

    @Test
    fun `a subject keeps the url field the open path publishes`() {
        val body = OffloadForeground.backgroundLaunchBody("ACTION_VIEW", "https://example.com")
        assertEquals("https://example.com", body.getString("subject"))
    }
}

