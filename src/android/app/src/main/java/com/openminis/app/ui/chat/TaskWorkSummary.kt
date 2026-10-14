package com.openminis.app.ui.chat

import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.room.withTransaction
import com.openminis.app.MinisApp
import com.openminis.app.R
import com.openminis.app.data.db.AgentTaskEntity
import com.openminis.app.data.model.SessionOverrides
import com.openminis.app.ui.components.MinisTextButton
import kotlinx.coroutines.launch
import org.json.JSONObject

/** Task facts stay in Room; this card is shared by chat and voice. */
@Composable
internal fun TaskWorkSummary(sessionId: String) {
    val context = LocalContext.current
    val app = context.applicationContext as MinisApp
    val scope = rememberCoroutineScope()
    val tasks by remember(sessionId) { app.database.agentTaskDao().observeSessionTasks(sessionId) }.collectAsState(initial = emptyList())
    val session by remember(sessionId) { app.database.chatDao().observeSession(sessionId) }.collectAsState(initial = null)
    val pendingReview by remember(sessionId) { app.database.chatDao().observeRequestsNeedingReview(sessionId) }.collectAsState(initial = 0)
    val selectedId = SessionOverrides.fromJson(session?.sessionOverrides).taskId
    var expanded by remember(sessionId) { mutableStateOf(false) }
    var acting by remember(sessionId) { mutableStateOf(false) }
    fun act(block: suspend () -> Unit) {
        scope.launch {
            acting = true
            try { block() }
            catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
            catch (error: Exception) { Toast.makeText(context, error.message, Toast.LENGTH_LONG).show() }
            finally { acting = false }
        }
    }
    if (tasks.isEmpty() && pendingReview == 0) return
    Column {
        if (pendingReview > 0) Text(stringResource(R.string.work_request_review), style = MaterialTheme.typography.bodySmall)
        if (selectedId != null) MinisTextButton(onClick = { act {
            app.database.withTransaction {
                val current = checkNotNull(app.chatRepository.getSession(sessionId))
                val overrides = JSONObject(current.sessionOverrides ?: "{}").also { it.remove("taskId") }
                app.chatRepository.dao.updateSessionOverrides(sessionId, overrides.toString())
            }
        } }, enabled = !acting) { Text(stringResource(R.string.work_task_clear_selection)) }
        MinisTextButton(onClick = { expanded = !expanded }) { Text(stringResource(R.string.work_task_summary)) }
        if (expanded) tasks.forEach { task ->
            val status = stringResource(when (task.status) {
                AgentTaskEntity.STATUS_COMPLETED -> R.string.agent_task_completed
                AgentTaskEntity.STATUS_CANCELLED -> R.string.agent_task_cancelled
                AgentTaskEntity.STATUS_FAILED -> R.string.agent_task_failed
                AgentTaskEntity.STATUS_PAUSED -> R.string.agent_task_paused
                AgentTaskEntity.STATUS_NEEDS_USER -> R.string.agent_task_needs_user
                AgentTaskEntity.STATUS_BUDGET_EXHAUSTED -> R.string.agent_task_budget_exhausted
                AgentTaskEntity.STATUS_WAITING -> R.string.work_task_waiting
                else -> R.string.agent_task_active
            })
            val phase = stringResource(when (task.phase) {
                AgentTaskEntity.PHASE_PLANNING -> R.string.agent_task_phase_planning
                AgentTaskEntity.PHASE_REVIEWING -> R.string.agent_task_phase_reviewing
                AgentTaskEntity.PHASE_REVISING -> R.string.agent_task_phase_revising
                AgentTaskEntity.PHASE_DELIVERING -> R.string.agent_task_phase_delivering
                else -> R.string.agent_task_phase_executing
            })
            Text(task.goal, style = MaterialTheme.typography.bodyMedium, maxLines = 3)
            Text(listOf(status, phase).joinToString(" · "),
                style = MaterialTheme.typography.labelMedium)
            if (task.progress.isNotBlank()) Text(task.progress, style = MaterialTheme.typography.bodySmall, maxLines = 4)
            val restore = task.completionEvidence?.let { runCatching { JSONObject(it).optJSONObject("restoration") }.getOrNull() }
            if (restore?.optBoolean("requires_review") == true) {
                MinisTextButton(onClick = { act { com.openminis.app.tools.ToolOutcomeVerification.reviewRestoreFromUi(context, sessionId, task) } }, enabled = !acting) {
                    Text(stringResource(R.string.work_task_restore_review))
                }
            }
            if (task.status == AgentTaskEntity.STATUS_PAUSED) {
                MinisTextButton(onClick = { act { app.database.agentTaskDao().resumeReviewedTask(task) } }, enabled = !acting) {
                    Text(stringResource(R.string.work_task_resume))
                }
            } else if (task.status !in AgentTaskEntity.TERMINAL_STATUSES) {
                Row {
                    MinisTextButton(onClick = { act {
                        app.agentTaskRepository.pause(task.id)
                    } }, enabled = !acting) { Text(stringResource(R.string.agent_task_pause)) }
                    MinisTextButton(onClick = { act {
                        app.agentTaskRepository.cancel(task.id)
                    } }, enabled = !acting) { Text(stringResource(R.string.agent_task_cancel)) }
                }
            }
            if (task.status !in AgentTaskEntity.TERMINAL_STATUSES && task.status != AgentTaskEntity.STATUS_PAUSED) {
                MinisTextButton(onClick = { act {
                    app.database.withTransaction {
                        val owner = checkNotNull(app.chatRepository.getSession(sessionId))
                        val current = checkNotNull(app.agentTaskRepository.get(task.id))
                        check(current.originSessionId == sessionId || current.currentOwnerSessionId == sessionId ||
                            current.goalSessionId == sessionId) { "task_owner_mismatch" }
                        check(com.openminis.app.agent.AgentSessionStore.activeRun(sessionId) == null) { "current_turn_still_running" }
                        val overrides = JSONObject(owner.sessionOverrides ?: "{}").put("taskId", task.id)
                        app.chatRepository.dao.updateSessionOverrides(sessionId, overrides.toString())
                    }
                } }, enabled = !acting && selectedId != task.id) {
                    Text(stringResource(if (selectedId == task.id) R.string.work_task_selected else R.string.work_task_select))
                }
            }
            if (task.status == AgentTaskEntity.STATUS_COMPLETED) MinisTextButton(onClick = {
                context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("minis://settings/files")).setPackage(context.packageName))
            }) { Text(stringResource(R.string.work_task_results)) }
        }
    }
}
