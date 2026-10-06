package com.openminis.app.data.db

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.openminis.app.data.repository.ChatRepository
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SessionSearchLiteralTest {
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

    private suspend fun session(text: String): String {
        val id = repository.createSession("m").id
        repository.appendMessage(id, "user", """[{"type":"text","value":"$text"}]""")
        return id
    }

    @Test
    fun underscoreAndPercentMatchThemselvesOnly() = runBlocking {
        val underscore = session("see foo_bar here")
        session("see fooXbar here")
        val percent = session("saved 100% of it")
        session("saved 1000 of it")

        assertEquals(listOf(underscore), repository.searchSessions("foo_bar").map { it.id })
        assertEquals(listOf(percent), repository.searchSessions("100%").map { it.id })
        // A lone % used to match every session that has any message.
        assertEquals(listOf(percent), repository.searchSessions("%").map { it.id })
    }
}
