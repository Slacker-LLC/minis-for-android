package com.openminis.app.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** A message sent while the agent works either steers the running task or waits for it; only the first is due mid-task. */
class PendingDeliveryTest {
    private fun prompt(id: String, delivery: PendingDelivery) = QueuedPrompt(id = id, text = id, delivery = delivery)

    @Test
    fun `only steering prompts are due at a step boundary, in send order`() {
        val queue = listOf(
            prompt("a", PendingDelivery.QUEUE),
            prompt("b", PendingDelivery.STEER),
            prompt("c", PendingDelivery.QUEUE),
            prompt("d", PendingDelivery.STEER),
        )
        assertEquals(listOf("b", "d"), queue.dueAtStepBoundary().map { it.id })
    }

    @Test
    fun `a queue holding only waiting prompts has nothing due, so the running task is not interrupted`() {
        val queue = listOf(prompt("a", PendingDelivery.QUEUE), prompt("b", PendingDelivery.QUEUE))
        assertTrue(queue.dueAtStepBoundary().isEmpty())
        assertTrue(emptyList<QueuedPrompt>().dueAtStepBoundary().isEmpty())
    }

    @Test
    fun `a prompt steers unless it says otherwise, and the mode can be switched with copy`() {
        val p = QueuedPrompt(id = "x", text = "hi")
        assertEquals(PendingDelivery.STEER, p.delivery)
        assertEquals(PendingDelivery.QUEUE, p.copy(delivery = PendingDelivery.QUEUE).delivery)
        assertEquals(PendingDelivery.STEER, ChatMessage(id = "m", role = "user", content = "hi").queuedDelivery)
    }
}
