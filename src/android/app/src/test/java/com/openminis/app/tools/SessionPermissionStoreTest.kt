package com.openminis.app.tools

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionPermissionStoreTest {
    @Test
    fun `workspace-write allows session-owned paths`() {
        assertTrue(SessionPermissionStore.isWorkspaceWritePath("notes.txt"))
        assertTrue(SessionPermissionStore.isWorkspaceWritePath("/workspace/notes.txt"))
        assertTrue(SessionPermissionStore.isWorkspaceWritePath("/var/minis/workspace/notes.txt"))
        assertTrue(SessionPermissionStore.isWorkspaceWritePath("/var/minis/attachments/photo.png"))
        assertTrue(SessionPermissionStore.isWorkspaceWritePath("/var/minis/offloads/result.json"))
        assertTrue(SessionPermissionStore.isWorkspaceWritePath("/var/minis/browser/page.html"))
    }

    @Test
    fun `workspace-write rejects global and external paths`() {
        assertFalse(SessionPermissionStore.isWorkspaceWritePath("/var/minis/shared/report.md"))
        assertFalse(SessionPermissionStore.isWorkspaceWritePath("/var/minis/skills/tool.md"))
        assertFalse(SessionPermissionStore.isWorkspaceWritePath("/var/minis/memory/GLOBAL.md"))
        assertFalse(SessionPermissionStore.isWorkspaceWritePath("/var/minis/mounts/docs/file.txt"))
        assertFalse(SessionPermissionStore.isWorkspaceWritePath("/etc/hosts"))
    }

    @Test
    fun `workspace-write rejects traversal and invalid paths`() {
        assertFalse(SessionPermissionStore.isWorkspaceWritePath("../escape.txt"))
        assertFalse(SessionPermissionStore.isWorkspaceWritePath("/workspace/../escape.txt"))
        assertFalse(SessionPermissionStore.isWorkspaceWritePath(""))
        assertFalse(SessionPermissionStore.isWorkspaceWritePath("/workspace/a\u0000b"))
    }
}
