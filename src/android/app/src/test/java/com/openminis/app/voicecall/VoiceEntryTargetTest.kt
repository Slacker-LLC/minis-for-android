package com.openminis.app.voicecall

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class VoiceEntryTargetTest {
    private val draft = { "__new__fresh" }

    @Test
    fun `deep link opens a fresh draft when new chat is on even with a remembered session`() {
        assertEquals("__new__fresh", VoiceEntryTarget.forDeepLink(true, "old", draft))
    }

    @Test
    fun `deep link reuses the remembered session when new chat is off`() {
        assertEquals("old", VoiceEntryTarget.forDeepLink(false, "old", draft))
    }

    @Test
    fun `deep link falls back to a fresh draft when nothing is remembered`() {
        assertEquals("__new__fresh", VoiceEntryTarget.forDeepLink(false, null, draft))
    }

    @Test
    fun `composer button moves to a new chat from a chat with content`() {
        assertEquals("__new__fresh", VoiceEntryTarget.redirectFromComposer(true, false, false, draft))
    }

    @Test
    fun `composer button stays put when new chat is off`() {
        assertNull(VoiceEntryTarget.redirectFromComposer(false, false, false, draft))
    }

    @Test
    fun `composer button reuses a blank draft instead of stacking another`() {
        assertNull(VoiceEntryTarget.redirectFromComposer(true, false, true, draft))
    }

    @Test
    fun `an action already scoped to a session is never redirected`() {
        assertNull(VoiceEntryTarget.redirectFromComposer(true, true, false, draft))
    }
}
