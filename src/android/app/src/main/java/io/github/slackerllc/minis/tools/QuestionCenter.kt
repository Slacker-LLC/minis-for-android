package io.github.slackerllc.minis.tools

import kotlinx.coroutines.CompletableDeferred
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * In-process registry of pending `ask_user_question` cards.
 *
 * The agent loop suspends on [PendingQuestion.deferred] until the Web Remote
 * answers via `chat.question.answer`, times out, or the run is cancelled.
 * Process-scoped on purpose: a pending question only matters while the agent
 * run that asked it is alive in this process.
 */
data class QuestionOption(
    val label: String,
    val value: String,
    val recommended: Boolean = false,
)

data class QuestionAnswer(
    val selected: List<String> = emptyList(),
    val custom: String? = null,
    val skipped: Boolean = false,
)

data class PendingQuestion(
    val id: String,
    val sessionId: String,
    val prompt: String,
    val options: List<QuestionOption>,
    val multiple: Boolean,
    val allowCustom: Boolean,
    val createdAt: Long,
    val deferred: CompletableDeferred<QuestionAnswer>,
)

object QuestionCenter {

    private val questions = ConcurrentHashMap<String, PendingQuestion>()

    fun register(question: PendingQuestion): PendingQuestion {
        questions[question.id] = question
        return question
    }

    fun pendingFor(sessionId: String?): List<PendingQuestion> =
        questions.values
            .filter { sessionId == null || it.sessionId == sessionId }
            .sortedBy { it.createdAt }

    /**
     * Complete the deferred answer. Returns false when the question is unknown
     * or was already answered (idempotent by design — the web UI may retry).
     */
    fun answer(questionId: String, answer: QuestionAnswer): Boolean {
        val q = questions[questionId] ?: return false
        val completed = q.deferred.complete(answer)
        // A successfully answered question is no longer pending; drop it so the
        // registry does not accumulate answered cards until the run ends.
        if (completed) questions.remove(questionId)
        return completed
    }

    /** Drop a single question from the registry (e.g. after it timed out). */
    fun remove(questionId: String) {
        questions.remove(questionId)
    }

    /** Drop all questions for a session (e.g. when the run is cancelled). */
    fun cancelForSession(sessionId: String, reason: String) {
        questions.values
            .filter { it.sessionId == sessionId }
            .forEach { q ->
                q.deferred.complete(QuestionAnswer(skipped = true))
                questions.remove(q.id)
            }
    }
}
