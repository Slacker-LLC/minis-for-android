package com.openminis.app.data.db

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.openminis.app.data.repository.ChatRepository
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SessionDeleteCascadeTest {
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
    fun deletingASessionRemovesItsMessagesInTheSameStatement() = runBlocking {
        val id = repository.createSession("m").id
        val other = repository.createSession("m").id
        repository.appendMessage(id, "user", """[{"type":"text","value":"a"}]""")
        repository.appendMessage(id, "assistant", """[{"type":"text","value":"b"}]""")
        repository.appendMessage(other, "user", """[{"type":"text","value":"keep"}]""")

        repository.deleteSession(id)

        assertNull(repository.getSession(id))
        assertEquals(0, database.chatDao().messageCountForSession(id))
        assertEquals(1, database.chatDao().messageCountForSession(other))
    }
}
