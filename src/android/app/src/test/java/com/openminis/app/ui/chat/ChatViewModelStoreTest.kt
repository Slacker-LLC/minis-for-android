package com.openminis.app.ui.chat

import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The store is the only owner of a session's ChatViewModel. Both delete paths
 * (session list, RPC) must release the same entry, and a draft id must reach
 * the same store as the persisted id it became.
 */
class ChatViewModelStoreTest {

    private val ids = mutableListOf<String>()

    private fun id(name: String) = "store-test-$name".also { ids += it }

    @After
    fun cleanUp() {
        ids.forEach { ChatViewModelStore.release(it) }
    }

    @Test
    fun `a store exists only after ownerFor and is gone after release`() {
        val s = id("a")
        assertFalse(ChatViewModelStore.hasStore(s))
        val first = ChatViewModelStore.ownerFor(s).viewModelStore
        assertTrue(ChatViewModelStore.hasStore(s))
        assertSame(first, ChatViewModelStore.ownerFor(s).viewModelStore)

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
    fun `releasing the persisted id also drops what the draft id reached`() {
        val draft = id("draft")
        val real = id("real")
        val store = ChatViewModelStore.ownerFor(draft).viewModelStore
        ChatViewModelStore.rename(draft, real)

        assertSame(store, ChatViewModelStore.ownerFor(draft).viewModelStore)
        assertTrue(ChatViewModelStore.hasStore(draft))

        ChatViewModelStore.release(real)
        assertFalse(ChatViewModelStore.hasStore(real))
        assertFalse(ChatViewModelStore.hasStore(draft))
    }
}
