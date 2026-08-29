package io.github.slackerllc.minis.tools

import android.content.Context
import android.util.Log
import io.github.slackerllc.minis.MinisApp
import io.github.slackerllc.minis.debug.HeadlessChatRunner
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap

/**
 * Delegate a self-contained sub-task to a child agent that runs in its own
 * session.
 *
 * Why a separate session rather than a nested loop: the child gets a genuinely
 * independent context, so a long exploration ("read 40 files and tell me where
 * X is defined") burns the child's context instead of the parent's. The parent
 * only ever sees the child's final answer. This is the "spawn" shape — the
 * child starts fresh and therefore needs a self-contained prompt; it cannot see
 * what the parent has been doing.
 *
 * Execution reuses [HeadlessChatRunner], which is the same ChatViewModel-backed
 * loop the app and the debug RPC already drive, so a child has the full tool
 * set, the persistent PRoot shell and streaming — without a second agent
 * implementation to keep in sync.
 */
object SubagentTool {
    const val NAME = "subagent"
    private const val TAG = "SubagentTool"
    private const val MAX_ANSWER_CHARS = 60_000

    /**
     * sessionId → delegation depth. Process-scoped on purpose: the cap only has
     * to hold within a live delegation chain, and a restart legitimately starts
     * a new one. Sessions never seen here are depth 0.
     */
    private val depths = ConcurrentHashMap<String, Int>()

    fun depthOf(sessionId: String?): Int = sessionId?.let { depths[it] } ?: 0

    /** Called when a session is known to be a child, to carry the cap downward. */
    private fun markChild(childSessionId: String, parentDepth: Int) {
        depths[childSessionId] = parentDepth + 1
    }

    suspend fun execute(
        argsJson: String,
        parentSessionId: String?,
        context: Context,
    ): ToolExecutionResult {
        val args = runCatching { JSONObject(argsJson) }.getOrNull()
            ?: return ToolExecutionResult("subagent: invalid arguments JSON", false)

        val title = args.optString("tool_title", "委派子任务")
        val prompt = args.optString("prompt").trim()
        if (prompt.isEmpty()) {
            return ToolExecutionResult(
                "subagent: `prompt` is required and must describe the whole task — " +
                    "the child cannot see this conversation.",
                false,
                toolTitle = title,
            )
        }

        val maxDepth = SubagentLimits.maxDepth(context)
        val depth = depthOf(parentSessionId)
        if (depth >= maxDepth) {
            return ToolExecutionResult(
                "subagent: delegation depth limit reached ($maxDepth). " +
                    "Do this task directly instead of delegating further.",
                false,
                toolTitle = title,
            )
        }

        val app = context.applicationContext as? MinisApp
            ?: return ToolExecutionResult("subagent: app not initialized", false, toolTitle = title)

        return try {
            val childId = HeadlessChatRunner.ensureSession(context)
            markChild(childId, depth)

            // Label the child so it is recognisable in the session list rather
            // than looking like a stray conversation the user never started.
            runCatching {
                app.chatRepository.updateSessionTitle(childId, "↳ " + title.take(40))
                app.chatRepository.dao.updateSource(childId, "subagent")
            }

            Log.i(TAG, "spawn depth=${depth + 1} child=${childId.take(8)} title=$title")

            val result = HeadlessChatRunner.prompt(
                context = context,
                sessionId = childId,
                text = prompt,
                wait = true,
                timeoutMs = SubagentLimits.timeoutMs(context),
            )

            val rawAnswer = result.responseText?.trim().orEmpty()
            // Cap the child's answer so an unbounded response cannot flood the
            // parent's context; flag the cut so the model knows it is partial.
            val answer = if (rawAnswer.length > MAX_ANSWER_CHARS) {
                rawAnswer.take(MAX_ANSWER_CHARS) +
                    "\n\n[truncated: child answer exceeded $MAX_ANSWER_CHARS characters]"
            } else rawAnswer
            val ok = result.status == "completed" && answer.isNotEmpty() && !result.timedOut

            if (ok) {
                ToolExecutionResult(answer, true, toolTitle = title)
            } else {
                // Never report a truncated run as success, but never throw the
                // partial answer away either — a child that did 90% of the work
                // before hitting the timeout still has something worth reading.
                val headline = when {
                    result.timedOut -> "subagent timed out after ${SubagentLimits.timeoutMs(context) / 1000}s"
                    answer.isEmpty() -> "subagent finished with no answer (status=${result.status})"
                    else -> "subagent did not complete (status=${result.status})"
                }
                val body = if (answer.isEmpty()) "" else "\n\nPartial output:\n$answer"
                ToolExecutionResult(
                    "$headline. Child session: $childId$body",
                    false,
                    toolTitle = title,
                    timedOut = result.timedOut,
                )
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            // Never swallow coroutine cancellation (e.g. the agent run being
            // cancelled); it must propagate to the caller.
            throw e
        } catch (t: Throwable) {
            Log.w(TAG, "subagent failed: ${t.message}", t)
            ToolExecutionResult("subagent failed: ${t.message}", false, toolTitle = title)
        }
    }
}
