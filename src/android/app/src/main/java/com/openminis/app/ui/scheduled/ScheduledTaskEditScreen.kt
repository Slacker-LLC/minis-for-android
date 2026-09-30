package com.openminis.app.ui.scheduled

import androidx.compose.foundation.clickable
import com.openminis.app.ui.settings.MinisTopBar
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.widthIn
import com.openminis.app.ui.components.MinisTextButton
import com.openminis.app.ui.settings.SettingsRow
import com.openminis.app.ui.settings.SettingsSegmented
import com.openminis.app.ui.settings.SettingsCardBlock
import com.openminis.app.ui.settings.SettingsSection
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimeInput
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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
import com.openminis.app.scheduled.ScheduledTaskTierMutationPolicy
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import com.openminis.app.ui.components.MinisOutlinedButton
import com.openminis.app.ui.components.MinisButton
import com.openminis.app.ui.components.MinisAlertDialog

/**
 * [T-android-scheduled-tasks-design / T-android-scheduled-tasks-full]
 * Create or edit a single scheduled task. taskId=null means create-new.
 *
 * Mirrors the iOS Shortcuts option set: target mode (new session / follow-up
 * an existing chat / re-run a chat from a chosen message), optional model
 * override, time (compact input), repeat, and an optional start/end date
 * window.
 */
private enum class TargetKind { NEW, FOLLOW_UP, RERUN }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScheduledTaskEditScreen(
    taskId: String?,
    initialBotId: String? = null,
    onBack: () -> Unit,
    onOpenSession: (sessionId: String) -> Unit,
) {
    val context = LocalContext.current
    val vm: ScheduledTasksViewModel = androidx.lifecycle.viewmodel.compose.viewModel(
        factory = ScheduledTasksViewModel.factory(context),
    )
    val bots by vm.bots.collectAsState()
    val scope = rememberCoroutineScope()
    val isNew = taskId == null

    var label by remember { mutableStateOf("") }
    var prompt by remember { mutableStateOf("") }
    var repeatMode by remember { mutableStateOf(ScheduledRepeatMode.DAILY) }
    var customDays by remember { mutableStateOf<Set<Int>>(emptySet()) }
    var hour by remember { mutableStateOf(9) }
    var minute by remember { mutableStateOf(0) }
    var enabled by remember { mutableStateOf(true) }
    var loaded by remember { mutableStateOf(isNew) }
    var createdAt by remember { mutableStateOf(System.currentTimeMillis()) }
    var lastFiredAt by remember { mutableStateOf<Long?>(null) }
    var lastResultPreview by remember { mutableStateOf<String?>(null) }
    var lastResultSessionId by remember { mutableStateOf<String?>(null) }
    var runHistory by remember { mutableStateOf<List<com.openminis.app.scheduled.ScheduledRun>>(emptyList()) }
    var saveError by remember { mutableStateOf<String?>(null) }
    var permissionTier by remember { mutableStateOf(ScheduledTaskPermissionTier.READ_ONLY) }
    var showFullTierConfirmation by remember { mutableStateOf(false) }
    var fullTierConfirmed by remember { mutableStateOf(false) }
    var saveAfterFullConfirmation by remember { mutableStateOf(false) }

    // [T-android-scheduled-tasks-full] target mode + model + date window
    var targetKind by remember { mutableStateOf(TargetKind.NEW) }
    var targetSessionId by remember { mutableStateOf<String?>(null) }
    var targetSessionTitle by remember { mutableStateOf<String?>(null) }
    var targetMessageId by remember { mutableStateOf<String?>(null) }
    var targetMessagePreview by remember { mutableStateOf<String?>(null) }
    var botId by remember(taskId, initialBotId) { mutableStateOf(initialBotId) }
    var targetBotId by remember(taskId) { mutableStateOf<String?>(null) }
    var targetBotLoaded by remember(taskId) { mutableStateOf(taskId == null) }
    // Three-way model selection mirroring ChatSessionEntity.modelBinding:
    //   - modelBinding == null  → "use app default" (resolved at run time)
    //   - {"type":"entry","entryId":"…"} → pinned to that entry
    //   - {"type":"group","groupId":"…"} → bound to that group (load-balance / fallback)
    var modelBinding by remember { mutableStateOf<String?>(null) }
    var modelDisplay by remember { mutableStateOf<String?>(null) }
    var startDateMs by remember { mutableStateOf<Long?>(null) }
    var endDateMs by remember { mutableStateOf<Long?>(null) }

    LaunchedEffect(taskId) {
        if (taskId == null) { botId = initialBotId; targetBotLoaded = true; loaded = true; return@LaunchedEffect }
        val existing = vm.get(taskId) ?: run { onBack(); return@LaunchedEffect }
        label = existing.label
        prompt = existing.prompt
        repeatMode = existing.repeatMode
        customDays = existing.customDays
        hour = existing.timeOfDayHour
        minute = existing.timeOfDayMinute
        enabled = existing.enabled
        permissionTier = existing.permissionTier
        createdAt = existing.createdAt
        lastFiredAt = existing.lastFiredAt
        lastResultPreview = existing.lastResultPreview
        lastResultSessionId = existing.lastResultSessionId
        runHistory = existing.runHistory
        botId = existing.botId ?: initialBotId
        startDateMs = existing.startDateMs
        endDateMs = existing.endDateMs
        when (val m = existing.targetMode) {
            ScheduledTargetMode.NewSession -> targetKind = TargetKind.NEW
            is ScheduledTargetMode.AppendToSession -> {
                targetKind = TargetKind.FOLLOW_UP
                targetSessionId = m.sessionId
            }
            is ScheduledTargetMode.RerunMessage -> {
                targetKind = TargetKind.RERUN
                targetSessionId = m.sessionId
                targetMessageId = m.messageId
            }
        }
        // Resolve display names for any pre-selected session / model. The
        // binding takes priority (it carries both group and entry shapes); we
        // only fall back to the legacy modelId-only path when no binding is
        // present on the row (tasks created before T-android-scheduled-task-
        // model-binding).
        when {
            existing.modelBinding != null -> {
                modelBinding = existing.modelBinding
                modelDisplay = vm.describeBinding(existing.modelBinding)
            }
            existing.modelId != null -> {
                val entry = vm.listModels().firstOrNull { it.modelId == existing.modelId }
                if (entry != null) {
                    modelBinding = """{"type":"entry","entryId":"${entry.entryId}"}"""
                    modelDisplay = entry.displayName
                }
            }
        }
        targetSessionId?.let { sid ->
            targetSessionTitle = vm.listSessions().firstOrNull { it.id == sid }?.title
            targetBotLoaded = false
            targetBotId = vm.sessionBotId(sid)
            if (existing.botId == null) botId = targetBotId
            targetBotLoaded = true
        }
        loaded = true
    }

    val runNowState by vm.runNowState.collectAsState()

    fun currentTask(): ScheduledTask = buildTask(
        id = taskId, label = label, prompt = prompt, hour = hour, minute = minute,
        repeatMode = repeatMode, customDays = customDays, enabled = enabled,
        createdAt = createdAt, lastFiredAt = lastFiredAt,
        lastResultPreview = lastResultPreview, lastResultSessionId = lastResultSessionId,
        targetKind = targetKind, targetSessionId = targetSessionId,
        targetMessageId = targetMessageId,
        runHistory = runHistory,
        modelBinding = modelBinding,
        botId = botId,
        permissionTier = permissionTier,
        modelEntryIdLookup = { eid ->
            vm.listModels().firstOrNull { it.entryId == eid }?.modelId
        },
        startDateMs = startDateMs, endDateMs = endDateMs,
    )

    // re-run replays the chosen message, so prompt isn't required there.
    val needsPrompt = targetKind != TargetKind.RERUN
    val targetOk = when (targetKind) {
        TargetKind.NEW -> true
        TargetKind.FOLLOW_UP -> targetSessionId != null
        TargetKind.RERUN -> targetSessionId != null && targetMessageId != null
    }
    val executorMatchesTarget = targetKind == TargetKind.NEW ||
        (targetBotLoaded && botId == targetBotId)
    val canSave = targetOk && executorMatchesTarget && (!needsPrompt || prompt.isNotBlank())
    val canRunNow = canSave && runNowState?.status != ScheduledTasksViewModel.RunStatus.RUNNING

    fun persistTask() {
        scope.launch {
            try {
                vm.upsertFromEditor(currentTask(), isNew, fullTierConfirmed)
                onBack()
            } catch (exception: Exception) {
                val message = exception.message.orEmpty()
                saveError = if (message.startsWith("Bot routine limit reached")) {
                    context.getString(
                        R.string.scheduled_task_bot_routine_limit,
                        ScheduledTask.MAX_ROUTINES_PER_BOT,
                    )
                } else message.ifBlank { context.getString(R.string.scheduled_task_save_failed) }
            }
        }
    }

    val requestSave: () -> Unit = {
        if (ScheduledTaskTierMutationPolicy.requiresFullConfirmation(permissionTier, fullTierConfirmed)) {
            saveAfterFullConfirmation = true
            showFullTierConfirmation = true
        } else {
            persistTask()
        }
    }

    Scaffold(
        topBar = {
            MinisTopBar(
                title = {
                    Text(
                        stringResource(
                            if (isNew) R.string.scheduled_task_new
                            else R.string.scheduled_task_edit,
                        ),
                        fontSize = 17.sp,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                    )
                },
                navigation = {
                    MinisTextButton(onClick = onBack) { Text(stringResource(R.string.cancel), fontSize = 17.sp) }
                },
                actions = {
                    MinisTextButton(onClick = requestSave, enabled = canSave) {
                        Text(stringResource(R.string.scheduled_task_save), fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
                    }
                },
            )
        },
    ) { padding ->
        if (!loaded) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
            return@Scaffold
        }
        EditFormBody(
            padding = padding,
            label = label, onLabelChange = { label = it },
            prompt = prompt, onPromptChange = { prompt = it },
            needsPrompt = needsPrompt,
            permissionTier = permissionTier,
            onPermissionTierChange = { requested ->
                if (requested == ScheduledTaskPermissionTier.FULL && permissionTier != requested) {
                    showFullTierConfirmation = true
                } else {
                    permissionTier = requested
                    if (requested != ScheduledTaskPermissionTier.FULL) fullTierConfirmed = false
                }
            },
            hour = hour, minute = minute, onTimeChange = { h, m -> hour = h; minute = m },
            repeatMode = repeatMode, onRepeatModeChange = { repeatMode = it },
            customDays = customDays, onCustomDaysChange = { customDays = it },
            targetKind = targetKind, onTargetKindChange = {
                targetKind = it
                if (it == TargetKind.NEW) {
                    targetSessionId = null
                    targetSessionTitle = null
                    targetBotId = null
                    targetBotLoaded = true
                }
                if (it != TargetKind.RERUN) { targetMessageId = null; targetMessagePreview = null }
            },
            targetSessionTitle = targetSessionTitle,
            onPickSession = { id, title ->
                targetSessionId = id; targetSessionTitle = title
                targetMessageId = null; targetMessagePreview = null
                targetBotLoaded = false
                scope.launch {
                    targetBotId = vm.sessionBotId(id)
                    botId = targetBotId
                    targetBotLoaded = true
                }
            },
            targetMessagePreview = targetMessagePreview,
            onPickMessage = { id, preview -> targetMessageId = id; targetMessagePreview = preview },
            targetSessionId = targetSessionId,
            bots = bots,
            botId = botId,
            targetBotId = targetBotId,
            targetBotLoaded = targetBotLoaded,
            onPickBot = { botId = it },
            modelBindingForPreselect = modelBinding,
            modelDisplay = modelDisplay,
            onPickModel = { binding, display -> modelBinding = binding; modelDisplay = display },
            startDateMs = startDateMs, onStartDateChange = { startDateMs = it },
            endDateMs = endDateMs, onEndDateChange = { endDateMs = it },
            vm = vm,
            isNew = isNew,
            canRunNow = canRunNow,
            canSave = canSave,
            onRunNow = { vm.runNow(currentTask()) },
            onSave = requestSave,
            onDelete = {
                if (taskId != null) scope.launch { vm.delete(taskId); onBack() }
            },
        )
    }

    saveError?.let { message ->
        MinisAlertDialog(
            onDismissRequest = { saveError = null },
            title = { Text(stringResource(R.string.scheduled_task_save)) },
            text = { Text(message) },
            confirmButton = {
                TextButton(onClick = { saveError = null }) { Text(stringResource(R.string.ok)) }
            },
        )
    }

    if (showFullTierConfirmation) {
        MinisAlertDialog(
            onDismissRequest = {
                showFullTierConfirmation = false
                saveAfterFullConfirmation = false
            },
            title = { Text(stringResource(R.string.scheduled_task_tier_full_confirmation_title)) },
            text = { Text(stringResource(R.string.scheduled_task_tier_full_confirmation_body)) },
            confirmButton = {
                TextButton(onClick = {
                    permissionTier = ScheduledTaskPermissionTier.FULL
                    fullTierConfirmed = true
                    showFullTierConfirmation = false
                    if (saveAfterFullConfirmation) {
                        saveAfterFullConfirmation = false
                        persistTask()
                    }
                }) { Text(stringResource(R.string.scheduled_task_tier_full_confirm)) }
            },
            dismissButton = {
                TextButton(onClick = {
                    showFullTierConfirmation = false
                    saveAfterFullConfirmation = false
                }) {
                    Text(stringResource(R.string.cancel))
                }
            },
        )
    }

    // [T-android-scheduled-tasks-full] Run-now feedback. RUNNING shows a
    // dismissable loading dialog; the runner returns quickly (async — it does
    // NOT wait for the agent loop), flipping to STARTED ("task started, open
    // the chat") or FAILED. Dismissing during RUNNING just hides the dialog;
    // the background run continues and posts its completion notification.
    val state = runNowState
    if (state != null) {
        when (state.status) {
            ScheduledTasksViewModel.RunStatus.RUNNING -> {
                MinisAlertDialog(
                    onDismissRequest = { vm.clearRunNowState() },
                    title = { Text(stringResource(R.string.scheduled_task_run_now_starting)) },
                    text = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            CircularProgressIndicator(modifier = Modifier.size(20.dp))
                            Spacer(Modifier.width(12.dp))
                            Text(stringResource(R.string.scheduled_task_run_now_starting_body))
                        }
                    },
                    confirmButton = {
                        TextButton(onClick = { vm.clearRunNowState() }) {
                            Text(stringResource(R.string.scheduled_task_run_now_dismiss))
                        }
                    },
                )
            }
            ScheduledTasksViewModel.RunStatus.STARTED -> {
                MinisAlertDialog(
                    onDismissRequest = { vm.clearRunNowState() },
                    title = { Text(stringResource(R.string.scheduled_task_run_now_started)) },
                    text = { Text(stringResource(R.string.scheduled_task_run_now_started_body)) },
                    confirmButton = {
                        val sid = state.sessionId
                        if (sid != null) {
                            TextButton(onClick = { vm.clearRunNowState(); onOpenSession(sid) }) {
                                Text(stringResource(R.string.scheduled_task_run_now_open_session))
                            }
                        } else {
                            TextButton(onClick = { vm.clearRunNowState() }) {
                                Text(stringResource(R.string.ok))
                            }
                        }
                    },
                    dismissButton = if (state.sessionId != null) {
                        { TextButton(onClick = { vm.clearRunNowState() }) {
                            Text(stringResource(R.string.scheduled_task_run_now_dismiss))
                        } }
                    } else null,
                )
            }
            ScheduledTasksViewModel.RunStatus.FAILED -> {
                MinisAlertDialog(
                    onDismissRequest = { vm.clearRunNowState() },
                    title = { Text(stringResource(R.string.scheduled_task_run_now_failed)) },
                    text = {
                        // The reason when we have one (no provider configured,
                        // target chat gone) instead of a generic failure line.
                        Text(state.message ?: stringResource(R.string.scheduled_task_run_now_failed_body))
                    },
                    confirmButton = {
                        TextButton(onClick = { vm.clearRunNowState() }) {
                            Text(stringResource(R.string.ok))
                        }
                    },
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun EditFormBody(
    padding: PaddingValues,
    label: String, onLabelChange: (String) -> Unit,
    prompt: String, onPromptChange: (String) -> Unit,
    needsPrompt: Boolean,
    permissionTier: ScheduledTaskPermissionTier,
    onPermissionTierChange: (ScheduledTaskPermissionTier) -> Unit,
    hour: Int, minute: Int, onTimeChange: (Int, Int) -> Unit,
    repeatMode: ScheduledRepeatMode, onRepeatModeChange: (ScheduledRepeatMode) -> Unit,
    customDays: Set<Int>, onCustomDaysChange: (Set<Int>) -> Unit,
    targetKind: TargetKind, onTargetKindChange: (TargetKind) -> Unit,
    targetSessionTitle: String?, onPickSession: (String, String) -> Unit,
    targetMessagePreview: String?, onPickMessage: (String, String) -> Unit,
    targetSessionId: String?,
    bots: List<com.openminis.app.data.db.BotEntity>,
    botId: String?,
    targetBotId: String?,
    targetBotLoaded: Boolean,
    onPickBot: (String?) -> Unit,
    modelBindingForPreselect: String?,
    modelDisplay: String?, onPickModel: (binding: String?, display: String?) -> Unit,
    startDateMs: Long?, onStartDateChange: (Long?) -> Unit,
    endDateMs: Long?, onEndDateChange: (Long?) -> Unit,
    vm: ScheduledTasksViewModel,
    isNew: Boolean,
    canRunNow: Boolean,
    canSave: Boolean,
    onRunNow: () -> Unit,
    onSave: () -> Unit,
    onDelete: () -> Unit,
) {
    // [T-android-scheduled-tasks-full] Compact time entry (TimeInput keyboard,
    // not the full clock dial — the user asked for the compact mode).
    val timeState = rememberTimePickerState(initialHour = hour, initialMinute = minute, is24Hour = true)
    LaunchedEffect(timeState.hour, timeState.minute) { onTimeChange(timeState.hour, timeState.minute) }

    var showSessionPicker by remember { mutableStateOf(false) }
    var showMessagePicker by remember { mutableStateOf(false) }
    var showModelPicker by remember { mutableStateOf(false) }
    var showBotPicker by remember { mutableStateOf(false) }
    var showStartPicker by remember { mutableStateOf(false) }
    var showEndPicker by remember { mutableStateOf(false) }
    val screenContext = LocalContext.current
    val virtualScreenAvailable = remember(screenContext) {
        com.openminis.app.tools.android.vscreen.VirtualScreenClientProvider.get(screenContext).isEnabled()
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(padding)
            .verticalScroll(rememberScrollState())
            .padding(bottom = 24.dp),
    ) {
        SettingsSection(header = stringResource(R.string.scheduled_task_field_label)) {
            SettingsCardBlock {
                CardTextField(value = label, onValueChange = onLabelChange, singleLine = true)
            }
        }

        // ── Target mode ──
        SettingsSection(header = stringResource(R.string.scheduled_task_field_target)) {
            val targetOpts = listOf(
                TargetKind.NEW to R.string.scheduled_task_target_new,
                TargetKind.FOLLOW_UP to R.string.scheduled_task_target_followup,
                TargetKind.RERUN to R.string.scheduled_task_target_rerun,
            )
            SettingsCardBlock {
                SettingsSegmented(
                    options = targetOpts.map { stringResource(it.second) },
                    selectedIndex = targetOpts.indexOfFirst { it.first == targetKind }.coerceAtLeast(0),
                    onSelect = { onTargetKindChange(targetOpts[it].first) },
                )
            }
            if (targetKind != TargetKind.NEW) {
                PickerRow(
                    title = stringResource(R.string.scheduled_task_field_session),
                    value = targetSessionTitle ?: stringResource(R.string.scheduled_task_pick_session),
                    onClick = { showSessionPicker = true },
                    showDivider = targetKind == TargetKind.RERUN,
                )
            }
            if (targetKind == TargetKind.RERUN) {
                PickerRow(
                    title = stringResource(R.string.scheduled_task_field_message),
                    value = targetMessagePreview ?: stringResource(R.string.scheduled_task_pick_message),
                    enabled = targetSessionId != null,
                    onClick = { if (targetSessionId != null) showMessagePicker = true },
                    showDivider = false,
                )
            }
        }

        // ── Time, repeat, active window ──
        SettingsSection(header = stringResource(R.string.scheduled_task_field_time)) {
            SettingsCardBlock {
                TimeInput(state = timeState)
                Spacer(Modifier.height(8.dp))
                val options = listOf(
                    ScheduledRepeatMode.ONCE to R.string.scheduled_task_repeat_once,
                    ScheduledRepeatMode.DAILY to R.string.scheduled_task_repeat_daily,
                    ScheduledRepeatMode.WEEKDAYS to R.string.scheduled_task_repeat_weekdays,
                    ScheduledRepeatMode.CUSTOM to R.string.scheduled_task_repeat_custom,
                )
                SettingsSegmented(
                    options = options.map { stringResource(it.second) },
                    selectedIndex = options.indexOfFirst { it.first == repeatMode }.coerceAtLeast(0),
                    onSelect = { onRepeatModeChange(options[it].first) },
                )
                if (repeatMode == ScheduledRepeatMode.CUSTOM) {
                    Spacer(Modifier.height(12.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
                        val names = java.text.DateFormatSymbols.getInstance().shortWeekdays
                        val days = listOf(
                            Calendar.MONDAY, Calendar.TUESDAY, Calendar.WEDNESDAY, Calendar.THURSDAY,
                            Calendar.FRIDAY, Calendar.SATURDAY, Calendar.SUNDAY,
                        )
                        days.forEach { dow ->
                            FilterChip(
                                selected = dow in customDays,
                                onClick = {
                                    val next = customDays.toMutableSet().apply {
                                        if (dow in this) remove(dow) else add(dow)
                                    }
                                    onCustomDaysChange(next)
                                },
                                label = { Text(names.getOrNull(dow).orEmpty(), fontSize = 12.sp) },
                                modifier = Modifier.weight(1f),
                            )
                        }
                    }
                }
            }
            PickerRow(
                title = stringResource(R.string.scheduled_task_field_start_date),
                value = startDateMs?.let { dateLabel(it) } ?: stringResource(R.string.scheduled_task_date_any),
                onClick = { showStartPicker = true },
                onClear = if (startDateMs != null) ({ onStartDateChange(null) }) else null,
            )
            PickerRow(
                title = stringResource(R.string.scheduled_task_field_end_date),
                value = endDateMs?.let { dateLabel(it) } ?: stringResource(R.string.scheduled_task_date_any),
                onClick = { showEndPicker = true },
                onClear = if (endDateMs != null) ({ onEndDateChange(null) }) else null,
                showDivider = false,
            )
        }

        // ── Prompt ──
        SettingsSection(
            header = stringResource(R.string.scheduled_task_field_prompt),
            footer = if (needsPrompt) null else stringResource(R.string.scheduled_task_rerun_note),
        ) {
            if (needsPrompt) {
                SettingsCardBlock {
                    CardTextField(
                        value = prompt,
                        onValueChange = onPromptChange,
                        placeholder = stringResource(R.string.scheduled_task_field_prompt_hint),
                        singleLine = false,
                        minLines = 4,
                    )
                }
            }
        }

        // ── Who runs it, and with which model ──
        val selectedBot = bots.firstOrNull { it.id == botId }
        val executorName = selectedBot?.name ?: botId
        val executorValue = if (targetKind == TargetKind.NEW) {
            if (botId == null) stringResource(R.string.scheduled_task_executor_regular)
            else if (selectedBot?.enabled == false) {
                "${executorName ?: botId} · ${stringResource(R.string.scheduled_task_bot_disabled)}"
            } else executorName ?: botId
        } else {
            val targetBot = bots.firstOrNull { it.id == targetBotId }
            if (!targetBotLoaded) stringResource(R.string.scheduled_task_executor_loading)
            else if (targetBotId == null) stringResource(R.string.scheduled_task_executor_regular)
            else targetBot?.name ?: targetBotId
        }
        SettingsSection(header = stringResource(R.string.scheduled_task_executor)) {
            PickerRow(
                title = stringResource(R.string.scheduled_task_executor),
                value = executorValue,
                enabled = targetKind == TargetKind.NEW,
                onClick = { showBotPicker = true },
            )
            PickerRow(
                title = stringResource(R.string.scheduled_task_field_model),
                value = modelDisplay ?: stringResource(
                    if (botId != null) R.string.scheduled_task_model_bot_default
                    else R.string.scheduled_task_model_default,
                ),
                onClick = { showModelPicker = true },
                onClear = if (modelDisplay != null) ({ onPickModel(null, null) }) else null,
                showDivider = false,
            )
        }

        // ── Routine privilege tier ──
        SettingsSection(
            header = stringResource(R.string.scheduled_task_permission_tier),
            footer = stringResource(
                if (permissionTier == ScheduledTaskPermissionTier.READ_ONLY) {
                    R.string.scheduled_task_tier_read_only_detail
                } else {
                    R.string.scheduled_task_tier_full_detail
                },
            ),
        ) {
            val tierOptions = listOf(
                ScheduledTaskPermissionTier.READ_ONLY to R.string.scheduled_task_tier_read_only,
                ScheduledTaskPermissionTier.FULL to R.string.scheduled_task_tier_full,
            )
            SettingsCardBlock {
                SettingsSegmented(
                    options = tierOptions.map { stringResource(it.second) },
                    selectedIndex = tierOptions.indexOfFirst { it.first == permissionTier }.coerceAtLeast(0),
                    onSelect = { onPermissionTierChange(tierOptions[it].first) },
                )
            }
        }

        Text(
            text = stringResource(R.string.scheduled_vscreen_unattended_hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
        )
        if (!virtualScreenAvailable) {
            Text(
                text = stringResource(R.string.scheduled_vscreen_unavailable_warning),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(horizontal = 20.dp),
            )
        }

        Column(modifier = Modifier.fillMaxWidth().padding(top = 8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            MinisTextButton(onClick = onRunNow, enabled = canRunNow) {
                Text(stringResource(R.string.scheduled_task_run_now), fontSize = 17.sp)
            }
            if (!isNew) {
                MinisTextButton(onClick = onDelete) {
                    Text(stringResource(R.string.scheduled_task_delete), fontSize = 17.sp, color = MaterialTheme.colorScheme.error)
                }
            }
        }
        Text(
            stringResource(R.string.scheduled_task_target_new_session_note),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp),
        )
    }

    if (showSessionPicker) {
        SessionPickerDialog(vm = vm, onDismiss = { showSessionPicker = false }) { id, title ->
            onPickSession(id, title); showSessionPicker = false
        }
    }
    if (showBotPicker) {
        MinisAlertDialog(
            onDismissRequest = { showBotPicker = false },
            title = { Text(stringResource(R.string.scheduled_task_executor)) },
            text = {
                LazyColumn(modifier = Modifier.heightIn(max = 360.dp)) {
                    item(key = "regular") {
                        TextButton(onClick = { onPickBot(null); showBotPicker = false }) {
                            Text(stringResource(R.string.scheduled_task_executor_regular))
                        }
                    }
                    items(bots.filter { it.enabled }, key = { it.id }) { bot ->
                        TextButton(onClick = { onPickBot(bot.id); showBotPicker = false }) {
                            Text(bot.name)
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showBotPicker = false }) { Text(stringResource(R.string.cancel)) }
            },
        )
    }
    if (showMessagePicker && targetSessionId != null) {
        MessagePickerDialog(vm = vm, sessionId = targetSessionId, onDismiss = { showMessagePicker = false }) { id, preview ->
            onPickMessage(id, preview); showMessagePicker = false
        }
    }
    // [T-android-scheduled-lateinit-crash-156] `vm.providerRepository` is null
    // when the Application is half-built (safe-mode / aborted onCreate); the
    // editor is reachable in exactly that state via a scheduled-task
    // notification tap. Gating the whole block here keeps the picker from
    // reading an unassigned lateinit — the row's label and the "use default
    // model" behaviour are unaffected.
    val providerRepo = vm.providerRepository
    if (showModelPicker && providerRepo != null) {
        // A null binding follows the Main slot; explicit bindings are entry-only.
        val cfg by providerRepo.config.collectAsState()
        val entryById: (String) -> com.openminis.app.data.model.ModelEntry? = { id ->
            cfg.modelEntries.firstOrNull { it.id == id }
        }
        val parsed = modelBindingForPreselect?.let { json ->
            runCatching { org.json.JSONObject(json) }.getOrNull()
        }
        val preselectEntryId = when (parsed?.optString("type")) {
            "entry" -> parsed.optString("entryId").takeIf { it.isNotEmpty() }
            else -> cfg.slots.main.firstOrNull()
        }
        com.openminis.app.ui.chat.ModelPickerSheet(
            activeEntryId = preselectEntryId,
            config = cfg,
            providerRepository = providerRepo,
            onSelectEntry = { entryId ->
                val entry = entryById(entryId)
                val name = entry?.model?.displayName ?: "Model"
                val json = """{"type":"entry","entryId":"$entryId"}"""
                onPickModel(json, name)
                showModelPicker = false
            },
            onDismiss = { showModelPicker = false },
        )
    }
    if (showStartPicker) {
        DateDialog(initialMs = startDateMs, onDismiss = { showStartPicker = false }) {
            onStartDateChange(it); showStartPicker = false
        }
    }
    if (showEndPicker) {
        DateDialog(initialMs = endDateMs, onDismiss = { showEndPicker = false }) {
            onEndDateChange(it); showEndPicker = false
        }
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(text, fontWeight = FontWeight.Medium, color = MaterialTheme.colorScheme.onSurface)
}

/** A picker as a settings row: title on the left, the chosen value (and a clear "x") on the right. */
@Composable
private fun PickerRow(
    title: String,
    value: String,
    enabled: Boolean = true,
    onClick: () -> Unit,
    onClear: (() -> Unit)? = null,
    showDivider: Boolean = true,
) {
    SettingsRow(
        title = title,
        onClick = if (enabled) onClick else null,
        showDivider = showDivider,
        titleColor = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
        trailing = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    value,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                    modifier = Modifier.widthIn(max = 180.dp),
                )
                if (onClear != null) {
                    Text(
                        "✕",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.clickable(onClick = onClear).padding(start = 10.dp, end = 4.dp),
                    )
                }
            }
        },
    )
}

/** A borderless text field for use inside a settings card. */
@Composable
private fun CardTextField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String? = null,
    singleLine: Boolean = true,
    minLines: Int = 1,
) {
    androidx.compose.material3.TextField(
        value = value,
        onValueChange = onValueChange,
        placeholder = placeholder?.let { { Text(it) } },
        singleLine = singleLine,
        minLines = minLines,
        colors = androidx.compose.material3.TextFieldDefaults.colors(
            focusedContainerColor = androidx.compose.ui.graphics.Color.Transparent,
            unfocusedContainerColor = androidx.compose.ui.graphics.Color.Transparent,
            disabledContainerColor = androidx.compose.ui.graphics.Color.Transparent,
            focusedIndicatorColor = androidx.compose.ui.graphics.Color.Transparent,
            unfocusedIndicatorColor = androidx.compose.ui.graphics.Color.Transparent,
        ),
        modifier = Modifier.fillMaxWidth(),
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SessionPickerDialog(
    vm: ScheduledTasksViewModel,
    onDismiss: () -> Unit,
    onPick: (id: String, title: String) -> Unit,
) {
    var sessions by remember { mutableStateOf<List<ScheduledTasksViewModel.SessionOption>?>(null) }
    LaunchedEffect(Unit) { sessions = vm.listSessions() }
    MinisAlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.scheduled_task_pick_session)) },
        text = {
            val list = sessions
            if (list == null) {
                Box(Modifier.fillMaxWidth().height(120.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            } else if (list.isEmpty()) {
                Text(stringResource(R.string.scheduled_task_no_sessions))
            } else {
                LazyColumn(modifier = Modifier.heightIn(max = 360.dp)) {
                    items(list, key = { it.id }) { s ->
                        Column(
                            Modifier.fillMaxWidth().clickable { onPick(s.id, s.title) }
                                .padding(vertical = 10.dp),
                        ) {
                            Text(s.title, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MessagePickerDialog(
    vm: ScheduledTasksViewModel,
    sessionId: String,
    onDismiss: () -> Unit,
    onPick: (id: String, preview: String) -> Unit,
) {
    var messages by remember { mutableStateOf<List<ScheduledTasksViewModel.MessageOption>?>(null) }
    LaunchedEffect(sessionId) { messages = vm.listUserMessages(sessionId) }
    MinisAlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.scheduled_task_pick_message)) },
        text = {
            val list = messages
            if (list == null) {
                Box(Modifier.fillMaxWidth().height(120.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            } else if (list.isEmpty()) {
                Text(stringResource(R.string.scheduled_task_no_messages_in_session))
            } else {
                LazyColumn(modifier = Modifier.heightIn(max = 360.dp)) {
                    items(list, key = { it.id }) { m ->
                        Column(
                            Modifier.fillMaxWidth().clickable { onPick(m.id, m.preview) }
                                .padding(vertical = 10.dp),
                        ) {
                            Text(m.preview, maxLines = 2, overflow = TextOverflow.Ellipsis, fontSize = 14.sp)
                        }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DateDialog(initialMs: Long?, onDismiss: () -> Unit, onPick: (Long) -> Unit) {
    val state = rememberDatePickerState(initialSelectedDateMillis = initialMs)
    DatePickerDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = {
                state.selectedDateMillis?.let { onPick(startOfLocalDay(it)) }
            }) { Text(stringResource(R.string.ok)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    ) {
        DatePicker(state = state)
    }
}

/** DatePicker hands back a UTC-midnight ms; convert to local start-of-day. */
private fun startOfLocalDay(utcMidnightMs: Long): Long {
    // The picker's value is UTC midnight of the picked calendar day. Re-anchor
    // to the local time zone's start-of-day so the active-window comparison in
    // ScheduledTask.nextTriggerMs (which works in local time) matches.
    val utc = Calendar.getInstance(java.util.TimeZone.getTimeZone("UTC")).apply {
        timeInMillis = utcMidnightMs
    }
    return Calendar.getInstance().apply {
        clear()
        set(utc.get(Calendar.YEAR), utc.get(Calendar.MONTH), utc.get(Calendar.DAY_OF_MONTH), 0, 0, 0)
    }.timeInMillis
}

private fun dateLabel(ms: Long): String =
    SimpleDateFormat("MMM d", Locale.getDefault()).format(Date(ms))

@Suppress("LongParameterList")
private fun buildTask(
    id: String?,
    label: String,
    prompt: String,
    hour: Int,
    minute: Int,
    repeatMode: ScheduledRepeatMode,
    customDays: Set<Int>,
    enabled: Boolean,
    createdAt: Long,
    lastFiredAt: Long?,
    lastResultPreview: String?,
    lastResultSessionId: String?,
    runHistory: List<com.openminis.app.scheduled.ScheduledRun>,
    targetKind: TargetKind,
    targetSessionId: String?,
    targetMessageId: String?,
    modelBinding: String?,
    botId: String?,
    permissionTier: ScheduledTaskPermissionTier,
    modelEntryIdLookup: (String) -> String?,
    startDateMs: Long?,
    endDateMs: Long?,
): ScheduledTask {
    val targetMode = when (targetKind) {
        TargetKind.NEW -> ScheduledTargetMode.NewSession
        TargetKind.FOLLOW_UP -> targetSessionId?.let { ScheduledTargetMode.AppendToSession(it) }
            ?: ScheduledTargetMode.NewSession
        TargetKind.RERUN ->
            if (targetSessionId != null && targetMessageId != null)
                ScheduledTargetMode.RerunMessage(targetSessionId, targetMessageId)
            else ScheduledTargetMode.NewSession
    }
    // For backcompat: if the binding pins to a specific entry, also fill in
    // legacy modelId so older code paths (and any external readers) still
    // resolve the same concrete model. Group bindings leave modelId null —
    // they resolve to a member entry at run time.
    val derivedModelId: String? = modelBinding?.let { json ->
        runCatching {
            val o = org.json.JSONObject(json)
            if (o.optString("type") == "entry") {
                o.optString("entryId").takeIf { it.isNotEmpty() }?.let(modelEntryIdLookup)
            } else null
        }.getOrNull()
    }
    return ScheduledTask(
        id = id ?: java.util.UUID.randomUUID().toString(),
        label = label.trim(),
        timeOfDayHour = hour,
        timeOfDayMinute = minute,
        repeatMode = repeatMode,
        customDays = if (repeatMode == ScheduledRepeatMode.CUSTOM) customDays else emptySet(),
        prompt = prompt.trim(),
        targetMode = targetMode,
        modelId = derivedModelId,
        modelBinding = modelBinding,
        botId = botId,
        permissionTier = permissionTier,
        enabled = enabled,
        createdAt = createdAt,
        startDateMs = startDateMs,
        endDateMs = endDateMs,
        lastFiredAt = lastFiredAt,
        lastResultPreview = lastResultPreview,
        lastResultSessionId = lastResultSessionId,
        runHistory = runHistory,
    )
}
