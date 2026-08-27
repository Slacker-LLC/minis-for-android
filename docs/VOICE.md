# Voice Runtime

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

For testable call-style state machines, wrap existing production classes behind narrow interfaces for speech input, model requests, and speech output; unit tests can then use fakes without real microphone/network/audio devices.

## Validation focus

- system ASR works without cloud ASR configuration;
- provider ASR can be selected through Voice Input configuration;
- provider TTS can be selected through Voice Output configuration;
- provider failures are surfaced and fallback behavior is deterministic;
- recording and playback do not run over each other unintentionally;
- stop/cancel/hang-up paths leave no stale callback or coroutine;
- custom HTTP voice endpoints obey the same provider transport policy as other provider traffic.
