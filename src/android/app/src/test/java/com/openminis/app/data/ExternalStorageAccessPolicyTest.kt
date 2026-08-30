package com.openminis.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ExternalStorageAccessPolicyTest {
    @Test
    fun android11PlusRequiresAllFilesForRawBindPaths() {
        val denied = ExternalStorageAccessPolicy.evaluate(
            sdkInt = 30,
            allFilesAccess = false,
            readGranted = true,
            writeGranted = true,
            legacyStorageView = true,
        )
        assertFalse(denied.rawPathReadable)
        assertFalse(denied.rawPathWritable)
        assertEquals(
            ExternalStorageAccessPolicy.Blocker.ALL_FILES_ACCESS_REQUIRED,
            denied.blocker,
        )

        val granted = ExternalStorageAccessPolicy.evaluate(
            sdkInt = 35,
            allFilesAccess = true,
            readGranted = false,
            writeGranted = false,
            legacyStorageView = false,
        )
        assertTrue(granted.rawPathReadable)
        assertTrue(granted.rawPathWritable)
        assertEquals(ExternalStorageAccessPolicy.Blocker.NONE, granted.blocker)
    }

    @Test
    fun persistedSafStyleReadWriteFlagsDoNotBypassScopedStorageOnRPlus() {
        val access = ExternalStorageAccessPolicy.evaluate(
            sdkInt = 34,
            allFilesAccess = false,
            readGranted = true,
            writeGranted = true,
            legacyStorageView = true,
        )
        assertFalse(access.rawPathReadable)
        assertEquals(
            ExternalStorageAccessPolicy.Blocker.ALL_FILES_ACCESS_REQUIRED,
            access.blocker,
        )
    }

    @Test
    fun android10RequiresLegacyViewAndReadPermission() {
        val noLegacyView = ExternalStorageAccessPolicy.evaluate(
            sdkInt = 29,
            allFilesAccess = false,
            readGranted = true,
            writeGranted = true,
            legacyStorageView = false,
        )
        assertFalse(noLegacyView.rawPathReadable)
        assertEquals(
            ExternalStorageAccessPolicy.Blocker.LEGACY_STORAGE_VIEW_REQUIRED,
            noLegacyView.blocker,
        )

        val noRead = ExternalStorageAccessPolicy.evaluate(
            sdkInt = 29,
            allFilesAccess = false,
            readGranted = false,
            writeGranted = true,
            legacyStorageView = true,
        )
        assertFalse(noRead.rawPathReadable)
        assertEquals(
            ExternalStorageAccessPolicy.Blocker.LEGACY_READ_PERMISSION_REQUIRED,
            noRead.blocker,
        )
    }

    @Test
    fun legacyReadOnlyAccessRemainsReadableButNotWritable() {
        for (sdk in listOf(28, 29)) {
            val access = ExternalStorageAccessPolicy.evaluate(
                sdkInt = sdk,
                allFilesAccess = false,
                readGranted = true,
                writeGranted = false,
                legacyStorageView = true,
            )
            assertTrue(access.rawPathReadable)
            assertFalse(access.rawPathWritable)
            assertEquals(
                ExternalStorageAccessPolicy.Blocker.LEGACY_WRITE_PERMISSION_REQUIRED,
                access.blocker,
            )
        }
    }

    @Test
    fun legacyReadWriteAccessAllowsRawBindPath() {
        for (sdk in listOf(26, 28, 29)) {
            val access = ExternalStorageAccessPolicy.evaluate(
                sdkInt = sdk,
                allFilesAccess = false,
                readGranted = true,
                writeGranted = true,
                legacyStorageView = true,
            )
            assertTrue(access.rawPathReadable)
            assertTrue(access.rawPathWritable)
            assertEquals(ExternalStorageAccessPolicy.Blocker.NONE, access.blocker)
        }
    }
}