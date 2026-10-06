package com.openminis.app.ui.chat

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.openminis.app.agent.AgentTurnOutcome
import com.openminis.app.data.model.AgentContentPart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * What the agent loop does today, pinned before it is taken out of [ChatViewModel]. These are
 * observations of the current behaviour, not a statement of the ideal: if one needs to change, change
 * it deliberately, in its own commit.
 */
@RunWith(AndroidJUnit4::class)
class AgentLoopCharacterizationTest {
    private fun parts(requests: List<List<com.openminis.app.data.model.LLMMessage>>, call: Int) =
        requests[call].flatMap { it.contentParts }

    @Test
    fun aToolCallRunsTheToolAndFeedsItsResultToTheNextRequest() = runBlocking {
        val provider = ScriptedProvider { call, _, _ ->
            if (call == 1) toolCall("call-1", "android.time") else text("Final answer")
        }
        withChatVm(chatOnly = false, provider) { vm, repository, p ->
            val turn = withContext(Dispatchers.Main) { vm.submitPrompt("What time is it?") }
            assertEquals(AgentTurnOutcome.Completed, withTimeout(30_000L) { turn.result.await() })

            assertEquals("one request for the tool call, one after its result", 2, p.calls)
            assertTrue("the tool is offered", "android.time" in p.offeredTools)
            val second = parts(p.requests, 1)
            assertTrue("the call is replayed", second.any { it is AgentContentPart.ToolUse && it.id == "call-1" })
            val result = second.filterIsInstance<AgentContentPart.ToolResult>().single { it.id == "call-1" }
            assertFalse("the tool ran", result.isError)
            assertTrue(result.content.isNotBlank())

            val stored = repository.dao.loadMessages(vm.currentSessionId)
            assertTrue("the final answer is stored", stored.any { it.role == "assistant" && it.partsJson.contains("Final answer") })
            assertTrue("the call and its result are stored", stored.any { it.partsJson.contains("call-1") })
        }
    }

    @Test
    fun aToolTheTurnDidNotOfferIsRefusedAndTheModelIsToldSo() = runBlocking {
        val provider = ScriptedProvider { call, _, _ ->
            if (call == 1) toolCall("call-x", "no.such.tool") else text("Understood")
        }
        withChatVm(chatOnly = false, provider) { vm, _, p ->
            val turn = withContext(Dispatchers.Main) { vm.submitPrompt("Use a tool") }
            assertEquals(AgentTurnOutcome.Completed, withTimeout(30_000L) { turn.result.await() })
            assertEquals(2, p.calls)
            val result = parts(p.requests, 1).filterIsInstance<AgentContentPart.ToolResult>().single { it.id == "call-x" }
            assertTrue("a refused call is reported as an error result", result.isError)
        }
    }

    @Test
    fun chatOnlyOffersNoToolsAndRefusesACallAnyway() = runBlocking {
        val provider = ScriptedProvider { call, _, _ ->
            if (call == 1) toolCall("call-c", "android.time") else text("Fine")
        }
        withChatVm(chatOnly = true, provider) { vm, _, p ->
            val turn = withContext(Dispatchers.Main) { vm.submitPrompt("Chat only") }
            assertEquals(AgentTurnOutcome.Completed, withTimeout(30_000L) { turn.result.await() })
            assertTrue("no tools are offered in chat-only mode", p.offeredTools.isEmpty())
            val result = parts(p.requests, 1).filterIsInstance<AgentContentPart.ToolResult>().single { it.id == "call-c" }
            assertTrue(result.isError)
            assertTrue(result.content, result.content.contains("chat-only", ignoreCase = true))
        }
    }

    @Test
    fun aSecondTurnSeesTheWholeConversationSoFar() = runBlocking {
        val provider = ScriptedProvider { call, _, _ -> text("Answer $call") }
        withChatVm(chatOnly = true, provider) { vm, _, p ->
            val first = withContext(Dispatchers.Main) { vm.submitPrompt("First question") }
            assertEquals(AgentTurnOutcome.Completed, withTimeout(30_000L) { first.result.await() })
            withContext(Dispatchers.Main) { vm.chatOnlyForNextTurn = true }
            val second = withContext(Dispatchers.Main) { vm.submitPrompt("Second question") }
            assertEquals(AgentTurnOutcome.Completed, withTimeout(30_000L) { second.result.await() })
            val sent = p.requests.last().joinToString("\n") { it.content }
            assertTrue(sent.contains("First question"))
            assertTrue(sent.contains("Answer 1"))
            assertTrue(sent.contains("Second question"))
        }
    }

    @Test
    fun aShortConversationIsNotCompactableAndIsLeftAsItWas() = runBlocking {
        val provider = ScriptedProvider { call, _, _ -> text("Answer $call") }
        withChatVm(chatOnly = true, provider) { vm, _, p ->
            val first = withContext(Dispatchers.Main) { vm.submitPrompt("Remember the code word PINEAPPLE") }
            assertEquals(AgentTurnOutcome.Completed, withTimeout(30_000L) { first.result.await() })
            withContext(Dispatchers.Main) { vm.compactBefore(vm.messages.value.last().id, includesBoundary = true) }
            delay(300)
            assertFalse(vm.isCompacting.value)
            withContext(Dispatchers.Main) { vm.chatOnlyForNextTurn = true }
            val next = withContext(Dispatchers.Main) { vm.submitPrompt("What next?") }
            assertEquals(AgentTurnOutcome.Completed, withTimeout(30_000L) { next.result.await() })
            assertTrue("nothing was folded", p.requests.last().joinToString("\n") { it.content }.contains("PINEAPPLE"))
            assertEquals("no summary request was made", 0, p.titleRequests)
        }
    }

    @Test
    fun compactingFoldsEarlierTurnsIntoASummaryTheNextRequestStartsFrom() = runBlocking {
        val provider = ScriptedProvider { call, _, _ -> text("Answer $call") }
        withChatVm(chatOnly = true, provider) { vm, _, p ->
            val prompts = listOf("Remember the code word PINEAPPLE") + (2..6).map { "Filler question $it" }
            for (prompt in prompts) {
                withContext(Dispatchers.Main) { vm.chatOnlyForNextTurn = true }
                val turn = withContext(Dispatchers.Main) { vm.submitPrompt(prompt) }
                assertEquals(AgentTurnOutcome.Completed, withTimeout(30_000L) { turn.result.await() })
            }
            withContext(Dispatchers.Main) { vm.compactBefore(vm.messages.value.last().id, includesBoundary = true) }
            delay(300)
            withTimeout(30_000L) { vm.isCompacting.first { !it } }
            withContext(Dispatchers.Main) { vm.chatOnlyForNextTurn = true }
            val next = withContext(Dispatchers.Main) { vm.submitPrompt("What next?") }
            assertEquals(AgentTurnOutcome.Completed, withTimeout(30_000L) { next.result.await() })
            val sent = p.requests.last().joinToString("\n") { it.content }
            assertTrue("a summary was requested; titles=${p.titleRequests}", p.titleRequests >= 1)
            // By design a warm-up window of recent turns before the anchor is still sent verbatim
            // (COMPACT_KEEP_RECENT_TOKENS), and a short conversation fits entirely inside it: so the early
            // turns stay, the summary is spliced in after them, and the messages after the anchor follow.
            val summaryAt = sent.indexOf("<context-summary>")
            assertTrue("the summary is in the request", summaryAt >= 0 && sent.contains("Scripted title"))
            assertTrue("it comes after the verbatim warm-up window", sent.indexOf("PINEAPPLE") < summaryAt)
            assertTrue("and before the messages after the anchor", summaryAt < sent.indexOf("Filler question 5"))
            assertTrue(sent.contains("What next?"))
        }
    }

    @Test
    fun theFirstExchangeOfAnUntitledSessionGetsATitle() = runBlocking {
        val provider = ScriptedProvider { _, _, _ -> text("Hello there") }
        withChatVm(chatOnly = true, provider, sessionTitle = "New Chat") { vm, repository, p ->
            val turn = withContext(Dispatchers.Main) { vm.submitPrompt("Plan a trip to Kyoto") }
            assertEquals(AgentTurnOutcome.Completed, withTimeout(30_000L) { turn.result.await() })
            withTimeout(15_000L) {
                while ((repository.getSession(vm.currentSessionId)?.title ?: "New Chat") == "New Chat") delay(100)
            }
            val title = repository.getSession(vm.currentSessionId)!!.title
            assertTrue("a title was written: $title", !title.isNullOrBlank() && title != "New Chat")
            assertTrue("the model was asked for it", p.titleRequests >= 1)
        }
    }

    @Test
    fun aSessionThatAlreadyHasATitleIsNotRetitled() = runBlocking {
        val provider = ScriptedProvider { _, _, _ -> text("Hello there") }
        withChatVm(chatOnly = true, provider, sessionTitle = "My own title") { vm, repository, p ->
            val turn = withContext(Dispatchers.Main) { vm.submitPrompt("Plan a trip to Kyoto") }
            assertEquals(AgentTurnOutcome.Completed, withTimeout(30_000L) { turn.result.await() })
            delay(1_000)
            assertEquals("My own title", repository.getSession(vm.currentSessionId)!!.title)
            assertEquals(0, p.titleRequests)
        }
    }

    @Test
    fun aComposerAttachmentIsStoredWithTheMessageAndClearedFromTheComposer() = runBlocking {
        val provider = ScriptedProvider { _, _, _ -> text("Got it") }
        withChatVm(chatOnly = true, provider) { vm, repository, _ ->
            val ctx = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().targetContext
            val file = java.io.File(ctx.cacheDir, "characterization-note.txt").apply { writeText("attachment body") }
            val attachment = InputAttachment(
                fileName = file.name,
                uri = android.net.Uri.fromFile(file),
                mimeType = "text/plain",
                kind = InputAttachment.Kind.DOCUMENT,
            )
            withContext(Dispatchers.Main) {
                vm.addAttachment(attachment)
                vm.setInputText("See the file")
                vm.sendMessage("See the file")
            }
            // Wait for the whole exchange to be stored: awaitStreamExit can return before the send has
            // started, which made this read race the write.
            withTimeout(30_000L) {
                while (repository.dao.loadMessages(vm.currentSessionId).none { it.role == "assistant" && it.partsJson.contains("Got it") }) delay(50)
            }
            val user = repository.dao.loadMessages(vm.currentSessionId).first { it.role == "user" }
            assertTrue("the message text is stored", user.partsJson.contains("See the file"))
            assertTrue("the attachment is referenced by the stored message: ${user.partsJson.take(300)}", user.partsJson.contains("characterization-note"))
            assertTrue("the composer is cleared after sending", vm.attachments.value.isEmpty())
            file.delete()
        }
    }
}
