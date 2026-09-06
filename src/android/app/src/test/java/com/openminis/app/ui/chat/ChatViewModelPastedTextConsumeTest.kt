package com.openminis.app.ui.chat

import com.openminis.app.data.storage.MediaStore
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import kotlin.coroutines.CoroutineContext

@OptIn(ExperimentalCoroutinesApi::class)
class ChatViewModelPastedTextConsumeTest {
    @get:Rule val temp = TemporaryFolder()
    private val pasted = listOf(PastedText(1, "hello world"), PastedText(2, "keep me"))

    @Test
    fun `actual preparation files are removed and paste retained when commit fails`() = runTest {
        val store = MediaStore(temp.newFolder("media"))
        val parts = PastedTextProcessor.processPastedParts("[Pasted#1]", pasted, "session", store)!!
        var buffer = pasted
        assertEquals("mediaRef", JSONObject(parts.partsJson.single()).getString("type"))
        assertTrue(parts.createdFiles.single().exists())
        val error = runCatching {
            PastedTextProcessor.commitMessage(parts, { error("database refused") }, { ids -> buffer = buffer.filterNot { it.id in ids } })
        }.exceptionOrNull()
        assertEquals("database refused", error?.message)
        assertEquals(pasted, buffer)
        assertFalse(parts.createdFiles.single().exists())
    }

    @Test
    fun `cancellation after DB success retains referenced files and consumes only committed paste`() = runTest {
        val store = MediaStore(temp.newFolder("media"))
        val parts = PastedTextProcessor.processPastedParts("[Pasted#1]", pasted, "session", store)!!
        var buffer = pasted
        var persistedJson: String? = null
        val job = launch {
            val owner = currentCoroutineContext()[Job]!!
            PastedTextProcessor.commitMessage(parts, {
                persistedJson = parts.partsJson.single()
                owner.cancel()
                "row-1"
            }, { ids -> buffer = buffer.filterNot { it.id in ids } })
        }
        job.join()
        assertTrue(job.isCancelled)
        assertNotNull(persistedJson)
        assertEquals(listOf(pasted[1]), buffer)
        assertEquals("hello world", parts.createdFiles.single().readText())
    }

    @Test
    fun `cancellation while dispatching preparation result back cannot leak a file`() = runTest {
        val store = MediaStore(temp.newFolder("media"))
        val delegate = StandardTestDispatcher(testScheduler, "caller")
        val io = StandardTestDispatcher(testScheduler, "io")
        var dispatches = 0
        val cancelOnReturn = object : CoroutineDispatcher() {
            override fun dispatch(context: CoroutineContext, block: Runnable) {
                dispatches++
                if (dispatches == 2) context[Job]!!.cancel()
                delegate.dispatch(context, block)
            }
        }
        var returned = false
        val job = launch(cancelOnReturn) {
            PastedTextProcessor.processPastedParts("[Pasted#1]", pasted, "session", store, io)
            returned = true
        }
        runCurrent()
        job.join()
        assertTrue(job.isCancelled)
        assertFalse(returned)
        assertTrue(store.mediaBaseDir.walkTopDown().none { it.isFile })
    }

    @Test
    fun `UI callback failure after commit cannot delete a referenced file`() = runTest {
        val store = MediaStore(temp.newFolder("media"))
        val parts = PastedTextProcessor.processPastedParts("[Pasted#1]", pasted, "session", store)!!
        val error = runCatching {
            PastedTextProcessor.commitMessage(parts, { "row-1" }, { error("callback failed") })
        }.exceptionOrNull()
        assertEquals("callback failed", error?.message)
        assertTrue(parts.createdFiles.single().exists())
    }
}
