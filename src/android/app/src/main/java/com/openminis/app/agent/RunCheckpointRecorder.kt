package com.openminis.app.agent

import android.content.Context
import org.json.JSONObject

/**
 * [T-android-run-checkpoint] Writes one run's recovery log.
 *
 * Ported from Eta `agent/runtime/AgentRunCheckpointRecorder.kt` and
 * `agent/runtime/AgentEventRecoveryProjection.kt` (Mangi-11/Eta @ c15de97);
 * attribution is centralised in PROVENANCE.md.
 *
 * Streaming text arrives in fragments; the recorder merges consecutive
 * fragments of the same block and flushes when either 512 characters or 250 ms
 * have accumulated, so a long answer costs a handful of appends instead of one
 * per token. Tool-argument fragments are deliberately dropped: only the current
 * model turn needs them, and the recovery log must not carry a half-written
 * argument object that could be mistaken for a call.
 */
class RunCheckpointRecorder private constructor(
    private val context: Context,
    private val sessionId: String,
    private val runId: String,
    private val nanoTime: () -> Long,
) {
    private data class PendingDelta(
        val kind: String,
        val index: Int,
        val chars: Int,
        val text: String,
    )

    private var pendingDelta: PendingDelta? = null
    private var lastFlushNanos = nanoTime()

    /**
     * One streamed fragment of assistant output. [kind] identifies the block
     * kind; tool-argument fragments are filtered here.
     */
    fun accept(kind: String, index: Int, text: String) {
        if (kind == KIND_TOOL_ARGUMENTS || text.isEmpty()) return
        val pending = pendingDelta
        pendingDelta = if (pending != null && pending.kind == kind && pending.index == index) {
            pending.copy(chars = pending.chars + text.length, text = pending.text + text)
        } else {
            flushPendingDelta()
            PendingDelta(kind = kind, index = index, chars = text.length, text = text)
        }
        val buffered = pendingDelta
        if (
            buffered != null &&
            (buffered.chars >= MAX_BUFFERED_DELTA_CHARS || nanoTime() - lastFlushNanos >= MAX_BUFFERED_DELTA_NANOS)
        ) {
            flushPendingDelta()
        }
    }

    /** Commits the buffered fragment; the log is removed by the terminal ACK. */
    fun seal() {
        flushPendingDelta()
    }

    /** Drops the buffered fragment and the whole checkpoint (the run never started). */
    fun discard() {
        pendingDelta = null
        RunCheckpointStore.remove(context, sessionId, runId)
    }

    private fun flushPendingDelta() {
        val event = pendingDelta ?: return
        pendingDelta = null
        val json = JSONObject()
            .put("type", "assistantDelta")
            .put("kind", event.kind)
            .put("index", event.index)
            .put("chars", event.chars)
            .put("text", event.text)
            .toString()
        RunCheckpointStore.appendEvent(context, sessionId, runId, json)
        lastFlushNanos = nanoTime()
    }

    companion object {
        /** Block kind whose fragments never enter the recovery log. */
        const val KIND_TOOL_ARGUMENTS = "tool_arguments"

        private const val MAX_BUFFERED_DELTA_CHARS = 512
        private const val MAX_BUFFERED_DELTA_NANOS = 250_000_000L

        /**
         * Opens the checkpoint and returns its recorder, or null when the run
         * cannot be checkpointed (blank ids, unknown entry source): the run then
         * proceeds without recovery instead of writing an unusable record.
         */
        fun create(
            context: Context,
            sessionId: String,
            runId: String,
            operation: String = RunCheckpointStore.OPERATION_CHAT,
            entrySource: String,
            entryPayload: String? = null,
            nanoTime: () -> Long = System::nanoTime,
        ): RunCheckpointRecorder? {
            val appContext = context.applicationContext
            val started = RunCheckpointStore.start(
                context = appContext,
                sessionId = sessionId,
                runId = runId,
                operation = operation,
                entrySource = entrySource,
                entryPayload = entryPayload,
            )
            if (!started) return null
            return RunCheckpointRecorder(appContext, sessionId, runId, nanoTime)
        }
    }
}
