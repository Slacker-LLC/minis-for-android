package com.openminis.app.ui.chat

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.openminis.app.R
import com.openminis.app.agent.subagents.SubAgentJob
import com.openminis.app.agent.subagents.SubAgentJobState
import com.openminis.app.agent.subagents.SubAgents
import com.openminis.app.ui.components.MinisTextButton
import com.openminis.app.ui.theme.ChatColors
import com.openminis.app.ui.theme.minisSheetColor
import kotlinx.coroutines.delay

/** What the card can do to a sub agent run; supplied by the chat screen, which knows the conversation. */
internal class SubAgentCardActions(
    val onStop: (jobId: String) -> Unit,
    val onResume: (jobId: String) -> Unit,
    val onSteer: (jobId: String, message: String) -> Unit,
    val onOpenSession: (sessionId: String) -> Unit,
)

/**
 * The detail view of a `subagent` tool block that started a run: who, on which model, how long, the
 * task, live status and — once it ends — the result. Stop / Steer while it runs, Resume when the app lost
 * it, and a way into the child session to watch it work. The status is read live from the registry; a
 * run the registry no longer holds falls back to what the call itself returned.
 */
@Composable
internal fun SubAgentCard(
    ref: SubAgentCardRef,
    actions: SubAgentCardActions?,
    scrollState: ScrollState,
) {
    val context = LocalContext.current
    val jobs by SubAgents.runtime(context).registry.jobs.collectAsState()
    val job: SubAgentJob? = jobs[ref.jobId]

    val state: SubAgentJobState? = job?.state
    val statusWire = state?.wire ?: ref.statusAtCall
    val running = state == SubAgentJobState.RUNNING || (state == null && ref.statusAtCall == "running")
    val queued = state == SubAgentJobState.QUEUED || (state == null && ref.statusAtCall == "queued")
    val interrupted = state == SubAgentJobState.INTERRUPTED
    val finished = isFinishedSubAgentStatus(statusWire)
    val resultText = job?.resultText ?: ref.resultAtCall

    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(state) {
        while (state == SubAgentJobState.RUNNING) {
            now = System.currentTimeMillis()
            delay(1_000)
        }
    }
    var steerOpen by remember { mutableStateOf(false) }
    var steerText by remember { mutableStateOf("") }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(minisSheetColor())
            .verticalScroll(scrollState)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Column(Modifier.weight(1f)) {
                Text(
                    ref.agent ?: job?.agentName ?: stringResource(R.string.sub_agent_card_title),
                    color = ChatColors.primaryText, fontSize = 18.sp, fontWeight = FontWeight.SemiBold,
                )
                Text(
                    listOfNotNull(ref.model ?: job?.modelLabel, ref.jobId.take(8)).joinToString(" · "),
                    color = ChatColors.secondaryText, fontSize = 12.sp,
                )
            }
            StatusChip(statusWire, spinning = running)
        }

        job?.elapsedMs(now)?.let {
            Text(formatElapsed(it), color = ChatColors.secondaryText, fontSize = 13.sp)
        }

        if (queued) {
            Text(stringResource(R.string.sub_agent_card_queued_hint), color = ChatColors.secondaryText, fontSize = 13.sp)
        }
        if (interrupted) {
            Text(stringResource(R.string.sub_agent_card_interrupted_hint), color = ChatColors.secondaryText, fontSize = 13.sp)
        }

        ref.task?.let { Section(stringResource(R.string.sub_agent_card_task), it, maxLines = 8) }

        if (finished && !resultText.isNullOrBlank()) {
            Section(stringResource(R.string.sub_agent_card_result), resultText, maxLines = Int.MAX_VALUE, selectable = true)
        } else if (running && ref.resultAtCall == null) {
            Text(stringResource(R.string.sub_agent_card_working), color = ChatColors.secondaryText, fontSize = 13.sp)
        }

        // Controls
        if (actions != null) {
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                if (running || queued) {
                    MinisTextButton(onClick = { actions.onStop(ref.jobId) }) {
                        Text(stringResource(R.string.sub_agent_card_stop), color = MaterialTheme.colorScheme.error)
                    }
                }
                if (running) {
                    MinisTextButton(onClick = { steerOpen = !steerOpen }) { Text(stringResource(R.string.sub_agent_card_steer)) }
                }
                if (interrupted) {
                    MinisTextButton(onClick = { actions.onResume(ref.jobId) }) { Text(stringResource(R.string.sub_agent_card_resume)) }
                }
                job?.childSessionId?.let { child ->
                    MinisTextButton(onClick = { actions.onOpenSession(child) }) { Text(stringResource(R.string.sub_agent_card_open_session)) }
                }
            }
            if (steerOpen && running) {
                OutlinedTextField(
                    value = steerText,
                    onValueChange = { steerText = it },
                    placeholder = { Text(stringResource(R.string.sub_agent_card_steer_hint)) },
                    modifier = Modifier.fillMaxWidth(),
                )
                MinisTextButton(
                    enabled = steerText.isNotBlank(),
                    onClick = {
                        actions.onSteer(ref.jobId, steerText.trim())
                        steerText = ""
                        steerOpen = false
                    },
                ) { Text(stringResource(R.string.sub_agent_card_send)) }
            }
        }
        Spacer(Modifier.height(8.dp))
    }
}

@Composable
private fun Section(title: String, body: String, maxLines: Int, selectable: Boolean = false) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(title, color = ChatColors.secondaryText, fontSize = 12.sp, fontWeight = FontWeight.Medium)
        val text = @Composable {
            Text(
                body,
                color = ChatColors.primaryText,
                fontSize = 14.sp,
                lineHeight = 20.sp,
                maxLines = maxLines,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
            )
        }
        if (selectable) SelectionContainer { text() } else text()
    }
}

@Composable
private fun StatusChip(wire: String?, spinning: Boolean) {
    val (label, color) = when (wire) {
        "queued" -> stringResource(R.string.sub_agent_state_queued) to Color(0xFF8E8E93)
        "running" -> stringResource(R.string.sub_agent_state_running) to Color(0xFF007AFF)
        "completed" -> stringResource(R.string.sub_agent_state_completed) to Color(0xFF34C759)
        "cancelled" -> stringResource(R.string.sub_agent_state_cancelled) to Color(0xFF8E8E93)
        "failed" -> stringResource(R.string.sub_agent_state_failed) to Color(0xFFFF3B30)
        "timeout" -> stringResource(R.string.sub_agent_state_timeout) to Color(0xFFFF9500)
        "interrupted" -> stringResource(R.string.sub_agent_state_interrupted) to Color(0xFFFF9500)
        else -> (wire ?: "") to Color(0xFF8E8E93)
    }
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(color.copy(alpha = 0.14f))
            .padding(horizontal = 10.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        if (spinning) CircularProgressIndicator(modifier = Modifier.size(12.dp), strokeWidth = 1.5.dp, color = color)
        Text(label, color = color, fontSize = 12.sp, fontWeight = FontWeight.Medium)
    }
}

/** 75 → "1m 15s"; under a minute → "42s". */
internal fun formatElapsed(ms: Long): String {
    val s = (ms / 1000).coerceAtLeast(0)
    return if (s < 60) "${s}s" else "${s / 60}m ${s % 60}s"
}
