package com.openminis.app.data.db

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.openminis.app.data.repository.ChatRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ChatAppendTransactionTest {
    private lateinit var database: AppDatabase
    private lateinit var repository: ChatRepository
    private lateinit var sessionId: String

    @Before
    fun setUp() = runBlocking {
        database = Room.inMemoryDatabaseBuilder(
            InstrumentationRegistry.getInstrumentation().targetContext,
            AppDatabase::class.java,
        ).build()
        repository = ChatRepository(database.chatDao())
        sessionId = repository.createSession("test-model").id
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun previewFailureRollsBackTheInsertedMessage() = runBlocking {
        database.openHelper.writableDatabase.execSQL(
            """CREATE TRIGGER reject_preview BEFORE UPDATE OF last_message ON sessions
               BEGIN SELECT RAISE(ABORT, 'preview refused'); END""",
        )
        val failure = runCatching {
            repository.appendMessage(sessionId, "user", """[{"type":"text","value":"hello"}]""")
        }.exceptionOrNull()
        assertNotNull(failure)
        assertEquals(0, database.chatDao().messageCountForSession(sessionId))
        assertNull(repository.getSession(sessionId)!!.lastMessage)
        database.openHelper.writableDatabase.execSQL("DROP TRIGGER reject_preview")
        repository.appendMessage(sessionId, "user", """[{"type":"text","value":"retry"}]""")
        assertEquals(1, database.chatDao().messageCountForSession(sessionId))
        assertEquals("retry", repository.getSession(sessionId)!!.lastMessage)
    }

    @Test
    fun concurrentAppendsAllocateDistinctOrdersInsideTheTransaction() = runBlocking {
        val rows = (1..20).map { index ->
            async(Dispatchers.IO) {
                repository.appendMessage(sessionId, "user", """[{"type":"text","value":"message $index"}]""")
            }
        }.awaitAll()
        assertEquals(20, rows.map { it.sortOrder }.toSet().size)
        assertEquals(20, database.chatDao().messageCountForSession(sessionId))
    }
}
