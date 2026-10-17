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
@Composable
internal fun VoiceConversationHost(
    viewModel: ChatViewModel,
    sessionId: String?,
    /** Mirrors `ChatScreen(isChatDestination=…)`; deep-link actions require it. */
    fromChatRoute: Boolean,
    ensureMicPermission: suspend () -> Boolean,
    /** 口头切换任务：结束通话后把聊天切到该任务所在会话（和聊天里的会话跳转同一个入口）。 */
    onMoveToSession: (String) -> Unit = {},
    /** 通话状态的出口：聊天页读它来显示球、静音钮和结束钮；通话没开时是 null。 */
    callUi: MutableState<VoiceCallUi?>,
) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val scope = rememberCoroutineScope()
    val focusManager = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    val player = remember(viewModel) {
        lazy { ReadAloudPlayer(context.applicationContext).also { it.communicationUsage = VoiceOutputState.callEarpiece.value } }
    }
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
            // 通话用的就是聊天输入框：在「＋」里选好的图片和文件，随下一条提交的消息一起发出。
            takeAttachments = {
                val session = viewModel.agentSession
                session._attachments.value.also { session._attachments.value = emptyList() }
            },
            onTaskReported = callTasks::markReported,
        )
    }
    // 适配器先于 endAudio 创建，切换回调经这个句柄转过去。
    val switchSession = remember(viewModel) { arrayOf<(String) -> Unit>({}) }
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
    var orbMode by remember(viewModel) { mutableStateOf(true) }
    var opening by remember(viewModel) { mutableStateOf(false) }
    var requestVersion by remember(viewModel) { mutableIntStateOf(0) }
    var entryError by remember(viewModel) { mutableStateOf<String?>(null) }
    val pending by DeepLinkCoordinator.pendingChatAction.collectAsState()
    val lifecycleState by lifecycle.currentStateAsState()
    val active = controller.state.active
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
        orbMode = true
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
    LaunchedEffect(pending, lifecycleState, sessionId) {
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

    // 通话不再盖一层页面：聊天页自己就是通话页，这里只把状态交给它（球、静音、结束、顶栏设置）。
    val state = controller.state
    val characterState = state.effectiveBloubState
    val statusLine = when {
        entryError != null -> entryError.orEmpty()
        opening -> stringResource(R.string.voice_call_preparing)
        else -> stringResource(voiceCallStatusRes(characterState))
    }
    val toastText = state.toast?.let { toastText(context, it) }
    LaunchedEffect(state.toast?.seq) {
        if (toastText != null) {
            Toast.makeText(context, toastText, Toast.LENGTH_SHORT).show()
            controller.clearToast()
        }
    }
    val actions = remember(viewModel) { VoiceCallActions() }
    SideEffect {
        actions.toggleMute = { controller.setMuted(!controller.state.muted) }
        actions.end = { endAudio() }
        actions.retry = {
            controller.endCall()
            openCall()
        }
        actions.tapOrb = {
            if (controller.state.effectiveBloubState == BloubState.LISTENING) input.stop()
            else {
                controller.interrupt()
                controller.setMuted(false)
            }
        }
        actions.sendText = { controller.sendText(it) }
        actions.toggleOrb = { orbMode = !orbMode }
        val next = if (visible) {
            VoiceCallUi(
                active = active,
                muted = state.muted,
                characterState = characterState,
                statusLine = statusLine,
                statusIsError = characterState == BloubState.ERROR || entryError != null,
                failed = entryError != null && !active && !opening,
                orbMode = orbMode,
                actions = actions,
            )
        } else {
            null
        }
        if (callUi.value != next) callUi.value = next
    }
    DisposableEffect(callUi) { onDispose { callUi.value = null } }
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
