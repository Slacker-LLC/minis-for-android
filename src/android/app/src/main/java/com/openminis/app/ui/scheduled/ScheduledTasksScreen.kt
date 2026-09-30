package com.openminis.app.ui.scheduled

import androidx.compose.foundation.ExperimentalFoundationApi
import com.openminis.app.ui.settings.MinisTopBar
import androidx.compose.foundation.background
import com.openminis.app.ui.theme.ChatColors
import com.openminis.app.ui.settings.SettingsSwitch
import com.openminis.app.ui.settings.SettingsScaffold
import com.openminis.app.ui.components.MinisTextButton
import androidx.compose.ui.draw.alpha
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.openminis.app.R
import com.openminis.app.scheduled.ScheduledRepeatMode
import com.openminis.app.scheduled.ScheduledTargetMode
import com.openminis.app.scheduled.ScheduledTask
import com.openminis.app.scheduled.ScheduledTaskPermissionTier
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import com.openminis.app.ui.components.MinisAlertDialog

/**
 * [T-android-scheduled-tasks-design / T-android-scheduled-tasks-run-records]
 * List + manage scheduled tasks. Entry point from SessionListScreen's
 * TopAppBar; tap a row to edit, FAB to create, switch to enable/disable.
 * Long-press opens a menu: Edit / Run records / Delete (with confirm).
 * The row no longer shows a result preview — execution history lives in
 * the Run records screen.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScheduledTasksScreen(
    onBack: () -> Unit,
    onEditTask: (taskId: String?) -> Unit,
    onViewRuns: (taskId: String) -> Unit,
    onOpenSession: (sessionId: String) -> Unit,
) {
    val context = LocalContext.current
    val vm: ScheduledTasksViewModel = androidx.lifecycle.viewmodel.compose.viewModel(
        factory = ScheduledTasksViewModel.factory(context),
    )
    val tasks by vm.tasks.collectAsState()
    val bots by vm.bots.collectAsState()
    var pendingDelete by remember { mutableStateOf<ScheduledTask?>(null) }

    SettingsScaffold(
        title = stringResource(R.string.scheduled_tasks_title),
        onBack = onBack,
        largeTitle = true,
        actions = {
            IconButton(onClick = { onEditTask(null) }) {
                Icon(Icons.Filled.Add, contentDescription = stringResource(R.string.scheduled_task_new), tint = MaterialTheme.colorScheme.primary)
            }
        },
    ) {
        if (tasks.isEmpty()) {
            Text(
                stringResource(R.string.scheduled_tasks_empty),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 24.dp),
            )
        } else {
            tasks.forEach { task ->
                ScheduledTaskCard(
                    task = task,
                    botName = task.botId?.let { id -> bots.firstOrNull { it.id == id }?.name ?: id },
                    onClick = { onEditTask(task.id) },
                    onToggle = { vm.setEnabled(task.id, it) },
                    onRunNow = { vm.runNow(task) },
                    onEdit = { onEditTask(task.id) },
                    onViewRuns = { onViewRuns(task.id) },
                    onDelete = { pendingDelete = task },
                )
            }
            Text(
                stringResource(R.string.scheduled_tasks_footer),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 16.dp),
            )
        }
    }

    val toDelete = pendingDelete
    if (toDelete != null) {
        MinisAlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text(stringResource(R.string.scheduled_task_delete_title)) },
            text = { Text(stringResource(R.string.scheduled_task_delete_body, toDelete.label)) },
            confirmButton = {
                androidx.compose.material3.TextButton(onClick = {
                    vm.delete(toDelete.id)
                    pendingDelete = null
                }) {
                    Text(stringResource(R.string.scheduled_task_delete_confirm), color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                androidx.compose.material3.TextButton(onClick = { pendingDelete = null }) {
                    Text(stringResource(R.string.cancel))
                }
            },
        )
    }
}

/**
 * One routine, as the board draws it: name over "Daily 07:30 · New chat", the owning member as a chip,
 * the switch on the right; under a hairline the next run, Run now and a "..." menu (edit, run history,
 * delete). A switched-off routine is dimmed.
 */
@Composable
private fun ScheduledTaskCard(
    task: ScheduledTask,
    botName: String?,
    onClick: () -> Unit,
    onToggle: (Boolean) -> Unit,
    onRunNow: () -> Unit,
    onEdit: () -> Unit,
    onViewRuns: () -> Unit,
    onDelete: () -> Unit,
) {
    val context = LocalContext.current
    var menuExpanded by remember(task.id) { mutableStateOf(false) }
    val dim = if (task.enabled) 1f else 0.55f
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 10.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f).alpha(dim)) {
                Text(
                    text = task.label.ifBlank { task.prompt.take(40) },
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 17.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = scheduleSummary(context, task),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 14.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (botName != null || task.permissionTier == ScheduledTaskPermissionTier.FULL) {
                    Row(modifier = Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        if (botName != null) {
                            Chip(stringResource(R.string.scheduled_task_bot_label, botName), ChatColors.ok)
                        }
                        if (task.permissionTier == ScheduledTaskPermissionTier.FULL) {
                            Chip(stringResource(R.string.scheduled_task_tier_full), ChatColors.warn)
                        }
                    }
                }
            }
            Spacer(Modifier.width(12.dp))
            SettingsSwitch(checked = task.enabled, onCheckedChange = onToggle)
        }
        HorizontalDivider(modifier = Modifier.padding(top = 10.dp), thickness = 0.5.dp, color = MaterialTheme.colorScheme.outlineVariant)
        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                Icons.Outlined.Schedule,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(16.dp),
            )
            Spacer(Modifier.width(6.dp))
            Text(
                text = task.nextTriggerMs()?.let { stringResource(R.string.scheduled_task_next_run, relativeWhen(context, it)) }
                    ?: stringResource(R.string.scheduled_task_no_next_run),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 13.sp,
                modifier = Modifier.weight(1f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            MinisTextButton(onClick = onRunNow) { Text(stringResource(R.string.scheduled_task_run_now), fontSize = 15.sp) }
            Box {
                IconButton(onClick = { menuExpanded = true }) {
                    Icon(Icons.Filled.MoreHoriz, contentDescription = stringResource(R.string.common_more), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                com.openminis.app.ui.components.MinisMenu(
                    expanded = menuExpanded,
                    onDismissRequest = { menuExpanded = false },
                    shape = RoundedCornerShape(14.dp),
                    tonalElevation = 0.dp,
                ) {
                    androidx.compose.material3.DropdownMenuItem(
                        text = { Text(stringResource(R.string.scheduled_task_menu_edit)) },
                        trailingIcon = { Icon(Icons.Outlined.Edit, contentDescription = null) },
                        onClick = { menuExpanded = false; onEdit() },
                    )
                    androidx.compose.material3.DropdownMenuItem(
                        text = { Text(stringResource(R.string.scheduled_task_menu_runs)) },
                        trailingIcon = { Icon(Icons.Outlined.History, contentDescription = null) },
                        onClick = { menuExpanded = false; onViewRuns() },
                    )
                    com.openminis.app.ui.components.MinisMenuDivider()
                    androidx.compose.material3.DropdownMenuItem(
                        text = { Text(stringResource(R.string.scheduled_task_menu_delete), color = MaterialTheme.colorScheme.error) },
                        trailingIcon = { Icon(Icons.Outlined.Delete, contentDescription = null, tint = MaterialTheme.colorScheme.error) },
                        onClick = { menuExpanded = false; onDelete() },
                    )
                }
            }
        }
    }
}

@Composable
private fun Chip(text: String, tint: Color) {
    Text(
        text = text,
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(tint.copy(alpha = 0.14f))
            .padding(horizontal = 8.dp, vertical = 3.dp),
        color = tint,
        fontWeight = FontWeight.SemiBold,
        fontSize = 12.sp,
        maxLines = 1,
    )
}

/** "Daily 07:30 · New chat": the repeat rule, the time and what a trigger does. */
private fun scheduleSummary(context: android.content.Context, task: ScheduledTask): String {
    val time = "%02d:%02d".format(task.timeOfDayHour, task.timeOfDayMinute)
    val repeat = when (task.repeatMode) {
        ScheduledRepeatMode.ONCE -> context.getString(R.string.scheduled_task_repeat_once)
        ScheduledRepeatMode.DAILY -> context.getString(R.string.scheduled_task_repeat_daily)
        ScheduledRepeatMode.WEEKDAYS -> context.getString(R.string.scheduled_task_repeat_weekdays)
        ScheduledRepeatMode.CUSTOM -> {
            val names = java.text.DateFormatSymbols.getInstance().shortWeekdays
            val days = task.customDays.sorted().joinToString(",") { names.getOrNull(it).orEmpty() }
            days.ifBlank { context.getString(R.string.scheduled_task_repeat_custom) }
        }
    }
    val target = when (task.targetMode) {
        ScheduledTargetMode.NewSession -> R.string.scheduled_task_target_new
        is ScheduledTargetMode.AppendToSession -> R.string.scheduled_task_target_followup
        is ScheduledTargetMode.RerunMessage -> R.string.scheduled_task_target_rerun
    }
    return "$repeat $time · ${context.getString(target)}"
}

/** "Tomorrow 07:30" / "明天 07:30", localized by the platform. */
private fun relativeWhen(context: android.content.Context, ms: Long): String =
    android.text.format.DateUtils.getRelativeDateTimeString(
        context, ms, android.text.format.DateUtils.DAY_IN_MILLIS, android.text.format.DateUtils.WEEK_IN_MILLIS, 0,
    ).toString()
