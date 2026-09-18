package com.openminis.app.xposed.google

import com.openminis.app.xposed.ModulePrefs
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-eta-xposed-groups] Ported from Eta `hook/google/GoogleAppHooks.kt` (Mangi-11/Eta @ c15de97).
 * The point of the two switches is that they are separate choices, and the point of the re-check is
 * that a command queued for one lock-screen state must not fire in the other.
 */
class GoogleVoiceCommandPolicyTest {

    @After
    fun tearDown() = ModulePrefs.attach(null)

    @Test
    fun `the lock screen and the screen on path are gated by different switches`() {
        assertEquals(
            ModulePrefs.Keys.LOCKSCREEN_VOICE_COMMAND,
            GoogleVoiceCommandPolicy.prefKey(keyguardLocked = true),
        )
        assertEquals(
            ModulePrefs.Keys.SCREEN_ON_VOICE_COMMAND,
            GoogleVoiceCommandPolicy.prefKey(keyguardLocked = false),
        )
    }

    @Test
    fun `both voice-command switches start off`() {
        ModulePrefs.attach(null)

        assertFalse(ModulePrefs.isEnabled(ModulePrefs.Keys.LOCKSCREEN_VOICE_COMMAND))
        assertFalse(ModulePrefs.isEnabled(ModulePrefs.Keys.SCREEN_ON_VOICE_COMMAND))
    }

    @Test
    fun `a queued command is dropped when the lock screen state flipped in between`() {
        assertTrue(GoogleVoiceCommandPolicy.stillEligible(true, true))
        assertTrue(GoogleVoiceCommandPolicy.stillEligible(false, false))
        assertFalse(GoogleVoiceCommandPolicy.stillEligible(true, false))
        assertFalse(
            "an unlocked session must not get a lock-screen voice command",
            GoogleVoiceCommandPolicy.stillEligible(false, true),
        )
    }

    @Test
    fun `the scenario names the case the log line is about`() {
        assertEquals("lockscreen", GoogleVoiceCommandPolicy.scenario(true))
        assertEquals("screen-on", GoogleVoiceCommandPolicy.scenario(false))
    }

    @Test
    fun `the same floaty instance is only answered once per window`() {
        var now = 0L
        val gate = VoiceCommandGate(6_000L, clock = { now })
        val first = Any()
        val second = Any()

        assertTrue(gate.shouldSend(first))
        assertTrue("another instance is another request", gate.shouldSend(second))

        now += 1_000
        assertFalse("the same instance resumed again inside the window", gate.shouldSend(first))

        now += 5_000
        assertTrue("the window has passed", gate.shouldSend(first))
    }

    @Test
    fun `a cleared attempt may retry immediately`() {
        var now = 0L
        val gate = VoiceCommandGate(6_000L, clock = { now })
        val instance = Any()

        assertTrue(gate.shouldSend(instance))
        gate.clear(instance)

        assertTrue("a command that did not go out must not eat the window", gate.shouldSend(instance))
    }
}
