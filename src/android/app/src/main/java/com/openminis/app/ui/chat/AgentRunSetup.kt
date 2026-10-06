package com.openminis.app.ui.chat

import com.openminis.app.data.model.AgentContentPart
import com.openminis.app.data.model.LLMMessage
import com.openminis.app.scheduled.ScheduledRunPromptPolicy

/** What an agent run is prepared with before its first model request; pure, so it can be tested alone. */
internal object AgentRunSetup {
    /**
     * The system prompt for a run: the caller's prompt and the bot's identity joined, then the
     * session's own instructions in a labelled block, then the safety note for scheduled/unattended
     * runs. Blank parts are left out; null when nothing remains.
     */
    fun systemPrompt(
        callerPrompt: String?,
        botPrompt: String?,
        sessionPrompt: String?,
        sessionSource: String?,
        unattended: Boolean,
    ): String? {
        val base = listOf(callerPrompt, botPrompt)
            .filterNot { it.isNullOrBlank() }
            .joinToString("\n\n")
            .ifBlank { null }
        val withSession = sessionPrompt?.let { sessionText ->
            buildString {
                if (!base.isNullOrBlank()) {
                    append(base)
                    append("\n\n")
                }
                append("<session-specific-instructions>\n")
                append(sessionText)
                append("\n</session-specific-instructions>")
            }
        } ?: base
        return ScheduledRunPromptPolicy.appendSafetyNote(
            systemPrompt = withSession,
            sessionSource = sessionSource,
            unattended = unattended,
        )
    }

    /** Size and shape of the history about to be sent: payload, not message count, is what costs memory. */
    data class ContextShape(
        val historySize: Int,
        val totalChars: Long,
        val maxMessageChars: Int,
        val maxMessageRole: String,
        val toolResultParts: Int,
        val imageParts: Int,
        val imageBytes: Long,
        val audioBase64Chars: Long,
    ) {
        val approxTokens: Long get() = totalChars / 4

        fun toLogString(memory: String): String =
            "[CtxShape] historySize=$historySize totalChars=$totalChars " +
                "maxMsgChars=$maxMessageChars maxMsgRole=$maxMessageRole toolResultParts=$toolResultParts " +
                "imageParts=$imageParts imageBytes=$imageBytes audioB64Chars=$audioBase64Chars " +
                "approxTokens=$approxTokens $memory"
    }

    fun contextShape(history: List<LLMMessage>): ContextShape {
        var chars = 0L
        var maxOne = 0
        var toolResults = 0
        var images = 0
        var imageBytes = 0L
        var audioChars = 0L
        var biggestRole = ""
        for (m in history) {
            var perMsg = m.content.length
            for (p in m.contentParts) {
                when (p) {
                    is AgentContentPart.ToolResult -> {
                        perMsg += p.content.length
                        toolResults++
                        // Inline image bytes never reach the char count, so track them separately: an
                        // image-heavy request is a different failure shape from a text-heavy one.
                        p.imageData?.let { images++; imageBytes += it.size }
                    }
                    is AgentContentPart.Text -> perMsg += p.text.length
                    is AgentContentPart.ImageData -> { images++; imageBytes += p.data.size }
                    else -> {}
                }
            }
            for (a in m.audioParts) audioChars += a.base64Data.length
            images += m.imageParts.size
            chars += perMsg
            if (perMsg > maxOne) { maxOne = perMsg; biggestRole = m.role.name }
        }
        return ContextShape(history.size, chars, maxOne, biggestRole, toolResults, images, imageBytes, audioChars)
    }
}
