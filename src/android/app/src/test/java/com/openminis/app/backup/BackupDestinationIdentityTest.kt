package com.openminis.app.backup

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BackupDestinationIdentityTest {
    private fun outcome(kind: String?, path: String?) =
        BackupHistory.DestinationOutcome("nas", succeeded = true, kind = kind, path = path)

    @Test
    fun `the server the package went to is recognised`() {
        assertTrue(BackupHistory.sameDestination(outcome("webdav", "old-folder"), "webdav", "old-folder"))
    }

    @Test
    fun `a reused name that points somewhere else is not the same destination`() {
        assertFalse("different folder", BackupHistory.sameDestination(outcome("webdav", "old-folder"), "webdav", "new-folder"))
        assertFalse("different backend", BackupHistory.sameDestination(outcome("webdav", "backups"), "sftp", "backups"))
    }

    @Test
    fun `a record that cannot be verified is not trusted`() {
        assertFalse(BackupHistory.sameDestination(outcome(null, null), "webdav", "backups"))
        assertFalse(BackupHistory.sameDestination(outcome("webdav", null), "webdav", "backups"))
        assertFalse(BackupHistory.sameDestination(outcome(null, "backups"), "webdav", "backups"))
    }
}
