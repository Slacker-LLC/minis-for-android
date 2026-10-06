package com.openminis.app.config

import com.openminis.app.ui.chat.ChatViewModelStore
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ConfigCallSessionTest {

    @After
    fun reset() {
        ChatViewModelStore.setActiveSession(null)
    }

    @Test
    fun theCallersSessionWinsOverTheForegroundChat() {
        ChatViewModelStore.setActiveSession("chat-B")
        val seen = ConfigCallSession.with("chat-A") { ConfigCallSession.targetSession() }
        assertEquals("chat-A", seen)
    }

    @Test
    fun aCallWithoutASessionFallsBackToTheForegroundChat() {
        ChatViewModelStore.setActiveSession("chat-B")
        assertEquals("chat-B", ConfigCallSession.with(null) { ConfigCallSession.targetSession() })
        assertEquals("chat-B", ConfigCallSession.with("") { ConfigCallSession.targetSession() })
        assertEquals("chat-B", ConfigCallSession.targetSession())
    }

    @Test
    fun withNeitherThereIsNoTarget() {
        assertNull(ConfigCallSession.with(null) { ConfigCallSession.targetSession() })
    }

    @Test
    fun theScopeEndsWithTheBlockEvenWhenItThrows() {
        ChatViewModelStore.setActiveSession("chat-B")
        runCatching { ConfigCallSession.with("chat-A") { error("boom") } }
        assertEquals("chat-B", ConfigCallSession.targetSession())
    }

    @Test
    fun scopesNest() {
        val inner = ConfigCallSession.with("outer") {
            ConfigCallSession.with("inner") { ConfigCallSession.targetSession() } to ConfigCallSession.targetSession()
        }
        assertEquals("inner" to "outer", inner)
    }
}
