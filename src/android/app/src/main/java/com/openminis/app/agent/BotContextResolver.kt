package com.openminis.app.agent

import com.openminis.app.data.db.BotEntity

object BotContextResolver {
    fun systemPrompt(bot: BotEntity?): String? {
        if (bot == null) return null
        val safeName = normalize(bot.name, NAME_MAX_CHARS)
        val instructions = bot.systemPrompt
            ?.replace("\r\n", "\n")?.replace('\r', '\n')
            ?.replace(Regex("[\\p{Cntrl}&&[^\\n\\t]]"), "")
            ?.trim()?.take(SYSTEM_PROMPT_MAX_CHARS)
        return buildString {
            append("<bot-identity>\n")
            append("You are the persistent Bot named ")
            append(safeName)
            append(".\n")
            if (!instructions.isNullOrBlank()) {
                append("<bot-instructions>\n")
                append(instructions)
                append("\n</bot-instructions>\n")
            }
            append("</bot-identity>")
        }
    }

    /** Keep the display name single-line and free of controls. */
    private fun normalize(value: String, maxChars: Int): String =
        value
            .replace(Regex("[\\p{Cntrl}\\s]+"), " ")
            .trim()
            .take(maxChars)

    private const val NAME_MAX_CHARS = 80
    private const val SYSTEM_PROMPT_MAX_CHARS = 12_000
}
