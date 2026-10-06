package com.openminis.app.data.db

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.openminis.app.data.repository.BotDelegationRepository
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** The recovery queries page past their first 32 rows, including rows created in the same millisecond. */
@RunWith(AndroidJUnit4::class)
class BotDelegationPagingTest {
    private lateinit var database: AppDatabase
    private lateinit var repository: BotDelegationRepository

    @Before
    fun setUp() {
        database = Room.inMemoryDatabaseBuilder(
            InstrumentationRegistry.getInstrumentation().targetContext,
            AppDatabase::class.java,
        ).build()
        repository = BotDelegationRepository(database.botDelegationDao())
    }

    @After
    fun tearDown() = database.close()

    private fun row(i: Int, status: String, delivered: Boolean = false) = BotDelegationEntity(
        id = "d-%03d".format(i),
        sourceBotId = "src",
        sourceSessionId = "s",
        sourceRunId = "r",
        sourceTurnSettled = 1,
        targetBotId = "t",
        prompt = "p",
        status = status,
        // Three rows per millisecond, so the id half of the cursor matters.
        createdAt = 1_000L + i / 3,
        updatedAt = 1_000L,
        finishedAt = if (status == BotDelegationEntity.STATUS_QUEUED) null else 2_000L,
        deliveredAt = if (delivered) 3_000L else null,
    )

    private suspend fun collect(fetch: suspend (BotDelegationEntity?) -> List<BotDelegationEntity>): List<String> {
        val seen = mutableListOf<String>()
        var after: BotDelegationEntity? = null
        while (true) {
            val page = fetch(after)
            seen += page.map { it.id }
            if (page.size < 32) return seen
            after = page.last()
        }
    }

    @Test
    fun everyDispatchableAndUndeliveredRowIsReachedOnceInOrder() = runBlocking<Unit> {
        val dao = database.botDelegationDao()
        repeat(75) { dao.insert(row(it, BotDelegationEntity.STATUS_QUEUED)) }
        repeat(70) { dao.insert(row(100 + it, BotDelegationEntity.STATUS_COMPLETED)) }
        dao.insert(row(500, BotDelegationEntity.STATUS_COMPLETED, delivered = true))

        val dispatchable = collect { repository.listDispatchable(32, it) }
        assertEquals((0 until 75).map { "d-%03d".format(it) }, dispatchable)

        val undelivered = collect { repository.listUndeliveredTerminal(32, it) }
        assertEquals((100 until 170).map { "d-%03d".format(it) }, undelivered)
    }
}
