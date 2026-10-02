package com.openminis.app.backup

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The name rules of the device-folder destination. The MediaStore write itself is covered on a device by
 * BackupDeviceStorageInstrumentedTest; these are the parts that decide what may be written or deleted.
 */
class BackupDeviceStorageTest {

    @Test
    fun `an ordinary package name is accepted`() {
        assertTrue(BackupDeviceStorage.isPackageName("minis-backup-20261002-101500.minisbak"))
        assertTrue(BackupDeviceStorage.isPackageName("a.minisbak"))
        assertTrue(BackupDeviceStorage.isPackageName("backup (2).minisbak"))
    }

    @Test
    fun `names that could reach outside the folder or are not packages are refused`() {
        assertFalse("path separator", BackupDeviceStorage.isPackageName("../secret.minisbak"))
        assertFalse("nested", BackupDeviceStorage.isPackageName("sub/dir.minisbak"))
        assertFalse("backslash", BackupDeviceStorage.isPackageName("sub\\dir.minisbak"))
        assertFalse("dot dot inside", BackupDeviceStorage.isPackageName("a..b.minisbak"))
        assertFalse("hidden", BackupDeviceStorage.isPackageName(".minisbak.minisbak"))
        assertFalse("extension only", BackupDeviceStorage.isPackageName(".minisbak"))
        assertFalse("empty", BackupDeviceStorage.isPackageName(""))
        assertFalse("wrong extension", BackupDeviceStorage.isPackageName("backup.zip"))
        assertFalse("extension in the middle", BackupDeviceStorage.isPackageName("backup.minisbak.txt"))
        assertFalse("control character", BackupDeviceStorage.isPackageName("bad\nname.minisbak"))
        assertFalse("too long", BackupDeviceStorage.isPackageName("x".repeat(200) + ".minisbak"))
    }

    @Test
    fun `a taken name gets a counter and a free name is kept`() {
        assertEquals("a.minisbak", BackupDeviceStorage.uniqueName("a.minisbak") { false })
        assertEquals("a (2).minisbak", BackupDeviceStorage.uniqueName("a.minisbak") { it == "a.minisbak" })
        val taken = setOf("a.minisbak", "a (2).minisbak", "a (3).minisbak")
        assertEquals("a (4).minisbak", BackupDeviceStorage.uniqueName("a.minisbak") { it in taken })
    }

    @Test
    fun `the destination is recorded in history under its own kind`() {
        // History tells a device copy from a server by kind; the delete path relies on it.
        assertEquals("device", BackupDeviceStorage.KIND)
        assertEquals("Download/Minis Backups", BackupDeviceStorage.DISPLAY_PATH)
    }
}
