package io.github.slackerllc.minis.tools

import android.content.Context
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject

/**
 * `ask_user_question` — pause the agent turn and ask the human a question.
 *
 * Mirrors the DeepSeek Harness `ask_user_question` tool: the model calls it
 * when it needs confirmation, a choice, or missing input to continue. The
 * Web Remote renders a question card (radio / multi-select / custom answer /
 * skip) and the answer resumes the suspended tool call as a structured result.
 */
object AskUserQuestionTool {
    const val NAME = "ask_user_question"

    const val DEFAULT_TIMEOUT_MINUTES = 10
    const val MAX_TIMEOUT_MINUTES = 30
    private const val MAX_OPTIONS = 12

    suspend fun execute(
        argsJson: String,
        sessionId: String?,
        context: Context,
    ): ToolExecutionResult {
        val args = runCatching { JSONObject(argsJson) }.getOrNull()
            ?: return ToolExecutionResult("ask_user_question: invalid arguments JSON", false)
        val title = args.optString("tool_title", "需要你确认")

        val prompt = args.optString("question").trim()
        if (prompt.isEmpty()) {
            return ToolExecutionResult(
                "ask_user_question: `question` is required and must be a concise question for the user.",
                false,
                toolTitle = title,
            )
        }

        val options = parseOptions(args.optJSONArray("options"))
        val multiple = args.optBoolean("multiple", false)
        val allowCustom = args.optBoolean("allowCustom", true)
        val timeoutMinutes = args.optInt("timeoutMinutes", DEFAULT_TIMEOUT_MINUTES)
            .coerceIn(1, MAX_TIMEOUT_MINUTES)

        val questionId = java.util.UUID.randomUUID().toString()
        val deferred = CompletableDeferred<QuestionAnswer>()
        QuestionCenter.register(
            PendingQuestion(
                id = questionId,
                sessionId = sessionId ?: "",
                prompt = prompt,
                options = options,
                multiple = multiple,
                allowCustom = allowCustom,
                createdAt = System.currentTimeMillis(),
                deferred = deferred,
            )
        )

        val answer = withTimeoutOrNull(timeoutMinutes * 60_000L) { deferred.await() }
        if (answer == null) {
            // The card is no longer pending — drop it so the registry does not
            // keep a stale question around after the timeout.
            QuestionCenter.remove(questionId)
            return ToolExecutionResult(
                "ask_user_question timed out after $timeoutMinutes minute(s) with no answer. " +
                    "Do not ask again; proceed with the most reasonable assumption.",
                false,
                toolTitle = title,
            )
        }
        if (answer.skipped) {
            return ToolExecutionResult(
                "The user skipped the question. Do not ask again; use your best judgment.",
                false,
                toolTitle = title,
            )
        }

        val parts = mutableListOf<String>()
        if (answer.selected.isNotEmpty()) {
            parts.add("selected=" + answer.selected.joinToString(","))
        }
        answer.custom?.takeIf { it.isNotBlank() }?.let { parts.add("custom=" + it) }
        if (parts.isEmpty()) {
            return ToolExecutionResult(
                "ask_user_question: the user submitted an empty answer. Ask a clearer question or proceed.",
                false,
                toolTitle = title,
            )
        }
        return ToolExecutionResult(
            "User answered: " + parts.joinToString("; "),
            true,
            toolTitle = title,
        )
    }

    private fun parseOptions(arr: JSONArray?): List<QuestionOption> {
        if (arr == null) return emptyList()
        val out = mutableListOf<QuestionOption>()
        for (i in 0 until arr.length().coerceAtMost(MAX_OPTIONS)) {
            val o = arr.optJSONObject(i) ?: continue
            val label = o.optString("label", "").trim()
            val value = o.optString("value", label).trim()
            if (label.isEmpty() && value.isEmpty()) continue
            out.add(QuestionOption(label = label, value = value, recommended = o.optBoolean("recommended", false)))
        }
        return out
    }
}
