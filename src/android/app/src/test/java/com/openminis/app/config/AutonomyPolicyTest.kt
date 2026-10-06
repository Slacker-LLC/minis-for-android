package com.openminis.app.config

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AutonomyPolicyTest {
    private val normal = listOf(ConfigRisk.NORMAL)

    @Test
    fun `ask confirms everything`() {
        assertTrue(AutonomyPolicy.configNeedsConfirmation(AutonomyMode.ASK, normal, touchesSecret = false))
        assertTrue(AutonomyPolicy.configNeedsConfirmation(AutonomyMode.ASK, emptyList(), touchesSecret = false))
    }

    @Test
    fun `smart lets ordinary non-secret settings through`() {
        assertFalse(AutonomyPolicy.configNeedsConfirmation(AutonomyMode.SMART, normal, touchesSecret = false))
        assertFalse(AutonomyPolicy.configNeedsConfirmation(AutonomyMode.SMART, listOf(ConfigRisk.NORMAL, ConfigRisk.NORMAL), false))
    }

    @Test
    fun `smart still asks for secrets and for anything risky, even in a mixed batch`() {
        assertTrue(AutonomyPolicy.configNeedsConfirmation(AutonomyMode.SMART, normal, touchesSecret = true))
        assertTrue(AutonomyPolicy.configNeedsConfirmation(AutonomyMode.SMART, listOf(ConfigRisk.SENSITIVE), false))
        assertTrue(AutonomyPolicy.configNeedsConfirmation(AutonomyMode.SMART, listOf(ConfigRisk.DESTRUCTIVE), false))
        assertTrue(AutonomyPolicy.configNeedsConfirmation(AutonomyMode.SMART, listOf(ConfigRisk.NORMAL, ConfigRisk.DESTRUCTIVE), false))
    }

    @Test
    fun `full never asks`() {
        assertFalse(AutonomyPolicy.configNeedsConfirmation(AutonomyMode.FULL, listOf(ConfigRisk.DESTRUCTIVE), touchesSecret = true))
    }

    @Test
    fun `an unknown or missing stored mode falls back to smart, never to full`() {
        assertEquals(AutonomyMode.SMART, AutonomyMode.fromWire(null))
        assertEquals(AutonomyMode.SMART, AutonomyMode.fromWire("yolo"))
        assertEquals(AutonomyMode.SMART, AutonomyMode.fromWire(""))
        assertEquals(AutonomyMode.FULL, AutonomyMode.fromWire("full"))
        assertEquals(AutonomyMode.ASK, AutonomyMode.fromWire("ask"))
    }
}
