package com.openminis.app.tools

import android.content.Context
import android.util.Log
import com.openminis.app.MinisApp
import com.openminis.app.data.model.AgentToolDefinition
import com.openminis.app.data.model.AgentToolParam
import com.openminis.app.agent.AgentRunner
import org.json.JSONObject
import kotlinx.coroutines.CancellationException

/**
 * Ralph loop (DeepSeek Harness dsh-tool-ralph contract, Android port).
 *
 * Runs one IMMUTABLE objective through a sequence of FRESH child agents:
 * each round spawns a brand-new session that does not inherit this
 * conversation — only the run's state directory and the previous round's
 * bounded handoff report. Each session has its own /var/minis/workspace, so
 * the state that crosses rounds lives in /var/minis/shared/ralph/<runId>
 * (the cross-session shared area), not in a round's workspace. Context cost is therefore
 * bounded by the handoff size per round, not by the accumulated session.
 *
 * Report contract (validated, never silently truncated):
 *   { "status": "continue|complete|blocked", "summary": <non-empty>,
 *     "evidence": <string>, "nextSteps": <string>, "blockedReason": <string> }
 * - continue: another round starts with this report as handoff
 * - complete: ralph returns success with the final report
 * - blocked:  ralph returns failure with the report
 * Invalid / missing / oversized reports fail the whole run (DSH: invalid
 * handoff fails the workflow rather than being truncated or misread).
 */
object RalphTool {
    const val NAME = "ralph"
    private const val TAG = "RalphTool"

    const val DEFAULT_MAX_ROUNDS = 3
    const val MAX_ROUNDS_CEILING = 6
    private const val GUEST_STATE_ROOT = "/var/minis/shared/ralph"
    private const val MAX_HANDOFF_CHARS = 8192
    private const val MAX_SUMMARY_CHARS = 2000

    fun definition(): AgentToolDefinition = AgentToolDefinition(
        name = NAME,
        description = "Run one immutable objective through a sequence of fresh child agents " +
            "(a ralph loop). Each round spawns a brand-new agent with its own context and workspace; " +
            "a per-run directory under /var/minis/shared/ralph/ is the authoritative state, and only " +
            "the previous round's short handoff report crosses rounds, so even very long explorations " +
            "cost bounded context. " +
            "The objective is immutable: do NOT edit it mid-run; if it changed, stop the loop and " +
            "start a new one. Use for long multi-step investigations that would otherwise flood " +
            "your context (reading many files, debugging across subsystems, big refactors). " +
            "Do NOT use for tasks you can finish in one or two steps, and do NOT delegate work " +
            "that needs your in-flight context — the children cannot see this conversation.",
        parameters = mapOf(
            "tool_title" to AgentToolParam("string", "A concise 5-10 word summary of what this ralph run does, shown to the user (e.g. 'Audit auth flow across the codebase'). Use the same language as the user."),
            "objective" to AgentToolParam("string", "The immutable objective every round works toward, in the user's language. Write it as a complete, self-contained task statement."),
            "maxRounds" to AgentToolParam("integer", "Optional round cap (default $DEFAULT_MAX_ROUNDS, ceiling $MAX_ROUNDS_CEILING). The run stops when a round reports complete/blocked or the cap is reached."),
        ),
        required = listOf("tool_title", "objective"),
        propertyOrdering = listOf("tool_title", "objective", "maxRounds"),
        timeoutMs = null, // each round has its own budget via RalphLimits
    )

    suspend fun execute(argsJson: String, parentSessionId: String?, context: Context): ToolExecutionResult {
        val args = runCatching { JSONObject(argsJson) }.getOrNull()
            ?: return ToolExecutionResult("ralph: invalid arguments JSON", false)

        val title = args.optString("tool_title", "Ralph 任务")
        val objective = args.optString("objective").trim()
        if (objective.isEmpty()) {
            return ToolExecutionResult("ralph: `objective` is required and immutable", false, toolTitle = title)
        }
        val maxRounds = args.optInt("maxRounds", DEFAULT_MAX_ROUNDS).coerceIn(1, MAX_ROUNDS_CEILING)

        val app = context.applicationContext as? MinisApp
            ?: return ToolExecutionResult("ralph: app not initialized", false, toolTitle = title)
        // The child's real source prevents nesting without serializing unrelated parents.
        if (parentSessionId != null && app.chatRepository.getSession(parentSessionId)?.source == NAME) {
            return ToolExecutionResult(
                "ralph: a Ralph child cannot start a nested Ralph loop; work directly in this round.",
                false, toolTitle = title, failureKind = ToolFailureKind.PERMISSION_DENIED, executionStarted = false,
            )
        }
        // State that crosses rounds. Every round is a new session with its own workspace, so the only
        // place a file written in round 1 is visible in round 2 is the cross-session shared area.
        val identity = kotlin.coroutines.coroutineContext[com.openminis.app.tools.runtime.ToolExecutor.ExecutionIdentity]
        val runId = identity?.intentId ?: java.util.UUID.randomUUID().toString()
        val stateDir = "$GUEST_STATE_ROOT/$runId"
        val backing = java.io.File(com.openminis.app.runtime.ubuntu.UbuntuPaths.hostShared, "ralph/$runId")
        val manifest = JSONObject().put("version", 1).put("run_id", runId).put("objective", objective)
            .put("parent_session_id", parentSessionId ?: JSONObject.NULL)
            .put("parent_run_id", identity?.model?.runId ?: JSONObject.NULL)
            .put("parent_task_id", identity?.model?.taskId ?: JSONObject.NULL)
            .put("parent_task_revision", identity?.model?.revision ?: JSONObject.NULL)
            .put("parent_stop_generation", identity?.model?.stopGeneration ?: JSONObject.NULL)
            .put("max_rounds", maxRounds).put("rounds_used", 0).put("status", "RUNNING").put("children", org.json.JSONArray())
        var terminalKnown = false
        fun saveManifest() {
            val atomic = android.util.AtomicFile(java.io.File(backing, "run.json"))
            val stream = atomic.startWrite()
            try { stream.write(manifest.toString().toByteArray(Charsets.UTF_8)); atomic.finishWrite(stream) }
            catch (error: Throwable) { atomic.failWrite(stream); throw error }
        }
        return try {
            check(backing.mkdirs() || backing.isDirectory) { "could not create Ralph state directory" }
            saveManifest()
            var handoff = ""
            for (round in 1..maxRounds) {
                identity?.model?.taskId?.let { taskId ->
                    val parent = checkNotNull(app.botTaskRepository.get(taskId)) { "parent_task_missing" }
                    check(parent.revision == identity.model.revision && parent.stopGeneration == identity.model.stopGeneration &&
                        parent.status in setOf(com.openminis.app.data.db.BotTaskEntity.STATUS_ACTIVE,
                            com.openminis.app.data.db.BotTaskEntity.STATUS_WAITING, com.openminis.app.data.db.BotTaskEntity.STATUS_NEEDS_USER)) { "parent_task_changed_or_stopped" }
                }
                manifest.put("rounds_used", round); saveManifest()
                Log.i(TAG, "round $round/$maxRounds objective=${objective.take(60)}")
                val (childId, result) = runRound(
                    context = context,
                    app = app,
                    objective = objective,
                    round = round,
                    maxRounds = maxRounds,
                    handoff = handoff,
                    title = title,
                    stateDir = stateDir,
                    requestId = "ralph:$runId:$round",
                    onChildCreated = { child -> manifest.getJSONArray("children").put(child); saveManifest() },
                )
                if (!result.completed || ToolCheckpointStore.drainPending(context, childId).isNotEmpty()) {
                    return ToolExecutionResult(
                        "ralph: round $round did not settle definitively (status=${result.status}, child=$childId). " +
                            "Child cleanup has completed; inspect its effects before retrying.",
                        false, toolTitle = title, timedOut = result.timedOut,
                        failureKind = if (result.timedOut) ToolFailureKind.TOOL_TIMEOUT else ToolFailureKind.OUTCOME_UNKNOWN,
                    )
                }
                val report = parseReport(result.responseText?.trim().orEmpty(), childId) ?: return ToolExecutionResult(
                    "ralph: round $round failed with an invalid report (child=$childId). " +
                        "Rounds: ${round - 1}, objective unchanged.",
                    false, toolTitle = title,
                )
                manifest.put("handoff", report.toHandoff())
                saveManifest()
                when (report.status) {
                    "complete" -> {
                        terminalKnown = true; manifest.put("status", "COMPLETED"); saveManifest()
                        return ToolExecutionResult(
                            "Ralph complete in $round round(s).\n\nSummary: ${report.summary}" +
                                report.evidence.let { if (it.isBlank()) "" else "\n\nEvidence:\n$it" },                            true, toolTitle = title,
                        )
                    }
                    "blocked" -> {
                        terminalKnown = true; manifest.put("status", "BLOCKED"); saveManifest()
                        return ToolExecutionResult(
                            "Ralph blocked in round $round.\n\nSummary: ${report.summary}" +
                                report.blockedReason.let { if (it.isBlank()) "" else "\n\nBlocked: $it" },                            false, toolTitle = title,
                        )
                    }
                    else -> {
                        // continue: carry the bounded report into the next round.
                        handoff = report.toHandoff()
                    }
                }
            }
            terminalKnown = true; manifest.put("status", "BUDGET_EXHAUSTED"); saveManifest()
            ToolExecutionResult(
                "Ralph finished after $maxRounds round(s) without reporting completion. " +
                    "Last summary: ${reportFrom(handoff)?.summary ?: "(none)"}",
                false, toolTitle = title,
            )
        } catch (e: CancellationException) {
            throw e
        } catch (error: Exception) {
            Log.w(TAG, "ralph failed: ${error.message}", error)
            ToolExecutionResult("ralph failed: ${error.message}", false, toolTitle = title,
                failureKind = ToolFailureKind.OUTCOME_UNKNOWN)
        } finally {
            if (!terminalKnown && backing.isDirectory) kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable + kotlinx.coroutines.Dispatchers.IO) {
                manifest.put("status", "OUTCOME_UNKNOWN")
                saveManifest()
            }
        }
    }

    /** One round: fresh session + prompt with the handoff + parse/validate the JSON report. */
    private suspend fun runRound(
        context: Context,
        app: MinisApp,
        objective: String,
        round: Int,
        maxRounds: Int,
        handoff: String,
        title: String,
        stateDir: String,
        requestId: String,
        onChildCreated: (String) -> Unit,
    ): Pair<String, AgentRunner.PromptResult> {
        val childId = AgentRunner.ensureSession(context, source = "ralph")
        onChildCreated(childId)
        runCatching {
            app.chatRepository.updateSessionTitle(childId, "↳ Ralph $round: " + title.take(36))
        }

        val prompt = buildRoundPrompt(objective, round, maxRounds, handoff, stateDir)
        // The common runner waits for this exact request's cleanup, not a session-wide busy flag
        // or a later user turn. A fresh round cannot outrun the request it follows.
        return childId to AgentRunner.prompt(
            context = context, sessionId = childId, text = prompt, wait = true,
            timeoutMs = RalphLimits.timeoutMs(context), awaitCancellationCleanup = true,
            requestId = requestId,
        )
    }

    private fun buildRoundPrompt(
        objective: String,
        round: Int,
        maxRounds: Int,
        handoff: String,
        stateDir: String,
    ): String = buildString {
        append("You are a fresh agent in round $round/$maxRounds of a ralph loop.\n\n")
        append("IMMUTABLE OBJECTIVE (do not change it; if it is no longer achievable, report blocked):\n")
        append(objective).append("\n\n")
        append("$stateDir is the authoritative state between rounds: keep notes, produced files and ")
        append("anything the next round needs there. Anything earlier rounds produced is on disk there — ")
        append("inspect it rather than assuming. Your own /var/minis/workspace is new this round and ")
        append("is NOT seen by the next round.\n\n")
        if (handoff.isNotBlank()) {
            append("HANDOFF FROM PREVIOUS ROUND (bounded report):\n").append(handoff).append("\n\n")
        }
        append("Work toward the objective. When you are done with this round, end your reply with ")
        append("a plain JSON object (no markdown fences) on its own final line: {\"status\": ")
        append("\"continue\"|\"complete\"|\"blocked\", \"summary\": \"<non-empty, <=$MAX_SUMMARY_CHARS chars>\", ")
        append("\"evidence\": \"<key evidence: paths, command output excerpts>\", ")
        append("\"nextSteps\": \"<plan for the next round, required when continue>\", ")
        append("\"blockedReason\": \"<required when blocked>\"}.\n")
        append("Use continue when the objective is not yet achieved. Use blocked only when it ")
        append("genuinely cannot be achieved (missing dependency, contradiction).\n")
    }

    internal fun parseReport(answer: String, childId: String): RalphReport? {
        val json = lastJsonObject(answer)
            ?: run { Log.w(TAG, "round child $childId produced no parseable JSON report"); return null }
        val status = json.optString("status").trim()
        val summary = json.optString("summary").trim()
        if (status !in setOf("continue", "complete", "blocked") || summary.isEmpty()) {
            Log.w(TAG, "round child $childId invalid report (status=$status summaryLen=${summary.length})")
            return null
        }
        // A summary over the limit is shortened, but never silently: the cut is marked in the text the
        // next round and the user read. (Failing a whole multi-round run over a few extra characters of
        // prose would cost more than it protects.)
        val boundedSummary = if (summary.length <= MAX_SUMMARY_CHARS) summary else {
            Log.w(TAG, "round child $childId summary is ${summary.length} chars; shortened to $MAX_SUMMARY_CHARS")
            val note = "… [summary shortened from ${summary.length} chars]"
            summary.take(MAX_SUMMARY_CHARS - note.length) + note
        }
        val report = RalphReport(
            status = status,
            summary = boundedSummary,
            evidence = json.optString("evidence").trim(),
            nextSteps = json.optString("nextSteps").trim(),
            blockedReason = json.optString("blockedReason").trim(),
        )
        if (status == "continue" && report.nextSteps.isBlank() || status == "blocked" && report.blockedReason.isBlank()) return null
        // Bounded handoff: an oversized report must fail the run, not be truncated silently.
        if (report.toHandoff().length > MAX_HANDOFF_CHARS) {
            Log.w(TAG, "round child $childId handoff exceeds $MAX_HANDOFF_CHARS chars")
            return null
        }
        return report
    }

    /**
     * The last JSON object in [text] that ends at its last `}`. Tries each `{` from the end, so a brace
     * inside a string value (`"evidence": "JSON starts with {"`) does not cut the report in half: that
     * start fails to parse and an earlier one is tried.
     */
    internal fun lastJsonObject(text: String): JSONObject? {
        val end = text.lastIndexOf('}')
        if (end < 0) return null
        var start = text.lastIndexOf('{', end)
        while (start >= 0) {
            runCatching { JSONObject(text.substring(start, end + 1)) }.getOrNull()?.let { return it }
            start = text.lastIndexOf('{', start - 1)
        }
        return null
    }

    private fun reportFrom(handoff: String): RalphReport? {
        if (handoff.isBlank()) return null
        return runCatching { JSONObject(handoff) }.getOrNull()?.let {
            RalphReport(
                status = it.optString("status"),
                summary = it.optString("summary"),
                evidence = it.optString("evidence"),
                nextSteps = it.optString("nextSteps"),
                blockedReason = it.optString("blockedReason"),
            )
        }
    }

    data class RalphReport(
        val status: String,
        val summary: String,
        val evidence: String,
        val nextSteps: String,
        val blockedReason: String,
    ) {
        fun toHandoff(): String = JSONObject()
            .put("status", status)
            .put("summary", summary)
            .put("evidence", evidence)
            .put("nextSteps", nextSteps)
            .put("blockedReason", blockedReason)
            .toString()
    }
}
