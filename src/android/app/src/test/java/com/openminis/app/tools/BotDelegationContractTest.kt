package com.openminis.app.tools

import com.openminis.app.data.db.BotDelegationEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlinx.coroutines.delay
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.util.concurrent.atomic.AtomicInteger

class BotDelegationContractTest {
    @Test
    fun `delegate tool exposes durable request fields`() {
        val definition = BotDelegationCoordinator.definition()
        assertEquals("delegate_bot", definition.name)
        assertEquals(listOf("tool_title", "target_bot_id", "prompt"), definition.required)
        assertTrue(definition.parameters.containsKey("target_bot_id"))
        assertTrue(definition.parameters.containsKey("prompt"))
    }

    @Test
    fun `roster and status tools expose source-scoped fields`() {
        assertEquals("list_bots", BotDelegationCoordinator.listBotsDefinition().name)
        assertEquals("check_delegation", BotDelegationCoordinator.checkDelegationDefinition().name)
        assertTrue("task_id" in BotDelegationCoordinator.checkDelegationDefinition().parameters)
    }

    @Test
    fun `same Bot turns never overlap`() = runBlocking {
        val active = AtomicInteger(0)
        val peak = AtomicInteger(0)
        val jobs = (1..2).map {
            launch {
                BotTurnLockRegistry.withLock("bot-1") {
                    val now = active.incrementAndGet()
                    peak.updateAndGet { old -> maxOf(old, now) }
                    delay(20)
                    active.decrementAndGet()
                }
            }
        }
        jobs.forEach { it.join() }
        assertEquals(1, peak.get())
    }

    @Test
    fun externalReservationIsReentrantOnlyForTargetSession() = runBlocking {
        assertTrue(BotTurnLockRegistry.tryAcquireExternal("bot-reserved", "session-a"))
        try {
            assertTrue(BotTurnLockRegistry.isLocked("bot-reserved"))
            var entered = false
            BotTurnLockRegistry.withLock("bot-reserved", "session-a") {
                entered = true
                Unit
            }
            assertTrue(entered)
            assertFalse(BotTurnLockRegistry.tryAcquireExternal("bot-reserved", "session-b"))
        } finally {
            BotTurnLockRegistry.releaseExternal("bot-reserved", "session-a")
        }
        assertFalse(BotTurnLockRegistry.isLocked("bot-reserved"))
    }

    @Test
    fun busyWaitEndsOnlyAfterTheCurrentTurnReleasesItsLock() = runBlocking {
        val botId = "bot-busy-period"
        assertTrue(BotTurnLockRegistry.tryAcquireExternal(botId, "busy-session"))
        val waiter = async(start = kotlinx.coroutines.CoroutineStart.UNDISPATCHED) {
            BotTurnLockRegistry.awaitAvailable(botId)
        }
        assertFalse(waiter.isCompleted)
        BotTurnLockRegistry.releaseExternal(botId, "different-session")
        assertFalse(waiter.isCompleted)
        BotTurnLockRegistry.releaseExternal(botId, "busy-session")
        waiter.await()
        assertFalse(BotTurnLockRegistry.isLocked(botId))
    }

    @Test
    fun `only explicit terminal states are terminal`() {
        assertTrue(BotDelegationEntity.STATUS_COMPLETED in BotDelegationEntity.TERMINAL_STATUSES)
        assertTrue(BotDelegationEntity.STATUS_FAILED in BotDelegationEntity.TERMINAL_STATUSES)
        assertTrue(BotDelegationEntity.STATUS_DENIED in BotDelegationEntity.TERMINAL_STATUSES)
        assertTrue(BotDelegationEntity.STATUS_CANCELLED in BotDelegationEntity.TERMINAL_STATUSES)
        assertTrue(BotDelegationEntity.STATUS_BUSY_GAVE_UP in BotDelegationEntity.TERMINAL_STATUSES)
        assertTrue(BotDelegationEntity.STATUS_RUNNING !in BotDelegationEntity.TERMINAL_STATUSES)
    }
}
