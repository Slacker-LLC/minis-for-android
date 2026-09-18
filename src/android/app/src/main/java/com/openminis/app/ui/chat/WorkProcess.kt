package com.openminis.app.ui.chat

import com.openminis.app.data.StepsPresentation

// [T-android-work-process] Pure grouping layer behind the collapsed
// "work process" row. Ported behaviour (not code) from Eta @ c15de97:
//   - ui/components/ChatMessageItem.kt `AgentWorkProcess` — consecutive
//     thinking + tool messages render as one expandable card whose header
//     reports the running step, then "completed N steps".
//   - ui/app/AgentRunMessageProjector.kt — a run that ends collapses; a run
//     that is still live keeps its thinking block open.
// Everything here is Android-free so the boundaries (what starts/ends a run,
// what counts as a step, which failure text lands on the collapsed row) are
// covered by JVM unit tests.

/** Tool statuses that mean "this step has not finished yet". */
internal val WORK_PROCESS_RUNNING_STATUSES: Set<ToolBlockStatus> = setOf(
    ToolBlockStatus.STREAMING,
    ToolBlockStatus.PENDING,
    ToolBlockStatus.RUNNING,
)

/** Tool statuses whose reason must be surfaced on the collapsed header row. */
internal val WORK_PROCESS_FAILED_STATUSES: Set<ToolBlockStatus> = setOf(
    ToolBlockStatus.FAILED,
    ToolBlockStatus.TIMEOUT,
)

/**
 * One maximal run of consecutive thinking + tool blocks inside a single
 * assistant message. Never empty; [blocks] keeps source order so the inline
 * panel can render thinking and tool results section by section.
 */
internal data class WorkProcess(
    val id: String,
    val blocks: List<AssistantBlock>,
) {
    init {
        require(blocks.isNotEmpty()) { "WorkProcess needs at least one block" }
    }

    val toolBlocks: List<AssistantBlock> get() = blocks.filter { it.kind == TOOL_USE_KIND }

    /** The tool whose call is still in flight, if any. */
    val runningTool: AssistantBlock?
        get() = blocks.lastOrNull {
            it.kind == TOOL_USE_KIND && it.toolStatus in WORK_PROCESS_RUNNING_STATUSES
        }

    /** True while at least one step is still executing. */
    val isRunning: Boolean get() = runningTool != null

    /** The last failing tool block — what the collapsed row has to explain. */
    val failedTool: AssistantBlock?
        get() = blocks.lastOrNull {
            it.kind == TOOL_USE_KIND && it.toolStatus in WORK_PROCESS_FAILED_STATUSES
        }

    /** 1-based ordinal of [block] among the tool steps, or null when it is not one. */
    private fun toolStepNumber(block: AssistantBlock): Int? {
        if (block.kind != TOOL_USE_KIND) return null
        val index = blocks.indexOfFirst { it === block }
        if (index < 0) return null
        var seen = 0
        blocks.forEachIndexed { i, candidate ->
            if (i <= index && candidate.kind == TOOL_USE_KIND) seen += 1
        }
        return seen.takeIf { it > 0 }
    }

    /** A snapshot of everything the collapsed row renders. */
    fun summary(maxFailureChars: Int = DEFAULT_FAILURE_CHARS): WorkProcessSummary {
        val running = runningTool
        val failed = failedTool
        return WorkProcessSummary(
            toolCount = toolBlocks.size,
            thinkingCount = blocks.size - toolBlocks.size,
            isRunning = running != null,
            runningStepNumber = running?.let(::toolStepNumber),
            runningToolName = running?.let {
                it.toolTitle.trim().ifBlank { toolDisplayName(it.toolName) }
            },
            failedStepNumber = failed?.let(::toolStepNumber),
            failedToolName = failed?.let {
                it.toolTitle.trim().ifBlank { toolDisplayName(it.toolName) }
            },
            // A running step outranks an earlier failure: the row must describe
            // what is happening now, and the failure stays visible inside the
            // panel. Only a finished run reports its failure on the header.
            failureReason = if (running == null) failed?.let { failureSummary(it, maxFailureChars) } else null,
        )
    }
}

/** Everything the collapsed "work process" header needs, resolved once. */
internal data class WorkProcessSummary(
    val toolCount: Int,
    val thinkingCount: Int,
    val isRunning: Boolean,
    val runningStepNumber: Int? = null,
    val runningToolName: String? = null,
    val failedStepNumber: Int? = null,
    val failedToolName: String? = null,
    val failureReason: String? = null,
) {
    val hasFailure: Boolean get() = failureReason != null || failedStepNumber != null
}

/**
 * One-liner describing why a tool step failed: the first non-blank line of the
 * tool result, else the step's own title, else the tool's display name. Always
 * non-empty and never longer than [maxChars] so it can sit on the collapsed row.
 */
internal fun failureSummary(block: AssistantBlock, maxChars: Int = DEFAULT_FAILURE_CHARS): String {
    val firstLine = block.content.lineSequence().firstOrNull { it.isNotBlank() }?.trim()
    val text = firstLine
        ?.takeIf { it.isNotEmpty() }
        ?: block.toolTitle.trim().takeIf { it.isNotEmpty() }
        ?: toolDisplayName(block.toolName)
    return if (text.length <= maxChars) text else text.take(maxChars).trimEnd() + "…"
}

/** A block that belongs to a work process (thinking or tool call). */
internal fun isWorkProcessBlock(block: AssistantBlock): Boolean =
    block.kind == TOOL_USE_KIND || block.kind == THINKING_KIND

/** One rendered unit of an assistant message: a plain block or a whole run. */
internal sealed interface AssistantTurnEntry {
    /** [blockIndex] is the index in the message's original block list. */
    data class Single(val blockIndex: Int, val block: AssistantBlock) : AssistantTurnEntry

    data class Process(val process: WorkProcess) : AssistantTurnEntry
}

/**
 * Group [blocks] into render entries.
 *
 * [StepsPresentation.PER_TOOL] is the historical layout: every block stays on
 * its own (thinking blocks are filtered later by the renderer, exactly as
 * before this change). [StepsPresentation.GROUPED] collapses each maximal run
 * of thinking / tool blocks into one [WorkProcess]; any other block kind
 * (text, media, info) ends the run, so a process never spans visible output.
 *
 * [thinkingVisible] mirrors the T300 rule: when the user turned Deep Thinking
 * off, thinking blocks render nothing, so in grouped mode they must not create
 * (or pad) a row either — a run of nothing but hidden thinking disappears.
 */
internal fun buildAssistantTurnEntries(
    messageId: String,
    blocks: List<AssistantBlock>,
    presentation: StepsPresentation,
    thinkingVisible: Boolean,
): List<AssistantTurnEntry> {
    if (presentation == StepsPresentation.PER_TOOL) {
        return blocks.mapIndexed { index, block -> AssistantTurnEntry.Single(index, block) }
    }

    val entries = mutableListOf<AssistantTurnEntry>()
    var index = 0
    while (index < blocks.size) {
        val block = blocks[index]
        val counts = isWorkProcessBlock(block) && (block.kind != THINKING_KIND || thinkingVisible)
        if (!counts) {
            if (block.kind != THINKING_KIND) {
                entries.add(AssistantTurnEntry.Single(index, block))
            }
            index += 1
            continue
        }
        val run = mutableListOf(block)
        var next = index + 1
        while (next < blocks.size) {
            val candidate = blocks[next]
            val candidateCounts =
                isWorkProcessBlock(candidate) && (candidate.kind != THINKING_KIND || thinkingVisible)
            if (!candidateCounts) break
            run.add(candidate)
            next += 1
        }
        entries.add(
            AssistantTurnEntry.Process(
                WorkProcess(
                    // Anchored on the first block so the row keeps the same
                    // LazyColumn key while the run grows, matching the
                    // key-stability rule the streaming rows depend on.
                    id = "$messageId:${run.first().id}",
                    blocks = run.toList(),
                ),
            ),
        )
        index = next
    }
    return entries
}

internal const val TOOL_USE_KIND = "tool_use"
internal const val THINKING_KIND = "thinking"

/** Collapsed-row failure text budget — long tool errors must not wrap the row. */
internal const val DEFAULT_FAILURE_CHARS = 120
