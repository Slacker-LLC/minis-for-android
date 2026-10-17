package com.openminis.app.voicecall

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.material.icons.rounded.Add
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import coil.compose.AsyncImage
import com.openminis.app.ui.chat.InputAttachment
import com.openminis.app.ui.chat.getFileName
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Send
import androidx.compose.material.icons.rounded.ChatBubbleOutline
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.MicOff
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import androidx.core.view.WindowCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.currentStateAsState
import android.view.WindowManager
import android.widget.Toast
import com.openminis.app.agent.ensureSessionForSettings
import com.openminis.app.R
import com.openminis.app.data.repository.ensureDefaultVoiceInputSlot
import com.openminis.app.data.repository.loadApiKey
import com.openminis.app.voicecall.realtime.AndroidRealtimeAudio
import com.openminis.app.voicecall.realtime.EngineSwitchingCallRuntime
import com.openminis.app.voicecall.realtime.OkHttpRealtimeTransport
import com.openminis.app.voicecall.realtime.RealtimeCallRuntime
import com.openminis.app.voicecall.realtime.RealtimeCallSettings
import com.openminis.app.voicecall.realtime.realtimeInstructions
import com.openminis.app.voicecall.realtime.resolveRealtime
import com.openminis.app.data.repository.ensureDefaultVoiceOutputSlot
import com.openminis.app.deeplink.DeepLinkCoordinator
import com.openminis.app.service.VoiceCallBridge
import com.openminis.app.service.VoiceCallForegroundService
import com.openminis.app.speech.ReadAloudPlayer
import com.openminis.app.speech.VoiceOutputState
import com.openminis.app.speech.SpeechRecognitionManager
import com.openminis.app.ui.chat.ChatViewModel
import com.openminis.app.ui.chat.TaskWorkSummary
import com.openminis.app.ui.components.MinisButton
import com.openminis.app.ui.components.MinisOutlinedButton
import com.openminis.app.ui.theme.ChatColors
import com.openminis.app.ui.theme.minisPageBackground
import com.openminis.app.voicecall.controller.CallController
import com.openminis.app.voicecall.grok.VoiceBotAvatar
import com.openminis.app.voicecall.grok.voiceCallStatusRes
import com.openminis.app.voicecall.model.BloubState
import com.openminis.app.voicecall.model.CallEnded
import com.openminis.app.voicecall.model.CallLine
import com.openminis.app.voicecall.model.CallToast
import com.openminis.app.voicecall.model.CallMode
import com.openminis.app.voicecall.runtime.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.Locale

/** 所有入口绑定当前聊天 VM；权限确认仍由 ChatScreen 的原有 Dialog 承接。 */
/** 一次通话里最多挂几张待发图片。 */
private const val MAX_CALL_IMAGES = 4

@Composable
internal fun VoiceConversationHost(
    viewModel: ChatViewModel,
    sessionId: String?,
    /** Mirrors `ChatScreen(isChatDestination=…)`; deep-link actions require it. */
    fromChatRoute: Boolean,
    ensureMicPermission: suspend () -> Boolean,
    /** 口头切换任务：结束通话后把聊天切到该任务所在会话（和聊天里的会话跳转同一个入口）。 */
    onMoveToSession: (String) -> Unit = {},
) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val scope = rememberCoroutineScope()
    val focusManager = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    val player = remember(viewModel) {
        lazy { ReadAloudPlayer(context.applicationContext).also { it.communicationUsage = VoiceOutputState.callEarpiece.value } }
    }
    // 通话里选的图片：随下一条提交的消息（打字或语音）一起发给 Agent。
    val pendingImages = remember(viewModel) { mutableStateListOf<InputAttachment>() }
    val callTasks = remember(viewModel) {
        AgentCallTasks(
            context.applicationContext,
            (context.applicationContext as com.openminis.app.MinisApp).agentTaskRepository,
            { viewModel.agentSession.currentSessionId },
        )
    }
    val gateway = remember(viewModel) {
        AgentCallGateway(
            viewModel.agentSession,
            takeAttachments = { pendingImages.toList().also { pendingImages.clear() } },
            onTaskReported = callTasks::markReported,
        )
    }
    // 适配器先于 endAudio 创建，切换回调经这个句柄转过去。
    val switchSession = remember(viewModel) { arrayOf<(String) -> Unit>({}) }
    val imagePicker = rememberLauncherForActivityResult(
        ActivityResultContracts.PickMultipleVisualMedia(MAX_CALL_IMAGES),
    ) { uris ->
        uris.forEach { uri ->
            if (pendingImages.size >= MAX_CALL_IMAGES) return@forEach
            pendingImages += InputAttachment(
                fileName = getFileName(context, uri) ?: "image.jpg",
                uri = uri,
                mimeType = context.contentResolver.getType(uri) ?: "image/jpeg",
                kind = InputAttachment.Kind.IMAGE,
            )
        }
    }
    val input = remember(viewModel) { AgentCallSpeechInput(viewModel.providerRepository) }
    val approvals = remember(viewModel) {
        AgentCallApprovals(context.applicationContext, { viewModel.agentSession.currentSessionId }, scope)
    }
    val runtime = remember(viewModel) {
        val classic = MinisCallRuntimeAdapter(
            input,
            gateway,
            AgentCallSpeechOutput(player),
            scope,
            approvals,
            context.getString(R.string.voice_call_working),
            callTasks,
            { id -> switchSession[0](id) },
        )
        RealtimeCallSettings.init(context)
        EngineSwitchingCallRuntime(classic, {
            val repo = viewModel.providerRepository
            val selection = RealtimeCallSettings.state.value
            val instance = repo.config.value.instances.firstOrNull { it.id == selection.instanceId }
            resolveRealtime(selection, instance, instance?.let { repo.loadApiKey(it.id) })?.let { connection ->
                RealtimeCallRuntime(
                    connection = connection,
                    instructions = realtimeInstructions(connection.profile.supportsTools),
                    transport = OkHttpRealtimeTransport(),
                    audio = AndroidRealtimeAudio(context.applicationContext),
                    model = gateway,
                    scope = scope,
                    approvals = approvals,
                    tasks = callTasks,
                    onSwitchSession = { id -> switchSession[0](id) },
                )
            }
        }, scope)
    }
    val controller = remember(runtime) { CallController(runtime, scope) }
    var visible by remember(viewModel) { mutableStateOf(false) }
    var opening by remember(viewModel) { mutableStateOf(false) }
    var requestVersion by remember(viewModel) { mutableIntStateOf(0) }
    var entryError by remember(viewModel) { mutableStateOf<String?>(null) }
    var transcriptMode by remember(viewModel) { mutableStateOf(false) }
    var draft by remember(viewModel) { mutableStateOf("") }
    val pending by DeepLinkCoordinator.pendingChatAction.collectAsState()
    val lifecycleState by lifecycle.currentStateAsState()
    val modelName by viewModel.modelName.collectAsState()
    val active = controller.state.active
    // 「＋」附图只在当前模型能看图（或有 Vision Group 代看）时出现；换模型后重新判断。
    val canAttachImages = remember(modelName, active) { viewModel.agentSession.canAcceptImages() }
    // 听筒/扬声器：通话期间按设置切换系统路由，挂断或离开页面一律还原。
    val earpiece by VoiceOutputState.callEarpiece.collectAsState()
    val audioRoute = remember { CallAudioRoute(context) }
    LaunchedEffect(active, earpiece) {
        val useEarpiece = active && earpiece
        if (player.isInitialized()) player.value.communicationUsage = useEarpiece
        if (useEarpiece) audioRoute.useEarpiece() else audioRoute.release()
    }

    fun endAudio() {
        requestVersion++
        opening = false
        visible = false
        transcriptMode = false
        draft = ""
        pendingImages.clear()
        controller.endCall()
        viewModel.voiceConversationActive.value = false
        VoiceCallBridge.publish(null)
    }

    switchSession[0] = { id ->
        endAudio()
        DeepLinkCoordinator.setPendingChatAction(DeepLinkCoordinator.ChatAction.START_VOICE, id)
        onMoveToSession(id)
    }

    fun openCall() {
        // 首页键盘开着时先收起：否则通话页盖上来、键盘又跟着收放，一次点击看到两段硬切。
        focusManager.clearFocus()
        keyboard?.hide()
        visible = true
        if (active || opening) return
        val version = ++requestVersion
        opening = true
        entryError = null
        scope.launch {
            try {
                if (!ensureMicPermission()) {
                    entryError = context.getString(R.string.voice_call_permission)
                    return@launch
                }
                if (version != requestVersion || !lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) return@launch
                viewModel.providerRepository.ensureDefaultVoiceInputSlot()
                viewModel.providerRepository.ensureDefaultVoiceOutputSlot()
                SpeechRecognitionManager.init(context)
                SpeechRecognitionManager.cancelRecording()
                SpeechRecognitionManager.clearDegradationAndRefresh()
                viewModel.replyPlayer?.stop()
                val sid = try {
                    viewModel.agentSession.ensureSessionForSettings()
                } catch (e: IllegalStateException) {
                    // 历史里点开的会话行已不存在（SessionDeletedException，消息 session_deleted）：
                    // 提示即可，别让异常冒出 scope 把进程带崩；其它状态错误照常抛。
                    if (e.message != "session_deleted") throw e
                    entryError = context.getString(R.string.voice_call_session_missing)
                    return@launch
                }
                com.openminis.app.data.AssistantHomePrefs.rememberVoiceSession(context, sid)
                viewModel.voiceConversationActive.value = true
                controller.startCall(CallMode.VOICE)
            } finally {
                if (version == requestVersion) opening = false
            }
        }
    }

    // 深链冷启动时组合先于 onResume，而 openCall 只在 RESUMED 下开麦：早于前台
    // 消费 pending 会静默停在 Ready 让用户再点一次，所以等前台就绪后再消费。
    LaunchedEffect(pending, lifecycleState) {
        if (!lifecycleState.isAtLeast(Lifecycle.State.RESUMED)) return@LaunchedEffect
        // Only the session this action was minted for may consume it: the
        // screen being navigated away from collects the same flow.
        if (DeepLinkCoordinator.pendingChatActionFor(sessionId, fromChatRoute) ==
            DeepLinkCoordinator.ChatAction.START_VOICE
        ) {
            val scoped = DeepLinkCoordinator.pendingChatActionSessionId() != null
            DeepLinkCoordinator.consumePendingChatAction()
            // 输入框上的语音键默认新开一个对话：通话和结束后停留的都是新对话。
            val redirect = VoiceEntryTarget.redirectFromComposer(
                newChat = VoiceOutputState.callNewChat.value,
                actionScopedToSession = scoped,
                currentIsBlankDraft = viewModel.isBlankDraft,
                newDraftId = { "__new__${java.util.UUID.randomUUID()}" },
            )
            if (redirect != null) {
                DeepLinkCoordinator.setPendingChatAction(DeepLinkCoordinator.ChatAction.START_VOICE, redirect)
                onMoveToSession(redirect)
            } else {
                openCall()
            }
        }
    }
    // runtime 自动挂断（空闲/连续出错）：不留结束页，直接回到聊天，用提示说明原因。
    val ended = controller.state.ended
    LaunchedEffect(ended) {
        if (ended != null) {
            Toast.makeText(context, endedText(context, ended), Toast.LENGTH_LONG).show()
            endAudio()
        }
    }
    // 通话期间挂一个 microphone 前台服务：切后台/锁屏后麦克风继续工作，通知栏上有静音和挂断。
    val callStartedAt = remember(active) { if (active) System.currentTimeMillis() else 0L }
    LaunchedEffect(active, controller.state.muted) {
        if (active) {
            VoiceCallBridge.onHangUp = { endAudio() }
            VoiceCallBridge.onToggleMute = { controller.setMuted(!controller.state.muted) }
            VoiceCallBridge.publish(VoiceCallBridge.Call(controller.state.muted, callStartedAt))
            if (!VoiceCallBridge.serviceRunning.value) VoiceCallForegroundService.start(context.applicationContext)
        }
    }
    LaunchedEffect(active) {
        while (controller.state.active) {
            delay(1000)
            controller.tick()
        }
    }
    DisposableEffect(lifecycle, controller) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) {
                requestVersion++
                opening = false
                // 前台服务在跑（通知栏看得见、可静音可挂断）时通话继续；否则不在用户不知情时采集，立即暂停。
                if (!(controller.state.active && VoiceCallBridge.serviceRunning.value)) {
                    controller.setMuted(true)
                }
            }
        }
        lifecycle.addObserver(observer)
        onDispose {
            lifecycle.removeObserver(observer)
            VoiceCallBridge.onHangUp = null
            VoiceCallBridge.onToggleMute = null
            audioRoute.release()
            endAudio()
            if (player.isInitialized()) player.value.shutdown()
        }
    }

    // 通话页淡入/淡出：窗口要等退场动画播完才摘掉，否则挂断时整页一下子消失。
    var windowShown by remember { mutableStateOf(false) }
    if (visible) windowShown = true
    if (windowShown) {
        Dialog(
            onDismissRequest = { endAudio() },
            properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
        ) {
            // 键盘高度只由 imePadding 处理；窗口自己不再平移/缩放，否则会算两遍。
            val dialogWindow = (LocalView.current.parent as? DialogWindowProvider)?.window
            val darkTheme = ChatColors.isDark
            SideEffect {
                dialogWindow?.let { window ->
                    window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING)
                    // 过渡由下面的 AnimatedVisibility 负责，关掉系统自带的窗口动画，免得叠两层。
                    window.setWindowAnimations(0)
                    // 系统对话框自带的变暗遮罩不跟着淡入淡出，会在开/关的第一帧闪一下灰。
                    window.clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
                    // 状态栏/导航栏在页面背景上要可读：浅色主题用深色图标。
                    WindowCompat.getInsetsController(window, window.decorView).apply {
                        isAppearanceLightStatusBars = !darkTheme
                        isAppearanceLightNavigationBars = !darkTheme
                    }
                }
            }
            val pageState = remember { MutableTransitionState(false) }
            pageState.targetState = visible
            LaunchedEffect(visible, pageState.isIdle) {
                if (!visible && pageState.isIdle) windowShown = false
            }
            AnimatedVisibility(
                visibleState = pageState,
                enter = fadeIn(tween(CALL_PAGE_ENTER_MS)) + scaleIn(tween(CALL_PAGE_ENTER_MS), initialScale = 0.96f),
                exit = fadeOut(tween(CALL_PAGE_EXIT_MS)) + scaleOut(tween(CALL_PAGE_EXIT_MS), targetScale = 0.98f),
            ) {
            Surface(Modifier.fillMaxSize(), color = minisPageBackground(ChatColors.isDark)) {
                val state = controller.state
                val characterState = state.effectiveBloubState
                val statusLine = when {
                    entryError != null -> entryError.orEmpty()
                    opening -> stringResource(R.string.voice_call_preparing)
                    else -> stringResource(voiceCallStatusRes(characterState))
                }
                val toastText = state.toast?.let { toastText(context, it) }
                val orbLabel = stringResource(
                    if (characterState == BloubState.LISTENING) R.string.voice_call_submit
                    else R.string.voice_call_interrupt,
                )
                // 点球：听的时候是「现在发送」，想/说/出错时是「打断并重新说」。
                val orb: @Composable (Modifier) -> Unit = { modifier ->
                    VoiceBotAvatar(
                        characterState,
                        modifier
                            .semantics { contentDescription = statusLine }
                            .clickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = null,
                                enabled = active && characterState != BloubState.MUTED,
                                onClickLabel = orbLabel,
                            ) {
                                if (characterState == BloubState.LISTENING) input.stop()
                                else {
                                    controller.interrupt()
                                    controller.setMuted(false)
                                }
                            },
                    )
                }
                val statusColor = if (characterState == BloubState.ERROR || entryError != null) {
                    MaterialTheme.colorScheme.error
                } else {
                    ChatColors.secondaryText
                }
                Box(Modifier.fillMaxSize()) {
                    Column(
                        Modifier
                            .fillMaxSize()
                            .systemBarsPadding()
                            .imePadding()
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        // 顶栏：计时居中，右侧在「球」和「文字记录」两种版面间切换。
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            // 通话页不放设置：模型、听筒、语速、引擎都在 设置 › 对话与输入 › 语音通话。
                            Spacer(Modifier.size(48.dp))
                            Spacer(Modifier.weight(1f))
                            if (active) {
                                Text(
                                    callClock(state.elapsedSeconds),
                                    style = MaterialTheme.typography.labelLarge,
                                    color = ChatColors.secondaryText,
                                )
                            }
                            Spacer(Modifier.weight(1f))
                            IconButton(onClick = { transcriptMode = !transcriptMode }) {
                                Icon(
                                    if (transcriptMode) Icons.Rounded.GraphicEq else Icons.Rounded.ChatBubbleOutline,
                                    contentDescription = stringResource(
                                        if (transcriptMode) R.string.voice_call_show_orb else R.string.voice_call_back,
                                    ),
                                )
                            }
                        }

                        if (transcriptMode) {
                            TranscriptLayout(
                                lines = state.transcript,
                                statusLine = statusLine,
                                statusColor = statusColor,
                                orb = { orb(Modifier.size(72.dp)) },
                                modifier = Modifier.weight(1f).fillMaxWidth(),
                            )
                        } else {
                            // 球版面：下滑切到文字记录。
                            var dragged by remember { mutableFloatStateOf(0f) }
                            val swipeThreshold = with(LocalDensity.current) { 72.dp.toPx() }
                            Box(
                                Modifier
                                    .weight(1f)
                                    .fillMaxWidth()
                                    .pointerInput(Unit) {
                                        detectVerticalDragGestures(
                                            onDragStart = { dragged = 0f },
                                            onDragEnd = { if (dragged > swipeThreshold) transcriptMode = true },
                                            onDragCancel = { dragged = 0f },
                                        ) { _, delta -> dragged += delta }
                                    },
                                contentAlignment = Alignment.Center,
                            ) {
                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                    orb(Modifier.size(240.dp))
                                    Spacer(Modifier.height(16.dp))
                                    Text(
                                        statusLine,
                                        style = MaterialTheme.typography.titleMedium,
                                        textAlign = TextAlign.Center,
                                        color = statusColor,
                                    )
                                }
                            }
                        }

                        TaskWorkSummary(viewModel.agentSession.activeSessionId)

                        // 底部：输入栏（文字框、静音、挂断）。两种版面共用。文字回车即发，
                        // 助手用语音回答；静音只关麦克风，挂断结束音频，已接受的任务继续在原会话跑。
                        Box(Modifier.fillMaxWidth().padding(top = 8.dp), contentAlignment = Alignment.Center) {
                            when {
                                active -> CallInputBar(
                                    draft = draft,
                                    onDraft = { draft = it },
                                    onSend = {
                                        controller.sendText(draft)
                                        draft = ""
                                    },
                                    muted = state.muted,
                                    onToggleMute = { controller.setMuted(!state.muted) },
                                    onEnd = { endAudio() },
                                    images = pendingImages,
                                    canAttach = canAttachImages,
                                    onAttach = {
                                        imagePicker.launch(
                                            PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly),
                                        )
                                    },
                                    onRemoveImage = { pendingImages.remove(it) },
                                )
                                opening -> Unit
                                else -> Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                    // 入口失败（缺麦克风权限等）：回聊天 / 重试。
                                    MinisOutlinedButton(onClick = { endAudio() }) {
                                        Text(stringResource(R.string.voice_call_leave_to_chat))
                                    }
                                    MinisButton(onClick = {
                                        controller.endCall()
                                        openCall()
                                    }) {
                                        Text(stringResource(R.string.voice_call_retry))
                                    }
                                }
                            }
                        }
                    }

                    // toast：悬在输入栏上方，2.5 秒后自行消失。
                    VoiceToast(toastText, state.toast?.seq) { controller.clearToast() }
                }
            }
            }
        }
    }
}

private const val CALL_PAGE_ENTER_MS = 240
private const val CALL_PAGE_EXIT_MS = 180

/** 文字记录版面：用户灰底气泡，助手纯文字；球缩小到底部，旁边是状态词。 */
@Composable
private fun TranscriptLayout(
    lines: List<CallLine>,
    statusLine: String,
    statusColor: Color,
    orb: @Composable () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier) {
        val listState = rememberLazyListState()
        LaunchedEffect(lines.size) {
            if (lines.isNotEmpty()) listState.animateScrollToItem(lines.lastIndex)
        }
        Box(Modifier.weight(1f).fillMaxWidth()) {
            if (lines.isEmpty()) {
                Text(
                    stringResource(R.string.voice_call_chat_empty),
                    style = MaterialTheme.typography.bodyMedium,
                    color = ChatColors.secondaryText,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.align(Alignment.Center).padding(24.dp),
                )
            } else {
                LazyColumn(
                    Modifier.fillMaxSize(),
                    state = listState,
                    contentPadding = PaddingValues(vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    items(lines) { line ->
                        if (line.fromUser) {
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                                Surface(
                                    shape = RoundedCornerShape(18.dp),
                                    color = MaterialTheme.colorScheme.surfaceVariant,
                                    modifier = Modifier.widthIn(max = 300.dp),
                                ) {
                                    Text(
                                        line.text,
                                        style = MaterialTheme.typography.bodyLarge,
                                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                                    )
                                }
                            }
                        } else {
                            Text(
                                line.text,
                                style = MaterialTheme.typography.bodyLarge,
                                modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp),
                            )
                        }
                    }
                }
            }
        }
        Row(
            Modifier.fillMaxWidth().padding(top = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center,
        ) {
            orb()
            Spacer(Modifier.width(8.dp))
            Text(statusLine, style = MaterialTheme.typography.bodyMedium, color = statusColor)
        }
    }
}

@Composable
private fun CallInputBar(
    draft: String,
    onDraft: (String) -> Unit,
    onSend: () -> Unit,
    muted: Boolean,
    onToggleMute: () -> Unit,
    onEnd: () -> Unit,
    images: List<InputAttachment>,
    canAttach: Boolean,
    onAttach: () -> Unit,
    onRemoveImage: (InputAttachment) -> Unit,
) {
    // 与聊天页输入框同一语言：左边圆角输入卡片（「＋」和文字同一行），右边静音圆钮和深色圆钮（有字是发送，否则挂断）。
    Column(Modifier.fillMaxWidth().padding(bottom = 4.dp)) {
        if (images.isNotEmpty()) {
            LazyRow(
                Modifier.padding(bottom = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(images, key = { it.id }) { image ->
                    Box(Modifier.size(64.dp)) {
                        AsyncImage(
                            model = image.uri,
                            contentDescription = image.fileName,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxSize().clip(RoundedCornerShape(12.dp)),
                        )
                        IconButton(
                            onClick = { onRemoveImage(image) },
                            modifier = Modifier.align(Alignment.TopEnd).size(24.dp),
                        ) {
                            Icon(
                                Icons.Rounded.Close,
                                contentDescription = stringResource(R.string.voice_call_attach_remove),
                                modifier = Modifier.size(16.dp).background(Color.Black.copy(alpha = 0.55f), CircleShape),
                                tint = Color.White,
                            )
                        }
                    }
                }
            }
        }
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Surface(
                modifier = Modifier.weight(1f),
                shape = RoundedCornerShape(26.dp),
                color = ChatColors.inputBg,
                shadowElevation = 3.dp,
                border = BorderStroke(0.5.dp, ChatColors.separator),
            ) {
                Row(Modifier.padding(start = 4.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (canAttach) {
                        IconButton(onClick = onAttach, enabled = images.size < MAX_CALL_IMAGES) {
                            Icon(Icons.Rounded.Add, contentDescription = stringResource(R.string.voice_call_attach), tint = ChatColors.primaryText)
                        }
                    }
                    TextField(
                        value = draft,
                        onValueChange = onDraft,
                        modifier = Modifier.weight(1f),
                        singleLine = true,
                        placeholder = { Text(stringResource(R.string.voice_call_input_hint)) },
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                        keyboardActions = KeyboardActions(onSend = { if (draft.isNotBlank()) onSend() }),
                        colors = TextFieldDefaults.colors(
                            focusedContainerColor = Color.Transparent,
                            unfocusedContainerColor = Color.Transparent,
                            disabledContainerColor = Color.Transparent,
                            focusedIndicatorColor = Color.Transparent,
                            unfocusedIndicatorColor = Color.Transparent,
                            disabledIndicatorColor = Color.Transparent,
                            focusedPlaceholderColor = ChatColors.secondaryText,
                            unfocusedPlaceholderColor = ChatColors.secondaryText,
                        ),
                    )
                }
            }
            FilledIconButton(
                onClick = onToggleMute,
                modifier = Modifier.size(52.dp),
                colors = IconButtonDefaults.filledIconButtonColors(
                    containerColor = if (muted) MaterialTheme.colorScheme.errorContainer else ChatColors.inputBg,
                    contentColor = if (muted) MaterialTheme.colorScheme.onErrorContainer else ChatColors.primaryText,
                ),
            ) {
                Icon(
                    if (muted) Icons.Rounded.MicOff else Icons.Rounded.Mic,
                    contentDescription = stringResource(if (muted) R.string.voice_call_unmute else R.string.voice_call_mute),
                )
            }
            val sending = draft.isNotBlank()
            FilledIconButton(
                onClick = if (sending) onSend else onEnd,
                modifier = Modifier.size(52.dp),
                colors = IconButtonDefaults.filledIconButtonColors(
                    containerColor = ChatColors.primaryText,
                    contentColor = ChatColors.inputBg,
                ),
            ) {
                Icon(
                    if (sending) Icons.AutoMirrored.Rounded.Send else Icons.Rounded.Close,
                    contentDescription = stringResource(if (sending) R.string.voice_call_send else R.string.voice_call_end),
                )
            }
        }
    }
}

private fun endedText(context: android.content.Context, ended: CallEnded): String = when (ended.reason) {
    CallEndReason.PERMISSION_DENIED -> context.getString(R.string.voice_call_permission)
    CallEndReason.RECOGNITION_FAILED ->
        context.getString(R.string.voice_call_recognition_failed, ended.detail.orEmpty())
    CallEndReason.TASK_FAILED -> taskIncompleteText(context, ended.detail)
    CallEndReason.IDLE_TIMEOUT -> context.getString(R.string.voice_call_ended_idle)
    CallEndReason.CONNECTION_FAILED -> context.getString(R.string.voice_call_ended_connection, ended.detail.orEmpty())
}

private fun taskIncompleteText(context: android.content.Context, detail: String?): String =
    if (detail.isNullOrBlank()) context.getString(R.string.voice_call_task_incomplete_generic)
    else context.getString(R.string.voice_call_task_incomplete, detail)

private fun toastText(context: android.content.Context, toast: CallToast): String = when (toast.kind) {
    CallNotice.RECOGNITION_RETRY -> context.getString(R.string.voice_call_toast_retry)
    CallNotice.TASK_FAILED -> taskIncompleteText(context, toast.detail)
    CallNotice.IDLE_REMINDER -> context.getString(R.string.voice_call_toast_idle)
}

@Composable
private fun BoxScope.VoiceToast(text: String?, seq: Long?, onGone: () -> Unit) {
    LaunchedEffect(seq) {
        if (seq != null) {
            delay(2500)
            onGone()
        }
    }
    AnimatedVisibility(
        visible = text != null,
        modifier = Modifier.align(Alignment.BottomCenter).systemBarsPadding().padding(bottom = 88.dp, start = 24.dp, end = 24.dp),
        enter = fadeIn(),
        exit = fadeOut(),
    ) {
        // exit 动画期间 text 已为 null，保留最后一句避免闪空。
        var last by remember { mutableStateOf(text.orEmpty()) }
        if (text != null) last = text
        Surface(
            shape = RoundedCornerShape(20.dp),
            color = MaterialTheme.colorScheme.inverseSurface,
            contentColor = MaterialTheme.colorScheme.inverseOnSurface,
        ) {
            Text(
                last,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                textAlign = TextAlign.Center,
            )
        }
    }
}

/** 通话计时 `mm:ss`。数字与冒号不随语言变化，所以不进资源。 */
private fun callClock(seconds: Long): String {
    val safe = seconds.coerceAtLeast(0L)
    return String.format(Locale.US, "%02d:%02d", safe / 60, safe % 60)
}
