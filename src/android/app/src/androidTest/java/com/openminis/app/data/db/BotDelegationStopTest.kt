package com.openminis.app.data.db

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** A delegation cannot be started once the task it belongs to has been stopped; the check is inside the claim. */
@RunWith(AndroidJUnit4::class)
class BotDelegationStopTest {
    private lateinit var database: AppDatabase

    @Before
    fun setUp() {
        database = Room.inMemoryDatabaseBuilder(
            InstrumentationRegistry.getInstrumentation().targetContext,
            AppDatabase::class.java,
        ).build()
    }

    @After
    fun tearDown() = database.close()

    private suspend fun task(id: String, status: String) = database.botTaskDao().insert(
        BotTaskEntity(id = id, originSessionId = "o", ownerBotId = "b", goal = "g", status = status, createdAt = 1, updatedAt = 1),
    )

    private suspend fun delegation(id: String, root: String?) = database.botDelegationDao().insert(
        BotDelegationEntity(
            id = id, sourceBotId = "s", sourceSessionId = "ss", sourceRunId = "r", sourceTurnSettled = 1,
            rootTaskId = root, targetBotId = "t", prompt = "p", createdAt = 1, updatedAt = 1,
        ),
    )

    @Test
    fun anActiveTaskLetsItsMemberStart() = runBlocking<Unit> {
        task("t1", BotTaskEntity.STATUS_ACTIVE)
        delegation("d1", "t1")
        assertEquals(1, database.botDelegationDao().claim("d1", "target", 5))
        assertEquals(BotDelegationEntity.STATUS_RUNNING, database.botDelegationDao().get("d1")?.status)
    }

    @Test
    fun aStoppedTaskRefusesTheClaimInEveryStoppedState() = runBlocking<Unit> {
        val stopped = listOf(
            BotTaskEntity.STATUS_PAUSED, BotTaskEntity.STATUS_CANCELLED, BotTaskEntity.STATUS_FAILED,
            BotTaskEntity.STATUS_BUDGET_EXHAUSTED, BotTaskEntity.STATUS_COMPLETED,
        )
        stopped.forEachIndexed { i, status ->
            task("t$i", status)
            delegation("d$i", "t$i")
            assertEquals(status, 0, database.botDelegationDao().claim("d$i", "target", 5))
            assertEquals(status, BotDelegationEntity.STATUS_QUEUED, database.botDelegationDao().get("d$i")?.status)
        }
        // The SQL list and the coordinator's own list name the same states.
        assertEquals(
            stopped.toSet(),
            com.openminis.app.tools.BotDelegationCoordinator.STOPPED_ROOT_STATUSES,
        )
    }

    @Test
    fun aDelegationWithoutATaskStillStarts() = runBlocking<Unit> {
        delegation("loose", null)
        assertEquals(1, database.botDelegationDao().claim("loose", "target", 5))
    }

    @Test
    fun aCancelBetweenTheDispatcherCheckAndTheClaimWins() = runBlocking<Unit> {
        task("t1", BotTaskEntity.STATUS_ACTIVE)
        delegation("d1", "t1")
        // The dispatcher looked and saw an active task...
        assertEquals(BotTaskEntity.STATUS_ACTIVE, database.botTaskDao().get("t1")?.status)
        // ...the user cancels...
        database.botTaskDao().stop("t1", BotTaskEntity.STATUS_CANCELLED, 2)
        // ...and the claim, made afterwards, does not start the member.
        assertEquals(0, database.botDelegationDao().claim("d1", "target", 5))
    }

    @Test
    fun theNonTerminalMembersOfATaskAreListed() = runBlocking<Unit> {
        task("t1", BotTaskEntity.STATUS_ACTIVE)
        delegation("q", "t1")
        delegation("r", "t1")
        delegation("other", "t2")
        database.botDelegationDao().claim("r", "target", 5)
        assertEquals(listOf("q", "r"), database.botDelegationDao().listNonTerminalForRootTask("t1").map { it.id })
    }
}
