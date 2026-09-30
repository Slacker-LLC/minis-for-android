package com.openminis.app.ui.scheduled

import com.openminis.app.ui.theme.ChatColors
import com.openminis.app.ui.bots.StatusPill
import com.openminis.app.ui.components.MinisEmptyState
import com.openminis.app.ui.settings.SettingsRow
import com.openminis.app.ui.settings.SettingsSection
import com.openminis.app.ui.settings.SettingsScaffold
import androidx.compose.foundation.clickable
import com.openminis.app.ui.settings.MinisTopBar
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.outlined.History
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.openminis.app.R
import com.openminis.app.scheduled.ScheduledRun
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * [T-android-scheduled-tasks-run-records] Execution log for one scheduled
 * task. Lists each recorded run newest-first; a run that produced a chat
 * (non-null sessionId) is tappable → opens that chat. Backed by the live
 * tasks flow so new runs appear without re-entering the screen.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScheduledTaskRunsScreen(
    taskId: String,
    onBack: () -> Unit,
    onOpenSession: (sessionId: String) -> Unit,
) {
    val context = LocalContext.current
    val vm: ScheduledTasksViewModel = androidx.lifecycle.viewmodel.compose.viewModel(
        factory = ScheduledTasksViewModel.factory(context),
    )
    val tasks by vm.tasks.collectAsState()
    val task = tasks.firstOrNull { it.id == taskId }
    val runs = task?.runHistory ?: emptyList()

    SettingsScaffold(
        title = stringResource(R.string.scheduled_task_runs_title),
        onBack = onBack,
        backLabel = stringResource(R.string.scheduled_tasks_title),
    ) {
        if (task != null && task.label.isNotBlank()) {
            Text(
                task.label,
                fontSize = 14.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
            )
        }
        if (runs.isEmpty()) {
            MinisEmptyState(
                icon = Icons.Outlined.History,
                title = stringResource(R.string.scheduled_task_runs_empty),
                modifier = Modifier.padding(top = 48.dp),
            )
        } else {
            SettingsSection {
                runs.forEachIndexed { index, run ->
                    RunRow(run = run, onOpenSession = onOpenSession, showDivider = index < runs.size - 1)
                }
            }
        }
    }
}

@Composable
private fun RunRow(run: ScheduledRun, onOpenSession: (String) -> Unit, showDivider: Boolean) {
    val tappable = run.sessionId != null
    SettingsRow(
        title = formatRunTime(run.firedAt),
        subtitle = run.preview?.takeIf { it.isNotBlank() },
        onClick = if (tappable) ({ onOpenSession(run.sessionId!!) }) else null,
        showDivider = showDivider,
        trailing = {
            StatusPill(
                stringResource(if (run.ok) R.string.scheduled_run_ok else R.string.scheduled_run_failed),
                if (run.ok) ChatColors.ok else ChatColors.bad,
            )
        },
    )
}

private fun formatRunTime(ms: Long): String =
    SimpleDateFormat("MMM d, HH:mm:ss", Locale.getDefault()).format(Date(ms))
