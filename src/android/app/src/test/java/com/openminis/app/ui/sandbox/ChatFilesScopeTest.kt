package com.openminis.app.ui.sandbox

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatFilesScopeTest {
    @Test
    fun theMountPointsInsideWorkspaceAreHidden() {
        for (name in listOf("attachments", "offloads", "browser")) {
            assertTrue(name, ChatFilesScope.isMountPoint("/var/minis/workspace", name, "dir"))
        }
    }

    @Test
    fun theChatsOwnFilesAreNeverHidden() {
        assertFalse(ChatFilesScope.isMountPoint("/var/minis/workspace", "report.md", "file"))
        assertFalse(ChatFilesScope.isMountPoint("/var/minis/workspace", "motor", "dir"))
        // A file that happens to be called "attachments" is a file, not a mount point.
        assertFalse(ChatFilesScope.isMountPoint("/var/minis/workspace", "attachments", "file"))
        // Deeper folders of the same name are the user's own.
        assertFalse(ChatFilesScope.isMountPoint("/var/minis/workspace/project", "browser", "dir"))
        assertFalse(ChatFilesScope.isMountPoint("/var/minis/attachments", "browser", "dir"))
    }
}
