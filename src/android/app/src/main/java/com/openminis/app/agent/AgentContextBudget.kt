package com.openminis.app.agent

import com.openminis.app.data.model.AgentContentPart
import com.openminis.app.data.model.LLMMessage

/**
 * Port of Eta `agent/model/AgentContextBudget.kt` (Mangi-11/Eta @ c15de97),
 * reduced to the estimator compaction needs. Attribution and licence of the
 * original are recorded centrally in `PROVENANCE.md` /
 * `THIRD_PARTY_LICENSES.md`; no per-file licence header is added on purpose.
 *
 * Kept from upstream: the CJK-aware density function ([textTokens]), the
 * per-message / per-image pricing ([rawEstimate]) and the compaction constants
 * [RECENT_MESSAGES], [RECENT_RATIO] and [MAX_OVERFLOW_ATTEMPTS].
 *
 * Deliberately NOT ported: the usage-calibration state (`observe`) and the
 * `shouldCompact` trigger. Minis already owns the trigger in
 * `ContextPolicy` / `ContextPressure` (analysis item C6), and a second,
 * competing threshold in the compactor would fire compaction at a different
 * occupancy than every other guard in the app.
 *
 * The upstream estimator walks a provider JSON array; the Minis equivalent is
 * a list of [LLMMessage], where tool traffic lives in `contentParts` rather
 * than in a `role: "tool"` row. The pricing below keeps upstream's shape —
 * 16 tokens of envelope, 8 per message, 4096 per image, everything else by
 * text density — so before/after numbers stay comparable inside one pass.
 */
internal object AgentContextBudget {

    /** Upstream `RECENT_MESSAGES`: keep at least this many recent messages. */
    const val RECENT_MESSAGES: Int = 4

    /** Upstream `RECENT_RATIO`: share of the window that may stay uncompacted. */
    const val RECENT_RATIO: Double = 0.20

    /** Upstream `MAX_OVERFLOW_ATTEMPTS`: overflow-driven summary re-splits. */
    const val MAX_OVERFLOW_ATTEMPTS: Int = 3

    /** Upstream charges a flat 4096 tokens per image part, whatever its bytes. */
    const val IMAGE_PART_TOKENS: Int = 4_096

    /** Upstream `rawEstimate` envelope overheads. */
    const val BASE_OVERHEAD: Int = 16
    const val MESSAGE_OVERHEAD: Int = 8

    /**
     * Upstream `textTokens`: ASCII runs about three characters per token while
     * CJK (and every other non-ASCII code point) is priced one token each.
     * Ported verbatim — the density is the whole reason a Chinese transcript
     * does not look four times cheaper than it is.
     */
    fun textTokens(text: String): Int {
        var ascii = 0
        var other = 0
        text.codePoints().forEach { codePoint -> if (codePoint < 128) ascii++ else other++ }
        return (ascii + 2) / 3 + other
    }

    /**
     * Upstream `rawEstimate(messages, tools)` without calibration: the
     * uncalibrated size of exactly the messages handed to a provider call.
     */
    fun rawEstimate(messages: List<LLMMessage>, toolsText: String = ""): Int {
        var tokens = textTokens(toolsText) + BASE_OVERHEAD
        for (message in messages) {
            tokens += MESSAGE_OVERHEAD
            tokens += textTokens(message.role.value)
            tokens += textTokens(message.dbMessageId.orEmpty())
            tokens += textTokens(message.content)
            for (part in message.contentParts) {
                tokens += when (part) {
                    is AgentContentPart.ImageData -> IMAGE_PART_TOKENS
                    is AgentContentPart.Text -> textTokens(part.text)
                    is AgentContentPart.ToolUse ->
                        textTokens(part.id) + textTokens(part.name) + textTokens(part.input.toString())
                    is AgentContentPart.ToolResult ->
                        textTokens(part.id) + textTokens(part.name) + textTokens(part.content) +
                            if (part.imageData != null) IMAGE_PART_TOKENS else 0
                }
            }
            // Upstream prices inline images by count, never by base64 length.
            for (image in message.imageParts) {
                if (image.data.isNotEmpty()) tokens += IMAGE_PART_TOKENS
            }
        }
        return tokens
    }
}
