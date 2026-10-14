package com.openminis.app.tools

import android.content.Context
import android.util.Log
import org.json.JSONObject
import org.json.JSONTokener
import java.io.File
import java.nio.channels.Channels
import java.nio.charset.CodingErrorAction
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.NoSuchFileException
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.BasicFileAttributes
import java.util.UUID

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

    class Unavailable(cause: Exception) : IllegalStateException("tool_checkpoint_unavailable", cause)

    data class IntentRecord(
        val callId: String,
        val toolName: String,
        val argsJson: String,
        val at: Long,
        val state: String, // "pending" | "done" | "reported"
        val intentId: String? = null,
        val runId: String? = null,
        val taskId: String? = null,
        val revision: Int? = null,
        val stopGeneration: Int? = null,
    )

    private fun fileFor(context: Context, sessionId: String): File {
        val dir = File(context.filesDir, "checkpoints").apply { mkdirs() }
        return File(dir, sessionId.replace(Regex("[^A-Za-z0-9._-]"), "_") + ".jsonl")
    }

    /** Record an execution intent BEFORE the tool body runs. */
    @Synchronized
    fun recordIntent(context: Context, sessionId: String, callId: String, toolName: String, argsJson: String,
                     runId: String? = null, taskId: String? = null,
                     revision: Int? = null, stopGeneration: Int? = null): String? {
        if (sessionId.isBlank() || callId.isBlank()) return null
        return try {
            recordIntentTo(fileFor(context, sessionId), callId, toolName, argsJson, runId, taskId, revision, stopGeneration)
        } catch (t: Throwable) {
            Log.w(TAG, "recordIntent failed: ${t.message}")
            null
        }
    }

    internal fun recordIntentTo(file: File, callId: String, toolName: String, argsJson: String,
                                runId: String? = null, taskId: String? = null,
                                revision: Int? = null, stopGeneration: Int? = null): String {
        // Never append new work to a recovery file whose existing outcomes cannot be read.
        checkedRows(file)
        require(callId.isNotBlank() && toolName.isNotBlank())
        require(revision == null || revision >= 0)
        require(stopGeneration == null || stopGeneration >= 0)
        val intentId = UUID.randomUUID().toString()
        val args = if (ToolSensitivePolicy.isSensitive(toolName)) {
            ToolSensitivePolicy.ARGUMENTS_PLACEHOLDER
        } else if (com.openminis.app.tools.runtime.ToolRegistry.canonicalName(toolName) == "linux.file.write") {
            // Store only the intended final fingerprint, including for short writes. Never retain
            // an arbitrarily large body or transport-only/sensitive parameters in the sidecar.
            val input = JSONObject(argsJson)
            if (input.opt("path") is String && input.opt("content") is String && !input.optBoolean("append", false)) {
                val bytes = input.getString("content").toByteArray(Charsets.UTF_8)
                JSONObject().put("file_write_summary_version", 1).put("path", input.getString("path"))
                    .put("utf8_size", bytes.size.toLong()).put("sha256", com.openminis.app.util.Sha256.hex(bytes)).toString()
            } else JSONObject().put("file_write_summary_version", 1).put("verifiable", false).toString()
        } else {
            argsJson.take(4000)
        }
        val line = JSONObject()
            .put("callId", callId)
            .put("tool", toolName)
            .put("args", args)
            .put("at", System.currentTimeMillis())
            .put("state", "pending")
            .put("intentId", intentId).put("runId", runId ?: JSONObject.NULL).put("taskId", taskId ?: JSONObject.NULL)
            .put("revision", revision ?: JSONObject.NULL).put("stopGeneration", stopGeneration ?: JSONObject.NULL)
            .toString()
        java.io.FileOutputStream(file, true).use { output ->
            output.write((line + "\n").toByteArray(Charsets.UTF_8))
            output.fd.sync()
        }
        return intentId
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
     * outcome-unknown call, including legacy "reported") are kept exactly. A damaged file is
     * never pruned. Older "done" rows may be removed; an empty file is deleted.
     */
    internal fun settle(file: File, callIds: Set<String>) {
        val rows = checkedRows(file)
        val kept = rows.filter { (_, parsed) ->
            parsed.getString("state") != "done" &&
                (!parsed.isNull("intentId") || parsed.optString("callId") !in callIds)
        }.map { it.first }
        if (kept.size == rows.size) return
        if (kept.isEmpty()) file.delete() else replaceAtomically(file, kept.joinToString("\n") + "\n")
    }

    /** A provider call ID is reusable; acknowledge only the persisted execution identity. */
    @Synchronized
    fun acknowledge(context: Context, sessionId: String, intentIds: Set<String>) {
        if (sessionId.isBlank() || intentIds.isEmpty()) return
        runCatching {
            val file = fileFor(context, sessionId)
            val rows = checkedRows(file)
            val kept = rows.filter { (_, parsed) ->
                parsed.optString("intentId") !in intentIds
            }.map { it.first }
            if (kept.size != rows.size) {
                if (kept.isEmpty()) file.delete() else replaceAtomically(file, kept.joinToString("\n") + "\n")
            }
        }.onFailure { Log.w(TAG, "acknowledge failed: ${it.message}") }
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
        } catch (error: Exception) {
            Log.w(TAG, "drainPending failed: ${error.message}")
            throw Unavailable(error)
        }
    }

    /** Parse the entire file before allowing any read, append or acknowledgement to use it. */
    private fun checkedRows(file: File): List<Pair<String, JSONObject>> {
        val path = file.toPath()
        val lines = try {
            check(Files.readAttributes(path, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS).isRegularFile) {
                "checkpoint_is_not_a_regular_file"
            }
            Files.newByteChannel(path, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS).use { channel ->
                val decoder = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                Channels.newReader(channel, decoder, -1).buffered().use { it.readLines() }
            }
        } catch (error: NoSuchFileException) {
            // Absence is different from permission failure or a dangling link.
            if (Files.notExists(path, LinkOption.NOFOLLOW_LINKS)) return emptyList()
            throw error
        }
        return checkedLines(lines)
    }

    private fun checkedLines(lines: List<String>): List<Pair<String, JSONObject>> {
        val identities = mutableSetOf<String>()
        return lines.mapIndexedNotNull { index, line ->
            if (line.isBlank()) return@mapIndexedNotNull null
            val parsed = try {
                val tokener = JSONTokener(line)
                val value = tokener.nextValue() as? JSONObject ?: error("invalid_checkpoint_object:${index + 1}")
                check(tokener.nextClean() == '\u0000') { "trailing_checkpoint_content:${index + 1}" }
                value
            } catch (_: org.json.JSONException) {
                // JSONTokener's exception includes the input; never attach it to logs or failures.
                error("invalid_checkpoint_json:${index + 1}")
            }
            val state = parsed.opt("state")
            check(state in setOf("pending", "reported", "done")) { "invalid_checkpoint_state:${index + 1}" }
            check(parsed.opt("callId") is String && parsed.getString("callId").isNotBlank()) { "invalid_checkpoint_call:${index + 1}" }
            for (key in listOf("tool", "args")) {
                check((state == "done" && !parsed.has(key)) || parsed.opt(key) is String) { "invalid_checkpoint_$key:${index + 1}" }
            }
            check(!parsed.has("tool") || parsed.getString("tool").isNotBlank()) { "invalid_checkpoint_tool:${index + 1}" }
            val at = parsed.opt("at")
            check((state == "done" && !parsed.has("at")) || ((at is Int || at is Long) && (at as Number).toLong() >= 0)) {
                "invalid_checkpoint_timestamp:${index + 1}"
            }
            for (key in listOf("intentId", "runId", "taskId")) {
                check(parsed.isNull(key) || parsed.opt(key) is String) { "invalid_checkpoint_$key:${index + 1}" }
            }
            for (key in listOf("revision", "stopGeneration")) {
                val value = parsed.opt(key)
                check(parsed.isNull(key) || ((value is Int || value is Long) && (value as Number).toLong() in 0..Int.MAX_VALUE.toLong())) {
                    "invalid_checkpoint_$key:${index + 1}"
                }
            }
            if (!parsed.isNull("intentId")) {
                val id = parsed.getString("intentId")
                check(id.isNotBlank() && identities.add(id)) { "invalid_checkpoint_execution:${index + 1}" }
            }
            line to parsed
        }
    }

    @Synchronized
    fun backupRows(context: Context, sessionId: String): List<String> = checkedRows(fileFor(context, sessionId))
        .filter { it.second.getString("state") != "done" }.map { it.first }

    /** Validate both inputs and preserve exact existing identities; restore never starts an action. */
    @Synchronized
    fun restoreRows(context: Context, sessionId: String, rows: List<String>) {
        require(com.openminis.app.runtime.ubuntu.UbuntuPaths.isSafeSessionId(sessionId)) { "invalid_restore_session" }
        require(rows.size <= 2048 && rows.all { it.length <= 64 * 1024 }) { "restore_checkpoint_limit" }
        val imported = checkedLines(rows).filter { it.second.getString("state") != "done" }.map { row ->
            if (ToolSensitivePolicy.isSensitive(row.second.getString("tool"))) {
                val value = row.second.put("args", ToolSensitivePolicy.ARGUMENTS_PLACEHOLDER)
                value.toString() to value
            } else row
        }
        val file = fileFor(context, sessionId)
        val existing = checkedRows(file)
        val merged = existing.toMutableList()
        for (row in imported) {
            val id = row.second.optString("intentId").takeIf { !row.second.isNull("intentId") }
            val same = existing.filter { id != null && it.second.optString("intentId") == id }
            if (same.isNotEmpty()) check(same.single().second.toString() == row.second.toString()) { "restored_execution_identity_conflict" }
            else if (merged.none { it.first == row.first }) merged.add(row)
        }
        checkedLines(merged.map { it.first })
        if (merged.size != existing.size) replaceAtomically(file, merged.joinToString("\n", postfix = "\n") { it.first })
    }

    fun legacyKey(intent: IntentRecord): String {
        require(intent.intentId == null)
        return com.openminis.app.util.Sha256.hex(JSONObject().put("call", intent.callId).put("tool", intent.toolName)
            .put("args", intent.argsJson).put("at", intent.at).put("run", intent.runId ?: JSONObject.NULL)
            .put("task", intent.taskId ?: JSONObject.NULL).put("revision", intent.revision ?: JSONObject.NULL)
            .put("stop", intent.stopGeneration ?: JSONObject.NULL).toString().toByteArray(Charsets.UTF_8))
    }

    @Synchronized
    fun acknowledgeLegacyObservation(context: Context, sessionId: String, keys: Set<String>) {
        if (keys.isEmpty()) return
        val file = fileFor(context, sessionId)
        val pending = readPending(file)
        val calls = pending.filter { it.intentId == null && legacyKey(it) in keys }
        val kept = checkedRows(file).filterNot { row ->
            row.second.getString("state") != "done" && row.second.isNull("intentId") && calls.any { original ->
                row.second.optString("callId") == original.callId && row.second.optString("tool") == original.toolName &&
                    row.second.optString("args") == original.argsJson && row.second.optLong("at", -1) == original.at &&
                    row.second.optString("runId").takeUnless { row.second.isNull("runId") } == original.runId &&
                    row.second.optString("taskId").takeUnless { row.second.isNull("taskId") } == original.taskId &&
                    row.second.optInt("revision").takeUnless { row.second.isNull("revision") } == original.revision &&
                    row.second.optInt("stopGeneration").takeUnless { row.second.isNull("stopGeneration") } == original.stopGeneration
            }
        }.map { it.first }
        if (kept.size != checkedRows(file).size) replaceAtomically(file, kept.joinToString("\n", postfix = if (kept.isEmpty()) "" else "\n"))
    }

    internal fun readPending(file: File): List<IntentRecord> = checkedRows(file)
        .filter { it.second.getString("state") != "done" }.map { (_, parsed) ->
            IntentRecord(callId = parsed.getString("callId"), toolName = parsed.getString("tool"),
                argsJson = parsed.getString("args"), at = parsed.getLong("at"), state = "pending",
                intentId = parsed.optString("intentId").takeIf { !parsed.isNull("intentId") && it.isNotBlank() },
                runId = parsed.optString("runId").takeIf { !parsed.isNull("runId") && it.isNotBlank() },
                taskId = parsed.optString("taskId").takeIf { !parsed.isNull("taskId") && it.isNotBlank() },
                revision = parsed.optInt("revision").takeIf { !parsed.isNull("revision") },
                stopGeneration = parsed.optInt("stopGeneration").takeIf { !parsed.isNull("stopGeneration") })
        }

    /** Drop all checkpoints for a session (session deleted / compacted). */
    @Synchronized
    fun clearSession(context: Context, sessionId: String) {
        runCatching { fileFor(context, sessionId).delete() }
    }
}
