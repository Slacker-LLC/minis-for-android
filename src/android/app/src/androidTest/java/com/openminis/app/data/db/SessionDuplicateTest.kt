package com.openminis.app.data.db

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.openminis.app.data.SessionForkManager
import com.openminis.app.data.repository.ChatRepository
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** What the session list's Duplicate now relies on. */
@RunWith(AndroidJUnit4::class)
class SessionDuplicateTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var database: AppDatabase
    private lateinit var repository: ChatRepository

    @Before
    fun setUp() {
        database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        repository = ChatRepository(database.chatDao())
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun aCopyOfAMemoryOffTunedSessionKeepsItsSettingsAndMessages() = runBlocking {
        val src = repository.createSession("model-a").id
        repository.appendMessage(src, "user", """[{"type":"text","value":"hello"}]""")
        repository.appendMessage(src, "assistant", """[{"type":"text","value":"hi"}]""")
        repository.updateSessionTitleAndCategory(src, "Tuned", "coding")
        repository.updateSessionBinding(src, """{"type":"entry","entryId":"e1"}""", "model-a")
        database.chatDao().updateMemoryEnabled(src, 0)
        database.chatDao().updateThinkingOverride(src, "HIGH")

        val copyId = SessionForkManager(context, repository).duplicateSession(src, title = "Copy of Tuned")!!
        val copy = repository.getSession(copyId)!!

        assertEquals("Copy of Tuned", copy.title)
        assertEquals("coding", copy.category)
        assertEquals(0, copy.memoryEnabled)
        assertEquals("HIGH", copy.thinkingOverride)
        assertNotNull(copy.modelBinding)
        assertEquals(2, database.chatDao().messageCountForSession(copyId))
        // The source is untouched.
        assertEquals(2, database.chatDao().messageCountForSession(src))
        assertEquals(0, repository.getSession(src)!!.memoryEnabled)
    }
}
