package com.openminis.app.tools

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

    /** Ends a sentence: a full stop that is not a decimal point, other terminators, or a line break. */
    private val SENTENCE_END = Regex("""[。！？；!?;\n]|\.(?!\d)""")

    /**
     * The code the message names, or null when it is not one of these.
     *
     * A code usually follows the word that names it ("验证码为 123456", "verification code: 4321"), but
     * English messages often lead with it ("123456 is your verification code"). The run that belongs to
     * the word is the nearest one IN THE SAME SENTENCE: after the word first, measured from the end of
     * the word to the start of the run (not start to start: a long keyword pushes its own code further
     * away than an amount written just before it), then before it. Only when the word's sentence holds
     * no run does the nearest one after it, and failing that before it, in the rest of the message
     * count. So "123456 is your verification code. Order amount 9999" is 123456, not the amount in the
     * next sentence.
     */
    fun codeIn(body: String): String? {
        val context = OTP_CONTEXT.find(body) ?: return null
        val runs = OTP.findAll(body).toList()
        val sentenceStart = SENTENCE_END.findAll(body.substring(0, context.range.first)).lastOrNull()
            ?.let { it.range.last + 1 } ?: 0
        val sentenceEnd = SENTENCE_END.find(body, context.range.last + 1)?.range?.first ?: body.length
        fun after(candidates: List<MatchResult>) = candidates.filter { it.range.first > context.range.last }
            .minByOrNull { it.range.first - context.range.last }
        fun before(candidates: List<MatchResult>) = candidates.filter { it.range.last < context.range.first }
            .minByOrNull { context.range.first - it.range.last }
        val inSentence = runs.filter { it.range.first >= sentenceStart && it.range.last < sentenceEnd }
        val chosen = after(inSentence) ?: before(inSentence) ?: after(runs) ?: before(runs)
        return chosen?.groupValues?.get(1)
    }
}
