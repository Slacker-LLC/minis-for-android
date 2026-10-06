package com.openminis.app.data.db

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.openminis.app.data.repository.BotTaskRepository
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** A stop stands: the conditional state write used by the delegation tool cannot revive it. */
@RunWith(AndroidJUnit4::class)
class BotTaskStopTest {
    private lateinit var database: AppDatabase
    private lateinit var tasks: BotTaskRepository

    @Before
    fun setUp() {
        database = Room.inMemoryDatabaseBuilder(
            InstrumentationRegistry.getInstrumentation().targetContext,
            AppDatabase::class.java,
        ).build()
        tasks = BotTaskRepository(database.botTaskDao())
    }

    @After
    fun tearDown() {
        database.close()
    }

    private suspend fun newTask() = tasks.ensureRootTask("s1", "m1", "bot1", "build the thing")

    @Test
    fun anActiveTaskTakesTheExecutingState() = runBlocking {
        val task = newTask()
        assertTrue(tasks.updateStateIfWakeable(task.id, BotTaskEntity.STATUS_ACTIVE, BotTaskEntity.PHASE_EXECUTING, "s1"))
        assertEquals(BotTaskEntity.PHASE_EXECUTING, tasks.get(task.id)!!.phase)
    }

    @Test
    fun aPausedTaskIsNotRevived() = runBlocking {
        val task = newTask()
        assertTrue(tasks.pause(task.id))
        assertFalse(tasks.updateStateIfWakeable(task.id, BotTaskEntity.STATUS_ACTIVE, BotTaskEntity.PHASE_EXECUTING, "s1"))
        assertEquals(BotTaskEntity.STATUS_PAUSED, tasks.get(task.id)!!.status)
    }

    @Test
    fun aCancelledTaskIsNotRevived() = runBlocking {
        val task = newTask()
        assertTrue(tasks.cancel(task.id))
        assertFalse(tasks.updateStateIfWakeable(task.id, BotTaskEntity.STATUS_ACTIVE, BotTaskEntity.PHASE_EXECUTING, "s1"))
        assertEquals(BotTaskEntity.STATUS_CANCELLED, tasks.get(task.id)!!.status)
    }
}
