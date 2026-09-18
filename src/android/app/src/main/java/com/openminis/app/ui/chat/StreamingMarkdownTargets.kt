package com.openminis.app.ui.chat

import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel

/**
 * [T-android-streaming-state] Parse-target pipeline for the streaming markdown
 * tail.
 *
 * Ported from Eta @ c15de97 `ui/components/StreamingMarkdownState.kt`
 * (`StreamingMarkdownTarget`, `consumeStreamingMarkdownTargets`). The reason it
 * exists verbatim in both projects: a live block gets a new text target every
 * paced tick, and the naive "cancel the previous parse, start a new one" shape
 * starves the display — under a fast stream every in-flight parse is thrown away
 * and nothing is ever published. Funnelling targets through a CONFLATED channel
 * and running ONE consumer coroutine fixes that:

 *  - a target that is already superseded is skipped instead of parsed;
 *  - a parse result is still published when the newer target merely APPENDED to
 *    it (an appended chunk cannot invalidate the prefix that was parsed), so a
 *    fast stream keeps painting intermediate states;
 *  - a result is dropped only when the stream was rewritten (the newer target
 *    no longer extends the parsed source) or when a completed snapshot has
 *    already been replaced by different content.
 */
internal data class StreamingMarkdownTarget(
    val content: String,
    val isStreaming: Boolean,
)

/** One parsed snapshot: what it was parsed from, and the parsed payload. */
internal data class StreamingMarkdownSnapshot<T>(
    val originalSource: String,
    val renderedSource: String,
    val isComplete: Boolean,
    val payload: T,
)

/**
 * Consume [targets] until the channel closes, parsing on the consumer's
 * dispatcher (callers wrap the expensive part in `withContext(Dispatchers.Default)`)
 * and publishing through [publish].
 */
internal suspend fun <T> consumeStreamingMarkdownTargets(
    targets: ReceiveChannel<StreamingMarkdownTarget>,
    parse: suspend (StreamingMarkdownTarget) -> StreamingMarkdownSnapshot<T>,
    publish: (StreamingMarkdownSnapshot<T>) -> Unit,
) {
    var target = targets.receiveCatching().getOrNull() ?: return
    while (true) {
        // Drop everything but the newest pending target before doing work: a
        // conflated channel keeps only the latest any, and intermediate states
        // are not worth a parse each.
        while (true) {
            target = targets.tryReceive().getOrNull() ?: break
        }
        val parsed = parse(target)
        val newerTarget = targets.tryReceive().getOrNull()
        // An appended chunk does not invalidate the prefix that was parsed, so
        // the snapshot is still worth publishing (a fast stream would otherwise
        // never paint). Only an upstream rewrite — or a completed snapshot that
        // a different content replaced — drops it.
        val supersededCompleteSnapshot = newerTarget != null && parsed.isComplete &&
            !isStreamingMarkdownTargetComplete(
                content = newerTarget.content,
                isStreaming = newerTarget.isStreaming,
                snapshotContent = parsed.originalSource,
                snapshotComplete = true,
            )
        if (newerTarget == null ||
            (newerTarget.content.startsWith(parsed.originalSource) && !supersededCompleteSnapshot)
        ) {
            publish(parsed)
        }
        target = newerTarget ?: targets.receiveCatching().getOrNull() ?: return
    }
}

/**
 * Conflated target channel for one live markdown block. [submit] is safe to
 * call from composition effects; the consumer owns the parsing.
 */
internal class StreamingMarkdownTargets {
    private val channel = Channel<StreamingMarkdownTarget>(Channel.CONFLATED)

    fun submit(content: String, isStreaming: Boolean) {
        channel.trySend(StreamingMarkdownTarget(content, isStreaming))
    }

    val receiveChannel: ReceiveChannel<StreamingMarkdownTarget> get() = channel

    fun close() {
        channel.close()
    }
}
