package com.openminis.app.provider.thinking

import com.openminis.app.data.model.ThinkingLevel
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The editor lets a user save these three formats, so a request must be buildable with each. */
class ThinkingEditorFormatsEmitTest {

    @After
    fun tearDown() {
        ThinkingRuleResolver.setAllCustomRules(emptyMap())
    }

    private fun ctx(level: ThinkingLevel) = ThinkingResolveContext(
        modelId = "some-model",
        instanceId = "inst-A",
        supportsReasoning = true,
        declaredEffortValues = null,
        level = level,
        maxTokens = 4096,
        isOpenRouter = false,
        usesUnifiedReasoningEffort = false,
        isMistral = false,
        isDashScope = false,
        offEffort = null,
    )

    private fun useRule(format: ThinkingWireFormat) = ThinkingRuleResolver.setCustomRules(
        "inst-A",
        listOf(
            ThinkingRule(
                kind = ThinkingRule.Kind.CUSTOM,
                scope = ThinkingRule.Scope.AllModels,
                wireFormat = format,
                label = "editor-rule",
            ),
        ),
    )

    private fun build(level: ThinkingLevel): JSONObject =
        JSONObject().also { ThinkingRuleResolver.apply(it, ctx(level)) }

    @Test
    fun aBooleanToggleSetsTheFlagFromTheLevel() {
        useRule(ThinkingWireFormat.BooleanToggle("enable_thinking"))
        assertEquals(true, build(ThinkingLevel.HIGH).getBoolean("enable_thinking"))
        assertEquals(false, build(ThinkingLevel.OFF).getBoolean("enable_thinking"))
    }

    @Test
    fun anExtraBodyToggleCreatesTheNestedPath() {
        useRule(ThinkingWireFormat.ExtraBodyToggle("extra_body.thinking.enabled"))
        assertEquals(true, build(ThinkingLevel.MEDIUM).getJSONObject("extra_body").getJSONObject("thinking").getBoolean("enabled"))
        assertEquals(false, build(ThinkingLevel.OFF).getJSONObject("extra_body").getJSONObject("thinking").getBoolean("enabled"))
    }

    @Test
    fun aCustomPathSendsThePerTierValueAndTheOffValue() {
        useRule(
            ThinkingWireFormat.CustomPath(
                path = "reasoning.mode",
                values = mapOf(ThinkingLevel.HIGH to "deep", ThinkingLevel.LOW to "fast"),
                offValue = "none",
            ),
        )
        assertEquals("deep", build(ThinkingLevel.HIGH).getJSONObject("reasoning").getString("mode"))
        assertEquals("fast", build(ThinkingLevel.LOW).getJSONObject("reasoning").getString("mode"))
        assertEquals("none", build(ThinkingLevel.OFF).getJSONObject("reasoning").getString("mode"))
    }

    @Test
    fun aCustomPathWithNoValueForATierOrNoOffValueSendsNothingForIt() {
        useRule(ThinkingWireFormat.CustomPath("mode", mapOf(ThinkingLevel.HIGH to "deep"), offValue = null))
        assertFalse(build(ThinkingLevel.MEDIUM).has("mode"))
        assertFalse(build(ThinkingLevel.OFF).has("mode"))
    }

    @Test
    fun putPathCreatesIntermediateObjectsAndKeepsSiblings() {
        val body = JSONObject().put("a", JSONObject().put("keep", 1))
        ThinkingRuleResolver.putPath(body, "a.b.c", "x")
        assertEquals(1, body.getJSONObject("a").getInt("keep"))
        assertEquals("x", body.getJSONObject("a").getJSONObject("b").getString("c"))
        ThinkingRuleResolver.putPath(body, "", "ignored")
        assertTrue(body.length() == 1)
    }
}
