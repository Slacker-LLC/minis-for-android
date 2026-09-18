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
| `roleplay/CharacterWorldbook.kt`, `roleplay/CharacterWorldbookSupport.kt` | `agent/roleplay/CharacterWorldbook.kt`, `CharacterWorldbookSupport.kt` |
| `roleplay/CharacterMacros.kt`, `roleplay/CharacterCardCompatibility.kt` | `agent/roleplay/CharacterMacros.kt`, `CharacterCardCompatibility.kt` |
| `roleplay/CharacterRepository.kt`, `roleplay/CharacterStoragePolicy.kt`, `data/db/CharacterEntity.kt`, `data/db/CharacterDao.kt` and the `characters` table in `data/db/AppDatabase.kt` | `data/repository/CharacterRepository.kt` and `data/db/CharacterEntity.kt` |
| `roleplay/CharacterPrompt.kt` (character block and depth-note placement) | `agent/roleplay/RoleplayRunContext.kt` |
| `roleplay/CharacterBinding.kt`, the session-binding additions in `roleplay/CharacterRepository.kt`, `data/db/ChatDao.kt`, `data/db/ChatSessionEntity.kt` and the `roleplay_json` column in `data/db/AppDatabase.kt` | `agent/roleplay/CharacterCard.kt` (`RoleplayBinding`) and the conversation binding in `RoleplayRunContext.kt` |
| `roleplay/RoleplayTurnProjection.kt` (per-request lore, depth note and character block) | `agent/roleplay/RoleplayRunContext.kt` (`projectMessages`) |
| `ui/settings/CharactersScreen.kt` (character library: import, share, delete) | Eta's character screens and its file-picker import path |
| `ui/settings/CharacterPickerDialog.kt` and the session-menu entry in `ui/sessions/SessionListScreen.kt` | Eta's conversation binding UI |
| `roleplay/CharacterWorldbookDraft.kt` (world book draft read/write) | `agent/roleplay/CharacterWorldbookDraft.kt` |
| `ui/settings/CharacterDetailScreen.kt` (character detail and world-book editor) | Eta's character detail and world-book editor screens |
| `roleplay/CharacterMemoryDocument.kt`, `roleplay/CharacterMemoryRepository.kt`, `tools/CharacterMemoryTools.kt` and the memory lines in `roleplay/CharacterPrompt.kt` | `data/repository/CharacterMemoryRepository.kt`, `agent/roleplay/CharacterMemoryTools.kt` and the memory half of `RoleplayRunContext.kt` |
| `xposed/HookInstallStatus.kt`, `xposed/ModuleTargets.kt` (hook install ledger and module target tables) | `core/HookRegistrar.kt`, `core/ModuleConfig.kt` and the process filter in `ModuleMain.kt` |
| `xposed/MinisXposedModule.kt`, `xposed/HookGroupRegistry.kt`, `src/main/resources/META-INF/xposed/*` and the module declaration in `app/build.gradle.kts` | `ModuleMain.kt` and the libxposed module declaration |
| `xposed/HookRegistrar.kt`, `xposed/HookSupport.kt`, `xposed/HookLogger.kt` | `core/HookRegistrar.kt`, `core/HookSupport.kt`, `core/ModuleLogger.kt` |
| `xposed/ModulePrefs.kt` (module switches and their defaults) | `config/Prefs.kt` |
| `xposed/LogThrottle.kt`, `xposed/HookGroups.kt`, `xposed/system/CircleToSearchInvoker.kt`, `xposed/hyperos/HyperOsScreenSearchHooks.kt`, `xposed/hyperos/HyperOsScreenSearchRequest.kt` | `core/LogThrottle.kt`, `hook/system/CircleToSearchInvoker.kt`, `hook/hyperos/HyperOsScreenSearch*.kt` |
| `xposed/AssistantLaunch.kt`, `xposed/hyperos/HyperOsPowerHooks.kt`, `xposed/hyperos/HyperOsPowerPolicy.kt` | `hook/hyperos/HyperOsPower*.kt` and the launch half of `hook/system/PowerHooks.kt` / `AssistantManager.kt` |
| `xposed/google/GoogleEligibilityHooks.kt`, `xposed/google/GoogleSpoofProfile.kt` | `hook/google/GoogleEligibilityHooks.kt` and the device-identity half of `hook/google/GoogleAppHooks.kt` (values from the `SPOOF_*` constants in `core/ModuleConfig.kt`) |
| `xposed/system/ContextualSearchHooks.kt`, `xposed/system/ContextualSearchCallerPolicy.kt` | `hook/system/ContextualSearchHooks.kt`, `hook/system/ContextualSearchCallerPolicy.kt` (the system-package check behind the caller policy joined `xposed/HookSupport.kt`) |
| `xposed/LogSafety.kt` (`safeLogType`, `toSafeLogToken`) | `core/LogSafety.kt` |
| `xposed/system/AccessibilityServiceEnforcer.kt`, `xposed/system/AccessibilityProtectionHooks.kt` (including its system-context resolver), `xposed/system/AccessibilityProtectionProtocol.kt` | `hook/system/AccessibilityServiceEnforcer.kt`, `hook/system/AccessibilityProtectionHooks.kt`, `agent/accessibility/AccessibilityProtectionProtocol.kt` |
| `xposed/system/AccessibilityProtectionClient.kt` (app-side caller), the module-protection section in `ui/settings/SystemPermissionsScreen.kt` and `accessibility/MinisAccessibilityHealthProvider.kt` | `agent/accessibility/AccessibilityProtectionClient.kt`, `agent/accessibility/AgentAccessibilityHealthProvider.kt` and Eta's accessibility settings surface |
| `xposed/system/HotwordSelfHealHooks.kt`, `xposed/system/HotwordSelfHealPolicy.kt`, `xposed/system/AssistantHotword.kt` (the hotword half of Eta's assistant manager) | `hook/system/HotwordSelfHealHooks.kt` and the software-hotword path in `hook/system/AssistantManager.kt` |
| `xposed/aimemory/ColorOsMemoryHooks.kt`, `xposed/aimemory/ColorOsMemoryBridgeProtocol.kt` (method and result ids renamed for this app), `xposed/aimemory/ColorOsMemoryDatabaseQuery.kt` | `hook/aimemory/ColorOsMemoryHooks.kt`, `core/ColorOsMemoryBridgeProtocol.kt`, `agent/tool/ColorOsMemoryDatabaseQuery.kt` |
| `tools/ColorOsMemoryTools.kt` (three tools and their bounds; the caller of the bridge) | `agent/tool/AgentColorOsMemoryTools.kt` and the ColorOS memory entries in `agent/model/AgentDeviceToolCatalog.kt` |
| `xposed/colordirect/ColorDirectHooks.kt`, `xposed/colordirect/ColorDirectTriggerPolicy.kt` (the trigger rule and the dedup window as values) | `hook/colordirect/ColorDirectHooks.kt` |
| `tools/ColorOsPersonalDataTools.kt` (notes, recordings, recording summaries plus their bounds and LIKE escaping), `tools/PersonalDataContentParser.kt` | `agent/tool/AgentPersonalDataTools.kt` (the query path and its parser) and the matching entries in `agent/model/AgentDeviceToolCatalog.kt` |
| `xposed/hyperos/HyperOsLauncherHooks.kt`, `xposed/hyperos/HyperOsSearchTrigger.kt`, `xposed/hyperos/HyperOsLongPressGesture.kt`, `xposed/hyperos/HyperOsLegacyGesture.kt`, `xposed/hyperos/HyperOsGesturePolicy.kt` (the takeover answer as a value) | `hook/hyperos/HyperOsLauncherHooks.kt`, `hook/hyperos/HyperOsSearchTrigger.kt`, `hook/hyperos/HyperOsLongPressGesture.kt`, `hook/hyperos/HyperOsLegacyGesture.kt` |
| `xposed/system/SystemUiHooks.kt`, `xposed/system/SystemUiOcrPolicy.kt` (the haptic id and the context lookup order as values) | `hook/system/SystemUiHooks.kt` |
| `tools/SmsCodeExtractionPolicy.kt` (the extraction rule) and `android.sms.code` in `tools/AndroidTelephonyTools.kt` | `agent/tool/AgentStructuredDeviceTools.kt` (`read_sms_code`) and its catalog entry |
| `tools/DeviceStatePolicy.kt` (the two commands as argv) and `tools/AndroidDeviceStateTools.kt` | `agent/tool/AgentStructuredDeviceTools.kt` (`set_device_state`) and its catalog entry |
| `tools/AppStatePolicy.kt` (the three argv forms) and the `freeze`/`unfreeze` actions in `tools/android/AndroidPackageController.kt` / `tools/android/AndroidAgentTools.kt` | `agent/tool/AgentStructuredDeviceTools.kt` (`app_state_control`) and its catalog entry |
| `tools/ClockDatabaseTools.kt` (the snapshot steps as separate privileged commands and the two queries) and `tools/PrivateDatabaseRules.kt` | `agent/tool/AgentPrivateDatabaseTools.kt` (`list_alarms` / `list_active_timers`) and their catalog entries |
| `tools/PrivateDatabaseTools.kt` (clipboard history and the health summary), `tools/PrivateDatabaseSnapshot.kt`, `tools/DatabaseRows.kt`, `tools/HealthSummaryPolicy.kt` | `agent/tool/AgentPrivateDatabaseTools.kt` (`search_clipboard_history` / `get_health_summary`, and the snapshot and row reading shared with the clock queries) and their catalog entries |
| `tools/ChatImagePolicy.kt` (the two caches and the row rule as values) and `tools/ChatImageTools.kt` | `agent/tool/AgentPersonalDataTools.kt` (`search_qq_chat_images` / `search_wechat_chat_images`) and their catalog entries |
| `tools/PersonalDataQueryTools.kt` (the shared content-query read, extracted from the ColorOS tools) and `tools/DownloadsTools.kt` | `agent/tool/AgentPersonalDataTools.kt` (`search_downloads` and the `query` helper behind every provider read) and its catalog entry |
| `xposed/ModuleSettingsStore.kt` and `ui/settings/ModuleSettingsScreen.kt` | `config/Prefs.kt` and the module settings screen behind it |
| `xposed/system/PowerKeyHooks.kt`, `xposed/system/PowerKeyPolicy.kt` (the assist message id, the OEM haptic and the dedup window as values) | `hook/system/PowerHooks.kt` and the constants it reads in `core/ModuleConfig.kt` |
| `provider/HostedWebSearchPolicy.kt`, the `hostedWebSearch` field in `data/model/LLMModel.kt` / `data/model/ProviderConfig.kt` and its toggle in `ui/settings/ModelEntryDetailScreen.kt` | `agent/model/ResponsesRequestBuilder.kt` and the `hosted_web_search_enabled` column behind it |
| `provider/HostedCallEventPolicy.kt`, `LLMStreamChunk.HostedToolActivity` and the row it appends in `ui/chat/ChatViewModel.kt` | `agent/model/OpenAiResponsesProvider.kt` (the hosted-call events, the `hostedTools` map and `ProviderEvent.HostedTool*`) |
| `accessibility/TextEditPlanner.kt`, the cursor-aware `setNodeText` / `placeCursor` in `accessibility/MinisAccessibilityService.kt` and the `input_text` action in `tools/android/AndroidUiController.kt` | `agent/accessibility/TextEditPlanner.kt` and `inputTextFocused` / `setNodeText(node, text, cursor)` in `agent/accessibility/AgentAccessibilityService.kt` |
| `accessibility/ClipboardRestorePolicy.kt`, the shared clipboard borrow and the `paste_text` action in `tools/android/AndroidUiController.kt` | `pasteText` / `restoreClipboardIfStillOwned` in `agent/accessibility/AgentAccessibilityService.kt` |
| `accessibility/TextInputBoundsPolicy.kt`, the write bounds in `tools/android/AndroidUiController.kt` and the `ime_enter` action | the `input_text` / `replace_text` bounds in `agent/device/RootShellDeviceController.kt` and `imeEnter` (the `press_key` ENTER path) in `agent/accessibility/AgentAccessibilityService.kt` |
| `runtime/guest/ClipboardBoundsPolicy.kt` and the read/write bounds in `runtime/guest/ClipboardOffloadHandler.kt` | the clipboard bounds in `agent/device/RootShellDeviceController.kt` (`clipboardSet` / `clipboardGet`) |
| `tools/android/UiWaitPolicy.kt` and the `match` / `include_desc` parameters of the `android_ui wait` action | `RootShellDeviceController.matches` and the `wait_for_text` parameters behind it |
| `util/BoundedStreams.kt` (the per-chunk cancellation check and the typed too-large failure) and the bounded copy in `webapp/AddToHomeSheet.kt` | `agent/device/BoundedFileCopy.kt` |
| `browser/BrowserPayloadLimiter.kt` (the JSON ladder; its text-level half is this repository's, for payloads that never become an envelope) | `agent/browser/BrowserPayloadLimiter.kt` |
| `browser/BrowserTextWindowPolicy.kt` and the windowed text reads (`offset` / `max_chars` through `browser/BrowserActionInput.kt`, `browser/BrowserUseJS.kt`, `browser/BrowserUseManager.kt` and the `browser_use` schema) | `agent/browser/BrowserDomScripts.kt` (`text` / `readable`) and the read-page arguments in `agent/browser/AgentBrowserSession.kt` |


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
