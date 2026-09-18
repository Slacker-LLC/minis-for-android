package com.openminis.app.ui.chat

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue

/**
 * [T-android-streaming-state] Restore bookkeeping for a streamed markdown
 * block, ported verbatim from Eta @ c15de97
 * `ui/components/StreamingMarkdownRestoreState.kt`.
 *
 * The boundary of a restore cycle is decided by the content that has already
 * been laid out: a layout callback belonging to a previous generation must not
 * release the current pause, so every cycle carries a generation number and a
 * baseline. [completeLayout] reports true exactly once per [begin], when the
 * rendered text has caught up with what the caller currently has.
 */
internal class StreamingMarkdownRestoreState {
    var generation by mutableIntStateOf(0)
        private set
    private var baseline: String? = null

    fun begin(content: String) {
        generation += 1
        baseline = content
    }

    fun pause() {
        generation += 1
        baseline = null
    }

    fun completeLayout(generation: Int, renderedContent: String, currentContent: String): Boolean {
        val pending = baseline ?: return false
        if (generation != this.generation) return false
        val caughtUp = renderedContent == currentContent ||
            (renderedContent.startsWith(pending) && currentContent.startsWith(renderedContent))
        if (!caughtUp) return false
        baseline = null
        return true
    }
}

/**
 * Terminal-delivery predicate, ported verbatim from Eta @ c15de97
 * `ui/components/StreamingMarkdownRestoreState.kt`: a parsed/rendered snapshot
 * may only be treated as FINAL when the stream has ended, the snapshot itself
 * was parsed from a complete stream, and it still corresponds to the content the
 * caller holds. Anything else means "re-render the real source".
 */
internal fun isStreamingMarkdownTargetComplete(
    content: String,
    isStreaming: Boolean,
    snapshotContent: String?,
    snapshotComplete: Boolean,
): Boolean = !isStreaming && snapshotComplete && snapshotContent == content
