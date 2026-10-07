package com.openminis.app.ui.chat

import android.content.Context
import android.util.Log
import androidx.compose.material.icons.outlined.Build
import androidx.lifecycle.viewModelScope
import com.openminis.app.agent.InterruptedTailDetector
import com.openminis.app.agent.InterruptedTailShape
import com.openminis.app.agent.RunCheckpointRecorder
import com.openminis.app.browser.BrowserTabPool
import com.openminis.app.data.db.ChatSessionEntity
import com.openminis.app.data.db.CompactMarkerEntity
import com.openminis.app.data.db.MessageEntity
import com.openminis.app.data.model.LLMMessage
import com.openminis.app.data.model.ThinkingLevel
import com.openminis.app.logging.AppLogger
import com.openminis.app.provider.LLMProvider
import com.openminis.app.provider.ProviderFactory
import com.openminis.app.runtime.ExecutionCoordinator
import com.openminis.app.service.SessionActivityTracker
import com.openminis.app.tools.FileReadTool
import com.openminis.app.ui.chat.ChatViewModel.Companion.INITIAL_VISIBLE_MESSAGE_CAP
import com.openminis.app.ui.chat.ChatViewModel.Companion.RUN_CHECKPOINT_ENTRY_SOURCE
import com.openminis.app.ui.chat.ChatViewModel.Companion.TAG
import com.openminis.app.ui.chat.ChatViewModel.FallbackCandidate
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal suspend fun <T> ChatViewModel.withBotTurnLock(block: suspend () -> T): T {
    val botId = sessionBotId ?: chatRepository.getSession(activeSessionId)?.botId
    return com.openminis.app.tools.BotTurnLockRegistry.withLock(botId, activeSessionId, block)
}

/** Assign one durable source-run key to the whole source stream. */
internal fun ChatViewModel.beginBotSourceRun(): String {
    val runId = "run-${java.util.UUID.randomUUID()}"
    activeBotRunId = runId
    // [T-android-run-checkpoint] Open this run's recovery record. A run that
    // cannot be checkpointed (blank session, unknown entry source) returns
    // null and simply proceeds without recovery.
    runCheckpointTranscript.clear()
    runCheckpointRecorder = RunCheckpointRecorder.create(
        context = context,
        sessionId = activeSessionId,
        runId = runId,
        entrySource = RUN_CHECKPOINT_ENTRY_SOURCE,
    )
    return runId
}

/**
 * Ensure a durable row exists before a UI edits per-session settings.
 * Draft chats normally materialize on first send; opening Advanced settings
 * is also a durable session action and must not write against the synthetic
 * `__new__` route id.
 */
suspend fun ChatViewModel.ensureSessionForSettings(): String = ensureSession()

/** T-chat-title-pill-edit: load the persisted [ChatSessionEntity] for the
 *  current session so the shared edit-title sheet (reused from the session
 *  list) can be opened from the in-chat title pill. Returns null for
 *  drafts that haven't been persisted yet. */
suspend fun ChatViewModel.loadSessionEntity(): com.openminis.app.data.db.ChatSessionEntity? {
    val sid = realSessionId.ifEmpty { return null }
    return runCatching { chatRepository.getSession(sid) }.getOrNull()
}

/** T-chat-title-pill-edit: update title + category from the in-chat
 *  edit sheet. Mirrors SessionListViewModel.updateTitleAndCategory but
 *  also refreshes the local StateFlows so the pill updates immediately
 *  without waiting for a session reload. */
fun ChatViewModel.updateTitleAndCategory(title: String, category: String?) {
    val sid = realSessionId.ifEmpty { return }
    viewModelScope.launch {
        chatRepository.updateSessionTitleAndCategory(sid, title, category)
        _sessionTitle.value = title.ifBlank { "New Chat" }
        com.openminis.app.tools.android.AndroidDebugSessionStore.update(sid) {
            it.copy(sessionTitle = title.ifBlank { "New Chat" })
        }
        _sessionCategory.value = category
    }
}

/** Ensure the session exists in the database. Called before first message. */
internal suspend fun ChatViewModel.ensureSession(): String = withContext(Dispatchers.IO) {
    pendingMediaCommitJob?.join()
    android.util.Log.e("PROMPT_DEBUG", "ensureSession called: sessionId='$sessionId', realSessionId='$realSessionId', isDraft=$isDraft")
    if (realSessionId.isNotEmpty()) {
        val existing = chatRepository.dao.getSession(realSessionId)
        android.util.Log.e("PROMPT_DEBUG", "Checking realSessionId='$realSessionId' in DB: existing=${existing != null}")
        if (existing != null) {
            return@withContext realSessionId
        }
    }
    val modelId = currentModel?.id ?: providerRepository.allVisibleEntries().firstOrNull()?.model?.id ?: "unknown"
    // [T-memory-global-toggle-settings-ui-android] Snapshot the
    // current in-memory `_memoryEnabled` into the new row. For a
    // draft VM this matches the global default we seeded at
    // construction; if the user flipped /memory on the draft
    // before first send, that choice wins.
    val session = chatRepository.createSession(
        modelId = modelId,
        memoryEnabled = _memoryEnabled.value,
    )
    realSessionId = session.id
    pendingThinkingOverride?.let {
        chatRepository.dao.updateThinkingOverride(session.id, it.name)
        pendingThinkingOverride = null
    }
    // Held draft events now have a real FK target. Keep their source order
    // and allocate sequence numbers only after Room's high-water mark is
    // known, so a process restart cannot collide with old rows.
    SessionEventHub.rename(sessionId, session.id)
    SessionEventHub.activateSession(chatRepository.dao, session.id)
    // "New Chat in Group": file the just-promoted draft into its folder.
    // Unconditional (vs iOS setFolderIfUnfiled) — the session is seconds
    // old and nothing else can have filed it yet.
    initialFolderId?.let { chatRepository.setFolderForSessions(it, listOf(session.id)) }
    // Move our cached VM from the draft key ("__new__...") to the real
    // sessionId so re-entering the session reuses the same instance.
    if (isDraft) {
        ChatViewModelStore.rename(sessionId, session.id)
        // Bring every disk/shell resource that was opened with the draft
        // id over to the real id *before* agent tools start running against
        // the persisted session — otherwise the first tool call (e.g.
        // yt-dlp writing into /var/minis/attachments) would land in
        // minis-sessions/__new__*/… and be orphaned when the user
        // re-enters the session and everything is resolved via the real
        // id. See debug report 2026-04-21 (TikTok Chinese filename).
        migrateDraftResources(fromDraft = sessionId, toReal = session.id)
        // [T-android-session-skill-override-init-timing] Re-point any
        // session_skill_overrides / mcp_session_overrides rows written
        // pre-first-message (against `__new__<uuid>`) onto the real
        // session id, mirroring the disk-resource hop above. Without
        // this, a skill or MCP server the user toggled on the draft
        // session sheet vanishes the next time the same chat is opened
        // (the prop carries the real id by then, but the override row
        // is still stranded under the draft key). Aligns with iOS
        // ed861471 (T-ios-session-skill-override-init-timing). Cheap
        // no-op when no rows match.
        skillRepository?.renameSessionOverrides(fromDraft = sessionId, toReal = session.id)
        mcpRepository?.renameSessionOverrides(fromDraft = sessionId, toReal = session.id)
        // Re-point the lazily-created BrowserTabPool if it was already
        // instantiated against the draft key (e.g. user opened the browser
        // sheet before sending a message). Without this, cookies and
        // downloads keep flowing into the draft directory.
        _browserTabPoolRef?.setSession(session.id)
    }
    // Persist the current model binding so it survives re-entry
    val entryId = _activeEntryId.value
    val binding = entryId?.let { com.openminis.app.data.model.ModelBinding.encodeEntry(it) }
    if (binding != null) {
        chatRepository.updateSessionBinding(realSessionId, binding, modelId)
    }
    return@withContext realSessionId
}

/**
 * Move every per-session disk resource from the draft directory to the
 * real one, and tear down any shell that was started against the draft id.
 *
 * The draft key leaks into persistent shells (`ExecutionCoordinator`),
 * browser artifacts (`persistBrowserArtifact`), and the `BrowserTabPool`'s
 * cookie/state store. Before this migration ran, a tool invocation that
 * happened before the user's first message would write into the draft's
 * `minis-sessions/__new__{uuid}` directory and become invisible the
 * moment the VM was recreated under the real id — exactly the symptom
 * observed with the Chinese-named TikTok download that appeared to
 * "disappear" after `yt-dlp` reported success.
 */
internal fun ChatViewModel.migrateDraftResources(fromDraft: String, toReal: String) {
    // Stop any shell that was already spun up against the draft id; its
    // -b mount arguments were frozen to the draft directory at launch, so
    // we can't reuse it after the migration.
    runCatching { ExecutionCoordinator.sessionDidTerminate(fromDraft) }

    val base = java.io.File(com.openminis.app.runtime.ubuntu.UbuntuPaths.hostSessions)
    val draftBase = java.io.File(base, fromDraft)
    if (!draftBase.isDirectory) return
    val realBase = java.io.File(base, toReal).apply { mkdirs() }

    listOf("attachments", "offloads", "workspace", "browser").forEach { subdir ->
        val src = java.io.File(draftBase, subdir)
        if (!src.isDirectory) return@forEach
        val dst = java.io.File(realBase, subdir).apply { mkdirs() }
        src.listFiles()?.forEach { child ->
            val target = java.io.File(dst, child.name)
            runCatching {
                if (!target.exists() && !child.renameTo(target)) {
                    copyRecursive(child, target)
                }
            }.onFailure {
                android.util.Log.w("ChatViewModel",
                    "migrateDraftResources: failed to move ${child.absolutePath} -> ${target.absolutePath}: ${it.message}")
            }
        }
    }
    runCatching { draftBase.deleteRecursively() }

    // Also rename the BrowserTabPool saved-state file (filesDir/browser_tabs/<sid>.json).
    // Otherwise the pool will load empty state on the next re-entry and the
    // user loses their open tabs even though the URLs never truly "went away".
    val tabsDir = java.io.File(context.filesDir, "browser_tabs")
    val draftTabs = java.io.File(tabsDir, "$fromDraft.json")
    if (draftTabs.exists()) {
        val realTabs = java.io.File(tabsDir, "$toReal.json")
        runCatching {
            if (!realTabs.exists()) {
                if (!draftTabs.renameTo(realTabs)) {
                    draftTabs.copyTo(realTabs, overwrite = false)
                    draftTabs.delete()
                }
            }
        }
    }
}

internal fun ChatViewModel.copyRecursive(src: java.io.File, dst: java.io.File): Boolean = runCatching {
    if (src.isDirectory) {
        dst.mkdirs()
        src.listFiles()?.all { copyRecursive(it, java.io.File(dst, it.name)) } ?: true
    } else {
        src.copyTo(dst, overwrite = false)
        src.delete()
        true
    }
}.getOrDefault(false)

internal fun ChatViewModel.loadSession() {
    // T-android-crash-detected-halt: when CrashFrequencyDetector
    // tripped (#459, ≥3 crashes in last hour), skip the heavy
    // session-restore path entirely. Re-running the same persisted
    // state is exactly what produced the burst, so we'd just feed
    // a re-crash loop while the user is staring at the share dialog.
    // The flag clears the moment the dialog closes (share / dismiss /
    // cancel) — see CrashFrequencyDetector.maybeShowOnActivity.
    if (com.openminis.app.crash.CrashFrequencyDetector.isSafeMode()) {
        android.util.Log.w(TAG, "loadSession: safe-mode active, skipping session restore")
        // [T-android-perf-logging] Surface the skip on the Perf timeline
        // too — when a crash_or_stall recovery loop is suspected, this
        // distinguishes "loadSession ran and was slow" from "loadSession
        // was skipped (safe-mode), so the stall is elsewhere".
        com.openminis.app.diagnostics.PerfLongCtx.step(
            sessionId,
            "loadSession.skipped",
            "reason=safeMode",
        )
        return
    }
    viewModelScope.launch {
        // [T-HANG-DIAG] timing markers to localise where session entry
        // stalls. Sentinel-tagged so a single grep -v can strip them
        // when this diagnostic is removed. Declared OUTSIDE the try
        // block so the EXIT log in `finally` can still read it after
        // an early-return / exception path.
        val tHangDiagStart = System.currentTimeMillis()
        println("[T-HANG-DIAG] loadSession ENTER session=$sessionId isDraft=$isDraft")
        com.openminis.app.diagnostics.PerfLongCtx.step(sessionId, "loadSession.enter", "isDraft=$isDraft")
        try {
        val config = providerRepository.config.value

        if (isDraft) {
            // Draft session: just set up provider using Main slot or first entry
            _sessionTitle.value = "New Chat"
            com.openminis.app.tools.android.AndroidDebugSessionStore.update(sessionId) {
                it.copy(sessionTitle = "New Chat")
            }
            _sessionCategory.value = null
            applyNewChatDefaultModel()
            return@launch
        }

        // Existing session: load from DB
        val session = chatRepository.getSession(sessionId) ?: return@launch
        _sessionTitle.value = session.title ?: "New Chat"
        com.openminis.app.tools.android.AndroidDebugSessionStore.update(sessionId) {
            it.copy(sessionTitle = session.title ?: "New Chat")
        }
        _sessionCategory.value = session.category
        sessionBotId = session.botId
        sessionSource = session.source
        _memoryEnabled.value = session.memoryEnabled != 0
        // T239: hydrate persisted thinking-mode override. null = unset
        // (use OFF as the legacy default); non-null = explicit user
        // choice persisted across cold-start. runCatching guards against
        // a stale enum name from a future rename — fall back silently
        // rather than crashing the session load.
        _thinkingLevel.value = session.thinkingOverride
            ?.let { runCatching { ThinkingLevel.valueOf(it) }.getOrNull() }
            ?: ThinkingLevel.OFF

        // Priority 1: restore the migrated entry-only model binding.
        var resolved = restoreFromBinding(session.modelBinding)

        // Priority 2: fall back to stored model_id
        if (!resolved) {
            val entry = findModelEntry(session.modelId)
            if (entry != null) {
                currentModel = entry.model
                _modelName.value = entry.model.displayName
                _activeEntryId.value = entry.id
                val instance = providerRepository.instance(entry.providerInstanceId)
                if (instance != null) {
                    val apiKey = providerRepository.usableApiKey(instance)
                    if (apiKey != null) {
                        currentProvider = ProviderFactory.create(instance, apiKey, entry.model, context)
                        _providerName.value = instance.label.ifEmpty { entry.model.provider }
                        resolved = true
                    }
                }
            }
        }

        // Priority 3: fall back to the main slot, then last-used/newest.
        if (!resolved) {
            resolved = applyNewChatDefaultModel()
        }

        // [T-HANG-DIAG] measure DB load + transform separately so a long
        // load on one stage is obvious in the trace.
        //
        // T-android-gc-storm-hang-crash (P0, issue #17): on a 405-message
        // session with one 397KB user row, loadMessages + toChatMessages
        // + the agentHistory rebuild below ran on Main and triggered a
        // GC storm (34MB freed, repeated) that blocked the frame loop for
        // 58s → crash_or_stall restart. Hoist the heavy DB + JSON-parse
        // work off Main so the UI thread stays responsive even when one
        // row is large. Stays inside the existing safe-mode guard above
        // (#466/#470) — we only move work, not gating.
        val tHangDiagBeforeLoad = System.currentTimeMillis()
        data class LoadedSessionData(
            val messages: List<com.openminis.app.data.db.MessageEntity>,
            val ordered: List<ChatMessage>,
            val llmHistory: List<LLMMessage>,
            val loadMs: Long,
            val transformMs: Long,
        )
        com.openminis.app.diagnostics.PerfLongCtx.step(sessionId, "db.query.begin")
        val loaded = withContext(Dispatchers.IO) {
            val tIoBeforeLoad = System.currentTimeMillis()
            val history = SessionHistoryLoader.load(chatRepository, sessionId)
            val rows = history.rows
            val tIoAfterLoad = System.currentTimeMillis()
            com.openminis.app.diagnostics.PerfLongCtx.step(
                sessionId,
                "db.query.end",
                "count=${rows.size}",
            )
            val chatUi = messageMapper.toChatMessages(rows, history.parts)
            val tIoAfterTransform = System.currentTimeMillis()
            com.openminis.app.diagnostics.PerfLongCtx.step(
                sessionId,
                "toChatMessages.end",
                "count=${chatUi.size}",
            )
            // Reuse the parsed parts for the LLM history off-Main too.
            // Build into a local list and
            // bulk-append to `agentHistory` on Main below; loadSession
            // runs once at init before any other writer touches
            // agentHistory, so a bulk addAll is race-free.
            val llm = ArrayList<LLMMessage>(rows.size)
            var totalPartsChars = 0L
            for (entity in rows) {
                totalPartsChars += entity.partsJson.length
                llm.add(messageMapper.toLLMMessage(entity, history.parts.getValue(entity.id)))
            }
            com.openminis.app.diagnostics.PerfLongCtx.step(
                sessionId,
                "toLLMMessage.end",
                "count=${llm.size} totalPartsChars=$totalPartsChars",
            )
            LoadedSessionData(
                messages = rows,
                ordered = chatUi,
                llmHistory = llm,
                loadMs = tIoAfterLoad - tIoBeforeLoad,
                transformMs = tIoAfterTransform - tIoAfterLoad,
            )
        }
        val messages = loaded.messages
        val ordered = loaded.ordered
        val tHangDiagAfterLoad = tHangDiagBeforeLoad + loaded.loadMs
        val tHangDiagAfterTransform = tHangDiagAfterLoad + loaded.transformMs
        println(
            "[T-HANG-DIAG] loadMessages session=$sessionId count=${messages.size} " +
                "tookMs=${loaded.loadMs}",
        )
        println(
            "[T-HANG-DIAG] toChatMessages session=$sessionId tookMs=${loaded.transformMs}",
        )
        // Per-message size sketch + oversize-row scan. Pure diagnostics —
        // does a full second pass over partsJson with several substring
        // searches per row, so on a 405-row session with 1MB total it
        // adds material main-thread time. Fire-and-forget on the IO
        // dispatcher so it can't contribute to the GC-storm hang the
        // rest of this task is trying to fix.
        viewModelScope.launch(Dispatchers.IO) {
            var totalChars = 0L
            var maxChars = 0
            var withTools = 0
            var withAttachments = 0
            for (m in messages) {
                val len = m.partsJson.length
                totalChars += len
                if (len > maxChars) maxChars = len
                // ContentPart serialises its discriminator in camelCase
                // ("toolUse" / "toolResult" — see ContentPart.PartType), so
                // the snake_case probe this used to run matched NOTHING and
                // reported toolMessages=0 on every session, including ones
                // whose history is almost entirely tool traffic. That is
                // the opposite of the signal this diagnostic exists to give
                // — it is here to finger oversized tool_result inlines as
                // the GC-storm culprit, and it was reporting them absent.
                if (m.partsJson.contains("\"toolUse\"") || m.partsJson.contains("\"toolResult\"")) {
                    withTools++
                }
                // Same casing trap: attachments serialise as "mediaRef",
                // never as "image"/"attachment".
                if (m.partsJson.contains("\"mediaRef\"")) {
                    withAttachments++
                }
            }
            println(
                "[T-HANG-DIAG] messages-shape session=$sessionId total=${messages.size} " +
                    "totalChars=$totalChars maxChars=$maxChars toolMessages=$withTools " +
                    "attachmentMessages=$withAttachments",
            )

            // [T-HANG-DIAG] for any message ≥ 50_000 chars, log size /
            // role / createdAt / structural type markers only — NEVER
            // the partsJson content (or any prefix/suffix of it). Earlier
            // versions echoed head500/tail500 to localise the culprit;
            // now that the cause is known (oversized tool_result inlines)
            // and FileReadTool / AIChatViewModel.executeFileRead enforce
            // an 80 KB hard cap upstream, only metadata is needed for
            // future audits.
            val OVERSIZE_THRESHOLD = 50_000
            val oversized = messages.filter { it.partsJson.length >= OVERSIZE_THRESHOLD }
            if (oversized.isNotEmpty()) {
                println(
                    "[T-HANG-DIAG] oversized-messages session=$sessionId " +
                        "count=${oversized.size} threshold=${OVERSIZE_THRESHOLD}",
                )
                for (m in oversized) {
                    val raw = m.partsJson
                    val len = raw.length
                    val hasToolUse = raw.contains("\"toolUse\"")
                    val hasToolResult = raw.contains("\"toolResult\"")
                    val hasImage = raw.contains("\"image\"") || raw.contains("\"image_url\"")
                    val hasBase64 = raw.contains("data:image") || raw.contains(";base64,")
                    println(
                        "[T-HANG-DIAG] oversized id=${m.id} role=${m.role} " +
                            "createdAt=${m.createdAt} len=$len " +
                            "hasToolUse=$hasToolUse hasToolResult=$hasToolResult " +
                            "hasImage=$hasImage hasBase64=$hasBase64 " +
                            "streamInterrupts=${m.streamInterruptCount}",
                    )
                }
            }
        }

        // Rebuild agentHistory from persisted messages.
        // Pre-built off-Main inside the withContext(Dispatchers.IO) block
        // above to avoid re-parsing partsJson on the UI thread. Safe to
        // bulk-addAll here because loadSession runs once at init before
        // any sender writes into agentHistory.
        agentHistory.addAll(loaded.llmHistory)
        val tHangDiagAfterAgentHistory = System.currentTimeMillis()
        println(
            "[T-HANG-DIAG] agentHistory rebuilt session=$sessionId tookMs=${tHangDiagAfterAgentHistory - tHangDiagAfterTransform}",
        )

        // Restore the most-recent compact summary, if any, so the first
        // outgoing turn after reopening a compacted session still sees
        // the folded-away context via [effectiveAgentHistory]. Also gray
        // out every UI message that falls before the marker's boundary —
        // mirrors iOS Phase 2.5 restore (AIChatViewModel.swift:3360+).
        val marker = runCatching { chatRepository.dao.latestCompactMarker(sessionId) }
            .onFailure { Log.w(TAG, "latestCompactMarker failed: ${it.message}") }
            .getOrNull()
        _compactSummary.value = marker?.summary
        _cachedLatestMarker = marker

        com.openminis.app.diagnostics.PerfLongCtx.step(
            sessionId,
            "stateflow.emit.begin",
            "count=${ordered.size}",
        )
        // [T-android-larky-longsession-followup] Reset the tail
        // window to its initial cap on every session (re)load. Without
        // this a freshly opened session would inherit the previous
        // session's enlarged cap (set via loadOlderMessages), defeating
        // the windowing intent on the first paint of every new session.
        _visibleMessageCap.value = INITIAL_VISIBLE_MESSAGE_CAP
        _messages.value = if (marker == null) {
            ordered
        } else {
            // Phase 2.5: build the historyDbIds set used by the
            // createdAt self-heal to filter to anchors that are
            // actually represented in agentHistory. Mirrors iOS
            // AIChatViewModel+Persistence.swift:406-408.
            val historyDbIds: Set<String> = buildSet {
                for (m in loaded.llmHistory) {
                    m.dbMessageId?.takeIf { it.isNotEmpty() }?.let { add(it) }
                }
            }
            applyCompactMarkerGraying(ordered, marker, loaded.messages, historyDbIds)
        }

        // Cold-start interrupt detection: an agent loop that was killed by
        // the OS (or app force-quit) leaves agentHistory in one of four
        // tell-tale shapes. Detecting any of them lets the user tap
        // Resume to pick up where the model left off — the in-memory
        // [_canResume] flag set by [handleUserCancelledCleanup] is lost
        // across cold starts so we have to re-derive it from the DB.
        // Mirrors iOS AIChatViewModel.loadSession lines 3546-3581.
        //   Case A: last entry is user with all-toolResult parts —
        //           tools completed but the next model call never fired.
        //   Case B: last entry is assistant with any tool_use parts —
        //           the model requested tools that never executed.
        //   Case C: last entry is user with the synthetic "Continue"
        //           reminder text — text-cancel handler committed it
        //           but [resume] never re-entered the agent loop.
        //   Case D: last entry is a non-empty user turn with no reply —
        //           the process died after persisting the prompt.
        val lastEntry = agentHistory.lastOrNull()
        val trackerActive = SessionActivityTracker.isActive(activeSessionId)
        if (lastEntry != null && !_isStreaming.value && !trackerActive) {
            val shape = InterruptedTailDetector.classify(lastEntry)
            if (shape != InterruptedTailShape.NONE) {
                _canResume.value = true
                Log.i(TAG, "loadSession: detected interrupted agent loop, canResume=true (shape=$shape)")
            }
        }
        // [T-android-run-checkpoint] Reconcile the run records a previous
        // process left behind before the tail detector's verdict is shown.
        runCatching { reconcileRunCheckpoints() }
        } catch (e: com.openminis.app.provider.ProviderTransportPolicy.Violation) {
            // Keep transport fail-closed while surfacing stale cleartext
            // approval as a session error instead of crashing the app.
            Log.w(TAG, "provider configuration rejected while loading session: " + e.message)
            currentProvider = null
            _activeEntryId.value = null
            _error.value = e.message ?: "Provider configuration rejected"
        } finally {
            // T201: open the gate even on early `return@launch` (draft path,
            // missing-session path) and on exception, so the init-time
            // config.collect can never deadlock waiting for us.
            sessionLoaded.value = true
            // [T-HANG-DIAG] total time spent in loadSession from ENTER to
            // either successful completion or early return. tHangDiagStart
            // was captured just inside `try` so this covers the whole
            // body the user perceives as "loading".
            println(
                "[T-HANG-DIAG] loadSession EXIT session=$sessionId " +
                    "totalMs=${System.currentTimeMillis() - tHangDiagStart}",
            )
            com.openminis.app.diagnostics.PerfLongCtx.step(
                sessionId,
                "loadSession.exit",
                "totalMs=${System.currentTimeMillis() - tHangDiagStart}",
            )
        }
    }
}

/**
 * Mark every non-system UI message that falls before [marker]'s boundary
 * as [ChatMessage.isCompactedHistory]. Mirrors iOS Phase 2.5 boundary
 * resolution (AIChatViewModel.swift:3380-3411) but with one improvement
 * over iOS for the compactAll case:
 *
 *   1) `firstKeptMessageId` — first kept message (divider goes BEFORE it)
 *   2) `boundaryMessageId`  — legacy alias of firstKeptMessageId
 *   3) Both null → compactAll. iOS naively places the divider at the end
 *      and grays every loaded UI message, which incorrectly gray-scales
 *      messages persisted AFTER the marker (e.g. follow-up turns sent
 *      between compact and reload). We instead use
 *      `lastCompactedMessageId` to find the last message included in the
 *      compacted range — anything after it stays active. The divider is
 *      placed immediately after that boundary.
 */
/**
 * Phase 2.5 marker restore (Android port of iOS
 * AIChatViewModel+Persistence.swift:236+).
 *
 * Resolution order (mirrors iOS exactly):
 *   1. v2 marker (`version >= 2`) — use `lastCompactedMessageId`
 *      via sourceDbIds range → divider AFTER that UI row
 *   2. v1 compactAll-shape (firstKept/boundary both null,
 *      lcmId set) — same as 1
 *   3. v1 compactBefore (firstKeptMessageId / boundaryMessageId
 *      set) — divider BEFORE that boundary row
 *   4. **createdAt self-heal** — find the last raw with
 *      `createdAt < marker.createdAt` whose id is still in
 *      agentHistory, use it as the new anchor, REWRITE the
 *      marker as v2 + write back to DB. Next load takes the
 *      v2 fast path (no heal needed).
 *   5. Final fallback — insert divider at idx=0, gray NOTHING.
 *      This deliberately differs from the pre-T-compact-v2
 *      behaviour of "divider at bottom, gray everything" which
 *      grayed newly-sent messages on every reload (the
 *      user-reported "divider at top, new messages keep
 *      turning gray" symptom).
 *
 * Suspending because the self-heal path writes back through
 * the DAO. Caller (loadSession) is already on a coroutine.
 */
internal suspend fun ChatViewModel.applyCompactMarkerGraying(
    messages: List<ChatMessage>,
    marker: com.openminis.app.data.db.CompactMarkerEntity,
    rawMessages: List<com.openminis.app.data.db.MessageEntity>,
    historyDbIds: Set<String>,
): List<ChatMessage> {
    // Some legacy rows have empty-string boundaries instead of NULL —
    // treat both as "no boundary" so the compactAll path below kicks in.
    val firstKeptId = (marker.firstKeptMessageId?.takeIf { it.isNotEmpty() })
        ?: (marker.boundaryMessageId?.takeIf { it.isNotEmpty() })
    val lcmId = marker.lastCompactedMessageId?.takeIf { it.isNotEmpty() }

    // ─── Resolve insertIdx ────────────────────────────────────────
    //
    // insertIdx semantics: messages[0 until insertIdx] become grayed
    // (isCompactedHistory=true); the divider sits at insertIdx;
    // messages[insertIdx..] stay active.
    //
    // Special value -1 → "unresolved": skip the rewrite below and
    // return the messages untouched with no divider (the marker is
    // effectively invisible until the user reverts or self-heals).
    // Used when even createdAt fallback fails — better to show no
    // divider than to incorrectly gray live messages.
    var insertIdx = -1
    var healedMarker: com.openminis.app.data.db.CompactMarkerEntity? = null

    // Helper: locate the UI message whose sourceDbIds (or id) contains
    // the given dbId. Matches iOS uiIndexForAnchorRaw, which scans by
    // sourceSortOrder range; Android's equivalent is sourceDbIds.
    fun uiIdxForDbId(dbId: String): Int =
        messages.indexOfLast { msg -> dbId in msg.sourceDbIds || msg.id == dbId }

    if (firstKeptId == null) {
        // v2 OR v1 compactAll-shape — anchored by lcmId.
        val lcmIdx = lcmId?.let { uiIdxForDbId(it) } ?: -1
        if (lcmIdx >= 0) {
            // Happy path: lcmId resolves directly. Divider AFTER anchor.
            insertIdx = lcmIdx + 1
        } else {
            // lcmId missing or orphaned. Try createdAt self-heal.
            val heal = anchorByCreatedAt(rawMessages, marker.createdAt, historyDbIds)
            val healUiIdx = heal?.let { uiIdxForDbId(it.id) } ?: -1
            if (heal != null && healUiIdx >= 0) {
                insertIdx = healUiIdx + 1
                healedMarker = rewriteMarkerForHeal(marker, heal, rawMessages.lastOrNull())
                AppLogger.warning(
                    TAG,
                    "[Compact] Phase2.5 self-heal: orphaned lcmId=${lcmId?.take(8) ?: "nil"} " +
                        "→ newAnchor=${heal.id.take(8)} (createdAt=${heal.createdAt}) " +
                        "→ uiIdx=$healUiIdx insertIdx=$insertIdx",
                )
            } else {
                // Even createdAt heal failed. Place divider at top
                // with NO graying — this is iOS's "insertIdx=0, no
                // gray" branch (Persistence.swift:350-351). The
                // pre-T-compact-v2 behaviour of "cutoff = lastIndex,
                // gray everything" produced the user-reported bug:
                // every new message also fell within [0..cutoff]
                // and was repeatedly grayed on each reload.
                insertIdx = 0
                AppLogger.warning(
                    TAG,
                    "[Compact] Phase2.5 unresolved (heal failed): marker.id=${marker.id.take(8)} " +
                        "lcmId=${lcmId?.take(8) ?: "nil"} — divider at top, no graying",
                )
            }
        }
    } else {
        // v1 compactBefore — anchored by firstKeptId. Divider BEFORE
        // the boundary; boundary is the first active message.
        val bIdx = messages.indexOfFirst { msg ->
            firstKeptId in msg.sourceDbIds || msg.id == firstKeptId
        }
        if (bIdx >= 0) {
            insertIdx = bIdx
        } else {
            // Boundary deleted / orphaned. Try createdAt self-heal —
            // same path as compactAll, then divider AFTER the healed
            // anchor (treating this as an upgrade to v2 compactAll
            // semantics).
            val heal = anchorByCreatedAt(rawMessages, marker.createdAt, historyDbIds)
            val healUiIdx = heal?.let { uiIdxForDbId(it.id) } ?: -1
            if (heal != null && healUiIdx >= 0) {
                insertIdx = healUiIdx + 1
                healedMarker = rewriteMarkerForHeal(marker, heal, rawMessages.lastOrNull())
                AppLogger.warning(
                    TAG,
                    "[Compact] Phase2.5 v1→v2 heal: firstKeptId=${firstKeptId.take(8)} orphaned " +
                        "→ newAnchor=${heal.id.take(8)} → uiIdx=$healUiIdx",
                )
            } else {
                insertIdx = 0
                AppLogger.warning(
                    TAG,
                    "[Compact] Phase2.5 v1 unresolved (heal failed): firstKeptId=${firstKeptId.take(8)} — " +
                        "divider at top, no graying",
                )
            }
        }
    }

    // ─── Persist healed marker (if any) ───────────────────────────
    //
    // Run BEFORE building the UI list so a future loadSession() picks
    // up the v2 fast path. Failure here is non-fatal — UI still
    // renders against the in-memory healed pointer.
    if (healedMarker != null) {
        runCatching { chatRepository.dao.updateCompactMarker(healedMarker) }
            .onFailure { Log.w(TAG, "updateCompactMarker (self-heal) failed: ${it.message}") }
        // Refresh in-memory cache so effectiveAgentHistory and the
        // next compact pass see the upgraded marker. The caller
        // (loadSession) sets _cachedLatestMarker = marker BEFORE
        // calling us, so overwrite with the healed one now.
        _cachedLatestMarker = healedMarker
        _compactSummary.value = healedMarker.summary
    }

    // ─── Apply graying ────────────────────────────────────────────
    val grayed: List<ChatMessage> = if (insertIdx <= 0) {
        // No graying — either explicit no-gray branch or boundary at
        // index 0 (nothing to gray).
        messages
    } else {
        messages.mapIndexed { idx, msg ->
            if (idx >= insertIdx) msg
            else if (msg.role == "system") msg
            else if (msg.isCompactedHistory) msg
            else msg.copy(isCompactedHistory = true)
        }
    }

    // ─── Insert divider row ───────────────────────────────────────
    // T126-marker: match iOS `"\(insertIdx) messages compacted"`
    // (AIChatViewModel.swift:3432). Count = number of UI bubbles
    // above the divider, not marker.compactedCount (which counts raw
    // agentHistory entries — tool_use/tool_result pairs that never
    // appear as their own UI bubble).
    val compactedUICount = (0 until insertIdx.coerceIn(0, grayed.size))
        .count { grayed[it].role != "system" }
    val dividerLabel = "$compactedUICount messages compacted"
    val markerForDivider = healedMarker ?: marker
    val dividerBlock = AssistantBlock(
        id = "compact-divider-${markerForDivider.id}",
        kind = "info",
        content = dividerLabel,
        toolName = "compact",
        toolArgs = markerForDivider.summary,
    )
    val dividerMsg = ChatMessage(
        id = "compact-divider-msg-${markerForDivider.id}",
        role = "system",
        content = "",
        toolBlocks = listOf(dividerBlock),
    )
    val withDivider = grayed.toMutableList()
    withDivider.add(insertIdx.coerceIn(0, withDivider.size), dividerMsg)
    return withDivider
}

/**
 * createdAt self-heal: return the LAST raw message whose
 * `createdAt < markerCreatedAt` AND whose id is still represented in
 * agentHistory (filtered via [historyDbIds]). When [historyDbIds] is
 * empty (no dbIds collected — unusual), the filter degrades to "just
 * the createdAt predicate" so we still recover SOMETHING.
 *
 * Mirrors iOS AIChatViewModel+Compaction.swift:125.
 */
internal fun ChatViewModel.anchorByCreatedAt(
    rawMessages: List<com.openminis.app.data.db.MessageEntity>,
    markerCreatedAt: Long,
    historyDbIds: Set<String>,
): com.openminis.app.data.db.MessageEntity? {
    return rawMessages.lastOrNull { raw ->
        raw.createdAt < markerCreatedAt &&
            (historyDbIds.isEmpty() || raw.id in historyDbIds)
    }
}

/**
 * Build a healed v2 marker that preserves identity (id, sessionId,
 * summary, createdAt, compactedCount) but swaps `lastCompactedMessageId`
 * to the recomputed anchor, zeroes legacy fields, and bumps `version`
 * to 2. Future loads resolve through the corrected lcmId directly
 * without re-running the createdAt fallback.
 *
 * Mirrors iOS AIChatViewModel+Compaction.swift:150.
 */
internal fun ChatViewModel.rewriteMarkerForHeal(
    original: com.openminis.app.data.db.CompactMarkerEntity,
    newAnchor: com.openminis.app.data.db.MessageEntity,
    lastRaw: com.openminis.app.data.db.MessageEntity?,
): com.openminis.app.data.db.CompactMarkerEntity {
    // Legacy sort-order fallback writes a past-end sentinel so any
    // hypothetical v1 reader sees "everything compacted, nothing
    // kept" (graceful degradation, no overlap with live tail).
    // Android's MessageEntity doesn't carry a sortOrder column —
    // use Int.MAX_VALUE like the original compactAll write path.
    return original.copy(
        firstKeptSortOrder = Int.MAX_VALUE,
        boundaryMessageId = null,
        firstKeptMessageId = null,
        lastCompactedMessageId = newAnchor.id,
        uiBoundarySortOrder = null,
        version = 2,
    )
}

/** Restore provider state from a JSON binding string. Returns true if successfully resolved. */
internal fun ChatViewModel.restoreFromBinding(bindingJson: String?): Boolean {
    val binding = com.openminis.app.data.model.ModelBinding.parse(bindingJson)
    if (binding !is com.openminis.app.data.model.ModelBinding.Entry) return false
    val entry = providerRepository.config.value.modelEntries.find { it.id == binding.entryId } ?: return false
    val instance = providerRepository.instance(entry.providerInstanceId) ?: return false
    val apiKey = providerRepository.usableApiKey(instance) ?: return false
    currentModel = entry.model
    _modelName.value = entry.model.displayName
    _providerName.value = instance.label.ifEmpty { entry.model.provider }
    _activeEntryId.value = entry.id
    currentProvider = ProviderFactory.create(instance, apiKey, entry.model, context)
    return true
}

/**
 * Apply an entry's configured thinking default when a session newly binds
 * to that entry, preserving explicit user choices already stored on a session.
 * Context limit is in-memory only on iOS; Android has no equivalent
 * runtime field yet, so we only handle thinking level here.
 *
 * Skips when the entry has no default override (null) — leaves the
 * session's existing override untouched so manual user choices on a
 * pre-bound chat aren't clobbered by a later group re-select that
 * happens to land on the same default state.
 */
internal fun ChatViewModel.applyEntrySessionDefaults(entryId: String) {
    val entry = providerRepository.config.value.modelEntries.find { it.id == entryId } ?: return
    val level = entry.overrides.defaultThinkingLevel ?: return
    if (_thinkingLevel.value == level) return
    _thinkingLevel.value = level
    if (hasNoRow) {
        pendingThinkingOverride = level
        return
    }
    viewModelScope.launch {
        val sid = ensureSession()
        chatRepository.dao.updateThinkingOverride(sid, level.name)
    }
}

/**
 * [T-newchat-default-model-fallback-android] Resolve and apply the default
 * model for a NEW chat: first available Main-slot entry, then last-used
 * entry, then the newest provider's newest text model.
 *
 *   2) last-used model — the entry the user last actively selected / used,
 *      if it still exists, is visible, and its provider is enabled.
 *   3) newest provider's newest text-output model — the final catch-all so
 *      a first-ever chat with providers but no Main-slot/last-used entry gets a
 *      sensible, text-capable default (image/audio-only models excluded).
 *
 * Sets currentModel / currentProvider / the name + activeEntry state flows.
 * Returns true when a model was applied. Mirrors iOS #636. The legacy
 * behaviour here was `allVisibleEntries().firstOrNull()` (the FIRST entry),
 * which ignored both last-used and add-order — replaced by this chain.
 */
internal fun ChatViewModel.applyNewChatDefaultModel(): Boolean {
    val entry = providerRepository.primaryEntry(com.openminis.app.data.model.ModelSlot.main)
        ?: providerRepository.lastUsedVisibleEntry()
        ?: providerRepository.newestProviderNewestTextEntry()
        ?: return false
    val instance = providerRepository.instance(entry.providerInstanceId) ?: return false
    currentModel = entry.model
    _modelName.value = entry.model.displayName
    _activeEntryId.value = entry.id
    _providerName.value = instance.label.ifEmpty { entry.model.provider }
    val apiKey = providerRepository.usableApiKey(instance)
    if (apiKey != null) {
        currentProvider = ProviderFactory.create(instance, apiKey, entry.model, context)
    }
    applyEntrySessionDefaults(entry.id)
    return true
}

/** Select and persist one model entry for this session. */
fun ChatViewModel.selectEntry(entryId: String) {
    val config = providerRepository.config.value
    val entry = config.modelEntries.find { it.id == entryId } ?: return
    val instance = providerRepository.instance(entry.providerInstanceId) ?: return
    val apiKey = providerRepository.usableApiKey(instance) ?: return

    currentModel = entry.model
    _modelName.value = entry.model.displayName
    _providerName.value = instance.label.ifEmpty { entry.model.provider }
    _activeEntryId.value = entry.id
    currentProvider = ProviderFactory.create(instance, apiKey, entry.model, context)
    persistBinding(com.openminis.app.data.model.ModelBinding.encodeEntry(entryId))
    applyEntrySessionDefaults(entry.id)
    // [T-newchat-default-model-fallback-android] Remember this as the
    // global last-used model so the NEXT new chat can reuse it when the
    // Main slot has no available entry.
    providerRepository.lastUsedEntryId = entryId
}

/** Persist the model binding to the DB session (no-op for draft sessions). */
internal fun ChatViewModel.persistBinding(bindingJson: String) {
    val sid = realSessionId.takeIf { it.isNotEmpty() } ?: return
    val modelId = currentModel?.id ?: return
    viewModelScope.launch {
        chatRepository.updateSessionBinding(sid, bindingJson, modelId)
    }
}

internal fun ChatViewModel.findModelEntry(modelId: String) =
    providerRepository.allVisibleEntries().find { it.model.id == modelId }

internal fun ChatViewModel.buildFallbackProviders(primaryProvider: LLMProvider): List<FallbackCandidate> {
    val config = providerRepository.config.value
    val members = providerRepository.availableEntries(com.openminis.app.data.model.ModelSlot.main).map { it.id }
    val currentEntry = _activeEntryId.value
        ?: providerRepository.availableEntries(com.openminis.app.data.model.ModelSlot.main)
            .firstOrNull { it.model.id == primaryProvider.model.id }
            ?.id
    val result = mutableListOf<FallbackCandidate>()
    for (entryId in fallbackEntryIdsInAttemptOrder(members, currentEntry)) {
        val entry = config.modelEntries.find { it.id == entryId } ?: continue
        val instance = config.instances.find { it.id == entry.providerInstanceId } ?: continue
        val apiKey = providerRepository.usableApiKey(instance) ?: continue
        val p = try {
            ProviderFactory.create(instance, apiKey, entry.model, context)
        } catch (_: Exception) { continue }
        result.add(FallbackCandidate(provider = p, entryId = entry.id))
    }
    return result
}

/**
 * Main-slot entries that fallback skipped (disabled instance / missing
 * credential / hidden entry), with reasons. When fallback exhausts, the user
 * needs to know WHY the other configured entries never got tried — e.g. the
 * Claude subscription was logged out, so every Anthropic entry was
 * silently filtered and fallback kept cycling OpenAI-only.
 */
internal fun ChatViewModel.unavailableSlotMembers(): List<String> {
    val config = providerRepository.config.value
    val result = mutableListOf<String>()
    for (entryId in config.slots.main) {
        val entry = config.modelEntries.find { it.id == entryId }
        if (entry == null) {
            result.add("⚠️ Model entry $entryId: Missing")
            continue
        }
        val instance = config.instances.find { it.id == entry.providerInstanceId }
        if (instance == null) {
            result.add("⚠️ ${entry.model.displayName}: Provider missing")
            continue
        }
        val label = instance.label.ifEmpty { entry.model.provider }
        val reason = when {
            entry.isHidden -> "Hidden"
            !instance.isEnabled -> "Disabled"
            providerRepository.usableApiKey(instance) == null -> "Not logged in"
            else -> continue
        }
        result.add("⚠️ ${entry.model.displayName} ($label): $reason")
    }
    return result
}

internal fun ChatViewModel.resolveNextFallbackProvider(): LLMProvider? {
    val currentEntryId = _activeEntryId.value ?: return null
    val config = providerRepository.config.value
    val memberIds = providerRepository.availableEntries(com.openminis.app.data.model.ModelSlot.main).map { it.id }
    for (entryId in fallbackEntryIdsInAttemptOrder(memberIds, currentEntryId)) {
        val entry = config.modelEntries.find { it.id == entryId } ?: continue
        val instance = providerRepository.instance(entry.providerInstanceId) ?: continue
        // [T-disabled-provider-via-slot-android] Skip disabled
        // providers when walking the group's fallback chain so a
        // disabled provider sitting after the current entry doesn't
        // get picked up. buildFallbackProviders already does this; the
        // single-step variant here had the same bug.
        if (!instance.isEnabled) continue
        val apiKey = providerRepository.usableApiKey(instance) ?: continue

        currentModel = entry.model
        _modelName.value = entry.model.displayName
        _activeEntryId.value = entry.id
        val provider = ProviderFactory.create(instance, apiKey, entry.model, context)
        currentProvider = provider
        return provider
    }
    return null
}
