package com.openminis.app.data.db

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.openminis.app.data.repository.ChatRepository
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** A row too large for the CursorWindow stays in the transcript instead of vanishing. */
@RunWith(AndroidJUnit4::class)
class UnreadableRowTest {
    private lateinit var database: AppDatabase
    private lateinit var repository: ChatRepository

    @Before
    fun setUp() {
        database = Room.inMemoryDatabaseBuilder(
            InstrumentationRegistry.getInstrumentation().targetContext,
            AppDatabase::class.java,
        ).build()
        repository = ChatRepository(database.chatDao())
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun anOversizedRowIsKeptAsAStandInAndTheRestStillLoads() = runBlocking {
        val id = repository.createSession("m").id
        repository.appendMessage(id, "user", """[{"type":"text","value":"first"}]""")
        // Bypass the append cap the way an old build could have: a 3 MB payload, over the 2 MB window.
        val huge = "[{\"type\":\"text\",\"value\":\"" + "x".repeat(3_000_000) + "\"}]"
        database.chatDao().insertMessage(
            MessageEntity(id = "big", sessionId = id, role = "assistant", partsJson = huge, createdAt = 5L, sortOrder = 1),
        )
        repository.appendMessage(id, "user", """[{"type":"text","value":"last"}]""")

        val loaded = repository.loadMessages(id)

        assertEquals(3, loaded.size)
        assertEquals(listOf("user", "assistant", "user"), loaded.map { it.role })
        assertEquals("big", loaded[1].id)
        assertTrue(loaded[1].partsJson.contains("too large"))
        assertTrue(loaded[2].partsJson.contains("last"))
    }
}
