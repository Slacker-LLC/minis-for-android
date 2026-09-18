package com.openminis.app.agent

import android.content.Context
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * [T-android-run-checkpoint] In-flight run checkpoint: the run's UI events, its
 * redacted transcript and its model context snapshot.
 *
 * Ported from Eta `agent/runtime/AgentRunCheckpointStore.kt` (Mangi-11/Eta @
 * c15de97); attribution is centralised in PROVENANCE.md.
 *
 * Eta keeps the three in one Room row plus an event table. Minis already stores
 * its execution-intent checkpoints as an append-only JSONL sidecar under
 * `filesDir` (see [com.openminis.app.tools.ToolCheckpointStore]) and has no
 * migration budget for a parallel run table, so the port keeps Eta's record
 * shape and decision rules on the same sidecar pattern:
 *
 * - the first line opens the run; **without it every later event is dropped**
 *   instead of being buffered for a run nobody can recover;
 * - the transcript and the context snapshot are delivered independently,
 *   because neither can be derived from the other;
 * - the terminal marker is written once and refuses every later write;
 * - any line that cannot be decoded marks the whole checkpoint untrusted
 *   (`damaged`), so recovery treats it as interrupted and never replays the
 *   tool calls it describes;
 * - records are bounded (events, transcript items and bytes, snapshot chars)
 *   and the number of orphaned checkpoints is capped by deleting the oldest
 *   first, with a log line.
 */
object RunCheckpointStore {
    private const val TAG = "RunCheckpointStore"

    const val OPERATION_CHAT = "chat"
    const val OPERATION_COMPACT = "compact"
    const val OPERATION_REWRITE = "rewrite"

    /** Entry sources that may open a checkpoint; anything else is refused. */
    private val ENTRY_SOURCES = setOf("chat-ui", "bot", "tool-rerun")

    const val STATE_RUNNING = "running"
    const val STATE_TERMINAL = "terminal"

    const val MAX_EVENTS = 2048
    const val MAX_TRANSCRIPT_ITEMS = 512
    const val MAX_TRANSCRIPT_BYTES = 2 * 1024 * 1024
    const val MAX_SNAPSHOT_CHARS = 120_000
    const val MAX_ORPHAN_CHECKPOINTS = 32
    private const val MAX_EVENT_CHARS = 64 * 1024

    data class Checkpoint(
        val sessionId: String,
        val runId: String,
        val operation: String,
        val entrySource: String,
        val entryPayload: String?,
        val state: String,
        val events: List<String>,
        val transcript: List<String>,
        val transcriptTruncated: Boolean,
        val contextSnapshot: String?,
        val createdAt: Long,
        val updatedAt: Long,
        /** True when a line failed to decode; the record may not be replayed. */
        val damaged: Boolean,
    ) {
        val terminal: Boolean get() = state == STATE_TERMINAL
    }

    private fun dir(context: Context): File =
        File(context.applicationContext.filesDir, "run-checkpoints").apply { mkdirs() }

    private fun fileFor(context: Context, sessionId: String, runId: String): File =
        File(dir(context), sanitize(sessionId) + "~" + sanitize(runId) + ".jsonl")

    private fun sanitize(value: String): String =
        value.replace(Regex("[^A-Za-z0-9._-]"), "_").take(120)

    /**
     * Opens a checkpoint. Returns false - and writes nothing - when the run
     * cannot be identified or comes from an entry source this store does not
     * know: such a run simply has no recovery, which is the fail-closed choice.
     */
    @Synchronized
    fun start(
        context: Context,
        sessionId: String,
        runId: String,
        operation: String = OPERATION_CHAT,
        entrySource: String,
        entryPayload: String? = null,
        now: Long = System.currentTimeMillis(),
    ): Boolean {
        if (sessionId.isBlank() || runId.isBlank()) return false
        if (entrySource !in ENTRY_SOURCES) return false
        val file = fileFor(context, sessionId, runId)
        if (file.exists()) return false
        return try {
            val header = JSONObject()
                .put("record", "run")
                .put("sessionId", sessionId)
                .put("runId", runId)
                .put("operation", operation)
                .put("entrySource", entrySource)
                .put("entryPayload", entryPayload ?: JSONObject.NULL)
                .put("createdAt", now)
            file.writeText(header.toString() + "\n")
            sweepOrphans(context, except = file)
            true
        } catch (t: Throwable) {
            Log.w(TAG, "start failed: " + t.message)
            false
        }
    }

    /**
     * Appends one UI-visible event. Events for a run without a checkpoint row
     * are dropped: buffering them would recreate the state the header exists to
     * make explicit.
     */
    @Synchronized
    fun appendEvent(context: Context, sessionId: String, runId: String, eventJson: String): Boolean {
        val file = fileFor(context, sessionId, runId)
        if (!file.exists()) return false
        val header = readHeader(file) ?: return false
        if (header.optString("state") == STATE_TERMINAL) return false
        val existing = countRecords(file, "event")
        if (existing >= MAX_EVENTS) return false
        val bounded = if (eventJson.length > MAX_EVENT_CHARS) {
            JSONObject()
                .put("truncatedEvent", true)
                .put("type", "truncated")
                .put("chars", eventJson.length)
                .toString()
        } else {
            eventJson
        }
        return appendLine(file, JSONObject().put("record", "event").put("sortIndex", existing).put("event", bounded))
    }

    /**
     * Stores the run's transcript. It is already redacted by the caller (see
     * [com.openminis.app.tools.ToolSensitivePolicy]); the store only bounds it
     * and keeps the tail, marking the loss instead of pretending it is whole.
     */
    @Synchronized
    fun saveTranscript(
        context: Context,
        sessionId: String,
        runId: String,
        transcript: List<String>,
    ): Boolean {
        val file = fileFor(context, sessionId, runId)
        if (!file.exists()) return false
        var items = transcript.map { it.take(MAX_TRANSCRIPT_BYTES) }
        var truncated = false
        if (items.size > MAX_TRANSCRIPT_ITEMS) {
            items = items.takeLast(MAX_TRANSCRIPT_ITEMS)
            truncated = true
        }
        var bytes = items.sumOf { it.toByteArray(Charsets.UTF_8).size }
        while (bytes > MAX_TRANSCRIPT_BYTES && items.isNotEmpty()) {
            bytes -= items.first().toByteArray(Charsets.UTF_8).size
            items = items.drop(1)
            truncated = true
        }
        return appendLine(
            file,
            JSONObject()
                .put("record", "transcript")
                .put("truncated", truncated)
                .put("items", JSONArray(items)),
        )
    }

    /** Stores the model context snapshot, bounded to the documented ceiling. */
    @Synchronized
    fun saveContext(context: Context, sessionId: String, runId: String, snapshot: String): Boolean {
        val file = fileFor(context, sessionId, runId)
        if (!file.exists()) return false
        val bounded = snapshot.take(MAX_SNAPSHOT_CHARS)
        return appendLine(
            file,
            JSONObject()
                .put("record", "context")
                .put("truncated", bounded.length != snapshot.length)
                .put("snapshot", bounded),
        )
    }

    /**
     * Marks the run finished. The first call wins; every later call is refused,
     * which is what keeps an acknowledged run from being written back to.
     */
    @Synchronized
    fun markTerminal(context: Context, sessionId: String, runId: String, now: Long = System.currentTimeMillis()): Boolean {
        val file = fileFor(context, sessionId, runId)
        if (!file.exists()) return false
        val header = readHeader(file) ?: return false
        if (header.optString("state") == STATE_TERMINAL) return false
        return appendLine(file, JSONObject().put("record", "terminal").put("at", now))
    }

    /** Deletes a checkpoint after its terminal state has been consumed. */
    @Synchronized
    fun remove(context: Context, sessionId: String, runId: String) {
        val file = fileFor(context, sessionId, runId)
        runCatching { if (file.exists()) file.delete() }
    }

    /** Loads one run, or null when it was never opened or is unreadable. */
    @Synchronized
    fun load(context: Context, sessionId: String, runId: String): Checkpoint? =
        read(fileFor(context, sessionId, runId))

    /** Every checkpoint that is still on disk, oldest first. */
    @Synchronized
    fun list(context: Context): List<Checkpoint> {
        val files = dir(context).listFiles()?.filter { it.isFile && it.name.endsWith(".jsonl") } ?: return emptyList()
        return files.mapNotNull { read(it) }.sortedBy { it.createdAt }
    }

    /**
     * Keeps the orphan count bounded by dropping the oldest checkpoints first.
     * The deletion is logged, never silent.
     */
    private fun sweepOrphans(context: Context, except: File) {
        val files = dir(context).listFiles()?.filter { it.isFile && it.name.endsWith(".jsonl") && it != except }
            ?: return
        if (files.size + 1 <= MAX_ORPHAN_CHECKPOINTS) return
        val oldest = files.mapNotNull { file -> read(file)?.let { it.createdAt to file } }
            .sortedBy { it.first }
            .take(files.size + 1 - MAX_ORPHAN_CHECKPOINTS)
        oldest.forEach { (createdAt, file) ->
            Log.w(TAG, "dropping the oldest run checkpoint " + file.name + " (created " + createdAt + ")")
            runCatching { file.delete() }
        }
    }

    private fun appendLine(file: File, record: JSONObject): Boolean = try {
        file.appendText(record.toString() + "\n")
        true
    } catch (t: Throwable) {
        Log.w(TAG, "append failed: " + t.message)
        false
    }

    private fun readHeader(file: File): JSONObject? = try {
        file.useLines { lines ->
            lines.firstOrNull()?.let { JSONObject(it) }
        }
    } catch (t: Throwable) {
        null
    }

    private fun countRecords(file: File, record: String): Int = try {
        file.useLines { lines -> lines.count { it.contains("\"record\":\"" + record + "\"") } }
    } catch (t: Throwable) {
        0
    }

    private fun read(file: File): Checkpoint? {
        if (!file.exists()) return null
        var sessionId = ""
        var runId = ""
        var operation = OPERATION_CHAT
        var entrySource = ""
        var entryPayload: String? = null
        var createdAt = 0L
        var updatedAt = 0L
        var state = STATE_RUNNING
        var damaged = false
        var transcriptTruncated = false
        var snapshot: String? = null
        val events = mutableListOf<String>()
        var transcript = emptyList<String>()
        var sawHeader = false
        try {
            file.forEachLine { line ->
                if (line.isBlank()) return@forEachLine
                val record = runCatching { JSONObject(line) }.getOrElse {
                    damaged = true
                    return@forEachLine
                }
                when (record.optString("record")) {
                    "run" -> {
                        if (sawHeader) {
                            damaged = true
                        } else {
                            sawHeader = true
                            sessionId = record.optString("sessionId")
                            runId = record.optString("runId")
                            operation = record.optString("operation", OPERATION_CHAT)
                            entrySource = record.optString("entrySource")
                            entryPayload = if (record.isNull("entryPayload")) null else record.optString("entryPayload")
                            createdAt = record.optLong("createdAt", 0L)
                            updatedAt = createdAt
                        }
                    }
                    "event" -> {
                        if (events.size < MAX_EVENTS) events += record.optString("event")
                    }
                    "transcript" -> {
                        val items = record.optJSONArray("items")
                        transcript = if (items == null) {
                            damaged = true
                            emptyList()
                        } else {
                            (0 until items.length()).mapNotNull { items.optString(it).takeIf(String::isNotEmpty) }
                        }
                        transcriptTruncated = transcriptTruncated || record.optBoolean("truncated", false)
                    }
                    "context" -> snapshot = record.optString("snapshot")
                    "terminal" -> {
                        if (state == STATE_TERMINAL) damaged = true else state = STATE_TERMINAL
                        updatedAt = record.optLong("at", updatedAt)
                    }
                    else -> damaged = true
                }
            }
        } catch (t: Throwable) {
            Log.w(TAG, "read failed for " + file.name + ": " + t.message)
            damaged = true
        }
        if (!sawHeader) return null
        return Checkpoint(
            sessionId = sessionId,
            runId = runId,
            operation = operation,
            entrySource = entrySource,
            entryPayload = entryPayload,
            state = state,
            events = events,
            transcript = transcript,
            transcriptTruncated = transcriptTruncated,
            contextSnapshot = snapshot,
            createdAt = createdAt,
            updatedAt = maxOf(if (updatedAt == 0L) createdAt else updatedAt, file.lastModified()),
            damaged = damaged,
        )
    }
}
