package com.openminis.app.tools

import kotlin.math.abs

/**
 * [T-eta-xposed-groups] Pulling a verification code out of a message body - and nothing else.
 *
 * Ported from Eta `agent/tool/AgentStructuredDeviceTools.kt` (Mangi-11/Eta @ c15de97); attribution
 * in THIRD_PARTY_LICENSES.md. The rule is Eta's, and it is deliberately stricter than "find a
 * number": a message only counts when it carries a word that says what the code is, and the code is
 * the 4-8 digit run nearest to that word - the first number in a real message is usually an amount,
 * a percentage or a phone number. Nothing but the code ever leaves this function, which is what
 * makes the tool safe to call for a login flow.
 */
object SmsCodeExtractionPolicy {
    const val DEFAULT_MAX_AGE_MINUTES = 10
    const val MIN_MAX_AGE_MINUTES = 1
    const val MAX_MAX_AGE_MINUTES = 1_440

    /** At most this many codes are returned, newest first. */
    const val MAX_RESULTS = 10

    /** A code is 4-8 digits that are not part of a longer number. */
    val OTP = Regex("""(?<!\d)(\d{4,8})(?!\d)""")

    /** The words that say "this message is about a code", in the languages the ROMs send. */
    val OTP_CONTEXT = Regex(
        """验证码|校验码|动态码|确认码|一次性密码|verification\s*code|one[- ]time\s*(?:code|password)|\botp\b""",
        RegexOption.IGNORE_CASE,
    )

    fun clampMaxAgeMinutes(requested: Int?): Int =
        (requested ?: DEFAULT_MAX_AGE_MINUTES).coerceIn(MIN_MAX_AGE_MINUTES, MAX_MAX_AGE_MINUTES)

    /** The code nearest the word that names it, or null when the message is not one of these. */
    fun codeIn(body: String): String? {
        val contextMatch = OTP_CONTEXT.find(body) ?: return null
        return OTP.findAll(body)
            .minByOrNull { match -> abs(match.range.first - contextMatch.range.first) }
            ?.groupValues
            ?.get(1)
    }
}
