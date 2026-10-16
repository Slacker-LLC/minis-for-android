package com.openminis.app.voicecall.runtime

import com.openminis.app.agent.AgentTurnHandle
import com.openminis.app.agent.AgentTurnOutcome
import com.openminis.app.agent.AgentRunner
import com.openminis.app.data.repository.ProviderRepository
import com.openminis.app.data.repository.resolveVoiceInputChoice
import com.openminis.app.speech.ReadAloudPlayer
import com.openminis.app.speech.SpeechRecognitionManager
import com.openminis.app.speech.SystemSpeechRecognitionEngine
import com.openminis.app.agent.AgentSession
import com.openminis.app.agent.enqueueVoicePrompt
import com.openminis.app.voicecall.ports.ApprovalDecision
import com.openminis.app.voicecall.ports.ApprovalPrompt
import com.openminis.app.voicecall.ports.CallApprovals
import com.openminis.app.voicecall.ports.CallModelGateway
import com.openminis.app.voicecall.ports.CallSpeechInput
import com.openminis.app.voicecall.ports.CallSpeechOutput
import com.openminis.app.voicecall.ports.RecognitionError
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import com.openminis.app.offload.OffloadPermissionManager

internal class AgentCallGateway(
    private val session: AgentSession,
    /** 通话里选好的图片：随下一条真正提交的消息发出；并入正在跑的任务（补充指令）时不带，留给下一轮。 */
    private val takeAttachments: () -> List<com.openminis.app.ui.chat.InputAttachment> = { emptyList() },
) : CallModelGateway {
    private var ownedTurn: AgentTurnHandle? = null

    override suspend fun ask(text: String): Result<String> = withContext(Dispatchers.Main.immediate) {
        try {
            withTimeout(5_000L) {
                session.sessionLoaded.first { it }
                while (session.currentProvider == null) delay(50)
            }
            if (session.isStreaming.value) {
                val id = session.enqueueVoicePrompt(text)
                val row = session.chatRepository.dao.getMessageById(id)
                return@withContext Result.success(if (row?.requestState == "NEEDS_REVIEW")
                    "补充指令已保存；当前回合已经结束，请在聊天中确认投递方式。" else "补充指令已保存，会在当前任务的下一步读取。")
            }
            val turn = AgentRunner.submit(session, text, takeAttachments(), userInput = true)
            ownedTurn = turn
            try {
                when (val outcome = turn.result.await()) {
                    AgentTurnOutcome.Completed -> {
                        val reply = AgentRunner.responseAfter(session.context, session.currentSessionId, turn.userMessageId)?.trim()
                        val row = turn.userMessageId?.let { session.chatRepository.dao.getMessageById(it) }
                        val app = session.context.applicationContext as com.openminis.app.MinisApp
                        val task = row?.requestTaskId?.let { app.agentTaskRepository.get(it) }
                        val brief = if (task == null) reply?.take(1000)?.takeIf { it.isNotBlank() }
                            ?: "本轮已结束，请在聊天中查看结果。"
                        else buildString {
                            val label = session.context.getString(when (task.status) {
                                com.openminis.app.data.db.AgentTaskEntity.STATUS_COMPLETED -> com.openminis.app.R.string.agent_task_completed
                                com.openminis.app.data.db.AgentTaskEntity.STATUS_PAUSED -> com.openminis.app.R.string.agent_task_paused
                                com.openminis.app.data.db.AgentTaskEntity.STATUS_NEEDS_USER -> com.openminis.app.R.string.agent_task_needs_user
                                com.openminis.app.data.db.AgentTaskEntity.STATUS_WAITING -> com.openminis.app.R.string.work_task_waiting
                                com.openminis.app.data.db.AgentTaskEntity.STATUS_CANCELLED -> com.openminis.app.R.string.agent_task_cancelled
                                com.openminis.app.data.db.AgentTaskEntity.STATUS_BUDGET_EXHAUSTED -> com.openminis.app.R.string.agent_task_budget_exhausted
                                com.openminis.app.data.db.AgentTaskEntity.STATUS_FAILED -> com.openminis.app.R.string.agent_task_failed
                                else -> com.openminis.app.R.string.agent_task_active
                            })
                            append(label).append("。")
                            if (task.progress.isNotBlank()) append(task.progress.take(600))
                            else if (!reply.isNullOrBlank()) append(reply.take(600))
                            if (task.status == com.openminis.app.data.db.AgentTaskEntity.STATUS_COMPLETED && task.completionEvidence != null)
                                append(" 已记录验收结果，可在聊天的成果入口核查和打开。")
                        }
                        Result.success(brief)
                    }
                    is AgentTurnOutcome.NeedsAttention -> Result.failure(IllegalStateException("请返回聊天处理确认：${outcome.reason}"))
                    is AgentTurnOutcome.Failed -> Result.failure(IllegalStateException(outcome.reason))
                    is AgentTurnOutcome.Rejected -> Result.failure(IllegalStateException("请返回聊天查看：${outcome.reason}"))
                    AgentTurnOutcome.Cancelled -> Result.failure(IllegalStateException("任务已停止"))
                }
            } finally {
                if (ownedTurn === turn && turn.result.isCompleted) ownedTurn = null
            }
        } catch (timeout: TimeoutCancellationException) {
            Result.failure(IllegalStateException("请先在聊天中配置可用的对话模型。"))
        } catch (cancelled: CancellationException) {
            // 挂断/后台释放的是音频等待；任务的寿命仍由原会话管理。
            throw cancelled
        } catch (error: Exception) {
            Result.failure(error)
        }
    }

    override fun cancel() { ownedTurn?.cancel() }
}

internal class AgentCallSpeechInput(private val providers: ProviderRepository) : CallSpeechInput {
    private var capturing = false
    override fun start(onPartial: (String) -> Unit, onFinal: (String) -> Unit, onError: (RecognitionError, String?) -> Unit) {
        if (SpeechRecognitionManager.state.value != com.openminis.app.speech.RecognitionState.IDLE) {
            onError(RecognitionError.ENGINE, "麦克风正在被其它语音输入使用")
            return
        }
        val choice = providers.resolveVoiceInputChoice()
        SpeechRecognitionManager.selectEngine(if (choice.isSystem) "system" else "provider")
        (SpeechRecognitionManager.availableEngines().firstOrNull { it.id == "system" } as? SystemSpeechRecognitionEngine)
            ?.preferOffline = choice.systemPreferOffline == true
        capturing = true
        SpeechRecognitionManager.startRecording(
            onPartialOrFinal = { text, final ->
                if (final) {
                    capturing = false
                    onFinal(text)
                } else onPartial(text)
            },
            onError = { error, message ->
                capturing = false
                val mapped = when (error) {
                    com.openminis.app.speech.RecognitionError.NO_MATCH -> RecognitionError.NO_MATCH
                    com.openminis.app.speech.RecognitionError.PERMISSION_DENIED -> RecognitionError.PERMISSION_DENIED
                    com.openminis.app.speech.RecognitionError.NETWORK -> RecognitionError.NETWORK
                    else -> RecognitionError.ENGINE
                }
                onError(mapped, message ?: error.name)
            },
            conversation = true,
        )
    }

    override fun stop() { if (capturing) SpeechRecognitionManager.stopRecording() }
    override fun cancel() {
        if (capturing) {
            capturing = false
            SpeechRecognitionManager.cancelRecording()
        }
    }
}

internal class AgentCallSpeechOutput(private val player: Lazy<ReadAloudPlayer>) : CallSpeechOutput {
    override suspend fun speak(text: String) {
        player.value.speakConversation(text)
        player.value.isSpeaking.first { !it }
    }

    override fun stop() { if (player.isInitialized()) player.value.stop() }
}

/**
 * 把「此通话所属会话」里的权限请求变成可口头回答的问题。别的会话的请求不接，
 * 也不会被通话里的一句话放行。
 */
internal class AgentCallApprovals(
    private val context: android.content.Context,
    private val sessionId: () -> String?,
    scope: CoroutineScope,
) : CallApprovals {
    override val pending: StateFlow<ApprovalPrompt?> = OffloadPermissionManager.pendingRequest
        .map { request ->
            request?.takeIf { it.sessionId == sessionId() }?.let {
                ApprovalPrompt(
                    question = context.getString(com.openminis.app.R.string.voice_call_approval_question, it.toolTitle),
                    unclear = context.getString(com.openminis.app.R.string.voice_call_approval_unclear),
                )
            }
        }
        .stateIn(scope, SharingStarted.Eagerly, null)

    override fun respond(decision: ApprovalDecision) {
        OffloadPermissionManager.respondToRequest(
            when (decision) {
                ApprovalDecision.ALLOW_ONCE -> OffloadPermissionManager.Response.ALLOW_ONCE
                ApprovalDecision.ALLOW_SESSION -> OffloadPermissionManager.Response.ALLOW_SESSION
                ApprovalDecision.DENY -> OffloadPermissionManager.Response.DENY_SESSION
            },
        )
    }
}
