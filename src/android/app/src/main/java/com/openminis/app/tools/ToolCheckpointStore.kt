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
 * Storage is a per-session JSONL sidecar under filesDir/checkpoints/. Only
 * unsettled intents are kept: a settled call is removed, so the file stays the
 * size of the calls in flight rather than of the whole session, and it is
 * deleted with the session. A sensitive tool's arguments (see
 * [ToolSensitivePolicy]) are never written here; recovery reports the call by
 * name. Writes are serialized per process.
 */
object ToolCheckpointStore {
    private const val TAG = "ToolCheckpointStore"

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
            recordIntentTo(fileFor(context, sessionId), callId, toolName, argsJson)
        } catch (t: Throwable) {
            // Checkpoints must never take the agent turn down.
            Log.w(TAG, "recordIntent failed: ${t.message}")
        }
    }

    internal fun recordIntentTo(file: File, callId: String, toolName: String, argsJson: String) {
        val args = if (ToolSensitivePolicy.isSensitive(toolName)) {
            ToolSensitivePolicy.ARGUMENTS_PLACEHOLDER
        } else {
            argsJson.take(4000)
        }
        val line = JSONObject()
            .put("callId", callId)
            .put("tool", toolName)
            .put("args", args)
            .put("at", System.currentTimeMillis())
            .put("state", "pending")
            .toString()
        file.appendText(line + "\n")
    }

    /** Mark the intent settled AFTER the tool body returns (success or failure). */
    @Synchronized
    fun markDone(context: Context, sessionId: String, callId: String, success: Boolean) {
        if (callId.isBlank()) return
        markDoneBatch(context, sessionId, mapOf(callId to success))
    }

    @Synchronized
    fun markDoneBatch(context: Context, sessionId: String, outcomes: Map<String, Boolean>) {
        if (sessionId.isBlank() || outcomes.isEmpty()) return
        try {
            settle(fileFor(context, sessionId), outcomes.keys)
        } catch (t: Throwable) {
            Log.w(TAG, "markDone failed: ${t.message}")
        }
    }

    /**
     * Remove the intents of [callIds] once their results are persisted. Lines still pending (an
     * outcome-unknown call) are kept exactly; anything else — settled rows, older "done" rows from
     * before this was pruned, unreadable lines — is dropped. An empty file is deleted.
     */
    internal fun settle(file: File, callIds: Set<String>) {
        if (!file.exists()) return
        val lines = file.readLines().filter { it.isNotBlank() }
        val kept = lines.filter { line ->
            val parsed = runCatching { JSONObject(line) }.getOrNull() ?: return@filter false
            parsed.optString("state") == "pending" && parsed.optString("callId") !in callIds
        }
        if (kept.size == lines.size) return
        if (kept.isEmpty()) file.delete() else replaceAtomically(file, kept.joinToString("\n") + "\n")
    }

    internal fun replaceAtomically(file: File, content: String) {
        val temporary = File.createTempFile("checkpoint-", ".tmp", file.absoluteFile.parentFile)
        try {
            java.io.FileOutputStream(temporary).use { output ->
                output.write(content.toByteArray(Charsets.UTF_8))
                output.fd.sync()
            }
            java.nio.file.Files.move(temporary.toPath(), file.toPath(),
                java.nio.file.StandardCopyOption.ATOMIC_MOVE,
                java.nio.file.StandardCopyOption.REPLACE_EXISTING)
        } finally {
            temporary.delete()
        }
    }

    /**
     * Intents that are still pending (tool body started, no result persisted).
     * Non-destructive snapshot: history inspection must not acknowledge delivery.
     */
    @Synchronized
    fun drainPending(context: Context, sessionId: String): List<IntentRecord> {
        if (sessionId.isBlank()) return emptyList()
        return try {
            readPending(fileFor(context, sessionId))
        } catch (t: Throwable) {
            Log.w(TAG, "drainPending failed: ${t.message}")
            emptyList()
        }
    }

    internal fun readPending(f: File): List<IntentRecord> {
            if (!f.exists()) return emptyList()
            val lines = f.readLines()
            val pending = mutableListOf<IntentRecord>()
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
                    // Reading history is not delivery or durable acknowledgement.
                    // Keep the intent pending until a result has been persisted.
                }
            }
            return pending
    }

    /** Drop all checkpoints for a session (session deleted / compacted). */
    @Synchronized
    fun clearSession(context: Context, sessionId: String) {
        runCatching { fileFor(context, sessionId).delete() }
    }
}
