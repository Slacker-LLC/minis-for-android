package com.openminis.app.ui.bots

import androidx.activity.compose.BackHandler
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
import com.openminis.app.service.SessionConcurrencyManager
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
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.map

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
) {
    val context = LocalContext.current
    val delegationRepository = remember(context) {
        (context.applicationContext as MinisApp).botDelegationRepository
    }
    val taskRepository = remember(context) {
        (context.applicationContext as MinisApp).botTaskRepository
    }
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
                config.modelEntries.firstOrNull { it.id == binding || it.baseModel.id == binding }?.model?.displayName
            },
            sessions = recent,
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
                        check(botRepository.setBotEnabled(selectedBot.id, !selectedBot.enabled)) { failureText }
                    } catch (cancelled: CancellationException) { throw cancelled }
                    catch (exception: Exception) { error = exception.message ?: failureText }
                }
            },
            onDelete = { deleting = selectedBot },
            onOpenSession = ::openSession,
            onOpenWork = { selectedTaskId = it },
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
            actions = {
                if (!showProgress) {
                    IconButton(onClick = { if (onOpenProgress != null) onOpenProgress() else { showProgress = true } }) {
                        Icon(Icons.AutoMirrored.Outlined.Assignment, stringResource(R.string.bots_progress))
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
                                    val delegation = delegations.firstOrNull { it.rootTaskId == task.id }
                                    delegation?.let { selectedTaskId = it.id }
                                        ?: run { error = taskNoExecutionText }
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
                                BotTaskRow(rootTask, delegationStatusText(rootTask)) {
                                    delegation?.let { selectedTaskId = it.id }
                                        ?: run { error = taskNoExecutionText }
                                }
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
                LazyColumn(contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp)) {
                    items(bots, key = { it.id }) { bot ->
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
                            waiting || botSessions.any { it.id in suspended } -> MaterialTheme.colorScheme.tertiary
                            else -> MaterialTheme.colorScheme.secondary
                        }
                        BotMemberRow(
                            bot = bot,
                            status = stringResource(status),
                            statusColor = statusColor,
                            preview = botSessions.firstOrNull()?.lastMessage ?: bot.systemPrompt?.lineSequence()?.firstOrNull { it.isNotBlank() },
                            enabled = openingId == null,
                            onOpen = { openConversation(bot) },
                            onDetails = { if (onMemberDetails != null) onMemberDetails(bot.id) else { selectedBotId = bot.id } },
                        )
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

@Composable
internal fun BotAvatar(bot: BotEntity, modifier: Modifier = Modifier, statusColor: Color? = null) {
    Box(modifier.size(42.dp), contentAlignment = Alignment.Center) {
        Box(Modifier.fillMaxSize().background(ChatColors.secondaryBg, CircleShape), contentAlignment = Alignment.Center) {
            Text(bot.name.codePoints().findFirst().orElse('?'.code).let { String(Character.toChars(it)) },
                style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Medium, color = ChatColors.primaryText)
        }
        statusColor?.let { color ->
            Box(
                Modifier
                    .align(Alignment.BottomEnd)
                    .size(11.dp)
                    .background(color, CircleShape)
                    .border(2.dp, MaterialTheme.colorScheme.background, CircleShape),
            )
        }
    }
}

@Composable
private fun BotMemberRow(
    bot: BotEntity,
    status: String,
    statusColor: Color,
    preview: String?,
    enabled: Boolean,
    onOpen: () -> Unit,
    onDetails: () -> Unit,
) {
    Row(Modifier.fillMaxWidth().heightIn(min = 88.dp), verticalAlignment = Alignment.CenterVertically) {
        Row(Modifier.weight(1f).clickable(enabled = enabled, onClick = onOpen).padding(vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            BotAvatar(bot, statusColor = statusColor)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(bot.name, Modifier.weight(1f), style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(status, style = MaterialTheme.typography.labelSmall, color = statusColor)
                }
                Text(preview ?: stringResource(R.string.bots_activity_empty), style = MaterialTheme.typography.bodySmall,
                    color = ChatColors.secondaryText, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        }
        IconButton(onClick = onDetails) {
            Icon(Icons.Default.MoreVert, stringResource(R.string.bots_details), tint = ChatColors.secondaryText)
        }
    }
    HorizontalDivider(thickness = 0.5.dp, color = ChatColors.separator.copy(alpha = 0.4f), modifier = Modifier.padding(start = 54.dp))
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
    bot: BotEntity, modelName: String?, sessions: List<ChatSessionEntity>, work: List<BotDelegationEntity>, bots: List<BotEntity>,
    opening: Boolean, onBack: () -> Unit, onOpen: () -> Unit, onNewTopic: () -> Unit, onEdit: () -> Unit,
    onToggle: () -> Unit, onDelete: () -> Unit, onOpenSession: (String) -> Unit, onOpenWork: (String) -> Unit,
) {
    var menu by remember { mutableStateOf(false) }
    var expanded by rememberSaveable(bot.id) { mutableStateOf(false) }
    SettingsScaffold(title = stringResource(R.string.bots_details), onBack = onBack, actions = {
        Box {
            IconButton(onClick = { menu = true }) { Icon(Icons.Default.MoreVert, stringResource(R.string.bots_more)) }
            MinisMenu(expanded = menu, onDismissRequest = { menu = false }) {
                DropdownMenuItem(text = { Text(stringResource(R.string.bots_edit)) }, onClick = { menu = false; onEdit() })
                DropdownMenuItem(text = { Text(stringResource(R.string.bots_new_topic)) }, enabled = bot.enabled && !opening,
                    onClick = { menu = false; onNewTopic() })
                DropdownMenuItem(text = { Text(stringResource(if (bot.enabled) R.string.bots_disable else R.string.bots_enable)) },
                    onClick = { menu = false; onToggle() })
                MinisMenuDivider()
                DropdownMenuItem(text = { Text(stringResource(R.string.bots_delete), color = MaterialTheme.colorScheme.error) },
                    onClick = { menu = false; onDelete() })
            }
        }
    }) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 16.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            BotAvatar(bot)
            Column(Modifier.weight(1f)) {
                Text(bot.name, style = MaterialTheme.typography.titleLarge)
                if (!bot.enabled) Text(stringResource(R.string.bots_disabled), style = MaterialTheme.typography.bodySmall, color = ChatColors.secondaryText)
            }
        }
        MinisButton(onClick = onOpen, enabled = !opening && (bot.enabled || sessions.isNotEmpty()),
            modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp)) {
            Icon(Icons.AutoMirrored.Outlined.Chat, null, Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text(stringResource(R.string.bots_continue))
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
    val selected = config.modelEntries.firstOrNull { it.id == binding || it.baseModel.id == binding }
    SettingsScaffold(title = stringResource(if (bot == null) R.string.bots_add else R.string.bots_edit), centerTitle = true,
        navigation = { MinisTextButton(onClick = onBack, enabled = !saving) { Text(stringResource(android.R.string.cancel)) } }, actions = {
            MinisTextButton(onClick = {
                onSave(name, prompt.ifBlank { null }, binding)
            }, enabled = name.isNotBlank() && !saving) { Text(stringResource(R.string.save)) }
        }) {
        SettingsSection(header = stringResource(R.string.bots_name)) {
            SectionTextField(value = name, onValueChange = { name = it.take(BotRepository.NAME_MAX_CHARS) },
                modifier = Modifier.padding(horizontal = 16.dp), placeholder = stringResource(R.string.bots_name))
        }
        SettingsSection(header = stringResource(R.string.bots_role)) {
            SectionTextField(value = prompt, onValueChange = { prompt = it.take(BotRepository.SYSTEM_PROMPT_MAX_CHARS) },
                modifier = Modifier.padding(horizontal = 16.dp).heightIn(min = 168.dp), singleLine = false,
                placeholder = stringResource(R.string.bots_role_hint))
        }
        SettingsSection(header = stringResource(R.string.bots_model), footer = stringResource(R.string.bots_model_footer)) {
            SettingsRow(title = if (binding == null) stringResource(R.string.bots_follow_default)
                else selected?.model?.displayName ?: stringResource(R.string.bots_model_missing), onClick = { picker = true }, showDivider = binding != null)
            if (binding != null) SettingsRow(title = stringResource(R.string.bots_follow_default), onClick = { binding = null }, showDivider = false)
        }
        Spacer(Modifier.height(24.dp))
    }
    if (picker) ModelPickerSheet(
        groups = emptyList(), selectedGroupId = null, activeEntryId = selected?.id, defaultPrimaryGroupId = null,
        config = config, providerRepository = providerRepository, onSelectGroup = {}, onSelectGroupEntry = { _, _ -> },
        onSelectEntry = { id ->
            if (providerRepository.allVisibleEntries().firstOrNull { it.id == id }?.model?.isTextOutput == true) {
                binding = id; picker = false
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
private fun BotTaskRow(task: BotTaskEntity, status: String, onClick: () -> Unit) {
    SettingsRow(
        title = task.goal.lineSequence().firstOrNull { it.isNotBlank() }.orEmpty(),
        subtitle = "$status · ${botTaskPhaseText(task)}",
        icon = Icons.AutoMirrored.Outlined.Assignment,
        onClick = onClick,
        minHeight = 72.dp,
    )
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
