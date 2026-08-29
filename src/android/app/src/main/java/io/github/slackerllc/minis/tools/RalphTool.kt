package io.github.slackerllc.minis.tools

import android.content.Context
import android.util.Log
import io.github.slackerllc.minis.MinisApp
import io.github.slackerllc.minis.data.model.AgentToolDefinition
import io.github.slackerllc.minis.data.model.AgentToolParam
import io.github.slackerllc.minis.debug.HeadlessChatRunner
import org.json.JSONObject
import java.util.concurrent.atomic.AtomicInteger

/**
 * Ralph loop (DeepSeek Harness dsh-tool-ralph contract, Android port).
 *
 * Runs one IMMUTABLE objective through a sequence of FRESH child agents:
 * each round spawns a brand-new session that does not inherit this
 * conversation — only the shared workspace (authoritative state) and the
 * previous round's bounded handoff report. Context cost is therefore
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
    private const val MAX_HANDOFF_CHARS = 8192
    private const val MAX_SUMMARY_CHARS = 2000

    /** Non-zero while a ralph run is active: ralph never nests. */
    private val activeRuns = AtomicInteger(0)

    fun definition(): AgentToolDefinition = AgentToolDefinition(
        name = NAME,
        description = "Run one immutable objective through a sequence of fresh child agents " +
            "(a ralph loop). Each round spawns a brand-new agent with its own context; the shared " +
            "workspace is the authoritative state and only the previous round's short handoff " +
            "report crosses rounds, so even very long explorations cost bounded context. " +
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
        timeoutMs = null, // each round has its own budget via SubagentLimits
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

        // Ralph never nests: a child agent inside a ralph run calling ralph
        // again would multiply context instead of bounding it.
        if (activeRuns.get() > 0) {
            return ToolExecutionResult(
                "ralph: a ralph run is already active; do this task directly instead of nesting.",
                false, toolTitle = title,
            )
        }

        val app = context.applicationContext as? MinisApp
            ?: return ToolExecutionResult("ralph: app not initialized", false, toolTitle = title)

        activeRuns.incrementAndGet()
        return try {
            var handoff = ""
            for (round in 1..maxRounds) {
                Log.i(TAG, "round $round/$maxRounds objective=${objective.take(60)}")
                val report = runRound(
                    context = context,
                    app = app,
                    objective = objective,
                    round = round,
                    maxRounds = maxRounds,
                    handoff = handoff,
                    title = title,
                ) ?: return ToolExecutionResult(
                    "ralph: round $round failed with an invalid report (see child session). " +
                        "Rounds: ${round - 1}, objective unchanged.",
                    false, toolTitle = title,
                )
                when (report.status) {
                    "complete" -> {
                        return ToolExecutionResult(
                            "Ralph complete in $round round(s).\n\nSummary: ${report.summary}" +
                                report.evidence.let { if (it.isBlank()) "" else "\n\nEvidence:\n$it" },                            true, toolTitle = title,
                        )
                    }
                    "blocked" -> {
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
            ToolExecutionResult(
                "Ralph finished after $maxRounds round(s) without reporting completion. " +
                    "Last summary: ${reportFrom(handoff)?.summary ?: "(none)"}",
                false, toolTitle = title,
            )
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (t: Throwable) {
            Log.w(TAG, "ralph failed: ${t.message}", t)
            ToolExecutionResult("ralph failed: ${t.message}", false, toolTitle = title)
        } finally {
            activeRuns.decrementAndGet()
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
    ): RalphReport? {
        val childId = HeadlessChatRunner.ensureSession(context)
        runCatching {
            app.chatRepository.updateSessionTitle(childId, "↳ Ralph $round: " + title.take(36))
            app.chatRepository.dao.updateSource(childId, "ralph")
        }

        val prompt = buildRoundPrompt(objective, round, maxRounds, handoff)
        val result = HeadlessChatRunner.prompt(
            context = context,
            sessionId = childId,
            text = prompt,
            wait = true,
            timeoutMs = SubagentLimits.timeoutMs(context),
        )

        val answer = result.responseText?.trim().orEmpty()
        if (answer.isEmpty() || result.timedOut || result.status != "completed") {
            Log.w(TAG, "round $round child did not complete (status=${result.status} timedOut=${result.timedOut})")
            return null
        }
        return parseReport(answer, childId)
    }

    private fun buildRoundPrompt(objective: String, round: Int, maxRounds: Int, handoff: String): String = buildString {
        append("You are a fresh agent in round $round/$maxRounds of a ralph loop.\n\n")
        append("IMMUTABLE OBJECTIVE (do not change it; if it is no longer achievable, report blocked):\n")
        append(objective).append("\n\n")
        append("The shared workspace (/var/minis/workspace) is the authoritative state between rounds. ")
        append("Anything earlier rounds produced is on disk there — inspect it rather than assuming.\n\n")
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

    private fun parseReport(answer: String, childId: String): RalphReport? {
        // Extract the last JSON object in the reply (the round report).
        val start = answer.lastIndexOf('{')
        val end = answer.lastIndexOf('}')
        if (start < 0 || end <= start) {
            Log.w(TAG, "round child $childId produced no JSON report")
            return null
        }
        val json = runCatching { JSONObject(answer.substring(start, end + 1)) }.getOrNull()
            ?: run { Log.w(TAG, "round child $childId produced unparseable JSON"); return null }
        val status = json.optString("status").trim()
        val summary = json.optString("summary").trim()
        if (status !in setOf("continue", "complete", "blocked") || summary.isEmpty()) {
            Log.w(TAG, "round child $childId invalid report (status=$status summaryLen=${summary.length})")
            return null
        }
        val report = RalphReport(
            status = status,
            summary = summary.take(MAX_SUMMARY_CHARS),
            evidence = json.optString("evidence").trim().take(MAX_HANDOFF_CHARS),
            nextSteps = json.optString("nextSteps").trim().take(MAX_HANDOFF_CHARS),
            blockedReason = json.optString("blockedReason").trim().take(MAX_HANDOFF_CHARS),
        )
        // Bounded handoff: an oversized report must fail the run, not be truncated silently.
        if (report.toHandoff().length > MAX_HANDOFF_CHARS) {
            Log.w(TAG, "round child $childId handoff exceeds $MAX_HANDOFF_CHARS chars")
            return null
        }
        return report
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
