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
    /**
     * The turn's own clock, carried by the turn's first run only (the row right under the user's message): the
     * moment the user sent the message and the moment the last reply row was written. Every other run carries
     * none, so a turn shows exactly one time.
     */
    val messageCreatedAtMs: Long = 0L,
    val messageUpdatedAtMs: Long? = null,
    /** The whole turn is still being generated; set on the clock-carrying run, whichever run is last. */
    val clockLive: Boolean = false,
    /**
     * The turn this run belongs to is still being generated. The row is "running" for the whole turn,
     * not only while a tool call is in flight: between two calls, and while the model writes its final
     * answer after the last one, no tool is running but the turn is not over - judging by the tools
     * alone folded the row at every gap and opened it again at the next call.
     */
    val turnLive: Boolean = false,
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

    /** True while a step is executing or the turn is still being generated. */
    val isRunning: Boolean get() = runningTool != null || turnLive

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

    /** True on the run that carries the turn's clock (see [messageCreatedAtMs]). */
    val carriesClock: Boolean get() = messageCreatedAtMs > 0L

    /** When the turn started: the user's send time, on the clock-carrying run only. */
    val startedAtMs: Long? get() = messageCreatedAtMs.takeIf { it > 0L }

    /**
     * How long the whole turn took, from the user's send to the last reply row. Null on every run but the
     * clock-carrying one, while the turn is live (the clock is still running) and when the row has no
     * timestamps. The steps' own timings are not used: a turn shows one time.
     */
    val durationMs: Long?
        get() {
            if (!carriesClock || clockLive) return null
            val end = messageUpdatedAtMs?.takeIf { it > 0L } ?: return null
            return (end - messageCreatedAtMs).takeIf { it > 0L }
        }

    /** [T-android-work-items] Steps per kind, in enum order. */
    val tallies: Map<WorkItemKind, Int>
        get() = blocks.groupingBy(::workItemKindOf).eachCount()

    /** The step the status line names while the run works: the call in flight, or the thinking being written. */
    private fun currentActivity(): String? {
        runningTool?.let { return it.toolTitle.trim().ifBlank { toolDisplayName(it.toolName) } }
        return null
    }

    /** A snapshot of everything the collapsed row renders. */
    fun summary(): WorkProcessSummary {
        val running = runningTool
        return WorkProcessSummary(
            toolCount = toolBlocks.size,
            thinkingCount = blocks.size - toolBlocks.size,
            isRunning = isRunning,
            runningStepNumber = running?.let(::toolStepNumber),
            runningToolName = currentActivity(),
            thinking = isRunning && running == null && blocks.last().kind == THINKING_KIND,
            carriesClock = carriesClock,
            clockLive = clockLive,
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
    /** The run is between calls writing its thinking, so the line can say so. */
    val thinking: Boolean = false,
    /** This run carries the turn's one clock; the others show no time at all. */
    val carriesClock: Boolean = false,
    /** The turn is still being generated (the clock is running). */
    val clockLive: Boolean = false,
    /** The finished turn's total time; only on the clock-carrying run. */
    val durationMs: Long? = null,
    /** Steps per kind, for the finished line. */
    val tallies: Map<WorkItemKind, Int> = emptyMap(),
)

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
 * [StepsPresentation.GROUPED] folds each maximal run of thinking + tool blocks into one [WorkProcess]
 * row and leaves everything the model wrote in between where it happened, as ordinary visible text.
 * That is how the agents this one is measured against show a turn: Codex keeps its commentary
 * messages in the thread between the collapsed work groups, and Claude Code prints the text between
 * tool calls. Folding the model's own sentences ("let me check X first") away with the tool calls hid
 * what it said about what it was doing the moment the turn finished.
 *
 * The turn's clock belongs to the first run (the row under the user's message): it is the only row that
 * shows a time, from the user's send to the end of the whole reply. The last run is the one that stays
 * open while the turn is live; earlier runs are finished by definition - the model went on to write
 * something after them - and show no time.
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
    turnLive: Boolean = false,
): List<AssistantTurnEntry> {
    if (presentation == StepsPresentation.PER_TOOL) {
        return blocks.mapIndexed { index, block -> AssistantTurnEntry.Single(index, block) }
    }

    fun counts(block: AssistantBlock) = isWorkProcessBlock(block) && (block.kind != THINKING_KIND || thinkingVisible)
    val lastWorkIndex = blocks.indexOfLast(::counts)
    val entries = mutableListOf<AssistantTurnEntry>()
    var run = mutableListOf<AssistantBlock>()
    var clockGiven = false
    fun flush(last: Boolean) {
        if (run.isEmpty()) return
        // The turn's one clock goes to the first run: the row right under the user's message.
        val first = !clockGiven
        clockGiven = true
        entries.add(
            AssistantTurnEntry.Process(
                WorkProcess(
                    // Anchored on the first block so the row keeps the same LazyColumn key while the run
                    // grows, matching the key-stability rule the streaming rows depend on.
                    id = "$messageId:" + run.first().id,
                    blocks = run.toList(),
                    messageCreatedAtMs = if (first) messageCreatedAtMs else 0L,
                    messageUpdatedAtMs = if (first) messageUpdatedAtMs else null,
                    clockLive = first && turnLive,
                    turnLive = last && turnLive,
                ),
            ),
        )
        run = mutableListOf()
    }
    blocks.forEachIndexed { index, block ->
        when {
            counts(block) -> run.add(block)
            // Hidden thinking (Deep Thinking off) renders nothing, so it neither pads a run nor ends one.
            block.kind == THINKING_KIND -> if (index > lastWorkIndex) entries.add(AssistantTurnEntry.Single(index, block))
            else -> {
                // A run ends where the model wrote something; it is the turn's last run when no work follows.
                flush(last = index > lastWorkIndex)
                entries.add(AssistantTurnEntry.Single(index, block))
            }
        }
    }
    flush(last = true)
    return entries
}

internal const val TOOL_USE_KIND = "tool_use"
internal const val THINKING_KIND = "thinking"

