package com.openminis.app.share

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * [T-eta-conversation-export] Markdown projection of one conversation: a self-contained
 * document a reader can keep, with thinking folded into `<details>` and tool activity
 * quoted rather than inlined.
 *
 * Ported from Eta `ui/app/ConversationMarkdownExporter.kt` (Mangi-11/Eta @ c15de97);
 * attribution in THIRD_PARTY_LICENSES.md. Like Eta's, the wording is injected through
 * [Labels] so the rules stay localizable and pure-JVM testable, and tool arguments and
 * results are bounded before they reach the document.
 *
 * Rendering is per block rather than per document: the caller streams a long session
 * message by message, so peak memory stays bounded no matter how long the transcript is.
 */
object ConversationMarkdownExporter {

    /** How much of one tool argument/result payload is kept in the document. */
    const val MAX_FIELD_CHARS = 400

    const val TRUNCATION_MARKER = "… (truncated)"

    private const val MAX_FILENAME_TITLE_CHARS = 40
    private const val FILENAME_TIMESTAMP_PATTERN = "yyyyMMdd-HHmm"

    private val FILENAME_UNSAFE_CHARS = Regex("[\\\\/:*?\"<>|\\p{C}]")
    private val WHITESPACE_RUNS = Regex("\\s+")

    data class Labels(
        val user: String = "User",
        val assistant: String = "Assistant",
        val thinking: String = "Thinking",
        val toolLineFormat: String = "Tool: %s (%s)",
        val argumentsFormat: String = "Arguments: %s",
        val resultFormat: String = "Result: %s",
        val imagesFormat: String = "%d image(s) not included",
        val toolStatusRunning: String = "running",
        val toolStatusSuccess: String = "ok",
        val toolStatusFailed: String = "failed",
        val toolStatusUnknown: String = "unrecorded",
    ) {
        fun status(status: ToolStatus): String = when (status) {
            ToolStatus.RUNNING -> toolStatusRunning
            ToolStatus.SUCCESS -> toolStatusSuccess
            ToolStatus.FAILED -> toolStatusFailed
            ToolStatus.UNKNOWN -> toolStatusUnknown
        }
    }

    enum class ToolStatus { RUNNING, SUCCESS, FAILED, UNKNOWN }

    sealed class Block {
        data class User(val text: String, val attachmentCount: Int = 0) : Block()
        data class Assistant(val text: String) : Block()
        data class Thinking(val text: String) : Block()
        data class Tool(
            val name: String,
            val status: ToolStatus = ToolStatus.UNKNOWN,
            val description: String? = null,
            val arguments: String? = null,
            val result: String? = null,
        ) : Block()
        data class Notice(val text: String) : Block()
    }

    /** The document's title line; newlines would break the heading. */
    fun documentTitle(title: String, fallback: String = "Conversation"): String =
        "# " + title.replace('\n', ' ').trim().ifEmpty { fallback }

    /** One block as Markdown, or null when it carries nothing worth writing. */
    fun renderBlock(block: Block, labels: Labels = Labels()): String? = when (block) {
        is Block.User -> {
            val text = block.text.trim()
            if (text.isEmpty() && block.attachmentCount == 0) {
                null
            } else {
                buildString {
                    append("## ").append(labels.user)
                    if (text.isNotEmpty()) append("\n\n").append(text)
                    if (block.attachmentCount > 0) {
                        append("\n\n_").append(labels.imagesFormat.format(block.attachmentCount)).append('_')
                    }
                }
            }
        }

        is Block.Assistant -> block.text.trim().takeIf { it.isNotEmpty() }
            ?.let { "## ${labels.assistant}\n\n$it" }

        is Block.Thinking -> block.text.trim().takeIf { it.isNotEmpty() }
            ?.let { "<details>\n<summary>${labels.thinking}</summary>\n\n$it\n\n</details>" }

        is Block.Tool -> buildList {
            add(labels.toolLineFormat.format(block.name, labels.status(block.status)))
            block.description?.trim()?.takeIf { it.isNotEmpty() }?.let(::add)
            block.arguments?.let { bounded(it) }?.takeIf { it.isNotEmpty() }
                ?.let { add(labels.argumentsFormat.format(it)) }
            block.result?.let { bounded(it) }?.takeIf { it.isNotEmpty() }
                ?.let { add(labels.resultFormat.format(it)) }
        }.toBlockquote()

        is Block.Notice -> block.text.trim().takeIf { it.isNotEmpty() }
            ?.let { listOf(it).toBlockquote() }
    }

    /** Everything quoted, so a tool line can never be mistaken for assistant prose. */
    private fun List<String>.toBlockquote(): String =
        joinToString(separator = "\n\n")
            .lines()
            .joinToString(separator = "\n") { line -> if (line.isEmpty()) ">" else "> $line" }

    private fun bounded(value: String): String {
        val text = value.trim()
        if (text.length <= MAX_FIELD_CHARS) return text
        return text.take(MAX_FIELD_CHARS) + TRUNCATION_MARKER
    }

    /**
     * Share-sheet file name: the title trimmed to one safe line, plus a local timestamp so
     * two exports of the same conversation do not collide.
     */
    fun defaultFileName(
        title: String,
        fallback: String = "conversation",
        nowMillis: Long = System.currentTimeMillis(),
        prefix: String = "Minis",
    ): String {
        val sanitized = title
            .replace(WHITESPACE_RUNS, " ")
            .replace(FILENAME_UNSAFE_CHARS, "")
            .trim()
            .take(MAX_FILENAME_TITLE_CHARS)
            .trim()
            .ifBlank { fallback }
        val timestamp = SimpleDateFormat(FILENAME_TIMESTAMP_PATTERN, Locale.US).format(Date(nowMillis))
        return "$prefix-$sanitized-$timestamp.md"
    }
}
