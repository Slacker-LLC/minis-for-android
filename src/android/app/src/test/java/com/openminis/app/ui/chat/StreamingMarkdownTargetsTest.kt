package com.openminis.app.ui.chat

import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-android-streaming-state] The parse-target pipeline (Eta @ c15de97
 * consumeStreamingMarkdownTargets): superseded targets are skipped, an appended
 * chunk never invalidates the parse that just finished, and a rewrite does.
 */
class StreamingMarkdownTargetsTest {

    private fun snapshot(target: StreamingMarkdownTarget) = StreamingMarkdownSnapshot(
        originalSource = target.content,
        renderedSource = target.content,
        isComplete = !target.isStreaming,
        payload = target.content.length,
    )

    @Test
    fun `superseded targets are skipped instead of parsed`() = runBlocking {
        val channel = Channel<StreamingMarkdownTarget>(Channel.CONFLATED)
        // Three targets pending before the consumer even starts: only the newest
        // is worth a parse (the older two are prefix states of it).
        channel.trySend(StreamingMarkdownTarget("a", true))
        channel.trySend(StreamingMarkdownTarget("ab", true))
        channel.trySend(StreamingMarkdownTarget("abc", true))
        val parsed = mutableListOf<String>()
        val published = mutableListOf<String>()

        consumeStreamingMarkdownTargets(
            targets = channel,
            parse = { target ->
                parsed += target.content
                if (target.content == "abc") channel.close()
                snapshot(target)
            },
            publish = { published += it.originalSource },
        )

        assertEquals(listOf("abc"), parsed)
        assertEquals(listOf("abc"), published)
    }

    @Test
    fun `an appended chunk still publishes the parse that just finished`() = runBlocking {
        val channel = Channel<StreamingMarkdownTarget>(Channel.CONFLATED)
        channel.trySend(StreamingMarkdownTarget("hello", true))
        val published = mutableListOf<String>()

        consumeStreamingMarkdownTargets(
            targets = channel,
            parse = { target ->
                if (target.content == "hello") {
                    // Arrives while the parse is in flight.
                    channel.trySend(StreamingMarkdownTarget("hello world", true))
                } else {
                    channel.close()
                }
                snapshot(target)
            },
            publish = { published += it.originalSource },
        )

        assertEquals(listOf("hello", "hello world"), published)
    }

    @Test
    fun `a rewritten stream drops the superseded snapshot`() = runBlocking {
        val channel = Channel<StreamingMarkdownTarget>(Channel.CONFLATED)
        channel.trySend(StreamingMarkdownTarget("hello", true))
        val published = mutableListOf<String>()

        consumeStreamingMarkdownTargets(
            targets = channel,
            parse = { target ->
                if (target.content == "hello") {
                    // Not an extension of "hello": the model corrected itself or
                    // the user edited the turn.
                    channel.trySend(StreamingMarkdownTarget("different text", true))
                } else {
                    channel.close()
                }
                snapshot(target)
            },
            publish = { published += it.originalSource },
        )

        assertEquals(listOf("different text"), published)
    }

    @Test
    fun `a completed snapshot superseded by newer text is not published`() = runBlocking {
        val channel = Channel<StreamingMarkdownTarget>(Channel.CONFLATED)
        channel.trySend(StreamingMarkdownTarget("done", false))
        val published = mutableListOf<String>()

        consumeStreamingMarkdownTargets(
            targets = channel,
            parse = { target ->
                if (target.content == "done") {
                    // A final parse that a rewrite immediately superseded must
                    // not be shown as the terminal render.
                    channel.trySend(StreamingMarkdownTarget("done and more", false))
                } else {
                    channel.close()
                }
                snapshot(target)
            },
            publish = { published += it.originalSource },
        )

        assertEquals(listOf("done and more"), published)
    }

    @Test
    fun `a closed channel with no work publishes nothing`() = runBlocking {
        val channel = Channel<StreamingMarkdownTarget>(Channel.CONFLATED)
        channel.close()
        val published = mutableListOf<String>()

        consumeStreamingMarkdownTargets(
            targets = channel,
            parse = { target -> snapshot(target) },
            publish = { published += it.originalSource },
        )

        assertTrue(published.isEmpty())
    }

    @Test
    fun `the conflated holder keeps only the newest target`() {
        val targets = StreamingMarkdownTargets()
        targets.submit("one", isStreaming = true)
        targets.submit("two", isStreaming = true)
        targets.submit("three", isStreaming = true)
        val received = targets.receiveChannel.tryReceive().getOrNull()
        assertEquals("three", received?.content)
        targets.close()
    }
}
