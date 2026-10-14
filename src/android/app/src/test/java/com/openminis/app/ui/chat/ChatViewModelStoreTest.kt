package com.openminis.app.ui.chat

import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Test

/**
 * Process-level page projections. AgentSessionStore is the only execution
 * owner, so a projection is kept only while its runtime owner is alive: a bare
 * id with no runtime owner never keeps a cached ViewModelStore, and release
 * stays safe when nothing is owned.
 */
class ChatViewModelStoreTest {

    private val ids = mutableListOf<String>()

    private fun id(name: String) = "store-test-$name".also { ids += it }

    @After
    fun cleanUp() {
        ids.forEach { ChatViewModelStore.release(it) }
    }

    @Test
    fun `a projection without a runtime owner is not cached`() {
        val s = id("a")
        assertFalse(ChatViewModelStore.hasStore(s))
        ChatViewModelStore.ownerFor(s)
        assertFalse("no runtime owner keeps no cached projection", ChatViewModelStore.hasStore(s))

        ChatViewModelStore.release(s)
        assertFalse(ChatViewModelStore.hasStore(s))
    }

    @Test
    fun `releasing an unknown session creates nothing`() {
        val s = id("unknown")
        ChatViewModelStore.release(s)
        assertFalse(ChatViewModelStore.hasStore(s))
    }

    @Test
    fun `releasing a renamed pair without a runtime owner releases nothing`() {
        val draft = id("draft")
        val real = id("real")
        ChatViewModelStore.rename(draft, real)

        ChatViewModelStore.release(real)
        assertFalse(ChatViewModelStore.hasStore(real))
        assertFalse(ChatViewModelStore.hasStore(draft))
    }
}
