package com.openminis.app.data.db

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.openminis.app.data.repository.ChatRepository
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** A generated title may only replace the title the request started from. */
@RunWith(AndroidJUnit4::class)
class GeneratedTitleWriteTest {
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
        sessionId = repository.createSession("model").id
        repository.updateSessionTitleAndCategory(sessionId, "Old title", "chat")
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun untouchedRowTakesTheGeneratedTitleAndCategory() = runBlocking {
        assertTrue(repository.updateGeneratedTitle(sessionId, "Fresh", "code", "Old title", "chat"))
        val row = repository.getSession(sessionId)!!
        assertEquals("Fresh", row.title)
        assertEquals("code", row.category)
    }

    @Test
    fun aTitleTypedMeanwhileSurvives() = runBlocking {
        repository.updateSessionTitle(sessionId, "My own name")
        assertFalse(repository.updateGeneratedTitle(sessionId, "Fresh", "code", "Old title", "chat"))
        val row = repository.getSession(sessionId)!!
        assertEquals("My own name", row.title)
        assertEquals("chat", row.category)
    }

    @Test
    fun aCategoryChosenMeanwhileSurvives() = runBlocking {
        repository.updateSessionTitleAndCategory(sessionId, "Old title", "travel")
        assertFalse(repository.updateGeneratedTitle(sessionId, "Fresh", "code", "Old title", "chat"))
        assertEquals("travel", repository.getSession(sessionId)!!.category)
        assertEquals("Old title", repository.getSession(sessionId)!!.title)
    }

    @Test
    fun aRowThatNeverHadATitleStillTakesOne() = runBlocking {
        val blank = repository.createSession("model").id
        assertTrue(repository.updateGeneratedTitle(blank, "First", null, null, null))
        assertEquals("First", repository.getSession(blank)!!.title)
    }
}
