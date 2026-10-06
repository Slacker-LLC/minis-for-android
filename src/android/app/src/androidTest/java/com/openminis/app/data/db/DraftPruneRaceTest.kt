package com.openminis.app.data.db

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.openminis.app.data.repository.ChatRepository
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DraftPruneRaceTest {
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

    private suspend fun oldDraft(id: String) = database.chatDao().insertSession(
        ChatSessionEntity(id = id, modelId = "m", createdAt = 1L, updatedAt = 1L),
    )

    @Test
    fun anOldEmptyDraftIsPruned() = runBlocking {
        oldDraft("draft")
        assertEquals(1, repository.pruneEmptyDrafts())
        assertNull(repository.getSession("draft"))
    }

    @Test
    fun aDraftThatGotAMessageAfterTheCandidateReadSurvives() = runBlocking {
        oldDraft("raced")
        // The candidate list as pruneEmptyDrafts read it.
        val candidates = database.chatDao().emptyUntitledSessionIds(System.currentTimeMillis())
        assertEquals(listOf("raced"), candidates)

        repository.appendMessage("raced", "user", """[{"type":"text","value":"first"}]""")

        // What the prune does for each stale candidate.
        assertEquals(0, database.chatDao().deleteSessionIfUnusedDraft("raced"))
        assertNotNull(repository.getSession("raced"))
        assertEquals(1, database.chatDao().messageCountForSession("raced"))
        assertEquals(0, repository.pruneEmptyDrafts())
    }

    @Test
    fun aTitledOrPinnedRowIsNotADraft() = runBlocking {
        database.chatDao().insertSession(
            ChatSessionEntity(id = "titled", title = "T", modelId = "m", createdAt = 1L, updatedAt = 1L),
        )
        database.chatDao().insertSession(
            ChatSessionEntity(id = "pinned", modelId = "m", createdAt = 1L, updatedAt = 1L, pinnedAt = 5L),
        )
        assertEquals(0, database.chatDao().deleteSessionIfUnusedDraft("titled"))
        assertEquals(0, database.chatDao().deleteSessionIfUnusedDraft("pinned"))
    }
}
