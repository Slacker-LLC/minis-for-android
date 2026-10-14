# 语音运行链

中文摘要：语音输入经系统或 Provider ASR 转成文字，复用现有 Chat/Pet 模型链，再由系统或 Provider TTS 播放；不另建 Runtime。2026-10-12 工作树已接入语音对话：聊天页声波按钮、语音快捷方式和系统助理入口共用 VoiceConversationHost，直接提交当前会话完整 Agent 回合。2026-09-12 小米实测分别取得 MiMo 文本、独立 MiMo TTS 请求与 Xiaomi 系统朗读证据，三者不能混写为同一个出口。完整 ASR、打断、通话与 Pi 语音矩阵仍待验收，见[实测报告](REAL-DEVICE-TEST-REPORT.md)。下文保留组件/API 明细。

This document describes the current speech-recognition, model, TTS, and voice-entry paths inside the Android application. It is not a separate APK or a second agent runtime.

## Pipeline

```text
microphone
  ↓
ASR
  ↓
text
  ↓
model / agent path
  ↓
assistant text
  ↓
TTS
  ↓
audio playback
```

## Main components

| Area | Primary code | Responsibility |
|---|---|---|
| ASR abstraction | `speech/SpeechRecognitionEngine.kt` | engine contract, state, errors |
| ASR orchestration | `speech/SpeechRecognitionManager.kt` | engine selection and recording lifecycle |
| System ASR | `speech/SystemSpeechRecognitionEngine.kt` | Android `SpeechRecognizer`, VAD, segmentation |
| Provider ASR | `speech/ProviderSpeechRecognitionEngine.kt` | recording, WAV packaging, provider transcription |
| Voice provider protocol | `provider/voice/VoiceProvider.kt` | OpenAI-compatible ASR/TTS requests |
| Voice provider factory | `provider/voice/VoiceProviderFactory.kt` | provider/base-URL adapter selection |
| Vendor adapters | `provider/voice/VoiceProviderVendors.kt` | provider-specific request differences |
| Voice model configuration | `data/repository/ProviderRepository.kt` | Voice Input/Output group resolution |
| Read-aloud orchestration | `speech/ReadAloudPlayer.kt` | provider audio playback, queueing, fallback |
| System TTS | `speech/TextToSpeechManager.kt` | Android `TextToSpeech` |
| Pet model path | `pet/PetChatEngine.kt` | short conversational model requests |
| Pet voice entry | `pet/PetOverlayService.kt` | connects ASR, model, and TTS |
| Inline chat voice input | `ui/chat/voice/InlineVoiceInputPanel.kt` | writes ASR results into chat input |

## ASR selection

Voice Input is resolved from provider/model configuration.

- system sentinel -> `SystemSpeechRecognitionEngine`;
- provider model -> `ProviderSpeechRecognitionEngine`;
- provider ASR generally returns final transcription after a segment completes;
- system ASR may expose partial results;
- `SpeechRecognitionManager` owns the unified recording state.

`RECORD_AUDIO` permission must be granted before microphone capture starts.

When recording begins, active read-aloud playback should pause so speaker audio is not fed back into recognition.

## Model paths

### Desktop pet

`PetChatEngine` uses the selected/default model for short conversational responses and stores the exchange in the pet session. It intentionally does not run the full tool-heavy agent loop for every pet utterance.

### Main chat

`ChatViewModel` owns the full chat/agent path: streaming, tools, persistence, and session lifecycle.

Voice features should integrate with these existing paths instead of creating a duplicate headless provider/session runtime.

## TTS

`ReadAloudPlayer.speakConversation(text)` is the voice-conversation output path.

Typical flow:

1. resolve Voice Output group;
2. choose system TTS or an online provider;
3. for provider TTS, synthesize audio bytes;
4. play cached audio through the media stack;
5. fall back to system TTS when the provider/audio path fails according to policy.

Conversation speech should respect temporary mute and microphone echo-protection state, but it should not depend on whether the user enabled automatic read-aloud for ordinary chat replies.

## Reading a reply or a selection aloud (chat)

There is one read-aloud pipeline in the chat. `ChatViewModel.startReplySpeech(messageId, displayIndex, text)` starts a
reply; `readSelectionAloud(text)` starts the text selected in a reply through the same function (message id
`selection`). Both drive `ReplySpeechState`, and `ui/chat/ReplySpeechControlBar.kt` is the only control surface: the
title ("AI reply N", "Selected text" or "Read aloud replies"), a status line, pause, close, the voice chip (opens the
voice picker), the speed chip, mute and a progress bar. The floating speaker button and the second player capsule of
earlier builds were removed.

- The bar shows for a reading you started and for automatic read-aloud while it is speaking or synthesising.
- Close ends the reading that was started by hand. In automatic mode it turns "read replies aloud" off, because
  stopping one sentence of an automatic reading would only be followed by the next.
- The selection toolbar offers **Copy** (with a menu for Markdown, rich text and plain text), **Read aloud** and
  **Quote**; the older reply menu entries for these were removed.

## Desktop-pet voice flow

```text
user activates pet voice
  ↓
resolve Voice Input
  ↓
ASR
  ↓ final text
PetChatEngine.ask
  ↓
ReadAloudPlayer.speakConversation
```

The pet path and the full chat Agent Loop intentionally remain separate interaction modes while sharing provider, speech, and persistence infrastructure.

## Runtime design rule

Do not create a second voice-specific source of truth for providers, models, sessions, or permissions. Voice code should consume the same `ProviderRepository` and runtime state used by the rest of the app.

Call-style state machines wrap existing production classes behind the existing speech/model/output ports. End-to-end verification exercises the real Agent Loop, Room and tool dispatch with scripted acoustic boundaries; device microphone and wake behavior require separate physical-device evidence.

## 连续语音对话（2026-10-12 工作树）

`VoiceConversationHost → CallController → MinisCallRuntimeAdapter → AgentCallGateway → 当前 ChatViewModel.submitPrompt`。

- 聊天页提供声波按钮；原麦克风按钮保留听写编辑用途。`minis://action/voice_chat`、旧 `minis://action/voice`、`ACTION_ASSIST` 与 `ACTION_VOICE_ASSIST` 进入同一对话页面。AssistSession 使用可解析的 `voice_chat` URI。
- 自动提交一段最终识别结果，Agent 可调用当前会话全部可用工具。成功回复经 `ReadAloudPlayer.speakConversation` 播放，然后再次聆听。用该请求的 AgentTurnHandle 判断回合终态，并按消息持久 Run 提取回复；任务短简报读取真实 Task 状态，不把回合结束当目标完成。
- 对话采用轮流录音/播报，播放时点击“打断并说话”停止该通话拥有的回合并再次聆听；尚不是原生音频模型的全双工、说话自动打断。
- 使用现有 Voice Input/Output 配置。VAD 在对话模式下采用约 1.8 秒尾部静音与 0.2 秒语音下限；普通听写保持原参数。实际端点时延依赖设备/识别服务，未视为真机验收。
- “补充指令”保留任务并重新聆听；当前会话忙时语音进入 STEER 队列，保留文字草稿附件。输入先保存正文后确认接收；STEER 的任务/停止代次失效时保留待审。
- 静音不取消工具任务；挂断/查看聊天仅停止音频和结果等待，已接受的任务在原会话继续。后台/锁屏立即静音，回到页面需明确恢复。此版不提供后台常驻录音，也不绕过锁屏或系统麦克风授权。
- 原有工具权限/危险操作/问用户确认 Dialog 仍保留在 ChatScreen，语音不获得额外权限。识别配置、权限或网络错误暂停等待显式重试。
- 通话期间停用该会话的普通自动朗读，防止同一回复重复播放；不修改用户保存的自动朗读偏好。

系统助理或语音快捷入口会恢复最近一次语音会话；会话已删除时才新建草稿。任务状态和成果入口读取该会话的 Agent Task 记录；取消录音、停止播报与停止本次语音请求分别控制。当前语音会话恢复路径已有受控验收，实际音频及 OEM 唤醒仍待验证，详见 [整体说明](development/M-SERIES-INTEGRATION.md)。

可重复验收与失败方式见 [开发记录](development/VOICE-CONVERSATION.md)。早期成员语音融合方案已停止，历史边界见 [Bot 与语音方向](development/BOT-VOICE-INTEGRATION.md)。

## Validation focus

- system ASR works without cloud ASR configuration;
- provider ASR can be selected through Voice Input configuration;
- provider TTS can be selected through Voice Output configuration;
- provider failures are surfaced and fallback behavior is deterministic;
- recording and playback do not run over each other unintentionally;
- stop/cancel/hang-up paths leave no stale callback or coroutine;
- custom HTTP voice endpoints obey the same provider transport policy as other provider traffic.
