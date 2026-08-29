# Agent foreground-service contract

This document describes the foreground-service boundary for interactive and headless Agent execution on Android.

## Foreground-service type

`AgentForegroundService` is not a media player and is not a data synchronization service.

On Android 14 (API 34) and newer it uses the `specialUse` foreground-service type with the manifest subtype:

> Active AI agent turn with model streaming and bounded tool execution

The app retains the `FOREGROUND_SERVICE_MEDIA_PLAYBACK` permission for genuine audio/media surfaces that may need a dedicated media-playback foreground service. Generic Agent, shell, browser, file, and model execution must never request `mediaPlayback` merely to obtain a longer process lifetime.

The Agent service also deliberately does not switch to `dataSync`. Android 15 applies cumulative background time limits to `dataSync`; changing labels would not make Agent execution synchronization work and would only move the lifecycle failure.

## Lifecycle

The Agent FGS exists only while at least one Agent turn is actually active:

```text
0 active turns
  -> no Agent FGS

first active turn
  -> start Agent FGS
  -> specialUse on API 34+

additional active turn
  -> update existing FGS

last active turn finishes/cancels
  -> stop Agent FGS
```

Being present on a chat screen does not qualify as active Agent work. `AgentForegroundService.startService()` re-checks `SessionActivityTracker.activeSessions` so stale presence-only callers cannot keep the process resident.

Scheduled/headless execution does not pre-start the Agent FGS before a session/provider is resolved. The same `ChatViewModel` active-turn transition used by interactive chat owns the service lifecycle.

## Stop and resume

The notification Stop action calls `SessionActivityTracker.cancelAllActiveStreams()` before removing the foreground notification and stopping the service. Each live `ChatViewModel` therefore follows its normal cancellation path; a later user Resume/new turn must claim active execution again and start a new FGS lifetime.

## Process death and task removal

The service returns `START_NOT_STICKY`. Swiping the app task from recents does not manually re-anchor or restart the foreground service.

If Android/OEM policy kills the process, Minis does not fabricate a replacement Agent execution simply to restore process residency. Existing persistence boundaries remain responsible for safe recovery:

- chat/session data is persisted by the repository/Room layer;
- `ToolCheckpointStore` persists pre-execution tool intents so an uncertain side effect can be surfaced as `TOOL_OUTCOME_UNKNOWN` instead of blindly replayed;
- `JobRegistry` is process-local/in-memory and must not be described as durable cross-process recovery.

Full automatic process-death recovery is a separate runtime concern; this change only removes the misleading media FGS/process-residency dependency.

## Android 14 / 15 / 16 validation

`AgentForegroundServiceManifestTest` is an API 34+ instrumentation test and is runnable on Android 14, 15, and 16. It verifies the installed/merged manifest declares `specialUse`, rejects `mediaPlayback` and `dataSync` for the Agent service, and exposes a non-empty special-use subtype.

Repository CI compiles the instrumentation APK with `:app:assembleDebugAndroidTest`. CI does not currently boot an Android 14/15/16 emulator or physical device, so this is compile/package evidence rather than claimed device execution.

JVM tests cover the lifecycle policy: presence-only state, active execution, stop/resume transition, and `START_NOT_STICKY` process-death behavior.

## Device and OEM limitations

Foreground-service start restrictions still apply. `specialUse` is a type declaration, not an exemption from Android background-start rules. Exact-alarm/scheduled execution and OEM background policies may behave differently across devices.

OEM task managers may still terminate a legitimate active FGS or throttle its process despite wake locks/battery exemptions. Those cases must be tested on representative devices; this document does not claim physical-device verification where none was run.
