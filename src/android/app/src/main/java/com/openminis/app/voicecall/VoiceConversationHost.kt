package com.openminis.app.voicecall

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.CallEnd
import androidx.compose.material.icons.rounded.ChatBubbleOutline
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.MicOff
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.currentStateAsState
import com.openminis.app.agent.ensureSessionForSettings
import com.openminis.app.R
import com.openminis.app.data.repository.ensureDefaultVoiceInputSlot
import com.openminis.app.data.repository.ensureDefaultVoiceOutputSlot
import com.openminis.app.deeplink.DeepLinkCoordinator
import com.openminis.app.speech.ReadAloudPlayer
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
import com.openminis.app.voicecall.model.CallToast
import com.openminis.app.voicecall.model.CallMode
import com.openminis.app.voicecall.runtime.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.Locale

/** 所有入口绑定当前聊天 VM；权限确认仍由 ChatScreen 的原有 Dialog 承接。 */
@Composable
internal fun VoiceConversationHost(
    viewModel: ChatViewModel,
    sessionId: String?,
    /** Mirrors `ChatScreen(isChatDestination=…)`; deep-link actions require it. */
    fromChatRoute: Boolean,
    ensureMicPermission: suspend () -> Boolean,
) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val scope = rememberCoroutineScope()
    val player = remember(viewModel) { lazy { ReadAloudPlayer(context.applicationContext) } }
    val gateway = remember(viewModel) { AgentCallGateway(viewModel.agentSession) }
    val input = remember(viewModel) { AgentCallSpeechInput(viewModel.providerRepository) }
    val runtime = remember(viewModel) {
        MinisCallRuntimeAdapter(
            input,
            gateway,
            AgentCallSpeechOutput(player),
            scope,
        )
    }
    val controller = remember(runtime) { CallController(runtime, scope) }
    var visible by remember(viewModel) { mutableStateOf(false) }
    var opening by remember(viewModel) { mutableStateOf(false) }
    var requestVersion by remember(viewModel) { mutableIntStateOf(0) }
    var entryError by remember(viewModel) { mutableStateOf<String?>(null) }
    var chatOpen by remember(viewModel) { mutableStateOf(false) }
    val pending by DeepLinkCoordinator.pendingChatAction.collectAsState()
    val lifecycleState by lifecycle.currentStateAsState()
    val modelName by viewModel.modelName.collectAsState()
    val active = controller.state.active

    fun endAudio() {
        requestVersion++
        opening = false
        visible = false
        chatOpen = false
        controller.endCall()
        viewModel.voiceConversationActive.value = false
    }

    fun openCall() {
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
                com.openminis.app.ui.chat.voice.VoiceModePrefs.isVoiceActive = false
                viewModel.replyPlayer?.stop()
                val sid = viewModel.agentSession.ensureSessionForSettings()
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
            DeepLinkCoordinator.consumePendingChatAction()
            openCall()
        }
    }
    // runtime 自动挂断（空闲/连续出错）后音频已停，只需把聊天页的「语音占用」标记放掉。
    val ended = controller.state.ended
    LaunchedEffect(ended) {
        if (ended != null) viewModel.voiceConversationActive.value = false
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
                // 前台交互第一版：切后台/锁屏立即暂停，不通过 Root 偷开麦克风。
                requestVersion++
                opening = false
                controller.setMuted(true)
            }
        }
        lifecycle.addObserver(observer)
        onDispose {
            lifecycle.removeObserver(observer)
            endAudio()
            if (player.isInitialized()) player.value.shutdown()
        }
    }

    if (visible) {
        Dialog(onDismissRequest = { endAudio() }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
            Surface(Modifier.fillMaxSize(), color = minisPageBackground(ChatColors.isDark)) {
                val state = controller.state
                val characterState = state.effectiveBloubState
                val statusLine = when {
                    entryError != null -> entryError.orEmpty()
                    opening -> stringResource(R.string.voice_call_preparing)
                    state.ended != null -> stringResource(R.string.voice_call_ended_title)
                    else -> stringResource(voiceCallStatusRes(characterState))
                }
                val toastText = state.toast?.let { toastText(context, it) }
                Box(Modifier.fillMaxSize()) {
                    Column(
                        Modifier
                            .fillMaxSize()
                            .systemBarsPadding()
                            .padding(horizontal = 20.dp, vertical = 12.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        // 顶栏：返回（结束音频）、计时、聊天记录。没有别的文字入口。
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            IconButton(onClick = { endAudio() }) {
                                Icon(
                                    Icons.AutoMirrored.Rounded.ArrowBack,
                                    contentDescription = stringResource(R.string.voice_call_leave_to_chat),
                                )
                            }
                            Spacer(Modifier.weight(1f))
                            if (active) {
                                Text(
                                    callClock(state.elapsedSeconds),
                                    style = MaterialTheme.typography.labelLarge,
                                    color = ChatColors.secondaryText,
                                )
                            }
                            Spacer(Modifier.weight(1f))
                            IconButton(onClick = { chatOpen = true }) {
                                Icon(
                                    Icons.Rounded.ChatBubbleOutline,
                                    contentDescription = stringResource(R.string.voice_call_back),
                                )
                            }
                        }

                        // 中部：只有角色和一个状态词。点角色：听的时候是「现在发送」，
                        // 想/说/出错时是「打断并重新说」。
                        Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                val orbLabel = stringResource(
                                    if (characterState == BloubState.LISTENING) R.string.voice_call_submit
                                    else R.string.voice_call_interrupt,
                                )
                                VoiceBotAvatar(
                                    if (state.ended != null) BloubState.MUTED else characterState,
                                    Modifier
                                        .size(240.dp)
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
                                Spacer(Modifier.height(16.dp))
                                Text(
                                    statusLine,
                                    style = MaterialTheme.typography.titleMedium,
                                    textAlign = TextAlign.Center,
                                    color = if (characterState == BloubState.ERROR || entryError != null) {
                                        MaterialTheme.colorScheme.error
                                    } else {
                                        ChatColors.secondaryText
                                    },
                                )
                                state.ended?.let { EndedDetail(it) }
                            }
                        }

                        TaskWorkSummary(viewModel.agentSession.activeSessionId)

                        // 底部：两个圆形按钮。静音只暂停音频，通话还在；挂断结束音频，
                        // 已接受的 Agent 任务继续在原会话跑。
                        Box(Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 16.dp), contentAlignment = Alignment.Center) {
                            when {
                                active -> Row(horizontalArrangement = Arrangement.spacedBy(48.dp)) {
                                    FilledIconButton(
                                        onClick = { controller.setMuted(!state.muted) },
                                        modifier = Modifier.size(68.dp),
                                        colors = IconButtonDefaults.filledIconButtonColors(
                                            containerColor = if (state.muted) MaterialTheme.colorScheme.surfaceVariant
                                            else MaterialTheme.colorScheme.primaryContainer,
                                            contentColor = if (state.muted) MaterialTheme.colorScheme.onSurfaceVariant
                                            else MaterialTheme.colorScheme.onPrimaryContainer,
                                        ),
                                    ) {
                                        Icon(
                                            if (state.muted) Icons.Rounded.MicOff else Icons.Rounded.Mic,
                                            contentDescription = stringResource(
                                                if (state.muted) R.string.voice_call_unmute else R.string.voice_call_mute,
                                            ),
                                            modifier = Modifier.size(30.dp),
                                        )
                                    }
                                    FilledIconButton(
                                        onClick = { endAudio() },
                                        modifier = Modifier.size(68.dp),
                                        colors = IconButtonDefaults.filledIconButtonColors(
                                            containerColor = MaterialTheme.colorScheme.error,
                                            contentColor = MaterialTheme.colorScheme.onError,
                                        ),
                                    ) {
                                        Icon(
                                            Icons.Rounded.CallEnd,
                                            contentDescription = stringResource(R.string.voice_call_end),
                                            modifier = Modifier.size(30.dp),
                                        )
                                    }
                                }
                                opening -> Unit
                                else -> Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                    // 结束页（或入口失败）：回聊天 / 重新通话。聊天里能看到权限确认等细节。
                                    MinisOutlinedButton(onClick = { endAudio() }) {
                                        Text(stringResource(R.string.voice_call_leave_to_chat))
                                    }
                                    MinisButton(onClick = {
                                        controller.endCall()
                                        openCall()
                                    }) {
                                        Text(
                                            stringResource(
                                                if (state.ended != null) R.string.voice_call_restart else R.string.voice_call_retry,
                                            ),
                                        )
                                    }
                                }
                            }
                        }
                    }

                    // toast：悬在按钮上方，2.5 秒后自行消失。
                    VoiceToast(toastText, state.toast?.seq) { controller.clearToast() }

                    VoiceChatPanel(
                        visible = chatOpen,
                        lines = state.transcript,
                        onClose = { chatOpen = false },
                        onLeave = { endAudio() },
                    )
                }
            }
        }
    }
}

@Composable
private fun EndedDetail(ended: CallEnded) {
    val context = LocalContext.current
    val reason = when (ended.reason) {
        CallEndReason.PERMISSION_DENIED -> context.getString(R.string.voice_call_permission)
        CallEndReason.RECOGNITION_FAILED ->
            context.getString(R.string.voice_call_recognition_failed, ended.detail.orEmpty())
        CallEndReason.TASK_FAILED -> taskIncompleteText(context, ended.detail)
        CallEndReason.IDLE_TIMEOUT -> context.getString(R.string.voice_call_ended_idle)
    }
    Column(
        Modifier.padding(top = 8.dp).widthIn(max = 320.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(reason, style = MaterialTheme.typography.bodyMedium, color = ChatColors.secondaryText, textAlign = TextAlign.Center)
        Text(
            stringResource(R.string.voice_call_ended_footer),
            style = MaterialTheme.typography.bodySmall,
            color = ChatColors.secondaryText,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 8.dp),
        )
    }
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
        modifier = Modifier.align(Alignment.BottomCenter).systemBarsPadding().padding(bottom = 128.dp, start = 24.dp, end = 24.dp),
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

/** 聊天记录抽屉：本次通话里说过的话，底部「返回聊天」是权限确认被本页遮住时的出口。 */
@Composable
private fun BoxScope.VoiceChatPanel(
    visible: Boolean,
    lines: List<com.openminis.app.voicecall.model.CallLine>,
    onClose: () -> Unit,
    onLeave: () -> Unit,
) {
    AnimatedVisibility(
        visible = visible,
        modifier = Modifier.align(Alignment.BottomCenter),
        enter = slideInVertically { it } + fadeIn(),
        exit = slideOutVertically { it } + fadeOut(),
    ) {
        Surface(
            Modifier.fillMaxWidth().fillMaxHeight(0.85f),
            shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 3.dp,
            shadowElevation = 12.dp,
        ) {
            Column(Modifier.navigationBarsPadding().padding(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.voice_call_chat_title), style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.weight(1f))
                    IconButton(onClick = onClose) {
                        Icon(Icons.Rounded.Close, contentDescription = stringResource(R.string.voice_call_chat_close))
                    }
                }
                val listState = rememberLazyListState()
                LaunchedEffect(lines.size) {
                    if (lines.isNotEmpty()) listState.animateScrollToItem(lines.lastIndex)
                }
                if (lines.isEmpty()) {
                    Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                        Text(
                            stringResource(R.string.voice_call_chat_empty),
                            style = MaterialTheme.typography.bodyMedium,
                            color = ChatColors.secondaryText,
                            textAlign = TextAlign.Center,
                        )
                    }
                } else {
                    LazyColumn(
                        Modifier.weight(1f).fillMaxWidth(),
                        state = listState,
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        items(lines) { line ->
                            Row(
                                Modifier.fillMaxWidth(),
                                horizontalArrangement = if (line.fromUser) Arrangement.End else Arrangement.Start,
                            ) {
                                Surface(
                                    shape = RoundedCornerShape(16.dp),
                                    color = if (line.fromUser) MaterialTheme.colorScheme.primaryContainer
                                    else MaterialTheme.colorScheme.surfaceVariant,
                                    modifier = Modifier.widthIn(max = 300.dp),
                                ) {
                                    Text(
                                        line.text,
                                        style = MaterialTheme.typography.bodyMedium,
                                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                                    )
                                }
                            }
                        }
                    }
                }
                MinisOutlinedButton(onClick = onLeave, modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
                    Text(stringResource(R.string.voice_call_leave_to_chat))
                }
            }
        }
    }
}

/** 通话计时 `mm:ss`。数字与冒号不随语言变化，所以不进资源。 */
private fun callClock(seconds: Long): String {
    val safe = seconds.coerceAtLeast(0L)
    return String.format(Locale.US, "%02d:%02d", safe / 60, safe % 60)
}
