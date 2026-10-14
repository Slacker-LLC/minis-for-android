package com.openminis.app.voicecall

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
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
import com.openminis.app.ui.components.MinisTextButton
import com.openminis.app.ui.theme.ChatColors
import com.openminis.app.ui.theme.minisPageBackground
import com.openminis.app.voicecall.controller.CallController
import com.openminis.app.voicecall.grok.VoiceBotAvatar
import com.openminis.app.voicecall.grok.voiceCallStatusRes
import com.openminis.app.voicecall.model.BloubState
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
            texts = CallRuntimeTexts(
                listening = context.getString(R.string.voice_call_listening_hint),
                recognitionFailed = { detail ->
                    context.getString(R.string.voice_call_recognition_failed, detail)
                },
                taskIncomplete = { detail ->
                    if (detail.isNullOrBlank()) context.getString(R.string.voice_call_task_incomplete_generic)
                    else context.getString(R.string.voice_call_task_incomplete, detail)
                },
            ),
        )
    }
    val controller = remember(runtime) { CallController(runtime, scope) }
    var visible by remember(viewModel) { mutableStateOf(false) }
    var opening by remember(viewModel) { mutableStateOf(false) }
    var requestVersion by remember(viewModel) { mutableIntStateOf(0) }
    var entryError by remember(viewModel) { mutableStateOf<String?>(null) }
    val pending by DeepLinkCoordinator.pendingChatAction.collectAsState()
    val lifecycleState by lifecycle.currentStateAsState()
    val modelName by viewModel.modelName.collectAsState()
    val active = controller.state.active

    fun endAudio() {
        requestVersion++
        opening = false
        visible = false
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
                val characterState = controller.state.effectiveBloubState
                val statusLine = when {
                    entryError != null -> entryError.orEmpty()
                    opening -> stringResource(R.string.voice_call_preparing)
                    else -> stringResource(voiceCallStatusRes(characterState))
                }
                val detailLine = if (active) controller.state.ai.subtitle else stringResource(R.string.voice_call_hint)
                Column(
                    Modifier
                        .fillMaxSize()
                        .systemBarsPadding()
                        .padding(horizontal = 20.dp, vertical = 16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    // 顶栏：只在通话进行时给计时，失败/准备中不显示无意义的 00:00。
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text(stringResource(R.string.voice_call_title), style = MaterialTheme.typography.titleMedium)
                        Spacer(Modifier.weight(1f))
                        if (active) {
                            Text(
                                callClock(controller.state.elapsedSeconds),
                                style = MaterialTheme.typography.labelLarge,
                                color = ChatColors.secondaryText,
                            )
                        }
                    }
                    Text(
                        modelName.ifBlank { context.getString(R.string.voice_call_model) },
                        style = MaterialTheme.typography.bodySmall,
                        color = ChatColors.secondaryText,
                        modifier = Modifier.fillMaxWidth(),
                    )

                    // 中部：角色居中，下面是状态词与最近一句字幕（长回答截断，全文在聊天里看）。
                    Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                        Column(
                            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()),
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            VoiceBotAvatar(characterState, Modifier.size(180.dp))
                            Spacer(Modifier.height(20.dp))
                            Text(
                                statusLine,
                                style = MaterialTheme.typography.headlineSmall,
                                textAlign = TextAlign.Center,
                                color = if (characterState == BloubState.ERROR) MaterialTheme.colorScheme.error else Color.Unspecified,
                            )
                            if (detailLine.isNotBlank()) {
                                Text(
                                    detailLine,
                                    style = MaterialTheme.typography.bodyLarge,
                                    color = ChatColors.secondaryText,
                                    textAlign = TextAlign.Center,
                                    maxLines = 4,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.padding(top = 10.dp),
                                )
                            }
                        }
                    }

                    TaskWorkSummary(viewModel.agentSession.activeSessionId)

                    // 底部：一行一个主操作（设计语言：整页最多一个强调按钮），
                    // 其余行是可换行的文字按钮，最后一行始终留给离开/挂断。
                    if (active) {
                        val primary = when {
                            controller.state.muted -> R.string.voice_call_unmute to { controller.setMuted(false) }
                            characterState == BloubState.LISTENING -> R.string.voice_call_submit to { input.stop() }
                            characterState == BloubState.ERROR -> R.string.voice_call_retry to { controller.interrupt(); controller.setMuted(false) }
                            else -> R.string.voice_call_interrupt to { controller.interrupt(); controller.setMuted(false) }
                        }
                        Row(
                            Modifier.fillMaxWidth().padding(top = 8.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            if (!controller.state.muted) {
                                MinisOutlinedButton(onClick = { controller.setMuted(true) }) {
                                    Text(stringResource(R.string.voice_call_mute))
                                }
                            }
                            MinisButton(onClick = primary.second) { Text(stringResource(primary.first)) }
                        }
                        FlowRow(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(4.dp, Alignment.CenterHorizontally),
                        ) {
                            if (characterState == BloubState.THINKING || characterState == BloubState.SPEAKING) {
                                MinisTextButton(onClick = {
                                    controller.listenForFollowUp()
                                    controller.setMuted(false)
                                }) { Text(stringResource(R.string.voice_call_follow_up)) }
                            }
                            MinisTextButton(
                                onClick = { input.cancel(); controller.setMuted(true) },
                                enabled = characterState == BloubState.LISTENING && !controller.state.muted,
                            ) { Text(stringResource(R.string.voice_cancel_recording)) }
                            MinisTextButton(onClick = { if (player.isInitialized()) player.value.stop() }) {
                                Text(stringResource(R.string.voice_stop_playback))
                            }
                            MinisTextButton(onClick = { gateway.cancel() }) {
                                Text(stringResource(R.string.voice_stop_owned_turn))
                            }
                        }
                    } else {
                        MinisButton(onClick = { openCall() }, enabled = !opening) { Text(stringResource(R.string.voice_call_retry)) }
                    }
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        // 「查看聊天」是权限确认被本页遮住时的出口；两个入口都结束音频，
                        // 已接受的 Agent 任务继续在原会话跑。
                        MinisTextButton(onClick = { endAudio() }) { Text(stringResource(R.string.voice_call_back)) }
                        Spacer(Modifier.weight(1f))
                        MinisOutlinedButton(onClick = { endAudio() }) { Text(stringResource(R.string.voice_call_end)) }
                    }
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
