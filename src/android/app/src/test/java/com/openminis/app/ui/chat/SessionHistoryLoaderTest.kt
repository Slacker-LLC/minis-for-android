package com.openminis.app.ui.chat

import com.openminis.app.data.db.ChatDao
import com.openminis.app.data.db.MessageEntity
import com.openminis.app.data.repository.ChatRepository
import java.lang.reflect.Proxy
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class SessionHistoryLoaderTest {
    @Test
    fun `reopening keeps oldest context and tool pairs across page boundaries`() = runTest {
        val rows = (0 until 405).map { index ->
            row(index, when (index) {
                0 -> """[{"type":"text","value":"original instruction"}]"""
                199 -> """[{"type":"toolUse","value":{"toolUseId":"call1","name":"shell_execute"}}]"""
                200 -> """[{"type":"toolResult","value":{"toolUseId":"call1","output":"ok"}}]"""
                else -> """[{"type":"text","value":"message $index"}]"""
            })
        }
        val offsets = mutableListOf<Int>()
        val repository = repository(rows.size) { offset, limit ->
            offsets.add(offset)
            rows.drop(offset).take(limit)
        }

        val history = SessionHistoryLoader.load(repository, "session")

        assertEquals(rows, history.rows)
        assertEquals(listOf(0, 200, 400), offsets)
        assertEquals("original instruction", history.parts.getValue("0").getOrThrow().getJSONObject(0).getString("value"))
        assertEquals("toolUse", history.parts.getValue("199").getOrThrow().getJSONObject(0).getString("type"))
        assertEquals("toolResult", history.parts.getValue("200").getOrThrow().getJSONObject(0).getString("type"))
    }

    @Test
    fun `cancellation never publishes a partial transcript`() = runTest {
        val cancelled = CancellationException("session closed")
        val repository = repository(405) { offset, limit ->
            if (offset > 0) throw cancelled
            (0 until limit).map { row(it) }
        }
        try {
            SessionHistoryLoader.load(repository, "session")
            fail("Expected cancellation")
        } catch (error: CancellationException) {
            assertSame(cancelled, error)
        }
    }

    @Test
    fun `malformed row stays available for bounded UI fallback`() = runTest {
        val rows = listOf(row(0, "broken json"), row(1))
        val history = SessionHistoryLoader.load(repository(2) { _, _ -> rows }, "session")
        assertEquals(rows, history.rows)
        assertTrue(history.parts.getValue("0").isFailure)
        assertTrue(history.parts.getValue("1").isSuccess)
    }

    private fun row(index: Int, json: String = "[]") = MessageEntity(
        id = index.toString(), sessionId = "session", role = "user", partsJson = json,
        createdAt = index.toLong(), sortOrder = index,
    )

    private fun repository(count: Int, page: (Int, Int) -> List<MessageEntity>): ChatRepository {
        val dao = Proxy.newProxyInstance(ChatDao::class.java.classLoader, arrayOf(ChatDao::class.java)) { _, method, args ->
            when (method.name) {
                "messageCountForSession" -> count
                "loadMessagesPage" -> page(args[1] as Int, args[2] as Int)
                else -> error("Unexpected DAO call: ${method.name}")
            }
        } as ChatDao
        return ChatRepository(dao)
    }
}
