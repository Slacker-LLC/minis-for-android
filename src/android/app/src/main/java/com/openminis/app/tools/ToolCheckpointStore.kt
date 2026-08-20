package com.openminis.app.tools

import android.content.Context
import android.util.Log
import org.json.JSONObject
import java.io.File

/**
 * Execution-intent checkpoints (DeepSeek Harness dsh-session-checkpoint-policy
 * contract, Android port).
 *
 * Before a top-level tool body runs we persist an intent row; after it
 * settles we mark it done. If the process dies between the two (kill by the
 * OS, crash, power loss — all routine on phones), the row stays pending and
 * the next turn can inject a model-visible TOOL_OUTCOME_UNKNOWN result so
 * the agent does not blindly re-run a call that may already have had side
 * effects (duplicate file writes, double API charges, ...).
 *
 * Storage is a per-session JSONL sidecar under filesDir/checkpoints/
 * (append-only, replayable — same spirit as the DSH JSONL persistence,
 * without touching Room). Writes are serialized per process.
 */
object ToolCheckpointStore {
    private const val TAG = "ToolCheckpointStore"
    private const val MAX_PENDING_PER_SESSION = 50

    data class IntentRecord(
        val callId: String,
        val toolName: String,
        val argsJson: String,
        val at: Long,
        val state: String, // "pending" | "done" | "reported"
    )

    private fun fileFor(context: Context, sessionId: String): File {
        val dir = File(context.filesDir, "checkpoints").apply { mkdirs() }
        return File(dir, sessionId.replace(Regex("[^A-Za-z0-9._-]"), "_") + ".jsonl")
    }

    /** Record an execution intent BEFORE the tool body runs. */
    @Synchronized
    fun recordIntent(context: Context, sessionId: String, callId: String, toolName: String, argsJson: String) {
        if (sessionId.isBlank() || callId.isBlank()) return
        try {
            val line = JSONObject()
                .put("callId", callId)
                .put("tool", toolName)
                .put("args", argsJson.take(4000))
                .put("at", System.currentTimeMillis())
                .put("state", "pending")
                .toString()
            fileFor(context, sessionId).appendText(line + "\n")
        } catch (t: Throwable) {
            // Checkpoints must never take the agent turn down.
            Log.w(TAG, "recordIntent failed: ${t.message}")
        }
    }

    /** Mark the intent settled AFTER the tool body returns (success or failure). */
    @Synchronized
    fun markDone(context: Context, sessionId: String, callId: String, success: Boolean) {
        if (sessionId.isBlank() || callId.isBlank()) return
        try {
            val f = fileFor(context, sessionId)
            if (!f.exists()) return
            val lines = f.readLines().toMutableList()
            var changed = false
            for (i in lines.indices) {
                val parsed = runCatching { JSONObject(lines[i]) }.getOrNull() ?: continue
                if (parsed.optString("callId") == callId && parsed.optString("state") == "pending") {
                    parsed.put("state", if (success) "done" else "done-failed")
                    parsed.put("finishedAt", System.currentTimeMillis())
                    lines[i] = parsed.toString()
                    changed = true
                }
            }
            if (changed) f.writeText(lines.joinToString("\n") + "\n")
        } catch (t: Throwable) {
            Log.w(TAG, "markDone failed: ${t.message}")
        }
    }

    /**
     * Intents that are still pending (tool body started, no result persisted).
     * Returns them and flips them to "reported" so they are injected exactly
     * once, even if history is rebuilt multiple times.
     */
    @Synchronized
    fun drainPending(context: Context, sessionId: String): List<IntentRecord> {
        if (sessionId.isBlank()) return emptyList()
        return try {
            val f = fileFor(context, sessionId)
            if (!f.exists()) return emptyList()
            val lines = f.readLines().toMutableList()
            val pending = mutableListOf<IntentRecord>()
            var changed = false
            for (i in lines.indices) {
                val parsed = runCatching { JSONObject(lines[i]) }.getOrNull() ?: continue
                if (parsed.optString("state") == "pending") {
                    pending += IntentRecord(
                        callId = parsed.optString("callId"),
                        toolName = parsed.optString("tool"),
                        argsJson = parsed.optString("args"),
                        at = parsed.optLong("at"),
                        state = "pending",
                    )
                    parsed.put("state", "reported")
                    lines[i] = parsed.toString()
                    changed = true
                }
            }
            if (changed) f.writeText(lines.joinToString("\n") + "\n")
            pending.take(MAX_PENDING_PER_SESSION)
        } catch (t: Throwable) {
            Log.w(TAG, "drainPending failed: ${t.message}")
            emptyList()
        }
    }

    /** Drop all checkpoints for a session (session deleted / compacted). */
    @Synchronized
    fun clearSession(context: Context, sessionId: String) {
        runCatching { fileFor(context, sessionId).delete() }
    }
}
