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

/**
 * [T-android-work-items] What a step *is*.
 *
 * Codex's turn model keeps a typed item per step (reasoning / command execution / file change /
 * MCP call / web search) and builds both the live view and the finished summary from those types
 * rather than from prose. This is the same idea at the granularity this app has: one enum per kind
 * a finished run can be summarised by, decided purely from the tool name so it is testable and
 * cannot drift between the live panel and the collapsed row.
 */
internal enum class WorkItemKind {
    REASONING,
    COMMAND,
    FILE_READ,
    FILE_EDIT,
    SEARCH,
    BROWSER,
    MCP,
    IMAGE,
    DELEGATION,
    OTHER,
}

/** Canonical tool names the classifier matches exactly; prefixes cover the rest. */
private val WORK_ITEM_COMMANDS = setOf("shell_execute", "shell", "linux_shell", "linux.shell")
private val WORK_ITEM_FILE_READS = setOf("file_read", "read", "linux.read")
private val WORK_ITEM_FILE_EDITS = setOf("file_write", "file_edit", "write", "edit", "linux.write", "linux.edit")

/** [T-android-work-items] The kind of one block: a thinking/text/info block is reasoning. */
internal fun workItemKindOf(block: AssistantBlock): WorkItemKind {
    if (block.kind != TOOL_USE_KIND) return WorkItemKind.REASONING
    val name = block.toolName.trim().lowercase()
    return when {
        name.isEmpty() -> WorkItemKind.OTHER
        name in WORK_ITEM_COMMANDS || name.contains("shell") || name.contains("terminal") -> WorkItemKind.COMMAND
        name in WORK_ITEM_FILE_READS || name.contains("read_file") -> WorkItemKind.FILE_READ
        name in WORK_ITEM_FILE_EDITS || name.contains("write_file") || name.contains("edit_file") -> WorkItemKind.FILE_EDIT
        name.startsWith("mcp") || name.startsWith("mcp.") || name.contains(".") -> WorkItemKind.MCP
        name.contains("search") || name.contains("web_") -> WorkItemKind.SEARCH
        name.startsWith("browser") -> WorkItemKind.BROWSER
        name.contains("image") || name.contains("photo") -> WorkItemKind.IMAGE
        name == "subagent" || name.contains("delegate") || name.startsWith("bot_") -> WorkItemKind.DELEGATION
        else -> WorkItemKind.OTHER
    }
}

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
    /** [T-android-turn-work] The turn's row timestamps, used when the steps carry none. */
    val messageCreatedAtMs: Long = 0L,
    val messageUpdatedAtMs: Long? = null,
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

    /**
     * [T-android-work-items] How long the run took, from the first step's start to the last step's
     * end. Null while a step is still running (there is no end yet) and when the blocks carry no
     * timings at all - the header then keeps its step wording instead of inventing a duration.
     */
    /**
     * When the turn started: the first step's clock, or the row's own created-at when the steps
     * carry no timings (a reloaded session has only the database timestamps).
     */
    val startedAtMs: Long?
        get() = messageCreatedAtMs.takeIf { it > 0L }
            ?: toolBlocks.mapNotNull { it.startTimeMs.takeIf { t -> t > 0L } }.minOrNull()

    val durationMs: Long?
        get() {
            if (isRunning) return null
            // [T-android-turn-work] The turn's own clock wins: a turn starts when the user sent the
            // message and ends when its last row was written, which is what Codex's duration_ms
            // measures and what survives a reload. Summing the steps' own timings instead reported
            // 1s for a turn whose single command slept for 20 (the per-block clocks are in-memory
            // and only cover the calls that carried one).
            val turnStart = messageCreatedAtMs.takeIf { it > 0L }
            val turnEnd = messageUpdatedAtMs?.takeIf { it > 0L }
            if (turnStart != null && turnEnd != null && turnEnd > turnStart) return turnEnd - turnStart
            val started = toolBlocks.mapNotNull { it.startTimeMs.takeIf { t -> t > 0L } }.minOrNull()
            val finished = toolBlocks
                .filter { it.startTimeMs > 0L && it.durationMs > 0L }
                .maxOfOrNull { it.startTimeMs + it.durationMs }
            return if (started != null && finished != null) (finished - started).takeIf { it > 0L } else null
        }

    /** [T-android-work-items] Steps per kind, in enum order. */
    val tallies: Map<WorkItemKind, Int>
        get() = blocks.groupingBy(::workItemKindOf).eachCount()

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
            durationMs = durationMs,
            tallies = tallies,
        )
    }
}

/**
 * [T-android-work-items] The groups of the "what this run consisted of" summary, biggest first.
 *
 * Reasoning and unclassified steps are left out: the first is already visible as the panel's own
 * thinking sections, and the second says "we could not name it" rather than telling the user
 * anything. Null when nothing is left to report.
 */
internal fun tallyEntries(tallies: Map<WorkItemKind, Int>): List<Pair<WorkItemKind, Int>> =
    tallies.entries
        .filter { it.value > 0 && it.key != WorkItemKind.REASONING && it.key != WorkItemKind.OTHER }
        .sortedWith(compareByDescending<Map.Entry<WorkItemKind, Int>> { it.value }.thenBy { it.key.ordinal })
        .map { it.key to it.value }

/**
 * [T-android-work-items] A file change as the work list shows it: which file, and how much moved.
 *
 * Codex keeps a file change as its own item type with the path and the diff on the item itself;
 * here the full diff already lives in the tool detail sheet, so the work row only needs the part
 * that answers "what did it touch, and how much" without opening anything.
 */
internal data class FileChangeSummary(
    val fileName: String,
    val addedLines: Int,
    val removedLines: Int,
    val isCreation: Boolean,
)

/** [T-android-work-items] The string value of one flat JSON field, without a JSON parser. */
internal fun toolArgString(argsJson: String, key: String): String? {
    val match = Regex("\"" + Regex.escape(key) + "\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"").find(argsJson)
        ?: return null
    return match.groupValues[1]
        .replace("\\n", "\n")
        .replace("\\r", "\r")
        .replace("\\t", "\t")
        .replace("\\\"", "\"")
        .replace("\\\\", "\\")
}

private fun lineCount(text: String): Int = when {
    text.isEmpty() -> 0
    else -> text.lines().size
}

/**
 * [T-android-work-items] The change [block] carries, or null when the block is not a file write/edit
 * or its arguments do not name a file. A write is a creation when it has no previous content to
 * compare against (the caller's only signal: the tool itself does not say).
 */
internal fun fileChangeSummary(block: AssistantBlock): FileChangeSummary? {
    if (block.kind != TOOL_USE_KIND) return null
    val name = block.toolName.lowercase()
    val isWrite = name.contains("write")
    val isEdit = name.contains("edit")
    if (!isWrite && !isEdit) return null
    val path = toolArgString(block.toolArgs, "path")
        ?: toolArgString(block.toolArgs, "file_path")
        ?: return null
    val fileName = path.trimEnd('/').substringAfterLast('/').takeIf { it.isNotBlank() } ?: return null
    val oldText = toolArgString(block.toolArgs, "old_string").orEmpty()
    val newText = if (isWrite) {
        toolArgString(block.toolArgs, "content").orEmpty()
    } else {
        toolArgString(block.toolArgs, "new_string").orEmpty()
    }
    val added = lineCount(newText)
    val removed = lineCount(oldText)
    if (added == 0 && removed == 0) return null
    return FileChangeSummary(
        fileName = fileName,
        addedLines = added,
        removedLines = removed,
        isCreation = isWrite && oldText.isEmpty(),
    )
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
    /** [T-android-work-items] Wall time of the finished run, when the steps carried timings. */
    val durationMs: Long? = null,
    /** [T-android-work-items] Steps per kind, for the expanded summary line. */
    val tallies: Map<WorkItemKind, Int> = emptyMap(),
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
 * before this change).
 *
 * [StepsPresentation.GROUPED] collapses a whole turn into ONE [WorkProcess]:
 * everything up to and including the last tool call, plus the narration the
 * model wrote between calls. The trailing text after that last call is the
 * turn's answer and stays outside the row. Codex splits a turn the same way
 * (work items collapse into one header, the AgentMessage does not), and this
 * app has to do it per message because the transcript already merges a turn's
 * assistant rows into one. Folding each text-interrupted run instead produced a
 * separate "用时 Ns" row per run - several durations for one question.
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
    messageCreatedAtMs: Long = 0L,
    messageUpdatedAtMs: Long? = null,
): List<AssistantTurnEntry> {
    if (presentation == StepsPresentation.PER_TOOL) {
        return blocks.mapIndexed { index, block -> AssistantTurnEntry.Single(index, block) }
    }

    // Everything up to the last work block belongs to the row; the text after it is the answer.
    val lastWorkIndex = blocks.indexOfLast { block ->
        isWorkProcessBlock(block) && (block.kind != THINKING_KIND || thinkingVisible)
    }
    if (lastWorkIndex < 0) {
        // Nothing the agent did - every block is answer text.
        return blocks.mapIndexed { index, block -> AssistantTurnEntry.Single(index, block) }
    }
    // Hidden thinking (Deep Thinking off) renders nothing, so it must not pad the row either.
    val workBlocks = blocks.subList(0, lastWorkIndex + 1).filter { block ->
        block.kind != THINKING_KIND || thinkingVisible
    }
    val entries = mutableListOf<AssistantTurnEntry>()
    entries.add(
        AssistantTurnEntry.Process(
            WorkProcess(
                // Anchored on the first block so the row keeps the same
                // LazyColumn key while the run grows, matching the
                // key-stability rule the streaming rows depend on.
                id = "$messageId:" + workBlocks.first().id,
                blocks = workBlocks.toList(),
                messageCreatedAtMs = messageCreatedAtMs,
                messageUpdatedAtMs = messageUpdatedAtMs,
            ),
        ),
    )
    for (index in (lastWorkIndex + 1) until blocks.size) {
        entries.add(AssistantTurnEntry.Single(index, blocks[index]))
    }
    return entries
}

internal const val TOOL_USE_KIND = "tool_use"
internal const val THINKING_KIND = "thinking"

/** Collapsed-row failure text budget — long tool errors must not wrap the row. */
internal const val DEFAULT_FAILURE_CHARS = 120
