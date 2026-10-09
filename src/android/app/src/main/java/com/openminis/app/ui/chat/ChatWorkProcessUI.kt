package com.openminis.app.ui.chat

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import kotlinx.coroutines.delay
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.openminis.app.R
import com.openminis.app.ui.theme.ChatColors

/**
 * [T-android-work-process] The collapsed "work process" row for one maximal
 * run of thinking + tool blocks.
 *
 * Header (always visible): a wrench / running-tool icon, the state line and an
 * expand chevron. The state line reports
 *
 *  - a live run:      "正在执行第 N 步 · <tool>" (or "正在思考…" before the first call)
 *  - a finished run:  "已完成 N 个步骤" — the roadmap's collapsed summary
 *  - a failed run:    "第 N 步失败 · <reason>" — the failure reason is written on
 *                     the COLLAPSED line, not buried inside the panel
 *
 * Visibility: a live run auto-expands and a finished run folds back, mirroring
 * Eta's `AgentWorkProcess` (`ui/components/ChatMessageItem.kt` @ c15de97
 * `LaunchedEffect(running) { if (running && !manuallyExpanded) expanded = true }`)
 * plus the projector's `collapsed = true` terminal transition
 * (`ui/app/AgentRunMessageProjector.kt` @ c15de97). A manual tap wins for the
 * rest of the run — the user's explicit state is never fought.
 *
 * Panel: sections in source order — a thinking block renders through the
 * existing [ThinkingBlock] (keeps its windowed large-content handling), a tool
 * call renders through the existing [ToolCallPill] so the detail sheet, stop
 * button and long-press copy menu stay identical to the perTool layout.
 */
@Composable
internal fun WorkProcessRowView(
    process: WorkProcess,
    allToolBlocks: List<AssistantBlock>,
    onStop: (() -> Unit)? = null,
    onOpenDetail: (String) -> Unit = {},
    onOpenTerminalWithCommand: (String) -> Unit = {},
    onCopyDetails: ((AssistantBlock) -> Unit)? = null,
    onRerunFromHere: (() -> Unit)? = null,
    onRetry: (() -> Unit)? = null,
) {
    // Everything the summary reads: the blocks, whether the turn is still live, and the turn's end time (the
    // duration). Keyed on the blocks alone it kept saying "working" after the turn had ended.
    val summary = remember(process.blocks, process.turnLive, process.messageUpdatedAtMs, process.messageCreatedAtMs) {
        process.summary()
    }
    // One clock per turn, on the run under the user's message: it ticks from the send until the whole reply is done.
    val startedAt = process.startedAtMs
    val clockRunning = process.clockLive && startedAt != null
    var elapsedSec by remember(process.id, clockRunning, startedAt) { mutableStateOf(0L) }
    LaunchedEffect(process.id, clockRunning, startedAt) {
        if (!clockRunning || startedAt == null) {
            elapsedSec = 0L
        } else {
            while (true) {
                elapsedSec = ((System.currentTimeMillis() - startedAt) / 1000L).coerceAtLeast(0L)
                delay(1_000L)
            }
        }
    }
    var expanded by rememberSaveable(process.id) { mutableStateOf(summary.isRunning) }
    var userToggled by rememberSaveable(process.id) { mutableStateOf(false) }

    // Auto-expand while the run is live; fold back when it ends. Skipped once
    // the user picked a state themselves (Eta's `manuallyExpanded` gate).
    LaunchedEffect(process.id, summary.isRunning) {
        if (!userToggled) expanded = summary.isRunning
    }

    val statusText = workStatusText(summary, elapsedSec.takeIf { clockRunning })

    // The redesign's status line: one small grey line above the reply, "已完成 · 用时 12s" with a chevron
    // (right when folded, down when open), or "正在工作 ..." while the turn runs. No bar, no rule under it.
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .clip(RoundedCornerShape(8.dp))
                .clickable {
                    userToggled = true
                    expanded = !expanded
                }
                .heightIn(min = 30.dp)
                .padding(end = 8.dp, bottom = 6.dp),
            verticalAlignment = Alignment.Top,
        ) {
            Text(
                text = statusText,
                fontSize = 13.sp,
                lineHeight = 20.sp,
                color = ChatColors.secondaryText,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false),
            )
            if (summary.isRunning || clockRunning) {
                Spacer(Modifier.width(6.dp))
                Box(modifier = Modifier.height(20.dp), contentAlignment = Alignment.Center) { BouncingDots(ChatColors.secondaryText) }
            }
            Spacer(Modifier.width(4.dp))
            Box(modifier = Modifier.height(20.dp), contentAlignment = Alignment.Center) {
                StepChevron(
                    expanded = expanded,
                    contentDescription = stringResource(
                        if (expanded) R.string.work_process_collapse else R.string.work_process_expand,
                    ),
                )
            }
        }
        AnimatedVisibility(
            visible = expanded,
            enter = fadeIn() + expandVertically(
                animationSpec = spring(
                    dampingRatio = Spring.DampingRatioNoBouncy,
                    stiffness = Spring.StiffnessMediumLow,
                ),
            ),
            exit = fadeOut() + shrinkVertically(
                animationSpec = spring(
                    dampingRatio = Spring.DampingRatioNoBouncy,
                    stiffness = Spring.StiffnessMediumLow,
                ),
            ),
        ) {
            val panelScroll = androidx.compose.foundation.rememberScrollState()
            Column(modifier = Modifier.padding(start = 4.dp, top = 2.dp)) {
              // A long run scrolls inside a bounded panel, so the header (and the fold button under it) is never
              // a long scroll away; a short run just lays out in full.
              Column(
                modifier = Modifier
                    .heightIn(max = WORK_PANEL_MAX_HEIGHT)
                    .verticalScroll(panelScroll),
              ) {
                val trailingBlockId = process.blocks.lastOrNull()?.id
                process.blocks.forEach { block ->
                    when (block.kind) {
                        // Thinking and the model's own remarks are one line each, like the tool steps; a tap opens them.
                        THINKING_KIND -> OneLineNote(
                            block = block,
                            isThinking = true,
                            isStreaming = summary.isRunning && block.id == trailingBlockId,
                        )
                        else -> if (block.kind == TOOL_USE_KIND) {
                            Column {
                                ToolCallPill(
                                    block = block,
                                    allToolBlocks = allToolBlocks,
                                    onRetry = onRetry,
                                    onStop = onStop,
                                    onOpenTerminalWithCommand = onOpenTerminalWithCommand,
                                    onOpenDetail = onOpenDetail,
                                    onRerunFromHere = onRerunFromHere,
                                    onCopyDetails = onCopyDetails?.let { copy -> { copy(block) } },
                                )
                                // [T-android-work-items] Codex keeps a file change as its own item
                                // with the path and the diff on the item itself. The full diff stays
                                // in the detail sheet; this line answers "which file, how much"
                                // without opening anything.
                                fileChangeSummary(block)?.let { change ->
                                    Row(
                                        modifier = Modifier.padding(start = 30.dp, end = 4.dp, bottom = 6.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        Text(
                                            text = change.fileName,
                                            style = MaterialTheme.typography.labelMedium,
                                            color = ChatColors.secondaryText,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                            modifier = Modifier.weight(1f, fill = false),
                                        )
                                        Spacer(Modifier.width(8.dp))
                                        if (change.addedLines > 0) {
                                            Text(
                                                text = "+" + change.addedLines,
                                                style = MaterialTheme.typography.labelMedium,
                                                color = ChatColors.ok,
                                            )
                                        }
                                        if (change.addedLines > 0 && change.removedLines > 0) {
                                            Spacer(Modifier.width(6.dp))
                                        }
                                        if (change.removedLines > 0) {
                                            Text(
                                                text = "−" + change.removedLines,
                                                style = MaterialTheme.typography.labelMedium,
                                                color = ToolErrorColor,
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
              }
              // The fold button sits under the steps, so a long run never has to be scrolled back to its start.
              Row(
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .clickable {
                        userToggled = true
                        expanded = false
                    }
                    .padding(horizontal = 4.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
              ) {
                Text(
                    text = stringResource(R.string.work_process_collapse),
                    fontSize = 13.sp,
                    color = ChatColors.secondaryText,
                )
                Spacer(Modifier.width(4.dp))
                Icon(
                    Icons.Filled.KeyboardArrowUp,
                    contentDescription = null,
                    tint = ChatColors.secondaryText,
                    modifier = Modifier.size(16.dp),
                )
              }
            }
        }
    }
}

/** Tallest the opened steps panel gets before it scrolls on its own. */
private val WORK_PANEL_MAX_HEIGHT = 440.dp

/**
 * One step of thinking or narration as a single line, the way a tool step is: a plain-text preview
 * (markdown marks stripped, the latest line while it streams), with the full text a tap away.
 */
@Composable
private fun OneLineNote(block: AssistantBlock, isThinking: Boolean, isStreaming: Boolean) {
    var open by rememberSaveable(block.id) { mutableStateOf(false) }
    val preview = notePreview(block.content, latest = isStreaming)
    if (preview.isEmpty() && !open) return
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(8.dp))
                .clickable { open = !open }
                .padding(horizontal = 4.dp, vertical = 5.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = if (isThinking) stringResource(R.string.chat_thinking_chip, preview) else preview,
                fontSize = 13.sp,
                color = ChatColors.secondaryText,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(4.dp))
            StepChevron(expanded = open, contentDescription = null)
        }
        if (open) {
            if (isThinking) {
                ThinkingNote(block = block, isStreaming = isStreaming)
            } else {
                StreamingMarkdownText(
                    content = block.content,
                    isStreaming = isStreaming,
                    modifier = Modifier.padding(horizontal = 4.dp, vertical = 4.dp),
                )
            }
        }
    }
}

/** The line a collapsed note shows: first non-blank line (latest one while streaming), without markdown marks. */
internal fun notePreview(content: String, latest: Boolean, maxChars: Int = 200): String {
    val lines = content.lineSequence().map { it.trim() }.filter { it.isNotEmpty() }
    val line = (if (latest) lines.lastOrNull() else lines.firstOrNull()).orEmpty()
    val plain = line
        .replace(Regex("^(#{1,6}|[-*+>]|\\d+[.)])\\s+"), "")
        .replace(Regex("[*_`~]+"), "")
        .trim()
    return if (plain.length <= maxChars) plain else plain.take(maxChars).trimEnd() + "…"
}

/**
 * The status line's words. Running: "Working · 12s · step 3 · <what the step does>" - the time only on the run that
 * carries the turn's clock. Finished: "Completed 5 steps · read 3 files, ran 2 commands · took 38s", the time again
 * only on that run. The wording rules live here so they stay in one place.
 */
@Composable
internal fun workStatusText(summary: WorkProcessSummary, clockElapsedSec: Long? = null): String {
    val parts = ArrayList<String>(4)
    if (summary.isRunning || clockElapsedSec != null) {
        parts.add(stringResource(R.string.work_status_running))
        if (clockElapsedSec != null) parts.add(formatStepDuration(clockElapsedSec, stillRunning = false))
        if (summary.isRunning) {
            summary.runningStepNumber?.let { parts.add(stringResource(R.string.work_status_step, it)) }
            when {
                summary.runningToolName != null -> parts.add(summary.runningToolName)
                summary.thinking -> parts.add(stringResource(R.string.work_status_thinking))
            }
        }
        return parts.joinToString(" · ")
    }
    parts.add(
        if (summary.toolCount > 0) {
            pluralStringResource(R.plurals.work_process_completed_steps, summary.toolCount, summary.toolCount)
        } else {
            stringResource(R.string.work_status_done)
        },
    )
    val groups = tallyEntries(summary.tallies)
    if (groups.isNotEmpty()) {
        val labels = ArrayList<String>(groups.size)
        for (index in groups.indices) labels.add(workItemTallyLabel(groups[index].first, groups[index].second))
        parts.add(labels.joinToString(", "))
    }
    summary.durationMs?.let {
        parts.add(stringResource(R.string.work_process_duration, formatStepDuration(it / 1000L, stillRunning = false)))
    }
    return parts.joinToString(" · ")
}

/**
 * Thinking text inside the expanded status line: a soft grey box (#F5F5F7, 12dp corners, 14/22.4 grey text),
 * the redesign's note. Long thinking keeps only its tail and scrolls inside the box.
 */
@Composable
internal fun ThinkingNote(block: AssistantBlock, isStreaming: Boolean) {
    val content = block.content
    val shown = if (content.length > THINKING_NOTE_TAIL) content.takeLast(THINKING_NOTE_TAIL) else content
    if (shown.isBlank()) return
    val scroll = androidx.compose.foundation.rememberScrollState()
    LaunchedEffect(content.length, isStreaming) {
        if (isStreaming) scroll.scrollTo(scroll.maxValue)
    }
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 6.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(if (ChatColors.isDark) ChatColors.secondaryBg else Color(0xFFF5F5F7))
            .heightIn(max = 260.dp)
            .verticalScroll(scroll)
            .padding(horizontal = 12.dp, vertical = 10.dp),
    ) {
        Text(text = shown, fontSize = 14.sp, lineHeight = 22.4.sp, color = ChatColors.secondaryText)
    }
}

private const val THINKING_NOTE_TAIL = 8_000

/** [T-android-work-items] One group of the expanded summary line, e.g. "7 commands". */
@Composable
internal fun workItemTallyLabel(kind: WorkItemKind, count: Int): String {
    val plural = when (kind) {
        WorkItemKind.COMMAND -> R.plurals.work_item_command
        WorkItemKind.FILE_READ -> R.plurals.work_item_file_read
        WorkItemKind.FILE_EDIT -> R.plurals.work_item_file_edit
        WorkItemKind.SEARCH -> R.plurals.work_item_search
        WorkItemKind.BROWSER -> R.plurals.work_item_browser
        WorkItemKind.MCP -> R.plurals.work_item_mcp
        WorkItemKind.IMAGE -> R.plurals.work_item_image
        WorkItemKind.DELEGATION -> R.plurals.work_item_delegation
        // Filtered out by tallyEntries; kept exhaustive so a new kind is a compile error here.
        WorkItemKind.REASONING, WorkItemKind.OTHER -> R.plurals.work_item_command
    }
    return pluralStringResource(plural, count, count)
}
