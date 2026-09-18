package com.openminis.app.roleplay

import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * [T-eta-character-cards] The macro set a character card may use.
 *
 * Ported from Eta `agent/roleplay/CharacterMacros.kt` (Mangi-11/Eta @ c15de97); attribution in
 * THIRD_PARTY_LICENSES.md. Everything Eta guarantees is kept: only known macros are expanded, an
 * unknown one is left exactly as written, `{{// …}}` is a comment and disappears, a macro that
 * contains itself stops at the first repetition instead of looping, nesting is bounded, and the
 * whole expansion is capped so a pathological card cannot allocate without limit.
 *
 * Two adaptations: the clock and the locale are parameters, so the date/time macros are testable
 * and the weekday follows the device language instead of being fixed to Chinese.
 */
object CharacterMacros {

    const val MAX_EXPANDED_CHARS = 2 * 1024 * 1024

    private const val MAX_DEPTH = 8

    private val token = Regex("\\{\\{([^{}]+)\\}\\}|<(USER|CHAR|BOT)>", RegexOption.IGNORE_CASE)

    /** An opening `{{name` without its closing braces: still a macro the card meant to use. */
    private val opening = Regex("\\{\\{\\s*([^\\s:{}]+)")

    fun hasUnsupportedMacros(text: String, card: CharacterCard): Boolean {
        val names = values(card, "用户", "", "", LocalDateTime.now(), Locale.getDefault()).keys
        val closed = token.findAll(text).any { match ->
            val key = keyOf(match)
            !key.startsWith("//") && key !in names
        }
        if (closed) return true
        return opening.findAll(text).any { match ->
            val key = match.groupValues[1].lowercase(Locale.ROOT)
            !key.startsWith("//") && key !in names
        }
    }

    fun expand(
        text: String,
        card: CharacterCard,
        userName: String = "用户",
        userDescription: String = "",
        original: String = "",
        now: LocalDateTime = LocalDateTime.now(),
        locale: Locale = Locale.getDefault(),
    ): String {
        val values = values(card, userName, userDescription, original, now, locale)

        fun resolve(source: String, active: Set<String>, depth: Int): String {
            checkSize(source.length)
            if (depth >= MAX_DEPTH) return source
            val output = StringBuilder(minOf(source.length, 8_192))
            var offset = 0
            token.findAll(source).forEach { match ->
                appendBounded(output, source, offset, match.range.first)
                val key = keyOf(match)
                val replacement = when {
                    key.startsWith("//") -> ""
                    key in active -> match.value
                    key in values -> resolve(values.getValue(key), active + key, depth + 1)
                    else -> match.value
                }
                appendBounded(output, replacement)
                offset = match.range.last + 1
            }
            appendBounded(output, source, offset, source.length)
            return output.toString()
        }
        return resolve(text, emptySet(), 0)
    }

    private fun keyOf(match: MatchResult): String {
        val raw = match.groups[1]?.value ?: match.groups[2]?.value.orEmpty()
        return raw.trim().lowercase(Locale.ROOT)
    }

    private fun appendBounded(output: StringBuilder, value: String, start: Int = 0, end: Int = value.length) {
        checkSize(output.length.toLong() + end - start)
        output.append(value, start, end)
    }

    private fun checkSize(length: Number) {
        if (length.toLong() > MAX_EXPANDED_CHARS) {
            throw CharacterCardException("CARD_MACRO_EXPANSION_LIMIT", "角色宏展开超过长度上限，请精简循环引用或重复宏")
        }
    }

    private fun values(
        card: CharacterCard,
        userName: String,
        userDescription: String,
        original: String,
        now: LocalDateTime,
        locale: Locale,
    ): Map<String, String> {
        val user = userName.ifBlank { "用户" }
        return mapOf(
            "char" to card.nickname,
            "user" to user,
            "bot" to card.nickname,
            "original" to original,
            "description" to card.description,
            "personality" to card.personality,
            "scenario" to card.scenario,
            "persona" to userDescription,
            "mesexamples" to card.exampleMessages,
            "mesexamplesraw" to card.exampleMessages,
            "charfirstmessage" to card.firstMessage,
            "charprompt" to card.systemPrompt,
            "charinstruction" to card.postHistoryInstructions,
            "chardepthprompt" to card.depthPrompt?.prompt.orEmpty(),
            "group" to card.nickname,
            "charifnotgroup" to card.nickname,
            "notchar" to user,
            "date" to now.format(DateTimeFormatter.ISO_LOCAL_DATE),
            "isodate" to now.format(DateTimeFormatter.ISO_LOCAL_DATE),
            "time" to now.format(DateTimeFormatter.ofPattern("HH:mm")),
            "isotime" to now.format(DateTimeFormatter.ofPattern("HH:mm")),
            "weekday" to now.format(DateTimeFormatter.ofPattern("EEEE", locale)),
            "newline" to "\n",
            "noop" to "",
        )
    }
}
