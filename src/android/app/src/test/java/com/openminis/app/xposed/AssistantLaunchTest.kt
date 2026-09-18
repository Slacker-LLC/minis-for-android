package com.openminis.app.xposed

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * [T-eta-xposed-groups] The decision half of the assistant launch, ported from Eta `PowerHooks` /
 * `AssistantManager` (Mangi-11/Eta @ c15de97): which action and package a target maps to, and that
 * "leave it to the system" is a real, reachable answer.
 */
class AssistantLaunchTest {

    @Test
    fun `the oem target means no takeover at all`() {
        assertNull(AssistantLaunch.targetFor(PowerAssistantTarget.OEM))
    }

    @Test
    fun `minis is reached through the assistant action this app declares`() {
        val target = AssistantLaunch.targetFor(PowerAssistantTarget.MINIS, ownPackage = "llc.slacker.minis")

        assertEquals("llc.slacker.minis", target?.packageName)
        assertEquals(
            listOf(AssistantLaunch.ACTION_VOICE_ASSIST, AssistantLaunch.ACTION_ASSIST),
            target?.actions,
        )
        assertEquals(
            "upstream only opens its own assistant once the role points at it",
            true,
            target?.requiresAssistantRole,
        )
    }

    @Test
    fun `gemini is reached through the standard assist action`() {
        val target = AssistantLaunch.targetFor(PowerAssistantTarget.GEMINI)

        assertEquals(AssistantLaunch.GEMINI_PACKAGE, target?.packageName)
        assertEquals(
            "upstream tries the voice command as well",
            listOf(AssistantLaunch.ACTION_ASSIST, AssistantLaunch.ACTION_VOICE_COMMAND),
            target?.actions,
        )
        assertEquals(AssistantLaunch.GEMINI_ASSIST_COMPONENT, target?.component)
        assertEquals(false, target?.requiresAssistantRole)
    }

    @Test
    fun `every target is either handled or deliberately left alone`() {
        PowerAssistantTarget.entries.forEach { target ->
            val handled = AssistantLaunch.targetFor(target) != null
            if (target == PowerAssistantTarget.OEM) {
                assertNull("the oem assistant keeps the button", AssistantLaunch.targetFor(target))
            } else {
                assert(handled)
            }
        }
    }

    @Test
    fun `an unreadable or unknown setting keeps the oem assistant`() {
        assertEquals(PowerAssistantTarget.OEM, PowerAssistantTarget.parse(null))
        assertEquals(PowerAssistantTarget.OEM, PowerAssistantTarget.parse(""))
        assertEquals(PowerAssistantTarget.OEM, PowerAssistantTarget.parse("  "))
        assertEquals(PowerAssistantTarget.OEM, PowerAssistantTarget.parse("chatgpt"))
    }

    @Test
    fun `known settings parse case insensitively`() {
        assertEquals(PowerAssistantTarget.MINIS, PowerAssistantTarget.parse("MINIS"))
        assertEquals(PowerAssistantTarget.GEMINI, PowerAssistantTarget.parse(" gemini "))
        assertEquals(PowerAssistantTarget.OEM, PowerAssistantTarget.parse("oem"))
    }
}
