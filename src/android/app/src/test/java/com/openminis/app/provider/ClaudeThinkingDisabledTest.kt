package com.openminis.app.provider

import com.openminis.app.provider.anthropic.AnthropicProvider
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Claude 4.6-4.x must be told thinking is off (they think by default); Claude 5+
 * rejects `thinking.type.disabled` with a 400 and treats an absent field as
 * adaptive, so for 5+ "off" is the absence of the field.
 */
class ClaudeThinkingDisabledTest {
    private fun accepts(id: String) = AnthropicProvider.modelAcceptsExplicitThinkingDisabled(id)

    @Test
    fun claude46To4xAcceptTheExplicitDisabledLiteral() {
        assertTrue(accepts("claude-opus-4-6"))
        assertTrue(accepts("claude-sonnet-4-7"))
        assertTrue(accepts("claude-opus-4-8-20260115"))
    }

    @Test
    fun claude5AndLaterRejectItSoNothingIsSent() {
        assertFalse(accepts("claude-fable-5-1"))
        assertFalse(accepts("claude-opus-5-5"))
        assertFalse(accepts("claude-sonnet-5"))
        assertFalse(accepts("claude-haiku-6"))
    }

    @Test
    fun legacyModelsNeverGetTheLiteral() {
        assertFalse(accepts("claude-haiku-4-5"))
        assertFalse(accepts("claude-3-7-sonnet"))
    }

    @Test
    fun aSnapshotStampIsNotReadAsAMinorVersion() {
        // claude-sonnet-4-20250514 is Claude 4.0; parsed as (4, 20250514) it would
        // be treated as >= 4.6 and sent a literal no 4.0 model accepts.
        assertFalse(accepts("claude-sonnet-4-20250514"))
        assertFalse(accepts("anthropic/claude-opus-4-20250514"))
    }

    @Test
    fun digitsInAGatewayNamespaceAreNotTheVersion() {
        assertFalse(accepts("v2-gateway/claude-opus-5"))
        assertTrue(accepts("inst-53/claude-opus-4-6"))
    }

    @Test
    fun nonClaudeIdsAreNeverMatched() {
        assertFalse(accepts("gpt-4-6"))
        assertFalse(accepts("gemini-3-pro"))
    }
}
