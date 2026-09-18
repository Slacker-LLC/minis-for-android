package com.openminis.app.tools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * [T-eta-xposed-groups] Ported from Eta `agent/tool/AgentStructuredDeviceTools.kt` (Mangi-11/Eta @
 * c15de97). These cases are the ones that decide whether the tool is safe to call: a message that
 * merely contains a number must not produce a code, and the code must be the one the message is
 * talking about rather than the first number in it.
 */
class SmsCodeExtractionPolicyTest {

    @Test
    fun `a code is found next to the word that names it`() {
        assertEquals("123456", SmsCodeExtractionPolicy.codeIn("您的验证码是 123456，5 分钟内有效"))
        assertEquals("4321", SmsCodeExtractionPolicy.codeIn("Your verification code is 4321"))
        assertEquals("987654", SmsCodeExtractionPolicy.codeIn("OTP: 987654"))
        assertEquals("2468", SmsCodeExtractionPolicy.codeIn("one-time password 2468"))
    }

    @Test
    fun `the nearest run wins, not the first one`() {
        assertEquals(
            "8888",
            SmsCodeExtractionPolicy.codeIn("验证码 8888，订单金额 12345 元"),
        )
        assertEquals(
            "12345",
            SmsCodeExtractionPolicy.codeIn("订单金额 8888 元，验证码 12345"),
        )
    }

    @Test
    fun `a message without the word that names a code produces nothing`() {
        assertNull(SmsCodeExtractionPolicy.codeIn("您的订单 123456 已发货"))
        assertNull(SmsCodeExtractionPolicy.codeIn("余额 8888 元"))
        assertNull(SmsCodeExtractionPolicy.codeIn(""))
    }

    @Test
    fun `a run that is not four to eight digits is not a code`() {
        assertNull(SmsCodeExtractionPolicy.codeIn("验证码 123"))
        assertNull(SmsCodeExtractionPolicy.codeIn("验证码 1234567890"))
        assertEquals(
            "4567",
            SmsCodeExtractionPolicy.codeIn("验证码 4567，参考号 123456789"),
        )
    }

    @Test
    fun `the look-back window is clamped to the declared range`() {
        assertEquals(
            SmsCodeExtractionPolicy.DEFAULT_MAX_AGE_MINUTES,
            SmsCodeExtractionPolicy.clampMaxAgeMinutes(null),
        )
        assertEquals(1, SmsCodeExtractionPolicy.clampMaxAgeMinutes(0))
        assertEquals(1, SmsCodeExtractionPolicy.clampMaxAgeMinutes(-10))
        assertEquals(60, SmsCodeExtractionPolicy.clampMaxAgeMinutes(60))
        assertEquals(1_440, SmsCodeExtractionPolicy.clampMaxAgeMinutes(100_000))
    }
}
