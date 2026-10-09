package com.openminis.app.ui.chat

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.openminis.app.agent.AgentTurnOutcome
import com.openminis.app.data.model.AgentContentPart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** After a model call fails in the middle of a turn, Retry goes on from where the turn stopped. */
@RunWith(AndroidJUnit4::class)
class RetryContinuesTest {
    @Test
    fun retryAfterAFailedModelCallKeepsTheStepsAlreadyDone() = runBlocking {
        val provider = ScriptedProvider { call, _, _ ->
            when (call) {
                1 -> toolCall("call-1", "android.time")
                2 -> toolCall("call-2", "android.time")
                3 -> throw java.io.IOException("no response from server (120s TTFB)")
                else -> text("Finished")
            }
        }
        withChatVm(chatOnly = false, provider) { vm, repository, p ->
            val turn = withContext(Dispatchers.Main) { vm.submitPrompt("Do the long job") }
            withTimeout(60_000L) { turn.result.await() }
            assertEquals("two steps, then the failing call", 3, p.calls)
            fun shape() = vm.messages.value.joinToString(" | ") { m -> "${m.role}:${m.content.length}c:${m.toolBlocks.size}b:${m.toolBlocks.map { it.kind + "/" + it.toolStatus }}" }
            val shapeBefore = withContext(Dispatchers.Main) { shape() }
            val stepsBefore = withContext(Dispatchers.Main) { vm.messages.value.sumOf { it.toolBlocks.size } }
            assertTrue("both steps are on screen", stepsBefore >= 2)

            withContext(Dispatchers.Main) { vm.retryLast() }
            withTimeout(60_000L) { while (p.calls < 4) delay(50) }
            delay(500)

            val fourth = p.requests[3].flatMap { it.contentParts }
            assertTrue("the first step is still in the request", fourth.any { it is AgentContentPart.ToolUse && it.id == "call-1" })
            assertTrue("the second step is still in the request", fourth.any { it is AgentContentPart.ToolUse && it.id == "call-2" })
            assertEquals("the job's prompt is sent once, not again", 1, p.requests[3].count { m ->
                m.contentParts.any { it is AgentContentPart.Text && it.text == "Do the long job" } || m.content == "Do the long job"
            })
            val stepsAfter = withContext(Dispatchers.Main) { vm.messages.value.sumOf { it.toolBlocks.size } }
            val shapeAfter = withContext(Dispatchers.Main) { shape() }
            assertTrue("the steps are still on screen after the retry: before=[$shapeBefore] after=[$shapeAfter]", stepsAfter >= stepsBefore)
            val stored = repository.dao.loadMessages(vm.currentSessionId)
            assertTrue("call-1 is still stored", stored.any { it.partsJson.contains("call-1") })
            assertTrue("call-2 is still stored", stored.any { it.partsJson.contains("call-2") })
        }
    }
}
