# Third-Party Licenses

Minis for Android contains code derived from [OpenMinis/OpenMinis](https://github.com/OpenMinis/OpenMinis) and uses additional third-party open-source components.

This file is a project-level inventory, not a substitute for license files shipped by each dependency. Exact dependency versions remain authoritative in Gradle, Cargo, vendored source, Go modules, generated rootfs package metadata, and lock/sum files.

## Project and source-lineage license

OpenMinis is distributed under GPL-3.0. Minis for Android is a derivative work and continues to be distributed under **GPL-3.0**. Replacing earlier Android runtime implementations does not remove GPL obligations that apply to derived application code.

When distributing a modified APK, provide corresponding source and preserve applicable copyright/license notices.

See [PROVENANCE.md](PROVENANCE.md) and [LICENSE](LICENSE).

## Ported source: Eta

| Component | Source | License | Current use |
|---|---|---|---|
| Ported Eta code | [Mangi-11/Eta](https://github.com/Mangi-11/Eta) @ `c15de97` | PolyForm Noncommercial 1.0.0 | bounded streaming I/O and other ported implementation modules |

License text: [third_party/eta/LICENSE](third_party/eta/LICENSE). That file also carries the notice the
license requires be passed on: `Required Notice: Copyright © 2026 蛮吉 (Mangi-11).`

Eta is **not** GPL-3.0 and is **not** distributed under this project's GPL-3.0 grant. Its license permits
use, modification, derivative works and distribution for noncommercial purposes only, and requires that
anyone who receives a copy also receives those terms and the required notice. Downstream use of the ported
modules is bound by that license regardless of how the rest of this repository is licensed.

Attribution lives here and in [PROVENANCE.md](PROVENANCE.md); ported files do not repeat the license header.

### Ported modules in this repository

Every file below carries a short `Ported from Eta ...` note naming its source file and the
commit; the license terms are not repeated per file. New ports append a row here.

| Minis path | Adapted from (Mangi-11/Eta `c15de97`) |
|---|---|
| `mcp/client/MCPHttpTransport.kt` (response bounds) | `agent/model/ProviderSseReader.kt` and the provider transport budgets |
| `tools/runtime/ToolCallValidator.kt` | `agent/model/AgentToolCallValidator.kt` |
| `data/repository/SkillArchiveReader.kt` (entry and byte budgets) | `agent/skill/SkillPackageInstaller.kt` |
| `data/repository/SkillPackageInstaller.kt` | `agent/skill/SkillPackageInstaller.kt` |
| `data/repository/SkillRecoveryJournal.kt` | `agent/skill/SkillRecoveryJournal.kt` |
| `data/repository/SkillMutationLock.kt` | `agent/skill/SkillMutationLock.kt` |
| `data/repository/SkillTransactionStore.kt` | file surface of the three skill modules above, bound to the Minis guest file API |
| `agent/AgentContextBudget.kt` | `agent/model/AgentContextBudget.kt` |
| `agent/AgentContextCompactor.kt` | `agent/model/AgentContextCompactor.kt` |
| `accessibility/AccessibilityNodeIdentity.kt`, `MainThreadCallGate.kt`, `MainThreadCallBridge.kt`, `PackageWindowVisibility.kt`, `ScreenshotWindowPolicy.kt`, `ScrollEventObservationGate.kt` | `agent/accessibility/*`, `agent/device/*` |
| `tools/android/AndroidUiActionEvidence.kt`, `ScrollAxisContract.kt`, `ScrollEvidenceContract.kt`, `ScrollGestureContract.kt`, `RootScrollMotionContract.kt`, `GestureFallbackPolicy.kt`, `ScreenshotOutcomePolicy.kt`, `ShellActionOutcomePolicy.kt`, `FocusedWindowParser.kt`, `AndroidDisplaySizeParser.kt` | `agent/accessibility/*`, `agent/device/*` |
| `tools/ToolSensitivePolicy.kt` | `agent/model/AgentSensitiveToolPolicy.kt`, `agent/model/AgentConversationCodec.kt` |
| `agent/RunCheckpointStore.kt`, `agent/RunCheckpointRecorder.kt`, `agent/RunContextSnapshot.kt`, `agent/RunRecoveryCoordinator.kt` | `agent/runtime/AgentRunCheckpointStore.kt`, `agent/runtime/AgentRunCheckpointRecorder.kt`, `agent/runtime/AgentEventRecoveryProjection.kt`, `ui/app/AgentRunRecoveryCoordinator.kt` |
| `data/repository/MemoryInjectionBudget.kt` (window budget, heading index, revision) | `agent/memory/AgentMemoryContext.kt` |
| `provider/LLMFailureClassifier.kt` (failure classes and the retry gate) | `agent/model/AgentModelFailure.kt`, `agent/model/AgentModelRetry.kt` |
| `ui/chat/WorkProcess.kt` (run grouping, running/failure summary) | `ui/app/AgentRunMessageProjector.kt`, `ui/components/ChatMessageItem.kt` (`AgentWorkProcess`) |
| `ui/markdown/StreamingMarkdownProjection.kt` | `ui/markdown/StreamingGfmParser.kt` (`StreamingGfmProjection` and the parser session's terminal guard) |
| `ui/chat/StreamingMarkdownTargets.kt` | `ui/components/StreamingMarkdownState.kt` (`consumeStreamingMarkdownTargets`) |
| `ui/chat/StreamingMarkdownRestore.kt` | `ui/components/StreamingMarkdownRestoreState.kt` |
| `ui/chat/RevealProgress.kt` (grapheme index, cadence and monotonic clamp) | `ui/components/SmoothTextReveal.kt` (algorithm half only; the draw/layout half is not ported) |
| `provider/CustomHeaderPolicy.kt` (forbidden/sensitive header names, name and value validation, log redaction) | `agent/model/CustomHeaderFilter.kt` |
| `provider/RequestBodyMerge.kt` (recursive custom-body merge rules) | `agent/model/RequestBodyMerge.kt` |
| `provider/openai/ResponsesCitationFormatter.kt` (formatter plus `ResponsesCitationStream`) | `agent/model/ResponsesCitationFormatter.kt`, annotation handling in `agent/model/OpenAiResponsesProvider.kt` |
| `data/model/LLMStreamChunk.kt` (`ProviderOutputItem`), `data/model/LLMMessage.kt` (`providerOutputItems`), `provider/openai/OpenAIProvider.kt` (capture and replay of opaque output items) | `agent/model/ResponsesEphemeralState.kt`, item handling in `agent/model/OpenAiResponsesProvider.kt` |
| `tools/android/UiCoordinateSpace.kt` (coordinate-space contract, frame conversion and refusals) | `agent/model/AgentToolSchema.kt` (`coordinateSpace`), `agent/model/AgentScreenObservationContract.kt` |
| `tools/ImageSourcePolicy.kt`, `tools/ReadImageTool.kt` (image source classification incl. gallery URIs) | `agent/model/AgentFileVisionToolCatalog.kt` |
| `tools/skills/SkillToolPolicy.kt`, `tools/skills/SkillTools.kt` (skill listing, SKILL.md and resource reads), `tools/skills/SkillSourcePolicy.kt` and the GitHub inspect/install additions in `data/repository/SkillRepository.kt` | `agent/model/AgentSkillToolCatalog.kt` and the discovery/install path behind it |
| `tools/android/UiGlobalActions.kt`, `tools/android/AndroidUiController.kt` (system panel actions) | `agent/device/RootShellDeviceController.kt` (`openSystemPanel`), `agent/model/AgentDeviceToolCatalog.kt` |
| `tools/android/AppSearchPolicy.kt`, `tools/android/PackageWaitPolicy.kt`, the `search` action in `tools/android/AndroidPackageController.kt` and `wait_for_package` in `tools/android/AndroidUiController.kt` | `agent/model/AgentDeviceToolCatalog.kt` + `agent/tool/AgentLocalTools.kt` (`search_apps`, `wait_for_package`) |
| `share/ConversationMarkdownExporter.kt` and the markdown format in `share/ChatExporter.kt` | `ui/app/ConversationMarkdownExporter.kt` |
| `service/OverlayRunActions.kt` and the stop control in `service/ToolOverlayController.kt` / `service/AgentForegroundService.kt` | assistant overlay panel in Eta `ui/app/*` (stop affordance and its rule) |
| `notifications/NotificationHistoryPolicy.kt`, `notifications/MinisNotificationListenerService.kt`, `data/repository/NotificationHistoryRepository.kt`, `tools/NotificationTools.kt` | `agent/device/AgentNotificationHistoryService.kt`, `data/repository/NotificationHistoryRepository.kt`, and the `recent_notifications` / `search_notification_history` device tools |
| `tools/history/ConversationHistoryPolicy.kt`, `tools/history/ConversationHistoryTool.kt` | `agent/model/AgentConversationToolCatalog.kt` and the runtime history seam that executes it |
| `tools/alarm/AlarmToolPolicy.kt`, `tools/AlarmTools.kt` | `agent/tool/AgentStructuredDeviceTools.kt` (`set_alarm`, `set_timer`) and the alarm table in `agent/model/AgentDeviceToolCatalog.kt` |
| `tools/context/DeviceContextPolicy.kt`, `tools/DeviceContextTool.kt` | `agent/tool/DeviceContextTool.kt` (`get_current_context`) |
| `runtime/guest/MediaQueryPolicy.kt`, the list extensions in `runtime/guest/PhotosOffloadHandler.kt` and `tools/AndroidMediaStoreTools.kt` | `agent/tool/AgentPersonalDataTools.kt` (`search_media`, `search_audio`, `search_recordings`, `search_files`) and the media entries in `agent/model/AgentDeviceToolCatalog.kt` |
| `roleplay/CharacterCard.kt`, `roleplay/CharacterCardCodec.kt`, `roleplay/CharacterCardPng.kt`, `roleplay/CharacterCardException.kt` | `agent/roleplay/CharacterCard.kt`, `CharacterCardCodec.kt`, `CharacterCardPng.kt`, `CharacterCardException.kt` |


## Active native/runtime components

| Component | Source | License | Current use |
|---|---|---|---|
| `minis-root-network-proxy` | this repository, `src/native/root-network-proxy/` | GPL-3.0-only | fixed loopback HTTP/CONNECT network compatibility helper |
| rclone v1.75.0 mobile binding | `deps/rclone-mobile/`, upstream `rclone/rclone` | MIT for rclone core; transitive modules retain their own licenses | Android remote-transfer binding |
| Ubuntu 24.04 Base | Ubuntu project | aggregate package licenses | generated Direct Ubuntu rootfs userspace |
| cppjieba | vendored Android native source | MIT | word segmentation |

The current `minis-root-network-proxy` Cargo manifest has no third-party crate dependencies; it uses the Rust standard library. The helper may be launched with privileged identity on Android for UID/VPN/BPF egress compatibility, but HTTP/CONNECT proxying itself is not inherently a Root requirement.

The rclone mobile module currently pins `github.com/rclone/rclone v1.75.0`; `deps/rclone-mobile/go.mod` and `go.sum` are authoritative for its transitive module graph. Those transitive dependencies keep their own licenses and notices.

The Ubuntu rootfs is a generated build artifact. Packages inside it retain their individual licenses and notices.

## Android dependencies

| Library / family | License |
|---|---|
| AndroidX / Jetpack / Compose | Apache-2.0 |
| OkHttp / MockWebServer | Apache-2.0 |
| Kotlin coroutines / serialization | Apache-2.0 |
| Coil | Apache-2.0 |
| multiplatform-markdown-renderer | Apache-2.0 |
| Reorderable | Apache-2.0 |
| ACRA | Apache-2.0 |
| Shizuku API/provider | MIT |
| RealTimeCutVADLibraryForAndroid | MIT |
| JUnit 4 | EPL-1.0 |
| org.json test dependency | Public Domain / JSON License |

Use `src/android/app/build.gradle.kts` and Gradle dependency reports for the current version set.

## Bundled web/UI assets

| Asset | Location | License |
|---|---|---|
| KaTeX | Android app assets | MIT |
| cppjieba dictionaries | Android app assets | MIT / upstream distribution terms |

## Removed or historical runtime components

The current Android execution architecture does **not** build or ship the former privileged `minisd` broker or the earlier Alpine/PRoot runtime. Older Git history, archived documents, Issue implementation records, and regression tests may still mention:

- `minisd` and its former Rust dependencies/protocol path;
- OpenMinis/Termux PRoot and ELF loaders (GPL-2.0);
- talloc (LGPL-3.0-or-later);
- Alpine Linux minirootfs;
- iSH and iOS-only dependencies removed from this Android-focused tree;
- historical Web Remote assets and associated web tooling.

These historical references do not mean those components are part of the current APK/runtime payload.

## Verification

Before publishing binaries, verify the final Gradle/Cargo/Go dependency graphs, generated Ubuntu rootfs package notices, and bundled assets against this inventory. If a dependency is added, removed, upgraded, or relicensed, update this file in the same change.
