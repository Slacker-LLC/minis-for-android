package com.openminis.app.ui.chat

import com.openminis.app.agent.AgentContextBudget
import com.openminis.app.agent.AgentContextCompactor
import com.openminis.app.data.CompactBudget
import com.openminis.app.data.CompactSplitPredicate
import com.openminis.app.data.model.AgentContentPart
import com.openminis.app.data.model.LLMError
import com.openminis.app.data.model.LLMMessage
import com.openminis.app.data.model.ThinkingLevel
import com.openminis.app.logging.AppLogger
import com.openminis.app.provider.LLMProvider
import com.openminis.app.tools.ToolSensitivePolicy
import com.openminis.app.tools.internal.ToolResultPruner
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CancellationException

private class CompactCallBudgetExceeded : IllegalStateException(
    "Compaction call budget exhausted",
)

/**
 * [C3-android-compaction-guards] A summarizer response failed Eta's summary
 * gate. Carries the upstream failure code and its actionable message so the
 * caller can surface the text without writing anything back — history and
 * marker are left exactly as they were.
 */
internal class CompactRejectedSummary(
    val code: String,
    message: String,
) : IllegalStateException(message)

/**
 * [C3-android-compaction-guards] One summarizer response: its text plus the
 * provider's stop reason, so the summary gate can reject a truncated answer
 * even when the text itself looks usable. Half-summaries are no longer
 * merged locally: Eta's overflow recovery chains them as `previous` and
 * gates each half on its own, so this is a plain carrier.
 */
internal data class SummaryDraft(val text: String, val stopReason: String?)

/** [C3] The summary priced the way the model will receive it. */
internal fun summaryAsMessage(summary: String): LLMMessage =
    LLMMessage(role = LLMMessage.Role.ASSISTANT, content = summary)

/**
 * The summarisation calls behind a compaction: transcript formatting, the chunked summary loop, overflow
 * halving, the summary gate and the file-activity tail. Moved out of [ChatViewModel] unchanged; the
 * provider, the model's context window and the progress display are supplied by the caller.
 */
internal class CompactionSummarizer(
    private val provider: () -> LLMProvider?,
    private val modelContextWindow: () -> Int?,
    private val onProgress: (depth: Int, callsIssued: Int) -> Unit,
) {
    /**
     * Number of summary requests issued by the current compaction run. The
     * splitter suspends between calls, so the counter must remain correct even
     * when coroutines resume on different IO threads.
     */
    private val callsIssued = AtomicInteger(0)

    fun resetCalls() = callsIssued.set(0)

    /**
     * Format the agent history as a plain-text transcript for the
     * summarisation LLM. Keeps role prefixes and bounds long tool arg / output
     * bodies so we stay well under any context window: a tool result above
     * [ToolResultPruner.THRESHOLD_CHARS] is pruned to head + omission marker +
     * tail instead of a plain head cut, so the summary still sees the outcome
     * of a long output. Mirrors iOS `buildConversationTextForSummary`.
     */
    fun buildConversationTextForSummary(history: List<LLMMessage>): String = buildString {
        for (msg in history) {
            val role = msg.role.name.lowercase()
            val text = msg.content.take(500)
            if (text.isNotEmpty()) {
                append(role).append(": ").append(text).append('\n')
            }
            for (part in msg.contentParts) {
                when (part) {
                    is AgentContentPart.Text -> {
                        append(role).append(": ").append(part.text.take(500)).append('\n')
                    }
                    is AgentContentPart.ToolUse -> {
                        // The summary is persisted, so a sensitive call contributes its name only.
                        val preview = if (ToolSensitivePolicy.isSensitive(part.name)) {
                            ToolSensitivePolicy.ARGUMENTS_PLACEHOLDER
                        } else {
                            part.input.toString().take(200)
                        }
                        append(role).append(" [tool:").append(part.name).append("]: ")
                            .append(preview).append('\n')
                    }
                    is AgentContentPart.ToolResult -> {
                        // Oversized results keep their head AND their tail: the
                        // outcome of a long shell/log output lives at the end, which
                        // a plain 500-char head cut drops. prune() returns null under
                        // its threshold, so the preview budget below still applies to
                        // every smaller result.
                        val preview = if (ToolSensitivePolicy.isSensitive(part.name)) {
                            ToolSensitivePolicy.RESULT_PLACEHOLDER
                        } else {
                            ToolResultPruner.prune(part.content) ?: part.content.take(500)
                        }
                        append(role).append(" [result:").append(part.name).append("]: ")
                            .append(preview).append('\n')
                    }
                    is AgentContentPart.ImageData -> {
                        append(role).append(" [image: ").append(part.mimeType).append("]\n")
                    }
                }
            }
        }
    }

    private data class FileActivity(val read: LinkedHashSet<String>, val modified: LinkedHashSet<String>)

    /**
     * Deterministic file-operation inventory carried across compactions. Pi
     * keeps readFiles/modifiedFiles outside the LLM summary; Android stores the
     * same information as a machine-readable tail on the summary so it survives
     * DB persistence and later compaction passes without a schema migration.
     */
    fun appendAuthoritativeFileActivity(
        summary: String,
        previousSummary: String?,
        messages: List<LLMMessage>,
    ): String {
        val activity = FileActivity(linkedSetOf(), linkedSetOf())
        parseFileActivity(previousSummary.orEmpty(), activity)
        for (msg in messages) {
            for (part in msg.contentParts) {
                if (part !is AgentContentPart.ToolUse) continue
                val path = part.input.optString("path", "").trim()
                if (path.isEmpty()) continue
                when (part.name) {
                    "file_read", "read_image" -> activity.read.add(path)
                    "file_write", "file_edit" -> activity.modified.add(path)
                }
            }
        }
        if (activity.read.isEmpty() && activity.modified.isEmpty()) return summary

        val cleaned = summary.replace(
            Regex("(?s)\n?<file-activity>.*?</file-activity>\\s*$"),
            "",
        ).trimEnd()
        return buildString {
            append(cleaned)
            append("\n\n<file-activity>\n")
            append("read:\n")
            for (path in activity.read) append("- ").append(path).append('\n')
            append("modified:\n")
            for (path in activity.modified) append("- ").append(path).append('\n')
            append("</file-activity>")
        }
    }

    private fun parseFileActivity(summary: String, out: FileActivity) {
        val block = Regex("(?s)<file-activity>(.*?)</file-activity>").find(summary)?.groupValues?.getOrNull(1) ?: return
        var mode = ""
        for (raw in block.lines()) {
            val line = raw.trim()
            when (line) {
                "read:" -> mode = "read"
                "modified:" -> mode = "modified"
                else -> if (line.startsWith("- ")) {
                    val path = line.removePrefix("- ").trim()
                    if (path.isNotEmpty()) {
                        if (mode == "read") out.read.add(path) else if (mode == "modified") out.modified.add(path)
                    }
                }
            }
        }
    }

    /**
     * [C3-android-compaction-guards] Eta's summary gate applied to any summary
     * text that is about to be carried forward or written back: normal finish,
     * non-empty, not the literal "null", inside the character cap and free of
     * tool-call syntax. Rejection throws [CompactRejectedSummary] so the
     * fail-closed path is identical everywhere — history and marker untouched.
     */
    fun requireValidSummary(summary: String, stopReason: String?, maxChars: Int) {
        val check = AgentContextCompactor.validateSummary(
            summary = summary,
            stopReason = stopReason,
            maxChars = maxChars,
            hasToolCalls = AgentContextCompactor.containsToolCallSyntax(summary),
        )
        if (check is AgentContextCompactor.SummaryCheck.Rejected) {
            throw CompactRejectedSummary(check.code, check.message)
        }
    }

    /**
     * [C3-android-compaction-guards] Eta's summary loop, ported from
     * AgentContextCompactor.compact(): walk the chunk plan, summarize one chunk
     * per call, and hand the previous chunk's summary to the next call as
     * `previous` (`summarize(chunk, previous, maxChars)`). Chunks come from
     * [AgentContextCompactor.chunkForSummary], so a chunk is always a run of
     * complete tool batches and a single oversized batch was already refused
     * before any call was issued.
     *
     * Every chunk answer passes the summary gate before it becomes the
     * `previous` of the next chunk, which is upstream's behaviour (its
     * summarize() validates on the way out) and the reason a truncated chunk
     * can no longer be folded into the final text unnoticed.
     *
     * Each planned chunk is re-packed against the summary that will really
     * precede it before the call is issued. The pre-flight plan was priced on
     * the previous session summary, while every call after the first carries a
     * summary the model just wrote, which can be longer — Eta packs inside its
     * own loop for the same reason. Re-packing reuses the same complete-batch
     * walk, so a chunk still never splits a tool batch, and a batch that only
     * fits alone is rejected instead of being shredded.
     */
    suspend fun summarizeCompactionChunks(
        chunks: List<List<LLMMessage>>,
        previousSummary: String?,
        summaryCharCap: Int,
        summaryMaxInputTokens: Int,
    ): SummaryDraft {
        var runningSummary = previousSummary?.takeIf { it.isNotBlank() }
        var lastStopReason: String? = null
        for ((index, planned) in chunks.withIndex()) {
            val packed = when (
                val repack = AgentContextCompactor.chunkForSummary(
                    messages = planned,
                    maxInputTokens = summaryMaxInputTokens,
                    previousSummary = runningSummary.orEmpty(),
                )
            ) {
                is AgentContextCompactor.ChunkPlan.Rejected -> {
                    throw CompactRejectedSummary(repack.code, repack.message)
                }
                is AgentContextCompactor.ChunkPlan.Chunks -> repack.chunks
            }
            for (chunk in packed) {
                val draft = generateCompactSummaryWithSplitting(
                    messages = chunk,
                    previousSummary = runningSummary,
                    summaryCharCap = summaryCharCap,
                    depth = 0,
                )
                val text = draft.text.trim()
                requireValidSummary(text, draft.stopReason, summaryCharCap)
                runningSummary = text
                lastStopReason = draft.stopReason
            }
            AppLogger.info(
                TAG,
                "[Compact] chunk ${index + 1}/${chunks.size} summarized in ${packed.size} call(s) " +
                    "(${runningSummary?.length ?: 0} chars carried)",
            )
        }
        return SummaryDraft(runningSummary.orEmpty(), lastStopReason)
    }

    /**
     * Summarize [messages], recursively halving when the input exceeds the
     * model's context window. Mirrors iOS
     * `generateCompactSummaryWithSplitting` (AIChatViewModel+Compaction.swift:820).
     *
     * The depth cap is [AgentContextBudget.MAX_OVERFLOW_ATTEMPTS] (matches iOS'
     * `<3` levels and Eta's `overflowShrinks >= MAX_OVERFLOW_ATTEMPTS`) so a
     * pathologically large conversation still terminates instead of fanning out
     * indefinitely. At each split we choose a structurally safe message
     * boundary and carry the running summary across the halves. The call budget
     * and outer timeout bound the total work even when a provider keeps
     * returning size-related failures.
     *
     * [C3-android-compaction-guards] The split point comes from Eta's group
     * halving (AgentContextCompactor.halfSplitBoundary) so an overflow retry
     * cuts between complete tool batches, exactly like the range ends above.
     * CompactSplitPredicate stays as the structural fallback for the text-only
     * histories where no complete batch boundary exists.
     */
    private suspend fun generateCompactSummaryWithSplitting(
        messages: List<LLMMessage>,
        previousSummary: String?,
        summaryCharCap: Int,
        depth: Int = 0,
    ): SummaryDraft {
        val callNumber = callsIssued.incrementAndGet()
        if (callNumber > CompactBudget.MAX_LLM_CALLS) {
            throw CompactCallBudgetExceeded()
        }
        onProgress(depth, callNumber)
        AppLogger.info(
            TAG,
            "[Compact] summary call $callNumber/$CompactBudget.MAX_LLM_CALLS (messages=${messages.size}, depth=$depth)",
        )
        val transcript = buildConversationTextForSummary(messages)
        val conversationText = if (previousSummary.isNullOrBlank()) {
            transcript
        } else {
            "Previous context summary:\n$previousSummary\n\n" +
                "New conversation to merge:\n$transcript"
        }
        return try {
            generateCompactSummary(conversationText)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (
                e is CompactCallBudgetExceeded ||
                !isSegmentRetryableError(e) ||
                messages.size < 2 ||
                // [C3-android-compaction-guards] Ported overflow bound: Eta
                // stops re-splitting once overflowShrinks reaches
                // AgentContextBudget.MAX_OVERFLOW_ATTEMPTS. Minis counts the
                // same recursion as a per-call depth (the existing iOS-aligned
                // "<3 levels" limit) and bounds the run-wide fan-out with
                // CompactBudget.MAX_LLM_CALLS instead.
                depth >= AgentContextBudget.MAX_OVERFLOW_ATTEMPTS ||
                callsIssued.get() + 2 > CompactBudget.MAX_LLM_CALLS
            ) {
                throw e
            }
            val split = AgentContextCompactor.halfSplitBoundary(messages)
                ?: CompactSplitPredicate.findSafeSplit(messages, messages.size / 2)
                ?: throw e
            val firstHalf = messages.subList(0, split).toList()
            val secondHalf = messages.subList(split, messages.size).toList()
            AppLogger.info(
                TAG,
                "[Compact] Splitting ${messages.size} messages at $split into " +
                    "${firstHalf.size} + ${secondHalf.size} (depth=$depth)",
            )
            // [C3-android-compaction-guards] Eta's overflow recovery: re-
            // summarize each half with the running summary carried forward —
            // the first half inherits `previousSummary`, the second half
            // inherits the first half's gated summary. This replaces the old
            // local concatenation of two independently built halves, which
            // could not reject a truncated half before it entered the merged
            // text.
            val firstHalfSummary = generateCompactSummaryWithSplitting(
                messages = firstHalf,
                previousSummary = previousSummary,
                summaryCharCap = summaryCharCap,
                depth = depth + 1,
            )
            requireValidSummary(
                firstHalfSummary.text.trim(),
                firstHalfSummary.stopReason,
                summaryCharCap,
            )
            return generateCompactSummaryWithSplitting(
                messages = secondHalf,
                previousSummary = firstHalfSummary.text.trim(),
                summaryCharCap = summaryCharCap,
                depth = depth + 1,
            )
        }
    }

    /**
     * Single-shot LLM call that turns [conversationText] into a structured
     * summary. Throws on provider error so the splitter above can detect
     * context-too-large failures and retry with halved input. Returns the text
     * together with the provider's stop reason: the caller's summary gate
     * rejects a truncated answer, so it must not be dropped here.
     */
    private suspend fun generateCompactSummary(conversationText: String): SummaryDraft {
        // Wrap the transcript in explicit BEGIN/END framing so the model
        // treats it as material to summarize rather than as a chat turn to
        // continue. Mirrors iOS AIChatViewModel+Compaction.swift
        // `compactUserMessage` construction. Without this wrapper, fast models
        // (e.g. deepseek-v4-flash) tend to "answer" whatever the last user
        // turn in the transcript said — producing a single-line continuation
        // instead of a structured summary.
        val userMessage = buildString {
            append("Compact this conversation into a context summary:\n\n")
            append(conversationText)
            append("\n\n---\nEND OF CONVERSATION TO COMPACT.\n\n")
            append(
                "Now generate a structured context summary following the system prompt " +
                    "instructions. Do NOT continue the conversation above — summarize it. " +
                    "Write everything in past tense, framed as \"what was discussed / what " +
                    "was done\", NOT as an ongoing goal or todo list."
            )
        }
        val contextWindow = modelContextWindow() ?: 128_000
        val estimatedInput = userMessage.length / 4
        val maxOut = maxOf(1024, minOf(8192, contextWindow - estimatedInput))
        val llm = provider()
            ?: throw IllegalStateException("No LLM provider available for compaction")
        val response = com.openminis.app.provider.LLMRetryPolicy.withRetry {
            llm.sendMessage(
                messages = listOf(
                    LLMMessage(role = LLMMessage.Role.USER, content = userMessage)
                ),
                systemPrompt = compactSummarySystemPrompt,
                maxTokens = maxOut,
                // Mirror iOS AIChatViewModel.swift:12926 — null lets the
                // provider/model use its default. gpt-5.x family rejects any
                // temperature != 1 with HTTP 400, and Android
                // OpenAIProvider.buildRequestBody omits the field entirely when
                // temperature is null.
                temperature = null,
                imageParts = emptyList(),
                tools = emptyList(),
                thinkingLevel = ThinkingLevel.OFF,
            )
        }
        // [C3-android-compaction-guards] The stop reason travels with the text
        // to the summary gate: upstream treats "did not finish normally" as one
        // of the CONTEXT_SUMMARY_INVALID conditions, and a truncated answer must
        // never be stored as the summary.
        return SummaryDraft(response.text, response.stopReason)
    }

    /**
     * Should a failed summary attempt be retried by splitting the input in half?
     *
     * Ported from iOS `isSegmentRetryableError`
     * (AIChatViewModel+Compaction.swift:1010, T-compact-segment-retry-any-error).
     *
     * Everything EXCEPT the two cases where a smaller request cannot help:
     *   - cancellation — the user (or a session switch) stopped the work, so a
     *     retry would fight that and immediately throw again;
     *   - network/offline — the request never reached a model, so payload size
     *     is irrelevant and splitting just doubles the failed round-trips.
     *
     * This deliberately REPLACES [isContextTooLargeError] on the split path.
     * That substring allow-list tried to enumerate how every provider words an
     * over-length refusal and was provably incomplete — OpenMinis#133's
     * `[context_length_exceeded] Your input exceeds the context window of this
     * model` slipped past several variants — and every miss silently disabled
     * splitting, so compaction failed outright instead of retrying smaller.
     *
     * Splitting on an unclassified error is safe: the worst case is two smaller
     * calls reaching the same failure, bounded by depth < 3 (≤8 leaf calls). A
     * summary built from halves is never worse than no summary at all, so the
     * burden of proof is inverted — retry unless retrying is provably pointless.
     */
    private fun isSegmentRetryableError(error: Throwable): Boolean {
        return shouldSplitOnError(error)
    }

    /**
     * System prompt for the single-shot summarisation call. Matches iOS
     * wording so cross-device summaries stay stylistically aligned.
     */
    private val compactSummarySystemPrompt: String = """
        You are a context compaction engine. Your summary will REPLACE the original messages in the conversation context window. The agent will read your summary as past context, then proceed based on the user's NEXT message — your summary is background, not a standing work order. Write the summary in the same language the user used in the conversation.

        MUST PRESERVE (never omit or shorten):
        - All file paths, directory names, URLs, UUIDs, and identifiers — copy verbatim
        - Commands executed and their outcomes (success/failure/output)
        - What was requested and what was done (record as past events, not as ongoing goals)
        - Key decisions made and their rationale
        - Errors encountered and how they were resolved
        - Important constraints, rules, or user preferences mentioned
        - Any tool calls and their results that affect current state

        STRUCTURE:
        1. Start with a one-line description of what the conversation was about (use past tense — "User asked X, agent did Y", NOT "Goal: X").
        2. Then a concise narrative of what happened, preserving technical details.
        3. End with a "What had been done so far" section listing completed work — NOT a "todo" or "pending" list. Do not invent ongoing objectives or carry-over tasks from old turns; if the user wants to continue, they will say so in their next message.

        PRIORITIZE recent context over older history — recent decisions and recent file/path references are most useful for continuity.

        Do NOT translate or alter code snippets, file paths, identifiers, or error messages. Be concise but never lose information the agent needs.
    """.trimIndent()

    companion object {
        private const val TAG = "CompactionSummarizer"

        /**
         * Decide whether reducing the request size could plausibly fix an
         * unsuccessful summary call. Quota, authentication, transport and
         * cancellation failures are independent of payload size and must not
         * fan one failure out into a long sequence of smaller calls.
         */
        internal fun shouldSplitOnError(error: Throwable): Boolean {
            if (error is CancellationException) return false
            if (error is LLMError) {
                return when (error) {
                    is LLMError.Cancelled,
                    is LLMError.NetworkError,
                    is LLMError.RateLimited,
                    is LLMError.TransientError,
                    is LLMError.InvalidApiKey,
                    -> false
                    else -> true
                }
            }
            if (error is java.io.IOException) return false
            return true
        }
    }
}
