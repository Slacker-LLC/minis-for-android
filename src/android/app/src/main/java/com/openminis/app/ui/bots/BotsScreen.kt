package com.openminis.app.ui.bots

import com.openminis.app.ui.settings.SettingsTextArea
import com.openminis.app.ui.settings.SettingsInlineTextRow
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.PlayCircle
import androidx.compose.material.icons.outlined.PauseCircle
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.activity.compose.BackHandler
import com.openminis.app.ui.settings.MinisSwitch
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Chat
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.outlined.Add
import androidx.compose.ui.unit.sp
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.outlined.AccountTree
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.automirrored.outlined.Assignment
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.Group
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.openminis.app.MinisApp
import com.openminis.app.R
import com.openminis.app.data.db.BotDelegationEntity
import com.openminis.app.data.db.BotEntity
import com.openminis.app.data.db.BotTaskEntity
import com.openminis.app.data.db.ChatSessionEntity
import com.openminis.app.data.repository.BotRepository
import com.openminis.app.data.repository.ChatRepository
import com.openminis.app.data.repository.ProviderRepository
import com.openminis.app.scheduled.ScheduledTask
import com.openminis.app.scheduled.ScheduledTaskManager
import com.openminis.app.service.SessionConcurrencyManager
import com.openminis.app.tools.BotWakePolicy
import com.openminis.app.ui.chat.ModelPickerSheet
import com.openminis.app.ui.components.MinisAlertDialog
import com.openminis.app.ui.components.MinisButton
import com.openminis.app.ui.components.MinisMenu
import com.openminis.app.ui.components.MinisMenuDivider
import com.openminis.app.ui.components.MinisTextButton
import com.openminis.app.ui.components.SectionTextField
import com.openminis.app.ui.settings.SettingsRow
import com.openminis.app.ui.settings.SettingsScaffold
import com.openminis.app.ui.settings.SettingsSection
import com.openminis.app.ui.theme.ChatColors
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.map
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun BotsScreen(
    botRepository: BotRepository,
    chatRepository: ChatRepository,
    providerRepository: ProviderRepository,
    onBack: () -> Unit,
    onOpenSession: (String) -> Unit,
    initialBotId: String? = null,
    initialEditing: Boolean = false,
    initialProgress: Boolean = false,
    onMemberDetails: ((String) -> Unit)? = null,
    onAddMember: (() -> Unit)? = null,
    onOpenProgress: (() -> Unit)? = null,
    onOpenRoutineEditor: (taskId: String?, botId: String?) -> Unit = { _, _ -> },
) {
    var showVirtualScreenViewer by androidx.compose.runtime.saveable.rememberSaveable { androidx.compose.runtime.mutableStateOf(false) }
    if (showVirtualScreenViewer) {
        com.openminis.app.ui.settings.VirtualScreenViewerDialog(onDismiss = { showVirtualScreenViewer = false })
    }
    val context = LocalContext.current
    val delegationRepository = remember(context) {
        (context.applicationContext as MinisApp).botDelegationRepository
    }
    val taskRepository = remember(context) {
        (context.applicationContext as MinisApp).botTaskRepository
    }
    val scheduledTaskManager = remember(context) { ScheduledTaskManager(context.applicationContext) }
    val scheduledTasks by remember(scheduledTaskManager) {
        scheduledTaskManager.store().observe()
    }.collectAsState(initial = emptyList())
    val loadedBots by remember(botRepository) {
        botRepository.observeBots().map<List<BotEntity>, List<BotEntity>?> { it }
    }.collectAsState(initial = null)
    val bots = loadedBots.orEmpty()
    val sessions by remember(chatRepository) { chatRepository.observeSessions() }.collectAsState(initial = emptyList())
    val delegations by remember(delegationRepository) { delegationRepository.observeRecent() }.collectAsState(initial = emptyList())
    val rootTasks by remember(taskRepository) { taskRepository.observeRecent() }.collectAsState(initial = emptyList())
    val running by SessionConcurrencyManager.runningSessions.collectAsState()
    val suspended by SessionConcurrencyManager.suspendedSessions.collectAsState()
    val config by providerRepository.config.collectAsState()
    val scope = rememberCoroutineScope()
    var selectedBotId by rememberSaveable(initialBotId) { mutableStateOf(initialBotId) }
    var selectedTaskId by rememberSaveable { mutableStateOf<String?>(null) }
    var showProgress by rememberSaveable { mutableStateOf(initialProgress) }
    var editing by rememberSaveable { mutableStateOf(initialEditing) }
    var editorBotId by rememberSaveable { mutableStateOf<String?>(null) }
    var openingId by remember { mutableStateOf<String?>(null) }
    var saving by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var deleting by remember { mutableStateOf<BotEntity?>(null) }
    val selectedBot = bots.firstOrNull { it.id == selectedBotId }
    val selectedTask by remember(selectedTaskId, delegationRepository) {
        delegationRepository.observe(selectedTaskId.orEmpty())
    }.collectAsState(initial = null)
    val failureText = stringResource(R.string.bots_operation_failed)
    val missingSessionText = stringResource(R.string.bots_session_missing)
    val taskNoExecutionText = stringResource(R.string.bots_task_no_execution)

    fun goBack() {
        if (saving) return
        when {
            editing -> if (initialEditing) onBack() else { editing = false }
            selectedTaskId != null -> selectedTaskId = null
            showProgress -> if (initialProgress) onBack() else { showProgress = false }
            selectedBotId != null && initialBotId == null && !initialEditing -> selectedBotId = null
            else -> onBack()
        }
    }
    BackHandler(enabled = editing || selectedTaskId != null || showProgress || (selectedBotId != null && initialBotId == null)) { goBack() }

    fun openConversation(bot: BotEntity, newTopic: Boolean = false) {
        if (openingId != null) return
        openingId = bot.id
        scope.launch {
            try {
                val session = botRepository.openConversation(bot.id, chatRepository, providerRepository, newTopic)
                onOpenSession(session.id)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (exception: Exception) {
                error = exception.message ?: failureText
            } finally {
                openingId = null
            }
        }
    }
    fun openSession(id: String) {
        scope.launch {
            if (chatRepository.getSession(id) != null) onOpenSession(id)
            else error = missingSessionText
        }
    }

    fun addMember() {
        if (onAddMember != null) onAddMember()
        else { editorBotId = null; editing = true }
    }

    if (loadedBots == null) {
        SettingsScaffold(title = stringResource(R.string.bots_team), onBack = ::goBack) {}
    } else if (editing) {
        BotEditorScreen(
            bot = bots.firstOrNull { it.id == editorBotId },
            providerRepository = providerRepository,
            saving = saving,
            onBack = ::goBack,
            onSave = { name, prompt, binding ->
                if (saving) return@BotEditorScreen
                saving = true
                val savingBotId = editorBotId
                scope.launch {
                    try {
                        if (savingBotId == null) {
                            selectedBotId = botRepository.createBot(name, prompt, binding).id
                        } else {
                            check(botRepository.updateBot(savingBotId, name, prompt, binding)) { failureText }
                        }
                        editing = false
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (exception: Exception) {
                        error = exception.message ?: failureText
                    } finally {
                        saving = false
                    }
                }
            },
        )
    } else if (selectedTaskId != null) {
        selectedTask?.let { DelegationDetail(it, bots, onBack = ::goBack, onOpenSession = ::openSession) }
            ?: SettingsScaffold(title = stringResource(R.string.bots_progress), onBack = ::goBack) {}
    } else if (selectedBot != null && !showProgress) {
        val recent = sessions.filter { it.botId == selectedBot.id }
        BotDetails(
            bot = selectedBot,
            modelName = selectedBot.modelBinding?.let { binding ->
                val entryId = (com.openminis.app.data.model.ModelBinding.parse(binding)
                    as? com.openminis.app.data.model.ModelBinding.Entry)?.entryId
                config.modelEntries.firstOrNull { it.id == entryId }?.model?.displayName
            },
            sessions = recent,
            routines = scheduledTasks.filter { it.botId == selectedBot.id },
            work = delegations.filter { it.sourceBotId == selectedBot.id || it.targetBotId == selectedBot.id },
            bots = bots,
            opening = openingId != null,
            onBack = ::goBack,
            onOpen = { openConversation(selectedBot) },
            onNewTopic = { openConversation(selectedBot, newTopic = true) },
            onEdit = { editorBotId = selectedBot.id; editing = true },
            onToggle = {
                scope.launch {
                    try {
                        val enabling = !selectedBot.enabled
                        check(botRepository.setBotEnabled(selectedBot.id, enabling)) { failureText }
                        if (enabling) scheduledTaskManager.rescheduleBotTasks(selectedBot.id)
                    } catch (cancelled: CancellationException) { throw cancelled }
                    catch (exception: Exception) { error = exception.message ?: failureText }
                }
            },
            onDelete = { deleting = selectedBot },
            onOpenSession = ::openSession,
            onOpenWork = { selectedTaskId = it },
            onOpenRoutineEditor = onOpenRoutineEditor,
            onToggleRoutine = { taskId, enabled ->
                scope.launch(Dispatchers.IO) { scheduledTaskManager.setEnabled(taskId, enabled) }
            },
        )
    } else if (selectedBotId != null && !showProgress) {
        SettingsScaffold(title = stringResource(R.string.bots_details), onBack = ::goBack, scrollable = false) {
            BotEmptyState(R.string.bots_member_missing, R.string.bots_member_missing_body)
        }
    } else {
        SettingsScaffold(
            title = stringResource(if (showProgress) R.string.bots_progress else R.string.bots_team),
            onBack = ::goBack,
            scrollable = false,
            largeTitle = !showProgress,
            actions = {
                if (!showProgress) {
                    // Watch what a member is doing on the virtual screen (only when it is switched on).
                    if (com.openminis.app.tools.android.vscreen.VirtualScreenClientProvider
                            .get(androidx.compose.ui.platform.LocalContext.current).isEnabled()
                    ) {
                        IconButton(onClick = { showVirtualScreenViewer = true }) {
                            Icon(Icons.Outlined.Visibility, stringResource(R.string.vscreen_viewer_menu))
                        }
                    }
                    IconButton(
                        onClick = ::addMember,
                        enabled = bots.size < BotRepository.MAX_BOTS,
                    ) { Icon(Icons.Outlined.Add, stringResource(R.string.bots_add)) }
                }
            },
        ) {
            if (showProgress) {
                if (delegations.isEmpty() && rootTasks.isEmpty()) {
                    BotEmptyState(R.string.bots_progress_empty, R.string.bots_progress_empty_body)
                } else {
                    val needsAttention = rootTasks.firstOrNull {
                        it.status == BotTaskEntity.STATUS_NEEDS_USER
                    }
                    LazyColumn(contentPadding = PaddingValues(16.dp)) {
                        needsAttention?.let { task ->
                            item(key = "needs-user-${task.id}") {
                                BotNeedsDecisionCard(task) {
                                    openSession(task.currentOwnerSessionId ?: task.originSessionId)
                                }
                            }
                        }
                        if (rootTasks.isNotEmpty()) {
                            item {
                                Text(
                                    stringResource(R.string.bots_root_tasks),
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.SemiBold,
                                    modifier = Modifier.padding(bottom = 4.dp),
                                )
                            }
                            items(rootTasks, key = { it.id }) { rootTask ->
                                val delegation = delegations.firstOrNull { it.rootTaskId == rootTask.id }
                                BotTaskRow(
                                    task = rootTask,
                                    status = delegationStatusText(rootTask),
                                    onClick = {
                                        delegation?.let { selectedTaskId = it.id }
                                            ?: run { error = taskNoExecutionText }
                                    },
                                    onPause = {
                                        scope.launch {
                                            if (!taskRepository.pause(rootTask.id)) error = failureText
                                        }
                                    },
                                    onCancel = {
                                        scope.launch {
                                            val cancelled = com.openminis.app.tools.BotDelegationCoordinator.current()
                                                ?.cancelRootTask(rootTask.id)
                                                ?: taskRepository.cancel(rootTask.id)
                                            if (!cancelled) error = failureText
                                        }
                                    },
                                )
                            }
                            if (delegations.isNotEmpty()) {
                                item {
                                    Text(
                                        stringResource(R.string.bots_execution_records),
                                        style = MaterialTheme.typography.titleSmall,
                                        fontWeight = FontWeight.SemiBold,
                                        modifier = Modifier.padding(top = 20.dp, bottom = 4.dp),
                                    )
                                }
                            }
                        }
                        items(delegations, key = { it.id }) { delegation ->
                            DelegationRow(delegation, bots) { selectedTaskId = delegation.id }
                        }
                    }
                }
            } else if (bots.isEmpty()) {
                BotEmptyState(R.string.bots_empty_title, R.string.bots_empty_body) {
                    MinisButton(onClick = ::addMember) {
                        Text(stringResource(R.string.bots_add))
                    }
                }
            } else {
                LazyColumn(contentPadding = PaddingValues(bottom = 24.dp)) {
                    item(key = "progress-entry") {
                        val running = delegations.count {
                            it.status == BotDelegationEntity.STATUS_RUNNING || it.status in setOf(BotDelegationEntity.STATUS_QUEUED, BotDelegationEntity.STATUS_WAITING_TARGET)
                        }
                        SettingsSection {
                            SettingsRow(
                                icon = Icons.Outlined.AccountTree,
                                iconColor = Color(0xFF0A84FF),
                                title = stringResource(R.string.bots_progress),
                                subtitle = if (running > 0) stringResource(R.string.bots_progress_running, running)
                                else stringResource(R.string.bots_progress_none),
                                onClick = { if (onOpenProgress != null) onOpenProgress() else { showProgress = true } },
                                showDivider = false,
                            )
                        }
                    }
                    item(key = "members") {
                        SettingsSection(header = stringResource(R.string.bots_members_header)) {
                            bots.forEachIndexed { index, bot ->
                                val botSessions = sessions.filter { it.botId == bot.id }
                                val executing = delegations.any { it.targetBotId == bot.id && it.status == BotDelegationEntity.STATUS_RUNNING }
                                val waiting = delegations.any { it.targetBotId == bot.id && it.status in setOf(BotDelegationEntity.STATUS_QUEUED, BotDelegationEntity.STATUS_WAITING_TARGET) }
                                val status = when {
                                    executing || botSessions.any { it.id in running } -> R.string.bots_working
                                    !bot.enabled -> R.string.bots_disabled
                                    waiting || botSessions.any { it.id in suspended } -> R.string.bots_waiting
                                    else -> R.string.bots_available
                                }
                                val statusColor = when {
                                    executing || botSessions.any { it.id in running } -> MaterialTheme.colorScheme.primary
                                    !bot.enabled -> ChatColors.tertiaryText
                                    waiting || botSessions.any { it.id in suspended } -> ChatColors.warn
                                    else -> ChatColors.ok
                                }
                                BotMemberRow(
                                    bot = bot,
                                    status = stringResource(status),
                                    statusColor = statusColor,
                                    preview = botSessions.firstOrNull()?.lastMessage ?: bot.systemPrompt?.lineSequence()?.firstOrNull { it.isNotBlank() },
                                    showDivider = index != bots.lastIndex,
                                    onOpen = { if (onMemberDetails != null) onMemberDetails(bot.id) else { selectedBotId = bot.id } },
                                )
                            }
                        }
                    }
                    if (bots.size >= BotRepository.MAX_BOTS) {
                        item {
                            Text(stringResource(R.string.bots_limit, BotRepository.MAX_BOTS),
                                color = ChatColors.secondaryText, style = MaterialTheme.typography.bodySmall,
                                modifier = Modifier.padding(vertical = 16.dp))
                        }
                    }
                }
            }
        }
    }
    deleting?.let { bot ->
        MinisAlertDialog(
            onDismissRequest = { deleting = null },
            title = stringResource(R.string.bots_delete_title, bot.name),
            text = stringResource(R.string.bots_delete_body),
            confirmText = stringResource(R.string.bots_delete),
            isDestructive = true,
            onConfirm = {
                deleting = null
                scope.launch {
                    try {
                        check(botRepository.deleteBot(bot.id)) { failureText }
                        scheduledTaskManager.deleteAllForBot(bot.id)
                        selectedBotId = null
                        if (initialBotId != null) onBack()
                    } catch (cancelled: CancellationException) { throw cancelled }
                    catch (exception: Exception) { error = exception.message ?: failureText }
                }
            },
        )
    }
    error?.let { message ->
        MinisAlertDialog(onDismissRequest = { error = null }, title = failureText,
            text = message, confirmText = stringResource(android.R.string.ok), onConfirm = { error = null })
    }
}

/** Each member gets one of a few fixed tints, chosen from its id so it never changes. */
internal fun memberTint(botId: String): Color {
    val palette = listOf(Color(0xFF0A84FF), Color(0xFF34C759), Color(0xFFFF9500), Color(0xFFAF52DE), Color(0xFFFF375F))
    return palette[Math.floorMod(botId.hashCode(), palette.size)]
}

@Composable
internal fun BotAvatar(bot: BotEntity, modifier: Modifier = Modifier, statusColor: Color? = null, size: androidx.compose.ui.unit.Dp = 48.dp) {
    val tint = if (bot.enabled) memberTint(bot.id) else ChatColors.tertiaryText
    Box(modifier.size(size), contentAlignment = Alignment.Center) {
        Box(Modifier.fillMaxSize().background(tint.copy(alpha = 0.14f), RoundedCornerShape(size / 4)), contentAlignment = Alignment.Center) {
            Text(bot.name.codePoints().findFirst().orElse('?'.code).let { String(Character.toChars(it)) },
                fontSize = (size.value * 0.42f).sp, fontWeight = FontWeight.SemiBold, color = tint)
        }
    }
}

/** A status as a small tinted pill with a dot: working, available, waiting, disabled. */
@Composable
internal fun StatusPill(text: String, color: Color) {
    Row(
        modifier = Modifier
            .background(color.copy(alpha = 0.14f), CircleShape)
            .padding(horizontal = 8.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        Box(Modifier.size(6.dp).background(color, CircleShape))
        Text(text, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = color, maxLines = 1)
    }
}

@Composable
private fun BotMemberRow(
    bot: BotEntity,
    status: String,
    statusColor: Color,
    preview: String?,
    showDivider: Boolean,
    onOpen: () -> Unit,
) {
    Column {
        Row(
            Modifier.fillMaxWidth().clickable(onClick = onOpen).padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            BotAvatar(bot, size = 52.dp)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(bot.name, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false))
                    StatusPill(status, statusColor)
                }
                Text(preview ?: stringResource(R.string.bots_activity_empty), style = MaterialTheme.typography.bodySmall,
                    color = ChatColors.secondaryText, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = ChatColors.secondaryText.copy(alpha = 0.5f), modifier = Modifier.size(20.dp))
        }
        if (showDivider) HorizontalDivider(thickness = 0.5.dp, color = ChatColors.separator.copy(alpha = 0.5f), modifier = Modifier.padding(start = 80.dp))
    }
}

@Composable
private fun BotNeedsDecisionCard(task: BotTaskEntity, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp),
        color = MaterialTheme.colorScheme.errorContainer,
        contentColor = MaterialTheme.colorScheme.onErrorContainer,
        shape = RoundedCornerShape(16.dp),
    ) {
        Row(
            Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Icon(Icons.Outlined.ErrorOutline, contentDescription = null)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    stringResource(R.string.bots_task_needs_user),
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    task.goal.lineSequence().firstOrNull { it.isNotBlank() }.orEmpty(),
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    botTaskPhaseText(task),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onErrorContainer.copy(alpha = 0.78f),
                )
            }
        }
    }
}

@Composable
private fun ColumnScope.BotEmptyState(title: Int, body: Int, action: @Composable (() -> Unit)? = null) {
    Column(Modifier.fillMaxWidth().weight(1f).padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically)) {
        Icon(Icons.Outlined.Group, null, Modifier.size(40.dp), tint = ChatColors.secondaryText)
        Text(stringResource(title), style = MaterialTheme.typography.titleMedium)
        Text(stringResource(body), style = MaterialTheme.typography.bodyMedium, color = ChatColors.secondaryText,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center)
        action?.invoke()
    }
}

@Composable
private fun BotDetails(
    bot: BotEntity, modelName: String?, sessions: List<ChatSessionEntity>, work: List<BotDelegationEntity>,
    routines: List<ScheduledTask>, bots: List<BotEntity>,
    opening: Boolean, onBack: () -> Unit, onOpen: () -> Unit, onNewTopic: () -> Unit, onEdit: () -> Unit,
    onToggle: () -> Unit, onDelete: () -> Unit, onOpenSession: (String) -> Unit, onOpenWork: (String) -> Unit,
    onOpenRoutineEditor: (taskId: String?, botId: String?) -> Unit,
    onToggleRoutine: (taskId: String, enabled: Boolean) -> Unit,
) {
    var menu by remember { mutableStateOf(false) }
    var expanded by rememberSaveable(bot.id) { mutableStateOf(false) }
    SettingsScaffold(
        title = "",
        onBack = onBack,
        backLabel = stringResource(R.string.bots_team),
        bottomBar = {
            Column(Modifier.background(MaterialTheme.colorScheme.background)) {
                HorizontalDivider(thickness = 0.5.dp, color = MaterialTheme.colorScheme.outlineVariant)
                Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
                    MinisTextButton(
                        onClick = onOpen,
                        enabled = !opening && (bot.enabled || sessions.isNotEmpty()),
                    ) { Text(stringResource(R.string.bots_continue), fontSize = 17.sp, fontWeight = FontWeight.SemiBold) }
                    MinisTextButton(
                        onClick = onNewTopic,
                        enabled = bot.enabled && !opening,
                    ) { Text(stringResource(R.string.bots_new_topic), fontSize = 17.sp) }
                }
            }
        },
        actions = {
            Box {
                IconButton(onClick = { menu = true }) { Icon(Icons.Default.MoreHoriz, stringResource(R.string.bots_more), tint = MaterialTheme.colorScheme.primary) }
                MinisMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.bots_edit)) },
                        trailingIcon = { Icon(Icons.Outlined.Edit, null) },
                        onClick = { menu = false; onEdit() },
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(if (bot.enabled) R.string.bots_disable else R.string.bots_enable)) },
                        trailingIcon = { Icon(if (bot.enabled) Icons.Outlined.PauseCircle else Icons.Outlined.PlayCircle, null) },
                        onClick = { menu = false; onToggle() },
                    )
                    MinisMenuDivider()
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.bots_delete), color = MaterialTheme.colorScheme.error) },
                        trailingIcon = { Icon(Icons.Outlined.Delete, null, tint = MaterialTheme.colorScheme.error) },
                        onClick = { menu = false; onDelete() },
                    )
                }
            }
        },
    ) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 16.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            BotAvatar(bot, size = 64.dp)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(bot.name, fontSize = 28.sp, fontWeight = FontWeight.Bold)
                StatusPill(
                    stringResource(if (bot.enabled) R.string.bots_available else R.string.bots_disabled),
                    if (bot.enabled) ChatColors.ok else ChatColors.tertiaryText,
                )
            }
        }
        SettingsSection(header = stringResource(R.string.bots_role), footer = if (!bot.enabled) stringResource(R.string.bots_disabled_footer) else null) {
            Column(Modifier.padding(16.dp)) {
                Text(bot.systemPrompt ?: stringResource(R.string.bots_role_empty), style = MaterialTheme.typography.bodyMedium,
                    maxLines = if (expanded) Int.MAX_VALUE else 6, overflow = TextOverflow.Ellipsis)
                if ((bot.systemPrompt?.length ?: 0) > 200 || (bot.systemPrompt?.count { it == '\n' } ?: 0) > 5) {
                    MinisTextButton(onClick = { expanded = !expanded }) {
                        Text(stringResource(if (expanded) R.string.bots_show_less else R.string.bots_show_all))
                    }
                }
            }
        }
        SettingsSection(header = stringResource(R.string.bots_model), footer = stringResource(R.string.bots_model_footer)) {
            SettingsRow(title = if (bot.modelBinding == null) stringResource(R.string.bots_follow_default)
                else modelName ?: stringResource(R.string.bots_model_missing), onClick = onEdit, showDivider = false)
        }
        SettingsSection(
            header = stringResource(R.string.bots_routines),
            footer = if (!bot.enabled) stringResource(R.string.bots_routines_disabled) else null,
        ) {
            SettingsRow(
                title = stringResource(R.string.bots_new_routine),
                icon = Icons.Outlined.Add,
                onClick = { onOpenRoutineEditor(null, bot.id) },
                showDivider = routines.isNotEmpty(),
            )
            routines.forEachIndexed { index, task ->
                val nextRun = task.nextTriggerMs()?.let {
                    SimpleDateFormat("MMM d, HH:mm", Locale.getDefault()).format(Date(it))
                } ?: stringResource(R.string.bots_routine_no_next)
                val recentResult = task.runHistory.firstOrNull()?.preview ?: task.lastResultPreview
                val screenFailure = when {
                    recentResult?.contains("screen_busy", ignoreCase = true) == true ->
                        stringResource(R.string.bots_routine_vscreen_busy)
                    recentResult?.contains("vscreen_unavailable", ignoreCase = true) == true ->
                        stringResource(R.string.bots_routine_vscreen_unavailable)
                    else -> null
                }
                val subtitle = listOfNotNull(nextRun, screenFailure ?: recentResult?.take(80)).joinToString(" · ")
                SettingsRow(
                    title = task.label.ifBlank { task.prompt.take(40) },
                    subtitle = subtitle,
                    onClick = { onOpenRoutineEditor(task.id, bot.id) },
                    trailing = {
                        MinisSwitch(checked = task.enabled, onCheckedChange = { onToggleRoutine(task.id, it) })
                    },
                    showDivider = index < routines.lastIndex,
                )
            }
        }
        if (work.isNotEmpty()) SettingsSection(header = stringResource(R.string.bots_recent_work)) {
            work.take(5).forEach { task -> DelegationRow(task, bots) { onOpenWork(task.id) } }
        }
        if (sessions.isNotEmpty()) SettingsSection(header = stringResource(R.string.bots_recent_conversations)) {
            sessions.take(5).forEachIndexed { index, session ->
                SettingsRow(title = session.title ?: bot.name, subtitle = session.lastMessage,
                    onClick = { onOpenSession(session.id) }, showDivider = index < minOf(sessions.size, 5) - 1)
            }
        }
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun BotEditorScreen(bot: BotEntity?, providerRepository: ProviderRepository, saving: Boolean, onBack: () -> Unit,
    onSave: (String, String?, String?) -> Unit) {
    var name by rememberSaveable(bot?.id) { mutableStateOf(bot?.name.orEmpty()) }
    var prompt by rememberSaveable(bot?.id) { mutableStateOf(bot?.systemPrompt.orEmpty()) }
    var binding by rememberSaveable(bot?.id) { mutableStateOf(bot?.modelBinding) }
    var picker by remember { mutableStateOf(false) }
    var modelError by remember { mutableStateOf(false) }
    val config by providerRepository.config.collectAsState()
    val selectedEntryId = (com.openminis.app.data.model.ModelBinding.parse(binding)
        as? com.openminis.app.data.model.ModelBinding.Entry)?.entryId
    val selected = config.modelEntries.firstOrNull { it.id == selectedEntryId }
    SettingsScaffold(title = stringResource(if (bot == null) R.string.bots_add else R.string.bots_edit), centerTitle = true,
        navigation = { MinisTextButton(onClick = onBack, enabled = !saving) { Text(stringResource(android.R.string.cancel)) } }, actions = {
            MinisTextButton(onClick = {
                onSave(name, prompt.ifBlank { null }, binding)
            }, enabled = name.isNotBlank() && !saving) { Text(stringResource(R.string.save)) }
        }) {
        SettingsSection(header = stringResource(R.string.bots_name)) {
            SettingsInlineTextRow(
                title = stringResource(R.string.bots_name),
                value = name,
                onValueChange = { name = it.take(BotRepository.NAME_MAX_CHARS) },
                placeholder = stringResource(R.string.bots_name),
                showDivider = false,
            )
        }
        SettingsSection(header = stringResource(R.string.bots_role)) {
            SettingsTextArea(
                value = prompt,
                onValueChange = { prompt = it.take(BotRepository.SYSTEM_PROMPT_MAX_CHARS) },
                placeholder = stringResource(R.string.bots_role_hint),
                minHeight = 168.dp,
            )
        }
        SettingsSection(header = stringResource(R.string.bots_model), footer = stringResource(R.string.bots_model_footer)) {
            SettingsRow(title = if (binding == null) stringResource(R.string.bots_follow_default)
                else selected?.model?.displayName ?: stringResource(R.string.bots_model_missing), onClick = { picker = true }, showDivider = binding != null)
            if (binding != null) SettingsRow(title = stringResource(R.string.bots_follow_default), onClick = { binding = null }, showDivider = false)
        }
        Spacer(Modifier.height(24.dp))
    }
    if (picker) ModelPickerSheet(
        activeEntryId = selected?.id,
        config = config,
        providerRepository = providerRepository,
        onSelectEntry = { id ->
            if (providerRepository.allVisibleEntries().firstOrNull { it.id == id }?.model?.isTextOutput == true) {
                binding = com.openminis.app.data.model.ModelBinding.encodeEntry(id); picker = false
            } else modelError = true
        }, onDismiss = { picker = false },
    )
    if (modelError) MinisAlertDialog(onDismissRequest = { modelError = false }, title = stringResource(R.string.bots_model),
        text = stringResource(R.string.bots_text_model_required), confirmText = stringResource(android.R.string.ok), onConfirm = { modelError = false })
}

@Composable
internal fun delegationStatus(task: BotDelegationEntity): String = stringResource(when {
    task.outcomeUnknown != 0 -> R.string.bots_outcome_unknown
    task.status == BotDelegationEntity.STATUS_RUNNING -> R.string.bots_working
    task.status == BotDelegationEntity.STATUS_QUEUED -> R.string.bots_queued
    task.status == BotDelegationEntity.STATUS_WAITING_TARGET -> R.string.bots_waiting
    task.status == BotDelegationEntity.STATUS_COMPLETED -> R.string.bots_execution_finished
    task.status == BotDelegationEntity.STATUS_CANCELLED -> R.string.bots_cancelled
    task.status == BotDelegationEntity.STATUS_DENIED -> R.string.bots_denied
    task.status == BotDelegationEntity.STATUS_BUSY_GAVE_UP -> R.string.bots_busy_gave_up
    else -> R.string.bots_failed
})

@Composable
private fun DelegationRow(task: BotDelegationEntity, bots: List<BotEntity>, onClick: () -> Unit) {
    val from = bots.firstOrNull { it.id == task.sourceBotId }?.name ?: stringResource(R.string.bots_unknown_member)
    val to = bots.firstOrNull { it.id == task.targetBotId }?.name ?: stringResource(R.string.bots_unknown_member)
    SettingsRow(title = task.prompt.lineSequence().firstOrNull { it.isNotBlank() }.orEmpty(),
        subtitle = "$from → $to · ${delegationStatus(task)}",
        icon = Icons.AutoMirrored.Outlined.Assignment,
        onClick = onClick,
        minHeight = 72.dp,
    )
}

@Composable
private fun BotTaskRow(
    task: BotTaskEntity,
    status: String,
    onClick: () -> Unit,
    onPause: () -> Unit,
    onCancel: () -> Unit,
) {
    Column(Modifier.fillMaxWidth()) {
        SettingsRow(
            title = task.goal.lineSequence().firstOrNull { it.isNotBlank() }.orEmpty(),
            subtitle = "$status · ${botTaskPhaseText(task)}",
            icon = Icons.AutoMirrored.Outlined.Assignment,
            onClick = onClick,
            minHeight = 72.dp,
        )
        Text(
            stringResource(
                R.string.bots_task_budget_summary,
                task.autoRunsUsed,
                BotWakePolicy.MAX_AUTO_WAKES,
                task.revisionRoundsUsed,
                BotWakePolicy.MAX_REVISION_ROUNDS,
                task.delegationsUsed,
                BotWakePolicy.MAX_DELEGATIONS_PER_TASK,
            ),
            modifier = Modifier.padding(start = 56.dp, end = 16.dp, bottom = 2.dp),
            style = MaterialTheme.typography.bodySmall,
            color = ChatColors.secondaryText,
        )
        if (task.status in setOf(
                BotTaskEntity.STATUS_ACTIVE,
                BotTaskEntity.STATUS_NEEDS_USER,
                BotTaskEntity.STATUS_WAITING,
            )
        ) {
            Row(
                Modifier.fillMaxWidth().padding(start = 48.dp, bottom = 4.dp),
                horizontalArrangement = Arrangement.Start,
            ) {
                TextButton(onClick = onPause) { Text(stringResource(R.string.bots_task_pause)) }
                TextButton(onClick = onCancel) { Text(stringResource(R.string.bots_task_cancel)) }
            }
        }
    }
}

@Composable
private fun delegationStatusText(task: BotTaskEntity): String = stringResource(
    when (task.status) {
        BotTaskEntity.STATUS_NEEDS_USER -> R.string.bots_task_needs_user
        BotTaskEntity.STATUS_PAUSED -> R.string.bots_task_paused
        BotTaskEntity.STATUS_COMPLETED -> R.string.bots_task_completed
        BotTaskEntity.STATUS_CANCELLED -> R.string.bots_task_cancelled
        BotTaskEntity.STATUS_FAILED -> R.string.bots_failed
        BotTaskEntity.STATUS_BUDGET_EXHAUSTED -> R.string.bots_task_budget_exhausted
        else -> R.string.bots_task_active
    },
)

@Composable
private fun botTaskPhaseText(task: BotTaskEntity): String = stringResource(
    when (task.phase) {
        BotTaskEntity.PHASE_PLANNING -> R.string.bots_phase_planning
        BotTaskEntity.PHASE_EXECUTING -> R.string.bots_phase_executing
        BotTaskEntity.PHASE_REVIEWING -> R.string.bots_phase_reviewing
        BotTaskEntity.PHASE_REVISING -> R.string.bots_phase_revising
        BotTaskEntity.PHASE_DELIVERING -> R.string.bots_phase_delivering
        else -> R.string.bots_phase_executing
    },
)

@Composable
private fun DelegationDetail(task: BotDelegationEntity, bots: List<BotEntity>, onBack: () -> Unit, onOpenSession: (String) -> Unit) {
    val from = bots.firstOrNull { it.id == task.sourceBotId }?.name ?: stringResource(R.string.bots_unknown_member)
    val to = bots.firstOrNull { it.id == task.targetBotId }?.name ?: stringResource(R.string.bots_unknown_member)
    SettingsScaffold(title = stringResource(R.string.bots_progress), onBack = onBack) {
        SettingsSection {
            SettingsRow(title = "$from → $to", subtitle = delegationStatus(task), showDivider = false)
        }
        SettingsSection(header = stringResource(R.string.bots_request)) {
            Text(task.prompt, modifier = Modifier.padding(16.dp), style = MaterialTheme.typography.bodyMedium)
        }
        SettingsSection(header = stringResource(R.string.bots_result)) {
            Text(task.errorText ?: task.resultText ?: stringResource(R.string.bots_no_result),
                modifier = Modifier.padding(16.dp), style = MaterialTheme.typography.bodyMedium)
        }
        SettingsSection {
            SettingsRow(title = stringResource(R.string.bots_source_conversation), onClick = { onOpenSession(task.sourceSessionId) }, showDivider = task.targetSessionId != null)
            task.targetSessionId?.let { id ->
                SettingsRow(title = stringResource(R.string.bots_open_execution), onClick = { onOpenSession(id) }, showDivider = false)
            }
        }
        Spacer(Modifier.height(24.dp))
    }
}
