package com.openminis.app.tools.android

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class StagedApkKeyTest {
    private fun key(session: String?, path: String = "/workspace/app.apk", size: Long = 100, modified: Long = 5) =
        AndroidApkInspector.stagedApkKey(session, path, size, modified)

    @Test
    fun theSamePathInTwoChatsStagesTwoCopies() {
        assertNotEquals(key("chat-A"), key("chat-B"))
    }

    @Test
    fun theSameFileInTheSameChatReusesItsCopy() {
        assertEquals(key("chat-A"), key("chat-A"))
    }

    @Test
    fun aChangedFileStagesAgain() {
        assertNotEquals(key("chat-A"), key("chat-A", size = 101))
        assertNotEquals(key("chat-A"), key("chat-A", modified = 6))
        assertNotEquals(key("chat-A"), key("chat-A", path = "/workspace/other.apk"))
    }

    @Test
    fun noSessionIsItsOwnNamespace() {
        assertNotEquals(key(null), key("chat-A"))
    }
}
