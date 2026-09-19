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
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
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
    val summary = remember(process.blocks) { process.summary() }
    var expanded by rememberSaveable(process.id) { mutableStateOf(summary.isRunning) }
    var userToggled by rememberSaveable(process.id) { mutableStateOf(false) }

    // Auto-expand while the run is live; fold back when it ends. Skipped once
    // the user picked a state themselves (Eta's `manuallyExpanded` gate).
    LaunchedEffect(process.id, summary.isRunning) {
        if (!userToggled) expanded = summary.isRunning
    }

    val failed = summary.failureReason != null
    val accent = when {
        failed -> ToolErrorColor
        summary.isRunning -> MaterialTheme.colorScheme.primary
        else -> ChatColors.secondaryText
    }
    val runningTool = process.runningTool
    val headerIcon = runningTool?.let { toolIconFor(it.toolName) } ?: Icons.Default.Build
    val headerText = workProcessHeaderText(summary)

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(ChatColors.secondaryBg.copy(alpha = 0.55f))
            .border(
                width = 0.5.dp,
                color = ChatColors.separator.copy(alpha = 0.55f),
                shape = RoundedCornerShape(12.dp),
            ),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable {
                    userToggled = true
                    expanded = !expanded
                }
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = headerIcon,
                contentDescription = null,
                tint = accent,
                modifier = Modifier.size(16.dp),
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = headerText,
                style = MaterialTheme.typography.bodyMedium,
                color = if (summary.isRunning || failed) ChatColors.primaryText else ChatColors.secondaryText,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Icon(
                imageVector = if (expanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                contentDescription = stringResource(
                    if (expanded) R.string.work_process_collapse else R.string.work_process_expand,
                ),
                tint = ChatColors.tertiaryText,
                modifier = Modifier.size(16.dp),
            )
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
            Column {
                HorizontalDivider(
                    color = ChatColors.separator.copy(alpha = 0.45f),
                    thickness = 0.5.dp,
                )
                // [T-android-work-items] What the run consisted of, by type - the same idea as
                // Codex's grouped work items, in one line above the individual steps.
                // [T-android-work-items] What the run consisted of, by type - the same idea as
                // Codex's grouped work items, in one line above the individual steps. The labels
                // are resolved with a plain loop: a composable call inside joinToString's lambda
                // is not a composable context.
                val tallyGroups = tallyEntries(summary.tallies)
                if (tallyGroups.isNotEmpty()) {
                    val parts = ArrayList<String>(tallyGroups.size)
                    for (index in tallyGroups.indices) {
                        val (kind, count) = tallyGroups[index]
                        parts.add(workItemTallyLabel(kind, count))
                    }
                    Text(
                        text = parts.joinToString(" · "),
                        style = MaterialTheme.typography.bodySmall,
                        color = ChatColors.secondaryText,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                    )
                }
                val trailingBlockId = process.blocks.lastOrNull()?.id
                process.blocks.forEach { block ->
                    when (block.kind) {
                        THINKING_KIND -> ThinkingBlock(
                            block = block,
                            // Only the still-trailing thinking block of a live
                            // run behaves like a streaming block (auto-expand);
                            // every earlier / finished one stays folded.
                            isStreaming = summary.isRunning && block.id == trailingBlockId,
                            isLast = block.id == trailingBlockId,
                        )
                        else -> if (block.kind == TOOL_USE_KIND) {
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
                        }
                    }
                }
                Spacer(Modifier.height(4.dp))
            }
        }
    }
}

/**
 * Collapsed-row / header text for [summary]. Pulled out of the composable so
 * the wording rules (which state wins, what the count means) are pure and
 * unit-testable — see `WorkProcessHeaderTextTest`.
 */
@Composable
internal fun workProcessHeaderText(summary: WorkProcessSummary): String = when {
    summary.isRunning && summary.runningStepNumber != null -> stringResource(
        R.string.work_process_running_step,
        summary.runningStepNumber,
        summary.runningToolName.orEmpty(),
    )
    summary.isRunning -> stringResource(R.string.work_process_running_thinking)
    summary.failureReason != null -> stringResource(
        R.string.work_process_failed_step,
        summary.failedStepNumber ?: 0,
        summary.failureReason,
    )
    // [T-android-work-items] A finished run leads with how long it took, the way Codex's turn
    // header does; the step count is what older rows (no timings) still fall back to.
    summary.durationMs != null -> stringResource(
        R.string.work_process_duration,
        formatStepDuration(summary.durationMs / 1000L, stillRunning = false),
    )
    summary.toolCount > 0 -> pluralStringResource(
        R.plurals.work_process_completed_steps,
        summary.toolCount,
        summary.toolCount,
    )
    else -> stringResource(R.string.work_process_completed_thinking)
}

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
