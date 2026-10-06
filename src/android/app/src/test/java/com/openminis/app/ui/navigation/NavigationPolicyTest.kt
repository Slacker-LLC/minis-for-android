package com.openminis.app.ui.navigation

import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import com.openminis.app.ui.chat.ChatViewModelStore
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.advanceUntilIdle
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class NavigationPolicyTest {
    @After
    fun reset() = ChatViewModelStore.setActiveSession(null)

    private fun registry(state: Lifecycle.State): LifecycleRegistry {
        val owner = object : LifecycleOwner {
            lateinit var registry: LifecycleRegistry
            override val lifecycle: Lifecycle get() = registry
        }
        return LifecycleRegistry.createUnsafe(owner).also {
            owner.registry = it
            it.currentState = state
        }
    }

    @Test
    fun awaitResumedWaitsForTheLifecycleInsteadOfGivingUp() = runTest {
        val lifecycle = registry(Lifecycle.State.STARTED)
        var delivered = false
        val job = launch(start = CoroutineStart.UNDISPATCHED) {
            lifecycle.awaitResumed()
            delivered = true
        }
        advanceUntilIdle()
        assertFalse("must not run while the destination is only STARTED", delivered)
        lifecycle.currentState = Lifecycle.State.RESUMED
        advanceUntilIdle()
        assertTrue(delivered)
        job.join()
    }

    @Test
    fun awaitResumedReturnsAtOnceWhenAlreadyResumed() = runTest {
        val lifecycle = registry(Lifecycle.State.RESUMED)
        lifecycle.awaitResumed()
    }

    @Test
    fun awaitResumedStaysCancellableWhileWaiting() = runTest {
        val lifecycle = registry(Lifecycle.State.CREATED)
        val job = launch(start = CoroutineStart.UNDISPATCHED) { lifecycle.awaitResumed() }
        advanceUntilIdle()
        assertTrue(job.isActive)
        job.cancel()
        job.join()
        assertTrue(job.isCancelled)
    }

    @Test
    fun aWarmShareOpensAChatFromEveryPageButAChat() {
        assertFalse(shareNeedsFreshChat(Routes.CHAT))
        assertFalse(shareNeedsFreshChat(null))
        assertTrue(shareNeedsFreshChat(Routes.ASSISTANT_HOME))
        assertTrue(shareNeedsFreshChat(Routes.SETTINGS))
        assertTrue(shareNeedsFreshChat(Routes.CHAT_FILES))
        assertTrue(shareNeedsFreshChat(Routes.SESSION_LIST))
    }

    @Test
    fun theMountedChatWinsOverTheOuterRoute() {
        assertEquals("B", visibleChatSessionId(mountedChat = "B", routeChat = "A"))
        assertEquals("A", visibleChatSessionId(mountedChat = null, routeChat = "A"))
        assertNull(visibleChatSessionId(mountedChat = null, routeChat = null))
        // Chat shown inside the session list: no chat route at all.
        assertEquals("B", visibleChatSessionId(mountedChat = "B", routeChat = null))
    }

    @Test
    fun aReplacedChatScreenCannotClearItsSuccessor() {
        ChatViewModelStore.setActiveSession("A")
        ChatViewModelStore.setActiveSession("B")
        ChatViewModelStore.clearActiveSession("A")
        assertEquals("B", ChatViewModelStore.mountedSessionId.value)
        ChatViewModelStore.clearActiveSession("B")
        assertNull(ChatViewModelStore.mountedSessionId.value)
    }
}
