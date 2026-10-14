package com.openminis.app.voicecall

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.openminis.app.agent.ensureSessionForSettings
import com.openminis.app.R
import com.openminis.app.data.repository.ensureDefaultVoiceInputSlot
import com.openminis.app.data.repository.ensureDefaultVoiceOutputSlot
import com.openminis.app.deeplink.DeepLinkCoordinator
import com.openminis.app.speech.ReadAloudPlayer
import com.openminis.app.speech.SpeechRecognitionManager
import com.openminis.app.ui.chat.ChatViewModel
import com.openminis.app.ui.components.MinisButton
import com.openminis.app.ui.components.MinisOutlinedButton
import com.openminis.app.voicecall.bloub.BloubAvatar
import com.openminis.app.voicecall.controller.CallController
import com.openminis.app.voicecall.model.BloubSize
import com.openminis.app.voicecall.model.BloubState
import com.openminis.app.voicecall.model.CallMode
import com.openminis.app.voicecall.runtime.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** 所有入口绑定当前聊天 VM；权限确认仍由 ChatScreen 的原有 Dialog 承接。 */
@Composable
internal fun VoiceConversationHost(
    viewModel: ChatViewModel,
    ensureMicPermission: suspend () -> Boolean,
) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val scope = rememberCoroutineScope()
    val player = remember(viewModel) { lazy { ReadAloudPlayer(context.applicationContext) } }
    val gateway = remember(viewModel) { AgentCallGateway(viewModel.agentSession) }
    val input = remember(viewModel) { AgentCallSpeechInput(viewModel.providerRepository) }
    val runtime = remember(viewModel) {
        MinisCallRuntimeAdapter(input, gateway, AgentCallSpeechOutput(player), scope)
    }
    val controller = remember(runtime) { CallController(runtime, scope) }
    var visible by remember(viewModel) { mutableStateOf(false) }
    var opening by remember(viewModel) { mutableStateOf(false) }
    var requestVersion by remember(viewModel) { mutableIntStateOf(0) }
    var entryError by remember(viewModel) { mutableStateOf<String?>(null) }
    val pending by DeepLinkCoordinator.pendingChatAction.collectAsState()
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

    LaunchedEffect(pending) {
        if (pending == DeepLinkCoordinator.ChatAction.START_VOICE) {
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
            Surface(Modifier.fillMaxSize()) {
                Column(
                    Modifier.systemBarsPadding().padding(24.dp).verticalScroll(rememberScrollState()),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(20.dp),
                ) {
                    Text(stringResource(R.string.voice_call_title), style = MaterialTheme.typography.headlineMedium)
                    Text(modelName.ifBlank { context.getString(R.string.voice_call_model) })
                    com.openminis.app.ui.chat.TaskWorkSummary(viewModel.agentSession.activeSessionId)
                    BloubAvatar(controller.state.effectiveBloubState, BloubSize.LARGE)
                    Text(
                        when {
                            opening -> stringResource(R.string.voice_call_preparing)
                            entryError != null -> entryError.orEmpty()
                            controller.state.muted -> stringResource(R.string.voice_call_paused)
                            else -> controller.state.ai.subtitle
                        },
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Text(stringResource(R.string.voice_call_hint), style = MaterialTheme.typography.bodyMedium)
                    if (active) {
                        if (controller.state.ai.bloubState == BloubState.THINKING || controller.state.ai.bloubState == BloubState.SPEAKING) {
                            TextButton(onClick = {
                                controller.listenForFollowUp()
                                controller.setMuted(false)
                            }) { Text(stringResource(R.string.voice_call_follow_up)) }
                        }
                        TextButton(onClick = { input.cancel(); controller.setMuted(true) }, enabled = controller.state.ai.bloubState == BloubState.LISTENING) { Text(stringResource(R.string.voice_cancel_recording)) }
                        TextButton(onClick = { if (player.isInitialized()) player.value.stop() }) { Text(stringResource(R.string.voice_stop_playback)) }
                        TextButton(onClick = { gateway.cancel() }) { Text(stringResource(R.string.voice_stop_owned_turn)) }
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            MinisOutlinedButton(onClick = { controller.setMuted(!controller.state.muted) }) {
                                Text(stringResource(if (controller.state.muted) R.string.voice_call_unmute else R.string.voice_call_mute))
                            }
                            MinisButton(onClick = {
                                controller.interrupt()
                                controller.setMuted(false)
                            }) {
                                Text(stringResource(if (controller.state.ai.bloubState == BloubState.ERROR) R.string.voice_call_retry else R.string.voice_call_interrupt))
                            }
                        }
                        if (controller.state.ai.bloubState == BloubState.LISTENING && !controller.state.muted) {
                            TextButton(onClick = { input.stop() }) { Text(stringResource(R.string.voice_call_submit)) }
                        }
                    } else {
                        MinisButton(onClick = { openCall() }, enabled = !opening) { Text(stringResource(R.string.voice_call_retry)) }
                    }
                    TextButton(onClick = { endAudio() }) { Text(stringResource(R.string.voice_call_back)) }
                    MinisOutlinedButton(onClick = { endAudio() }) { Text(stringResource(R.string.voice_call_end)) }
                }
            }
        }
    }
}
