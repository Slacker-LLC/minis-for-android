package com.openminis.app.ui.chat

import com.openminis.app.agent.Level
import com.openminis.app.agent.ToolLoopDetector
import com.openminis.app.data.model.AgentContentPart
import com.openminis.app.data.model.AgentToolDefinition
import com.openminis.app.logging.AppLogger
import com.openminis.app.service.SessionActivityTracker
import com.openminis.app.tools.ToolExecutionResult
import com.openminis.app.tools.ToolSensitivePolicy
import org.json.JSONObject

/** What a [ToolRound] needs from the conversation it runs in. */
internal interface ToolRoundHost {
    /** True while the next turn is chat-only: no tool may run. */
    val chatOnly: Boolean

    /** The session the round belongs to (logging only). */
    val sessionId: String

    /** Re-renders the assistant message from [blocks] on the main thread. */
    suspend fun refreshUi(assistantId: String, text: String, blocks: List<AssistantBlock>)

    fun preflight(name: String, args: JSONObject, tools: List<AgentToolDefinition>): String?

    suspend fun executeTool(
        name: String,
        argsJson: String,
        toolId: String,
        blocks: MutableList<AssistantBlock>,
        assistantId: String,
        currentText: String,
    ): ToolExecutionResult
}

/**
 * One round of tool calls from a single model turn: gate each call against what was offered, repair its
 * arguments, refuse truncated writes, consult the loop detector, validate, execute, and fold the outcome
 * into the tool blocks and the result parts sent back to the model. Moved out of
 * [ChatViewModel.runAgentLoop] unchanged.
 */
internal class ToolRound(
    private val host: ToolRoundHost,
    private val turn: Int,
    private val turnToolNames: Set<String>,
    private val turnTools: List<AgentToolDefinition>,
    private val assistantId: String,
    private val allToolBlocks: MutableList<AssistantBlock>,
    private val toolInputChunkRings: MutableMap<String, MutableList<String>>,
    private val toolLoopDetector: ToolLoopDetector,
    private val sessionEventEmitter: ChatSessionEventEmitter,
    /** Called right before a call is executed, so a cancellation during it still counts as a side effect. */
    private val onExecuting: () -> Unit,
) {
    /** The tool_result parts to send back, one per call, in call order. */
    val resultParts = mutableListOf<AgentContentPart>()

    suspend fun run(toolCalls: List<Triple<String, String, JSONObject>>, accumulatedText: String) {
        for ((id, name, args) in toolCalls) {
            // GH#32: schema filtering is not a security boundary. A stale
            // provider response, retry, or hallucinated tool call can still
            // name a tool that was not offered this turn. Reject it before
            // repair, permission/status updates, or execution. This also
            // makes host.chatOnly fail closed.
            val isTurnTool = name in turnToolNames ||
                turnTools.any { it.matchesName(name) } ||
                com.openminis.app.tools.runtime.ToolRegistry.canonicalName(name)?.let { canonical ->
                    canonical in turnToolNames || turnTools.any { it.matchesName(canonical) }
                } == true
            if (!isTurnTool) {
                val blockedMessage = if (host.chatOnly) {
                    "Error: Tools are disabled in chat-only mode."
                } else {
                    "Error: Tool '$name' is not available."
                }
                AppLogger.warning(
                    "ToolPreflight",
                    "BLOCKED tool=$name id=$id chatOnly=${host.chatOnly} session=${host.sessionId}",
                )
                val blockIdx = allToolBlocks.indexOfFirst { it.id == id }
                val failContent = if (host.chatOnly) "Disabled in chat-only mode" else "Tool not available"
                if (blockIdx >= 0) {
                    val elapsed = System.currentTimeMillis() - allToolBlocks[blockIdx].startTimeMs
                    allToolBlocks[blockIdx] = allToolBlocks[blockIdx].copy(
                        toolStatus = ToolBlockStatus.FAILED,
                        content = failContent,
                        durationMs = elapsed,
                    )
                    sessionEventEmitter.toolResult(assistantId, allToolBlocks[blockIdx])
                }
                toolLoopDetector.record(
                    toolName = name,
                    params = parseToolParams(args.toString()),
                    result = null,
                    errorMessage = blockedMessage,
                    toolCallId = id,
                )
                resultParts.add(
                    AgentContentPart.ToolResult(
                        id = id,
                        name = name,
                        content = blockedMessage,
                        isError = true,
                    ),
                )
                host.refreshUi(assistantId, accumulatedText, allToolBlocks)
                continue
            }

            // [T-android-overlay-tool-title] Pull tool_title uniformly
            // from args for ALL tools — without this browser_use's
            // tool_title never reached the overlay (only shell_execute
            // had a per-tool status override that surfaced it). Reading
            // it here also means new tools added later automatically
            // get title-in-overlay behavior without per-call plumbing.
            val dispatchToolTitle = try {
                args.optString("tool_title", "").takeIf { it.isNotBlank() }
            } catch (_: Exception) { null }
            SessionActivityTracker.updateToolStatus(
                status = "Running: $name",
                toolName = name,
                isRunning = true,
                toolTitle = dispatchToolTitle,
            )
            // JSON repair (T-tool-json-repair b2c4f8a6): salvage truncated /
            // type-mismatched / typo'd args BEFORE preflight rejects them.
            // Mutates `args` in place; downstream argsStr and preflight see
            // the repaired payload. Mirrors iOS repairToolArgs in
            // AIChatViewModel.swift.
            val repairs = com.openminis.app.provider.ToolJsonRepair.repair(
                name, args, toolInputChunkRings[id]?.lastOrNull(), turnTools,
            )
            if (repairs.isNotEmpty()) {
                AppLogger.warning(
                    "ToolPreflight",
                    "[ToolRepair] REPAIRED tool=$name id=$id strategies=[${repairs.joinToString(", ")}] " +
                        "argsKeys=[${args.keys().asSequence().toList().sorted().joinToString(",")}] " +
                        "rawTail=<<<${toolInputChunkRings[id]?.lastOrNull()?.take(500) ?: ""}>>>"
                )
            }
            // [T-truncated-args-visibility #119] Non-null when THIS call's
            // arguments arrived truncated and were auto-closed. Only the
            // truncation strategy means the VALUE was cut short; coercion
            // and fuzzy-name repairs fix the shape of a complete argument.
            // Mirrors iOS truncationRepairTag.
            val truncationRepairTag: String? = repairs.firstOrNull { it.startsWith("truncation+") }

            // [T-truncated-args-visibility #119] Refuse truncated WRITES.
            // Auto-closing an unterminated JSON string is indistinguishable
            // from the model ending `content` there, so a half file lands on
            // disk while UI and tool result both report success. For writes a
            // partial artifact is silent corruption of user data and is worse
            // than no write at all; read-only and shell tools keep the
            // repair-and-run behaviour. Mirrors iOS ConcurrentTools.
            if (truncationRepairTag != null && (name == "file_write" || name == "file_edit")) {
                val path = args.optString("path", "").ifBlank { args.optString("file_path", "") }
                AppLogger.warning(
                    "ToolPreflight",
                    "[ToolRepair] REFUSED truncated write tool=$name id=$id strategy=$truncationRepairTag path=$path"
                )
                val modelMessage = buildString {
                    append("Error: This call was NOT executed. Its argument stream was truncated ")
                    append("in transit (repair strategy: $truncationRepairTag), so the `content` ")
                    append("your client sent was cut short and would have written an incomplete file")
                    if (path.isNotBlank()) append(" to $path")
                    append(". Nothing was written to disk — the target file is unchanged.\n\n")
                    append("The most likely cause is the response hitting its output-token limit ")
                    append("mid-argument. Re-issue this write in smaller pieces: write the first ")
                    append("part, then append the rest with follow-up calls, rather than repeating ")
                    append("the same oversized call.")
                }
                val refusedIdx = allToolBlocks.indexOfFirst { it.id == id }
                if (refusedIdx >= 0) {
                    val elapsed = System.currentTimeMillis() - allToolBlocks[refusedIdx].startTimeMs
                    allToolBlocks[refusedIdx] = allToolBlocks[refusedIdx].copy(
                        toolStatus = ToolBlockStatus.FAILED,
                        content = "Blocked: arguments were truncated in transit",
                        durationMs = elapsed,
                    )
                    sessionEventEmitter.toolResult(assistantId, allToolBlocks[refusedIdx])
                    host.refreshUi(assistantId, accumulatedText, allToolBlocks)
                }
                toolLoopDetector.record(name, parseToolParams(args.toString()),
                    result = null, errorMessage = modelMessage, toolCallId = id)
                resultParts.add(AgentContentPart.ToolResult(
                    id = id, name = name,
                    content = modelMessage,
                    isError = true,
                ))
                toolInputChunkRings.remove(id)
                continue
            }
            val argsStr = args.toString()
            val paramsMap = parseToolParams(argsStr)
            // Flip PENDING → RUNNING right before the execute dispatch so the UI
            // (tool pill spinner) shows the exact moment execution begins.
            val preIdx = allToolBlocks.indexOfFirst { it.id == id }
            if (preIdx >= 0 && allToolBlocks[preIdx].toolStatus == ToolBlockStatus.PENDING) {
                allToolBlocks[preIdx] = allToolBlocks[preIdx].copy(toolStatus = ToolBlockStatus.RUNNING)
                host.refreshUi(assistantId, accumulatedText, allToolBlocks)
            }

            // Loop-detector check BEFORE execution. CRITICAL outcomes short-circuit
            // the call: synthesize an error result so the tool_use/tool_result pair
            // stays balanced and the LLM sees the block reason.
            val precheck = toolLoopDetector.check(name, paramsMap)
            if (precheck.level == Level.CRITICAL) {
                val blockedMsg = precheck.message ?: "[LOOP BLOCKED] tool execution blocked"
                android.util.Log.w("ToolChain[VM]",
                    "[turn=$turn] tool BLOCKED by loop detector name=$name msg=$blockedMsg")
                AppLogger.warning("ChatViewModel",
                    "tool blocked by loop detector name=$name reason=$blockedMsg")
                val blockIdx = allToolBlocks.indexOfFirst { it.id == id }
                if (blockIdx >= 0) {
                    val elapsed = System.currentTimeMillis() - allToolBlocks[blockIdx].startTimeMs
                    allToolBlocks[blockIdx] = allToolBlocks[blockIdx].copy(
                        toolStatus = ToolBlockStatus.FAILED,
                        content = blockedMsg,
                        durationMs = elapsed,
                    )
                    sessionEventEmitter.toolResult(assistantId, allToolBlocks[blockIdx])
                }
                // Record the blocked attempt so consecutive blocks still
                // count toward the unknown-tool / circuit-breaker windows.
                toolLoopDetector.record(name, paramsMap,
                    result = null, errorMessage = blockedMsg, toolCallId = id)
                resultParts.add(AgentContentPart.ToolResult(
                    id = id, name = name,
                    content = blockedMsg,
                    isError = true,
                ))
                continue
            }

            // Preflight: reject empty / missing-required-field tool calls
            // BEFORE the UI flips to RUNNING and BEFORE executeTool() does
            // any actual work. Mirrors iOS preflightValidateToolCall in
            // AIChatViewModel.swift. Synthesizes a tool_result error so the
            // model can self-correct on the next turn without us spawning
            // shells or touching the filesystem on `{}` args.
            val preflightError = host.preflight(name, args, turnTools)
            if (preflightError != null) {
                val chunkRing: List<String> = toolInputChunkRings.remove(id) ?: emptyList()
                AppLogger.warning(
                    "ToolPreflight",
                    "BLOCKED tool=$name id=$id reason=\"$preflightError\" " +
                        "argsKeys=[${args.keys().asSequence().toList().sorted().joinToString(",")}] " +
                        "chunkCount=${chunkRing.size} " +
                        "lastChunk=<<<${chunkRing.lastOrNull()?.take(500) ?: ""}>>>"
                )
                chunkRing.forEachIndexed { i, snap ->
                    AppLogger.warning(
                        "ToolPreflight",
                        "  chunk[$i] bytes=${snap.toByteArray(Charsets.UTF_8).size} raw=<<<${snap.take(500)}>>>"
                    )
                }
                // English literal — string resource lookup intentionally
                // avoided to keep this commit independent of any in-flight
                // strings.xml refactor in other sessions. Promote to a
                // localized R.string entry in a follow-up if needed.
                val uiMessage = "Blocked invalid tool call"
                // Wording covers both sides of preflight: absent/empty required
                // fields and — since [T-android-tool-arg-schema] — arguments
                // that do not match the declared schema.
                val modelMessage = "Error: Tool call rejected before execution. $preflightError The arguments your client sent did not satisfy the tool's schema — re-issue the call with every required parameter filled in, using the declared types and allowed values. Do not retry with the same arguments."
                val blockIdxPre = allToolBlocks.indexOfFirst { it.id == id }
                if (blockIdxPre >= 0) {
                    val elapsedPre = System.currentTimeMillis() - allToolBlocks[blockIdxPre].startTimeMs
                    allToolBlocks[blockIdxPre] = allToolBlocks[blockIdxPre].copy(
                        toolStatus = ToolBlockStatus.FAILED,
                        content = uiMessage,
                        durationMs = elapsedPre,
                    )
                    sessionEventEmitter.toolResult(assistantId, allToolBlocks[blockIdxPre])
                }
                toolLoopDetector.record(
                    toolName = name, params = paramsMap,
                    result = null, errorMessage = modelMessage, toolCallId = id
                )
                resultParts.add(AgentContentPart.ToolResult(
                    id = id, name = name,
                    content = modelMessage,
                    isError = true,
                ))
                host.refreshUi(assistantId, accumulatedText, allToolBlocks)
                continue
            }

            // [C6-android-model-failure-discipline] From here on this round has
            // produced a side effect: a later failure is terminal.
            onExecuting()
            // logcat can be captured to disk by the diagnostics logger, so a sensitive tool's
            // arguments and output are not written here (see ToolSensitivePolicy).
            val sensitiveTool = ToolSensitivePolicy.isSensitive(name)
            android.util.Log.d("ToolChain[VM]", "[turn=$turn] executeTool START name=$name args=${if (sensitiveTool) "[redacted]" else argsStr.take(200)}")
            val result = host.executeTool(name, argsStr, id, allToolBlocks, assistantId, accumulatedText)
            android.util.Log.d("ToolChain[VM]", "[turn=$turn] executeTool END name=$name success=${result.success} title=${result.toolTitle} outputLen=${result.output.length} output=${if (sensitiveTool) "[redacted]" else result.output.take(200)}")

            // Record post-execution. WARNING text is appended to the tool
            // result so the model sees it on its next turn. No block here —
            // CRITICAL only fires from check() and we already returned above.
            val errMsgForDetector = if (!result.success) result.output else null
            val postRecord = toolLoopDetector.record(
                toolName = name,
                params = paramsMap,
                result = if (result.success) result.output else null,
                errorMessage = errMsgForDetector,
                toolCallId = id,
            )
            val outputForLLM = if (postRecord.level == Level.WARNING && postRecord.message != null) {
                AppLogger.debug("ChatViewModel",
                    "appending loop-warning to tool result name=$name key=${postRecord.warningKey}")
                "${result.output}\n\n${postRecord.message}"
            } else {
                result.output
            }

            val blockIdx = allToolBlocks.indexOfFirst { it.id == id }
            if (blockIdx >= 0) {
                val elapsed = System.currentTimeMillis() - allToolBlocks[blockIdx].startTimeMs
                // Keep live-streamed content if it has more data than the truncated result.
                // T263: takeLast(80) was applied uniformly, but it was sized for
                // shell_execute (long stdout streams where the tail is what
                // matters). For tools whose first line carries metadata —
                // file_read's `[path | N bytes | M lines | showing A-B of M]`
                // banner, file_write/file_edit confirmations, memory_* /
                // browser_use structured headers — clipping the head dropped
                // the banner entirely. iOS routes file_read through a
                // dedicated branch (AIChatViewModel.swift:5229) and avoids
                // this; mirror that intent by gating the trim to shell_execute.
                val existingContent = allToolBlocks[blockIdx].content
                val resultContent = if (name == "shell_execute") {
                    result.output.lines().takeLast(80).joinToString("\n")
                } else {
                    result.output
                }
                val finalContent = if (existingContent.length > resultContent.length) existingContent else resultContent
                // [T-truncated-args-visibility #119] A call built from
                // truncated args must not render as a clean success — that
                // silence is the reported bug. Show it with the same weight
                // as the blocked path. Mirrors iOS ConcurrentTools.
                val finalStatus = when {
                    result.success && truncationRepairTag != null -> ToolBlockStatus.FAILED
                    result.success -> ToolBlockStatus.SUCCESS
                    result.timedOut -> ToolBlockStatus.TIMEOUT
                    else -> ToolBlockStatus.FAILED
                }
                // T-bg-overlay phase 1: tool finished — drop the
                // notification's indeterminate progress bar so the
                // user can tell streaming has paused (LLM step) vs
                // a tool is in flight.
                // [T-overlay-glyph-typed-outcome] Pass the typed
                // outcome so the bg overlay glyph reflects the real
                // SUCCESS / TIMEOUT / FAILED result instead of
                // text-sniffing the stale "Running: foo" status.
                val toolOutcome = when (finalStatus) {
                    ToolBlockStatus.SUCCESS -> com.openminis.app.service.ToolOutcome.Success
                    ToolBlockStatus.TIMEOUT -> com.openminis.app.service.ToolOutcome.Timeout
                    ToolBlockStatus.FAILED -> com.openminis.app.service.ToolOutcome.Error
                    else -> com.openminis.app.service.ToolOutcome.Unknown
                }
                SessionActivityTracker.clearToolRunning(toolOutcome)
                android.util.Log.d("ToolChain[VM]", "[turn=$turn] block[$blockIdx] status→$finalStatus title=${result.toolTitle} contentLen=${finalContent.length}")
                allToolBlocks[blockIdx] = allToolBlocks[blockIdx].copy(
                    toolStatus = finalStatus,
                    content = finalContent,
                    toolTitle = result.toolTitle.ifEmpty { allToolBlocks[blockIdx].toolTitle },
                    durationMs = elapsed,
                    browserURL = result.pageURL ?: allToolBlocks[blockIdx].browserURL,
                    imageFilePath = result.imageFilePath ?: allToolBlocks[blockIdx].imageFilePath,
                )
                sessionEventEmitter.toolResult(assistantId, allToolBlocks[blockIdx])
            }

            // [T-truncated-args-visibility #119] Tell the MODEL its own
            // arguments were altered. Writes never reach here (refused
            // above); this covers the tools we still run repaired, where the
            // model would otherwise assume the args it emitted were the args
            // that ran. Mirrors iOS ConcurrentTools.
            val outputForLLMWithNote = if (truncationRepairTag != null) {
                outputForLLM + "\n\n<system-reminder>The argument stream for this call was " +
                    "truncated in transit and auto-closed by the client (repair strategy: " +
                    "$truncationRepairTag) before execution. The arguments actually used may be " +
                    "incomplete — verify the result and re-issue the call with complete " +
                    "arguments if anything is missing.</system-reminder>"
            } else {
                outputForLLM
            }

            resultParts.add(AgentContentPart.ToolResult(
                id = id,
                name = name,
                content = outputForLLMWithNote,
                isError = !result.success,
                imageData = result.imageData,
                imageMimeType = result.imageMimeType,
                imageLinuxPath = result.imageLinuxPath,
            ))
        }
    }
}

/**
 * Parameters of a tool call as a map for the loop detector. Malformed JSON degrades gracefully to an
 * empty map — the detector still hashes the tool name, so identical bad calls are still detected.
 */
internal fun parseToolParams(argsJson: String): Map<String, Any?> {
    if (argsJson.isBlank()) return emptyMap()
    return try {
        val obj = JSONObject(argsJson)
        val out = HashMap<String, Any?>(obj.length())
        val keys = obj.keys()
        while (keys.hasNext()) {
            val k = keys.next()
            val v = obj.get(k)
            out[k] = if (v == JSONObject.NULL) null else v
        }
        out
    } catch (_: Exception) {
        emptyMap()
    }
}
