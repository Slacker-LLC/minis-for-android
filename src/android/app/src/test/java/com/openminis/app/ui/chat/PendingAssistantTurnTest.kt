package com.openminis.app.ui.chat

import com.openminis.app.data.model.AgentContentPart
import com.openminis.app.data.storage.MediaStore
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.json.JSONArray
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

@OptIn(ExperimentalCoroutinesApi::class)
class PendingAssistantTurnTest {
    @get:Rule val temp = TemporaryFolder()

    @Test
    fun `cancellation during database commit cannot duplicate or lose a generated image`() = runTest {
        val base = temp.newFolder("media")
        val ref = MediaStore(base).saveMedia(byteArrayOf(1, 2), "image/png", "session")
        val turn = AssistantTurnCodec.build(listOf(AssistantTurnCodec.mediaBlock(ref, base)), 0, emptyMap(), base)
        val pending = PendingAssistantTurn("assistant", "session", 0)
        val writeStarted = CompletableDeferred<Unit>()
        val writeFinished = CompletableDeferred<Unit>()
        var rows = 0
        val normal = async {
            pending.commit(turn) { rows++; writeStarted.complete(Unit); writeFinished.await(); "row-1" }
        }
        writeStarted.await()
        normal.cancel()
        val stop = async {
            pending.commit(AssistantTurnCodec.interrupted(turn)) { rows++; "duplicate" }
        }
        runCurrent()
        writeFinished.complete(Unit)
        assertEquals("row-1", stop.await())
        assertEquals(1, rows)
        val saved = JSONArray(pending.committed!!.turn.partsJson)
        assertEquals("mediaRef", saved.getJSONObject(0).getString("type"))
        assertEquals(ref.id, AssistantTurnCodec.restoreMediaBlock(saved.getJSONObject(0).getJSONObject("value"), base).mediaRef!!.id)
        assertTrue(java.io.File(base, ref.relativePath).exists())
    }

    @Test
    fun `stopped media turn preserves only its own ordered blocks and marker`() = runTest {
        val base = temp.newFolder("media")
        val store = MediaStore(base)
        val prior = AssistantTurnCodec.mediaBlock(store.saveMedia(byteArrayOf(1), "image/png", "session"), base)
        val current = AssistantTurnCodec.mediaBlock(store.saveMedia(byteArrayOf(2), "image/jpeg", "session"), base)
        val blocks = listOf(prior, AssistantBlock("text", "text", "caption"), current)
        val turn = AssistantTurnCodec.interrupted(AssistantTurnCodec.build(blocks, 1, emptyMap(), base))
        val pending = PendingAssistantTurn("assistant", "session", 1)
        assertEquals("stopped-row", pending.commit(turn) { "stopped-row" })
        assertEquals("stopped-row", pending.commit(turn) { error("must not write twice") })
        val json = JSONArray(pending.committed!!.turn.partsJson)
        assertEquals(listOf("text", "mediaRef", "text"), (0 until json.length()).map { json.getJSONObject(it).getString("type") })
        assertFalse(turn.partsJson.contains(prior.mediaRef!!.id))
        assertTrue((turn.parts.last() as AgentContentPart.Text).text.contains("user stopped"))
    }

    @Test
    fun `retry removes only abandoned media files and refuses escaped paths`() {
        val base = temp.newFolder("media")
        val store = MediaStore(base)
        val retained = AssistantTurnCodec.mediaBlock(store.saveMedia(byteArrayOf(1), "image/png", "session"), base)
        val abandoned = AssistantTurnCodec.mediaBlock(store.saveMedia(byteArrayOf(2), "image/png", "session"), base)
        AssistantTurnCodec.discardMedia(listOf(abandoned), base)
        assertTrue(java.io.File(retained.imageFilePath!!).exists())
        assertFalse(java.io.File(abandoned.imageFilePath!!).exists())
        val unsafe = abandoned.copy(mediaRef = abandoned.mediaRef!!.copy(relativePath = "../outside"))
        assertThrows(IllegalArgumentException::class.java) { AssistantTurnCodec.discardMedia(listOf(unsafe), base) }
    }
}
