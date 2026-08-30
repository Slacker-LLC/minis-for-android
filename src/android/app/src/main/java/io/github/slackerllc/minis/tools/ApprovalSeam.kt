package io.github.slackerllc.minis.tools

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * One-time tool approval seam (DeepSeek Harness dsh-user-approval contract,
 * Android port).
 *
 * Dangerous operations (destructive shell commands, etc.) declare themselves
 * through [request]; the seam asks the user (phone UI / Web Remote), returns
 * allowed-once | rejected | cancelled | unavailable, and records an audit
 * pair (asked/decided) in the log. Policy is ask | never; "never" rejects
 * without asking. Missing responders close as cancelled/unavailable.
 *
 * Minimal surface: policy stored in SharedPreferences, pending requests
 * in-process (a pending request only matters while the agent turn that
 * asked it is alive), answered via agent.approval.* RPC on the Web Remote
 * and via [answer] from any UI surface.
 */
object ApprovalSeam {
    private const val TAG = "ApprovalSeam"
    private const val PREFS = "approval_seam"
    private const val KEY_POLICY = "policy"
    private const val ANSWER_TIMEOUT_MS = 120_000L

    const val POLICY_ASK = "ask"
    const val POLICY_NEVER = "never"

    data class ApprovalRequest(
        val id: String,
        val sessionId: String,
        val toolName: String,
        val summary: String,
        val createdAt: Long = System.currentTimeMillis(),
        val deferred: CompletableDeferred<Boolean> = CompletableDeferred(),
    )

    data class ApprovalDecision(val decision: String, val answeredBy: String? = null)

    private val pending = ConcurrentHashMap<String, ApprovalRequest>()

    fun policy(context: Context): String =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_POLICY, POLICY_ASK)
            ?.takeIf { it == POLICY_ASK || it == POLICY_NEVER }
            ?: POLICY_ASK

    fun setPolicy(context: Context, policy: String) {
        if (policy != POLICY_ASK && policy != POLICY_NEVER) return
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY_POLICY, policy).apply()
    }

    /**
     * Request a one-time approval for a dangerous operation. Returns
     * allowed-once when answered positively, otherwise rejected / cancelled
     * (timeout) / unavailable (no responder in time). Never throws.
     */
    suspend fun request(
        context: Context,
        sessionId: String,
        toolName: String,
        summary: String,
    ): ApprovalDecision {
        if (policy(context) == POLICY_NEVER) {
            Log.w(TAG, "approval policy=never; rejecting $toolName: $summary")
            return ApprovalDecision("rejected", "policy")
        }
        val req = ApprovalRequest(
            id = UUID.randomUUID().toString(),
            sessionId = sessionId,
            toolName = toolName,
            summary = summary.take(500),
        )
        pending[req.id] = req
        Log.i(TAG, "approval/asked id=${req.id.take(8)} tool=$toolName session=$sessionId summary=${summary.take(120)}")
        val allowed = withTimeoutOrNull(ANSWER_TIMEOUT_MS) { req.deferred.await() }
        pending.remove(req.id)
        return when {
            allowed == true -> ApprovalDecision("allowed-once", "user")
            allowed == false -> ApprovalDecision("rejected", "user")
            else -> ApprovalDecision("cancelled", null)
        }
    }

    /** Answer a pending request (from phone UI / Web Remote RPC). */
    fun answer(approvalId: String, allowed: Boolean): Boolean {
        val req = pending[approvalId] ?: return false
        val decided = req.deferred.complete(allowed)
        if (decided) {
            Log.i(TAG, "approval/decided id=${approvalId.take(8)} allowed=$allowed")
        }
        return decided
    }

    fun pendingFor(sessionId: String?): List<ApprovalRequest> =
        pending.values
            .filter { sessionId == null || it.sessionId == sessionId }
            .sortedBy { it.createdAt }

    /** Cancel all pending approvals for a session (run cancelled). */
    fun cancelForSession(sessionId: String) {
        pending.values.filter { it.sessionId == sessionId }.forEach { req ->
            req.deferred.complete(false)
            pending.remove(req.id)
        }
    }
}

/**
 * Detects destructive shell commands that should go through the approval
 * seam. Conservative regex list: only clearly destructive patterns, to avoid
 * false positives on legitimate work (e.g. `rm file.txt` is fine,
 * `rm -rf /` is not).
 */
object DangerousCommandPolicy {
    private val DANGEROUS_PATTERNS = listOf(
        Regex("""\brm\s+(-[a-z]*r[a-z]*f[a-z]*|-[a-z]*f[a-z]*r[a-z]*)\s+(/|/\*|~)""", RegexOption.IGNORE_CASE),
        Regex("""\bmkfs\b""", RegexOption.IGNORE_CASE),
        Regex("""\bdd\s+.*of=/dev/""", RegexOption.IGNORE_CASE),
        Regex("""\b:\(\)\s*\{\s*:\|:&\s*\}\s*;\s*:"""),
        Regex("""\b>\s*/dev/sd"""),
        Regex("""\bshutdown\b|\breboot\b|\bpoweroff\b""", RegexOption.IGNORE_CASE),
        Regex("""\bchmod\s+[-+]?[0-7]{3,4}\s+/""", RegexOption.IGNORE_CASE),
        Regex("""\bcurl\s+.*\|\s*(sudo\s+)?(sh|bash)\s*$""", RegexOption.IGNORE_CASE),
    )

    /** Returns a human-readable reason when [command] is dangerous, else null. */
    fun dangerousReason(command: String): String? {
        val c = command.trim()
        if (c.isEmpty()) return null
        for (pattern in DANGEROUS_PATTERNS) {
            if (pattern.containsMatchIn(c)) {
                return "matches dangerous pattern: " + pattern.pattern.take(60)
            }
        }
        return null
    }
}
